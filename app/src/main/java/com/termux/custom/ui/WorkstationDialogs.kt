package com.termux.custom.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.termux.custom.agents.CodingAgentsState
import com.termux.custom.agents.CodingBudgetTier
import com.termux.custom.agents.CodingModelCatalog
import com.termux.custom.agents.CodingModelPreset
import com.termux.custom.agents.CodingProvider
import com.termux.custom.security.OAuthTokenState
import com.termux.custom.skills.SkillRepoInfo

// ============================================================================================
// DIALOGS: SEARCHABLE COMMAND PALETTE, CODING MODELS VAULT & SECURE OAUTH DIALOG
// ============================================================================================
private data class PaletteAction(
    val title: String,
    val subtitle: String,
    val shortcut: String = "",
    val keywords: String = "",
    val run: () -> Unit,
)

@Composable
internal fun SearchableCommandPaletteDialog(
    isZenMode: Boolean,
    onDismiss: () -> Unit,
    onExecuteShellCommand: (String) -> Unit,
    onNewPtySession: () -> Unit,
    onToggleZen: () -> Unit,
    onOpenTokenVault: () -> Unit,
    onOpenCodingModelsVault: () -> Unit,
    onOpenSshPanel: () -> Unit,
    onCheckAppUpdate: () -> Unit,
    onOpenTutorial: () -> Unit,
) {
    var query by rememberSaveable { mutableStateOf("") }
    val latestExec by rememberUpdatedState(onExecuteShellCommand)
    val latestNewPty by rememberUpdatedState(onNewPtySession)
    val latestToggleZen by rememberUpdatedState(onToggleZen)
    val latestOpenToken by rememberUpdatedState(onOpenTokenVault)
    val latestOpenCoding by rememberUpdatedState(onOpenCodingModelsVault)
    val latestOpenSsh by rememberUpdatedState(onOpenSshPanel)
    val latestCheckUpdate by rememberUpdatedState(onCheckAppUpdate)
    val latestOpenTutorial by rememberUpdatedState(onOpenTutorial)

    val actions = remember(isZenMode) {
        listOf(
            PaletteAction("agy", "Launch official Google Antigravity CLI (native arm64)", "", "antigravity cli agent", { latestExec("agy") }),
            PaletteAction("opencode", "Launch native Bionic OpenCode v2 coding agent", "", "opencode code coder qwen devstral", { latestExec("opencode") }),
            PaletteAction("Dispositivos SSH & Tailscale", "Connect to online LAN & Tailscale devices with 1 tap", "", "ssh tailscale lan remote jetson pi devices", { latestOpenSsh() }),
            PaletteAction("Coding Models & Vault (0600)", "Select code-only models (Free / DeepSeek / Claude / OpenAI / Ollama)", "", "models openrouter mistral deepseek claude anthropic openai ollama qwen coder codestral codex", { latestOpenCoding() }),
            PaletteAction("workstation install-ssh", "Install native Termux OpenSSH client (ssh, ssh-keygen)", "", "ssh openssh install tailscale keygen", { latestExec("workstation install-ssh") }),
            PaletteAction("workstation skills update", "Pull latest skills repos safely (rebase + autostash, keeps AI edits)", "", "skills git pull update sync repo", { latestExec("workstation skills update") }),
            PaletteAction("workstation skills diff", "Inspect local skill improvements made by agy / opencode / claude", "", "skills diff changes git status", { latestExec("workstation skills diff") }),
            PaletteAction("workstation skills push", "Commit & push improved skills to your remote Git repository", "", "skills push sync github backup", { latestExec("workstation skills push") }),
            PaletteAction("workstation install-opencode", "Install native Bionic OpenCode v2 via signed APT repo", "", "install opencode bionic apt", { latestExec("workstation install-opencode") }),
            PaletteAction("workstation setup", "Install native Antigravity CLI (agy) + glibc + toolchain", "", "setup install agy glibc", { latestExec("workstation setup") }),
            PaletteAction("Workstation Doctor", "Verify agy, opencode, openssh, glibc, TLS, DNS & 0600 vaults", "", "doctor diagnose toolchain agy opencode ssh", { latestExec("workstation doctor") }),
            PaletteAction("agy update", "Self-update native Antigravity CLI binary", "", "update upgrade agy", { latestExec("agy update") }),
            PaletteAction("zellij", "Start Zellij terminal multiplexer", "", "", { latestExec("zellij") }),
            PaletteAction("New Terminal Session", "Spawn an isolated native PTY shell tab", "^⇧T", "", { latestNewPty() }),
            PaletteAction(if (isZenMode) "Exit Zen Mode" else "Enter Zen Mode", "Expand terminal canvas across full display", "^⇧B", "", { latestToggleZen() }),
            PaletteAction("Clean Caches (melcat-gc)", "Purge apt/pip/npm caches and zstd-compress old logs", "", "", { latestExec("melcat-gc") }),
            PaletteAction("Portable Snapshot", "Create secret-free tar.zst workspace archive", "", "", { latestExec("melcat-gc --snapshot") }),
            PaletteAction("Antigravity OAuth Vault", "Provision or rotate 0600 Antigravity OAuth token", "", "oauth token vault", { latestOpenToken() }),
            PaletteAction("Check for App Update (OTA)", "Query GitHub Releases and verify SHA-256", "", "", { latestCheckUpdate() }),
            PaletteAction("Interactive Tutorial & First Steps", "Guided workstation onboarding & setup checklist", "", "tutorial help primeros pasos guide", { latestOpenTutorial() }),
            PaletteAction("claude (optional)", "Launch Claude CLI if installed with Anthropic account", "", "claude anthropic", { latestExec("claude") }),
        )
    }

    val filtered = remember(query, actions) {
        val q = query.trim().lowercase()
        if (q.isEmpty()) actions else actions.filter {
            it.title.lowercase().contains(q) ||
                it.subtitle.lowercase().contains(q) ||
                it.keywords.lowercase().contains(q)
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = WorkstationColors.SurfaceElevated,
            border = BorderStroke(1.dp, WorkstationColors.BorderStrong),
            modifier = Modifier
                .fillMaxWidth(0.92f)
                .widthIn(max = 520.dp)
                .heightIn(max = 460.dp),
        ) {
            Column(
                modifier = Modifier.padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = { Text("Search commands, agents, actions…", fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(
                        autoCorrectEnabled = false,
                        keyboardType = KeyboardType.Ascii,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )

                LazyColumn(
                    modifier = Modifier.weight(1f, fill = false),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    items(filtered, key = { it.title }) { action ->
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = WorkstationColors.SurfaceKeycap,
                            border = BorderStroke(1.dp, WorkstationColors.BorderHairline),
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable(onClick = action.run),
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 12.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = action.title,
                                        fontFamily = FontFamily.Monospace,
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = WorkstationColors.AccentCyan,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    Text(
                                        text = action.subtitle,
                                        fontSize = 11.sp,
                                        color = WorkstationColors.TextSecondary,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                                if (action.shortcut.isNotEmpty()) {
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = action.shortcut,
                                        fontFamily = FontFamily.Monospace,
                                        fontSize = 10.sp,
                                        color = WorkstationColors.TextMuted,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun TokenVaultDialog(
    currentState: OAuthTokenState,
    onDismiss: () -> Unit,
    onLaunchAgySignIn: () -> Unit,
    onSaveToken: (String) -> Unit,
    onRevokeToken: () -> Unit,
    errorText: String?,
) {
    var inputToken by rememberSaveable { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = WorkstationColors.SurfaceElevated,
        title = { Text("Antigravity CLI OAuth Vault (0600)", fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = "Launch the official Antigravity CLI (`agy`) to complete Google Sign-In directly in your browser. Its token at ~/.gemini/antigravity-cli/antigravity-oauth-token is automatically detected and locked with POSIX 0600.",
                    fontSize = 12.sp,
                    color = WorkstationColors.TextSecondary,
                )
                Button(
                    onClick = onLaunchAgySignIn,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = WorkstationColors.AccentCyan,
                        contentColor = WorkstationColors.VoidCanvas,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        text = "Sign in via Antigravity CLI (agy)",
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                HorizontalDivider(thickness = 1.dp, color = WorkstationColors.BorderHairline)
                Text(
                    text = "Or import an existing antigravity-oauth-token from another device:",
                    fontSize = 11.sp,
                    color = WorkstationColors.TextMuted,
                )
                OutlinedTextField(
                    value = inputToken,
                    onValueChange = { inputToken = it },
                    label = { Text("antigravity-oauth-token", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    singleLine = true,
                    isError = !errorText.isNullOrBlank(),
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(
                        autoCorrectEnabled = false,
                        keyboardType = KeyboardType.Password,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )
                if (!errorText.isNullOrBlank()) {
                    Text(
                        text = errorText,
                        fontSize = 11.sp,
                        color = WorkstationColors.AccentRose,
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onSaveToken(inputToken) },
                enabled = inputToken.trim().length >= 8,
            ) {
                Text("Lock 0600 & Sync", maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        },
        dismissButton = {
            Row {
                if (currentState.isPresent) {
                    TextButton(
                        onClick = onRevokeToken,
                        colors = ButtonDefaults.textButtonColors(contentColor = WorkstationColors.AccentRose),
                    ) {
                        Text("Revoke", maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                TextButton(onClick = onDismiss) {
                    Text("Cancel", maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        },
    )
}

@Composable
internal fun PrivateRepoOtaTokenDialog(
    hasExistingToken: Boolean,
    onDismiss: () -> Unit,
    onSaveToken: (String) -> Unit,
    onClearToken: () -> Unit,
) {
    var inputPat by rememberSaveable { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = WorkstationColors.SurfaceElevated,
        title = { Text("Private GitHub OTA Token", fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = "Optional: Only needed while the repository is Private. Paste a read-only GitHub Fine-Grained PAT (Contents + Metadata Read) to enable 1-tap automatic OTA updates. If you make the repo Public, leave empty.",
                    fontSize = 12.sp,
                    color = WorkstationColors.TextSecondary,
                )
                OutlinedTextField(
                    value = inputPat,
                    onValueChange = { inputPat = it },
                    label = { Text("GitHub Read-Only PAT", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(
                        autoCorrectEnabled = false,
                        keyboardType = KeyboardType.Password,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onSaveToken(inputPat) },
                enabled = inputPat.trim().length >= 10,
            ) {
                Text("Save & Check", maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        },
        dismissButton = {
            Row {
                if (hasExistingToken) {
                    TextButton(
                        onClick = onClearToken,
                        colors = ButtonDefaults.textButtonColors(contentColor = WorkstationColors.AccentRose),
                    ) {
                        Text("Clear", maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                TextButton(onClick = onDismiss) {
                    Text("Cancel", maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        },
    )
}

@Composable
internal fun NewWorkspaceDialog(
    onDismiss: () -> Unit,
    onConfirm: (title: String, agentType: String, summary: String) -> Unit,
) {
    val agentTypes = remember { listOf("agy", "opencode", "zellij", "sh", "claude") }
    var title by rememberSaveable { mutableStateOf("") }
    var selectedAgentType by rememberSaveable { mutableStateOf("agy") }
    var summary by rememberSaveable { mutableStateOf("") }

    val trimmedTitle = title.trim()

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = WorkstationColors.SurfaceElevated,
        title = {
            Text(
                text = "New Workspace",
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = title,
                    onValueChange = { next ->
                        if (next.length <= 48) {
                            title = next
                        } else {
                            title = next.take(48)
                        }
                    },
                    label = { Text("Workspace Title (max 48)", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )

                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = "Agent Runtime",
                        fontSize = 11.sp,
                        color = WorkstationColors.TextSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        agentTypes.forEach { agent ->
                            val selected = selectedAgentType == agent
                            FilterChip(
                                selected = selected,
                                onClick = { selectedAgentType = agent },
                                label = {
                                    Text(
                                        text = agent,
                                        fontFamily = FontFamily.Monospace,
                                        fontSize = 11.sp,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = WorkstationColors.AccentGoldWash,
                                    selectedLabelColor = WorkstationColors.AccentGold,
                                    containerColor = WorkstationColors.SurfaceKeycap,
                                    labelColor = WorkstationColors.TextSecondary,
                                ),
                                border = FilterChipDefaults.filterChipBorder(
                                    enabled = true,
                                    selected = selected,
                                    borderColor = WorkstationColors.BorderHairline,
                                    selectedBorderColor = WorkstationColors.BorderStrong,
                                ),
                            )
                        }
                    }
                }

                OutlinedTextField(
                    value = summary,
                    onValueChange = { summary = it },
                    label = { Text("Summary (optional)", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    singleLine = false,
                    maxLines = 3,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onConfirm(trimmedTitle, selectedAgentType, summary.trim()) },
                enabled = trimmedTitle.isNotEmpty(),
            ) {
                Text("Create", maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel", maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        },
    )
}

@Composable
internal fun ImportDeltasDialog(
    onDismiss: () -> Unit,
    onConfirm: (json: String) -> Unit,
) {
    var jsonText by rememberSaveable { mutableStateOf("") }
    var validationError by rememberSaveable { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = WorkstationColors.SurfaceElevated,
        title = {
            Text(
                text = "Import CRDT Deltas",
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "Paste an exported CRDT delta bundle JSON to merge remote workspace states.",
                    fontSize = 12.sp,
                    color = WorkstationColors.TextSecondary,
                )
                OutlinedTextField(
                    value = jsonText,
                    onValueChange = {
                        jsonText = it
                        validationError = null
                    },
                    label = { Text("Delta Bundle JSON", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    singleLine = false,
                    minLines = 4,
                    maxLines = 8,
                    isError = validationError != null,
                    modifier = Modifier.fillMaxWidth(),
                )
                validationError?.let { err ->
                    Text(
                        text = err,
                        fontSize = 11.sp,
                        color = WorkstationColors.AccentRose,
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val trimmed = jsonText.trim()
                    if (!trimmed.startsWith("{") || !trimmed.contains("\"deltas\"")) {
                        validationError = "JSON must start with '{' and contain \"deltas\"."
                    } else {
                        onConfirm(trimmed)
                    }
                },
                enabled = jsonText.isNotBlank(),
            ) {
                Text("Merge Deltas", maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel", maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        },
    )
}

@Composable
internal fun CodingModelsVaultDialog(
    state: CodingAgentsState,
    errorText: String?,
    onDismiss: () -> Unit,
    onSelectModel: (CodingModelPreset) -> Unit,
    onSaveApiKey: (CodingProvider, String, onSaved: () -> Unit) -> Unit,
    onClearApiKey: (CodingProvider) -> Unit,
    onSaveOllamaUrl: (String) -> Unit,
    onClearOllamaUrl: () -> Unit,
    onLaunchOrInstallOpenCode: () -> Unit,
) {
    val initialProvider = state.activePreset?.provider ?: CodingProvider.OPENCODE_FREE
    var selectedProviderName by rememberSaveable { mutableStateOf(initialProvider.name) }
    val selectedProvider = CodingProvider.entries.firstOrNull { it.name == selectedProviderName }
        ?: CodingProvider.OPENCODE_FREE

    var apiKeyInput by rememberSaveable(selectedProviderName) { mutableStateOf("") }
    var ollamaUrlInput by rememberSaveable(state.ollamaBaseUrl) {
        mutableStateOf(state.ollamaBaseUrl ?: "http://192.168.1.50:11434/v1")
    }

    val providerPresets = remember(selectedProvider) {
        CodingModelCatalog.presetsFor(selectedProvider)
    }
    val providerStatus = state.providerStatuses[selectedProvider]

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        androidx.compose.foundation.layout.Box(
            modifier = Modifier
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .imePadding()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            contentAlignment = Alignment.Center,
        ) {
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = WorkstationColors.SurfaceElevated,
                border = BorderStroke(1.dp, WorkstationColors.BorderStrong),
                modifier = Modifier
                    .fillMaxWidth()
                    .widthIn(max = 580.dp)
                    .heightIn(max = 640.dp),
            ) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Coding Models & Vault (0600)",
                                fontSize = 15.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = WorkstationColors.TextPrimary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                text = "Modelos enfocados a código (gratis, económicos, pro y locales)",
                                fontSize = 11.sp,
                                color = WorkstationColors.TextSecondary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        TextButton(onClick = onDismiss) {
                            Text("Close", fontSize = 12.sp, color = WorkstationColors.TextSecondary)
                        }
                    }

                    // Provider selector tabs
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        CodingProvider.entries.forEach { provider ->
                            val selected = provider == selectedProvider
                            val configured = state.isProviderReady(provider)
                            val labelText = if (provider != CodingProvider.OPENCODE_FREE && configured) {
                                "${provider.displayName} ✓"
                            } else {
                                provider.displayName
                            }
                            FilterChip(
                                selected = selected,
                                onClick = { selectedProviderName = provider.name },
                                label = {
                                    Text(
                                        text = labelText,
                                        fontFamily = FontFamily.Monospace,
                                        fontSize = 11.sp,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = WorkstationColors.AccentGoldWash,
                                    selectedLabelColor = WorkstationColors.AccentGold,
                                    containerColor = WorkstationColors.SurfaceKeycap,
                                    labelColor = WorkstationColors.TextSecondary,
                                ),
                                border = FilterChipDefaults.filterChipBorder(
                                    enabled = true,
                                    selected = selected,
                                    borderColor = WorkstationColors.BorderHairline,
                                    selectedBorderColor = WorkstationColors.BorderStrong,
                                ),
                            )
                        }
                    }

                    // Unified scrolling body (credential box + model presets) so IME / Phone Landscape never collapses to 0dp
                    LazyColumn(
                        modifier = Modifier.weight(1f, fill = false),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        item(key = "provider_setup_${selectedProvider.name}") {
                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = WorkstationColors.SurfaceKeycap,
                                border = BorderStroke(1.dp, WorkstationColors.BorderHairline),
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Column(
                                    modifier = Modifier.padding(10.dp),
                                    verticalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    Text(
                                        text = selectedProvider.subtitle,
                                        fontSize = 11.sp,
                                        color = WorkstationColors.TextSecondary,
                                    )

                                    when {
                                        selectedProvider == CodingProvider.OPENCODE_FREE -> {
                                            Text(
                                                text = "Sin API key: elige un modelo gratis abajo y abre `opencode`. Los modelos gratuitos de OpenCode Console rotan periódicamente (`/models` muestra la lista en vivo).",
                                                fontSize = 11.sp,
                                                color = WorkstationColors.AccentEmerald,
                                            )
                                        }

                                        selectedProvider.requiresApiKey -> {
                                            if (providerStatus?.isConfigured == true) {
                                                Text(
                                                    text = "Vault activo: ${providerStatus.preview} · mode ${providerStatus.posixModeOctal} ({file:...}, sin secretos en opencode.json)",
                                                    fontFamily = FontFamily.Monospace,
                                                    fontSize = 10.sp,
                                                    color = WorkstationColors.AccentEmerald,
                                                )
                                            }
                                            OutlinedTextField(
                                                value = apiKeyInput,
                                                onValueChange = { apiKeyInput = it },
                                                label = {
                                                    Text(
                                                        "${selectedProvider.envVar ?: "API Key"} (0600)",
                                                        fontSize = 11.sp,
                                                        maxLines = 1,
                                                        overflow = TextOverflow.Ellipsis,
                                                    )
                                                },
                                                singleLine = true,
                                                visualTransformation = PasswordVisualTransformation(),
                                                keyboardOptions = KeyboardOptions(
                                                    autoCorrectEnabled = false,
                                                    keyboardType = KeyboardType.Password,
                                                ),
                                                modifier = Modifier.fillMaxWidth(),
                                            )
                                            Row(
                                                modifier = Modifier.fillMaxWidth(),
                                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                                verticalAlignment = Alignment.CenterVertically,
                                            ) {
                                                Button(
                                                    onClick = {
                                                        onSaveApiKey(selectedProvider, apiKeyInput) {
                                                            apiKeyInput = ""
                                                        }
                                                    },
                                                    enabled = apiKeyInput.trim().length >= 8,
                                                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                                                    modifier = Modifier.weight(1f),
                                                ) {
                                                    Text("Lock 0600", fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                                }
                                                if (providerStatus?.isConfigured == true) {
                                                    TextButton(
                                                        onClick = { onClearApiKey(selectedProvider) },
                                                        colors = ButtonDefaults.textButtonColors(contentColor = WorkstationColors.AccentRose),
                                                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp),
                                                    ) {
                                                        Text("Clear", fontSize = 11.sp, maxLines = 1)
                                                    }
                                                }
                                            }
                                        }

                                        selectedProvider.requiresBaseUrl -> {
                                            if (providerStatus?.isConfigured == true) {
                                                Text(
                                                    text = "Endpoint Ollama v2 activo: ${providerStatus.preview}",
                                                    fontFamily = FontFamily.Monospace,
                                                    fontSize = 10.sp,
                                                    color = WorkstationColors.AccentEmerald,
                                                )
                                            }
                                            OutlinedTextField(
                                                value = ollamaUrlInput,
                                                onValueChange = { ollamaUrlInput = it },
                                                label = {
                                                    Text(
                                                        "Ollama URL (LAN o 127.0.0.1)",
                                                        fontSize = 11.sp,
                                                        maxLines = 1,
                                                        overflow = TextOverflow.Ellipsis,
                                                    )
                                                },
                                                singleLine = true,
                                                keyboardOptions = KeyboardOptions(
                                                    autoCorrectEnabled = false,
                                                    keyboardType = KeyboardType.Uri,
                                                ),
                                                modifier = Modifier.fillMaxWidth(),
                                            )
                                            Row(
                                                modifier = Modifier.fillMaxWidth(),
                                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                                verticalAlignment = Alignment.CenterVertically,
                                            ) {
                                                Button(
                                                    onClick = { onSaveOllamaUrl(ollamaUrlInput) },
                                                    enabled = ollamaUrlInput.trim().startsWith("http"),
                                                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                                                    modifier = Modifier.weight(1f),
                                                ) {
                                                    Text("Save Endpoint", fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                                }
                                                if (providerStatus?.isConfigured == true) {
                                                    TextButton(
                                                        onClick = onClearOllamaUrl,
                                                        colors = ButtonDefaults.textButtonColors(contentColor = WorkstationColors.AccentRose),
                                                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp),
                                                    ) {
                                                        Text("Clear", fontSize = 11.sp, maxLines = 1)
                                                    }
                                                }
                                            }
                                        }
                                    }

                                    if (!errorText.isNullOrBlank()) {
                                        Text(
                                            text = errorText,
                                            fontSize = 11.sp,
                                            color = WorkstationColors.AccentRose,
                                        )
                                    }
                                }
                            }
                        }

                        items(providerPresets, key = { it.id }) { preset ->
                            val isActive = state.activeOpenCodeModel == preset.id
                            val tierColor = when (preset.tier) {
                                CodingBudgetTier.FREE, CodingBudgetTier.LOCAL -> WorkstationColors.AccentEmerald
                                CodingBudgetTier.BUDGET -> WorkstationColors.AccentGold
                                CodingBudgetTier.PRO -> WorkstationColors.AccentAmber
                            }
                            val tierWash = when (preset.tier) {
                                CodingBudgetTier.FREE, CodingBudgetTier.LOCAL -> WorkstationColors.AccentEmeraldWash
                                CodingBudgetTier.BUDGET -> WorkstationColors.AccentGoldWash
                                CodingBudgetTier.PRO -> WorkstationColors.AccentAmberWash
                            }

                            Surface(
                                shape = RoundedCornerShape(10.dp),
                                color = if (isActive) WorkstationColors.SurfaceInteractive else WorkstationColors.SurfaceKeycap,
                                border = BorderStroke(
                                    1.dp,
                                    if (isActive) WorkstationColors.AccentGold else WorkstationColors.BorderHairline,
                                ),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { onSelectModel(preset) },
                            ) {
                                Column(
                                    modifier = Modifier.padding(horizontal = 11.dp, vertical = 9.dp),
                                    verticalArrangement = Arrangement.spacedBy(3.dp),
                                ) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Text(
                                            text = if (isActive) "★ ${preset.displayName}" else preset.displayName,
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.SemiBold,
                                            color = if (isActive) WorkstationColors.AccentGold else WorkstationColors.TextPrimary,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            modifier = Modifier.weight(1f),
                                        )
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Surface(
                                            shape = RoundedCornerShape(5.dp),
                                            color = tierWash,
                                        ) {
                                            Text(
                                                text = preset.tier.badge,
                                                fontFamily = FontFamily.Monospace,
                                                fontSize = 9.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = tierColor,
                                                maxLines = 1,
                                                softWrap = false,
                                                overflow = TextOverflow.Ellipsis,
                                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                            )
                                        }
                                    }
                                    Text(
                                        text = "${preset.contextWindow} · ${preset.priceLabel} · ${preset.specialty}",
                                        fontSize = 11.sp,
                                        color = WorkstationColors.TextSecondary,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    Text(
                                        text = preset.id,
                                        fontFamily = FontFamily.Monospace,
                                        fontSize = 9.sp,
                                        color = WorkstationColors.TextMuted,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                        }
                    }

                    HorizontalDivider(thickness = 1.dp, color = WorkstationColors.BorderHairline)

                    Button(
                        onClick = onLaunchOrInstallOpenCode,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = WorkstationColors.AccentGold,
                            contentColor = WorkstationColors.VoidCanvas,
                        ),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            text = if (state.opencodeInstalled) "Launch OpenCode v2" else "Install Native OpenCode v2",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}

@Composable
internal fun AddSkillRepoDialog(
    isRootGitRepo: Boolean,
    onDismiss: () -> Unit,
    onCloneSubRepo: (gitUrl: String, folderName: String) -> Unit,
    onConnectRootRepo: (gitUrl: String) -> Unit,
) {
    var mode by rememberSaveable { mutableStateOf("add") } // "add" or "init"
    var gitUrl by rememberSaveable { mutableStateOf("") }
    var folderName by rememberSaveable { mutableStateOf("") }
    var errorText by rememberSaveable { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = WorkstationColors.SurfaceElevated,
        title = {
            Text(
                text = "Repositorios de Skills (Git)",
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                color = WorkstationColors.TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(9.dp),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    FilterChip(
                        selected = mode == "add",
                        onClick = {
                            mode = "add"
                            errorText = null
                        },
                        label = { Text("Agregar Skill Repo", fontSize = 11.sp, maxLines = 1) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = WorkstationColors.AccentGoldWash,
                            selectedLabelColor = WorkstationColors.AccentGold,
                            containerColor = WorkstationColors.SurfaceKeycap,
                            labelColor = WorkstationColors.TextSecondary,
                        ),
                    )
                    FilterChip(
                        selected = mode == "init",
                        onClick = {
                            mode = "init"
                            errorText = null
                        },
                        label = { Text(if (isRootGitRepo) "Cambiar Remote" else "Sync Propio (Init)", fontSize = 11.sp, maxLines = 1) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = WorkstationColors.AccentGoldWash,
                            selectedLabelColor = WorkstationColors.AccentGold,
                            containerColor = WorkstationColors.SurfaceKeycap,
                            labelColor = WorkstationColors.TextSecondary,
                        ),
                    )
                }

                Text(
                    text = if (mode == "add") {
                        "Clona un repo Git dentro de ~/.gemini/config/skills/. Podrás actualizarlo con rebase+autostash sin perder mejoras del agente."
                    } else {
                        "Conecta ~/.gemini/config/skills/ a tu propio repo GitHub para hacer push de mejoras que haga el agente y sincronizar celular y tablet."
                    },
                    fontSize = 11.sp,
                    color = WorkstationColors.TextSecondary,
                )

                OutlinedTextField(
                    value = gitUrl,
                    onValueChange = {
                        gitUrl = it.trim().take(240)
                        errorText = null
                    },
                    label = { Text("URL Git (https://... o git@github.com:...)", fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    singleLine = true,
                    isError = errorText != null,
                    keyboardOptions = KeyboardOptions(
                        autoCorrectEnabled = false,
                        keyboardType = KeyboardType.Uri,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )

                if (mode == "add") {
                    OutlinedTextField(
                        value = folderName,
                        onValueChange = {
                            folderName = it.trim().take(48)
                            errorText = null
                        },
                        label = { Text("Nombre carpeta (opcional)", fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(autoCorrectEnabled = false),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }

                if (!errorText.isNullOrBlank()) {
                    Text(
                        text = errorText!!,
                        fontSize = 11.sp,
                        color = WorkstationColors.AccentRose,
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val urlResult = com.termux.custom.skills.SkillsRepoManager.validateGitUrl(gitUrl)
                    if (urlResult.isFailure) {
                        errorText = urlResult.exceptionOrNull()?.message ?: "URL inválida"
                        return@Button
                    }
                    val safeUrl = urlResult.getOrThrow()
                    if (mode == "add") {
                        if (folderName.isNotBlank()) {
                            val nameRes = com.termux.custom.skills.SkillsRepoManager.validateSkillName(folderName)
                            if (nameRes.isFailure) {
                                errorText = nameRes.exceptionOrNull()?.message ?: "Nombre inválido"
                                return@Button
                            }
                            onCloneSubRepo(safeUrl, nameRes.getOrThrow())
                        } else {
                            onCloneSubRepo(safeUrl, "")
                        }
                    } else {
                        onConnectRootRepo(safeUrl)
                    }
                },
                enabled = gitUrl.isNotBlank(),
            ) {
                Text(if (mode == "add") "Clonar" else "Conectar", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancelar", fontSize = 12.sp, color = WorkstationColors.TextSecondary)
            }
        },
    )
}

@Composable
internal fun SkillMarkdownEditorDialog(
    skillName: String,
    skillInfo: SkillRepoInfo?,
    content: String,
    isLoading: Boolean,
    isLoadFailed: Boolean,
    isSaving: Boolean,
    hasUnsavedChanges: Boolean,
    errorText: String?,
    onContentChange: (String) -> Unit,
    onSave: () -> Unit,
    onOpenDiff: () -> Unit,
    onDismiss: () -> Unit,
) {
    var pendingDiscardAction by remember { mutableStateOf<String?>(null) } // "dismiss" or "diff"
    val byteCount = remember(content) { utf8ByteLength(content) }
    val isOverLimit = byteCount > 131_072
    val sizeLabel = remember(byteCount) {
        if (byteCount < 1024) {
            "$byteCount B / 128 KB"
        } else {
            String.format(java.util.Locale.US, "%.1f / 128 KB", byteCount / 1024.0)
        }
    }
    val statusBadgeText = when {
        hasUnsavedChanges -> "SIN GUARDAR"
        skillInfo?.hasLocalEdits == true -> "EDITADO"
        !skillInfo?.branch.isNullOrBlank() -> skillInfo!!.branch!!
        skillInfo?.isGitRepo == true -> "GIT"
        else -> "LOCAL"
    }
    val statusBadgeColor = when {
        hasUnsavedChanges || skillInfo?.hasLocalEdits == true -> WorkstationColors.AccentAmber
        skillInfo?.isGitRepo == true -> WorkstationColors.AccentEmerald
        else -> WorkstationColors.AccentGold
    }
    val statusBadgeBg = when {
        hasUnsavedChanges || skillInfo?.hasLocalEdits == true -> WorkstationColors.AccentAmberWash
        skillInfo?.isGitRepo == true -> WorkstationColors.AccentEmeraldWash
        else -> WorkstationColors.SurfaceKeycap
    }

    fun requestCloseOrDiff(action: String) {
        if (isSaving) return
        if (hasUnsavedChanges && !isLoadFailed) {
            pendingDiscardAction = action
        } else if (action == "diff") {
            onOpenDiff()
        } else {
            onDismiss()
        }
    }

    Dialog(
        onDismissRequest = { requestCloseOrDiff("dismiss") },
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
            dismissOnClickOutside = !isSaving && !hasUnsavedChanges,
        ),
    ) {
        androidx.compose.foundation.layout.BoxWithConstraints(
            modifier = Modifier
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .imePadding()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            contentAlignment = Alignment.Center,
        ) {
            val isCompactHeight = maxHeight < 380.dp
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = WorkstationColors.SurfaceElevated,
                border = BorderStroke(1.dp, WorkstationColors.BorderStrong),
                modifier = Modifier
                    .fillMaxWidth()
                    .widthIn(max = 680.dp)
                    .heightIn(max = 680.dp),
            ) {
                Column(
                    modifier = Modifier
                        .padding(if (isCompactHeight) 10.dp else 14.dp)
                        .then(if (isCompactHeight) Modifier.verticalScroll(rememberScrollState()) else Modifier),
                    verticalArrangement = Arrangement.spacedBy(if (isCompactHeight) 8.dp else 10.dp),
                ) {
                    // Header
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "SKILL.md · $skillName",
                                fontSize = if (isCompactHeight) 14.sp else 15.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = WorkstationColors.TextPrimary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                text = "~/.gemini/config/skills/$skillName/SKILL.md",
                                fontFamily = FontFamily.Monospace,
                                fontSize = 10.sp,
                                color = WorkstationColors.TextMuted,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = statusBadgeBg,
                        ) {
                            Text(
                                text = statusBadgeText,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 9.sp,
                                fontWeight = FontWeight.Bold,
                                color = statusBadgeColor,
                                maxLines = 1,
                                softWrap = false,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp),
                            )
                        }
                    }

                    when {
                        isLoading -> {
                            Text(
                                text = "Cargando SKILL.md…",
                                fontFamily = FontFamily.Monospace,
                                fontSize = 12.sp,
                                color = WorkstationColors.AccentCyan,
                                modifier = Modifier.padding(vertical = 24.dp),
                            )
                        }
                        isLoadFailed -> {
                            Surface(
                                shape = RoundedCornerShape(10.dp),
                                color = WorkstationColors.SurfaceKeycap,
                                border = BorderStroke(1.dp, WorkstationColors.AccentRose.copy(alpha = 0.5f)),
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text(
                                    text = errorText ?: "No se pudo cargar SKILL.md de forma segura. Edítalo desde la terminal para evitar sobrescrituras.",
                                    fontSize = 12.sp,
                                    color = WorkstationColors.AccentRose,
                                    modifier = Modifier.padding(12.dp),
                                )
                            }
                        }
                        else -> {
                            val editorModifier = if (isCompactHeight) {
                                Modifier
                                    .fillMaxWidth()
                                    .heightIn(min = 96.dp, max = 180.dp)
                            } else {
                                Modifier
                                    .fillMaxWidth()
                                    .weight(1f, fill = false)
                                    .heightIn(min = 180.dp, max = 440.dp)
                            }

                            OutlinedTextField(
                                value = content,
                                onValueChange = {
                                    pendingDiscardAction = null
                                    onContentChange(it)
                                },
                                enabled = !isSaving,
                                isError = isOverLimit || !errorText.isNullOrBlank(),
                                textStyle = TextStyle(
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 12.sp,
                                    lineHeight = 17.sp,
                                    color = WorkstationColors.TextPrimary,
                                ),
                                placeholder = {
                                    Text(
                                        text = "---\nname: $skillName\ndescription: ...\n---\n",
                                        fontFamily = FontFamily.Monospace,
                                        fontSize = 11.sp,
                                        color = WorkstationColors.TextMuted,
                                    )
                                },
                                singleLine = false,
                                minLines = if (isCompactHeight) 4 else 10,
                                keyboardOptions = KeyboardOptions(autoCorrectEnabled = false),
                                modifier = editorModifier,
                            )

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    text = "Compartido por agy · opencode · claude",
                                    fontSize = 10.sp,
                                    color = WorkstationColors.TextSecondary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f),
                                )
                                Text(
                                    text = sizeLabel,
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 10.sp,
                                    color = if (isOverLimit) WorkstationColors.AccentRose else WorkstationColors.TextMuted,
                                    maxLines = 1,
                                )
                            }
                        }
                    }

                    if (!isLoadFailed && isOverLimit) {
                        Text(
                            text = "Excede el límite de 128 KB para edición segura en la app.",
                            fontSize = 11.sp,
                            color = WorkstationColors.AccentRose,
                        )
                    } else if (!isLoadFailed && !errorText.isNullOrBlank()) {
                        Text(
                            text = errorText,
                            fontSize = 11.sp,
                            color = WorkstationColors.AccentRose,
                        )
                    }

                    if (pendingDiscardAction != null) {
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = WorkstationColors.AccentAmberWash,
                            border = BorderStroke(1.dp, WorkstationColors.AccentAmber.copy(alpha = 0.55f)),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 10.dp, vertical = 7.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Text(
                                    text = "Tienes cambios sin guardar. ¿Descartarlos?",
                                    fontSize = 11.sp,
                                    color = WorkstationColors.AccentAmber,
                                    modifier = Modifier.weight(1f),
                                )
                                TextButton(
                                    onClick = { pendingDiscardAction = null },
                                    contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp),
                                ) {
                                    Text("Seguir", fontSize = 11.sp, color = WorkstationColors.TextPrimary)
                                }
                                TextButton(
                                    onClick = {
                                        val action = pendingDiscardAction
                                        pendingDiscardAction = null
                                        if (action == "diff") onOpenDiff() else onDismiss()
                                    },
                                    contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp),
                                ) {
                                    Text("Descartar", fontSize = 11.sp, color = WorkstationColors.AccentRose, fontWeight = FontWeight.SemiBold)
                                }
                            }
                        }
                    }

                    HorizontalDivider(thickness = 1.dp, color = WorkstationColors.BorderHairline)

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        OutlinedButton(
                            onClick = { requestCloseOrDiff("diff") },
                            enabled = !isLoading && !isSaving,
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp),
                            border = BorderStroke(1.dp, WorkstationColors.BorderHairline),
                        ) {
                            Text("Diff", fontSize = 11.sp, maxLines = 1, softWrap = false)
                        }
                        Spacer(modifier = Modifier.weight(1f))
                        TextButton(
                            onClick = { requestCloseOrDiff("dismiss") },
                            enabled = !isSaving,
                        ) {
                            Text("Cancelar", fontSize = 12.sp, color = WorkstationColors.TextSecondary, maxLines = 1)
                        }
                        Button(
                            onClick = onSave,
                            enabled = !isLoading && !isLoadFailed && !isSaving && !isOverLimit,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = WorkstationColors.AccentGold,
                                contentColor = WorkstationColors.VoidCanvas,
                            ),
                            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
                        ) {
                            Text(
                                text = if (isSaving) "Guardando…" else "Guardar",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun utf8ByteLength(text: CharSequence): Int {
    var count = 0
    var i = 0
    val len = text.length
    while (i < len) {
        val ch = text[i]
        when {
            ch.code < 0x80 -> {
                count += 1
                i += 1
            }
            ch.code < 0x800 -> {
                count += 2
                i += 1
            }
            Character.isHighSurrogate(ch) && i + 1 < len && Character.isLowSurrogate(text[i + 1]) -> {
                count += 4
                i += 2
            }
            else -> {
                count += 3
                i += 1
            }
        }
    }
    return count
}

