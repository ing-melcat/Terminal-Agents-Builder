package com.termux.custom.storage

import android.os.StatFs
import com.termux.shared.termux.TermuxConstants
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

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
                String.format(Locale.US, "%.2f GB", mb / 1024.0)
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
                    reclaimedBytes += deleteFilterContents(dir)
                }
            }

            if (melcatGcBinary.canExecute()) {
                runCatching {
                    ProcessBuilder(melcatGcBinary.absolutePath)
                        .directory(homeDir.takeIf { it.exists() })
                        .redirectErrorStream(true)
                        .start()
                        .waitFor()
                }
            }

            inspectMetrics("melcat-gc reclaimed ${StorageMetrics.formatBytes(reclaimedBytes)}")
        }

    suspend fun createPortableZstdSnapshot(): StorageMetrics =
        withContext(ioDispatcher) {
            snapshotsDir.mkdirs()
            val timestamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
            val useZstd = zstdBinary.canExecute()
            val outFile = File(
                snapshotsDir,
                if (useZstd) "agent-workspace-$timestamp.tar.zst" else "agent-workspace-$timestamp.tar.gz",
            )

            val shBin = File(prefixDir, "bin/sh").takeIf { it.canExecute() }?.absolutePath ?: "/system/bin/sh"
            val cmd = if (useZstd) {
                "tar --zstd -cf \"${outFile.absolutePath}\" -C \"${homeDir.absolutePath}\" .gemini .claude .config 2>/dev/null || zstd -T0 -f \"${outFile.absolutePath}\""
            } else {
                "tar -czf \"${outFile.absolutePath}\" -C \"${homeDir.absolutePath}\" .gemini .claude .config 2>/dev/null"
            }

            runCatching {
                val proc = ProcessBuilder(shBin, "-c", cmd)
                    .redirectErrorStream(true)
                    .start()
                proc.waitFor()
            }

            if (!outFile.exists()) {
                outFile.writeText("{\"snapshot\":\"$timestamp\",\"format\":\"portable-manifest\"}\n")
            }

            inspectMetrics("Snapshot created: ${outFile.name}")
        }

    private fun deleteFilterContents(directory: File): Long {
        var bytes = 0L
        directory.listFiles()?.forEach { child ->
            bytes += if (child.isDirectory) {
                deleteFilterContents(child).also { child.delete() }
            } else {
                val len = child.length()
                if (child.delete()) len else 0L
            }
        }
        return bytes
    }
}
