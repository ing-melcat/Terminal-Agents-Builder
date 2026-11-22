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
import com.termux.custom.data.AgentSessionRecord
import com.termux.custom.guardian.GuardianStatus
import com.termux.custom.security.OAuthTokenState
import com.termux.custom.storage.StorageMetrics
import com.termux.custom.updater.ReleaseInfo
import com.termux.custom.updater.UpdateState
import java.io.File

// ============================================================================================
// INSPECTOR PANELS (COMMERCIAL BUYER-READY POLISH)
// ============================================================================================
@Composable
internal fun AgentsWorkspacePanel(
    sessions: List<AgentSessionRecord>,
    siteId: String,
    isBusy: Boolean,
    onLaunchSafeCommand: (String) -> Unit,
    onNewContext: (title: String, agentType: String, summary: String) -> Unit,
    onExportDeltas: () -> Unit,
    onShareDeltas: () -> Unit,
    onImportDeltas: (json: String) -> Unit,
    onArchiveContext: (String) -> Unit,
) {
    var showNewWorkspaceDialog by rememberSaveable { mutableStateOf(false) }
    var showImportDeltasDialog by rememberSaveable { mutableStateOf(false) }

    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        // Quick-Start Agent Launchers (1-tap presets)
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
                        color = WorkstationColors.AccentCyan,
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

        LazyColumn(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
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
internal fun ShieldAndVaultPanel(
    shieldScore: Int,
    bootstrapReady: Boolean,
    guardianStatus: GuardianStatus,
    tokenState: OAuthTokenState,
    installedReleaseTag: String,
    updateState: UpdateState,
    hasPrivateRepoToken: Boolean,
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
                    if (updateState is UpdateState.UpdateAvailable || updateState is UpdateState.ReadyToInstall) {
                        WorkstationColors.BorderStrong
                    } else {
                        WorkstationColors.BorderHairline
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
