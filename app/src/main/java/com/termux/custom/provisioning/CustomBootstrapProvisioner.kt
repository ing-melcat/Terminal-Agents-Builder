package com.termux.custom.provisioning

import android.content.Context
import android.system.Os
import com.termux.shared.logger.Logger
import com.termux.shared.termux.TermuxConstants
import java.io.File

object CustomBootstrapProvisioner {

    private const val LOG_TAG = "CustomBootstrapProvisioner"
    private const val ASSET_SCRIPT_PATH = "custom/bootstrap-custom.sh"

    /**
     * Invoked strictly AFTER TermuxInstaller atomic rename ($STAGING_PREFIX -> $PREFIX) completes.
     * Performs idempotent setup of ~/.gemini/config/skills, symlink to ~/.claude/skills,
     * melcat-gc binary, and shell environment for agy & claude CLI.
     */
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

    @JvmStatic
    fun provisionSynchronously(context: Context): Boolean {
        val prefixDir = File(TermuxConstants.TERMUX_PREFIX_DIR_PATH)
        val homeDir = File(TermuxConstants.TERMUX_HOME_DIR_PATH)
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

        // Ensure ~/.claude/skills -> ~/.gemini/config/skills symlink
        runCatching {
            if (!claudeSkillsLink.exists()) {
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

        // Extract and execute bootstrap-custom.sh if $PREFIX/bin/sh is available
        val customDir = File(prefixDir, "etc/melcat")
        customDir.mkdirs()
        val scriptFile = File(customDir, "bootstrap-custom.sh")
        context.assets.open(ASSET_SCRIPT_PATH).use { input ->
            scriptFile.outputStream().use { output ->
                input.copyTo(output)
            }
        }
        runCatching { Os.chmod(scriptFile.absolutePath, 448) } // 0700

        val shBinary = File(prefixDir, "bin/sh")
        if (shBinary.canExecute()) {
            val process = ProcessBuilder(shBinary.absolutePath, scriptFile.absolutePath)
                .directory(homeDir)
                .redirectErrorStream(true)
                .start()
            val exitCode = process.waitFor()
            Logger.logInfo(LOG_TAG, "bootstrap-custom.sh completed with exitCode=$exitCode")
            return exitCode == 0
        }
        return true
    }
}
