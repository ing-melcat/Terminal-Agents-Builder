package com.termux.custom.ssh

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.system.Os
import android.system.OsConstants
import com.termux.shared.termux.TermuxConstants
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

enum class SshTransport(val label: String, val shortBadge: String) {
    DIRECT("SSH Directo (LAN / IP)", "DIRECTO"),
    TAILSCALE("Tailscale VPN", "TAILSCALE"),
}

enum class SshDeviceSource {
    SAVED,
    SSH_CONFIG,
}

enum class SshReachability {
    CHECKING,
    ONLINE,
    NEEDS_TAILSCALE,
    OFFLINE,
}

data class SshDeviceRecord(
    val id: String,
    val name: String,
    val host: String,
    val port: Int = 22,
    val username: String = "",
    val transport: SshTransport = SshTransport.DIRECT,
    val identityFile: String = "",
    val tags: String = "",
    val localPortForwards: String = "",
    val startupCommand: String = "",
    val source: SshDeviceSource = SshDeviceSource.SAVED,
    val lastConnectedAt: Long = 0L,
) {
    val targetDisplay: String
        get() {
            val prefix = if (username.isNotBlank()) "$username@" else ""
            val hostPart = if (host.contains(':') && port != 22) "[$host]" else host
            val suffix = if (port != 22) ":$port" else ""
            return "$prefix$hostPart$suffix"
        }

    val safeTabTitle: String
        get() {
            val cleaned = name.lowercase()
                .replace(Regex("[^a-z0-9._-]+"), "-")
                .trim('-')
                .take(16)
                .ifEmpty { host.replace(':', '-').take(16) }
            return "ssh:$cleaned"
        }
}

data class SshDeviceProbe(
    val reachability: SshReachability,
    val latencyMs: Long? = null,
    val checkedAtMs: Long = System.currentTimeMillis(),
)

data class SshPanelState(
    val opensshInstalled: Boolean = false,
    val tailscaleLocalIp: String? = null,
    val defaultPublicKey: String? = null,
    val defaultPublicKeyName: String? = null,
    val devices: List<SshDeviceRecord> = emptyList(),
    val probes: Map<String, SshDeviceProbe> = emptyMap(),
    val isScanning: Boolean = false,
) {
    val isTailscaleActive: Boolean get() = !tailscaleLocalIp.isNullOrBlank()

    val onlineCount: Int
        get() = devices.count { probes[it.id]?.reachability == SshReachability.ONLINE }

    val sortedDevices: List<SshDeviceRecord>
        get() = devices.sortedWith(
            compareBy<SshDeviceRecord> { device ->
                when (probes[device.id]?.reachability) {
                    SshReachability.ONLINE -> 0
                    SshReachability.CHECKING -> 1
                    SshReachability.NEEDS_TAILSCALE -> 2
                    SshReachability.OFFLINE -> 3
                    null -> 4
                }
            }.thenBy { device ->
                probes[device.id]?.latencyMs ?: Long.MAX_VALUE
            }.thenByDescending { it.lastConnectedAt }
                .thenBy { it.name.lowercase() }
        )
}

/**
 * Manages known SSH devices (Direct LAN/IP and Tailscale VPN), auto-discovers hosts from ~/.ssh/config,
 * detects active Tailscale Android VPN (TRANSPORT_VPN + tunnel-filtered 100.64.0.0/10 & fd7a:115c:a1e0::/48),
 * probes live TCP reachability in parallel with strict cancellation, generates ED25519 keys safely, and
 * constructs injection-proof OS execve argv[] arrays for native Termux OpenSSH sessions.
 */
class SshDevicesManager(
    context: Context? = null,
    private val prefixDir: File = File(TermuxConstants.TERMUX_PREFIX_DIR_PATH),
    private val homeDir: File = File(TermuxConstants.TERMUX_HOME_DIR_PATH),
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val tcpProber: suspend (host: String, port: Int, timeoutMs: Int) -> Long? = ::defaultTcpProbe,
    private val tailscaleIpDetector: (() -> String?)? = null,
) {
    private val appContext: Context? = context?.applicationContext
    private val writeMutex = Mutex()
    private val melcatConfigDir: File get() = File(homeDir, ".config/melcat")
    private val devicesFile: File get() = File(melcatConfigDir, "ssh-devices.json")
    private val sshDir: File get() = File(homeDir, ".ssh")
    private val sshConfigFile: File get() = File(sshDir, "config")

    private fun resolveTailscaleIp(): String? = runCatching {
        tailscaleIpDetector?.invoke() ?: detectLocalTailscaleIp(appContext)
    }.getOrNull()

    suspend fun inspectEnvironmentAndDevices(
        existingProbes: Map<String, SshDeviceProbe> = emptyMap(),
    ): SshPanelState = withContext(ioDispatcher) {
        val sshBin = File(prefixDir, "bin/ssh")
        val saved = readSavedDevicesTolerant()
        val seenIds = saved.mapTo(HashSet()) { it.id }
        val fromConfig = parseSshConfigHosts(saved).filter { seenIds.add(it.id) }
        val merged = saved + fromConfig
        val (pubKeyName, pubKeyContent) = readDefaultPublicKey()
        val validIds = merged.mapTo(HashSet()) { it.id }

        SshPanelState(
            opensshInstalled = sshBin.canExecute(),
            tailscaleLocalIp = resolveTailscaleIp(),
            defaultPublicKey = pubKeyContent,
            defaultPublicKeyName = pubKeyName,
            devices = merged,
            probes = existingProbes.filterKeys { it in validIds },
            isScanning = false,
        )
    }

    suspend fun probeAllDevices(
        currentState: SshPanelState,
        timeoutMs: Int = 1500,
    ): SshPanelState = withContext(ioDispatcher) {
        val refreshed = inspectEnvironmentAndDevices(currentState.probes)
        if (refreshed.devices.isEmpty()) return@withContext refreshed

        val semaphore = Semaphore(MAX_CONCURRENT_PROBES)
        val tailscaleActive = refreshed.isTailscaleActive

        val results: Map<String, SshDeviceProbe> = coroutineScope {
            refreshed.devices.map { device ->
                async(ioDispatcher) {
                    semaphore.withPermit {
                        if (device.transport == SshTransport.TAILSCALE && !tailscaleActive) {
                            return@withPermit device.id to SshDeviceProbe(
                                reachability = SshReachability.NEEDS_TAILSCALE,
                                latencyMs = null,
                                checkedAtMs = System.currentTimeMillis(),
                            )
                        }
                        val latency = try {
                            tcpProber(device.host, device.port, timeoutMs)
                        } catch (ce: CancellationException) {
                            throw ce
                        } catch (_: Throwable) {
                            null
                        }
                        val reachability = if (latency != null) {
                            SshReachability.ONLINE
                        } else {
                            SshReachability.OFFLINE
                        }
                        device.id to SshDeviceProbe(
                            reachability = reachability,
                            latencyMs = latency,
                            checkedAtMs = System.currentTimeMillis(),
                        )
                    }
                }
            }.awaitAll().toMap()
        }

        refreshed.copy(probes = results, isScanning = false)
    }

    suspend fun upsertDevice(
        id: String?,
        name: String,
        host: String,
        port: Int,
        username: String,
        transport: SshTransport,
        identityFile: String,
        tags: String,
        localPortForwards: String = "",
        startupCommand: String = "",
        existingProbes: Map<String, SshDeviceProbe> = emptyMap(),
    ): Result<SshPanelState> = withContext(ioDispatcher) {
        writeMutex.withLock {
            runCatching {
                val validated = validateRecord(
                    id = id?.takeIf { it.isNotBlank() && !it.startsWith(SSH_CONFIG_ID_PREFIX) }
                        ?: UUID.randomUUID().toString(),
                    name = name,
                    host = host,
                    port = port,
                    username = username,
                    transport = transport,
                    identityFile = identityFile,
                    tags = tags,
                    localPortForwards = localPortForwards,
                    startupCommand = startupCommand,
                )
                val current = readSavedDevicesForMutation().toMutableList()
                val idx = current.indexOfFirst { it.id == validated.id }
                if (idx >= 0) {
                    current[idx] = validated.copy(lastConnectedAt = current[idx].lastConnectedAt)
                } else {
                    current.add(validated)
                }
                writeSavedDevicesAtomic(current)
                inspectEnvironmentAndDevices(existingProbes)
            }
        }
    }

    suspend fun deleteDevice(
        deviceId: String,
        existingProbes: Map<String, SshDeviceProbe> = emptyMap(),
    ): Result<SshPanelState> = withContext(ioDispatcher) {
        writeMutex.withLock {
            runCatching {
                val current = readSavedDevicesForMutation().filterNot { it.id == deviceId }
                writeSavedDevicesAtomic(current)
                inspectEnvironmentAndDevices(existingProbes - deviceId)
            }
        }
    }

    suspend fun markDeviceConnected(
        deviceId: String,
        existingProbes: Map<String, SshDeviceProbe> = emptyMap(),
    ): SshPanelState = withContext(ioDispatcher) {
        writeMutex.withLock {
            runCatching {
                val current = readSavedDevicesForMutation().toMutableList()
                val idx = current.indexOfFirst { it.id == deviceId }
                if (idx >= 0) {
                    current[idx] = current[idx].copy(lastConnectedAt = System.currentTimeMillis())
                    writeSavedDevicesAtomic(current)
                }
            }
            inspectEnvironmentAndDevices(existingProbes)
        }
    }

    /**
     * Generates ~/.ssh/id_ed25519 natively via ProcessBuilder (never writing into active PTYs,
     * which could be a remote SSH session or TUI agent). Refuses to overwrite an existing key.
     */
    suspend fun generateDefaultEd25519Key(
        existingProbes: Map<String, SshDeviceProbe> = emptyMap(),
    ): Result<SshPanelState> = withContext(ioDispatcher) {
        writeMutex.withLock {
            runCatching {
                val keygenBin = File(prefixDir, "bin/ssh-keygen")
                check(keygenBin.canExecute()) { "OpenSSH no está instalado. Ejecuta 'workstation install-ssh'." }

                if (!sshDir.exists()) sshDir.mkdirs()
                runCatching { Os.chmod(sshDir.absolutePath, 448) } // 0700

                val keyFile = File(sshDir, "id_ed25519")
                val pubFile = File(sshDir, "id_ed25519.pub")
                if (!keyFile.exists() && !pubFile.exists()) {
                    val pb = ProcessBuilder(
                        keygenBin.absolutePath,
                        "-t", "ed25519",
                        "-N", "",
                        "-f", keyFile.absolutePath,
                        "-C", "rur-workstation",
                    ).redirectErrorStream(true)
                    pb.environment()["HOME"] = homeDir.absolutePath
                    pb.environment()["PATH"] = "${prefixDir.absolutePath}/bin:/system/bin"
                    pb.environment()["LD_LIBRARY_PATH"] = "${prefixDir.absolutePath}/lib"
                    val proc = pb.start()
                    val output = proc.inputStream.bufferedReader().use { it.readText() }
                    val finished = proc.waitFor(15, TimeUnit.SECONDS)
                    if (!finished) {
                        proc.destroyForcibly()
                        error("ssh-keygen excedió el tiempo de espera")
                    }
                    if (proc.exitValue() != 0 || !pubFile.isFile) {
                        error("ssh-keygen falló (exit ${proc.exitValue()}): ${output.take(120)}")
                    }
                    enforceMode0600(keyFile)
                }
                inspectEnvironmentAndDevices(existingProbes)
            }
        }
    }

    /**
     * Builds strict OS execve arguments for Termux OpenSSH client (`$PREFIX/bin/ssh`).
     * Re-validates every field before launch, binds `-L` tunnels strictly to `127.0.0.1`,
     * and always places `--` before host to prevent option injection.
     */
    fun buildSshArgv(device: SshDeviceRecord): Array<String> {
        val safe = validateRecord(
            id = device.id,
            name = device.name,
            host = device.host,
            port = device.port,
            username = device.username,
            transport = device.transport,
            identityFile = device.identityFile,
            tags = device.tags,
            localPortForwards = device.localPortForwards,
            startupCommand = device.startupCommand,
            source = device.source,
            lastConnectedAt = device.lastConnectedAt,
        )
        val args = mutableListOf(
            "-o", "ConnectTimeout=8",
            "-o", "ServerAliveInterval=30",
            "-o", "ServerAliveCountMax=3",
            "-p", safe.port.toString(),
        )
        if (safe.username.isNotBlank()) {
            args.add("-l")
            args.add(safe.username)
        }
        if (safe.identityFile.isNotBlank()) {
            args.add("-i")
            args.add(File(sshDir, safe.identityFile).absolutePath)
        }
        val tunnels = parseLocalPortForwards(safe.localPortForwards)
        for (tunnel in tunnels) {
            args.add("-L")
            args.add("127.0.0.1:${tunnel.localPort}:${tunnel.remoteHost}:${tunnel.remotePort}")
        }
        if (safe.startupCommand.isNotBlank()) {
            args.add("-t")
        }
        args.add("--")
        args.add(safe.host)
        if (safe.startupCommand.isNotBlank()) {
            args.add(safe.startupCommand)
        }
        return args.toTypedArray()
    }

    /**
     * Builds a 100% static zero-interpolation `$PREFIX/bin/sh` execve invocation that launches
     * `$PREFIX/bin/ssh` with positional argv (`"$@"`) and drops back into Termux's login shell
     * when SSH exits so connection diagnostics remain readable in the dedicated PTY tab.
     */
    fun buildPtySessionExec(device: SshDeviceRecord): Pair<String, Array<String>> {
        val safe = validateRecord(
            id = device.id,
            name = device.name,
            host = device.host,
            port = device.port,
            username = device.username,
            transport = device.transport,
            identityFile = device.identityFile,
            tags = device.tags,
            localPortForwards = device.localPortForwards,
            startupCommand = device.startupCommand,
            source = device.source,
            lastConnectedAt = device.lastConnectedAt,
        )
        val shBin = File(prefixDir, "bin/sh").absolutePath
        val sshBin = File(prefixDir, "bin/ssh").absolutePath
        val loginBin = File(prefixDir, "bin/login").absolutePath
        val bashBin = File(prefixDir, "bin/bash").absolutePath
        val sshArgv = buildSshArgv(safe)
        val label = "${safe.name} (${safe.targetDisplay})"

        val argv = ArrayList<String>(8 + sshArgv.size).apply {
            add("-c")
            add(SSH_PTY_WRAPPER_SCRIPT)
            add("rur-ssh")
            add(label)
            add(sshBin)
            add(loginBin)
            add(bashBin)
            add(shBin)
            addAll(sshArgv)
        }
        return shBin to argv.toTypedArray()
    }

    /**
     * Builds a 100% static zero-interpolation `$PREFIX/bin/sh` execve invocation to push the
     * user's public SSH key (`id_ed25519.pub` / configured key `.pub`) to the remote device
     * via `ssh-copy-id` (with pure `ssh` fallback), allowing 1-tap passwordless login afterward.
     */
    fun buildCopyIdPtyExec(device: SshDeviceRecord): Pair<String, Array<String>> {
        val safe = validateRecord(
            id = device.id,
            name = device.name,
            host = device.host,
            port = device.port,
            username = device.username,
            transport = device.transport,
            identityFile = device.identityFile,
            tags = device.tags,
            localPortForwards = device.localPortForwards,
            startupCommand = device.startupCommand,
            source = device.source,
            lastConnectedAt = device.lastConnectedAt,
        )
        val pubKeyFile = resolveSafePublicKeyFile(safe.identityFile)
            ?: error("No se encontró clave pública en ~/.ssh/. Toca 'Crear Key' primero.")

        val shBin = File(prefixDir, "bin/sh").absolutePath
        val copyIdBin = File(prefixDir, "bin/ssh-copy-id").absolutePath
        val sshBin = File(prefixDir, "bin/ssh").absolutePath
        val loginBin = File(prefixDir, "bin/login").absolutePath
        val bashBin = File(prefixDir, "bin/bash").absolutePath
        val label = "${safe.name} (${safe.targetDisplay})"

        val commonArgs = mutableListOf(
            "-o", "ConnectTimeout=8",
            "-p", safe.port.toString(),
        )
        if (safe.username.isNotBlank()) {
            commonArgs.add("-o")
            commonArgs.add("User=${safe.username}")
        }
        commonArgs.add("--")
        commonArgs.add(safe.host)

        val argv = ArrayList<String>(10 + commonArgs.size).apply {
            add("-c")
            add(COPY_ID_PTY_WRAPPER_SCRIPT)
            add("rur-copy-id")
            add(label)
            add(copyIdBin)
            add(sshBin)
            add(pubKeyFile.absolutePath)
            add(loginBin)
            add(bashBin)
            add(shBin)
            addAll(commonArgs)
        }
        return shBin to argv.toTypedArray()
    }

    private fun isSymlinkFailClosed(file: File): Boolean = try {
        val st = Os.lstat(file.absolutePath)
        if (st != null && st.st_mode != 0) {
            OsConstants.S_ISLNK(st.st_mode)
        } else {
            runCatching { java.nio.file.Files.isSymbolicLink(file.toPath()) }.getOrDefault(false)
        }
    } catch (e: android.system.ErrnoException) {
        e.errno != OsConstants.ENOENT
    } catch (_: Throwable) {
        runCatching { java.nio.file.Files.isSymbolicLink(file.toPath()) }.getOrDefault(true)
    }

    private fun isValidOpenSshPublicKeyContent(text: String): Boolean {
        val trimmed = text.trim()
        if (trimmed.isEmpty() || trimmed.contains('\n') || trimmed.contains('\r')) return false
        if (trimmed.contains("-----BEGIN", ignoreCase = true)) return false
        return trimmed.startsWith("ssh-") ||
            trimmed.startsWith("ecdsa-sha2-") ||
            trimmed.startsWith("sk-ssh-") ||
            trimmed.startsWith("sk-ecdsa-")
    }

    private fun resolveSafePublicKeyFile(identityFile: String): File? {
        // When the device specifies an explicit identityFile, NEVER silently push a different key
        val candidates = if (identityFile.isNotBlank()) {
            val base = identityFile.removeSuffix(".pub")
            listOf("$base.pub")
        } else {
            listOf("id_ed25519.pub", "id_rsa.pub")
        }
        val canonicalSshDir = runCatching { sshDir.canonicalFile }.getOrNull() ?: return null
        for (name in candidates) {
            if (!PUBLIC_KEY_FILE_REGEX.matches(name) || name.startsWith('-')) continue
            val candidate = File(sshDir, name)
            if (isSymlinkFailClosed(candidate)) continue
            if (!candidate.isFile || candidate.length() !in 16L..8192L) continue
            val inDir = runCatching { candidate.canonicalFile.parentFile == canonicalSshDir }.getOrDefault(false)
            if (!inDir) continue
            val text = runCatching { candidate.readText(Charsets.UTF_8) }.getOrNull() ?: continue
            if (isValidOpenSshPublicKeyContent(text)) return candidate
        }
        return null
    }

    /**
     * Builds a dedicated local PTY invocation for `workstation install-ssh` that never writes
     * keystrokes into any active remote SSH or TUI agent session.
     */
    fun buildInstallOpenSshPtyExec(): Pair<String, Array<String>> {
        val shBin = File(prefixDir, "bin/sh").absolutePath
        val wsBin = File(prefixDir, "bin/workstation").absolutePath
        val loginBin = File(prefixDir, "bin/login").absolutePath
        val bashBin = File(prefixDir, "bin/bash").absolutePath
        return shBin to arrayOf(
            "-c",
            INSTALL_SSH_WRAPPER_SCRIPT,
            "rur-install-ssh",
            wsBin,
            loginBin,
            bashBin,
            shBin,
        )
    }

    private fun readDefaultPublicKey(): Pair<String?, String?> {
        val pubFile = resolveSafePublicKeyFile("") ?: return null to null
        val text = runCatching { pubFile.readText(Charsets.UTF_8).trim() }.getOrNull()
        return if (!text.isNullOrBlank() && isValidOpenSshPublicKeyContent(text)) {
            pubFile.name to text
        } else {
            null to null
        }
    }

    private data class MutableSshConfigHost(
        val alias: String,
        var hostName: String? = null,
        var user: String? = null,
        var port: Int? = null,
        var identityFile: String? = null,
    )

    private fun parseSshConfigHosts(existingSaved: List<SshDeviceRecord>): List<SshDeviceRecord> {
        if (!sshConfigFile.isFile || sshConfigFile.length() > MAX_CONFIG_BYTES) return emptyList()
        val lines = runCatching { sshConfigFile.readLines() }.getOrDefault(emptyList())
        val savedKeys = existingSaved.mapTo(HashSet()) {
            "${it.host.lowercase()}:${it.port}:${it.username.lowercase()}"
        }

        // Preserve stanza declaration order and apply OpenSSH first-match-wins across duplicate stanzas
        val entriesByKey = LinkedHashMap<String, MutableSshConfigHost>()
        var activeAliases: List<MutableSshConfigHost> = emptyList()

        for (rawLine in lines) {
            val trimmed = rawLine.trim()
            if (trimmed.isEmpty() || trimmed.startsWith('#')) continue
            val parts = trimmed.split(Regex("[\\s=]+"), limit = 2)
            if (parts.size < 2) continue
            val key = parts[0].lowercase()
            val rawValue = parts[1].trim()

            when (key) {
                "host" -> {
                    val tokens = splitConfigTokens(rawValue)
                    val concrete = tokens.filter { tok ->
                        tok.isNotEmpty() && !tok.contains('*') && !tok.contains('?') && !tok.startsWith('!')
                    }
                    activeAliases = concrete.map { alias ->
                        val lower = alias.lowercase()
                        entriesByKey.getOrPut(lower) { MutableSshConfigHost(alias = alias) }
                    }
                }
                "match" -> {
                    // Never attribute Match block directives to the preceding Host block
                    activeAliases = emptyList()
                }
                "hostname" -> {
                    val v = stripSingleTokenQuotes(rawValue)
                    if (v.isNotEmpty()) {
                        activeAliases.forEach { if (it.hostName == null) it.hostName = v }
                    }
                }
                "user" -> {
                    val v = stripSingleTokenQuotes(rawValue)
                    if (v.isNotEmpty()) {
                        activeAliases.forEach { if (it.user == null) it.user = v }
                    }
                }
                "port" -> {
                    val parsed = stripSingleTokenQuotes(rawValue).toIntOrNull()?.takeIf { it in 1..65535 }
                    if (parsed != null) {
                        activeAliases.forEach { if (it.port == null) it.port = parsed }
                    }
                }
                "identityfile" -> {
                    val v = stripSingleTokenQuotes(rawValue)
                    if (v.isNotEmpty() && activeAliases.isNotEmpty()) {
                        val safeKeyName = resolveSafeIdentityNameInSshDir(v)
                        if (safeKeyName != null) {
                            activeAliases.forEach { if (it.identityFile == null) it.identityFile = safeKeyName }
                        }
                    }
                }
            }
        }

        val discovered = mutableListOf<SshDeviceRecord>()
        for ((lowerAlias, entry) in entriesByKey) {
            val targetHost = (entry.hostName ?: entry.alias).trim()
            if (targetHost.isEmpty() || targetHost.contains('*') || targetHost.contains('?')) continue
            val port = entry.port ?: 22
            val user = entry.user.orEmpty()
            val dedupKey = "${targetHost.lowercase()}:$port:${user.lowercase()}"
            if (dedupKey in savedKeys) continue

            val valid = runCatching {
                validateRecord(
                    id = "$SSH_CONFIG_ID_PREFIX$lowerAlias",
                    name = entry.alias,
                    host = targetHost,
                    port = port,
                    username = user,
                    transport = if (looksLikeTailscaleHost(targetHost)) SshTransport.TAILSCALE else SshTransport.DIRECT,
                    identityFile = entry.identityFile.orEmpty(),
                    tags = "~/.ssh/config",
                    source = SshDeviceSource.SSH_CONFIG,
                )
            }.getOrNull()

            if (valid != null) {
                savedKeys.add(dedupKey)
                discovered.add(valid)
            }
        }
        return discovered
    }

    private fun resolveSafeIdentityNameInSshDir(rawPath: String): String? {
        val expanded = if (rawPath.startsWith("~/")) {
            File(homeDir, rawPath.removePrefix("~/"))
        } else {
            File(rawPath)
        }
        val baseName = expanded.name
        if (!IDENTITY_FILE_REGEX.matches(baseName) || baseName.startsWith('-')) return null

        // Reject symlinks escaping ~/.ssh/
        val isSymlink = runCatching {
            if (expanded.exists()) OsConstants.S_ISLNK(Os.lstat(expanded.absolutePath).st_mode)
            else false
        }.getOrDefault(false)
        if (isSymlink) return null

        val inSshDir = runCatching {
            val canonicalSshDir = sshDir.canonicalFile
            val canonicalParent = expanded.canonicalFile.parentFile
            canonicalParent == canonicalSshDir
        }.getOrDefault(false)
        return if (inSshDir) baseName else null
    }

    private fun readSavedDevicesForMutation(): List<SshDeviceRecord> {
        if (!devicesFile.isFile) return emptyList()
        require(devicesFile.length() <= MAX_CONFIG_BYTES) {
            "~/.config/melcat/ssh-devices.json excede el tamaño máximo permitido."
        }
        val raw = devicesFile.readText().trim()
        if (raw.isEmpty()) return emptyList()
        val root = runCatching { JSONObject(raw) }.getOrElse {
            throw IllegalStateException("~/.config/melcat/ssh-devices.json está dañado; corrige el JSON antes de modificarlo.")
        }
        return parseDevicesJson(root)
    }

    private fun readSavedDevicesTolerant(): List<SshDeviceRecord> {
        if (!devicesFile.isFile || devicesFile.length() > MAX_CONFIG_BYTES) return emptyList()
        val raw = runCatching { devicesFile.readText() }.getOrDefault("").trim()
        if (raw.isEmpty()) return emptyList()
        val root = runCatching { JSONObject(raw) }.getOrNull() ?: return emptyList()
        return parseDevicesJson(root)
    }

    private fun parseDevicesJson(root: JSONObject): List<SshDeviceRecord> {
        val arr = root.optJSONArray("devices") ?: return emptyList()
        val result = mutableListOf<SshDeviceRecord>()
        val seenIds = HashSet<String>()
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            val transport = SshTransport.entries.firstOrNull {
                it.name.equals(obj.optString("transport"), ignoreCase = true)
            } ?: SshTransport.DIRECT
            var id = obj.optString("id").trim()
            if (id.isEmpty() || id.startsWith(SSH_CONFIG_ID_PREFIX) || !seenIds.add(id)) {
                id = UUID.randomUUID().toString().also { seenIds.add(it) }
            }
            runCatching {
                validateRecord(
                    id = id,
                    name = obj.optString("name"),
                    host = obj.optString("host"),
                    port = obj.optInt("port", 22),
                    username = obj.optString("username", ""),
                    transport = transport,
                    identityFile = obj.optString("identityFile", ""),
                    tags = obj.optString("tags", ""),
                    localPortForwards = obj.optString("localPortForwards", ""),
                    startupCommand = obj.optString("startupCommand", ""),
                    source = SshDeviceSource.SAVED,
                    lastConnectedAt = obj.optLong("lastConnectedAt", 0L),
                )
            }.onSuccess { result.add(it) }
        }
        return result
    }

    private fun writeSavedDevicesAtomic(devices: List<SshDeviceRecord>) {
        if (!melcatConfigDir.exists()) melcatConfigDir.mkdirs()
        runCatching { Os.chmod(melcatConfigDir.absolutePath, 448) } // 0700

        val arr = JSONArray()
        devices.forEach { d ->
            arr.put(
                JSONObject().apply {
                    put("id", d.id)
                    put("name", d.name)
                    put("host", d.host)
                    put("port", d.port)
                    put("username", d.username)
                    put("transport", d.transport.name)
                    put("identityFile", d.identityFile)
                    put("tags", d.tags)
                    put("localPortForwards", d.localPortForwards)
                    put("startupCommand", d.startupCommand)
                    put("lastConnectedAt", d.lastConnectedAt)
                }
            )
        }
        val payload = JSONObject().apply {
            put("version", 1)
            put("devices", arr)
        }
        val bytes = (payload.toString(2) + "\n").toByteArray(Charsets.UTF_8)
        val tmp = File(melcatConfigDir, ".ssh-devices.json.tmp.${System.nanoTime()}")

        val wroteWithOs = runCatching {
            val fd = Os.open(
                tmp.absolutePath,
                OsConstants.O_WRONLY or OsConstants.O_CREAT or OsConstants.O_TRUNC or OsConstants.O_NOFOLLOW,
                384, // 0600
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
            check(tmp.isFile && tmp.length() == bytes.size.toLong()) { "Os write did not materialize tmp file" }
            Os.chmod(tmp.absolutePath, 384)
            Os.rename(tmp.absolutePath, devicesFile.absolutePath)
            !tmp.exists() && devicesFile.isFile
        }.onFailure {
            runCatching { tmp.delete() }
        }.getOrDefault(false)

        if (!wroteWithOs) {
            try {
                FileOutputStream(tmp).use { out ->
                    out.write(bytes)
                    out.fd.sync()
                }
                enforceMode0600(tmp)
                if (!tmp.renameTo(devicesFile)) {
                    // Unconditionally unlink target (even if dangling symlink) before fallback copy
                    runCatching { Os.remove(devicesFile.absolutePath) }
                    devicesFile.delete()
                    tmp.copyTo(devicesFile, overwrite = true)
                    tmp.delete()
                }
                enforceMode0600(devicesFile)
            } finally {
                if (tmp.exists()) runCatching { tmp.delete() }
            }
        }
    }

    private fun enforceMode0600(file: File) {
        if (isSymlinkFailClosed(file) || !file.exists()) return
        runCatching { Os.chmod(file.absolutePath, 384) }
        file.setReadable(false, false)
        file.setWritable(false, false)
        file.setExecutable(false, false)
        file.setReadable(true, true)
        file.setWritable(true, true)
    }

    data class LocalPortForward(
        val localPort: Int,
        val remoteHost: String,
        val remotePort: Int,
    ) {
        fun toDisplaySpec(): String =
            if (remoteHost == "127.0.0.1") "$localPort:$remotePort" else "$localPort:$remoteHost:$remotePort"
    }

    companion object {
        private const val MAX_CONCURRENT_PROBES = 8
        private const val MAX_CONFIG_BYTES = 262_144L
        private const val MAX_TUNNELS_PER_DEVICE = 6
        private const val SSH_CONFIG_ID_PREFIX = "sshcfg:"
        private val HOST_REGEX = Regex("^[A-Za-z0-9][A-Za-z0-9._-]{0,252}$")
        private val IPV6_CHARS_REGEX = Regex("^[0-9A-Fa-f:.]{2,45}$")
        private val USER_REGEX = Regex("^[A-Za-z0-9._][A-Za-z0-9._-]{0,63}$")
        private val IDENTITY_FILE_REGEX = Regex("^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$")
        private val PUBLIC_KEY_FILE_REGEX = Regex("^[A-Za-z0-9][A-Za-z0-9._-]{0,63}\\.pub$")

        private val SSH_PTY_WRAPPER_SCRIPT = """
            label="${'$'}1"
            ssh_bin="${'$'}2"
            login_bin="${'$'}3"
            bash_bin="${'$'}4"
            sh_bin="${'$'}5"
            shift 5
            printf '\033[1;33m[RUR SSH]\033[0m Connecting to %s ...\n' "${'$'}label"
            if [ ! -x "${'$'}ssh_bin" ]; then
                printf '\033[1;31m[RUR SSH]\033[0m OpenSSH not installed. Run: workstation install-ssh\n'
            else
                "${'$'}ssh_bin" "${'$'}@"
                rc=${'$'}?
                printf '\n\033[1;33m[RUR SSH]\033[0m Session ended (exit %d). Returning to local shell...\n' "${'$'}rc"
            fi
            if [ -x "${'$'}login_bin" ]; then
                exec "${'$'}login_bin"
            elif [ -x "${'$'}{SHELL:-}" ]; then
                exec "${'$'}SHELL" -l
            elif [ -x "${'$'}bash_bin" ]; then
                exec "${'$'}bash_bin" -l
            else
                exec "${'$'}sh_bin"
            fi
        """.trimIndent()

        private val COPY_ID_PTY_WRAPPER_SCRIPT = """
            label="${'$'}1"
            copy_id_bin="${'$'}2"
            ssh_bin="${'$'}3"
            pub_key="${'$'}4"
            login_bin="${'$'}5"
            bash_bin="${'$'}6"
            sh_bin="${'$'}7"
            shift 7
            printf '\033[1;33m[RUR SSH]\033[0m Installing public key on %s ...\n' "${'$'}label"
            if [ ! -r "${'$'}pub_key" ]; then
                printf '\033[1;31m[RUR SSH]\033[0m Public key missing: %s\n' "${'$'}pub_key"
            elif [ -x "${'$'}copy_id_bin" ]; then
                "${'$'}copy_id_bin" -i "${'$'}pub_key" "${'$'}@"
                rc=${'$'}?
                printf '\n\033[1;33m[RUR SSH]\033[0m ssh-copy-id finished (exit %d).\n' "${'$'}rc"
            elif [ -x "${'$'}ssh_bin" ]; then
                "${'$'}ssh_bin" "${'$'}@" 'umask 077 && mkdir -p ~/.ssh && chmod 700 ~/.ssh && touch ~/.ssh/authorized_keys && chmod 600 ~/.ssh/authorized_keys && { [ ! -s ~/.ssh/authorized_keys ] || printf "\n"; cat; } >> ~/.ssh/authorized_keys' < "${'$'}pub_key"
                rc=${'$'}?
                printf '\n\033[1;33m[RUR SSH]\033[0m Public key push finished (exit %d).\n' "${'$'}rc"
            else
                printf '\033[1;31m[RUR SSH]\033[0m OpenSSH not installed. Run: workstation install-ssh\n'
            fi
            if [ -x "${'$'}login_bin" ]; then
                exec "${'$'}login_bin"
            elif [ -x "${'$'}{SHELL:-}" ]; then
                exec "${'$'}SHELL" -l
            elif [ -x "${'$'}bash_bin" ]; then
                exec "${'$'}bash_bin" -l
            else
                exec "${'$'}sh_bin"
            fi
        """.trimIndent()

        private val INSTALL_SSH_WRAPPER_SCRIPT = """
            ws_bin="${'$'}1"
            login_bin="${'$'}2"
            bash_bin="${'$'}3"
            sh_bin="${'$'}4"
            if [ -x "${'$'}ws_bin" ]; then
                "${'$'}ws_bin" install-ssh || true
            else
                pkg update -y && pkg install -y -o Dpkg::Options::=--force-confold openssh || true
            fi
            if [ -x "${'$'}login_bin" ]; then
                exec "${'$'}login_bin"
            elif [ -x "${'$'}bash_bin" ]; then
                exec "${'$'}bash_bin" -l
            else
                exec "${'$'}sh_bin"
            fi
        """.trimIndent()

        private val probeExecutor = Executors.newFixedThreadPool(MAX_CONCURRENT_PROBES) { runnable ->
            Thread(runnable, "rur-ssh-probe").apply { isDaemon = true }
        }

        private fun splitConfigTokens(raw: String): List<String> {
            val out = mutableListOf<String>()
            val sb = StringBuilder()
            var inQuotes = false
            for (ch in raw) {
                when {
                    ch == '"' -> inQuotes = !inQuotes
                    ch.isWhitespace() && !inQuotes -> {
                        if (sb.isNotEmpty()) {
                            out.add(sb.toString())
                            sb.setLength(0)
                        }
                    }
                    else -> sb.append(ch)
                }
            }
            if (sb.isNotEmpty()) out.add(sb.toString())
            return out
        }

        private fun stripSingleTokenQuotes(raw: String): String =
            splitConfigTokens(raw).firstOrNull().orEmpty()

        private fun isValidIpv6Literal(host: String): Boolean {
            if (!host.contains(':') || host.contains(":::") || host.contains("..")) return false
            if (!IPV6_CHARS_REGEX.matches(host)) return false
            val doubleColons = host.windowed(2).count { it == "::" }
            return doubleColons <= 1
        }

        /**
         * Parses & strictly validates comma/whitespace-separated local port forwards:
         * - `localPort:remotePort` (binds `127.0.0.1:localPort -> 127.0.0.1:remotePort`)
         * - `localPort:remoteHost:remotePort` (binds `127.0.0.1:localPort -> remoteHost:remotePort`)
         */
        fun parseLocalPortForwards(raw: String): List<LocalPortForward> {
            val trimmed = raw.trim()
            if (trimmed.isEmpty()) return emptyList()
            val tokens = trimmed.split(Regex("[,\\s]+")).filter { it.isNotBlank() }
            require(tokens.size <= MAX_TUNNELS_PER_DEVICE) {
                "Máximo $MAX_TUNNELS_PER_DEVICE túneles locales por dispositivo"
            }
            val seenLocalPorts = HashSet<Int>()
            return tokens.map { tok ->
                val parts = tok.split(':')
                when (parts.size) {
                    2 -> {
                        val lp = parts[0].toIntOrNull()
                        val rp = parts[1].toIntOrNull()
                        require(lp != null && lp in 1024..65535 && rp != null && rp in 1..65535) {
                            "Túnel inválido '$tok' (puerto local 1024–65535, remoto 1–65535)"
                        }
                        require(seenLocalPorts.add(lp)) { "Puerto local duplicado en túneles: $lp" }
                        LocalPortForward(localPort = lp, remoteHost = "127.0.0.1", remotePort = rp)
                    }
                    3 -> {
                        val lp = parts[0].toIntOrNull()
                        val rh = parts[1].trim()
                        val rp = parts[2].toIntOrNull()
                        require(lp != null && lp in 1024..65535 && rp != null && rp in 1..65535) {
                            "Túnel inválido '$tok' (puerto local 1024–65535, remoto 1–65535)"
                        }
                        require(rh.isNotEmpty() && !rh.startsWith('-') && !rh.contains("..") && HOST_REGEX.matches(rh)) {
                            "Host remoto inválido en túnel '$tok'"
                        }
                        require(seenLocalPorts.add(lp)) { "Puerto local duplicado en túneles: $lp" }
                        LocalPortForward(localPort = lp, remoteHost = rh, remotePort = rp)
                    }
                    else -> throw IllegalArgumentException(
                        "Túnel inválido '$tok' (usa local:remoto o local:host:remoto, ej. 11434:11434)"
                    )
                }
            }
        }

        fun validateRecord(
            id: String,
            name: String,
            host: String,
            port: Int,
            username: String,
            transport: SshTransport,
            identityFile: String,
            tags: String,
            localPortForwards: String = "",
            startupCommand: String = "",
            source: SshDeviceSource = SshDeviceSource.SAVED,
            lastConnectedAt: Long = 0L,
        ): SshDeviceRecord {
            val cleanName = name.trim().filterNot { it.isISOControl() }.take(48)
            require(cleanName.isNotEmpty()) { "Ingresa un nombre para el dispositivo" }

            val cleanHost = host.trim().removePrefix("[").removeSuffix("]")
            require(cleanHost.isNotEmpty()) { "Ingresa el host, IP o dominio Tailscale" }
            require(!cleanHost.startsWith('-')) { "El host no puede iniciar con '-'" }
            require(!cleanHost.contains("..")) { "Host inválido" }
            require(HOST_REGEX.matches(cleanHost) || isValidIpv6Literal(cleanHost)) {
                "Host inválido: usa letras, números, puntos, guiones o IPv6"
            }

            require(port in 1..65535) { "El puerto debe estar entre 1 y 65535" }

            val cleanUser = username.trim()
            if (cleanUser.isNotEmpty()) {
                require(!cleanUser.startsWith('-')) { "El usuario no puede iniciar con '-'" }
                require(USER_REGEX.matches(cleanUser)) {
                    "Usuario inválido: usa solo letras, números, '.', '_' o '-'"
                }
            }

            val cleanIdentity = identityFile.trim()
            if (cleanIdentity.isNotEmpty()) {
                require(!cleanIdentity.startsWith('-')) { "Nombre de clave inválido" }
                require(!cleanIdentity.contains("..") && !cleanIdentity.contains('/') && !cleanIdentity.contains('\\')) {
                    "Escribe solo el nombre de archivo dentro de ~/.ssh/ (ej. id_ed25519)"
                }
                require(IDENTITY_FILE_REGEX.matches(cleanIdentity)) {
                    "Nombre de clave inválido (ej. id_ed25519)"
                }
            }

            val cleanTags = tags.trim().filterNot { it.isISOControl() }.take(64)
            val normalizedForwards = parseLocalPortForwards(localPortForwards)
                .joinToString(", ") { it.toDisplaySpec() }
            val cleanStartup = startupCommand.trim().filterNot { it.isISOControl() }.take(160)

            return SshDeviceRecord(
                id = id,
                name = cleanName,
                host = cleanHost,
                port = port,
                username = cleanUser,
                transport = transport,
                identityFile = cleanIdentity,
                tags = cleanTags,
                localPortForwards = normalizedForwards,
                startupCommand = cleanStartup,
                source = source,
                lastConnectedAt = lastConnectedAt,
            )
        }

        /**
         * Returns true for Tailscale CGNAT IPs (100.64.0.0/10 per RFC 6598),
         * Tailscale IPv6 ULA (fd7a:115c:a1e0::/48), or MagicDNS (*.ts.net).
         */
        fun looksLikeTailscaleHost(host: String): Boolean {
            val h = host.trim().removePrefix("[").removeSuffix("]").lowercase()
            if (h.endsWith(".ts.net")) return true
            if (h.startsWith("fd7a:115c:a1e0:")) return true
            val parts = h.split('.')
            if (parts.size != 4) return false
            val octets = parts.map { it.toIntOrNull() ?: return false }
            return octets[0] == 100 && octets[1] in 64..127 && octets[2] in 0..255 && octets[3] in 0..255
        }

        private fun matchTailscaleAddress(addr: InetAddress): Pair<Boolean, String?> {
            if (addr is Inet4Address) {
                val b = addr.address
                if (b.size == 4) {
                    val first = b[0].toInt() and 0xFF
                    val second = b[1].toInt() and 0xFF
                    if (first == 100 && second in 64..127) {
                        return true to addr.hostAddress
                    }
                }
            } else if (addr is Inet6Address) {
                val b = addr.address
                if (b.size == 16 &&
                    (b[0].toInt() and 0xFF) == 0xFD &&
                    (b[1].toInt() and 0xFF) == 0x7A &&
                    (b[2].toInt() and 0xFF) == 0x11 &&
                    (b[3].toInt() and 0xFF) == 0x5C &&
                    (b[4].toInt() and 0xFF) == 0xA1 &&
                    (b[5].toInt() and 0xFF) == 0xE0
                ) {
                    return false to addr.hostAddress?.substringBefore('%')
                }
            }
            return false to null
        }

        fun detectLocalTailscaleIp(context: Context? = null): String? {
            var ipv6Fallback: String? = null

            // 1. Prefer Android ConnectivityManager active TRANSPORT_VPN networks (avoids cellular CGNAT false positives)
            if (context != null) {
                val cm = runCatching {
                    context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
                }.getOrNull()
                if (cm != null) {
                    val networks = runCatching { cm.allNetworks }.getOrNull().orEmpty()
                    for (net in networks) {
                        val caps = runCatching { cm.getNetworkCapabilities(net) }.getOrNull() ?: continue
                        if (!caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) continue
                        val lp = runCatching { cm.getLinkProperties(net) }.getOrNull() ?: continue
                        for (la in lp.linkAddresses) {
                            val (isV4, ip) = matchTailscaleAddress(la.address)
                            if (isV4 && ip != null) return ip
                            if (!isV4 && ip != null && ipv6Fallback == null) ipv6Fallback = ip
                        }
                    }
                    if (ipv6Fallback != null) return ipv6Fallback
                }
            }

            // 2. Fallback: scan only tunnel / point-to-point interfaces (excludes cellular rmnet_data*/ccmni*)
            val interfaces = runCatching { NetworkInterface.getNetworkInterfaces() }.getOrNull() ?: return null
            while (interfaces.hasMoreElements()) {
                val iface = interfaces.nextElement()
                val active = runCatching { iface.isUp && !iface.isLoopback }.getOrDefault(false)
                if (!active) continue
                val ifaceName = iface.name.lowercase()
                val isTunnel = ifaceName.startsWith("tun") ||
                    ifaceName.startsWith("tailscale") ||
                    ifaceName.startsWith("utun") ||
                    ifaceName.startsWith("wg")
                if (!isTunnel) continue

                val addrs = iface.inetAddresses
                while (addrs.hasMoreElements()) {
                    val (isV4, ip) = matchTailscaleAddress(addrs.nextElement())
                    if (isV4 && ip != null) return ip
                    if (!isV4 && ip != null && ipv6Fallback == null) ipv6Fallback = ip
                }
            }
            return ipv6Fallback
        }

        private suspend fun defaultTcpProbe(host: String, port: Int, timeoutMs: Int): Long? {
            return withTimeoutOrNull(timeoutMs.toLong() + 500L) {
                suspendCancellableCoroutine { cont ->
                    val socket = Socket()
                    val future = probeExecutor.submit {
                        val start = System.nanoTime()
                        val latency = try {
                            socket.connect(InetSocketAddress(host, port), timeoutMs)
                            ((System.nanoTime() - start) / 1_000_000L).coerceAtLeast(1L)
                        } catch (_: Throwable) {
                            null
                        } finally {
                            runCatching { socket.close() }
                        }
                        if (cont.isActive) cont.resume(latency)
                    }
                    cont.invokeOnCancellation {
                        future.cancel(true)
                        runCatching { socket.close() }
                    }
                }
            }
        }
    }
}
