package com.termux.custom.security

import android.content.Context
import android.system.Os
import android.system.OsConstants
import com.termux.shared.termux.TermuxConstants
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermission
import java.security.MessageDigest

data class OAuthTokenState(
    val isPresent: Boolean,
    val maskedPreview: String,
    val fingerprintSha256: String,
    val posixModeOctal: String,
    val storagePath: String,
    val syncedCliTargets: List<String>,
)

/**
 * Protects and synchronizes the real Google Antigravity CLI (`agy`) OAuth token:
 *   - Official CLI path: ~/.gemini/antigravity-cli/antigravity-oauth-token (0600)
 *   - Legacy/compat mirrors: ~/.config/antigravity/antigravity-oauth-token, ~/.gemini/antigravity-oauth-token
 *   - Private app vault: $APP_FILES_DIR/antigravity-oauth-token (0600)
 *
 * When the user signs in using the real `agy` CLI (`agy` -> Google Sign-In),
 * `inspectTokenState()` automatically detects ~/.gemini/antigravity-cli/antigravity-oauth-token,
 * locks it to 0600, and reflects it in the Shield panel without overwriting newer tokens.
 */
class AntigravityTokenWarden(
    context: Context,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private val appContext: Context = context.applicationContext

    private val privateTokenFile: File
        get() = File(appContext.filesDir, TOKEN_FILE_NAME)

    /** Official path where Google Antigravity CLI (`agy`) persists its OAuth token on Linux/Termux. */
    private val officialAgyCliTokenFile: File
        get() = File(TermuxConstants.TERMUX_HOME_DIR_PATH, ".gemini/antigravity-cli/$TOKEN_FILE_NAME")

    private val termuxHomeTokenFile: File
        get() = File(TermuxConstants.TERMUX_HOME_DIR_PATH, ".config/antigravity/$TOKEN_FILE_NAME")

    private val geminiHomeTokenFile: File
        get() = File(TermuxConstants.TERMUX_HOME_DIR_PATH, ".gemini/$TOKEN_FILE_NAME")

    suspend fun inspectTokenState(): OAuthTokenState = withContext(ioDispatcher) {
        // Newest non-empty copy wins (so signing in via the real `agy` CLI immediately updates the vault).
        // On ties, prefer the official Antigravity CLI path, then the app-private vault.
        val source = listOf(officialAgyCliTokenFile, privateTokenFile, termuxHomeTokenFile, geminiHomeTokenFile)
            .filter { it.isFile && it.length() > 0L }
            .maxWithOrNull(
                compareBy<File> { it.lastModified() }
                    .thenBy {
                        when (it) {
                            officialAgyCliTokenFile -> 2
                            privateTokenFile -> 1
                            else -> 0
                        }
                    }
            )

        if (source == null) {
            return@withContext OAuthTokenState(
                isPresent = false,
                maskedPreview = "Not configured",
                fingerprintSha256 = "none",
                posixModeOctal = "----",
                storagePath = officialAgyCliTokenFile.absolutePath,
                syncedCliTargets = emptyList(),
            )
        }

        val rawToken = source.readText().trim()
        val privateCurrent = if (privateTokenFile.isFile) privateTokenFile.readText().trim() else null
        if (source != privateTokenFile && rawToken.isNotEmpty() && privateCurrent != rawToken) {
            writeAndLockFileAtomic(privateTokenFile, rawToken)
        } else {
            enforceMode0600(privateTokenFile)
        }

        val syncedTargets = syncToCliPathsInternal(rawToken)
        val modeOctal = readModeOctal(if (officialAgyCliTokenFile.isFile) officialAgyCliTokenFile else privateTokenFile)

        OAuthTokenState(
            isPresent = rawToken.isNotEmpty(),
            maskedPreview = maskToken(rawToken),
            fingerprintSha256 = sha256Short(rawToken),
            posixModeOctal = modeOctal,
            storagePath = officialAgyCliTokenFile.absolutePath,
            syncedCliTargets = syncedTargets,
        )
    }

    suspend fun provisionOrSyncToken(rawTokenInput: String): Result<OAuthTokenState> =
        withContext(ioDispatcher) {
            runCatching {
                val clean = rawTokenInput.trim()
                require(clean.length >= 8) { "Token must be at least 8 characters" }
                require(clean.none { it.isISOControl() || it.isWhitespace() }) {
                    "Token cannot contain spaces or control characters"
                }
                writeAndLockFileAtomic(privateTokenFile, clean)
                syncToCliPathsInternal(clean)
                inspectTokenState()
            }
        }

    suspend fun revokeLocalToken(): OAuthTokenState = withContext(ioDispatcher) {
        listOf(
            officialAgyCliTokenFile,
            privateTokenFile,
            termuxHomeTokenFile,
            geminiHomeTokenFile,
        ).forEach { file ->
            secureWipeAndDelete(file)
        }
        inspectTokenState()
    }

    private fun syncToCliPathsInternal(token: String): List<String> {
        if (token.isEmpty()) return emptyList()
        val synced = mutableListOf<String>()
        val homeDir = File(TermuxConstants.TERMUX_HOME_DIR_PATH)
        if (homeDir.exists() || homeDir.mkdirs()) {
            listOf(officialAgyCliTokenFile, termuxHomeTokenFile, geminiHomeTokenFile).forEach { target ->
                runCatching {
                    val current = if (target.isFile) target.readText().trim() else null
                    if (current == token) enforceMode0600(target) else writeAndLockFileAtomic(target, token)
                    synced.add(target.absolutePath)
                }
            }
        }
        return synced
    }

    private fun writeAndLockFileAtomic(target: File, content: String) {
        val parent = target.parentFile ?: return
        if (!parent.exists()) {
            parent.mkdirs()
        }
        runCatching { Os.chmod(parent.absolutePath, 448) } // always enforce 0700 on parent dir

        val tmpFile = File(parent, ".${target.name}.tmp.${System.nanoTime()}")
        val bytes = (content + "\n").toByteArray(Charsets.UTF_8)

        val openedWithOs = runCatching {
            val fd = Os.open(
                tmpFile.absolutePath,
                OsConstants.O_WRONLY or OsConstants.O_CREAT or OsConstants.O_TRUNC or OsConstants.O_NOFOLLOW,
                384, // 0600 from creation (zero TOCTOU)
            )
            check(fd != null && fd.valid()) { "Os.open unavailable" }
            try {
                FileOutputStream(fd).use { out ->
                    out.write(bytes)
                    out.fd.sync()
                }
            } finally {
                runCatching { Os.close(fd) }
            }
            check(tmpFile.isFile && tmpFile.length() == bytes.size.toLong()) { "Os.open write incomplete" }
            Os.chmod(tmpFile.absolutePath, 384)
            Os.rename(tmpFile.absolutePath, target.absolutePath)
            !tmpFile.exists() && target.isFile && target.length() == bytes.size.toLong()
        }.getOrDefault(false)

        if (!openedWithOs) {
            // Fallback for JVM/Robolectric test runner where Os.open/Os.rename may be stubbed
            try {
                FileOutputStream(tmpFile).use { out ->
                    out.write(bytes)
                    out.fd.sync()
                }
                enforceMode0600(tmpFile)
                if (!tmpFile.renameTo(target)) {
                    tmpFile.copyTo(target, overwrite = true)
                    tmpFile.delete()
                }
                enforceMode0600(target)
            } finally {
                if (tmpFile.exists()) runCatching { tmpFile.delete() }
            }
        }
    }

    private fun enforceMode0600(file: File) {
        if (!file.exists()) return
        runCatching {
            Os.chmod(file.absolutePath, 384) // 0600 octal
        }
        file.setReadable(false, false)
        file.setWritable(false, false)
        file.setExecutable(false, false)
        file.setReadable(true, true)
        file.setWritable(true, true)
    }

    private fun secureWipeAndDelete(file: File) {
        if (!file.exists()) return
        runCatching {
            val len = file.length().coerceIn(0L, 65_536L).toInt()
            if (len > 0) {
                FileOutputStream(file).use { out ->
                    out.write(ByteArray(len))
                    out.fd.sync()
                }
            }
        }
        file.delete()
    }

    private fun readModeOctal(file: File): String {
        if (!file.exists()) return "----"
        val osMode = runCatching {
            val stat = Os.stat(file.absolutePath)
            stat.st_mode and 511
        }.getOrNull()

        if (osMode != null && osMode != 0) {
            return String.format("%04o", osMode)
        }

        // Honest NIO POSIX fallback for Robolectric JVM
        return runCatching {
            val perms = Files.getPosixFilePermissions(file.toPath())
            var bits = 0
            if (PosixFilePermission.OWNER_READ in perms) bits = bits or 256
            if (PosixFilePermission.OWNER_WRITE in perms) bits = bits or 128
            if (PosixFilePermission.OWNER_EXECUTE in perms) bits = bits or 64
            if (PosixFilePermission.GROUP_READ in perms) bits = bits or 32
            if (PosixFilePermission.GROUP_WRITE in perms) bits = bits or 16
            if (PosixFilePermission.GROUP_EXECUTE in perms) bits = bits or 8
            if (PosixFilePermission.OTHERS_READ in perms) bits = bits or 4
            if (PosixFilePermission.OTHERS_WRITE in perms) bits = bits or 2
            if (PosixFilePermission.OTHERS_EXECUTE in perms) bits = bits or 1
            String.format("%04o", bits)
        }.getOrDefault("ERR")
    }

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
