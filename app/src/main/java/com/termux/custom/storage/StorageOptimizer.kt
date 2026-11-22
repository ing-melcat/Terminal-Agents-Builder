package com.termux.custom.storage

import android.os.StatFs
import android.system.Os
import android.system.OsConstants
import com.termux.shared.termux.TermuxConstants
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

data class StorageMetrics(
    val prefixPath: String,
    val freeBytes: Long,
    val totalBytes: Long,
    val snapshotsCount: Int,
    val latestSnapshotPath: String?,
    val zstdAvailable: Boolean,
    val lastGcSummary: String,
) {
    val freeFormatted: String
        get() = formatBytes(freeBytes)

    val usedPercent: Int
        get() = if (totalBytes <= 0L) 0 else (((totalBytes - freeBytes) * 100L) / totalBytes).toInt()

    companion object {
        fun formatBytes(bytes: Long): String {
            val mb = bytes / (1024.0 * 1024.0)
            return if (mb >= 1024.0) {
                String.format(Locale.US, "%.1f GB", mb / 1024.0)
            } else {
                String.format(Locale.US, "%.1f MB", mb)
            }
        }
    }
}

class StorageOptimizer(
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private val prefixDir = File(TermuxConstants.TERMUX_PREFIX_DIR_PATH)
    private val homeDir = File(TermuxConstants.TERMUX_HOME_DIR_PATH)
    private val snapshotsDir = File(homeDir, ".local/share/melcat/snapshots")
    private val logsDir = File(homeDir, ".local/share/melcat/logs")
    private val zstdBinary = File(prefixDir, "bin/zstd")
    private val melcatGcBinary = File(prefixDir, "bin/melcat-gc")

    suspend fun inspectMetrics(lastSummary: String = "Ready"): StorageMetrics =
        withContext(ioDispatcher) {
            val targetForStat = when {
                prefixDir.exists() -> prefixDir
                homeDir.exists() -> homeDir
                else -> File("/data")
            }
            val stat = runCatching { StatFs(targetForStat.absolutePath) }.getOrNull()
            val free = stat?.availableBytes ?: 0L
            val total = stat?.totalBytes ?: 1L

            val snapshots = snapshotsDir
                .takeIf { it.exists() }
                ?.listFiles { f -> f.isFile && (f.name.endsWith(".tar.zst") || f.name.endsWith(".tar.gz")) }
                ?.sortedByDescending { it.lastModified() }
                .orEmpty()

            StorageMetrics(
                prefixPath = prefixDir.absolutePath,
                freeBytes = free,
                totalBytes = total,
                snapshotsCount = snapshots.size,
                latestSnapshotPath = snapshots.firstOrNull()?.absolutePath,
                zstdAvailable = zstdBinary.canExecute(),
                lastGcSummary = lastSummary,
            )
        }

    suspend fun runMelcatGarbageCollection(): StorageMetrics =
        withContext(ioDispatcher) {
            logsDir.mkdirs()
            snapshotsDir.mkdirs()

            var reclaimedBytes = 0L
            val cacheCandidates = listOf(
                File(prefixDir, "var/cache/apt/archives"),
                File(homeDir, ".cache/pip"),
                File(homeDir, ".npm/_cacache"),
            )
            for (dir in cacheCandidates) {
                if (dir.exists()) {
                    reclaimedBytes += deleteFilterContentsSafely(dir)
                }
            }

            if (melcatGcBinary.canExecute()) {
                runCatching {
                    val pb = ProcessBuilder(melcatGcBinary.absolutePath)
                        .directory(homeDir.takeIf { it.exists() })
                        .redirectErrorStream(true)
                    pb.environment()["PATH"] = "${prefixDir.absolutePath}/bin:/system/bin:/vendor/bin"
                    pb.environment()["HOME"] = homeDir.absolutePath
                    val proc = pb.start()
                    proc.inputStream.bufferedReader().use { it.readText() }
                    if (!proc.waitFor(20, TimeUnit.SECONDS)) {
                        proc.destroyForcibly()
                    }
                }
            }

            inspectMetrics("Cleaned caches (${StorageMetrics.formatBytes(reclaimedBytes)} reclaimed)")
        }

    suspend fun createPortableZstdSnapshot(): StorageMetrics =
        withContext(ioDispatcher) {
            snapshotsDir.mkdirs()
            runCatching { Os.chmod(snapshotsDir.absolutePath, 448) } // 0700

            val timestamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
            val useZstd = zstdBinary.canExecute()
            val outFile = File(
                snapshotsDir,
                if (useZstd) "workspace-$timestamp.tar.zst" else "workspace-$timestamp.tar.gz",
            )

            val existingSources = listOf(".gemini", ".claude", ".config/melcat")
                .filter { File(homeDir, it).exists() }

            if (existingSources.isEmpty()) {
                return@withContext inspectMetrics("Nothing to snapshot yet — initialize workspace first")
            }

            val sourcesArg = existingSources.joinToString(" ")
            val excludes = "--exclude='*oauth-token*' --exclude='*oauth_creds.json*' --exclude='.gemini/oauth_creds.json' --exclude='.gemini/google_accounts.json' --exclude='.config/antigravity/antigravity-oauth-token' --exclude='.gemini/antigravity-oauth-token'"
            val shBin = File(prefixDir, "bin/sh").takeIf { it.canExecute() }?.absolutePath ?: "/system/bin/sh"
            val cmd = if (useZstd) {
                "tar $excludes -cf - -C \"${homeDir.absolutePath}\" $sourcesArg | \"${zstdBinary.absolutePath}\" -T0 -q -o \"${outFile.absolutePath}\""
            } else {
                "tar $excludes -czf \"${outFile.absolutePath}\" -C \"${homeDir.absolutePath}\" $sourcesArg"
            }

            val exitCode = runCatching {
                val pb = ProcessBuilder(shBin, "-c", cmd).redirectErrorStream(true)
                pb.environment()["PATH"] = "${prefixDir.absolutePath}/bin:/system/bin:/vendor/bin"
                pb.environment()["HOME"] = homeDir.absolutePath
                val proc = pb.start()
                proc.inputStream.bufferedReader().use { it.readText() }
                if (!proc.waitFor(45, TimeUnit.SECONDS)) {
                    proc.destroyForcibly()
                    -1
                } else {
                    proc.exitValue()
                }
            }.getOrDefault(-1)

            if (exitCode == 0 && outFile.isFile && outFile.length() > 0L) {
                runCatching { Os.chmod(outFile.absolutePath, 384) } // 0600 on snapshot archive
                inspectMetrics("Backup saved (${StorageMetrics.formatBytes(outFile.length())}): ${outFile.name}")
            } else {
                outFile.delete()
                inspectMetrics("Backup failed (exit=$exitCode) — verify tar/zstd in terminal")
            }
        }

    /**
     * Recursively clears cache files without following symlinks outside target directory.
     */
    private fun deleteFilterContentsSafely(directory: File): Long {
        var bytes = 0L
        directory.listFiles()?.forEach { child ->
            val isSymlink = runCatching {
                val st = Os.lstat(child.absolutePath)
                OsConstants.S_ISLNK(st.st_mode)
            }.getOrDefault(false)

            if (isSymlink) {
                child.delete()
            } else if (child.isDirectory) {
                bytes += deleteFilterContentsSafely(child)
                child.delete()
            } else {
                val len = child.length()
                if (child.delete()) bytes += len
            }
        }
        return bytes
    }
}
