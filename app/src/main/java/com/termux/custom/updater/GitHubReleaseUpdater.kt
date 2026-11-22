package com.termux.custom.updater

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.system.Os
import androidx.core.content.FileProvider
import com.termux.BuildConfig
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

data class ReleaseInfo(
    val tagName: String,
    val title: String,
    val releaseNotes: String,
    val apkDownloadUrl: String,
    val sha256Url: String?,
    val htmlUrl: String,
    val matchedAbi: String,
)

sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState
    data class UpToDate(val currentTag: String) : UpdateState
    data class UpdateAvailable(val release: ReleaseInfo) : UpdateState
    data class Downloading(val release: ReleaseInfo, val progressPercent: Int) : UpdateState
    data class ReadyToInstall(
        val release: ReleaseInfo,
        val apkFile: File,
        val sha256Verified: Boolean,
    ) : UpdateState
    data class PrivateRepoAuthRequired(val releasesWebUrl: String) : UpdateState
    data class Error(val message: String) : UpdateState
}

class GitHubReleaseUpdater(
    context: Context,
    private val currentTag: String = BuildConfig.WORKSTATION_RELEASE_TAG,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private val appContext = context.applicationContext
    private val tokenFile: File
        get() = File(appContext.filesDir, OTA_TOKEN_FILE)

    private val _state = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val state: StateFlow<UpdateState> = _state.asStateFlow()

    private val _hasPrivateRepoToken = MutableStateFlow(false)
    val hasPrivateRepoTokenFlow: StateFlow<Boolean> = _hasPrivateRepoToken.asStateFlow()

    val installedTag: String
        get() = currentTag

    fun hasPrivateRepoToken(): Boolean = tokenFile.isFile && tokenFile.length() > 0L

    suspend fun refreshTokenState(): Boolean = withContext(ioDispatcher) {
        val present = hasPrivateRepoToken()
        _hasPrivateRepoToken.value = present
        present
    }

    suspend fun savePrivateRepoToken(rawToken: String) = withContext(ioDispatcher) {
        val clean = rawToken.trim()
        if (clean.isEmpty()) {
            tokenFile.delete()
            _hasPrivateRepoToken.value = false
            return@withContext
        }
        tokenFile.writeText(clean + "\n")
        runCatching { Os.chmod(tokenFile.absolutePath, 384) } // 0600
        tokenFile.setReadable(false, false)
        tokenFile.setWritable(false, false)
        tokenFile.setReadable(true, true)
        tokenFile.setWritable(true, true)
        _hasPrivateRepoToken.value = true
    }

    private fun readPrivateRepoToken(): String? =
        tokenFile.takeIf { it.isFile && it.length() > 0L }
            ?.let { runCatching { it.readText().trim().ifEmpty { null } }.getOrNull() }

    suspend fun checkForUpdate(silentOnNetworkError: Boolean = false) {
        if (_state.value is UpdateState.Downloading) return
        _state.value = UpdateState.Checking

        val token = withContext(ioDispatcher) { readPrivateRepoToken() }
        val deviceAbis = Build.SUPPORTED_ABIS?.toList().orEmpty().ifEmpty { listOf("arm64-v8a") }

        val result = withContext(ioDispatcher) {
            runCatching {
                val (code, body) = httpGetResponse(LATEST_RELEASE_API_URL, token)
                if (code == 404 || code == 401) {
                    throw PrivateRepoException()
                }
                if (code !in 200..299) {
                    throw IllegalStateException("GitHub API HTTP $code")
                }
                parseLatestReleaseJson(body, deviceAbis)
            }
        }

        result.onSuccess { release ->
            if (isRemoteTagNewer(currentTag, release.tagName)) {
                _state.value = UpdateState.UpdateAvailable(release)
            } else {
                _state.value = UpdateState.UpToDate(currentTag)
            }
        }.onFailure { err ->
            if (err is PrivateRepoException) {
                _state.value = if (silentOnNetworkError) {
                    UpdateState.UpToDate(currentTag)
                } else {
                    UpdateState.PrivateRepoAuthRequired(RELEASES_WEB_URL)
                }
            } else if (silentOnNetworkError) {
                _state.value = UpdateState.UpToDate(currentTag)
            } else {
                _state.value = UpdateState.Error(err.message ?: "Unable to reach GitHub Releases")
            }
        }
    }

    suspend fun downloadAndVerifyUpdate(release: ReleaseInfo) {
        // Guard against rapid double taps starting concurrent writers on the same .part file.
        if (_state.value is UpdateState.Downloading) return
        _state.value = UpdateState.Downloading(release, 0)
        val token = withContext(ioDispatcher) { readPrivateRepoToken() }

        val result = withContext(ioDispatcher) {
            runCatching {
                val updatesDir = File(appContext.cacheDir, "updates").apply { mkdirs() }
                updatesDir.listFiles()?.forEach { it.delete() }

                val safeTag = release.tagName.replace(Regex("[^A-Za-z0-9._-]"), "_")
                val partFile = File(updatesDir, "workstation-$safeTag.apk.part")
                val finalFile = File(updatesDir, "workstation-$safeTag.apk")

                // 1. Fetch optional SHA-256 checksum
                val expectedSha256 = release.sha256Url?.let { url ->
                    runCatching {
                        val (code, text) = httpGetResponse(url, token)
                        if (code in 200..299) {
                            text.trim().split(Regex("\\s+")).firstOrNull()?.lowercase()
                        } else null
                    }.getOrNull()
                }

                // 2. Stream APK with live progress & SHA-256 verification
                val conn = openConnectionFollowingRedirects(
                    rawUrl = release.apkDownloadUrl,
                    bearerToken = token,
                    acceptHeader = "application/octet-stream",
                )
                val totalLength = conn.contentLengthLong
                val digest = MessageDigest.getInstance("SHA-256")

                try {
                    BufferedInputStream(conn.inputStream).use { input ->
                        FileOutputStream(partFile).use { output ->
                            val buffer = ByteArray(16_384)
                            var downloaded = 0L
                            var lastPercent = -1
                            while (true) {
                                val read = input.read(buffer)
                                if (read == -1) break
                                output.write(buffer, 0, read)
                                digest.update(buffer, 0, read)
                                downloaded += read
                                if (totalLength > 0L) {
                                    val pct = ((downloaded * 100L) / totalLength).toInt().coerceIn(0, 99)
                                    if (pct != lastPercent) {
                                        lastPercent = pct
                                        _state.value = UpdateState.Downloading(release, pct)
                                    }
                                }
                            }
                            output.fd.sync()
                        }
                    }
                } finally {
                    conn.disconnect()
                }

                val actualSha256 = digest.digest().joinToString("") { "%02x".format(it) }
                val verified = if (!expectedSha256.isNullOrBlank() && expectedSha256.length == 64) {
                    if (!actualSha256.equals(expectedSha256, ignoreCase = true)) {
                        partFile.delete()
                        throw SecurityException("SHA-256 mismatch — update aborted for safety.")
                    }
                    true
                } else {
                    false
                }

                if (!partFile.renameTo(finalFile)) {
                    partFile.copyTo(finalFile, overwrite = true)
                    partFile.delete()
                }
                Pair(finalFile, verified)
            }
        }

        result.onSuccess { (apkFile, verified) ->
            _state.value = UpdateState.ReadyToInstall(release, apkFile, verified)
        }.onFailure { err ->
            _state.value = UpdateState.Error(err.message ?: "Download failed")
        }
    }

    /**
     * Launches Android's native Package Installer on the downloaded APK via FileProvider.
     * Works consistently across Android 7 through Android 15 (minSdk 24 / targetSdk 28).
     */
    fun launchApkInstaller(context: Context, apkFile: File): Boolean {
        if (!apkFile.isFile) {
            _state.value = UpdateState.Error("Update APK file missing")
            return false
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !context.packageManager.canRequestPackageInstalls()) {
            val permIntent = Intent(
                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:${context.packageName}"),
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            runCatching { context.startActivity(permIntent) }
            return false
        }

        return runCatching {
            val authority = "${context.packageName}.custom.updater.fileprovider"
            val contentUri = FileProvider.getUriForFile(context, authority, apkFile)
            val installIntent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(contentUri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(installIntent)
            true
        }.getOrElse { err ->
            _state.value = UpdateState.Error("Installer failed: ${err.message ?: err.javaClass.simpleName}")
            false
        }
    }

    fun openReleasesInBrowser(context: Context, url: String = RELEASES_WEB_URL) {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }
    }

    private fun openConnectionFollowingRedirects(
        rawUrl: String,
        bearerToken: String?,
        acceptHeader: String,
    ): HttpURLConnection {
        var currentUrl = rawUrl
        var redirects = 0
        while (redirects < 6) {
            val parsed = URL(currentUrl)
            val isGitHubHost = parsed.host.endsWith("github.com") || parsed.host.endsWith("githubusercontent.com")
            val conn = (parsed.openConnection() as HttpURLConnection).apply {
                connectTimeout = 10_000
                readTimeout = 25_000
                instanceFollowRedirects = false
                setRequestProperty("User-Agent", "Terminal-Agents-Builder/$currentTag")
                setRequestProperty("Accept", acceptHeader)
                if (!bearerToken.isNullOrBlank() && isGitHubHost) {
                    setRequestProperty("Authorization", "Bearer $bearerToken")
                }
            }
            val code = conn.responseCode
            if (code in 301..308) {
                val location = conn.getHeaderField("Location")
                conn.disconnect()
                if (location.isNullOrBlank()) throw IllegalStateException("Redirect missing Location")
                currentUrl = location
                redirects++
            } else {
                return conn
            }
        }
        throw IllegalStateException("Too many redirects")
    }

    private fun httpGetResponse(url: String, bearerToken: String?): Pair<Int, String> {
        val conn = openConnectionFollowingRedirects(
            rawUrl = url,
            bearerToken = bearerToken,
            acceptHeader = "application/vnd.github+json",
        )
        return try {
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val body = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            Pair(code, body)
        } finally {
            conn.disconnect()
        }
    }

    private class PrivateRepoException : Exception("Private repository requires read-only GitHub token")

    companion object {
        const val REPO_OWNER = "ing-melcat"
        const val REPO_NAME = "Terminal-Agents-Builder"
        const val OTA_TOKEN_FILE = "github-ota-token"
        const val RELEASES_WEB_URL = "https://github.com/$REPO_OWNER/$REPO_NAME/releases"
        const val LATEST_RELEASE_API_URL =
            "https://api.github.com/repos/$REPO_OWNER/$REPO_NAME/releases/latest"

        fun parseLatestReleaseJson(
            jsonText: String,
            supportedAbis: List<String> = listOf("arm64-v8a"),
        ): ReleaseInfo {
            val root = JSONObject(jsonText)
            val tagName = root.getString("tag_name").trim()
            val title = root.optString("name", tagName).ifBlank { tagName }
            val body = root.optString("body", "")
            val htmlUrl = root.optString(
                "html_url",
                "https://github.com/$REPO_OWNER/$REPO_NAME/releases/tag/$tagName",
            )

            // Match the device's highest-priority ABI (arm64-v8a, armeabi-v7a, x86_64, x86, or universal)
            val abiPreference = supportedAbis + "universal"
            var matchedApkUrl: String? = null
            var matchedShaUrl: String? = null
            var matchedAbi = "arm64-v8a"

            val assets = root.optJSONArray("assets")
            if (assets != null) {
                val assetMap = mutableMapOf<String, String>()
                for (i in 0 until assets.length()) {
                    val asset = assets.optJSONObject(i) ?: continue
                    val name = asset.optString("name", "")
                    val url = asset.optString("browser_download_url", "")
                    if (name.isNotEmpty() && url.isNotEmpty()) {
                        assetMap[name] = url
                    }
                }
                for (abi in abiPreference) {
                    val apkEntry = assetMap.entries.firstOrNull { it.key.endsWith("_$abi.apk") }
                    if (apkEntry != null) {
                        matchedApkUrl = apkEntry.value
                        matchedShaUrl = assetMap["${apkEntry.key}.sha256"]
                        matchedAbi = abi
                        break
                    }
                }
            }

            // Fallback to immutable tag raw URL inside releases/ (works with private PAT via raw.githubusercontent.com)
            if (matchedApkUrl == null) {
                matchedAbi = if ("arm64-v8a" in supportedAbis) "arm64-v8a" else "universal"
                val apkFileName = "termux-app_apt-android-7-debug_arm64-v8a.apk"
                matchedApkUrl = "https://raw.githubusercontent.com/$REPO_OWNER/$REPO_NAME/$tagName/releases/$apkFileName"
                matchedShaUrl = "https://raw.githubusercontent.com/$REPO_OWNER/$REPO_NAME/$tagName/releases/$apkFileName.sha256"
            }

            return ReleaseInfo(
                tagName = tagName,
                title = title,
                releaseNotes = body,
                apkDownloadUrl = matchedApkUrl,
                sha256Url = matchedShaUrl,
                htmlUrl = htmlUrl,
                matchedAbi = matchedAbi,
            )
        }

        fun isRemoteTagNewer(currentTag: String, remoteTag: String): Boolean {
            val cur = parseTagTuple(currentTag)
            val rem = parseTagTuple(remoteTag)
            for (i in 0 until maxOf(cur.size, rem.size)) {
                val c = cur.getOrElse(i) { 0 }
                val r = rem.getOrElse(i) { 0 }
                if (r != c) return r > c
            }
            return false
        }

        private fun parseTagTuple(tag: String): List<Int> =
            Regex("\\d+").findAll(tag).mapNotNull { it.value.toIntOrNull() }.toList()
    }
}
