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
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.termux.custom.security.OAuthTokenState

// ============================================================================================
// DIALOGS: SEARCHABLE COMMAND PALETTE & SECURE PASSWORD-MASKED VAULT DIALOG
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
    onCheckAppUpdate: () -> Unit,
    onOpenTutorial: () -> Unit,
) {
    var query by rememberSaveable { mutableStateOf("") }

    val actions = remember(isZenMode) {
        listOf(
            PaletteAction("agy", "Show native Antigravity workstation status & skills", "", "", { onExecuteShellCommand("agy") }),
            PaletteAction("agy setup", "Install native arm64 toolchain (git, node, zstd, zsh, tmux)", "", "", { onExecuteShellCommand("agy setup") }),
            PaletteAction("Agy Doctor (diagnose toolchain)", "Verify ARM64 prefix, binaries, and environment health", "", "doctor diagnose toolchain agy", { onExecuteShellCommand("agy doctor") }),
            PaletteAction("claude", "Launch Claude CLI interactive session", "", "", { onExecuteShellCommand("claude") }),
            PaletteAction("zellij", "Start Zellij terminal multiplexer", "", "", { onExecuteShellCommand("zellij") }),
            PaletteAction("New Terminal Session", "Spawn an isolated native PTY shell tab", "^⇧T", "", onNewPtySession),
            PaletteAction(if (isZenMode) "Exit Zen Mode" else "Enter Zen Mode", "Expand terminal canvas across full display", "^⇧B", "", onToggleZen),
            PaletteAction("Clean Caches (melcat-gc)", "Purge apt/pip/npm caches and zstd-compress old logs", "", "", { onExecuteShellCommand("melcat-gc") }),
            PaletteAction("Portable Snapshot", "Create secret-free tar.zst workspace archive", "", "", { onExecuteShellCommand("melcat-gc --snapshot") }),
            PaletteAction("OAuth Credentials Vault", "Provision or rotate 0600 OAuth token", "", "", onOpenTokenVault),
            PaletteAction("Check for App Update (OTA)", "Query GitHub Releases and verify SHA-256", "", "", onCheckAppUpdate),
            PaletteAction("Interactive Tutorial & First Steps", "Guided workstation onboarding & setup checklist", "", "tutorial help primeros pasos guide", onOpenTutorial),
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
    onGoogleOAuthSignIn: () -> Unit,
    isOAuthInProgress: Boolean,
    onSaveToken: (String) -> Unit,
    onRevokeToken: () -> Unit,
    errorText: String?,
) {
    var inputToken by rememberSaveable { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = WorkstationColors.SurfaceElevated,
        title = { Text("Google OAuth 2.0 & Vault (0600)", fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = "Authenticate with your Google account in 1 tap (same official OAuth 2.0 flow as 'agy login' / Gemini CLI). Credentials are locked with POSIX 0600 permissions.",
                    fontSize = 12.sp,
                    color = WorkstationColors.TextSecondary,
                )
                Button(
                    onClick = onGoogleOAuthSignIn,
                    enabled = !isOAuthInProgress,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = WorkstationColors.AccentCyan,
                        contentColor = WorkstationColors.VoidCanvas,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        text = if (isOAuthInProgress) "Waiting for Google Sign-In in browser…" else "Sign in with Google (OAuth 2.0)",
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                HorizontalDivider(thickness = 1.dp, color = WorkstationColors.BorderHairline)
                Text(
                    text = "Or paste an existing token / API key manually:",
                    fontSize = 11.sp,
                    color = WorkstationColors.TextMuted,
                )
                OutlinedTextField(
                    value = inputToken,
                    onValueChange = { inputToken = it },
                    label = { Text("OAuth Token / API Key", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    singleLine = true,
                    enabled = !isOAuthInProgress,
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
                enabled = !isOAuthInProgress && inputToken.trim().length >= 8,
            ) {
                Text("Lock 0600 & Sync", maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        },
        dismissButton = {
            Row {
                if (currentState.isPresent) {
                    TextButton(
                        onClick = onRevokeToken,
                        enabled = !isOAuthInProgress,
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
    val agentTypes = remember { listOf("agy", "claude", "zellij", "sh") }
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
                                    selectedContainerColor = WorkstationColors.AccentCyanWash,
                                    selectedLabelColor = WorkstationColors.AccentCyan,
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
