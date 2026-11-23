package com.termux.custom.ui

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.spring
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import com.termux.custom.agents.CodingAgentsState
import com.termux.custom.agents.CodingProvider
import com.termux.custom.data.AgentSessionRecord
import com.termux.custom.guardian.GuardianStatus
import com.termux.custom.security.OAuthTokenState
import com.termux.custom.skills.SkillRepoInfo
import com.termux.custom.skills.SkillsSummaryState
import com.termux.custom.storage.StorageMetrics
import com.termux.custom.updater.ConflictingAddonInfo
import com.termux.custom.updater.ReleaseInfo
import com.termux.custom.updater.UpdateState
import java.io.File

// ============================================================================================
// INSPECTOR PANELS (RUR AEROSPACE CARBON & GOLD EDITION)
// ============================================================================================
@Composable
internal fun AgentsWorkspacePanel(
    sessions: List<AgentSessionRecord>,
    siteId: String,
    isBusy: Boolean,
    codingAgentsState: CodingAgentsState,
    skillsState: SkillsSummaryState,
    onRefreshAgentsState: () -> Unit,
    onOpenCodingModelsDialog: () -> Unit,
    onOpenAddSkillRepoDialog: () -> Unit,
    onOpenSkillEditor: (SkillRepoInfo) -> Unit,
    onRunSkillsAction: (subcommand: String) -> Unit,
    onLaunchSafeCommand: (String) -> Unit,
    onNewContext: (title: String, agentType: String, summary: String) -> Unit,
    onExportDeltas: () -> Unit,
    onShareDeltas: () -> Unit,
    onImportDeltas: (json: String) -> Unit,
    onArchiveContext: (String) -> Unit,
) {
    var showNewWorkspaceDialog by rememberSaveable { mutableStateOf(false) }
    var showImportDeltasDialog by rememberSaveable { mutableStateOf(false) }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        // 1. Native Coding Agents Hub (Antigravity CLI + OpenCode v2 Bionic)
        item(key = "native_coding_agents_hub") {
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = WorkstationColors.SurfaceElevated,
                border = BorderStroke(1.dp, WorkstationColors.BorderStrong),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = "Native Coding Agents",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = WorkstationColors.TextPrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(
                            onClick = onRefreshAgentsState,
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                        ) {
                            Text(
                                text = "Refresh",
                                fontFamily = FontFamily.Monospace,
                                fontSize = 10.sp,
                                color = WorkstationColors.AccentGold,
                                maxLines = 1,
                            )
                        }
                    }

                    // Agent 1: Official Google Antigravity CLI (agy)
                    NativeAgentRow(
                        name = "Antigravity CLI (agy)",
                        detail = if (codingAgentsState.agyInstalled) {
                            "Native arm64 VA39 · Google Sign-In"
                        } else {
                            "Not installed · 1-tap arm64 setup"
                        },
                        installed = codingAgentsState.agyInstalled,
                        primaryLabel = if (codingAgentsState.agyInstalled) "Launch" else "Install",
                        onPrimaryClick = {
                            onLaunchSafeCommand(
                                if (codingAgentsState.agyInstalled) "agy" else "workstation setup && workstation doctor"
                            )
                        },
                        secondaryLabel = if (codingAgentsState.agyInstalled) "Update" else null,
                        onSecondaryClick = { onLaunchSafeCommand("agy update") },
                    )

                    // Agent 2: Native Bionic OpenCode v2 (Free $0 + Paid Code-Only Models)
                    val activeModelLabel = codingAgentsState.activePreset?.let {
                        "${it.displayName} (${it.tier.badge})"
                    } ?: codingAgentsState.activeOpenCodeModel ?: "Gratis \$0 (/models) · Code-Only"

                    NativeAgentRow(
                        name = "OpenCode v2 (opencode)",
                        detail = if (codingAgentsState.opencodeInstalled) {
                            activeModelLabel
                        } else {
                            "Bionic aarch64 · Modelos código gratis y pago"
                        },
                        installed = codingAgentsState.opencodeInstalled,
                        primaryLabel = if (codingAgentsState.opencodeInstalled) "Launch" else "Install",
                        onPrimaryClick = {
                            onLaunchSafeCommand(
                                if (codingAgentsState.opencodeInstalled) "opencode" else "workstation install-opencode"
                            )
                        },
                        secondaryLabel = "Models",
                        onSecondaryClick = onOpenCodingModelsDialog,
                    )
                }
            }
        }

        // 1b. Unified Git Skills & Repos Hub (shared by agy, opencode, claude)
        item(key = "unified_skills_repos_hub") {
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = WorkstationColors.SurfaceElevated,
                border = BorderStroke(
                    1.dp,
                    if (skillsState.modifiedCount > 0) WorkstationColors.AccentAmber.copy(alpha = 0.45f)
                    else WorkstationColors.BorderHairline,
                ),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = "Unified Skills & Repos",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = WorkstationColors.TextPrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = if (skillsState.modifiedCount > 0) WorkstationColors.AccentAmberWash
                            else if (skillsState.gitReposCount > 0) WorkstationColors.AccentEmeraldWash
                            else WorkstationColors.SurfaceKeycap,
                        ) {
                            Text(
                                text = when {
                                    skillsState.modifiedCount > 0 -> "${skillsState.modifiedCount} EDITADOS"
                                    skillsState.gitReposCount > 0 -> "${skillsState.totalCount} · ${skillsState.gitReposCount} GIT"
                                    else -> "${skillsState.totalCount} LOCALES"
                                },
                                fontFamily = FontFamily.Monospace,
                                fontSize = 9.sp,
                                fontWeight = FontWeight.Bold,
                                color = when {
                                    skillsState.modifiedCount > 0 -> WorkstationColors.AccentAmber
                                    skillsState.gitReposCount > 0 -> WorkstationColors.AccentEmerald
                                    else -> WorkstationColors.TextSecondary
                                },
                                maxLines = 1,
                                softWrap = false,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                            )
                        }
                    }

                    Text(
                        text = "~/.gemini/config/skills → agy · opencode · claude · Toca una skill para ver/editar SKILL.md",
                        fontSize = 10.sp,
                        color = WorkstationColors.TextSecondary,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )

                    if (skillsState.skills.isNotEmpty()) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            skillsState.skills.forEach { sk ->
                                val dotColor = when {
                                    sk.hasLocalEdits -> WorkstationColors.AccentAmber
                                    sk.isGitRepo || skillsState.isRootGitRepo -> WorkstationColors.AccentEmerald
                                    else -> WorkstationColors.AccentGold
                                }
                                Surface(
                                    shape = RoundedCornerShape(7.dp),
                                    color = WorkstationColors.SurfaceKeycap,
                                    border = BorderStroke(
                                        1.dp,
                                        if (sk.hasLocalEdits) WorkstationColors.AccentAmber.copy(alpha = 0.45f)
                                        else WorkstationColors.BorderHairline,
                                    ),
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(7.dp))
                                        .clickable { onOpenSkillEditor(sk) },
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(5.dp),
                                    ) {
                                        Box(
                                            modifier = Modifier
                                                .size(6.dp)
                                                .clip(CircleShape)
                                                .background(dotColor)
                                        )
                                        Text(
                                            text = sk.name,
                                            fontFamily = FontFamily.Monospace,
                                            fontSize = 10.sp,
                                            color = WorkstationColors.TextPrimary,
                                            maxLines = 1,
                                            softWrap = false,
                                        )
                                        if (sk.branch != null) {
                                            Text(
                                                text = sk.branch,
                                                fontFamily = FontFamily.Monospace,
                                                fontSize = 9.sp,
                                                color = WorkstationColors.TextMuted,
                                                maxLines = 1,
                                                softWrap = false,
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Button(
                            onClick = { onRunSkillsAction("update") },
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp),
                            modifier = Modifier.weight(1f),
                        ) {
                            Text(
                                text = "Update",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                softWrap = false,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        OutlinedButton(
                            onClick = onOpenAddSkillRepoDialog,
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp),
                            border = BorderStroke(1.dp, WorkstationColors.BorderStrong),
                            modifier = Modifier.weight(1f),
                        ) {
                            Text(
                                text = "+ Repo",
                                fontSize = 11.sp,
                                color = WorkstationColors.AccentGold,
                                maxLines = 1,
                                softWrap = false,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        OutlinedButton(
                            onClick = { onRunSkillsAction("diff") },
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp),
                            border = BorderStroke(1.dp, WorkstationColors.BorderHairline),
                            modifier = Modifier.weight(1f),
                        ) {
                            Text(
                                text = "Diff",
                                fontSize = 11.sp,
                                maxLines = 1,
                                softWrap = false,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        OutlinedButton(
                            onClick = { onRunSkillsAction("push") },
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp),
                            border = BorderStroke(
                                1.dp,
                                if (skillsState.modifiedCount > 0) WorkstationColors.AccentAmber.copy(alpha = 0.6f)
                                else WorkstationColors.BorderHairline,
                            ),
                            modifier = Modifier.weight(1f),
                        ) {
                            Text(
                                text = "Push",
                                fontSize = 11.sp,
                                color = if (skillsState.modifiedCount > 0) WorkstationColors.AccentAmber else WorkstationColors.TextPrimary,
                                maxLines = 1,
                                softWrap = false,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
        }

        // 2. Local-First CRDT Workspaces Card
        item(key = "local_workspaces_controls") {
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = WorkstationColors.SurfaceElevated,
                border = BorderStroke(1.dp, WorkstationColors.BorderHairline),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = "Local Workspaces",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = WorkstationColors.TextPrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            text = "P2P · $siteId",
                            fontFamily = FontFamily.Monospace,
                            fontSize = 10.sp,
                            color = WorkstationColors.AccentGold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }

                    Button(
                        onClick = { showNewWorkspaceDialog = true },
                        enabled = !isBusy,
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("New Workspace", fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        OutlinedButton(
                            onClick = onExportDeltas,
                            enabled = !isBusy,
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp),
                            border = BorderStroke(1.dp, WorkstationColors.BorderHairline),
                            modifier = Modifier.weight(1f),
                        ) {
                            Text("Export", fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        OutlinedButton(
                            onClick = onShareDeltas,
                            enabled = !isBusy,
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp),
                            border = BorderStroke(1.dp, WorkstationColors.BorderHairline),
                            modifier = Modifier.weight(1f),
                        ) {
                            Text("Share", fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        OutlinedButton(
                            onClick = { showImportDeltasDialog = true },
                            enabled = !isBusy,
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp),
                            border = BorderStroke(1.dp, WorkstationColors.BorderHairline),
                            modifier = Modifier.weight(1f),
                        ) {
                            Text("Import", fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        }

        // 3. Saved CRDT Sessions
        items(sessions, key = { it.id }) { session ->
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = WorkstationColors.SurfaceElevated,
                border = BorderStroke(1.dp, WorkstationColors.BorderHairline),
                modifier = Modifier
                    .fillMaxWidth()
                    .animateContentSize(spring(0.86f, 480f)),
            ) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = session.title,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium,
                            color = WorkstationColors.TextPrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = WorkstationColors.SurfaceInteractive,
                        ) {
                            Text(
                                text = "${session.agentType} · v${session.lamportVersion}",
                                fontFamily = FontFamily.Monospace,
                                fontSize = 10.sp,
                                color = WorkstationColors.AccentEmerald,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                            )
                        }
                    }
                    Text(
                        text = session.compactedSummary,
                        fontSize = 12.sp,
                        color = WorkstationColors.TextSecondary,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        OutlinedButton(
                            onClick = { onLaunchSafeCommand(session.toSafeLaunchCommand()) },
                            enabled = !isBusy,
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                            border = BorderStroke(1.dp, WorkstationColors.BorderStrong),
                            modifier = Modifier.weight(1f),
                        ) {
                            Text("Open in Terminal", fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        TextButton(
                            onClick = { onArchiveContext(session.id) },
                            enabled = !isBusy,
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                        ) {
                            Text("Archive", fontSize = 11.sp, color = WorkstationColors.AccentRose, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        }
    }

    if (showNewWorkspaceDialog) {
        NewWorkspaceDialog(
            onDismiss = { showNewWorkspaceDialog = false },
            onConfirm = { title, agentType, summary ->
                showNewWorkspaceDialog = false
                onNewContext(title, agentType, summary)
            },
        )
    }

    if (showImportDeltasDialog) {
        ImportDeltasDialog(
            onDismiss = { showImportDeltasDialog = false },
            onConfirm = { json ->
                showImportDeltasDialog = false
                onImportDeltas(json)
            },
        )
    }
}

@Composable
private fun NativeAgentRow(
    name: String,
    detail: String,
    installed: Boolean,
    primaryLabel: String,
    onPrimaryClick: () -> Unit,
    secondaryLabel: String? = null,
    onSecondaryClick: (() -> Unit)? = null,
) {
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = WorkstationColors.SurfaceKeycap,
        border = BorderStroke(
            1.dp,
            if (installed) WorkstationColors.AccentEmerald.copy(alpha = 0.28f)
            else WorkstationColors.BorderHairline,
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(if (installed) WorkstationColors.AccentEmerald else WorkstationColors.AccentAmber)
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = name,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = WorkstationColors.TextPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = detail,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 10.sp,
                        color = if (installed) WorkstationColors.AccentGold else WorkstationColors.TextSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = if (installed) WorkstationColors.AccentEmeraldWash else WorkstationColors.AccentAmberWash,
                ) {
                    Text(
                        text = if (installed) "READY" else "SETUP",
                        fontFamily = FontFamily.Monospace,
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (installed) WorkstationColors.AccentEmerald else WorkstationColors.AccentAmber,
                        maxLines = 1,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Button(
                    onClick = onPrimaryClick,
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                    modifier = Modifier.weight(1f),
                ) {
                    Text(primaryLabel, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                if (secondaryLabel != null && onSecondaryClick != null) {
                    OutlinedButton(
                        onClick = onSecondaryClick,
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                        border = BorderStroke(1.dp, WorkstationColors.BorderStrong),
                        modifier = Modifier.weight(1f),
                    ) {
                        Text(secondaryLabel, fontSize = 11.sp, color = WorkstationColors.AccentGold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}

@Composable
internal fun ShieldAndVaultPanel(
    shieldScore: Int,
    bootstrapReady: Boolean,
    guardianStatus: GuardianStatus,
    tokenState: OAuthTokenState,
    codingAgentsState: CodingAgentsState,
    installedReleaseTag: String,
    updateState: UpdateState,
    hasPrivateRepoToken: Boolean,
    conflictingAddons: List<ConflictingAddonInfo>,
    onUninstallAddon: (String) -> Unit,
    onCheckUpdates: () -> Unit,
    onDownloadUpdate: (ReleaseInfo) -> Unit,
    onInstallApk: (File) -> Unit,
    onOpenOtaTokenDialog: () -> Unit,
    onOpenReleasesBrowser: () -> Unit,
    onToggleWakeLock: () -> Unit,
    onRequestBatteryExclusion: () -> Unit,
    onOpenAutostart: () -> Unit,
    onCopyPhantomAdbFix: () -> Unit,
    onOpenTokenDialog: () -> Unit,
    onOpenCodingModelsDialog: () -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        // Workstation Shield Readiness Score Card
        item {
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = WorkstationColors.SurfaceElevated,
                border = BorderStroke(1.dp, WorkstationColors.BorderHairline),
                modifier = Modifier.fillMaxWidth(),
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
                        Text(
                            text = "Workstation Shield",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = WorkstationColors.TextPrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            text = "$shieldScore%",
                            fontFamily = FontFamily.Monospace,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (shieldScore >= 75) WorkstationColors.AccentEmerald else WorkstationColors.AccentAmber,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }

                    LinearProgressIndicator(
                        progress = { shieldScore / 100f },
                        color = if (shieldScore >= 75) WorkstationColors.AccentEmerald else WorkstationColors.AccentCyan,
                        trackColor = WorkstationColors.SurfaceInteractive,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(6.dp)
                            .clip(RoundedCornerShape(3.dp)),
                    )

                    ShieldChecklistRow("Native ARM64 Engine", bootstrapReady)
                    ShieldChecklistRow("Background Wake & Low-Latency Wi-Fi", guardianStatus.wakeLockHeld && guardianStatus.wifiLockHeld)
                    ShieldChecklistRow("Battery Optimization Bypassed", guardianStatus.batteryOptimizationIgnored)
                    ShieldChecklistRow("OAuth Credentials Vault (${tokenState.posixModeOctal})", tokenState.isPresent)
                    ShieldChecklistRow("Phantom Process Shield", guardianStatus.phantomShielded)
                }
            }
        }

        // In-App GitHub Releases OTA Auto-Updater Card
        item {
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = WorkstationColors.SurfaceElevated,
                border = BorderStroke(
                    1.dp,
                    when {
                        conflictingAddons.isNotEmpty() -> WorkstationColors.AccentAmber.copy(alpha = 0.6f)
                        updateState is UpdateState.UpdateAvailable || updateState is UpdateState.ReadyToInstall -> WorkstationColors.BorderStrong
                        else -> WorkstationColors.BorderHairline
                    },
                ),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = "Auto-Updates (GitHub OTA)",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = WorkstationColors.TextPrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            text = installedReleaseTag,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 10.sp,
                            color = WorkstationColors.AccentCyan,
                            maxLines = 1,
                        )
                    }

                    when (updateState) {
                        UpdateState.Idle -> {
                            Text(
                                text = "Auto-checks GitHub Releases on launch with SHA-256 verification.",
                                fontSize = 11.sp,
                                color = WorkstationColors.TextSecondary,
                            )
                        }
                        UpdateState.Checking -> {
                            Text(
                                text = "Checking GitHub Releases…",
                                fontFamily = FontFamily.Monospace,
                                fontSize = 11.sp,
                                color = WorkstationColors.AccentCyan,
                            )
                        }
                        is UpdateState.UpToDate -> {
                            Text(
                                text = "Up to date (${updateState.currentTag}).",
                                fontFamily = FontFamily.Monospace,
                                fontSize = 11.sp,
                                color = WorkstationColors.AccentEmerald,
                            )
                        }
                        is UpdateState.UpdateAvailable -> {
                            Text(
                                text = "New release ready: ${updateState.release.tagName} (${updateState.release.matchedAbi})",
                                fontFamily = FontFamily.Monospace,
                                fontSize = 11.sp,
                                color = WorkstationColors.AccentCyan,
                            )
                            Button(
                                onClick = { onDownloadUpdate(updateState.release) },
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text("Download & Verify ${updateState.release.tagName}", fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                        is UpdateState.Downloading -> {
                            Text(
                                text = "Downloading ${updateState.release.tagName}… ${updateState.progressPercent}%",
                                fontFamily = FontFamily.Monospace,
                                fontSize = 11.sp,
                                color = WorkstationColors.AccentCyan,
                            )
                            LinearProgressIndicator(
                                progress = { updateState.progressPercent / 100f },
                                color = WorkstationColors.AccentCyan,
                                trackColor = WorkstationColors.SurfaceInteractive,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(5.dp)
                                    .clip(RoundedCornerShape(3.dp)),
                            )
                        }
                        is UpdateState.ReadyToInstall -> {
                            Text(
                                text = "${updateState.release.tagName} ready · ${if (updateState.sha256Verified) "SHA-256 Verified" else "Downloaded"}",
                                fontFamily = FontFamily.Monospace,
                                fontSize = 11.sp,
                                color = WorkstationColors.AccentEmerald,
                            )
                            Button(
                                onClick = { onInstallApk(updateState.apkFile) },
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text("Install Update Now", fontSize = 12.sp, maxLines = 1)
                            }
                        }
                        is UpdateState.PrivateRepoAuthRequired -> {
                            Text(
                                text = "Private GitHub repo detected — set a read-only GitHub token or make repo public for automatic OTA.",
                                fontSize = 11.sp,
                                color = WorkstationColors.AccentAmber,
                            )
                        }
                        is UpdateState.Error -> {
                            Text(
                                text = updateState.message,
                                fontSize = 11.sp,
                                color = WorkstationColors.AccentRose,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }

                    if (conflictingAddons.isNotEmpty()) {
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = WorkstationColors.AccentAmberWash,
                            border = BorderStroke(1.dp, WorkstationColors.AccentAmber.copy(alpha = 0.5f)),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Column(
                                modifier = Modifier.padding(10.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                Text(
                                    text = "Plugins Termux con firma distinta detectados",
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = WorkstationColors.AccentAmber,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    text = "Comparten sharedUserId=\"com.termux\" y pueden bloquear actualizaciones con \"conflicto con un paquete\". Desinstálalos antes de actualizar:",
                                    fontSize = 10.sp,
                                    color = WorkstationColors.TextSecondary,
                                )
                                conflictingAddons.forEach { addon ->
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    ) {
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                text = addon.label,
                                                fontSize = 11.sp,
                                                fontWeight = FontWeight.Medium,
                                                color = WorkstationColors.TextPrimary,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                            )
                                            Text(
                                                text = addon.packageName,
                                                fontFamily = FontFamily.Monospace,
                                                fontSize = 9.sp,
                                                color = WorkstationColors.TextMuted,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                            )
                                        }
                                        OutlinedButton(
                                            onClick = { onUninstallAddon(addon.packageName) },
                                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                                            border = BorderStroke(1.dp, WorkstationColors.AccentAmber.copy(alpha = 0.65f)),
                                        ) {
                                            Text(
                                                text = "Desinstalar",
                                                fontSize = 10.sp,
                                                fontWeight = FontWeight.SemiBold,
                                                color = WorkstationColors.AccentAmber,
                                                maxLines = 1,
                                                softWrap = false,
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        OutlinedButton(
                            onClick = onCheckUpdates,
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp),
                            border = BorderStroke(1.dp, WorkstationColors.BorderHairline),
                            modifier = Modifier.weight(1f),
                        ) {
                            Text("Check Now", fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        OutlinedButton(
                            onClick = onOpenOtaTokenDialog,
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp),
                            border = BorderStroke(1.dp, WorkstationColors.BorderHairline),
                            modifier = Modifier.weight(1f),
                        ) {
                            Text(if (hasPrivateRepoToken) "PAT Set" else "Private PAT", fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        OutlinedButton(
                            onClick = onOpenReleasesBrowser,
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp),
                            border = BorderStroke(1.dp, WorkstationColors.BorderHairline),
                            modifier = Modifier.weight(1f),
                        ) {
                            Text("Releases", fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        }

        // Credentials Vault Card
        item {
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = WorkstationColors.SurfaceElevated,
                border = BorderStroke(1.dp, WorkstationColors.BorderHairline),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = "Agent Credentials Vault",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = WorkstationColors.TextPrimary,
                        maxLines = 1,
                    )
                    Text(
                        text = if (tokenState.isPresent) {
                            "${tokenState.maskedPreview} · Mode ${tokenState.posixModeOctal} · SHA ${tokenState.fingerprintSha256}"
                        } else {
                            "Share one OAuth identity securely with CLI agents (chmod 0600)."
                        },
                        fontFamily = if (tokenState.isPresent) FontFamily.Monospace else FontFamily.SansSerif,
                        fontSize = 11.sp,
                        color = if (tokenState.isPresent) WorkstationColors.AccentEmerald else WorkstationColors.TextSecondary,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Button(
                        onClick = onOpenTokenDialog,
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            text = if (tokenState.isPresent) "Rotate OAuth Token" else "Connect OAuth Token",
                            fontSize = 12.sp,
                            maxLines = 1,
                        )
                    }
                }
            }
        }

        // Coding Models & API Providers Vault Card (OpenRouter, Mistral, DeepSeek, Anthropic, OpenAI, Ollama LAN + Free $0)
        item {
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = WorkstationColors.SurfaceElevated,
                border = BorderStroke(1.dp, WorkstationColors.BorderHairline),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = "Coding Models Vault (0600)",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = WorkstationColors.TextPrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            text = "${codingAgentsState.configuredPaidOrLocalProvidersCount} API/LAN",
                            fontFamily = FontFamily.Monospace,
                            fontSize = 10.sp,
                            color = WorkstationColors.AccentGold,
                            maxLines = 1,
                        )
                    }
                    val activeModelSummary = codingAgentsState.activePreset?.let {
                        "${it.displayName} · ${it.tier.badge}"
                    } ?: codingAgentsState.activeOpenCodeModel ?: "Gratis \$0 (/models) · OpenCode v2"
                    Text(
                        text = activeModelSummary,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        color = WorkstationColors.AccentEmerald,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = "DeepSeek, Anthropic, OpenAI, OpenRouter, Mistral & Ollama LAN en 0600 ({file:...}, excluidos de snapshots).",
                        fontSize = 11.sp,
                        color = WorkstationColors.TextSecondary,
                    )
                    OutlinedButton(
                        onClick = onOpenCodingModelsDialog,
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                        border = BorderStroke(1.dp, WorkstationColors.BorderStrong),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            text = "Configure Coding Models & Keys",
                            fontSize = 12.sp,
                            color = WorkstationColors.AccentGold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }

        // OS Background Protection Controls
        item {
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = WorkstationColors.SurfaceElevated,
                border = BorderStroke(1.dp, WorkstationColors.BorderHairline),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = "Background Process Protection",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = WorkstationColors.TextPrimary,
                        maxLines = 1,
                    )
                    Text(
                        text = "Phantom process limit: ${guardianStatus.maxPhantomProcesses}",
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        color = WorkstationColors.TextSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Button(
                            onClick = onToggleWakeLock,
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp),
                            modifier = Modifier.weight(1f),
                        ) {
                            Text(
                                text = if (guardianStatus.wakeLockHeld && guardianStatus.wifiLockHeld) "Release Locks" else "Hold Locks",
                                fontSize = 11.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        OutlinedButton(
                            onClick = onRequestBatteryExclusion,
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp),
                            border = BorderStroke(1.dp, WorkstationColors.BorderHairline),
                            modifier = Modifier.weight(1f),
                        ) {
                            Text("No Battery Doze", fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        OutlinedButton(
                            onClick = onOpenAutostart,
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp),
                            border = BorderStroke(1.dp, WorkstationColors.BorderHairline),
                            modifier = Modifier.weight(1f),
                        ) {
                            Text(
                                text = if (guardianStatus.isHyperOsOrMiui) "HyperOS Autostart" else "App Autostart",
                                fontSize = 11.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        OutlinedButton(
                            onClick = onCopyPhantomAdbFix,
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp),
                            border = BorderStroke(1.dp, WorkstationColors.BorderHairline),
                            modifier = Modifier.weight(1f),
                        ) {
                            Text("Copy ADB Fix", fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ShieldChecklistRow(
    label: String,
    passed: Boolean,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(
            imageVector = WorkstationIcons.CheckCircle,
            contentDescription = null,
            tint = if (passed) WorkstationColors.AccentEmerald else WorkstationColors.TextMuted,
            modifier = Modifier.size(15.dp),
        )
        Text(
            text = label,
            fontSize = 12.sp,
            color = if (passed) WorkstationColors.TextPrimary else WorkstationColors.TextSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
internal fun StorageBackupsPanel(
    metrics: StorageMetrics,
    onRunCleanup: () -> Unit,
    onCreateBackup: () -> Unit,
    isBusy: Boolean,
    onInstallToolchain: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = WorkstationColors.SurfaceElevated,
        border = BorderStroke(1.dp, WorkstationColors.BorderHairline),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = "Storage & Portable Backups",
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                color = WorkstationColors.TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = "Free: ${metrics.freeFormatted} (${metrics.usedPercent}% used) · Backups: ${metrics.snapshotsCount} · zstd: ${if (metrics.zstdAvailable) "Active" else "Gzip fallback"}",
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
                color = if (metrics.zstdAvailable) WorkstationColors.AccentEmerald else WorkstationColors.AccentAmber,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (!metrics.zstdAvailable) {
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = WorkstationColors.AccentAmberWash,
                    border = BorderStroke(1.dp, WorkstationColors.AccentAmber.copy(alpha = 0.4f)),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(
                        modifier = Modifier.padding(10.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            text = "zstd not installed — snapshots fall back to gzip",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium,
                            color = WorkstationColors.AccentAmber,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        OutlinedButton(
                            onClick = onInstallToolchain,
                            enabled = !isBusy,
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                            border = BorderStroke(1.dp, WorkstationColors.AccentAmber.copy(alpha = 0.5f)),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(
                                text = "Install toolchain (workstation setup)",
                                fontSize = 11.sp,
                                color = WorkstationColors.AccentAmber,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
            Text(
                text = metrics.lastGcSummary,
                fontSize = 12.sp,
                color = WorkstationColors.TextSecondary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = "Snapshots strictly exclude OAuth tokens and are locked to 0600.",
                fontSize = 11.sp,
                color = WorkstationColors.TextMuted,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button(
                    onClick = onRunCleanup,
                    enabled = !isBusy,
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp),
                    modifier = Modifier.weight(1f),
                ) {
                    Text("Clean Caches", fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                OutlinedButton(
                    onClick = onCreateBackup,
                    enabled = !isBusy,
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp),
                    border = BorderStroke(1.dp, WorkstationColors.BorderStrong),
                    modifier = Modifier.weight(1f),
                ) {
                    Text("Create Snapshot", fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}
