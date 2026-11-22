package com.termux.custom.guardian

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.termux.R
import com.termux.custom.ui.WorkstationActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit

data class GuardianStatus(
    val isServiceRunning: Boolean = false,
    val wakeLockHeld: Boolean = false,
    val wifiLockHeld: Boolean = false,
    val batteryOptimizationIgnored: Boolean = false,
    val isHyperOsOrMiui: Boolean = false,
    val maxPhantomProcesses: String = "Checking...",
    val adbPhantomFixCommand: String = ADB_PHANTOM_FIX_CMD,
) {
    val phantomShielded: Boolean
        get() = maxPhantomProcesses.startsWith("Unlimited") ||
            (maxPhantomProcesses.toLongOrNull() ?: 0L) >= 1024L

    companion object {
        const val ADB_PHANTOM_FIX_CMD =
            "adb shell \"/system/bin/device_config set_sync_disabled_for_tests persistent && /system/bin/device_config put activity_manager max_phantom_processes 2147483647 && settings put global settings_enable_monitor_phantom_procs false\""
    }
}

class ProcessGuardianService : Service() {

    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ensureNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Always enter foreground first to satisfy API 26+ startForegroundService contract
        startForeground(NOTIFICATION_ID, buildNotification())

        when (intent?.action) {
            ACTION_STOP -> {
                releaseLocks()
                ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
                stopSelf()
                publishAsync(running = false)
                return START_NOT_STICKY
            }
            ACTION_TOGGLE_LOCKS -> {
                val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                if (wakeLock?.isHeld == true) {
                    releaseLocks()
                    prefs.edit().putBoolean(KEY_USER_RELEASED_LOCKS, true).apply()
                } else {
                    acquireLocks()
                    prefs.edit().putBoolean(KEY_USER_RELEASED_LOCKS, false).apply()
                }
            }
            else -> {
                // Respect an explicit "Release Locks" from the user across activity relaunches.
                val userReleased = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                    .getBoolean(KEY_USER_RELEASED_LOCKS, false)
                if (!userReleased) acquireLocks()
            }
        }

        // Refresh notification with updated lock state
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        nm?.notify(NOTIFICATION_ID, buildNotification())
        publishAsync(running = true)
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        releaseLocks()
        val appCtx = applicationContext
        _statusFlow.value = _statusFlow.value.copy(
            isServiceRunning = false,
            wakeLockHeld = false,
            wifiLockHeld = false,
        )
        serviceScope.cancel()
        // Lightweight non-blocking battery check
        runCatching {
            val pm = appCtx.getSystemService(Context.POWER_SERVICE) as PowerManager
            val ignored = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                pm.isIgnoringBatteryOptimizations(appCtx.packageName)
            } else {
                true
            }
            _statusFlow.value = _statusFlow.value.copy(batteryOptimizationIgnored = ignored)
        }
        super.onDestroy()
    }

    @SuppressLint("WakelockTimeout")
    private fun acquireLocks() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        if (wakeLock == null) {
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "TermuxCustom::AgentGuardianWakeLock").apply {
                setReferenceCounted(false)
            }
        }
        if (wakeLock?.isHeld != true) {
            wakeLock?.acquire()
        }

        val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        if (wifiLock == null) {
            @Suppress("DEPRECATION")
            val wifiMode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                WifiManager.WIFI_MODE_FULL_LOW_LATENCY
            } else {
                WifiManager.WIFI_MODE_FULL_HIGH_PERF
            }
            wifiLock = wm.createWifiLock(wifiMode, "TermuxCustom::AgentGuardianWifiLock").apply {
                setReferenceCounted(false)
            }
        }
        if (wifiLock?.isHeld != true) {
            wifiLock?.acquire()
        }
    }

    private fun releaseLocks() {
        runCatching {
            if (wakeLock?.isHeld == true) wakeLock?.release()
        }
        runCatching {
            if (wifiLock?.isHeld == true) wifiLock?.release()
        }
    }

    private fun ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Workstation Process Shield",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "Keeps native arm64 AI agents active in background"
            }
            val nm = getSystemService(NotificationManager::class.java)
            nm?.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        val launchIntent = Intent(this, WorkstationActivity::class.java)
        val pendingFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        val contentIntent = PendingIntent.getActivity(this, 0, launchIntent, pendingFlags)

        val wakeHeld = wakeLock?.isHeld == true
        val wifiHeld = wifiLock?.isHeld == true

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_service_notification)
            .setContentTitle("Terminal Agents Shield Active")
            .setContentText("WakeLock: ${if (wakeHeld) "ON" else "OFF"} · Low-Latency Wi-Fi: ${if (wifiHeld) "ON" else "OFF"}")
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun publishAsync(running: Boolean) {
        val appCtx = applicationContext
        val wakeHeld = wakeLock?.isHeld == true
        val wifiHeld = wifiLock?.isHeld == true
        // Publish lock state immediately without blocking main thread
        _statusFlow.value = _statusFlow.value.copy(
            isServiceRunning = running,
            wakeLockHeld = wakeHeld,
            wifiLockHeld = wifiHeld,
        )
        serviceScope.launch {
            _statusFlow.value = inspectSystemStatusIo(
                context = appCtx,
                isRunning = running,
                wakeHeld = wakeHeld,
                wifiHeld = wifiHeld,
            )
        }
    }

    companion object {
        private const val CHANNEL_ID = "termux_custom_process_guardian"
        private const val NOTIFICATION_ID = 4201
        const val ACTION_START = "com.termux.custom.guardian.START"
        private const val PREFS_NAME = "melcat_guardian"
        private const val KEY_USER_RELEASED_LOCKS = "user_released_locks"
        const val ACTION_STOP = "com.termux.custom.guardian.STOP"
        const val ACTION_TOGGLE_LOCKS = "com.termux.custom.guardian.TOGGLE_LOCKS"

        private val _statusFlow = MutableStateFlow(GuardianStatus())
        val statusFlow: StateFlow<GuardianStatus> = _statusFlow.asStateFlow()

        fun start(context: Context) {
            val intent = Intent(context.applicationContext, ProcessGuardianService::class.java).apply {
                action = ACTION_START
            }
            runCatching { ContextCompat.startForegroundService(context.applicationContext, intent) }
        }

        fun toggleLocks(context: Context) {
            val intent = Intent(context.applicationContext, ProcessGuardianService::class.java).apply {
                action = ACTION_TOGGLE_LOCKS
            }
            runCatching { ContextCompat.startForegroundService(context.applicationContext, intent) }
        }

        suspend fun refreshSnapshot(context: Context) {
            val appCtx = context.applicationContext
            val current = _statusFlow.value
            val updated = withContext(Dispatchers.IO) {
                inspectSystemStatusIo(
                    context = appCtx,
                    isRunning = current.isServiceRunning,
                    wakeHeld = current.wakeLockHeld,
                    wifiHeld = current.wifiLockHeld,
                )
            }
            _statusFlow.value = updated
        }

        @SuppressLint("BatteryLife")
        fun requestIgnoreBatteryOptimizations(context: Context) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                    data = Uri.parse("package:${context.packageName}")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                runCatching { context.startActivity(intent) }.onFailure {
                    val fallback = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    runCatching { context.startActivity(fallback) }
                }
            }
        }

        fun openHyperOsAutostartSettings(context: Context) {
            val candidates = listOf(
                Intent("miui.intent.action.OP_AUTO_START").addCategory(Intent.CATEGORY_DEFAULT),
                Intent().setComponent(
                    ComponentName(
                        "com.miui.securitycenter",
                        "com.miui.permcenter.autostart.AutoStartManagementActivity",
                    )
                ),
                Intent("miui.intent.action.POWER_HIDE_MODE_ACTIVITY_LIST").addCategory(Intent.CATEGORY_DEFAULT),
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = Uri.fromParts("package", context.packageName, null)
                },
            )
            for (intent in candidates) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                if (runCatching { context.startActivity(intent) }.isSuccess) {
                    return
                }
            }
        }

        private fun inspectSystemStatusIo(
            context: Context,
            isRunning: Boolean,
            wakeHeld: Boolean,
            wifiHeld: Boolean,
        ): GuardianStatus {
            val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
            val batteryIgnored = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                pm.isIgnoringBatteryOptimizations(context.packageName)
            } else {
                true
            }

            val manufacturer = Build.MANUFACTURER.lowercase()
            val isXiaomi = manufacturer.contains("xiaomi") ||
                manufacturer.contains("redmi") ||
                manufacturer.contains("poco") ||
                readSystemPropertyFast("ro.miui.ui.version.name").isNotEmpty() ||
                readSystemPropertyFast("ro.mi.os.version.name").isNotEmpty()

            val phantomLimit = probePhantomProcessLimitSafe()

            return GuardianStatus(
                isServiceRunning = isRunning,
                wakeLockHeld = wakeHeld,
                wifiLockHeld = wifiHeld,
                batteryOptimizationIgnored = batteryIgnored,
                isHyperOsOrMiui = isXiaomi,
                maxPhantomProcesses = phantomLimit,
            )
        }

        private fun probePhantomProcessLimitSafe(): String {
            if (Build.VERSION.SDK_INT < 31) {
                return "Unlimited (Android < 12)"
            }
            return runCatching {
                val proc = ProcessBuilder("/system/bin/sh", "-c", "device_config get activity_manager max_phantom_processes 2>/dev/null")
                    .redirectErrorStream(true)
                    .start()
                val output = BufferedReader(InputStreamReader(proc.inputStream)).use { it.readLine()?.trim().orEmpty() }
                val finished = proc.waitFor(2, TimeUnit.SECONDS)
                if (!finished) {
                    proc.destroyForcibly()
                }
                if (output.isNotEmpty() && output != "null" && !output.contains("Permission", ignoreCase = true)) {
                    output
                } else {
                    "32 (Android Default)"
                }
            }.getOrDefault("32 (Android Default)")
        }

        @SuppressLint("PrivateApi")
        private fun readSystemPropertyFast(key: String): String =
            runCatching {
                val clazz = Class.forName("android.os.SystemProperties")
                val method = clazz.getMethod("get", String::class.java, String::class.java)
                (method.invoke(null, key, "") as? String).orEmpty()
            }.getOrDefault("")
    }
}
