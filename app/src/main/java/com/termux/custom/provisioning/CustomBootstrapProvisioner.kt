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
    private const val STAMP_VERSION_FILE = ".config/melcat/.bootstrap_provisioned_v6"
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
        val agyBin = File(prefixDir, "bin/agy")
        val gcBin = File(prefixDir, "bin/melcat-gc")

        if (stampFile.isFile && agyBin.canExecute() && gcBin.canExecute()) {
            return true
        }

        if (!homeDir.exists()) {
            homeDir.mkdirs()
        }

        val geminiSkillsDir = File(homeDir, ".gemini/config/skills")
        val claudeDir = File(homeDir, ".claude")
        val claudeSkillsLink = File(claudeDir, "skills")
        val antigravityConfigDir = File(homeDir, ".config/antigravity")
        val melcatConfigDir = File(homeDir, ".config/melcat")

        geminiSkillsDir.mkdirs()
        claudeDir.mkdirs()
        antigravityConfigDir.mkdirs()
        melcatConfigDir.mkdirs()
        runCatching { Os.chmod(antigravityConfigDir.absolutePath, 448) } // 0700

        // Safely check for missing or broken symlink using Os.lstat
        runCatching {
            val lstatExists = runCatching { Os.lstat(claudeSkillsLink.absolutePath) }.isSuccess
            if (lstatExists && !claudeSkillsLink.exists()) {
                // Broken symlink — unlink before recreating
                Os.remove(claudeSkillsLink.absolutePath)
            }
            if (runCatching { Os.lstat(claudeSkillsLink.absolutePath) }.isFailure) {
                Os.symlink(geminiSkillsDir.absolutePath, claudeSkillsLink.absolutePath)
            }
        }

        // Seed core active skills if missing
        listOf("senior-auditor", "motion-framer", "caveman").forEach { skillName ->
            val dir = File(geminiSkillsDir, skillName)
            dir.mkdirs()
            val skillMd = File(dir, "SKILL.md")
            if (!skillMd.exists()) {
                skillMd.writeText(
                    """
                    ---
                    name: $skillName
                    description: Active workstation skill ($skillName) for arm64-v8a native CLI agents
                    ---
                    # $skillName
                    Auto-provisioned by Terminal Agents Builder.
                    """.trimIndent() + "\n"
                )
            }
        }

        // Extract and run bootstrap-custom.sh with explicit Termux environment and drained output
        val customDir = File(prefixDir, "etc/melcat")
        customDir.mkdirs()
        val scriptFile = File(customDir, "bootstrap-custom.sh")
        context.applicationContext.assets.open(ASSET_SCRIPT_PATH).use { input ->
            scriptFile.outputStream().use { output ->
                input.copyTo(output)
            }
        }
        runCatching { Os.chmod(scriptFile.absolutePath, 448) } // 0700

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
        val output = runCatching {
            process.inputStream.bufferedReader().use { it.readText() }
        }.getOrDefault("")
        val completed = process.waitFor(15, TimeUnit.SECONDS)
        if (!completed) {
            process.destroyForcibly()
            Logger.logError(LOG_TAG, "bootstrap-custom.sh timed out: $output")
            return false
        }
        val exitCode = process.exitValue()
        Logger.logInfo(LOG_TAG, "bootstrap-custom.sh completed with exitCode=$exitCode")
        return exitCode == 0
    }
}
