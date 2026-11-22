package com.termux.custom.security

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.system.Os
import android.system.OsConstants
import android.util.Base64
import com.termux.shared.termux.TermuxConstants
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.File
import java.io.FileOutputStream
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.ServerSocket
import java.net.URL
import java.net.URLEncoder
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermission
import java.security.MessageDigest
import java.security.SecureRandom

data class OAuthTokenState(
    val isPresent: Boolean,
    val maskedPreview: String,
    val fingerprintSha256: String,
    val posixModeOctal: String,
    val storagePath: String,
    val syncedCliTargets: List<String>,
    val accountEmail: String? = null,
)

class AntigravityTokenWarden(
    context: Context,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private val appContext: Context = context.applicationContext

    private val privateTokenFile: File
        get() = File(appContext.filesDir, TOKEN_FILE_NAME)

    private val termuxHomeTokenFile: File
        get() = File(TermuxConstants.TERMUX_HOME_DIR_PATH, ".config/antigravity/$TOKEN_FILE_NAME")

    private val geminiHomeTokenFile: File
        get() = File(TermuxConstants.TERMUX_HOME_DIR_PATH, ".gemini/$TOKEN_FILE_NAME")

    private val geminiOauthCredsFile: File
        get() = File(TermuxConstants.TERMUX_HOME_DIR_PATH, ".gemini/$GEMINI_OAUTH_CREDS_FILE")

    private val geminiAccountsFile: File
        get() = File(TermuxConstants.TERMUX_HOME_DIR_PATH, ".gemini/$GEMINI_ACCOUNTS_FILE")

    private val geminiSettingsFile: File
        get() = File(TermuxConstants.TERMUX_HOME_DIR_PATH, ".gemini/$GEMINI_SETTINGS_FILE")

    suspend fun inspectTokenState(): OAuthTokenState = withContext(ioDispatcher) {
        // Also harvest token from ~/.gemini/oauth_creds.json if the user logged in via `agy login` or `gemini` CLI
        val oauthCredsToken = extractTokenFromOauthCredsJson()
        if (!oauthCredsToken.isNullOrEmpty()) {
            val latestVaultMod = listOf(privateTokenFile, termuxHomeTokenFile, geminiHomeTokenFile)
                .filter { it.isFile && it.length() > 0L }
                .maxOfOrNull { it.lastModified() } ?: 0L
            if (geminiOauthCredsFile.lastModified() >= latestVaultMod || !privateTokenFile.isFile) {
                writeAndLockFileAtomic(privateTokenFile, oauthCredsToken)
            }
            enforceMode0600(geminiOauthCredsFile)
        }

        // Newest non-empty copy wins, so a token rotated from the CLI (`agy login`) is never
        // overwritten by a stale app-private copy. Ties prefer the app-private vault.
        val source = listOf(privateTokenFile, termuxHomeTokenFile, geminiHomeTokenFile)
            .filter { it.isFile && it.length() > 0 }
            .maxWithOrNull(compareBy<File> { it.lastModified() }.thenBy { if (it == privateTokenFile) 1 else 0 })

        if (source == null) {
            return@withContext OAuthTokenState(
                isPresent = false,
                maskedPreview = "Not configured",
                fingerprintSha256 = "none",
                posixModeOctal = "----",
                storagePath = privateTokenFile.absolutePath,
                syncedCliTargets = emptyList(),
                accountEmail = null,
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
        val modeOctal = readModeOctal(privateTokenFile)
        val cachedEmail = readCachedGoogleAccountEmail()
        val preview = if (!cachedEmail.isNullOrBlank()) {
            "$cachedEmail (${maskToken(rawToken)})"
        } else {
            maskToken(rawToken)
        }

        OAuthTokenState(
            isPresent = rawToken.isNotEmpty(),
            maskedPreview = preview,
            fingerprintSha256 = sha256Short(rawToken),
            posixModeOctal = modeOctal,
            storagePath = privateTokenFile.absolutePath,
            syncedCliTargets = syncedTargets,
            accountEmail = cachedEmail,
        )
    }

    /**
     * Authentic 1-tap Google OAuth 2.0 Web Loopback PKCE flow (compatible with @google/gemini-cli & agy).
     * Binds an ephemeral localhost server on 127.0.0.1:<port>, opens the system browser to Google Sign-In,
     * receives the OAuth authorization code on /oauth2callback, exchanges it for access + refresh tokens,
     * and writes ~/.gemini/oauth_creds.json, ~/.gemini/google_accounts.json, ~/.gemini/settings.json,
     * and antigravity-oauth-token all locked with POSIX 0600 permissions.
     */
    suspend fun signInWithGoogleOAuth(activityContext: Context): Result<OAuthTokenState> =
        withContext(ioDispatcher) {
            runCatching {
                ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { server ->
                    server.soTimeout = 180_000 // 3 minutes for user to complete Google Sign-In
                    val port = server.localPort
                    val redirectUri = "http://127.0.0.1:$port/oauth2callback"

                    val rng = SecureRandom()
                    val stateBytes = ByteArray(24).also { rng.nextBytes(it) }
                    val state = stateBytes.joinToString("") { "%02x".format(it) }

                    val verifierBytes = ByteArray(32).also { rng.nextBytes(it) }
                    val codeVerifier = Base64.encodeToString(
                        verifierBytes,
                        Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING,
                    )
                    val challengeBytes = MessageDigest.getInstance("SHA-256")
                        .digest(codeVerifier.toByteArray(Charsets.US_ASCII))
                    val codeChallenge = Base64.encodeToString(
                        challengeBytes,
                        Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING,
                    )

                    val authUrl = buildString {
                        append("https://accounts.google.com/o/oauth2/v2/auth")
                        append("?client_id=").append(urlEncode(OAUTH_CLIENT_ID))
                        append("&redirect_uri=").append(urlEncode(redirectUri))
                        append("&response_type=code")
                        append("&access_type=offline")
                        append("&prompt=consent")
                        append("&scope=").append(urlEncode(OAUTH_SCOPES))
                        append("&state=").append(urlEncode(state))
                        append("&code_challenge_method=S256")
                        append("&code_challenge=").append(urlEncode(codeChallenge))
                    }

                    val browserIntent = Intent(Intent.ACTION_VIEW, Uri.parse(authUrl)).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    activityContext.startActivity(browserIntent)

                    var authCode: String? = null
                    while (authCode == null) {
                        server.accept().use { client ->
                            client.soTimeout = 15_000
                            val reader = BufferedReader(InputStreamReader(client.getInputStream(), Charsets.UTF_8))
                            val requestLine = reader.readLine() ?: return@use
                            val pathAndQuery = requestLine.split(" ").getOrNull(1) ?: "/"
                            if (!pathAndQuery.startsWith("/oauth2callback")) {
                                writeHttpResponse(client.getOutputStream(), 404, "Not Found")
                                return@use
                            }

                            val uri = Uri.parse("http://127.0.0.1$pathAndQuery")
                            val errorParam = uri.getQueryParameter("error")
                            val returnedState = uri.getQueryParameter("state")
                            val codeParam = uri.getQueryParameter("code")

                            if (!errorParam.isNullOrBlank()) {
                                writeHttpHtml(
                                    client.getOutputStream(),
                                    400,
                                    "Autenticación cancelada ($errorParam). Puedes volver a Terminal Agents.",
                                )
                                throw IllegalStateException("Google OAuth error: $errorParam")
                            }
                            if (returnedState != state) {
                                writeHttpHtml(
                                    client.getOutputStream(),
                                    400,
                                    "State mismatch en OAuth. Intenta de nuevo desde Terminal Agents.",
                                )
                                throw IllegalStateException("OAuth state mismatch")
                            }
                            if (codeParam.isNullOrBlank()) {
                                writeHttpHtml(
                                    client.getOutputStream(),
                                    400,
                                    "No se recibió código de autorización.",
                                )
                                throw IllegalStateException("Missing OAuth authorization code")
                            }

                            writeHttpHtml(
                                client.getOutputStream(),
                                200,
                                "Autenticación completada con éxito. Volviendo a Terminal Agents Builder…",
                            )
                            authCode = codeParam
                        }
                    }

                    val tokenJson = exchangeAuthorizationCode(
                        code = authCode!!,
                        redirectUri = redirectUri,
                        codeVerifier = codeVerifier,
                    )
                    val accessToken = tokenJson.optString("access_token", "").trim()
                    val refreshToken = tokenJson.optString("refresh_token", "").trim()
                    val primaryToken = refreshToken.ifEmpty { accessToken }
                    require(primaryToken.isNotEmpty()) { "Google OAuth response did not contain a valid token" }

                    val email = fetchGoogleUserEmail(accessToken)
                    persistGeminiCliOAuthCredentials(tokenJson, email)
                    writeAndLockFileAtomic(privateTokenFile, primaryToken)
                    syncToCliPathsInternal(primaryToken)

                    // Bring WorkstationActivity back to front smoothly
                    runCatching {
                        val returnIntent = Intent().apply {
                            setClassName(appContext.packageName, "com.termux.custom.ui.WorkstationActivity")
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                        }
                        appContext.startActivity(returnIntent)
                    }

                    inspectTokenState()
                }
            }
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
            privateTokenFile,
            termuxHomeTokenFile,
            geminiHomeTokenFile,
            geminiOauthCredsFile,
            geminiAccountsFile,
        ).forEach { file ->
            secureWipeAndDelete(file)
        }
        inspectTokenState()
    }

    private fun exchangeAuthorizationCode(
        code: String,
        redirectUri: String,
        codeVerifier: String,
    ): JSONObject {
        val body = buildString {
            append("client_id=").append(urlEncode(OAUTH_CLIENT_ID))
            append("&client_secret=").append(urlEncode(OAUTH_CLIENT_SECRET))
            append("&code=").append(urlEncode(code))
            append("&code_verifier=").append(urlEncode(codeVerifier))
            append("&grant_type=authorization_code")
            append("&redirect_uri=").append(urlEncode(redirectUri))
        }.toByteArray(Charsets.UTF_8)

        val conn = (URL("https://oauth2.googleapis.com/token").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 15_000
            readTimeout = 15_000
            doOutput = true
            setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            setRequestProperty("Accept", "application/json")
        }
        return try {
            conn.outputStream.use { it.write(body) }
            val status = conn.responseCode
            val stream = if (status in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (status !in 200..299) {
                throw IllegalStateException("Token exchange failed (HTTP $status): ${text.take(180)}")
            }
            JSONObject(text)
        } finally {
            conn.disconnect()
        }
    }

    private fun fetchGoogleUserEmail(accessToken: String): String? {
        if (accessToken.isBlank()) return null
        return runCatching {
            val conn = (URL("https://www.googleapis.com/oauth2/v2/userinfo").openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 10_000
                readTimeout = 10_000
                setRequestProperty("Authorization", "Bearer $accessToken")
            }
            try {
                if (conn.responseCode in 200..299) {
                    val json = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
                    json.optString("email", "").trim().ifEmpty { null }
                } else {
                    null
                }
            } finally {
                conn.disconnect()
            }
        }.getOrNull()
    }

    private fun persistGeminiCliOAuthCredentials(tokenJson: JSONObject, email: String?) {
        val homeDir = File(TermuxConstants.TERMUX_HOME_DIR_PATH)
        if (!homeDir.exists() && !homeDir.mkdirs()) return

        val creds = JSONObject().apply {
            val access = tokenJson.optString("access_token", "")
            val refresh = tokenJson.optString("refresh_token", "")
            val scope = tokenJson.optString("scope", OAUTH_SCOPES)
            val tokenType = tokenJson.optString("token_type", "Bearer")
            val idToken = tokenJson.optString("id_token", "")
            val expiresIn = tokenJson.optLong("expires_in", 3600L)
            if (access.isNotEmpty()) put("access_token", access)
            if (refresh.isNotEmpty()) put("refresh_token", refresh)
            put("scope", scope)
            put("token_type", tokenType)
            if (idToken.isNotEmpty()) put("id_token", idToken)
            put("expiry_date", System.currentTimeMillis() + (expiresIn * 1000L))
        }
        writeAndLockFileAtomic(geminiOauthCredsFile, creds.toString(2))

        if (!email.isNullOrBlank()) {
            val accounts = JSONObject().apply {
                put("active", email)
                put("old", JSONArray())
            }
            writeAndLockFileAtomic(geminiAccountsFile, accounts.toString(2))
        }

        val settings = runCatching {
            if (geminiSettingsFile.isFile) JSONObject(geminiSettingsFile.readText()) else JSONObject()
        }.getOrDefault(JSONObject()).apply {
            put("selectedAuthType", "oauth-personal")
            val security = optJSONObject("security") ?: JSONObject()
            val auth = security.optJSONObject("auth") ?: JSONObject()
            auth.put("selectedType", "oauth-personal")
            security.put("auth", auth)
            put("security", security)
        }
        writeAndLockFileAtomic(geminiSettingsFile, settings.toString(2))
    }

    private fun extractTokenFromOauthCredsJson(): String? {
        if (!geminiOauthCredsFile.isFile || geminiOauthCredsFile.length() <= 0L) return null
        return runCatching {
            val json = JSONObject(geminiOauthCredsFile.readText())
            json.optString("refresh_token", "").trim()
                .ifEmpty { json.optString("access_token", "").trim() }
                .ifEmpty { null }
        }.getOrNull()
    }

    private fun readCachedGoogleAccountEmail(): String? {
        if (!geminiAccountsFile.isFile || geminiAccountsFile.length() <= 0L) return null
        return runCatching {
            val json = JSONObject(geminiAccountsFile.readText())
            json.optString("active", "").trim().ifEmpty { null }
        }.getOrNull()
    }

    private fun writeHttpResponse(out: java.io.OutputStream, status: Int, text: String) {
        val body = text.toByteArray(Charsets.UTF_8)
        val headers = "HTTP/1.1 $status OK\r\nContent-Type: text/plain; charset=utf-8\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n"
        out.write(headers.toByteArray(Charsets.US_ASCII))
        out.write(body)
        out.flush()
    }

    private fun writeHttpHtml(out: java.io.OutputStream, status: Int, message: String) {
        val html = """
            <!doctype html>
            <html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
            <title>Terminal Agents Builder — OAuth</title>
            <style>body{background:#05070B;color:#F1F5F9;font-family:system-ui,sans-serif;display:flex;align-items:center;justify-content:center;height:100vh;margin:0;padding:24px;text-align:center}
            .card{background:#111827;border:1px solid #38BDF8;border-radius:16px;padding:24px;max-width:420px}</style></head>
            <body><div class="card"><h3 style="color:#38BDF8;margin-top:0">Terminal Agents Builder</h3><p>$message</p></div></body></html>
        """.trimIndent()
        val body = html.toByteArray(Charsets.UTF_8)
        val headers = "HTTP/1.1 $status OK\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n"
        out.write(headers.toByteArray(Charsets.US_ASCII))
        out.write(body)
        out.flush()
    }

    private fun urlEncode(value: String): String =
        URLEncoder.encode(value, "UTF-8")

    private fun syncToCliPathsInternal(token: String): List<String> {
        if (token.isEmpty()) return emptyList()
        val synced = mutableListOf<String>()
        val homeDir = File(TermuxConstants.TERMUX_HOME_DIR_PATH)
        if (homeDir.exists() || homeDir.mkdirs()) {
            listOf(termuxHomeTokenFile, geminiHomeTokenFile).forEach { target ->
                runCatching {
                    // Skip redundant rewrites on every resume when the CLI copy is already current.
                    val current = if (target.isFile) target.readText().trim() else null
                    if (current == token) enforceMode0600(target) else writeAndLockFileAtomic(target, token)
                    synced.add(target.absolutePath)
                }
            }
            if (geminiOauthCredsFile.isFile) {
                enforceMode0600(geminiOauthCredsFile)
                synced.add(geminiOauthCredsFile.absolutePath)
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
            try {
                FileOutputStream(fd).use { out ->
                    out.write(bytes)
                    out.fd.sync()
                }
            } finally {
                runCatching { Os.close(fd) }
            }
            Os.chmod(tmpFile.absolutePath, 384)
            Os.rename(tmpFile.absolutePath, target.absolutePath)
            target.isFile && target.length() > 0L
        }.getOrDefault(false)

        if (!openedWithOs) {
            // Fallback for JVM/Robolectric test runner where Os.open/Os.rename may be stubbed
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
        const val GEMINI_OAUTH_CREDS_FILE = "oauth_creds.json"
        const val GEMINI_ACCOUNTS_FILE = "google_accounts.json"
        const val GEMINI_SETTINGS_FILE = "settings.json"

        // Official Google OAuth 2.0 installed-app CLI credentials used by @google/gemini-cli
        const val OAUTH_CLIENT_ID = "681255809395-oo8ft2oprdrnp9e3aqf6av3hmdib135j.apps.googleusercontent.com"
        const val OAUTH_CLIENT_SECRET = "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"
        const val OAUTH_SCOPES =
            "https://www.googleapis.com/auth/cloud-platform https://www.googleapis.com/auth/userinfo.email https://www.googleapis.com/auth/userinfo.profile"
    }
}
