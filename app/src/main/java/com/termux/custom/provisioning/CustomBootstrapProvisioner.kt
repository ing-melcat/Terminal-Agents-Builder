package com.termux.custom.provisioning

import android.content.Context
import android.system.Os
import com.termux.shared.logger.Logger
import com.termux.shared.termux.TermuxConstants
import java.io.File
import java.util.concurrent.TimeUnit

object CustomBootstrapProvisioner {

    private const val LOG_TAG = "CustomBootstrapProvisioner"
    private const val ASSET_SCRIPT_PATH = "custom/bootstrap-custom.sh"
    private const val STAMP_VERSION_FILE = ".config/melcat/.bootstrap_provisioned_v12"
    private val provisioningLock = Any()

    private fun hasValidPrefixBinaries(): Boolean {
        val prefixDir = File(TermuxConstants.TERMUX_PREFIX_DIR_PATH)
        val shBinary = File(prefixDir, "bin/sh")
        val loginBinary = File(prefixDir, "bin/login")
        return prefixDir.isDirectory &&
            shBinary.isFile && shBinary.canExecute() &&
            loginBinary.isFile && loginBinary.canExecute()
    }

    /**
     * Returns true ONLY when TermuxInstaller's atomic bootstrap extraction has completed
     * (not currently in progress) and both $PREFIX/bin/sh and $PREFIX/bin/login are executable.
     */
    @JvmStatic
    fun isBootstrapInstalled(): Boolean {
        if (com.termux.app.TermuxInstaller.isBootstrapInstallInProgress()) return false
        return hasValidPrefixBinaries()
    }

    @JvmStatic
    fun runPostBootstrapProvisioningAsync(context: Context) {
        val appContext = context.applicationContext
        Thread(
            {
                runCatching {
                    provisionSynchronously(appContext)
                }.onFailure { err ->
                    Logger.logStackTraceWithMessage(
                        LOG_TAG,
                        "Custom post-bootstrap provisioning encountered non-fatal error",
                        err,
                    )
                }
            },
            "CustomBootstrapProvisionerThread",
        ).start()
    }

    /**
     * Synchronized, idempotent post-bootstrap setup.
     * Refuses to write inside $PREFIX if Termux bootstrap binaries (bin/sh + bin/login) are not present.
     */
    @JvmStatic
    fun provisionSynchronously(context: Context): Boolean = synchronized(provisioningLock) {
        if (!hasValidPrefixBinaries()) {
            Logger.logInfo(LOG_TAG, "Deferring custom provisioning: Termux bootstrap not yet installed.")
            return false
        }

        val prefixDir = File(TermuxConstants.TERMUX_PREFIX_DIR_PATH)
        val homeDir = File(TermuxConstants.TERMUX_HOME_DIR_PATH)
        val stampFile = File(homeDir, STAMP_VERSION_FILE)
        val workstationBin = File(prefixDir, "bin/workstation")
        val gcBin = File(prefixDir, "bin/melcat-gc")

        if (stampFile.isFile && workstationBin.canExecute() && gcBin.canExecute()) {
            return true
        }

        if (!homeDir.exists()) {
            homeDir.mkdirs()
        }

        val geminiSkillsDir = File(homeDir, ".gemini/config/skills")
        val agyCliDir = File(homeDir, ".gemini/antigravity-cli")
        val opencodeConfigDir = File(homeDir, ".config/opencode")
        val claudeDir = File(homeDir, ".claude")
        val antigravityConfigDir = File(homeDir, ".config/antigravity")
        val melcatConfigDir = File(homeDir, ".config/melcat")
        val melcatVaultDir = File(homeDir, ".config/melcat/vault")

        geminiSkillsDir.mkdirs()
        agyCliDir.mkdirs()
        opencodeConfigDir.mkdirs()
        claudeDir.mkdirs()
        antigravityConfigDir.mkdirs()
        melcatConfigDir.mkdirs()
        melcatVaultDir.mkdirs()
        runCatching { Os.chmod(antigravityConfigDir.absolutePath, 448) } // 0700
        runCatching { Os.chmod(agyCliDir.absolutePath, 448) } // 0700
        runCatching { Os.chmod(melcatVaultDir.absolutePath, 448) } // 0700

        // Safely link ~/.gemini/antigravity-cli/skills, ~/.config/opencode/skills, and ~/.claude/skills -> ~/.gemini/config/skills
        listOf(
            File(claudeDir, "skills"),
            File(agyCliDir, "skills"),
            File(opencodeConfigDir, "skills"),
        ).forEach { link ->
            runCatching {
                val lstatExists = runCatching { Os.lstat(link.absolutePath) }.isSuccess
                if (lstatExists && !link.exists()) {
                    Os.remove(link.absolutePath)
                }
                if (runCatching { Os.lstat(link.absolutePath) }.isFailure) {
                    Os.symlink(geminiSkillsDir.absolutePath, link.absolutePath)
                }
            }
        }

        // Extract and run bootstrap-custom.sh (single source of truth for rich SKILL.md seeds & CLI scripts)
        val customDir = File(prefixDir, "etc/melcat")
        customDir.mkdirs()
        val scriptFile = File(customDir, "bootstrap-custom.sh")
        val scriptTmp = File(customDir, "bootstrap-custom.sh.tmp")
        context.applicationContext.assets.open(ASSET_SCRIPT_PATH).use { input ->
            scriptTmp.outputStream().use { output ->
                input.copyTo(output)
            }
        }
        runCatching { Os.chmod(scriptTmp.absolutePath, 448) } // 0700
        if (!scriptTmp.renameTo(scriptFile)) {
            scriptTmp.copyTo(scriptFile, overwrite = true)
            scriptTmp.delete()
            runCatching { Os.chmod(scriptFile.absolutePath, 448) }
        }

        val shBinary = File(prefixDir, "bin/sh")
        val tmpDir = File(prefixDir, "tmp").apply { mkdirs() }
        val builder = ProcessBuilder(shBinary.absolutePath, scriptFile.absolutePath)
            .directory(homeDir)
            .redirectErrorStream(true)
        builder.environment().apply {
            put("PREFIX", prefixDir.absolutePath)
            put("HOME", homeDir.absolutePath)
            put("TMPDIR", tmpDir.absolutePath)
            put("PATH", "${prefixDir.absolutePath}/bin:/system/bin")
        }

        val process = builder.start()
        val outputBuffer = StringBuilder()
        val readerThread = Thread(
            {
                runCatching {
                    process.inputStream.bufferedReader().use { reader ->
                        val chunk = CharArray(512)
                        while (true) {
                            val n = reader.read(chunk)
                            if (n < 0) break
                            synchronized(outputBuffer) {
                                if (outputBuffer.length < 4096) {
                                    outputBuffer.append(chunk, 0, n.coerceAtMost(4096 - outputBuffer.length))
                                }
                            }
                        }
                    }
                }
            },
            "BootstrapStdoutDrainer",
        ).apply {
            isDaemon = true
            start()
        }

        val completed = try {
            runCatching { process.outputStream.close() }
            process.waitFor(15, TimeUnit.SECONDS)
        } finally {
            if (process.isAlive) {
                process.destroyForcibly()
            }
            runCatching { process.inputStream.close() }
            runCatching { readerThread.join(500) }
        }
        if (!completed) {
            val partial = synchronized(outputBuffer) { outputBuffer.toString() }
            Logger.logError(LOG_TAG, "bootstrap-custom.sh timed out: $partial")
            return false
        }
        val exitCode = process.exitValue()
        Logger.logInfo(LOG_TAG, "bootstrap-custom.sh completed with exitCode=$exitCode")
        return exitCode == 0
    }
}
