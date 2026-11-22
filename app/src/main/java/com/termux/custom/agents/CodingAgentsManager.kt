package com.termux.custom.agents

import android.system.Os
import android.system.OsConstants
import com.termux.shared.termux.TermuxConstants
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.URI
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermission

enum class CodingProvider(
    val id: String,
    val displayName: String,
    val envVar: String?,
    val requiresApiKey: Boolean,
    val requiresBaseUrl: Boolean,
    val subtitle: String,
) {
    OPENCODE_FREE(
        id = "opencode",
        displayName = "Gratis (\$0)",
        envVar = null,
        requiresApiKey = false,
        requiresBaseUrl = false,
        subtitle = "Modelos gratuitos de OpenCode Console sin API key ni tarjeta",
    ),
    OPENROUTER(
        id = "openrouter",
        displayName = "OpenRouter",
        envVar = "OPENROUTER_API_KEY",
        requiresApiKey = true,
        requiresBaseUrl = false,
        subtitle = "1 clave -> Qwen3 Coder, Kimi Code, Devstral, Codestral y Codex",
    ),
    MISTRAL(
        id = "mistral",
        displayName = "Mistral",
        envVar = "MISTRAL_API_KEY",
        requiresApiKey = true,
        requiresBaseUrl = false,
        subtitle = "API directa de Mistral para Codestral y Devstral",
    ),
    OLLAMA(
        id = "ollama",
        displayName = "Ollama LAN",
        envVar = null,
        requiresApiKey = false,
        requiresBaseUrl = true,
        subtitle = "Modelos *-coder 100% privados y \$0 en tu PC por Wi-Fi o local",
    ),
}

enum class CodingBudgetTier(val badge: String) {
    FREE("GRATIS \$0"),
    LOCAL("LOCAL \$0"),
    BUDGET("ECONÓMICO"),
    PRO("PRO"),
}

data class CodingModelPreset(
    val id: String,
    val provider: CodingProvider,
    val displayName: String,
    val specialty: String,
    val contextWindow: String,
    val priceLabel: String,
    val tier: CodingBudgetTier,
    val recommended: Boolean = false,
)

data class ProviderCredentialStatus(
    val provider: CodingProvider,
    val isConfigured: Boolean,
    val preview: String,
    val posixModeOctal: String,
)

data class CodingAgentsState(
    val agyInstalled: Boolean,
    val opencodeInstalled: Boolean,
    val activeOpenCodeModel: String?,
    val ollamaBaseUrl: String?,
    val providerStatuses: Map<CodingProvider, ProviderCredentialStatus>,
) {
    val configuredPaidOrLocalProvidersCount: Int
        get() = providerStatuses.values.count {
            it.provider != CodingProvider.OPENCODE_FREE && it.isConfigured
        }

    fun isProviderReady(provider: CodingProvider): Boolean = when (provider) {
        CodingProvider.OPENCODE_FREE -> true
        else -> providerStatuses[provider]?.isConfigured == true
    }

    val activePreset: CodingModelPreset?
        get() = CodingModelCatalog.findById(activeOpenCodeModel)
}

/**
 * Curated catalog of coding-focused models verified against live OpenCode v2 / models.dev / OpenRouter catalogs.
 * Covers every budget tier: Free ($0), Local/LAN ($0), Budget ($0.07-$0.30/M), and Pro Frontier.
 */
object CodingModelCatalog {

    val presets: List<CodingModelPreset> = listOf(
        // 1. FREE ($0 — No API Key required via OpenCode Console)
        CodingModelPreset(
            id = "opencode/deepseek-v4.1-flash-free",
            provider = CodingProvider.OPENCODE_FREE,
            displayName = "DeepSeek V4.1 Flash Free",
            specialty = "#1 en OpenCode para programación · cero retención",
            contextWindow = "1M ctx",
            priceLabel = "\$0 gratis",
            tier = CodingBudgetTier.FREE,
            recommended = true,
        ),
        CodingModelPreset(
            id = "opencode/flint-40b-free",
            provider = CodingProvider.OPENCODE_FREE,
            displayName = "Flint 40B Free",
            specialty = "Rápido para ediciones y debugging ligero",
            contextWindow = "262K ctx",
            priceLabel = "\$0 gratis",
            tier = CodingBudgetTier.FREE,
        ),

        // 2. OPENROUTER — 100% Dedicated Coding Models (Budget -> Pro)
        CodingModelPreset(
            id = "openrouter/qwen/qwen3-coder-30b-a3b-instruct",
            provider = CodingProvider.OPENROUTER,
            displayName = "Qwen3 Coder 30B-A3B",
            specialty = "MoE exclusivo de código · el más económico",
            contextWindow = "262K ctx",
            priceLabel = "\$0.07 / \$0.28",
            tier = CodingBudgetTier.BUDGET,
        ),
        CodingModelPreset(
            id = "openrouter/qwen/qwen3-coder-next",
            provider = CodingProvider.OPENROUTER,
            displayName = "Qwen3 Coder Next",
            specialty = "80B MoE (3B activos) · diseñado para agentes CLI",
            contextWindow = "262K ctx",
            priceLabel = "\$0.12 / \$0.80",
            tier = CodingBudgetTier.BUDGET,
            recommended = true,
        ),
        CodingModelPreset(
            id = "openrouter/qwen/qwen3-coder-flash",
            provider = CodingProvider.OPENROUTER,
            displayName = "Qwen3 Coder Flash",
            specialty = "Ventana 1M para repositorios grandes",
            contextWindow = "1M ctx",
            priceLabel = "\$0.20 / \$0.98",
            tier = CodingBudgetTier.BUDGET,
        ),
        CodingModelPreset(
            id = "openrouter/openai/gpt-5.1-codex-mini",
            provider = CodingProvider.OPENROUTER,
            displayName = "GPT-5.1 Codex Mini",
            specialty = "Familia Codex rápida para refactors y tests",
            contextWindow = "400K ctx",
            priceLabel = "\$0.25 / \$2.00",
            tier = CodingBudgetTier.BUDGET,
        ),
        CodingModelPreset(
            id = "openrouter/qwen/qwen3-coder",
            provider = CodingProvider.OPENROUTER,
            displayName = "Qwen3 Coder 480B",
            specialty = "480B MoE (35B activos) · código complejo",
            contextWindow = "262K ctx",
            priceLabel = "\$0.30 / \$1.00",
            tier = CodingBudgetTier.BUDGET,
        ),
        CodingModelPreset(
            id = "openrouter/mistralai/codestral-2508",
            provider = CodingProvider.OPENROUTER,
            displayName = "Codestral 2508",
            specialty = "Baja latencia especializado en código",
            contextWindow = "256K ctx",
            priceLabel = "\$0.30 / \$0.90",
            tier = CodingBudgetTier.BUDGET,
        ),
        CodingModelPreset(
            id = "openrouter/mistralai/devstral-2512",
            provider = CodingProvider.OPENROUTER,
            displayName = "Devstral 2 (123B)",
            specialty = "Agentic coding multi-archivo de Mistral",
            contextWindow = "262K ctx",
            priceLabel = "\$0.40 / \$2.00",
            tier = CodingBudgetTier.PRO,
        ),
        CodingModelPreset(
            id = "openrouter/moonshotai/kimi-k2.7-code",
            provider = CodingProvider.OPENROUTER,
            displayName = "Kimi K2.7 Code",
            specialty = "Especializado en ingeniería de software",
            contextWindow = "262K ctx",
            priceLabel = "\$0.60 / \$3.00",
            tier = CodingBudgetTier.PRO,
        ),
        CodingModelPreset(
            id = "openrouter/openai/gpt-5.3-codex",
            provider = CodingProvider.OPENROUTER,
            displayName = "GPT-5.3 Codex",
            specialty = "Codex frontier para arquitectura y tareas críticas",
            contextWindow = "400K ctx",
            priceLabel = "\$1.75 / \$14.00",
            tier = CodingBudgetTier.PRO,
        ),

        // 3. MISTRAL DIRECT API — Dedicated Coding Models
        CodingModelPreset(
            id = "mistral/codestral-latest",
            provider = CodingProvider.MISTRAL,
            displayName = "Codestral Latest",
            specialty = "Endpoint directo Mistral · código rápido",
            contextWindow = "256K ctx",
            priceLabel = "API Mistral",
            tier = CodingBudgetTier.BUDGET,
            recommended = true,
        ),
        CodingModelPreset(
            id = "mistral/devstral-latest",
            provider = CodingProvider.MISTRAL,
            displayName = "Devstral Latest",
            specialty = "Endpoint directo Mistral · agente de código",
            contextWindow = "262K ctx",
            priceLabel = "API Mistral",
            tier = CodingBudgetTier.PRO,
        ),

        // 4. OLLAMA LOCAL / LAN — 100% Private & $0 Coding Weights
        CodingModelPreset(
            id = "ollama/qwen2.5-coder:7b",
            provider = CodingProvider.OLLAMA,
            displayName = "Qwen2.5 Coder 7B",
            specialty = "4.7 GB Q4 · corre en 8 GB RAM (Local/LAN)",
            contextWindow = "64K ctx",
            priceLabel = "\$0 privado",
            tier = CodingBudgetTier.LOCAL,
            recommended = true,
        ),
        CodingModelPreset(
            id = "ollama/qwen2.5-coder:14b",
            provider = CodingProvider.OLLAMA,
            displayName = "Qwen2.5 Coder 14B",
            specialty = "9.0 GB Q4 · PC en Wi-Fi con 12-16 GB",
            contextWindow = "64K ctx",
            priceLabel = "\$0 privado",
            tier = CodingBudgetTier.LOCAL,
        ),
        CodingModelPreset(
            id = "ollama/deepseek-coder-v2:16b",
            provider = CodingProvider.OLLAMA,
            displayName = "DeepSeek Coder V2 16B",
            specialty = "MoE · 338 lenguajes de programación",
            contextWindow = "64K ctx",
            priceLabel = "\$0 privado",
            tier = CodingBudgetTier.LOCAL,
        ),
        CodingModelPreset(
            id = "ollama/codestral:22b",
            provider = CodingProvider.OLLAMA,
            displayName = "Codestral 22B",
            specialty = "13 GB Q4 · PC en Wi-Fi con 16-24 GB",
            contextWindow = "32K ctx",
            priceLabel = "\$0 privado",
            tier = CodingBudgetTier.LOCAL,
        ),
        CodingModelPreset(
            id = "ollama/qwen3-coder:30b",
            provider = CodingProvider.OLLAMA,
            displayName = "Qwen3 Coder 30B (MoE)",
            specialty = "3.3B activos · PC en Wi-Fi con 24 GB",
            contextWindow = "64K ctx",
            priceLabel = "\$0 privado",
            tier = CodingBudgetTier.LOCAL,
        ),
    )

    fun presetsFor(provider: CodingProvider): List<CodingModelPreset> =
        presets.filter { it.provider == provider }

    fun findById(modelId: String?): CodingModelPreset? =
        if (modelId.isNullOrBlank()) null else presets.firstOrNull { it.id == modelId }
}

/**
 * Manages native coding agent runtime status, 0600 provider API key vault, and
 * OpenCode v2 native config (~/.config/opencode/opencode.json + cli.json) without ever
 * writing plaintext secrets into opencode.json (uses official `{file:...}` references).
 */
class CodingAgentsManager(
    private val prefixDir: File = File(TermuxConstants.TERMUX_PREFIX_DIR_PATH),
    private val homeDir: File = File(TermuxConstants.TERMUX_HOME_DIR_PATH),
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private val writeMutex = kotlinx.coroutines.sync.Mutex()
    private val vaultDir: File get() = File(homeDir, ".config/melcat/vault")
    private val opencodeConfigDir: File get() = File(homeDir, ".config/opencode")
    private val opencodeJsonFile: File get() = File(opencodeConfigDir, "opencode.json")
    private val opencodeCliJsonFile: File get() = File(opencodeConfigDir, "cli.json")
    private val providersEnvFile: File get() = File(vaultDir, "providers.env")
    private val ollamaUrlFile: File get() = File(vaultDir, "ollama.url")

    private fun keyFileFor(provider: CodingProvider): File =
        File(vaultDir, "${provider.id}.key")

    suspend fun inspectState(): CodingAgentsState = withContext(ioDispatcher) {
        val agyBin = File(prefixDir, "bin/agy")
        val agyVa39 = File(prefixDir, "bin/agy.va39")
        val opencodeBin = File(prefixDir, "bin/opencode")

        val configJson = readOpenCodeJsonTolerant()
        val activeModel = configJson.optString("model").takeIf { it.isNotBlank() }

        val ollamaUrl = readTrimmedOrNull(ollamaUrlFile)
            ?: configJson.optJSONObject("providers")
                ?.optJSONObject("ollama")
                ?.optJSONObject("settings")
                ?.optString("baseURL")
                ?.takeIf { it.isNotBlank() }

        val statuses = CodingProvider.entries.associateWith { provider ->
            when {
                provider == CodingProvider.OPENCODE_FREE -> ProviderCredentialStatus(
                    provider = provider,
                    isConfigured = true,
                    preview = "Listo (\$0 sin API key)",
                    posixModeOctal = "----",
                )
                provider == CodingProvider.OLLAMA -> ProviderCredentialStatus(
                    provider = provider,
                    isConfigured = !ollamaUrl.isNullOrBlank(),
                    preview = ollamaUrl ?: "No configurado",
                    posixModeOctal = if (ollamaUrlFile.isFile) readModeOctal(ollamaUrlFile) else "----",
                )
                else -> {
                    val file = keyFileFor(provider)
                    val value = readTrimmedOrNull(file)
                    if (!value.isNullOrBlank()) {
                        enforceMode0600(file)
                        ProviderCredentialStatus(
                            provider = provider,
                            isConfigured = true,
                            preview = maskSecret(value),
                            posixModeOctal = readModeOctal(file),
                        )
                    } else {
                        ProviderCredentialStatus(
                            provider = provider,
                            isConfigured = false,
                            preview = "Sin API key",
                            posixModeOctal = "----",
                        )
                    }
                }
            }
        }

        CodingAgentsState(
            agyInstalled = agyBin.canExecute() && agyVa39.canExecute(),
            opencodeInstalled = opencodeBin.canExecute(),
            activeOpenCodeModel = activeModel,
            ollamaBaseUrl = ollamaUrl,
            providerStatuses = statuses,
        )
    }

    suspend fun saveProviderApiKey(
        provider: CodingProvider,
        rawApiKey: String,
    ): Result<CodingAgentsState> = withContext(ioDispatcher) {
        writeMutex.withLock {
            runCatching {
                require(provider.requiresApiKey) { "${provider.displayName} does not require an API key" }
                val clean = rawApiKey.trim()
                require(clean.length >= 8) { "API key must be at least 8 characters" }
                require(clean.none { it.isISOControl() || it.isWhitespace() }) {
                    "API key cannot contain spaces or control characters"
                }

                // Parse existing config BEFORE writing secret so malformed user config fails cleanly
                val config = readOpenCodeJsonForMutation()
                ensureVaultDir0700()
                val targetKeyFile = keyFileFor(provider)
                writeAndLockFileAtomic(targetKeyFile, clean)
                rebuildProvidersEnvFile()

                // Wire official OpenCode v2 {file:...} substitution — ZERO plaintext secrets inside opencode.json!
                config.put("\$schema", OPENCODE_CONFIG_SCHEMA)
                val providersObj = config.optJSONObject("providers") ?: JSONObject().also {
                    config.put("providers", it)
                }
                val providerObj = providersObj.optJSONObject(provider.id) ?: JSONObject().also {
                    providersObj.put(provider.id, it)
                }
                val settingsObj = providerObj.optJSONObject("settings") ?: JSONObject().also {
                    providerObj.put("settings", it)
                }
                settingsObj.put("apiKey", "{file:~/.config/melcat/vault/${provider.id}.key}")

                writeOpenCodeJson(config)
                ensureTermuxCliKeybinds()
                inspectState()
            }
        }
    }

    suspend fun clearProviderApiKey(provider: CodingProvider): CodingAgentsState =
        withContext(ioDispatcher) {
            writeMutex.withLock {
                if (provider.requiresApiKey) {
                    secureWipeAndDelete(keyFileFor(provider))
                    rebuildProvidersEnvFile()

                    val config = runCatching { readOpenCodeJsonForMutation() }.getOrNull()
                    val providersObj = config?.optJSONObject("providers")
                    val providerObj = providersObj?.optJSONObject(provider.id)
                    val settingsObj = providerObj?.optJSONObject("settings")
                    if (config != null && settingsObj != null) {
                        settingsObj.remove("apiKey")
                        if (settingsObj.length() == 0) providerObj.remove("settings")
                        if (providerObj.length() == 0) providersObj.remove(provider.id)
                        if (providersObj.length() == 0) config.remove("providers")
                        writeOpenCodeJson(config)
                    }
                }
                inspectState()
            }
        }

    suspend fun saveOllamaBaseUrl(rawUrl: String): Result<CodingAgentsState> =
        withContext(ioDispatcher) {
            writeMutex.withLock {
                runCatching {
                    val normalized = normalizeAndValidateOllamaUrl(rawUrl)
                    val config = readOpenCodeJsonForMutation()
                    ensureVaultDir0700()
                    writeAndLockFileAtomic(ollamaUrlFile, normalized)

                    // Official OpenCode v2 built-in Ollama discovery endpoint (providers.ollama.settings.baseURL)
                    config.put("\$schema", OPENCODE_CONFIG_SCHEMA)
                    val providersObj = config.optJSONObject("providers") ?: JSONObject().also {
                        config.put("providers", it)
                    }
                    val ollamaObj = providersObj.optJSONObject("ollama") ?: JSONObject().also {
                        providersObj.put("ollama", it)
                    }
                    val settingsObj = ollamaObj.optJSONObject("settings") ?: JSONObject().also {
                        ollamaObj.put("settings", it)
                    }
                    settingsObj.put("baseURL", normalized)

                    writeOpenCodeJson(config)
                    ensureTermuxCliKeybinds()
                    inspectState()
                }
            }
        }

    suspend fun clearOllamaBaseUrl(): CodingAgentsState = withContext(ioDispatcher) {
        writeMutex.withLock {
            secureWipeAndDelete(ollamaUrlFile)
            val config = runCatching { readOpenCodeJsonForMutation() }.getOrNull()
            val providersObj = config?.optJSONObject("providers")
            val ollamaObj = providersObj?.optJSONObject("ollama")
            val settingsObj = ollamaObj?.optJSONObject("settings")
            if (config != null && settingsObj != null) {
                settingsObj.remove("baseURL")
                if (settingsObj.length() == 0) ollamaObj.remove("settings")
                if (ollamaObj.length() == 0) providersObj.remove("ollama")
                if (providersObj.length() == 0) config.remove("providers")
                writeOpenCodeJson(config)
            }
            inspectState()
        }
    }

    suspend fun selectCodingModel(preset: CodingModelPreset): Result<CodingAgentsState> =
        withContext(ioDispatcher) {
            writeMutex.withLock {
                runCatching {
                    require(CodingModelCatalog.findById(preset.id) != null) {
                        "Model must belong to the verified coding-only catalog"
                    }
                    val config = readOpenCodeJsonForMutation()
                    config.put("\$schema", OPENCODE_CONFIG_SCHEMA)
                    config.put("model", preset.id)
                    writeOpenCodeJson(config)
                    ensureTermuxCliKeybinds()
                    inspectState()
                }
            }
        }

    /**
     * Ensures ~/.config/opencode/cli.json exists with Termux touch-keyboard friendly `agent.cycle: "tab"`
     * (Android soft keyboards cannot send Shift+Tab). Never overwrites existing user customizations.
     */
    fun ensureTermuxCliKeybinds() {
        if (opencodeCliJsonFile.isFile && opencodeCliJsonFile.length() > 0L) return
        opencodeConfigDir.mkdirs()
        val cliJson = JSONObject().apply {
            put("\$schema", OPENCODE_CLI_SCHEMA)
            put(
                "keybinds",
                JSONObject().apply {
                    put("agent.cycle", "tab")
                    put("agent.cycle.reverse", "shift+tab")
                },
            )
        }
        writePlainFileAtomic(opencodeCliJsonFile, unescapeJsonForwardSlashes(cliJson.toString(2)) + "\n")
    }

    /**
     * Strict read used before mutating opencode.json: strips JSONC trailing commas safely and
     * throws if a non-empty user file cannot be parsed, preventing silent destruction of custom config.
     */
    private fun readOpenCodeJsonForMutation(): JSONObject {
        if (!opencodeJsonFile.isFile) return JSONObject()
        val raw = opencodeJsonFile.readText().trim()
        if (raw.isEmpty()) return JSONObject()
        val normalized = TRAILING_COMMA_REGEX.replace(raw, "$1")
        return runCatching { JSONObject(normalized) }.getOrElse {
            throw IllegalStateException(
                "Existing ~/.config/opencode/opencode.json is not valid JSON — fix syntax before saving to avoid overwriting custom settings."
            )
        }
    }

    /** Tolerant read used solely for read-only UI inspection. */
    private fun readOpenCodeJsonTolerant(): JSONObject {
        if (!opencodeJsonFile.isFile) return JSONObject()
        val raw = runCatching { opencodeJsonFile.readText() }.getOrDefault("").trim()
        if (raw.isEmpty()) return JSONObject()
        val normalized = TRAILING_COMMA_REGEX.replace(raw, "$1")
        return runCatching { JSONObject(normalized) }.getOrDefault(JSONObject())
    }

    private fun writeOpenCodeJson(json: JSONObject) {
        opencodeConfigDir.mkdirs()
        writePlainFileAtomic(opencodeJsonFile, unescapeJsonForwardSlashes(json.toString(2)) + "\n")
    }

    /**
     * Android's org.json escapes '/' as '\/'. Unescaping '\/' -> '/' (while preserving '\\')
     * produces clean RFC 8259 JSON so OpenCode v2's `{file:...}` substitutions and URLs never see '\/'.
     */
    private fun unescapeJsonForwardSlashes(rawJson: String): String {
        val sb = StringBuilder(rawJson.length)
        var i = 0
        while (i < rawJson.length) {
            val ch = rawJson[i]
            if (ch == '\\' && i + 1 < rawJson.length) {
                val next = rawJson[i + 1]
                if (next == '/') {
                    sb.append('/')
                } else {
                    sb.append(ch).append(next)
                }
                i += 2
            } else {
                sb.append(ch)
                i += 1
            }
        }
        return sb.toString()
    }

    private fun rebuildProvidersEnvFile() {
        ensureVaultDir0700()
        val lines = mutableListOf(
            "# Auto-managed by Terminal Agents Builder (0600, excluded from snapshots)",
        )
        CodingProvider.entries.filter { it.requiresApiKey && it.envVar != null }.forEach { provider ->
            val value = readTrimmedOrNull(keyFileFor(provider))
            if (!value.isNullOrBlank()) {
                val escaped = value.replace("'", "'\"'\"'")
                lines.add("export ${provider.envVar}='$escaped'")
            }
        }
        if (lines.size == 1) {
            secureWipeAndDelete(providersEnvFile)
        } else {
            writeAndLockFileAtomic(providersEnvFile, lines.joinToString("\n"))
        }
    }

    private fun ensureVaultDir0700() {
        if (!vaultDir.exists()) vaultDir.mkdirs()
        runCatching { Os.chmod(vaultDir.absolutePath, 448) } // 0700
    }

    private fun readTrimmedOrNull(file: File): String? =
        if (file.isFile && file.length() > 0L) {
            runCatching { file.readText().trim() }.getOrNull()?.takeIf { it.isNotEmpty() }
        } else {
            null
        }

    private fun writePlainFileAtomic(target: File, content: String) {
        val parent = target.parentFile ?: return
        if (!parent.exists()) parent.mkdirs()
        val tmp = File(parent, ".${target.name}.tmp.${System.nanoTime()}")
        FileOutputStream(tmp).use { out ->
            out.write(content.toByteArray(Charsets.UTF_8))
            out.fd.sync()
        }
        if (!tmp.renameTo(target)) {
            tmp.copyTo(target, overwrite = true)
            tmp.delete()
        }
    }

    private fun writeAndLockFileAtomic(target: File, content: String) {
        val parent = target.parentFile ?: return
        if (!parent.exists()) parent.mkdirs()
        runCatching { Os.chmod(parent.absolutePath, 448) } // 0700

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
        runCatching { Os.chmod(file.absolutePath, 384) }
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
        val osMode = runCatching { Os.stat(file.absolutePath).st_mode and 511 }.getOrNull()
        if (osMode != null && osMode != 0) return String.format("%04o", osMode)
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

    private fun maskSecret(secret: String): String =
        if (secret.length <= 10) "••••" + secret.takeLast(2)
        else secret.take(4) + "••••••••" + secret.takeLast(4)

    companion object {
        const val OPENCODE_CONFIG_SCHEMA = "https://opencode.ai/config.json"
        const val OPENCODE_CLI_SCHEMA = "https://opencode.ai/v2/cli.json"
        private val TRAILING_COMMA_REGEX = Regex(",\\s*([}\\]])")

        fun normalizeAndValidateOllamaUrl(rawInput: String): String {
            val trimmed = rawInput.trim().trimEnd('/')
            require(trimmed.isNotEmpty()) { "Enter an Ollama URL (e.g. http://192.168.1.50:11434/v1)" }
            require(trimmed.none { it.isISOControl() || it.isWhitespace() || it in ";|&`\$'\"<>" }) {
                "Ollama URL contains invalid characters"
            }
            val parsed = runCatching { URI(trimmed) }.getOrNull()
                ?: throw IllegalArgumentException("Invalid URL syntax")
            val scheme = parsed.scheme?.lowercase()
            require(scheme == "http" || scheme == "https") {
                "Ollama URL must start with http:// or https://"
            }
            require(parsed.userInfo == null) {
                "Ollama URL cannot embed credentials (user:pass)"
            }
            require(parsed.query.isNullOrEmpty() && parsed.fragment.isNullOrEmpty()) {
                "Ollama URL cannot contain query parameters or fragments"
            }
            require(!parsed.host.isNullOrBlank()) {
                "Ollama URL must include a valid host or IP address"
            }
            require(parsed.port == -1 || parsed.port in 1..65535) {
                "Ollama URL port must be between 1 and 65535"
            }
            val normalizedPath = (parsed.rawPath ?: "").trimEnd('/')
            val finalPath = if (normalizedPath.endsWith("/v1", ignoreCase = true)) {
                normalizedPath.dropLast(3) + "/v1"
            } else {
                "$normalizedPath/v1"
            }
            val authority = if (parsed.port != -1) "${parsed.host}:${parsed.port}" else parsed.host
            return "$scheme://$authority$finalPath"
        }
    }
}
