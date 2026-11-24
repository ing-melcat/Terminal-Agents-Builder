package com.termux.custom.rur

import android.system.Os
import android.system.OsConstants
import com.termux.shared.termux.TermuxConstants
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URI
import java.net.URL
import java.net.URLEncoder
import java.net.UnknownHostException
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.security.SecureRandom
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.ConcurrentHashMap
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLParameters
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

enum class RurModuleCategory(val label: String) {
    LIVEKIT_RECEIVERS("Receptores LiveKit"),
    OFFBOARD_STACK("Offboard ROS 2"),
    DIAGNOSTICS("Diagnóstico"),
}

enum class RurModuleOutputKind {
    CONSOLE_STREAM,
    INTERACTIVE_SHELL,
}

enum class RurTokenRole {
    RECEIVER,
    CAMERA,
    OPERATOR,
    NONE,
}

enum class RurEditableScriptSlot(val key: String, val label: String, val defaultCommand: String) {
    LIVEKIT_RECEIVER_LAUNCH(
        key = "livekit_rx_pc",
        label = "Receptor LiveKit (Nube 3D / Cámara / IMU)",
        defaultCommand = "ros2 launch rover_rassor_bringup livekit_receiver.launch.py",
    ),
    LIVEKIT_AGENT_HEADLESS(
        key = "livekit_rx_agent",
        label = "Agente LiveKit Headless (sin RViz2)",
        defaultCommand = "ros2 run livekit_agent livekit_agent",
    ),
    MOTION_RECEIVER(
        key = "livekit_rx_motion",
        label = "Receptor Movimiento / Actuadores",
        defaultCommand = "python3 -u scripts/robot_receiver_prod.py",
    ),
}

data class RurModuleSpec(
    val id: String,
    val code: String,
    val label: String,
    val description: String,
    val badge: String,
    val category: RurModuleCategory,
    val outputKind: RurModuleOutputKind,
    val topics: List<String>,
    val defaultCommand: String,
    val editableSlot: RurEditableScriptSlot? = null,
    val requiresLiveKitReceiver: Boolean = false,
    val tokenRole: RurTokenRole = RurTokenRole.NONE,
    val runInScriptsDir: Boolean = false,
) {
    val tabTitle: String get() = "ros:$code"
}

data class RurStackSpec(
    val id: String,
    val label: String,
    val subtitle: String,
    val moduleIds: List<String>,
)

data class RurRobotInfo(
    val id: String,
    val name: String,
    val status: String = "offline",
    val room: String = "room-1",
    val rosDomainId: Int = 42,
    val livekitUrl: String = "",
    val locomotionType: String = "tank",
    val version: Int = 1,
    val hasCredentials: Boolean = false,
)

data class RurOperatorSession(
    val id: String,
    val matricula: String,
    val nombre: String,
    val alias: String,
    val grupo: String,
    val roles: List<String>,
    val totpEnabled: Boolean,
    val mustChangePassword: Boolean,
    val loginAtIso: String,
    val expiresAtEpochMs: Long,
    val hasSessionAuth: Boolean,
    val permisos: List<String> = emptyList(),
) {
    val displayName: String
        get() = alias.ifBlank { nombre.ifBlank { matricula } }

    val canOperate: Boolean
        get() = isAdmin ||
            permisos.any { it.equals("robots.operar", ignoreCase = true) } ||
            roles.any { it.equals("operador", ignoreCase = true) }

    val isAdmin: Boolean
        get() = roles.any { it.equals("admin", ignoreCase = true) }

    val primaryRoleLabel: String
        get() = when {
            isAdmin -> "ADMIN"
            roles.firstOrNull { !it.equals("miembro", ignoreCase = true) } != null ->
                roles.first { !it.equals("miembro", ignoreCase = true) }.uppercase(Locale.US)
            canOperate -> "OPERADOR"
            else -> roles.firstOrNull()?.uppercase(Locale.US)?.ifBlank { "MIEMBRO" } ?: "MIEMBRO"
        }

    fun isExpired(nowMs: Long = System.currentTimeMillis()): Boolean =
        expiresAtEpochMs > 0L && nowMs >= expiresAtEpochMs
}

data class Rur2faSetupPayload(
    val secret: String,
    val otpauthUri: String,
)

data class RurLiveKitTokens(
    val robotId: String,
    val receiverToken: String = "",
    val cameraToken: String = "",
    val operatorToken: String = "",
    val livekitUrl: String = "",
    val room: String = "",
    val rosDomainId: Int = 42,
    val expiresAt: String = "",
    val source: String = "local_env",
) {
    val hasAnyToken: Boolean
        get() = receiverToken.isNotBlank() || cameraToken.isNotBlank() || operatorToken.isNotBlank()
}

data class RurSyncStatus(
    val ok: Boolean = false,
    val code: String = "LOCAL_ONLY",
    val mode: String = "local",
    val lastSyncIso: String? = null,
    val error: String? = null,
    val etag: String? = null,
    val pendingDriftNotice: String? = null,
)

data class RurControlState(
    val prootDistroInstalled: Boolean = false,
    val ubuntuInstalled: Boolean = false,
    val ros2JazzyInstalled: Boolean = false,
    val offboardWsPresent: Boolean = false,
    val offboardWsBuilt: Boolean = false,
    val scriptsDirPresent: Boolean = false,
    val webApiUrl: String = DEFAULT_WEB_API_URL,
    val hasDeviceKey: Boolean = false,
    val deviceKeyPreview: String = "",
    val operatorSession: RurOperatorSession? = null,
    val offlineBypassEnabled: Boolean = false,
    val activeRobotId: String = DEFAULT_ROBOT_ID,
    val defaultRosDomainId: Int = DEFAULT_ROS_DOMAIN_ID,
    val offboardRepoPath: String = DEFAULT_OFFBOARD_REPO_REL,
    val scriptsPath: String = DEFAULT_SCRIPTS_REL,
    val linkedPiSshDeviceId: String = "",
    val customScripts: Map<String, String> = emptyMap(),
    val robots: List<RurRobotInfo> = defaultRobots(),
    val syncStatus: RurSyncStatus = RurSyncStatus(),
    val hasLiveRamToken: Boolean = false,
    val liveTokenSource: String = "local_env",
    val activeLiveKitRoom: String = "room-1",
    val activeLiveKitUrl: String = "",
) {
    val ros2Ready: Boolean get() = prootDistroInstalled && ubuntuInstalled && ros2JazzyInstalled

    val activeRobot: RurRobotInfo
        get() = robots.firstOrNull { it.id == activeRobotId } ?: robots.firstOrNull() ?: defaultRobots().first()

    val effectiveRosDomainId: Int
        get() = activeRobot.rosDomainId.coerceIn(0, 232)

    val isAuthenticated: Boolean
        get() = operatorSession != null && !operatorSession.isExpired()

    fun resolvedCommandFor(module: RurModuleSpec): String {
        val slot = module.editableSlot ?: return module.defaultCommand
        return customScripts[slot.key]?.trim()?.takeIf { it.isNotEmpty() } ?: module.defaultCommand
    }

    companion object {
        const val DEFAULT_WEB_API_URL = "https://rur.unipolidgo.edu.mx"
        const val DEFAULT_ROBOT_ID = "rassor-1"
        const val DEFAULT_ROS_DOMAIN_ID = 42
        const val DEFAULT_OFFBOARD_REPO_REL = "RUR/rover_rassor_project-"
        const val DEFAULT_SCRIPTS_REL = "RUR/monitoring_center_control"

        fun defaultRobots(): List<RurRobotInfo> = listOf(
            RurRobotInfo(
                id = "rassor-1",
                name = "RASSOR-1",
                status = "offline",
                room = "room-1",
                rosDomainId = 42,
                livekitUrl = "wss://rassor-tb5pwrbm.livekit.cloud",
                locomotionType = "tank",
                version = 1,
                hasCredentials = false,
            ),
        )
    }
}

object RurControlCatalog {
    val modules: List<RurModuleSpec> = listOf(
        // ── Sección #1: Receptores LiveKit (Prerrequisito para ver tópicos) ─────────────────
        RurModuleSpec(
            id = "livekit_rx_pc",
            code = "livekit_rx_pc",
            label = "Receptor LiveKit (offboard_ws)",
            description = "Lanza livekit_receiver.launch.py: reconstruye PointCloud2, cámara, profundidad LZ4, IMU y /scan.",
            badge = "Receptor 3D",
            category = RurModuleCategory.LIVEKIT_RECEIVERS,
            outputKind = RurModuleOutputKind.CONSOLE_STREAM,
            topics = listOf("/camera/color/image_raw", "/camera/depth/color/points", "/scan", "/imu/data"),
            defaultCommand = RurEditableScriptSlot.LIVEKIT_RECEIVER_LAUNCH.defaultCommand,
            editableSlot = RurEditableScriptSlot.LIVEKIT_RECEIVER_LAUNCH,
            requiresLiveKitReceiver = false,
            tokenRole = RurTokenRole.RECEIVER,
        ),
        RurModuleSpec(
            id = "livekit_rx_agent",
            code = "livekit_rx_agent",
            label = "Agente LiveKit Headless",
            description = "Ejecuta livekit_agent en pura consola (sin RViz2) — ideal para Android proot + Foxglove 3D.",
            badge = "Headless",
            category = RurModuleCategory.LIVEKIT_RECEIVERS,
            outputKind = RurModuleOutputKind.CONSOLE_STREAM,
            topics = listOf("PointCloud2", "RGB + Depth LZ4", "IMU/Odom"),
            defaultCommand = RurEditableScriptSlot.LIVEKIT_AGENT_HEADLESS.defaultCommand,
            editableSlot = RurEditableScriptSlot.LIVEKIT_AGENT_HEADLESS,
            requiresLiveKitReceiver = false,
            tokenRole = RurTokenRole.RECEIVER,
        ),
        RurModuleSpec(
            id = "livekit_rx_motion",
            code = "livekit_rx_motion",
            label = "Receptor Movimiento / Actuadores",
            description = "Recibe canales WebRTC de movimiento, joystick binario 12B, brazos, tambores y ruedas.",
            badge = "Movimiento",
            category = RurModuleCategory.LIVEKIT_RECEIVERS,
            outputKind = RurModuleOutputKind.CONSOLE_STREAM,
            topics = listOf("Joystick 12B", "Wheels", "ArmFront/Back", "DrumFront/Back"),
            defaultCommand = RurEditableScriptSlot.MOTION_RECEIVER.defaultCommand,
            editableSlot = RurEditableScriptSlot.MOTION_RECEIVER,
            requiresLiveKitReceiver = false,
            tokenRole = RurTokenRole.OPERATOR,
            runInScriptsDir = true,
        ),

        // ── Sección #2: Consumidores offboard_ws (consumen los tópicos de los receptores) ──
        RurModuleSpec(
            id = "foxglove_bridge",
            code = "foxglove",
            label = "Foxglove Bridge 3D (:8765)",
            description = "Sustituto GPU WebGL de RViz2: visualiza nube 3D, cámara, TF y mapa en navegador a 60-120 FPS.",
            badge = "WebGL 3D",
            category = RurModuleCategory.OFFBOARD_STACK,
            outputKind = RurModuleOutputKind.CONSOLE_STREAM,
            topics = listOf("ws://127.0.0.1:8765", "PointCloud2", "/scan", "/map"),
            defaultCommand = "ros2 launch foxglove_bridge foxglove_bridge_launch.xml port:=8765 address:=127.0.0.1",
            requiresLiveKitReceiver = true,
        ),
        RurModuleSpec(
            id = "depth_laserscan",
            code = "laserscan",
            label = "Depth → LaserScan (/scan)",
            description = "Convierte la profundidad alineada del receptor LiveKit en escáner 2D /scan para SLAM/Nav2.",
            badge = "LaserScan",
            category = RurModuleCategory.OFFBOARD_STACK,
            outputKind = RurModuleOutputKind.CONSOLE_STREAM,
            topics = listOf("/camera/aligned_depth_to_color/image_raw", "/scan"),
            defaultCommand = "ros2 run depthimage_to_laserscan depthimage_to_laserscan_node",
            requiresLiveKitReceiver = true,
        ),
        RurModuleSpec(
            id = "slam_toolbox",
            code = "slam_2d",
            label = "SLAM Toolbox 2D",
            description = "Mapeo 2D en vivo (rover_rassor_mapping / slam_toolbox) desde /scan y odometría local.",
            badge = "SLAM 2D",
            category = RurModuleCategory.OFFBOARD_STACK,
            outputKind = RurModuleOutputKind.CONSOLE_STREAM,
            topics = listOf("/scan", "/odom", "/map", "/tf"),
            defaultCommand = "ros2 launch rover_rassor_mapping online_async_launch.py 2>/dev/null || ros2 launch slam_toolbox online_async_launch.py",
            requiresLiveKitReceiver = true,
        ),
        RurModuleSpec(
            id = "nav2_planner",
            code = "nav2_plan",
            label = "Nav2 + A* / Dijkstra",
            description = "Servidor de navegación autónoma y mapas de costos (rover_rassor_navigation + planning).",
            badge = "Nav2",
            category = RurModuleCategory.OFFBOARD_STACK,
            outputKind = RurModuleOutputKind.CONSOLE_STREAM,
            topics = listOf("/plan", "/cmd_vel", "/local_costmap", "/global_costmap"),
            defaultCommand = "ros2 launch rover_rassor_navigation navigation_launch.py 2>/dev/null || ros2 launch nav2_bringup navigation_launch.py use_sim_time:=false",
            requiresLiveKitReceiver = true,
        ),
        RurModuleSpec(
            id = "colcon_build",
            code = "colcon",
            label = "Compilar offboard_ws",
            description = "Compila livekit_agent, bringup, mapping, navigation y plugins C++ A*/Dijkstra.",
            badge = "Build",
            category = RurModuleCategory.OFFBOARD_STACK,
            outputKind = RurModuleOutputKind.CONSOLE_STREAM,
            topics = listOf("offboard_ws/install"),
            defaultCommand = "colcon build --symlink-install",
            requiresLiveKitReceiver = false,
        ),

        // ── Sección #3: Diagnóstico ROS 2 ──────────────────────────────────────────────────
        RurModuleSpec(
            id = "topic_list",
            code = "topics",
            label = "Tópicos DDS (-v)",
            description = "Lista todos los tópicos activos (nube, cámara, movimiento) y sus publicadores/suscriptores.",
            badge = "DDS List",
            category = RurModuleCategory.DIAGNOSTICS,
            outputKind = RurModuleOutputKind.CONSOLE_STREAM,
            topics = listOf("ros2 topic list -v"),
            defaultCommand = "ros2 topic list -v",
            requiresLiveKitReceiver = true,
        ),
        RurModuleSpec(
            id = "topic_hz",
            code = "topic_hz",
            label = "Auditoría de Frecuencia (Hz)",
            description = "Mide FPS reales de cámara, nube de puntos o /scan generados por el receptor LiveKit.",
            badge = "Hz Audit",
            category = RurModuleCategory.DIAGNOSTICS,
            outputKind = RurModuleOutputKind.CONSOLE_STREAM,
            topics = listOf("ros2 topic hz"),
            defaultCommand = "TARGET=\"\$(ros2 topic list 2>/dev/null | grep -E 'points|image_raw|scan|odom' | head -n 1)\"; if [ -n \"\$TARGET\" ]; then echo \"==> Midiendo Hz en \$TARGET (Ctrl+C para salir)\"; ros2 topic hz \"\$TARGET\"; else echo 'Sin tópicos de cámara/nube activos. Inicia primero un Receptor LiveKit arriba.'; ros2 topic list -v; fi",
            requiresLiveKitReceiver = true,
        ),
        RurModuleSpec(
            id = "node_list",
            code = "nodes",
            label = "Nodos & Info ROS 2",
            description = "Muestra nodos vivos en el dominio local y verifica DDS/Jazzy.",
            badge = "Nodos",
            category = RurModuleCategory.DIAGNOSTICS,
            outputKind = RurModuleOutputKind.CONSOLE_STREAM,
            topics = listOf("ros2 node list"),
            defaultCommand = "echo '=== Nodos ROS 2 ===' && ros2 node list && echo '' && echo '=== Tópicos ===' && ros2 topic list",
            requiresLiveKitReceiver = false,
        ),
        RurModuleSpec(
            id = "ros2_shell",
            code = "shell",
            label = "Shell Ubuntu ROS 2 Jazzy",
            description = "Consola interactiva Ubuntu 24.04 con Jazzy, offboard_ws y ROS_DOMAIN_ID listos.",
            badge = "Ubuntu",
            category = RurModuleCategory.DIAGNOSTICS,
            outputKind = RurModuleOutputKind.INTERACTIVE_SHELL,
            topics = listOf("Jazzy", "offboard_ws"),
            defaultCommand = "",
            requiresLiveKitReceiver = false,
        ),
    )

    val stacks: List<RurStackSpec> = listOf(
        RurStackSpec(
            id = "stack_livekit_foxglove",
            label = "LiveKit + Foxglove 3D",
            subtitle = "Agente LiveKit headless + visor WebGL :8765",
            moduleIds = listOf("livekit_rx_agent", "foxglove_bridge"),
        ),
        RurStackSpec(
            id = "stack_livekit_slam",
            label = "LiveKit + SLAM 2D",
            subtitle = "Receptor offboard_ws + slam_toolbox",
            moduleIds = listOf("livekit_rx_pc", "slam_toolbox"),
        ),
        RurStackSpec(
            id = "stack_livekit_diag",
            label = "LiveKit + Tópicos",
            subtitle = "Receptor offboard_ws + lista DDS (-v)",
            moduleIds = listOf("livekit_rx_pc", "topic_list"),
        ),
    )

    fun byId(id: String): RurModuleSpec? = modules.firstOrNull { it.id == id }
    fun byCategory(category: RurModuleCategory): List<RurModuleSpec> = modules.filter { it.category == category }
}

private data class StoredOperatorAuth(
    val session: RurOperatorSession,
    val bearerToken: String,
    val cookieHeader: String,
    val csrfToken: String,
)

class RurControlCenterManager(
    private val homeDir: File = File(TermuxConstants.TERMUX_HOME_DIR_PATH),
    private val prefixDir: File = File(TermuxConstants.TERMUX_PREFIX_DIR_PATH),
) {
    private val configDir = File(homeDir, ".config/melcat")
    private val vaultDir = File(homeDir, ".config/melcat/vault")
    private val cacheDir = File(homeDir, ".cache/melcat-rur")
    private val configFile = File(configDir, "rur-ops.json")
    private val deviceKeyFile = File(vaultDir, "rur-device.key")
    private val operatorSessionFile = File(vaultDir, "rur-operator-session.json")

    // RAM-only LiveKit JWTs (Zero-Exposure: NEVER persisted to rur-ops.json)
    private val ramTokensByRobot = ConcurrentHashMap<String, RurLiveKitTokens>()
    private val secureRandom = SecureRandom()
    private val configLock = Any()

    @Volatile
    private var lastSyncStatus = RurSyncStatus()

    suspend fun inspectState(): RurControlState = withContext(Dispatchers.IO) {
        val persisted = synchronized(configLock) { loadPersistedConfig() }
        val deviceKey = readSecretFile(deviceKeyFile)
        val operatorAuth = loadValidOperatorAuth()
        val prootBin = File(prefixDir, "bin/proot-distro")
        val ubuntuV5Rootfs = File(prefixDir, "var/lib/proot-distro/containers/ubuntu/rootfs")
        val ubuntuV4Rootfs = File(prefixDir, "var/lib/proot-distro/installed-rootfs/ubuntu")
        val ubuntuRootfs = when {
            ubuntuV5Rootfs.isDirectory && File(ubuntuV5Rootfs, "bin/bash").exists() -> ubuntuV5Rootfs
            ubuntuV4Rootfs.isDirectory && File(ubuntuV4Rootfs, "bin/bash").exists() -> ubuntuV4Rootfs
            else -> ubuntuV5Rootfs
        }
        val ros2Setup = File(ubuntuRootfs, "opt/ros/jazzy/setup.bash")
        val repoDir = resolveUserPath(persisted.offboardRepoPath)
        val offboardDir = File(repoDir, "offboard_ws")
        val offboardSetup = File(offboardDir, "install/setup.bash")
        val scriptsDir = resolveUserPath(persisted.scriptsPath)

        val robots = persisted.robots.ifEmpty { RurControlState.defaultRobots() }
        val activeId = persisted.activeRobotId.takeIf { id -> robots.any { it.id == id } } ?: robots.first().id
        val activeRobot = robots.first { it.id == activeId }
        val ramTokens = ramTokensByRobot[activeId]

        RurControlState(
            prootDistroInstalled = prootBin.isFile && prootBin.canExecute(),
            ubuntuInstalled = ubuntuRootfs.isDirectory && File(ubuntuRootfs, "bin/bash").exists(),
            ros2JazzyInstalled = ros2Setup.isFile,
            offboardWsPresent = offboardDir.isDirectory,
            offboardWsBuilt = offboardSetup.isFile,
            scriptsDirPresent = scriptsDir.isDirectory,
            webApiUrl = persisted.webApiUrl,
            hasDeviceKey = deviceKey.isNotEmpty(),
            deviceKeyPreview = maskSecretPreview(deviceKey),
            operatorSession = operatorAuth?.session,
            offlineBypassEnabled = persisted.offlineBypassEnabled,
            activeRobotId = activeId,
            defaultRosDomainId = persisted.defaultRosDomainId,
            offboardRepoPath = persisted.offboardRepoPath,
            scriptsPath = persisted.scriptsPath,
            linkedPiSshDeviceId = persisted.linkedPiSshDeviceId,
            customScripts = persisted.customScripts,
            robots = robots,
            syncStatus = lastSyncStatus,
            hasLiveRamToken = ramTokens?.hasAnyToken == true,
            liveTokenSource = ramTokens?.source ?: "local_env",
            activeLiveKitRoom = ramTokens?.room?.ifBlank { activeRobot.room } ?: activeRobot.room,
            activeLiveKitUrl = ramTokens?.livekitUrl?.ifBlank { activeRobot.livekitUrl } ?: activeRobot.livekitUrl,
        )
    }

    /**
     * Authenticates an operator directly against PAGE_RUR (`POST /api/login.php` with fallback to `/api/login`)
     * matching `src/LoginApp.tsx` and PostgreSQL `login_system.users` (`matricula`, `password_hash` / `code`).
     * Captures the 24h JWT `token` (and any `Set-Cookie` / `rur_csrf`) and locks it in the 0600 vault.
     */
    suspend fun loginOperator(
        rawMatricula: String,
        rawCredential: String,
    ): Result<RurControlState> = withContext(Dispatchers.IO) {
        val matricula = rawMatricula.trim()
        val credential = rawCredential.trim()
        if (matricula.isEmpty() || credential.isEmpty()) {
            return@withContext Result.failure(IllegalArgumentException("Por favor completa Matrícula y Código de Acceso."))
        }
        if (matricula.length > 64 || credential.length > 256 ||
            matricula.any { it.code < 0x20 || it.code == 0x7F } ||
            credential.any { it.code < 0x20 || it.code == 0x7F }
        ) {
            return@withContext Result.failure(IllegalArgumentException("Caracteres inválidos en Matrícula o Código de Acceso."))
        }

        val persisted = synchronized(configLock) { loadPersistedConfig() }
        val baseUrl = validateWebApiUrl(persisted.webApiUrl).getOrElse { return@withContext Result.failure(it) }

        val payload = JSONObject().apply {
            put("matricula", matricula)
            put("password", credential)
            put("code", credential)
        }.toString()
        val reqHeaders = mapOf(
            "Content-Type" to "application/json",
            "Accept" to "application/json",
            "User-Agent" to USER_AGENT,
        )

        // Match PAGE_RUR (`POST /api/login` in LoginApp.tsx + Caddyfile `@identity`) first,
        // with automatic fallback to `/api/login.php` for legacy PHP backends on 404/405
        var httpRes = runCatching {
            executeHttpJson(
                method = "POST",
                urlStr = "$baseUrl/api/login",
                headers = reqHeaders,
                body = payload,
            )
        }.getOrElse { err ->
            return@withContext Result.failure(
                IllegalStateException("No se pudo conectar con $baseUrl: ${err.message ?: err.javaClass.simpleName}")
            )
        }
        if (httpRes.statusCode == 404 || httpRes.statusCode == 405) {
            val fallbackRes = runCatching {
                executeHttpJson(
                    method = "POST",
                    urlStr = "$baseUrl/api/login.php",
                    headers = reqHeaders,
                    body = payload,
                )
            }.getOrNull()
            if (fallbackRes != null) {
                httpRes = fallbackRes
            }
        }

        val json = runCatching { JSONObject(httpRes.body) }.getOrNull()
        val bearerToken = json?.optString("token", "")?.trim().orEmpty()
        val hasAuth = bearerToken.isNotEmpty() || httpRes.cookieHeader.isNotBlank()
        if (httpRes.statusCode !in 200..299 || json == null || !json.optBoolean("success", false) || !hasAuth) {
            val serverMsg = json?.optString("message")?.ifBlank { json.optString("error") }.orEmpty()
            return@withContext Result.failure(
                IllegalStateException(
                    serverMsg.ifBlank { "Credenciales incorrectas (HTTP ${httpRes.statusCode})." }
                )
            )
        }

        val userJson = json.optJSONObject("user") ?: JSONObject().put("matricula", matricula)
        val parsedSession = parseOperatorSessionFromUserJson(
            userJson = userJson,
            fallbackMatricula = matricula,
            hasSessionAuth = true,
        )
        val stored = StoredOperatorAuth(
            session = parsedSession,
            bearerToken = bearerToken,
            cookieHeader = httpRes.cookieHeader,
            csrfToken = extractCookieValue(httpRes.cookieHeader, "rur_csrf"),
        )
        if (!saveOperatorAuthAtomic(stored)) {
            return@withContext Result.failure(IllegalStateException("No se pudo guardar la sesión de operador (0600)."))
        }

        synchronized(configLock) {
            val latest = loadPersistedConfig()
            if (latest.offlineBypassEnabled) {
                savePersistedConfig(latest.copy(offlineBypassEnabled = false))
            }
        }

        // Immediately sync fleet & fetch RAM LiveKit tokens for active robot
        syncFleetFromPageRurInternal(forceTokens = true)
        Result.success(inspectState())
    }

    /**
     * Requests a Google Authenticator TOTP secret from PAGE_RUR (`GET /api/profile/2fa/generate`).
     */
    suspend fun generateOperator2fa(): Result<Rur2faSetupPayload> = withContext(Dispatchers.IO) {
        val auth = loadValidOperatorAuth()
            ?: return@withContext Result.failure(IllegalStateException("Inicia sesión de operador primero."))
        val persisted = synchronized(configLock) { loadPersistedConfig() }
        val baseUrl = validateWebApiUrl(persisted.webApiUrl).getOrElse { return@withContext Result.failure(it) }

        val headers = buildOperatorAuthHeaders(auth, includeCsrf = false)
        val httpRes = runCatching {
            executeHttpJson(
                method = "GET",
                urlStr = "$baseUrl/api/profile/2fa/generate",
                headers = headers,
                body = null,
            )
        }.getOrElse { err ->
            return@withContext Result.failure(IllegalStateException("Error conectando con PAGE_RUR: ${err.message}"))
        }

        val json = runCatching { JSONObject(httpRes.body) }.getOrNull()
        if (httpRes.statusCode !in 200..299 || json == null || !json.optBoolean("success", false)) {
            val msg = json?.optString("message").orEmpty().ifBlank { "No se pudo generar 2FA (HTTP ${httpRes.statusCode})." }
            return@withContext Result.failure(IllegalStateException(msg))
        }

        val secret = json.optString("secret", "").trim()
        val otpauth = json.optString("otpauth", "").trim()
        if (secret.isEmpty()) {
            return@withContext Result.failure(IllegalStateException("Respuesta 2FA incompleta desde el servidor."))
        }
        Result.success(Rur2faSetupPayload(secret = secret, otpauthUri = otpauth))
    }

    /**
     * Verifies a 6-digit TOTP code and enables Passwordless 2FA in PAGE_RUR (`POST /api/profile/2fa/enable`).
     */
    suspend fun enableOperator2fa(code: String, secret: String): Result<RurControlState> = withContext(Dispatchers.IO) {
        val auth = loadValidOperatorAuth()
            ?: return@withContext Result.failure(IllegalStateException("Sesión de operador expirada."))
        val cleanCode = code.trim()
        val cleanSecret = secret.trim()
        if (!cleanCode.matches(Regex("^\\d{6}$"))) {
            return@withContext Result.failure(IllegalArgumentException("El código 2FA debe tener exactamente 6 dígitos."))
        }
        if (cleanSecret.isEmpty() || cleanSecret.length > 128) {
            return@withContext Result.failure(IllegalArgumentException("Secreto TOTP inválido."))
        }

        val persisted = synchronized(configLock) { loadPersistedConfig() }
        val baseUrl = validateWebApiUrl(persisted.webApiUrl).getOrElse { return@withContext Result.failure(it) }
        val headers = buildOperatorAuthHeaders(auth, includeCsrf = true) + ("Content-Type" to "application/json")
        val body = JSONObject().apply {
            put("code", cleanCode)
            put("secret", cleanSecret)
        }.toString()

        val httpRes = runCatching {
            executeHttpJson(
                method = "POST",
                urlStr = "$baseUrl/api/profile/2fa/enable",
                headers = headers,
                body = body,
            )
        }.getOrElse { err ->
            return@withContext Result.failure(IllegalStateException("Error al verificar 2FA: ${err.message}"))
        }

        val json = runCatching { JSONObject(httpRes.body) }.getOrNull()
        if (httpRes.statusCode !in 200..299 || json == null || !json.optBoolean("success", false)) {
            val msg = json?.optString("message").orEmpty().ifBlank { "Código 2FA inválido o expirado." }
            return@withContext Result.failure(IllegalStateException(msg))
        }

        val updatedAuth = auth.copy(
            session = auth.session.copy(
                totpEnabled = true,
                mustChangePassword = false,
            ),
        )
        saveOperatorAuthAtomic(updatedAuth)
        Result.success(inspectState())
    }

    suspend fun logoutOperator(): RurControlState = withContext(Dispatchers.IO) {
        secureDeleteFile(operatorSessionFile)
        purgeAllEphemeralEnvFiles()
        ramTokensByRobot.clear()
        lastSyncStatus = RurSyncStatus(ok = false, code = "LOGGED_OUT", mode = "local")
        inspectState()
    }

    suspend fun setOfflineBypass(enabled: Boolean): RurControlState = withContext(Dispatchers.IO) {
        synchronized(configLock) {
            val current = loadPersistedConfig()
            savePersistedConfig(current.copy(offlineBypassEnabled = enabled))
        }
        inspectState()
    }

    suspend fun saveSettings(
        webApiUrl: String,
        deviceKeyInput: String?, // null = keep existing; "" = clear; non-blank = save new
        defaultRosDomainId: Int,
        offboardRepoPath: String,
        scriptsPath: String,
        linkedPiSshDeviceId: String,
        customScripts: Map<String, String>,
    ): Result<RurControlState> = withContext(Dispatchers.IO) {
        val cleanUrl = validateWebApiUrl(webApiUrl).getOrElse { return@withContext Result.failure(it) }
        if (defaultRosDomainId !in 0..232) {
            return@withContext Result.failure(IllegalArgumentException("ROS_DOMAIN_ID debe estar entre 0 y 232."))
        }
        val cleanRepo = sanitizeRelativeOrHomePath(offboardRepoPath, RurControlState.DEFAULT_OFFBOARD_REPO_REL)
            ?: return@withContext Result.failure(IllegalArgumentException("Ruta de offboard_ws inválida."))
        val cleanScripts = sanitizeRelativeOrHomePath(scriptsPath, RurControlState.DEFAULT_SCRIPTS_REL)
            ?: return@withContext Result.failure(IllegalArgumentException("Ruta de scripts inválida."))

        val validatedScripts = LinkedHashMap<String, String>()
        for (slot in RurEditableScriptSlot.entries) {
            val rawCmd = customScripts[slot.key]?.trim().orEmpty()
            if (rawCmd.isNotEmpty()) {
                if (rawCmd.length > MAX_EDITABLE_CMD_LEN || rawCmd.any { it == '\u0000' || it == '\r' || it == '\n' }) {
                    return@withContext Result.failure(
                        IllegalArgumentException("Comando inválido en '${slot.label}' (una sola línea, máx $MAX_EDITABLE_CMD_LEN caracteres).")
                    )
                }
                if (rawCmd != slot.defaultCommand) {
                    validatedScripts[slot.key] = rawCmd
                }
            }
        }

        if (deviceKeyInput != null) {
            val trimmedKey = deviceKeyInput.trim()
            if (trimmedKey.isEmpty()) {
                secureDeleteFile(deviceKeyFile)
            } else {
                if (trimmedKey.length > 512 || trimmedKey.any { it.code < 0x20 || it.code == 0x7F || it.isWhitespace() }) {
                    return@withContext Result.failure(IllegalArgumentException("La Device Key contiene caracteres inválidos."))
                }
                if (!writeSecretFileAtomic(deviceKeyFile, trimmedKey)) {
                    return@withContext Result.failure(IllegalStateException("No se pudo guardar la Device Key (0600)."))
                }
            }
            ramTokensByRobot.clear()
        }

        val saved = synchronized(configLock) {
            val current = loadPersistedConfig()
            val updatedRobots = current.robots.ifEmpty { RurControlState.defaultRobots() }.map { r ->
                if (r.id == current.activeRobotId && !lastSyncStatus.ok) {
                    r.copy(rosDomainId = defaultRosDomainId)
                } else {
                    r
                }
            }
            val updated = current.copy(
                webApiUrl = cleanUrl,
                defaultRosDomainId = defaultRosDomainId,
                offboardRepoPath = cleanRepo,
                scriptsPath = cleanScripts,
                linkedPiSshDeviceId = linkedPiSshDeviceId.trim().take(64),
                customScripts = validatedScripts,
                robots = updatedRobots,
            )
            savePersistedConfig(updated)
        }
        if (!saved) {
            return@withContext Result.failure(IllegalStateException("No se pudo guardar rur-ops.json"))
        }
        Result.success(inspectState())
    }

    suspend fun selectActiveRobot(robotId: String): RurControlState = withContext(Dispatchers.IO) {
        val targetId = synchronized(configLock) {
            val current = loadPersistedConfig()
            val robots = current.robots.ifEmpty { RurControlState.defaultRobots() }
            val target = robots.firstOrNull { it.id == robotId } ?: robots.first()
            savePersistedConfig(current.copy(activeRobotId = target.id))
            target.id
        }
        ramTokensByRobot.clear()
        lastSyncStatus = lastSyncStatus.copy(pendingDriftNotice = null)
        fetchTokensForRobotInternal(targetId)
        inspectState()
    }

    suspend fun upsertLocalRobot(
        name: String,
        room: String,
        rosDomainId: Int,
        livekitUrl: String,
    ): Result<RurControlState> = withContext(Dispatchers.IO) {
        val cleanName = name.trim()
        val cleanRoom = room.trim()
        val cleanLk = livekitUrl.trim()
        if (cleanName.isEmpty() || cleanName.length > 48) {
            return@withContext Result.failure(IllegalArgumentException("Nombre del robot inválido (1..48 caracteres)."))
        }
        if (!cleanRoom.matches(Regex("^[A-Za-z0-9._-]{1,64}$"))) {
            return@withContext Result.failure(IllegalArgumentException("Sala LiveKit inválida (solo letras, números, . _ -)."))
        }
        if (rosDomainId !in 0..232) {
            return@withContext Result.failure(IllegalArgumentException("ROS_DOMAIN_ID debe estar entre 0 y 232."))
        }
        if (cleanLk.isNotEmpty() && !cleanLk.startsWith("wss://") && !cleanLk.startsWith("ws://")) {
            return@withContext Result.failure(IllegalArgumentException("URL LiveKit debe iniciar con wss:// o ws://"))
        }

        val idSlug = cleanName.lowercase(Locale.US)
            .replace(Regex("[^a-z0-9]+"), "-")
            .trim('-')
            .ifEmpty { "robot-${System.currentTimeMillis() % 10000}" }

        val saved = synchronized(configLock) {
            val current = loadPersistedConfig()
            val existing = current.robots.ifEmpty { RurControlState.defaultRobots() }.toMutableList()
            val record = RurRobotInfo(
                id = idSlug,
                name = cleanName,
                status = "offline",
                room = cleanRoom,
                rosDomainId = rosDomainId,
                livekitUrl = cleanLk,
                locomotionType = "tank",
                version = 1,
                hasCredentials = false,
            )
            val idx = existing.indexOfFirst { it.id == idSlug }
            if (idx >= 0) existing[idx] = record else existing.add(record)
            savePersistedConfig(current.copy(robots = existing, activeRobotId = record.id))
        }
        if (!saved) {
            return@withContext Result.failure(IllegalStateException("No se pudo guardar el robot local."))
        }
        Result.success(inspectState())
    }

    /**
     * Synchronizes robot fleet from PAGE_RUR:
     * 1) If Device Key is configured -> GET /api/control-center/robots with ETag + hot drift detection
     * 2) Else if Operator Session is active -> GET /api/robots (Bearer/Cookie), fallback /api/public-robots
     */
    suspend fun syncFleetFromPageRur(forceTokens: Boolean = false): RurControlState = withContext(Dispatchers.IO) {
        syncFleetFromPageRurInternal(forceTokens)
        inspectState()
    }

    private fun syncFleetFromPageRurInternal(forceTokens: Boolean) {
        val persisted = synchronized(configLock) { loadPersistedConfig() }
        val baseUrl = validateWebApiUrl(persisted.webApiUrl).getOrNull() ?: run {
            lastSyncStatus = lastSyncStatus.copy(ok = false, code = "INVALID_URL", error = "URL de PAGE_RUR inválida.")
            return
        }
        val deviceKey = readSecretFile(deviceKeyFile)
        val operatorAuth = loadValidOperatorAuth()

        if (deviceKey.isNotEmpty()) {
            syncViaControlCenterDeviceKey(persisted, baseUrl, deviceKey, forceTokens)
        } else if (operatorAuth != null) {
            syncViaOperatorSession(persisted, baseUrl, operatorAuth, forceTokens)
        } else {
            lastSyncStatus = RurSyncStatus(
                ok = false,
                code = "AUTH_REQUIRED",
                mode = "local",
                error = "Inicia sesión de operador RUR o usa modo local.",
            )
        }
    }

    private fun syncViaControlCenterDeviceKey(
        persisted: PersistedRurConfig,
        baseUrl: String,
        deviceKey: String,
        forceTokens: Boolean,
    ) {
        val headers = LinkedHashMap<String, String>().apply {
            put("Authorization", "Bearer $deviceKey")
            put("Accept", "application/json")
            put("User-Agent", USER_AGENT)
            lastSyncStatus.etag?.takeIf { it.isNotBlank() }?.let { put("If-None-Match", it) }
        }
        val res = runCatching {
            executeHttpJson("GET", "$baseUrl/api/control-center/robots", headers, null)
        }.getOrElse { err ->
            lastSyncStatus = lastSyncStatus.copy(
                ok = false,
                code = "NET_UNREACHABLE",
                error = "Sin conexión con PAGE_RUR (${err.message ?: "red"}).",
            )
            return
        }

        when (res.statusCode) {
            304 -> {
                lastSyncStatus = lastSyncStatus.copy(
                    ok = true,
                    code = "OK",
                    mode = "device_key_etag",
                    lastSyncIso = nowIsoUtc(),
                    error = null,
                )
                if (forceTokens || ramTokensByRobot[persisted.activeRobotId]?.hasAnyToken != true) {
                    fetchTokensForRobotInternal(persisted.activeRobotId)
                }
                return
            }
            401 -> {
                ramTokensByRobot.clear()
                lastSyncStatus = lastSyncStatus.copy(
                    ok = false,
                    code = "DEVICE_REVOKED",
                    error = "Device Key rechazada o revocada en PAGE_RUR.",
                )
                return
            }
            429 -> {
                lastSyncStatus = lastSyncStatus.copy(
                    ok = false,
                    code = "RATE_LIMITED",
                    error = "Límite de peticiones alcanzado; reintentando en el próximo ciclo.",
                )
                return
            }
        }
        if (res.statusCode !in 200..299) {
            lastSyncStatus = lastSyncStatus.copy(
                ok = false,
                code = "HTTP_${res.statusCode}",
                error = "Error HTTP ${res.statusCode} desde PAGE_RUR.",
            )
            return
        }

        val parsedRobots = parseRobotsPayload(res.body, persisted.defaultRosDomainId)
        if (parsedRobots.isEmpty()) {
            lastSyncStatus = lastSyncStatus.copy(ok = false, code = "EMPTY_CATALOG", error = "Catálogo vacío.")
            return
        }

        val (drift, nextActiveId) = synchronized(configLock) {
            val latest = loadPersistedConfig()
            val d = computeDriftNotice(latest, parsedRobots)
            val nextId = if (parsedRobots.any { it.id == latest.activeRobotId }) latest.activeRobotId else parsedRobots.first().id
            savePersistedConfig(latest.copy(robots = parsedRobots, activeRobotId = nextId))
            d to nextId
        }

        lastSyncStatus = RurSyncStatus(
            ok = true,
            code = "OK",
            mode = "device_key_etag",
            lastSyncIso = nowIsoUtc(),
            error = null,
            etag = res.etag ?: lastSyncStatus.etag,
            pendingDriftNotice = drift,
        )

        if (forceTokens || ramTokensByRobot[nextActiveId]?.hasAnyToken != true) {
            fetchTokensForRobotInternal(nextActiveId)
        }
    }

    private fun syncViaOperatorSession(
        persisted: PersistedRurConfig,
        baseUrl: String,
        operatorAuth: StoredOperatorAuth,
        forceTokens: Boolean,
    ) {
        val headers = buildOperatorAuthHeaders(operatorAuth, includeCsrf = false)
        var res = runCatching {
            executeHttpJson("GET", "$baseUrl/api/robots", headers, null)
        }.getOrElse { err ->
            lastSyncStatus = lastSyncStatus.copy(
                ok = false,
                code = "NET_UNREACHABLE",
                error = "Sin conexión con PAGE_RUR (${err.message ?: "red"}).",
            )
            return
        }

        if (res.statusCode == 401 || res.statusCode == 403) {
            // Try PAGE_RUR public robots fallback (/api/robots/publicos, then legacy /api/public-robots)
            val pub = runCatching {
                executeHttpJson("GET", "$baseUrl/api/robots/publicos", mapOf("User-Agent" to USER_AGENT), null)
            }.getOrNull()?.takeIf { it.statusCode in 200..299 }
                ?: runCatching {
                    executeHttpJson("GET", "$baseUrl/api/public-robots", mapOf("User-Agent" to USER_AGENT), null)
                }.getOrNull()
            if (pub != null && pub.statusCode in 200..299) {
                res = pub
            } else {
                ramTokensByRobot.clear()
                lastSyncStatus = lastSyncStatus.copy(
                    ok = false,
                    code = "SESSION_EXPIRED",
                    error = "Sesión expirada en PAGE_RUR (HTTP ${res.statusCode}). Vuelve a iniciar sesión.",
                )
                return
            }
        }

        if (res.statusCode !in 200..299) {
            lastSyncStatus = lastSyncStatus.copy(
                ok = false,
                code = "HTTP_${res.statusCode}",
                error = "Error HTTP ${res.statusCode} consultando /api/robots.",
            )
            return
        }

        val parsedRobots = parseRobotsPayload(res.body, persisted.defaultRosDomainId)
        if (parsedRobots.isNotEmpty()) {
            val (drift, nextActiveId) = synchronized(configLock) {
                val latest = loadPersistedConfig()
                val d = computeDriftNotice(latest, parsedRobots)
                val nextId = if (parsedRobots.any { it.id == latest.activeRobotId }) latest.activeRobotId else parsedRobots.first().id
                savePersistedConfig(latest.copy(robots = parsedRobots, activeRobotId = nextId))
                d to nextId
            }
            lastSyncStatus = RurSyncStatus(
                ok = true,
                code = "OK",
                mode = "operator_db",
                lastSyncIso = nowIsoUtc(),
                error = null,
                etag = res.etag,
                pendingDriftNotice = drift,
            )
            if (forceTokens || ramTokensByRobot[nextActiveId]?.hasAnyToken != true) {
                fetchTokensForRobotInternal(nextActiveId)
            }
        } else {
            lastSyncStatus = lastSyncStatus.copy(
                ok = true,
                code = "OK_EMPTY",
                mode = "operator_db",
                lastSyncIso = nowIsoUtc(),
                error = null,
            )
        }
    }

    suspend fun fetchTokensForRobot(robotId: String): RurLiveKitTokens = withContext(Dispatchers.IO) {
        fetchTokensForRobotInternal(robotId)
    }

    private fun fetchTokensForRobotInternal(robotId: String): RurLiveKitTokens {
        val persisted = synchronized(configLock) { loadPersistedConfig() }
        val robot = persisted.robots.firstOrNull { it.id == robotId }
            ?: RurControlState.defaultRobots().first()
        val baseUrl = validateWebApiUrl(persisted.webApiUrl).getOrNull()
        val deviceKey = readSecretFile(deviceKeyFile)
        val operatorAuth = loadValidOperatorAuth()

        var receiverTok = ""
        var cameraTok = ""
        var operatorTok = ""
        var lkUrl = robot.livekitUrl
        var lkRoom = robot.room
        var domainId = robot.rosDomainId
        var expiresAt = ""
        var source = "local_env"

        // 1. Control Center Device Key tokens (receiver + camera) if configured
        if (baseUrl != null && deviceKey.isNotEmpty()) {
            val encodedId = URLEncoder.encode(robot.id, StandardCharsets.UTF_8.name())
            val res = runCatching {
                executeHttpJson(
                    method = "POST",
                    urlStr = "$baseUrl/api/control-center/robots/$encodedId/tokens",
                    headers = mapOf(
                        "Authorization" to "Bearer $deviceKey",
                        "Content-Type" to "application/json",
                        "Accept" to "application/json",
                        "User-Agent" to USER_AGENT,
                    ),
                    body = "{}",
                )
            }.getOrNull()
            if (res != null && res.statusCode in 200..299) {
                val json = runCatching { JSONObject(res.body) }.getOrNull()
                if (json != null && json.optBoolean("success", false)) {
                    val tokensObj = json.optJSONObject("tokens")
                    receiverTok = tokensObj?.optString("receiver", "").orEmpty().trim()
                    cameraTok = tokensObj?.optString("camera", "").orEmpty().trim()
                    lkUrl = json.optString("livekitUrl", lkUrl).ifBlank { lkUrl }
                    lkRoom = json.optString("room", lkRoom).ifBlank { lkRoom }
                    domainId = json.optInt("rosDomainId", domainId).coerceIn(0, 232)
                    expiresAt = json.optString("expiresAt", "")
                    if (receiverTok.isNotEmpty() || cameraTok.isNotEmpty()) {
                        source = "control_center_key"
                    }
                }
            }
        }

        // 2. Operator/Viewer DB token (POST /api/livekit-token in PAGE_RUR svc-robots issues operator or viewer token based on robots.operar permission!)
        if (baseUrl != null && operatorAuth != null) {
            val headers = buildOperatorAuthHeaders(operatorAuth, includeCsrf = true) +
                ("Content-Type" to "application/json")
            val body = JSONObject().put("robotId", robot.id).toString()
            val res = runCatching {
                executeHttpJson("POST", "$baseUrl/api/livekit-token", headers, body)
            }.getOrNull()
            if (res != null && res.statusCode in 200..299) {
                val json = runCatching { JSONObject(res.body) }.getOrNull()
                val tok = json?.optString("token", "").orEmpty().trim()
                if (json != null && json.optBoolean("success", false) && tok.isNotEmpty()) {
                    if (operatorAuth.session.canOperate) {
                        operatorTok = tok
                    }
                    if (receiverTok.isEmpty()) receiverTok = tok
                    lkUrl = json.optString("livekitUrl", lkUrl).ifBlank { lkUrl }
                    lkRoom = json.optString("room", lkRoom).ifBlank { lkRoom }
                    source = if (source == "control_center_key") "control_center+operator" else "operator_db"
                }
            }

            // Read-only viewer token fallback (POST /api/livekit/public-token in PAGE_RUR, or legacy GET /api/livekit-viewer-token/:id)
            if (receiverTok.isEmpty() && operatorTok.isEmpty()) {
                val pubRes = runCatching {
                    executeHttpJson(
                        method = "POST",
                        urlStr = "$baseUrl/api/livekit/public-token",
                        headers = mapOf(
                            "Content-Type" to "application/json",
                            "Accept" to "application/json",
                            "User-Agent" to USER_AGENT,
                        ),
                        body = JSONObject().put("robotId", robot.id).toString(),
                    )
                }.getOrNull()?.takeIf { it.statusCode in 200..299 }
                    ?: runCatching {
                        val encodedId = URLEncoder.encode(robot.id, StandardCharsets.UTF_8.name())
                        executeHttpJson(
                            method = "GET",
                            urlStr = "$baseUrl/api/livekit-viewer-token/$encodedId",
                            headers = buildOperatorAuthHeaders(operatorAuth, includeCsrf = false),
                            body = null,
                        )
                    }.getOrNull()
                if (pubRes != null && pubRes.statusCode in 200..299) {
                    val json = runCatching { JSONObject(pubRes.body) }.getOrNull()
                    val tok = json?.optString("token", "").orEmpty().trim()
                    if (json != null && json.optBoolean("success", false) && tok.isNotEmpty()) {
                        receiverTok = tok
                        lkUrl = json.optString("livekitUrl", lkUrl).ifBlank { lkUrl }
                        lkRoom = json.optString("room", lkRoom).ifBlank { lkRoom }
                        source = "viewer_db"
                    }
                }
            }
        }

        val bundle = RurLiveKitTokens(
            robotId = robot.id,
            receiverToken = receiverTok,
            cameraToken = cameraTok,
            operatorToken = operatorTok,
            livekitUrl = lkUrl,
            room = lkRoom,
            rosDomainId = domainId,
            expiresAt = expiresAt,
            source = source,
        )
        if (bundle.hasAnyToken) {
            ramTokensByRobot[robot.id] = bundle
        }
        return bundle
    }

    // ============================================================================================
    // PTY EXECVE BUILDERS (Positional-arg safe, ZERO argv[] secret exposure, ZERO quote injection)
    // ============================================================================================

    fun buildInstallRos2PtyExec(): Pair<String, Array<String>> {
        val shBin = File(prefixDir, "bin/sh").absolutePath
        val workstationBin = File(prefixDir, "bin/workstation").absolutePath
        val loginBin = File(prefixDir, "bin/login").absolutePath
        val bashBin = File(prefixDir, "bin/bash").absolutePath
        return shBin to arrayOf(
            "-c",
            INSTALL_ROS2_WRAPPER_SCRIPT,
            "rur-install-ros2",
            workstationBin,
            loginBin,
            bashBin,
            shBin,
        )
    }

    fun buildRos2InteractiveShellExec(state: RurControlState): Pair<String, Array<String>> {
        val envFile = writeEphemeralEnvFile(state, RurTokenRole.RECEIVER)
        val offboardDir = File(resolveUserPath(state.offboardRepoPath), "offboard_ws").absolutePath
        return buildProotBashExec(
            workDir = offboardDir,
            rosCmd = "",
            envFile = envFile,
            interactive = true,
            bannerTitle = "RUR Ubuntu 24.04 · ROS 2 Jazzy · ${state.activeRobot.name} (DOMAIN=${state.effectiveRosDomainId})",
        )
    }

    fun buildRos2AiAgentPtyExec(agentBinary: String, state: RurControlState): Pair<String, Array<String>> {
        require(agentBinary == "agy" || agentBinary == "opencode") {
            "Unsupported ROS 2 AI agent: $agentBinary"
        }
        val envFile = writeEphemeralEnvFile(state, RurTokenRole.NONE)
        val repoDir = resolveUserPath(state.offboardRepoPath).absolutePath
        val shBin = File(prefixDir, "bin/sh").absolutePath
        return shBin to arrayOf(
            "-c",
            PROOT_AI_WRAPPER_SCRIPT,
            "rur-ros2-ai",
            File(prefixDir, "bin/proot-distro").absolutePath,
            homeDir.absolutePath,
            prefixDir.absolutePath,
            repoDir,
            envFile.absolutePath,
            agentBinary,
        )
    }

    fun buildModulePtyExec(module: RurModuleSpec, state: RurControlState): Pair<String, Array<String>> {
        if (module.outputKind == RurModuleOutputKind.INTERACTIVE_SHELL) {
            return buildRos2InteractiveShellExec(state)
        }
        val envFile = writeEphemeralEnvFile(state, module.tokenRole)
        val workDir = if (module.runInScriptsDir) {
            resolveUserPath(state.scriptsPath).absolutePath
        } else {
            File(resolveUserPath(state.offboardRepoPath), "offboard_ws").absolutePath
        }
        val cmd = state.resolvedCommandFor(module)
        return buildProotBashExec(
            workDir = workDir,
            rosCmd = cmd,
            envFile = envFile,
            interactive = false,
            bannerTitle = "RUR offboard_ws · ${module.label} · ${state.activeRobot.name} (DOMAIN=${state.effectiveRosDomainId})",
        )
    }

    fun cleanupEphemeralEnvFromArgv(argv: Array<String>) {
        val envPath = argv.firstOrNull { it.startsWith(cacheDir.absolutePath + "/.env.") } ?: return
        secureDeleteFile(File(envPath))
    }

    private fun buildProotBashExec(
        workDir: String,
        rosCmd: String,
        envFile: File,
        interactive: Boolean,
        bannerTitle: String,
    ): Pair<String, Array<String>> {
        val shBin = File(prefixDir, "bin/sh").absolutePath
        return shBin to arrayOf(
            "-c",
            PROOT_ROS2_WRAPPER_SCRIPT,
            "rur-ros2",
            File(prefixDir, "bin/proot-distro").absolutePath,
            homeDir.absolutePath,
            prefixDir.absolutePath,
            workDir,
            envFile.absolutePath,
            if (interactive) "1" else "0",
            bannerTitle,
            rosCmd,
        )
    }

    /**
     * Writes an ephemeral 0600 env file under ~/.cache/melcat-rur/ so LIVEKIT_TOKEN never
     * appears on argv[] or /proc/pid/cmdline. The wrapper sources it and deletes it immediately.
     */
    internal fun writeEphemeralEnvFile(state: RurControlState, tokenRole: RurTokenRole): File {
        ensureSafePrivateDir(cacheDir)
        cleanupStaleEphemeralEnvFiles()
        val randBytes = ByteArray(16).also { secureRandom.nextBytes(it) }
        val suffix = randBytes.joinToString("") { "%02x".format(it) }
        val file = File(cacheDir, ".env.$suffix")

        val robot = state.activeRobot
        val ramTokens = ramTokensByRobot[robot.id]
        val resolvedToken = when (tokenRole) {
            RurTokenRole.RECEIVER -> ramTokens?.receiverToken?.ifBlank { ramTokens.operatorToken }.orEmpty()
            RurTokenRole.CAMERA -> ramTokens?.cameraToken?.ifBlank { ramTokens.receiverToken }.orEmpty()
            RurTokenRole.OPERATOR -> ramTokens?.operatorToken?.ifBlank { ramTokens.receiverToken }.orEmpty()
            RurTokenRole.NONE -> ""
        }
        val resolvedUrl = ramTokens?.livekitUrl?.ifBlank { robot.livekitUrl } ?: robot.livekitUrl
        val resolvedRoom = ramTokens?.room?.ifBlank { robot.room } ?: robot.room

        val sb = StringBuilder()
        sb.append("export ROS_DOMAIN_ID=").append(posixSingleQuote(state.effectiveRosDomainId.toString())).append('\n')
        sb.append("export RMW_IMPLEMENTATION='rmw_cyclonedds_cpp'\n")
        sb.append("export PYTHONIOENCODING='utf-8'\n")
        sb.append("export PYTHONUNBUFFERED='1'\n")
        sb.append("export RUR_ROBOT_ID=").append(posixSingleQuote(robot.id)).append('\n')
        sb.append("export RUR_ROBOT_NAME=").append(posixSingleQuote(robot.name)).append('\n')
        if (resolvedUrl.isNotBlank()) {
            sb.append("export LIVEKIT_URL=").append(posixSingleQuote(resolvedUrl)).append('\n')
        }
        if (resolvedRoom.isNotBlank()) {
            sb.append("export LIVEKIT_ROOM=").append(posixSingleQuote(resolvedRoom)).append('\n')
        }
        if (resolvedToken.isNotBlank()) {
            sb.append("export LIVEKIT_TOKEN=").append(posixSingleQuote(resolvedToken)).append('\n')
        }
        state.operatorSession?.takeIf { !it.isExpired() }?.let { op ->
            sb.append("export RUR_OPERATOR_MATRICULA=").append(posixSingleQuote(op.matricula)).append('\n')
            sb.append("export RUR_OPERATOR_ROLE=").append(posixSingleQuote(op.primaryRoleLabel)).append('\n')
        }

        writeStrictNew0600File(file, sb.toString().toByteArray(StandardCharsets.UTF_8))
        return file
    }

    private fun cleanupStaleEphemeralEnvFiles() {
        val cutoff = System.currentTimeMillis() - 60_000L
        cacheDir.listFiles()?.forEach { f ->
            if (f.name.startsWith(".env.") && f.lastModified() < cutoff) {
                secureDeleteFile(f)
            }
        }
    }

    private fun purgeAllEphemeralEnvFiles() {
        cacheDir.listFiles()?.forEach { f ->
            if (f.name.startsWith(".env.")) {
                secureDeleteFile(f)
            }
        }
    }

    // ============================================================================================
    // INTERNAL HTTP, PARSING & 0600 STORAGE HELPERS
    // ============================================================================================

    private data class HttpJsonResult(
        val statusCode: Int,
        val body: String,
        val etag: String?,
        val cookieHeader: String,
    )

    private fun executeHttpJson(
        method: String,
        urlStr: String,
        headers: Map<String, String>,
        body: String?,
    ): HttpJsonResult {
        var currentUrl = urlStr
        var currentMethod = method
        var currentBody = body
        val originUri = URI(urlStr)
        val hasSensitiveReq = body != null || headers.keys.any {
            it.equals("Authorization", ignoreCase = true) ||
                it.equals("Cookie", ignoreCase = true) ||
                it.equals("X-CSRF-Token", ignoreCase = true)
        }

        for (hop in 0..MAX_REDIRECTS) {
            val targetUri = URI(currentUrl)
            val sameSecureOrigin = targetUri.scheme.equals(originUri.scheme, ignoreCase = true) &&
                targetUri.host.equals(originUri.host, ignoreCase = true) &&
                effectivePort(targetUri) == effectivePort(originUri)

            val filteredHeaders = LinkedHashMap<String, String>()
            headers.forEach { (k, v) ->
                val isSensitive = k.equals("Authorization", ignoreCase = true) ||
                    k.equals("Cookie", ignoreCase = true) ||
                    k.equals("X-CSRF-Token", ignoreCase = true)
                if (!isSensitive || sameSecureOrigin) {
                    filteredHeaders[k] = v
                }
            }

            val hopRes = try {
                performSingleHttpHop(
                    urlStr = currentUrl,
                    method = currentMethod,
                    headers = filteredHeaders,
                    body = currentBody,
                    overrideIp = null,
                    sniHostname = null,
                )
            } catch (uhe: UnknownHostException) {
                val host = targetUri.host?.lowercase(Locale.US).orEmpty()
                if (targetUri.scheme.equals("https", ignoreCase = true) && host.isNotEmpty()) {
                    val resolvedIp = resolveHostViaDoHOrFallback(host)
                        ?: throw uhe
                    performSingleHttpHop(
                        urlStr = currentUrl,
                        method = currentMethod,
                        headers = filteredHeaders,
                        body = currentBody,
                        overrideIp = resolvedIp,
                        sniHostname = host,
                    )
                } else {
                    throw uhe
                }
            }

            val code = hopRes.statusCode
            if (code in setOf(301, 302, 303, 307, 308) && hop < MAX_REDIRECTS) {
                val loc = hopRes.redirectLocation.orEmpty()
                if (loc.isNotEmpty()) {
                    val nextUri = URI(currentUrl).resolve(loc)
                    validateWebApiUrl(nextUri.toString()).getOrThrow()
                    val sameOrigin = nextUri.scheme.equals(originUri.scheme, ignoreCase = true) &&
                        nextUri.host.equals(originUri.host, ignoreCase = true) &&
                        effectivePort(nextUri) == effectivePort(originUri)
                    if (hasSensitiveReq && !sameOrigin) {
                        throw SecurityException("Redirección cross-origin bloqueada para solicitud autenticada.")
                    }
                    currentUrl = nextUri.toString()
                    if (code == 303) {
                        currentMethod = "GET"
                        currentBody = null
                    }
                    continue
                }
            }

            return HttpJsonResult(
                statusCode = hopRes.statusCode,
                body = hopRes.body,
                etag = hopRes.etag,
                cookieHeader = hopRes.cookieHeader,
            )
        }
        throw IllegalStateException("Demasiadas redirecciones HTTP.")
    }

    private data class SingleHopResult(
        val statusCode: Int,
        val body: String,
        val etag: String?,
        val cookieHeader: String,
        val redirectLocation: String?,
    )

    private fun performSingleHttpHop(
        urlStr: String,
        method: String,
        headers: Map<String, String>,
        body: String?,
        overrideIp: String?,
        sniHostname: String?,
    ): SingleHopResult {
        val conn = if (overrideIp != null && sniHostname != null) {
            openHttpsConnectionViaDirectIpWithSni(urlStr, overrideIp, sniHostname)
        } else {
            URL(urlStr).openConnection() as HttpURLConnection
        }.apply {
            instanceFollowRedirects = false
            requestMethod = method
            connectTimeout = 10_000
            readTimeout = 12_000
            doInput = true
            headers.forEach { (k, v) -> setRequestProperty(k, v) }
        }
        try {
            if (body != null && (method == "POST" || method == "PUT" || method == "PATCH")) {
                conn.doOutput = true
                val bytes = body.toByteArray(StandardCharsets.UTF_8)
                conn.setFixedLengthStreamingMode(bytes.size)
                conn.outputStream.use { it.write(bytes) }
            }
            val code = conn.responseCode
            val loc = conn.getHeaderField("Location")?.trim()
            val stream = if (code in 200..399) conn.inputStream else (conn.errorStream ?: conn.inputStream)
            val text = if (stream != null) readBoundedUtf8(stream, MAX_HTTP_BODY_BYTES) else ""
            val cookies = extractMergedCookies(conn)
            val etag = conn.getHeaderField("ETag")
            return SingleHopResult(
                statusCode = code,
                body = text,
                etag = etag,
                cookieHeader = cookies,
                redirectLocation = loc,
            )
        } finally {
            conn.disconnect()
        }
    }

    private fun extractMergedCookies(conn: HttpURLConnection): String {
        val pairs = LinkedHashMap<String, String>()
        conn.headerFields.forEach { (key, values) ->
            if (key != null && key.equals("Set-Cookie", ignoreCase = true) && values != null) {
                values.forEach { raw ->
                    val firstPart = raw.substringBefore(';').trim()
                    val eqIdx = firstPart.indexOf('=')
                    if (eqIdx > 0) {
                        val name = firstPart.substring(0, eqIdx).trim()
                        val value = firstPart.substring(eqIdx + 1).trim()
                        if (name.isNotEmpty()) {
                            pairs[name] = value
                        }
                    }
                }
            }
        }
        return pairs.entries.joinToString("; ") { "${it.key}=${it.value}" }
    }

    private fun buildOperatorAuthHeaders(auth: StoredOperatorAuth, includeCsrf: Boolean): Map<String, String> {
        val map = LinkedHashMap<String, String>()
        map["Accept"] = "application/json"
        map["User-Agent"] = USER_AGENT
        if (auth.bearerToken.isNotBlank()) {
            map["Authorization"] = "Bearer ${auth.bearerToken}"
        }
        if (auth.cookieHeader.isNotBlank()) {
            map["Cookie"] = auth.cookieHeader
        }
        if (includeCsrf && auth.csrfToken.isNotBlank()) {
            map["X-CSRF-Token"] = auth.csrfToken
        }
        return map
    }

    private fun computeDriftNotice(persisted: PersistedRurConfig, freshRobots: List<RurRobotInfo>): String? {
        val oldActive = persisted.robots.firstOrNull { it.id == persisted.activeRobotId } ?: return null
        val freshActive = freshRobots.firstOrNull { it.id == persisted.activeRobotId }
            ?: return "El robot activo (${oldActive.name}) ya no está en PAGE_RUR."
        val changed = mutableListOf<String>()
        if (freshActive.room != oldActive.room) changed += "Sala (${freshActive.room})"
        if (freshActive.rosDomainId != oldActive.rosDomainId) changed += "Domain (${freshActive.rosDomainId})"
        if (freshActive.livekitUrl.isNotBlank() && freshActive.livekitUrl != oldActive.livekitUrl) changed += "URL LiveKit"
        if (freshActive.version != oldActive.version) changed += "Config v${freshActive.version}"
        return if (changed.isEmpty()) null else "Cambio detectado en PAGE_RUR: ${changed.joinToString(", ")}"
    }

    private fun resolveUserPath(raw: String): File {
        val trimmed = raw.trim().ifEmpty { RurControlState.DEFAULT_OFFBOARD_REPO_REL }
        return if (trimmed.startsWith("/")) {
            File(trimmed)
        } else if (trimmed.startsWith("~/")) {
            File(homeDir, trimmed.removePrefix("~/"))
        } else {
            File(homeDir, trimmed)
        }
    }

    private data class PersistedRurConfig(
        val webApiUrl: String = RurControlState.DEFAULT_WEB_API_URL,
        val activeRobotId: String = RurControlState.DEFAULT_ROBOT_ID,
        val defaultRosDomainId: Int = RurControlState.DEFAULT_ROS_DOMAIN_ID,
        val offboardRepoPath: String = RurControlState.DEFAULT_OFFBOARD_REPO_REL,
        val scriptsPath: String = RurControlState.DEFAULT_SCRIPTS_REL,
        val linkedPiSshDeviceId: String = "",
        val offlineBypassEnabled: Boolean = false,
        val customScripts: Map<String, String> = emptyMap(),
        val robots: List<RurRobotInfo> = RurControlState.defaultRobots(),
    )

    private fun loadPersistedConfig(): PersistedRurConfig {
        if (!configFile.isFile || isSymlinkFailClosed(configFile)) return PersistedRurConfig()
        val raw = runCatching { configFile.readText(StandardCharsets.UTF_8) }.getOrNull() ?: return PersistedRurConfig()
        val obj = runCatching { JSONObject(raw) }.getOrNull() ?: return PersistedRurConfig()

        val scriptsMap = LinkedHashMap<String, String>()
        val scriptsObj = obj.optJSONObject("customScripts")
        if (scriptsObj != null) {
            for (slot in RurEditableScriptSlot.entries) {
                val v = scriptsObj.optString(slot.key, "").trim()
                if (v.isNotEmpty() && v.length <= MAX_EDITABLE_CMD_LEN && v.none { it == '\u0000' || it == '\r' || it == '\n' }) {
                    scriptsMap[slot.key] = v
                }
            }
        }

        val robotsArr = obj.optJSONArray("robots")
        val robots = mutableListOf<RurRobotInfo>()
        if (robotsArr != null) {
            for (i in 0 until robotsArr.length()) {
                val r = robotsArr.optJSONObject(i) ?: continue
                parseSingleRobotJson(r, obj.optInt("defaultRosDomainId", RurControlState.DEFAULT_ROS_DOMAIN_ID))
                    ?.let { robots.add(it) }
            }
        }

        return PersistedRurConfig(
            webApiUrl = obj.optString("webApiUrl", RurControlState.DEFAULT_WEB_API_URL).ifBlank { RurControlState.DEFAULT_WEB_API_URL },
            activeRobotId = obj.optString("activeRobotId", RurControlState.DEFAULT_ROBOT_ID).ifBlank { RurControlState.DEFAULT_ROBOT_ID },
            defaultRosDomainId = obj.optInt("defaultRosDomainId", RurControlState.DEFAULT_ROS_DOMAIN_ID).coerceIn(0, 232),
            offboardRepoPath = obj.optString("offboardRepoPath", RurControlState.DEFAULT_OFFBOARD_REPO_REL).ifBlank { RurControlState.DEFAULT_OFFBOARD_REPO_REL },
            scriptsPath = obj.optString("scriptsPath", RurControlState.DEFAULT_SCRIPTS_REL).ifBlank { RurControlState.DEFAULT_SCRIPTS_REL },
            linkedPiSshDeviceId = obj.optString("linkedPiSshDeviceId", "").trim(),
            offlineBypassEnabled = obj.optBoolean("offlineBypassEnabled", false),
            customScripts = scriptsMap,
            robots = robots.ifEmpty { RurControlState.defaultRobots() },
        )
    }

    private fun savePersistedConfig(cfg: PersistedRurConfig): Boolean {
        ensureSafePrivateDir(configDir)
        val obj = JSONObject().apply {
            put("webApiUrl", cfg.webApiUrl)
            put("activeRobotId", cfg.activeRobotId)
            put("defaultRosDomainId", cfg.defaultRosDomainId)
            put("offboardRepoPath", cfg.offboardRepoPath)
            put("scriptsPath", cfg.scriptsPath)
            put("linkedPiSshDeviceId", cfg.linkedPiSshDeviceId)
            put("offlineBypassEnabled", cfg.offlineBypassEnabled)
            put("customScripts", JSONObject().apply {
                cfg.customScripts.forEach { (k, v) -> put(k, v) }
            })
            put("robots", JSONArray().apply {
                cfg.robots.forEach { r ->
                    put(
                        JSONObject().apply {
                            put("id", r.id)
                            put("name", r.name)
                            put("status", r.status)
                            put("room", r.room)
                            put("rosDomainId", r.rosDomainId)
                            put("livekitUrl", r.livekitUrl)
                            put("locomotionType", r.locomotionType)
                            put("version", r.version)
                            put("hasCredentials", r.hasCredentials)
                        }
                    )
                }
            })
        }
        return writeAtomic0600(configFile, obj.toString(2).toByteArray(StandardCharsets.UTF_8))
    }

    private fun loadValidOperatorAuth(): StoredOperatorAuth? {
        if (!operatorSessionFile.isFile || isSymlinkFailClosed(operatorSessionFile)) return null
        val raw = runCatching { operatorSessionFile.readText(StandardCharsets.UTF_8) }.getOrNull() ?: return null
        val obj = runCatching { JSONObject(raw) }.getOrNull() ?: return null
        val expiresAt = obj.optLong("expiresAtEpochMs", 0L)
        if (expiresAt <= System.currentTimeMillis()) {
            secureDeleteFile(operatorSessionFile)
            return null
        }
        val rolesList = mutableListOf<String>()
        val rolesArr = obj.optJSONArray("roles")
        if (rolesArr != null) {
            for (i in 0 until rolesArr.length()) {
                rolesArr.optString(i, "").trim().takeIf { it.isNotEmpty() }?.let { rolesList.add(it) }
            }
        }
        val permisosList = mutableListOf<String>()
        val permisosArr = obj.optJSONArray("permisos")
        if (permisosArr != null) {
            for (i in 0 until permisosArr.length()) {
                permisosArr.optString(i, "").trim().takeIf { it.isNotEmpty() }?.let { permisosList.add(it) }
            }
        }
        val session = RurOperatorSession(
            id = obj.optString("id", ""),
            matricula = obj.optString("matricula", ""),
            nombre = obj.optString("nombre", ""),
            alias = obj.optString("alias", ""),
            grupo = obj.optString("grupo", ""),
            roles = rolesList.ifEmpty { listOf("miembro") },
            totpEnabled = obj.optBoolean("totpEnabled", false),
            mustChangePassword = obj.optBoolean("mustChangePassword", false),
            loginAtIso = obj.optString("loginAtIso", ""),
            expiresAtEpochMs = expiresAt,
            hasSessionAuth = obj.optString("bearerToken").isNotBlank() || obj.optString("cookieHeader").isNotBlank(),
            permisos = permisosList,
        )
        if (session.matricula.isBlank()) return null
        return StoredOperatorAuth(
            session = session,
            bearerToken = obj.optString("bearerToken", ""),
            cookieHeader = obj.optString("cookieHeader", ""),
            csrfToken = obj.optString("csrfToken", ""),
        )
    }

    private fun saveOperatorAuthAtomic(auth: StoredOperatorAuth): Boolean {
        ensureSafePrivateDir(vaultDir)
        val obj = JSONObject().apply {
            put("id", auth.session.id)
            put("matricula", auth.session.matricula)
            put("nombre", auth.session.nombre)
            put("alias", auth.session.alias)
            put("grupo", auth.session.grupo)
            put("roles", JSONArray(auth.session.roles))
            put("permisos", JSONArray(auth.session.permisos))
            put("totpEnabled", auth.session.totpEnabled)
            put("mustChangePassword", auth.session.mustChangePassword)
            put("loginAtIso", auth.session.loginAtIso)
            put("expiresAtEpochMs", auth.session.expiresAtEpochMs)
            put("bearerToken", auth.bearerToken)
            put("cookieHeader", auth.cookieHeader)
            put("csrfToken", auth.csrfToken)
        }
        return writeAtomic0600(operatorSessionFile, obj.toString(2).toByteArray(StandardCharsets.UTF_8))
    }

    private fun readSecretFile(file: File): String {
        if (!file.isFile || isSymlinkFailClosed(file)) return ""
        return runCatching { file.readText(StandardCharsets.UTF_8).trim() }.getOrDefault("")
    }

    private fun writeSecretFileAtomic(target: File, secret: String): Boolean {
        ensureSafePrivateDir(vaultDir)
        return writeAtomic0600(target, (secret + "\n").toByteArray(StandardCharsets.UTF_8))
    }

    private fun ensureSafePrivateDir(dir: File) {
        if (isSymlinkFailClosed(dir)) {
            throw SecurityException("Refusing symlinked directory: ${dir.absolutePath}")
        }
        dir.mkdirs()
        runCatching { Os.chmod(dir.absolutePath, 448) } // 0700
    }

    private fun writeStrictNew0600File(target: File, bytes: ByteArray) {
        val parent = target.parentFile ?: throw IllegalStateException("Missing parent dir")
        ensureSafePrivateDir(parent)
        val posixOk = runCatching {
            val flags = OsConstants.O_WRONLY or OsConstants.O_CREAT or OsConstants.O_EXCL or OsConstants.O_NOFOLLOW
            val fd = Os.open(target.absolutePath, flags, 384) // 0600
            if (fd == null || !fd.valid()) return@runCatching false
            FileOutputStream(fd).use { out ->
                out.write(bytes)
                out.fd.sync()
            }
            Os.chmod(target.absolutePath, 384)
            target.isFile && target.length() == bytes.size.toLong()
        }.getOrDefault(false)
        if (!posixOk) {
            if (isSymlinkFailClosed(target)) throw SecurityException("Refusing symlink target")
            target.writeBytes(bytes)
            runCatching { target.setReadable(false, false); target.setReadable(true, true) }
            runCatching { target.setWritable(false, false); target.setWritable(true, true) }
            runCatching { Os.chmod(target.absolutePath, 384) }
        }
    }

    private fun writeAtomic0600(target: File, bytes: ByteArray): Boolean {
        val parent = target.parentFile ?: return false
        if (isSymlinkFailClosed(parent) || isSymlinkFailClosed(target)) return false
        parent.mkdirs()
        runCatching { Os.chmod(parent.absolutePath, 448) }

        val tmp = File(parent, ".${target.name}.tmp.${System.nanoTime()}")
        var committed = false
        try {
            val posixOk = runCatching {
                val flags = OsConstants.O_WRONLY or OsConstants.O_CREAT or OsConstants.O_EXCL or OsConstants.O_NOFOLLOW
                val fd = Os.open(tmp.absolutePath, flags, 384) // 0600
                if (fd == null || !fd.valid()) return@runCatching false
                FileOutputStream(fd).use { out ->
                    out.write(bytes)
                    out.fd.sync()
                }
                if (tmp.length() != bytes.size.toLong()) return@runCatching false
                Os.chmod(tmp.absolutePath, 384)
                Os.rename(tmp.absolutePath, target.absolutePath)
                !tmp.exists() && target.isFile && target.length() == bytes.size.toLong()
            }.getOrDefault(false)

            if (posixOk) {
                committed = true
                return true
            }

            if (isSymlinkFailClosed(tmp) || isSymlinkFailClosed(target)) return false
            tmp.writeBytes(bytes)
            runCatching { tmp.setReadable(false, false); tmp.setReadable(true, true) }
            runCatching { tmp.setWritable(false, false); tmp.setWritable(true, true) }
            runCatching { Os.chmod(tmp.absolutePath, 384) }
            if (!tmp.renameTo(target)) {
                if (isSymlinkFailClosed(target)) return false
                target.writeBytes(bytes)
                tmp.delete()
            }
            runCatching { Os.chmod(target.absolutePath, 384) }
            committed = target.isFile && target.length() == bytes.size.toLong()
            return committed
        } finally {
            if (!committed) runCatching { tmp.delete() }
        }
    }

    private fun secureDeleteFile(file: File) {
        if (!file.exists() && !isSymlinkFailClosed(file)) return
        if (!isSymlinkFailClosed(file) && file.isFile) {
            runCatching {
                val len = file.length().coerceIn(0L, 65536L).toInt()
                if (len > 0) file.writeBytes(ByteArray(len))
            }
        }
        runCatching { file.delete() }
    }

    companion object {
        private const val USER_AGENT = "RUR-ControlCenter-Android/2.1"
        private const val OPERATOR_SESSION_TTL_MS = 24L * 60L * 60L * 1000L // 24 hours (matches PAGE_RUR shared/auth.js)
        private const val MAX_HTTP_BODY_BYTES = 262_144 // 256 KB cap
        private const val MAX_REDIRECTS = 3
        private const val MAX_EDITABLE_CMD_LEN = 512

        // Official PAGE_RUR production server IP (Caddy HTTPS on rur.unipolidgo.edu.mx)
        // Used ONLY as a last-resort fallback if the local ISP DNS and DoH are unreachable;
        // TLS certificate & SNI hostname verification for `rur.unipolidgo.edu.mx` remain strictly enforced.
        private val KNOWN_RUR_HOST_FALLBACK_IPS = mapOf(
            "rur.unipolidgo.edu.mx" to "187.245.209.200",
        )
        private val dohResolvedIpCache = ConcurrentHashMap<String, String>()

        private val LIVEKIT_RECEIVER_TITLES = setOf(
            "ros:livekit_rx_pc",
            "ros:livekit_rx_agent",
            "ros:livekit_rx_motion",
        )

        /**
         * Resolves a hostname via DNS-over-HTTPS (Cloudflare `1.1.1.1` then Google `8.8.8.8` by literal IP
         * so zero DNS lookup is required to reach the DoH server), falling back to [KNOWN_RUR_HOST_FALLBACK_IPS]
         * when the user's ISP DNS fails to contact `srvdnsnam01.finanzasdurango.gob.mx`.
         */
        internal fun resolveHostViaDoHOrFallback(hostname: String): String? {
            val cleanHost = hostname.trim().lowercase(Locale.US)
            dohResolvedIpCache[cleanHost]?.let { return it }

            val encoded = URLEncoder.encode(cleanHost, StandardCharsets.UTF_8.name())
            val dohUrls = listOf(
                "https://1.1.1.1/dns-query?name=$encoded&type=A",
                "https://8.8.8.8/resolve?name=$encoded&type=A",
            )
            for (dohUrl in dohUrls) {
                val ip = runCatching {
                    val conn = (URL(dohUrl).openConnection() as HttpURLConnection).apply {
                        requestMethod = "GET"
                        connectTimeout = 3_500
                        readTimeout = 3_500
                        setRequestProperty("Accept", "application/dns-json")
                        setRequestProperty("User-Agent", USER_AGENT)
                    }
                    try {
                        if (conn.responseCode in 200..299) {
                            val text = readBoundedUtf8(conn.inputStream, 32_768)
                            parseFirstIpv4FromDnsJson(text)
                        } else {
                            null
                        }
                    } finally {
                        conn.disconnect()
                    }
                }.getOrNull()
                if (!ip.isNullOrBlank()) {
                    dohResolvedIpCache[cleanHost] = ip
                    return ip
                }
            }

            return KNOWN_RUR_HOST_FALLBACK_IPS[cleanHost]?.also {
                dohResolvedIpCache[cleanHost] = it
            }
        }

        internal fun parseFirstIpv4FromDnsJson(jsonText: String): String? {
            val obj = runCatching { JSONObject(jsonText) }.getOrNull() ?: return null
            val answers = obj.optJSONArray("Answer") ?: return null
            for (i in 0 until answers.length()) {
                val ans = answers.optJSONObject(i) ?: continue
                if (ans.optInt("type", 0) == 1) {
                    val data = ans.optString("data", "").trim()
                    if (data.matches(Regex("^\\d{1,3}(\\.\\d{1,3}){3}$"))) {
                        return data
                    }
                }
            }
            return null
        }

        /**
         * Opens an [HttpsURLConnection] to [resolvedIp] while preserving:
         * 1) TLS SNI (`ServerNameIndication`) = [sniHostname] (required by Caddy to select the certificate)
         * 2) Strict X.509 certificate hostname verification against [sniHostname]
         * 3) HTTP `Host` header = [sniHostname]
         */
        private fun openHttpsConnectionViaDirectIpWithSni(
            originalUrlStr: String,
            resolvedIp: String,
            sniHostname: String,
        ): HttpsURLConnection {
            val uri = URI(originalUrlStr)
            val portPart = if (uri.port > 0) ":${uri.port}" else ""
            val pathPart = uri.rawPath?.ifEmpty { "/" } ?: "/"
            val queryPart = uri.rawQuery?.let { "?$it" }.orEmpty()
            val ipUrl = URL("https://$resolvedIp$portPart$pathPart$queryPart")
            val defaultFactory = HttpsURLConnection.getDefaultSSLSocketFactory()
            val defaultVerifier = HttpsURLConnection.getDefaultHostnameVerifier()

            val conn = ipUrl.openConnection() as HttpsURLConnection
            conn.setRequestProperty("Host", if (uri.port > 0) "$sniHostname:${uri.port}" else sniHostname)
            conn.sslSocketFactory = SniOverrideSslSocketFactory(defaultFactory, sniHostname)
            conn.hostnameVerifier = javax.net.ssl.HostnameVerifier { _, session ->
                defaultVerifier.verify(sniHostname, session)
            }
            return conn
        }

        private class SniOverrideSslSocketFactory(
            private val delegate: SSLSocketFactory,
            private val sniHostname: String,
        ) : SSLSocketFactory() {
            override fun getDefaultCipherSuites(): Array<String> = delegate.defaultCipherSuites
            override fun getSupportedCipherSuites(): Array<String> = delegate.supportedCipherSuites

            private fun configureSni(socket: Socket): Socket {
                if (socket is SSLSocket) {
                    val params: SSLParameters = socket.sslParameters
                    params.serverNames = listOf(SNIHostName(sniHostname))
                    socket.sslParameters = params
                }
                return socket
            }

            override fun createSocket(s: Socket?, host: String?, port: Int, autoClose: Boolean): Socket =
                configureSni(delegate.createSocket(s, sniHostname, port, autoClose))

            override fun createSocket(host: String?, port: Int): Socket {
                val raw = Socket()
                raw.connect(InetSocketAddress(host, port), 10_000)
                return configureSni(delegate.createSocket(raw, sniHostname, port, true))
            }

            override fun createSocket(host: String?, port: Int, localHost: InetAddress?, localPort: Int): Socket {
                val raw = Socket(localHost, localPort)
                raw.connect(InetSocketAddress(host, port), 10_000)
                return configureSni(delegate.createSocket(raw, sniHostname, port, true))
            }

            override fun createSocket(host: InetAddress?, port: Int): Socket {
                val raw = Socket()
                raw.connect(InetSocketAddress(host, port), 10_000)
                return configureSni(delegate.createSocket(raw, sniHostname, port, true))
            }

            override fun createSocket(address: InetAddress?, port: Int, localAddress: InetAddress?, localPort: Int): Socket {
                val raw = Socket(localAddress, localPort)
                raw.connect(InetSocketAddress(address, port), 10_000)
                return configureSni(delegate.createSocket(raw, sniHostname, port, true))
            }
        }

        fun isRos2SessionTitle(title: String?): Boolean {
            val t = title?.trim() ?: return false
            return t.startsWith("ros:") || t.startsWith("ros-ai:") || t.startsWith("ros-ssh:")
        }

        /**
         * Returns true ONLY for ROS 2 node/receiver/shell sessions (`ros:*`),
         * explicitly excluding AI sessions (`ros-ai:*`) so Teardown & Logout preserve AI agents.
         */
        fun isRos2NodeSessionTitle(title: String?): Boolean {
            val t = title?.trim() ?: return false
            return t.startsWith("ros:")
        }

        fun isLiveKitReceiverSessionTitle(title: String?): Boolean {
            val t = title?.trim() ?: return false
            return t in LIVEKIT_RECEIVER_TITLES
        }

        internal fun validateWebApiUrl(raw: String): Result<String> {
            val trimmed = raw.trim().trimEnd('/')
            if (trimmed.isEmpty()) {
                return Result.failure(IllegalArgumentException("URL de PAGE_RUR requerida."))
            }
            val uri = runCatching { URI(trimmed) }.getOrNull()
                ?: return Result.failure(IllegalArgumentException("URL de PAGE_RUR mal formada."))
            val scheme = uri.scheme?.lowercase(Locale.US).orEmpty()
            val host = uri.host?.lowercase(Locale.US).orEmpty()
            if (host.isEmpty()) {
                return Result.failure(IllegalArgumentException("Falta el dominio en la URL de PAGE_RUR."))
            }
            val isLoopback = host == "localhost" || host == "127.0.0.1"
            if (scheme != "https" && !(scheme == "http" && isLoopback)) {
                return Result.failure(IllegalArgumentException("Solo se permite HTTPS para PAGE_RUR (o http://localhost para pruebas)."))
            }
            if (uri.userInfo != null) {
                return Result.failure(IllegalArgumentException("La URL no debe contener credenciales embebidas."))
            }
            return Result.success(trimmed)
        }

        internal fun sanitizeRelativeOrHomePath(raw: String, fallback: String): String? {
            val trimmed = raw.trim().ifEmpty { fallback }
            if (trimmed.length > 240 || trimmed.any { it == '\u0000' || it == '\r' || it == '\n' }) return null
            if (trimmed.split('/').any { it == ".." }) return null
            return trimmed
        }

        internal fun parseOperatorSessionFromUserJson(
            userJson: JSONObject,
            fallbackMatricula: String,
            hasSessionAuth: Boolean,
            nowMs: Long = System.currentTimeMillis(),
        ): RurOperatorSession {
            val roles = parseRolesFlexible(userJson)
            val permisos = parseStringArrayFlexible(userJson.optJSONArray("permisos"))
            val matricula = userJson.optString("matricula", "").trim().ifBlank { fallbackMatricula }
            val nombre = userJson.optString("nombre", "").trim()
                .ifBlank { userJson.optString("name", "").trim() }
            val alias = userJson.optString("alias", "").trim()
            val grupo = userJson.optString("grupo", "").trim()
            val id = userJson.opt("id")?.toString()?.trim().orEmpty().ifBlank { matricula }
            return RurOperatorSession(
                id = id,
                matricula = matricula,
                nombre = nombre,
                alias = alias,
                grupo = grupo,
                roles = roles,
                totpEnabled = userJson.optBoolean("totp_enabled", false),
                mustChangePassword = userJson.optBoolean("debe_cambiar_password", false),
                loginAtIso = formatIsoUtc(nowMs),
                expiresAtEpochMs = nowMs + OPERATOR_SESSION_TTL_MS,
                hasSessionAuth = hasSessionAuth,
                permisos = permisos,
            )
        }

        private fun parseStringArrayFlexible(arr: JSONArray?): List<String> {
            if (arr == null) return emptyList()
            val out = mutableListOf<String>()
            for (i in 0 until arr.length()) {
                arr.optString(i, "").trim().takeIf { it.isNotEmpty() }?.let { out.add(it) }
            }
            return out
        }

        internal fun parseRolesFlexible(userJson: JSONObject): List<String> {
            val out = mutableListOf<String>()
            val rolesArr = userJson.optJSONArray("roles")
            if (rolesArr != null) {
                for (i in 0 until rolesArr.length()) {
                    rolesArr.optString(i, "").trim().takeIf { it.isNotEmpty() }?.let { out.add(it) }
                }
            }
            if (out.isEmpty()) {
                val rawRole = userJson.optString("roles", "").ifBlank { userJson.optString("rol", "") }.trim()
                if (rawRole.startsWith("[")) {
                    runCatching {
                        val arr = JSONArray(rawRole)
                        for (i in 0 until arr.length()) {
                            arr.optString(i, "").trim().takeIf { it.isNotEmpty() }?.let { out.add(it) }
                        }
                    }
                } else if (rawRole.isNotEmpty()) {
                    rawRole.split(',').map { it.trim() }.filter { it.isNotEmpty() }.forEach { out.add(it) }
                }
            }
            return out.ifEmpty { listOf("miembro") }
        }

        internal fun parseRobotsPayload(body: String, fallbackDomainId: Int): List<RurRobotInfo> {
            val trimmed = body.trim()
            val arr: JSONArray? = when {
                trimmed.startsWith("[") -> runCatching { JSONArray(trimmed) }.getOrNull()
                trimmed.startsWith("{") -> runCatching { JSONObject(trimmed).optJSONArray("robots") }.getOrNull()
                else -> null
            }
            if (arr == null) return emptyList()
            val list = mutableListOf<RurRobotInfo>()
            for (i in 0 until arr.length()) {
                val r = arr.optJSONObject(i) ?: continue
                parseSingleRobotJson(r, fallbackDomainId)?.let { list.add(it) }
            }
            return list
        }

        private fun parseSingleRobotJson(r: JSONObject, fallbackDomainId: Int): RurRobotInfo? {
            val id = r.optString("id", "").trim()
            if (id.isEmpty()) return null
            val name = r.optString("name", id).trim().ifBlank { id }
            val domain = when {
                r.has("rosDomainId") -> r.optInt("rosDomainId", fallbackDomainId)
                r.has("ros_domain_id") -> r.optInt("ros_domain_id", fallbackDomainId)
                else -> fallbackDomainId
            }.coerceIn(0, 232)
            val lkUrl = r.optString("livekitUrl", "").ifBlank { r.optString("livekit_url", "") }.trim()
            return RurRobotInfo(
                id = id,
                name = name,
                status = r.optString("status", "offline").trim().ifBlank { "offline" },
                room = r.optString("room", "room-1").trim().ifBlank { "room-1" },
                rosDomainId = domain,
                livekitUrl = lkUrl,
                locomotionType = r.optString("locomotionType", "tank").ifBlank { "tank" },
                version = r.optInt("version", 1),
                hasCredentials = r.optBoolean("hasCredentials", false),
            )
        }

        internal fun extractCookieValue(cookieHeader: String, cookieName: String): String {
            if (cookieHeader.isBlank()) return ""
            val prefix = "$cookieName="
            return cookieHeader.split(';')
                .map { it.trim() }
                .firstOrNull { it.startsWith(prefix) }
                ?.removePrefix(prefix)
                ?.trim()
                .orEmpty()
        }

        internal fun posixSingleQuote(value: String): String =
            "'" + value.replace("'", "'\"'\"'") + "'"

        internal fun maskSecretPreview(secret: String): String = when {
            secret.isBlank() -> ""
            secret.length <= 8 -> "••••••••"
            else -> "${secret.take(6)}…${secret.takeLast(4)}"
        }

        private fun effectivePort(uri: URI): Int = when {
            uri.port > 0 -> uri.port
            uri.scheme.equals("https", ignoreCase = true) -> 443
            else -> 80
        }

        private fun readBoundedUtf8(stream: java.io.InputStream, maxBytes: Int): String {
            stream.use { input ->
                val out = ByteArrayOutputStream()
                val buf = ByteArray(4096)
                var total = 0
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    val remaining = maxBytes - total
                    if (remaining <= 0) break
                    val toWrite = n.coerceAtMost(remaining)
                    out.write(buf, 0, toWrite)
                    total += toWrite
                }
                return out.toString(StandardCharsets.UTF_8.name())
            }
        }

        private fun isSymlinkFailClosed(file: File): Boolean = try {
            val st = Os.lstat(file.absolutePath)
            if (st != null && st.st_mode != 0) {
                OsConstants.S_ISLNK(st.st_mode)
            } else {
                Files.isSymbolicLink(file.toPath())
            }
        } catch (e: android.system.ErrnoException) {
            if (e.errno == OsConstants.ENOENT) false else true
        } catch (_: Throwable) {
            try {
                Files.isSymbolicLink(file.toPath())
            } catch (_: Throwable) {
                true
            }
        }

        private fun nowIsoUtc(): String = formatIsoUtc(System.currentTimeMillis())

        private fun formatIsoUtc(epochMs: Long): String {
            val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
            sdf.timeZone = TimeZone.getTimeZone("UTC")
            return sdf.format(Date(epochMs))
        }

        // Positional-arg safe installer wrapper: runs `workstation install-ros2` and immediately enters Ubuntu ROS 2
        // (NEVER drops the user into a plain Termux shell inside the ROS 2 sector!)
        // Outer argv: $1=workstationBin $2=loginBin $3=bashBin $4=shBin
        private val INSTALL_ROS2_WRAPPER_SCRIPT = """
            WS_BIN="${'$'}1"
            if [ -x "${'$'}WS_BIN" ]; then
                "${'$'}WS_BIN" install-ros2
                RC=${'$'}?
                if [ "${'$'}RC" = "0" ]; then
                    printf "\n\033[1;32m==> Ubuntu 24.04 + ROS 2 Jazzy instalado correctamente. Entrando a Ubuntu ROS 2...\033[0m\n"
                    exec "${'$'}WS_BIN" ros2 shell
                fi
                printf "\n\033[1;31m==> La instalación finalizó con código %s. Toca 'Reintentar Instalación' arriba.\033[0m\n" "${'$'}RC"
            else
                echo "[RUR] workstation CLI no encontrado en ${'$'}WS_BIN" >&2
            fi
            while sleep 3600; do :; done
        """.trimIndent()

        // Positional-arg safe proot-distro Ubuntu ROS 2 wrapper (ZERO nested quote interpolation, ZERO fallback to Termux shell!)
        // Outer argv: $1=prootBin $2=homeDir $3=prefixDir $4=workDir $5=envFile $6=isInteractive $7=banner $8=rosCmd
        private val PROOT_ROS2_WRAPPER_SCRIPT = """
            PROOT_BIN="${'$'}1"
            HOME_DIR="${'$'}2"
            PREFIX_DIR="${'$'}3"
            WORK_DIR="${'$'}4"
            ENV_FILE="${'$'}5"
            IS_INTERACTIVE="${'$'}6"
            BANNER="${'$'}7"
            ROS_CMD="${'$'}8"
            if [ ! -x "${'$'}PROOT_BIN" ]; then
                rm -f "${'$'}ENV_FILE" 2>/dev/null || true
                echo "[RUR] Ubuntu 24.04 + ROS 2 Jazzy requiere instalación previa."
                exit 1
            fi
            exec "${'$'}PROOT_BIN" login ubuntu \
                --bind "${'$'}HOME_DIR:${'$'}HOME_DIR" \
                --bind "${'$'}PREFIX_DIR:${'$'}PREFIX_DIR" \
                -- /bin/bash -lc '
                    HOME_DIR="${'$'}1"; PREFIX_DIR="${'$'}2"; WORK_DIR="${'$'}3"
                    ENV_FILE="${'$'}4"; IS_INTERACTIVE="${'$'}5"; BANNER="${'$'}6"; ROS_CMD="${'$'}7"
                    export HOME="${'$'}HOME_DIR"
                    export TERM="${'$'}{TERM:-xterm-256color}"
                    export BROWSER="${'$'}PREFIX_DIR/bin/termux-open-url"
                    if [ -r "${'$'}ENV_FILE" ]; then
                        . "${'$'}ENV_FILE"
                        rm -f "${'$'}ENV_FILE" 2>/dev/null || true
                    fi
                    if [ -r "${'$'}HOME_DIR/.config/melcat/vault/providers.env" ]; then
                        . "${'$'}HOME_DIR/.config/melcat/vault/providers.env"
                    fi
                    if [ -r /opt/ros/jazzy/setup.bash ]; then
                        set +u 2>/dev/null || true
                        . /opt/ros/jazzy/setup.bash
                    fi
                    if [ -d "${'$'}WORK_DIR" ]; then
                        cd "${'$'}WORK_DIR"
                    else
                        cd "${'$'}HOME_DIR"
                    fi
                    if [ -r "${'$'}WORK_DIR/install/setup.bash" ]; then
                        . "${'$'}WORK_DIR/install/setup.bash"
                    fi
                    printf "\033[1;33m==> %s\033[0m\n" "${'$'}BANNER"
                    TK_STATE="local-.env"
                    if [ -n "${'$'}{LIVEKIT_TOKEN:-}" ]; then TK_STATE="RAM-OK"; fi
                    printf "\033[0;90m    ROS_DOMAIN_ID=%s | RMW=%s | LIVEKIT_ROOM=%s | TOKEN=%s\033[0m\n" \
                        "${'$'}{ROS_DOMAIN_ID:-42}" "${'$'}{RMW_IMPLEMENTATION:-rmw_cyclonedds_cpp}" \
                        "${'$'}{LIVEKIT_ROOM:-local}" "${'$'}TK_STATE"
                    if [ "${'$'}IS_INTERACTIVE" = "1" ] || [ -z "${'$'}ROS_CMD" ]; then
                        exec /bin/bash -i
                    fi
                    eval "${'$'}ROS_CMD"
                    STATUS=${'$'}?
                    printf "\n\033[0;90m[Proceso terminado con código %s — shell Ubuntu ROS 2 lista]\033[0m\n" "${'$'}STATUS"
                    exec /bin/bash -i
                ' _ "${'$'}HOME_DIR" "${'$'}PREFIX_DIR" "${'$'}WORK_DIR" "${'$'}ENV_FILE" "${'$'}IS_INTERACTIVE" "${'$'}BANNER" "${'$'}ROS_CMD"
        """.trimIndent()

        // Positional-arg safe Ubuntu ROS 2 AI agent wrapper (`agy` / `opencode` sharing Termux $HOME + 0600 vaults)
        // Outer argv: $1=prootBin $2=homeDir $3=prefixDir $4=repoDir $5=envFile $6=agentBin
        private val PROOT_AI_WRAPPER_SCRIPT = """
            PROOT_BIN="${'$'}1"
            HOME_DIR="${'$'}2"
            PREFIX_DIR="${'$'}3"
            REPO_DIR="${'$'}4"
            ENV_FILE="${'$'}5"
            AGENT_BIN="${'$'}6"
            if [ ! -x "${'$'}PROOT_BIN" ]; then
                rm -f "${'$'}ENV_FILE" 2>/dev/null || true
                echo "[RUR] Ubuntu 24.04 + ROS 2 Jazzy requiere instalación previa."
                exit 1
            fi
            exec "${'$'}PROOT_BIN" login ubuntu \
                --bind "${'$'}HOME_DIR:${'$'}HOME_DIR" \
                --bind "${'$'}PREFIX_DIR:${'$'}PREFIX_DIR" \
                -- /bin/bash -lc '
                    HOME_DIR="${'$'}1"; PREFIX_DIR="${'$'}2"; REPO_DIR="${'$'}3"
                    ENV_FILE="${'$'}4"; AGENT_BIN="${'$'}5"
                    export HOME="${'$'}HOME_DIR"
                    export TERM="${'$'}{TERM:-xterm-256color}"
                    export BROWSER="${'$'}PREFIX_DIR/bin/termux-open-url"
                    if [ -r "${'$'}ENV_FILE" ]; then
                        . "${'$'}ENV_FILE"
                        rm -f "${'$'}ENV_FILE" 2>/dev/null || true
                    fi
                    if [ -r "${'$'}HOME_DIR/.config/melcat/vault/providers.env" ]; then
                        . "${'$'}HOME_DIR/.config/melcat/vault/providers.env"
                    fi
                    if [ -r /opt/ros/jazzy/setup.bash ]; then
                        set +u 2>/dev/null || true
                        . /opt/ros/jazzy/setup.bash
                    fi
                    if [ -r "${'$'}REPO_DIR/offboard_ws/install/setup.bash" ]; then
                        . "${'$'}REPO_DIR/offboard_ws/install/setup.bash"
                    fi
                    if [ -d "${'$'}REPO_DIR/offboard_ws" ]; then
                        cd "${'$'}REPO_DIR/offboard_ws"
                    elif [ -d "${'$'}REPO_DIR" ]; then
                        cd "${'$'}REPO_DIR"
                    else
                        cd "${'$'}HOME_DIR"
                    fi
                    printf "\033[1;33m==> RUR AI (%s) en Ubuntu 24.04 + ROS 2 Jazzy (DOMAIN=%s)\033[0m\n" "${'$'}AGENT_BIN" "${'$'}{ROS_DOMAIN_ID:-42}"
                    if [ -x "/usr/local/bin/${'$'}AGENT_BIN" ]; then
                        exec "/usr/local/bin/${'$'}AGENT_BIN"
                    elif [ -x "${'$'}PREFIX_DIR/bin/${'$'}AGENT_BIN" ]; then
                        exec "${'$'}PREFIX_DIR/bin/${'$'}AGENT_BIN"
                    else
                        echo "[RUR] ${'$'}AGENT_BIN no está instalado aún. Ejecuta workstation setup / install-opencode en Dev & IA."
                        exec /bin/bash -i
                    fi
                ' _ "${'$'}HOME_DIR" "${'$'}PREFIX_DIR" "${'$'}REPO_DIR" "${'$'}ENV_FILE" "${'$'}AGENT_BIN"
        """.trimIndent()
    }
}
