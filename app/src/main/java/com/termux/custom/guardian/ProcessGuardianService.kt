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
import com.termux.R
import com.termux.custom.ui.WorkstationActivity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.BufferedReader
import java.io.InputStreamReader

data class GuardianStatus(
    val isServiceRunning: Boolean = false,
    val wakeLockHeld: Boolean = false,
    val wifiLockHeld: Boolean = false,
    val batteryOptimizationIgnored: Boolean = false,
    val isHyperOsOrMiui: Boolean = false,
    val maxPhantomProcesses: String = "32 (Android Default)",
    val adbPhantomFixCommand: String = ADB_PHANTOM_FIX_CMD,
) {
    companion object {
        const val ADB_PHANTOM_FIX_CMD =
            "adb shell \"/system/bin/device_config set_sync_disabled_for_tests persistent && /system/bin/device_config put activity_manager max_phantom_processes 2147483647 && settings put global settings_enable_monitor_phantom_procs false\""
    }
}

class ProcessGuardianService : Service() {

    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ensureNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                releaseLocks()
                stopForeground( true)
                stopSelf()
                refreshStatus(this, running = false)
                return START_NOT_STICKY
            }
            ACTION_TOGGLE_LOCKS -> {
                if (wakeLock?.isHeld == true) {
                    releaseLocks()
                } else {
                    acquireLocks()
                }
            }
            else -> {
                acquireLocks()
            }
        }

        startForeground(NOTIFICATION_ID, buildNotification())
        refreshStatus(this, running = true)
        return START_STICKY
    }

    override fun onDestroy() {
        releaseLocks()
        refreshStatus(this, running = false)
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
            wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "TermuxCustom::AgentGuardianWifiLock").apply {
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
                "AI Agents Process Guardian",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "Keeps native arm64-v8a CLI agents (agy & claude) alive under HyperOS"
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
            .setContentTitle("Terminal Agents Guardian Active")
            .setContentText("WakeLock: ${if (wakeHeld) "ON" else "OFF"} · High-Perf Wi-Fi: ${if (wifiHeld) "ON" else "OFF"}")
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun refreshStatus(context: Context, running: Boolean) {
        _statusFlow.value = inspectSystemStatus(
            context = context,
            isRunning = running,
            wakeHeld = wakeLock?.isHeld == true,
            wifiHeld = wifiLock?.isHeld == true,
        )
    }

    companion object {
        private const val CHANNEL_ID = "termux_custom_process_guardian"
        private const val NOTIFICATION_ID = 4201
        const val ACTION_START = "com.termux.custom.guardian.START"
        const val ACTION_STOP = "com.termux.custom.guardian.STOP"
        const val ACTION_TOGGLE_LOCKS = "com.termux.custom.guardian.TOGGLE_LOCKS"

        private val _statusFlow = MutableStateFlow(GuardianStatus())
        val statusFlow: StateFlow<GuardianStatus> = _statusFlow.asStateFlow()

        fun start(context: Context) {
            val intent = Intent(context, ProcessGuardianService::class.java).apply {
                action = ACTION_START
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun toggleLocks(context: Context) {
            val intent = Intent(context, ProcessGuardianService::class.java).apply {
                action = ACTION_TOGGLE_LOCKS
            }
            context.startService(intent)
        }

        fun refreshSnapshot(context: Context) {
            val current = _statusFlow.value
            _statusFlow.value = inspectSystemStatus(
                context = context,
                isRunning = current.isServiceRunning,
                wakeHeld = current.wakeLockHeld,
                wifiHeld = current.wifiLockHeld,
            )
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

        private fun inspectSystemStatus(
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
                getSystemProperty("ro.miui.ui.version.name").isNotEmpty() ||
                getSystemProperty("ro.mi.os.version.name").isNotEmpty()

            val phantomLimit = probePhantomProcessLimit()

            return GuardianStatus(
                isServiceRunning = isRunning,
                wakeLockHeld = wakeHeld,
                wifiLockHeld = wifiHeld,
                batteryOptimizationIgnored = batteryIgnored,
                isHyperOsOrMiui = isXiaomi,
                maxPhantomProcesses = phantomLimit,
            )
        }

        private fun probePhantomProcessLimit(): String {
            if (Build.VERSION.SDK_INT < 31) {
                return "Unlimited (SDK < 31)"
            }
            return runCatching {
                val proc = ProcessBuilder("/system/bin/sh", "-c", "device_config get activity_manager max_phantom_processes 2>/dev/null")
                    .redirectErrorStream(true)
                    .start()
                val output = BufferedReader(InputStreamReader(proc.inputStream)).readLine()?.trim().orEmpty()
                proc.waitFor()
                if (output.isNotEmpty() && output != "null" && !output.contains("Permission", ignoreCase = true)) {
                    output
                } else {
                    "Android 12+ Default (32) — Use ADB Fix"
                }
            }.getOrDefault("Android 12+ Default (32)")
        }

        private fun getSystemProperty(key: String): String =
            runCatching {
                val proc = ProcessBuilder("/system/bin/getprop", key).start()
                BufferedReader(InputStreamReader(proc.inputStream)).readLine()?.trim().orEmpty()
            }.getOrDefault("")
    }
}
