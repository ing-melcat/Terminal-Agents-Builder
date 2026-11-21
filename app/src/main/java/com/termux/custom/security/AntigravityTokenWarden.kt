package com.termux.custom.security

import android.content.Context
import android.system.Os
import com.termux.shared.termux.TermuxConstants
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest

data class OAuthTokenState(
    val isPresent: Boolean,
    val maskedPreview: String,
    val fingerprintSha256: String,
    val posixModeOctal: String,
    val storagePath: String,
    val syncedCliTargets: List<String>,
)

class AntigravityTokenWarden(
    private val context: Context,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private val privateTokenFile: File
        get() = File(context.filesDir, TOKEN_FILE_NAME)

    private val termuxHomeTokenFile: File
        get() = File(TermuxConstants.TERMUX_HOME_DIR_PATH, ".config/antigravity/$TOKEN_FILE_NAME")

    private val geminiHomeTokenFile: File
        get() = File(TermuxConstants.TERMUX_HOME_DIR_PATH, ".gemini/$TOKEN_FILE_NAME")

    suspend fun inspectTokenState(): OAuthTokenState = withContext(ioDispatcher) {
        val source = when {
            privateTokenFile.exists() && privateTokenFile.length() > 0 -> privateTokenFile
            termuxHomeTokenFile.exists() && termuxHomeTokenFile.length() > 0 -> termuxHomeTokenFile
            geminiHomeTokenFile.exists() && geminiHomeTokenFile.length() > 0 -> geminiHomeTokenFile
            else -> null
        }

        if (source == null) {
            return@withContext OAuthTokenState(
                isPresent = false,
                maskedPreview = "Not provisioned",
                fingerprintSha256 = "none",
                posixModeOctal = "0000",
                storagePath = privateTokenFile.absolutePath,
                syncedCliTargets = emptyList(),
            )
        }

        val rawToken = source.readText().trim()
        if (source != privateTokenFile && rawToken.isNotEmpty()) {
            writeAndLockFile(privateTokenFile, rawToken)
        } else {
            enforceMode0600(privateTokenFile)
        }

        val syncedTargets = syncToCliPathsInternal(rawToken)
        val modeOctal = readModeOctal(privateTokenFile)

        OAuthTokenState(
            isPresent = rawToken.isNotEmpty(),
            maskedPreview = maskToken(rawToken),
            fingerprintSha256 = sha256Short(rawToken),
            posixModeOctal = modeOctal,
            storagePath = privateTokenFile.absolutePath,
            syncedCliTargets = syncedTargets,
        )
    }

    suspend fun provisionOrSyncToken(rawTokenInput: String): Result<OAuthTokenState> =
        withContext(ioDispatcher) {
            runCatching {
                val clean = rawTokenInput.trim()
                require(clean.length >= 8) { "Token must be at least 8 characters" }
                writeAndLockFile(privateTokenFile, clean)
                syncToCliPathsInternal(clean)
                inspectTokenState()
            }
        }

    suspend fun revokeLocalToken(): OAuthTokenState = withContext(ioDispatcher) {
        listOf(privateTokenFile, termuxHomeTokenFile, geminiHomeTokenFile).forEach { file ->
            if (file.exists()) {
                file.writeText("")
                file.delete()
            }
        }
        inspectTokenState()
    }

    private fun syncToCliPathsInternal(token: String): List<String> {
        if (token.isEmpty()) return emptyList()
        val synced = mutableListOf<String>()
        val homeDir = File(TermuxConstants.TERMUX_HOME_DIR_PATH)
        if (homeDir.exists() || homeDir.mkdirs()) {
            listOf(termuxHomeTokenFile, geminiHomeTokenFile).forEach { target ->
                runCatching {
                    writeAndLockFile(target, token)
                    synced.add(target.absolutePath)
                }
            }
        }
        return synced
    }

    private fun writeAndLockFile(target: File, content: String) {
        target.parentFile?.let { parent ->
            if (!parent.exists()) {
                parent.mkdirs()
                runCatching { Os.chmod(parent.absolutePath, 448) } // 0700
            }
        }
        target.writeText(content + "\n")
        enforceMode0600(target)
    }

    private fun enforceMode0600(file: File) {
        if (!file.exists()) return
        runCatching {
            Os.chmod(file.absolutePath, 384) // 0600 octal = 384 decimal
        }
        file.setReadable(false, false)
        file.setWritable(false, false)
        file.setExecutable(false, false)
        file.setReadable(true, true)
        file.setWritable(true, true)
    }

    private fun readModeOctal(file: File): String =
        runCatching {
            val stat = Os.stat(file.absolutePath)
            val perms = stat.st_mode and 511 // 0777 octal
            if (perms != 0) {
                String.format("%04o", perms)
            } else if (file.canRead() && file.canWrite() && !file.canExecute()) {
                "0600"
            } else {
                "0000"
            }
        }.getOrDefault("0600")

    private fun maskToken(token: String): String =
        if (token.length <= 10) {
            "••••" + token.takeLast(2)
        } else {
            token.take(4) + "••••••••" + token.takeLast(4)
        }

    private fun sha256Short(token: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(token.toByteArray())
        return digest.take(6).joinToString("") { "%02x".format(it) }
    }

    companion object {
        const val TOKEN_FILE_NAME = "antigravity-oauth-token"
    }
}
