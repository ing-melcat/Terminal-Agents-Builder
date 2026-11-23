package com.termux.custom.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.exclude
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.termux.custom.bridge.TermuxViewSurfaceBridge
import com.termux.custom.guardian.GuardianStatus
import com.termux.custom.security.OAuthTokenState
import com.termux.custom.storage.StorageMetrics

private val TabletInspectorExpandedWidth = 360.dp

// ============================================================================================
// LAYOUT 1: PHONE PORTRAIT (360-599dp wide, vertical)
// ============================================================================================
@Composable
internal fun PhonePortraitLayout(
    activeSpace: WorkstationSpace,
    refreshHz: Int,
    liveSessions: List<LivePtyTab>,
    activeSessionIndex: Int,
    primaryTab: PrimaryTab,
    isImeVisible: Boolean,
    hasHardwareKeyboard: Boolean,
    surfaceBridge: TermuxViewSurfaceBridge,
    shieldScore: Int,
    tokenPresent: Boolean,
    freeStorage: String,
    statusNotice: String,
    onSelectSpace: (WorkstationSpace) -> Unit,
    onSelectSession: (Int) -> Unit,
    onAddSession: () -> Unit,
    onCloseSession: (Int) -> Unit,
    onOpenTutorial: () -> Unit,
    onSelectTab: (PrimaryTab) -> Unit,
    onOpenCommandPalette: () -> Unit,
    onOpenClassicTermux: () -> Unit,
    onQuickCommand: (String) -> Unit,
    renderInspector: @Composable (InspectorTab) -> Unit,
    renderRurPanel: @Composable () -> Unit,
) {
    val haptics = LocalHapticFeedback.current
    val isRosSpace = activeSpace == WorkstationSpace.ROS2_CONTROL
    val horizontalInsets = WindowInsets.navigationBars
        .union(WindowInsets.displayCutout)
        .only(WindowInsetsSides.Horizontal)
    val visibleTabs = if (isRosSpace) {
        listOf(PrimaryTab.TERMINAL, PrimaryTab.ROS_CONTROL, PrimaryTab.SSH, PrimaryTab.SHIELD)
    } else {
        listOf(PrimaryTab.TERMINAL, PrimaryTab.AGENTS, PrimaryTab.SSH, PrimaryTab.SHIELD, PrimaryTab.STORAGE)
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // Strict single-row 50dp header + statusBarsPadding inside full-bleed Surface
        Surface(
            color = WorkstationColors.SurfaceBase,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .windowInsetsPadding(
                        WindowInsets.statusBars
                            .union(WindowInsets.displayCutout)
                            .only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal)
                    ),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(50.dp)
                        .padding(horizontal = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    RurBrandBadge(size = 24.dp)
                    WorkspaceSpaceSwitcher(
                        activeSpace = activeSpace,
                        compact = true,
                        onSelectSpace = onSelectSpace,
                    )
                    Spacer(modifier = Modifier.weight(1f))
                    CompactStatusPill(
                        label = "${refreshHz}Hz",
                        healthy = liveSessions.isNotEmpty(),
                    )
                    CompactIconAction(
                        icon = WorkstationIcons.CommandSpark,
                        contentDescription = "Command Palette",
                        size = 32.dp,
                        onClick = {
                            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            onOpenCommandPalette()
                        },
                    )
                    CompactHelpKeycap(size = 32.dp, onClick = onOpenTutorial)
                    CompactIconAction(
                        icon = WorkstationIcons.ClassicShell,
                        contentDescription = "Classic Shell",
                        size = 32.dp,
                        onClick = {
                            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            onOpenClassicTermux()
                        },
                    )
                }
                HorizontalDivider(thickness = 1.dp, color = WorkstationColors.BorderHairline)
            }
        }

        // Main Workspace Area — ime + nav bottom insets only when NavigationBar is hidden
        val workspaceBottomInsets = if (isImeVisible) {
            WindowInsets.ime.union(WindowInsets.navigationBars).only(WindowInsetsSides.Bottom)
        } else {
            WindowInsets(0)
        }
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .windowInsetsPadding(workspaceBottomInsets),
        ) {
            // Base layer: Always-mounted Terminal viewport (zero reparenting crashes!)
            Column(modifier = Modifier.fillMaxSize()) {
                SessionTabStrip(
                    sessions = liveSessions,
                    activeIndex = activeSessionIndex,
                    compact = false,
                    onSelect = onSelectSession,
                    onAdd = onAddSession,
                    onCloseSession = onCloseSession,
                    emptyLabel = if (isRosSpace) "Toca + para Ubuntu ROS 2" else "Initializing runtime…",
                )
                TerminalCanvasHost(
                    surfaceBridge = surfaceBridge,
                    visible = primaryTab == PrimaryTab.TERMINAL,
                    contentInsets = horizontalInsets,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                )
                if (!hasHardwareKeyboard && primaryTab == PrimaryTab.TERMINAL) {
                    TactileExtraKeysDock(
                        surfaceBridge = surfaceBridge,
                        slim = false,
                        onQuickCommand = onQuickCommand,
                        contentInsets = horizontalInsets,
                        ros2Mode = isRosSpace,
                    )
                }
            }

            // Opaque full-bleed panel overlay for Agents / ROS Control / SSH / Shield / Storage tabs
            if (primaryTab != PrimaryTab.TERMINAL) {
                Surface(
                    color = WorkstationColors.VoidCanvas,
                    modifier = Modifier.fillMaxSize(),
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .windowInsetsPadding(horizontalInsets)
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                    ) {
                        if (primaryTab == PrimaryTab.ROS_CONTROL) {
                            renderRurPanel()
                        } else {
                            renderInspector(primaryTab.toInspectorTab())
                        }
                    }
                }
            }
        }

        // Auto-hide Telemetry + Bottom NavigationBar when IME opens -> +96dp typing room!
        AnimatedVisibility(
            visible = !isImeVisible,
            enter = expandVertically() + fadeIn(),
            exit = shrinkVertically() + fadeOut(),
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                CompactTelemetryBar(
                    leftText = if (isRosSpace) {
                        "ROS2:${liveSessions.size} · Shield:${shieldScore}% · $freeStorage"
                    } else {
                        "PTY:${liveSessions.size} · Shield:${shieldScore}% · ${if (tokenPresent) "Vault:ON" else "Vault:OFF"} · $freeStorage"
                    },
                    rightNotice = statusNotice,
                    compactPhone = true,
                    applyBottomInsets = false,
                )
                HorizontalDivider(thickness = 1.dp, color = WorkstationColors.BorderHairline)
                Surface(
                    color = WorkstationColors.SurfaceBase,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .windowInsetsPadding(
                                WindowInsets.navigationBars
                                    .union(WindowInsets.displayCutout)
                                    .only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal)
                            )
                            .height(58.dp)
                            .padding(horizontal = 4.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        visibleTabs.forEach { tab ->
                            val selected = primaryTab == tab
                            val accentWash = if (tab == PrimaryTab.ROS_CONTROL) WorkstationColors.AccentEmeraldWash else WorkstationColors.AccentCyanWash
                            val accentFg = if (tab == PrimaryTab.ROS_CONTROL) WorkstationColors.AccentEmerald else WorkstationColors.AccentCyan
                            Surface(
                                shape = RoundedCornerShape(10.dp),
                                color = if (selected) accentWash else androidx.compose.ui.graphics.Color.Transparent,
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxHeight()
                                    .clickable {
                                        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                        onSelectTab(tab)
                                    },
                            ) {
                                Column(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .padding(horizontal = 2.dp, vertical = 4.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.Center,
                                ) {
                                    Icon(
                                        imageVector = tabIcon(tab),
                                        contentDescription = tab.label,
                                        tint = if (selected) accentFg else WorkstationColors.TextSecondary,
                                        modifier = Modifier.size(19.dp),
                                    )
                                    Spacer(modifier = Modifier.height(2.dp))
                                    Text(
                                        text = tab.label,
                                        fontSize = 10.sp,
                                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                                        color = if (selected) accentFg else WorkstationColors.TextSecondary,
                                        maxLines = 1,
                                        softWrap = false,
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

// ============================================================================================
// LAYOUT 2: PHONE LANDSCAPE (height < 500dp, width > height — Left Scrollable Rail + 38dp Top)
// ============================================================================================
@Composable
internal fun PhoneLandscapeLayout(
    activeSpace: WorkstationSpace,
    refreshHz: Int,
    liveSessions: List<LivePtyTab>,
    activeSessionIndex: Int,
    primaryTab: PrimaryTab,
    hasHardwareKeyboard: Boolean,
    surfaceBridge: TermuxViewSurfaceBridge,
    onSelectSpace: (WorkstationSpace) -> Unit,
    onSelectSession: (Int) -> Unit,
    onAddSession: () -> Unit,
    onCloseSession: (Int) -> Unit,
    onOpenTutorial: () -> Unit,
    onSelectTab: (PrimaryTab) -> Unit,
    onOpenCommandPalette: () -> Unit,
    onOpenClassicTermux: () -> Unit,
    onQuickCommand: (String) -> Unit,
    renderInspector: @Composable (InspectorTab) -> Unit,
    renderRurPanel: @Composable () -> Unit,
) {
    val haptics = LocalHapticFeedback.current
    val isRosSpace = activeSpace == WorkstationSpace.ROS2_CONTROL
    val endInsets = WindowInsets.navigationBars
        .union(WindowInsets.displayCutout)
        .only(WindowInsetsSides.End)
    val workspaceBottomInsets = WindowInsets.ime
        .union(WindowInsets.navigationBars)
        .only(WindowInsetsSides.Bottom)
    val visibleTabs = if (isRosSpace) {
        listOf(PrimaryTab.TERMINAL, PrimaryTab.ROS_CONTROL, PrimaryTab.SSH, PrimaryTab.SHIELD)
    } else {
        listOf(PrimaryTab.TERMINAL, PrimaryTab.AGENTS, PrimaryTab.SSH, PrimaryTab.SHIELD, PrimaryTab.STORAGE)
    }

    Row(modifier = Modifier.fillMaxSize()) {
        // Slim scrollable 52dp Left Icon Rail (Space toggle + active space tabs)
        Surface(
            color = WorkstationColors.SurfaceBase,
            modifier = Modifier.fillMaxHeight(),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxHeight()
                    .windowInsetsPadding(
                        WindowInsets.statusBars
                            .union(WindowInsets.navigationBars)
                            .union(WindowInsets.displayCutout)
                            .only(WindowInsetsSides.Start + WindowInsetsSides.Vertical)
                    )
                    .width(52.dp)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 4.dp, vertical = 6.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = if (isRosSpace) WorkstationColors.AccentEmeraldWash else WorkstationColors.AccentGoldWash,
                    border = androidx.compose.foundation.BorderStroke(
                        1.dp,
                        if (isRosSpace) WorkstationColors.AccentEmerald.copy(alpha = 0.55f) else WorkstationColors.BorderStrong,
                    ),
                    modifier = Modifier
                        .size(width = 44.dp, height = 30.dp)
                        .clickable {
                            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            onSelectSpace(
                                if (isRosSpace) WorkstationSpace.DEV_AI else WorkstationSpace.ROS2_CONTROL
                            )
                        },
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text(
                            text = if (isRosSpace) "ROS" else "DEV",
                            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (isRosSpace) WorkstationColors.AccentEmerald else WorkstationColors.AccentGold,
                            maxLines = 1,
                            softWrap = false,
                        )
                    }
                }
                HorizontalDivider(
                    thickness = 1.dp,
                    color = WorkstationColors.BorderHairline,
                    modifier = Modifier.padding(vertical = 2.dp),
                )
                visibleTabs.forEach { tab ->
                    val selected = primaryTab == tab
                    val accentWash = if (tab == PrimaryTab.ROS_CONTROL) WorkstationColors.AccentEmeraldWash else WorkstationColors.AccentCyanWash
                    val accentFg = if (tab == PrimaryTab.ROS_CONTROL) WorkstationColors.AccentEmerald else WorkstationColors.AccentCyan
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = if (selected) accentWash else androidx.compose.ui.graphics.Color.Transparent,
                        modifier = Modifier
                            .size(44.dp)
                            .clickable {
                                haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                onSelectTab(tab)
                            },
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = tabIcon(tab),
                                contentDescription = tab.label,
                                tint = if (selected) accentFg else WorkstationColors.TextSecondary,
                                modifier = Modifier.size(20.dp),
                            )
                        }
                    }
                }
            }
        }

        VerticalDivider(thickness = 1.dp, color = WorkstationColors.BorderHairline)

        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight(),
        ) {
            // Merged 38dp Landscape Strip (Session Tabs + Actions in ONE ultra-compact strip!)
            Surface(
                color = WorkstationColors.SurfaceBase,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .windowInsetsPadding(
                            WindowInsets.statusBars
                                .union(WindowInsets.navigationBars)
                                .union(WindowInsets.displayCutout)
                                .only(WindowInsetsSides.Top + WindowInsetsSides.End)
                        )
                        .height(38.dp)
                        .padding(horizontal = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    SessionTabStripRow(
                        sessions = liveSessions,
                        activeIndex = activeSessionIndex,
                        compact = true,
                        onSelect = {
                            onSelectSession(it)
                            onSelectTab(PrimaryTab.TERMINAL)
                        },
                        onAdd = onAddSession,
                        onCloseSession = onCloseSession,
                        emptyLabel = if (isRosSpace) "Toca + para Ubuntu ROS 2" else "Initializing runtime…",
                        modifier = Modifier.weight(1f),
                    )
                    CompactStatusPill(label = "${refreshHz}Hz", healthy = liveSessions.isNotEmpty())
                    CompactIconAction(
                        icon = WorkstationIcons.CommandSpark,
                        contentDescription = "Commands",
                        size = 30.dp,
                        onClick = onOpenCommandPalette,
                    )
                    CompactHelpKeycap(
                        size = 30.dp,
                        onClick = onOpenTutorial,
                    )
                    CompactIconAction(
                        icon = WorkstationIcons.ClassicShell,
                        contentDescription = "Classic Shell",
                        size = 30.dp,
                        onClick = onOpenClassicTermux,
                    )
                }
            }
            HorizontalDivider(thickness = 1.dp, color = WorkstationColors.BorderHairline)

            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .windowInsetsPadding(workspaceBottomInsets),
            ) {
                Column(modifier = Modifier.fillMaxSize()) {
                    TerminalCanvasHost(
                        surfaceBridge = surfaceBridge,
                        visible = primaryTab == PrimaryTab.TERMINAL,
                        contentInsets = endInsets,
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth(),
                    )
                    if (!hasHardwareKeyboard && primaryTab == PrimaryTab.TERMINAL) {
                        TactileExtraKeysDock(
                            surfaceBridge = surfaceBridge,
                            slim = true,
                            onQuickCommand = onQuickCommand,
                            contentInsets = endInsets,
                            ros2Mode = isRosSpace,
                        )
                    }
                }

                if (primaryTab != PrimaryTab.TERMINAL) {
                    Surface(
                        color = WorkstationColors.VoidCanvas,
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .windowInsetsPadding(endInsets)
                                .padding(horizontal = 12.dp, vertical = 6.dp),
                        ) {
                            if (primaryTab == PrimaryTab.ROS_CONTROL) {
                                renderRurPanel()
                            } else {
                                renderInspector(primaryTab.toInspectorTab())
                            }
                        }
                    }
                }
            }
        }
    }
}

// ============================================================================================
// LAYOUT 3: TABLET PORTRAIT (600-839dp — Full-width Terminal + Toggleable Inspector Sheet)
// ============================================================================================
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TabletPortraitLayout(
    activeSpace: WorkstationSpace,
    refreshHz: Int,
    hasHardwareKeyboard: Boolean,
    liveSessions: List<LivePtyTab>,
    activeSessionIndex: Int,
    inspectorTab: InspectorTab,
    ros2InspectorShowSsh: Boolean,
    isSheetOpen: Boolean,
    surfaceBridge: TermuxViewSurfaceBridge,
    crdtSiteId: String,
    tokenState: OAuthTokenState,
    guardianStatus: GuardianStatus,
    storageMetrics: StorageMetrics,
    statusNotice: String,
    onSelectSpace: (WorkstationSpace) -> Unit,
    onSelectSession: (Int) -> Unit,
    onAddSession: () -> Unit,
    onCloseSession: (Int) -> Unit,
    onOpenTutorial: () -> Unit,
    onToggleSheet: () -> Unit,
    onSelectInspectorTab: (InspectorTab) -> Unit,
    onSelectRos2InspectorShowSsh: (Boolean) -> Unit,
    onOpenCommandPalette: () -> Unit,
    onOpenClassicTermux: () -> Unit,
    onQuickCommand: (String) -> Unit,
    renderInspector: @Composable (InspectorTab) -> Unit,
    renderRurPanel: @Composable () -> Unit,
) {
    val isRosSpace = activeSpace == WorkstationSpace.ROS2_CONTROL
    val horizontalInsets = WindowInsets.navigationBars
        .union(WindowInsets.displayCutout)
        .only(WindowInsetsSides.Horizontal)

    Column(modifier = Modifier.fillMaxSize()) {
        Surface(color = WorkstationColors.SurfaceBase, modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .windowInsetsPadding(
                        WindowInsets.statusBars
                            .union(WindowInsets.displayCutout)
                            .only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal)
                    ),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(50.dp)
                        .padding(horizontal = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    RurBrandBadge(size = 26.dp)
                    WorkspaceSpaceSwitcher(
                        activeSpace = activeSpace,
                        compact = false,
                        onSelectSpace = onSelectSpace,
                    )
                    Spacer(modifier = Modifier.weight(1f))
                    CompactStatusPill(
                        label = "${refreshHz}Hz · ${if (hasHardwareKeyboard) "KB" else "Touch"}",
                        healthy = liveSessions.isNotEmpty(),
                    )
                    CompactKeycapButton(label = "⌘K", onClick = onOpenCommandPalette)
                    CompactHelpKeycap(onClick = onOpenTutorial)
                    CompactIconAction(
                        icon = WorkstationIcons.PanelZen,
                        contentDescription = "Inspector",
                        highlighted = isSheetOpen,
                        onClick = onToggleSheet,
                    )
                    CompactIconAction(
                        icon = WorkstationIcons.ClassicShell,
                        contentDescription = "Classic Shell",
                        onClick = onOpenClassicTermux,
                    )
                }
                HorizontalDivider(thickness = 1.dp, color = WorkstationColors.BorderHairline)
            }
        }

        SessionTabStrip(
            sessions = liveSessions,
            activeIndex = activeSessionIndex,
            compact = false,
            onSelect = onSelectSession,
            onAdd = onAddSession,
            onCloseSession = onCloseSession,
            emptyLabel = if (isRosSpace) "Toca + para Ubuntu ROS 2" else "Initializing runtime…",
        )

        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .windowInsetsPadding(
                    WindowInsets.ime
                        .exclude(WindowInsets.navigationBars)
                        .only(WindowInsetsSides.Bottom)
                ),
        ) {
            TerminalCanvasHost(
                surfaceBridge = surfaceBridge,
                visible = true,
                contentInsets = horizontalInsets,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
            )
            if (!hasHardwareKeyboard) {
                TactileExtraKeysDock(
                    surfaceBridge = surfaceBridge,
                    slim = false,
                    onQuickCommand = onQuickCommand,
                    contentInsets = horizontalInsets,
                    ros2Mode = isRosSpace,
                )
            }
        }

        CompactTelemetryBar(
            leftText = if (isRosSpace) {
                "ROS2:${liveSessions.size} · Ubuntu 24.04 Jazzy · Wake:${if (guardianStatus.wakeLockHeld) "ON" else "OFF"} · Free:${storageMetrics.freeFormatted}"
            } else {
                "PTY:${liveSessions.size} · Node:$crdtSiteId · Vault:${if (tokenState.isPresent) tokenState.posixModeOctal else "OFF"} · Wake:${if (guardianStatus.wakeLockHeld) "ON" else "OFF"} · Free:${storageMetrics.freeFormatted}"
            },
            rightNotice = statusNotice,
            compactPhone = false,
            applyBottomInsets = true,
        )
    }

    if (isSheetOpen) {
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = false)
        ModalBottomSheet(
            onDismissRequest = onToggleSheet,
            sheetState = sheetState,
            containerColor = WorkstationColors.SurfaceElevated,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 340.dp, max = 580.dp)
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (isRosSpace) {
                    Ros2InspectorHeader(
                        showSshPi = ros2InspectorShowSsh,
                        onSelectShowSshPi = onSelectRos2InspectorShowSsh,
                    )
                    Box(modifier = Modifier.weight(1f)) {
                        if (ros2InspectorShowSsh) {
                            renderInspector(InspectorTab.SSH)
                        } else {
                            renderRurPanel()
                        }
                    }
                } else {
                    InspectorSegmentedHeader(
                        selected = inspectorTab,
                        onSelect = onSelectInspectorTab,
                    )
                    Box(modifier = Modifier.weight(1f)) {
                        renderInspector(inspectorTab)
                    }
                }
            }
        }
    }
}

// ============================================================================================
// LAYOUT 4: TABLET LANDSCAPE (width >= 840dp — Xiaomi Pad 6 120Hz Desktop + Zen Mode ^⇧B)
// ============================================================================================
@Composable
internal fun TabletLandscapeLayout(
    activeSpace: WorkstationSpace,
    refreshHz: Int,
    hasHardwareKeyboard: Boolean,
    liveSessions: List<LivePtyTab>,
    activeSessionIndex: Int,
    inspectorTab: InspectorTab,
    ros2InspectorShowSsh: Boolean,
    isZenMode: Boolean,
    surfaceBridge: TermuxViewSurfaceBridge,
    crdtSiteId: String,
    tokenState: OAuthTokenState,
    guardianStatus: GuardianStatus,
    storageMetrics: StorageMetrics,
    statusNotice: String,
    onSelectSpace: (WorkstationSpace) -> Unit,
    onSelectSession: (Int) -> Unit,
    onAddSession: () -> Unit,
    onCloseSession: (Int) -> Unit,
    onOpenTutorial: () -> Unit,
    onSelectInspectorTab: (InspectorTab) -> Unit,
    onSelectRos2InspectorShowSsh: (Boolean) -> Unit,
    onToggleZenMode: () -> Unit,
    onOpenCommandPalette: () -> Unit,
    onOpenClassicTermux: () -> Unit,
    onQuickCommand: (String) -> Unit,
    renderInspector: @Composable (InspectorTab) -> Unit,
    renderRurPanel: @Composable () -> Unit,
) {
    val isRosSpace = activeSpace == WorkstationSpace.ROS2_CONTROL
    val inspectorWidth by animateDpAsState(
        targetValue = if (isZenMode) 0.dp else TabletInspectorExpandedWidth,
        animationSpec = MotionTokens.PaneWidthSpring,
        label = "ZenInspectorWidth",
    )
    val startInsets = WindowInsets.navigationBars
        .union(WindowInsets.displayCutout)
        .only(WindowInsetsSides.Start)
    val endInsets = WindowInsets.navigationBars
        .union(WindowInsets.displayCutout)
        .only(WindowInsetsSides.End)

    Column(modifier = Modifier.fillMaxSize()) {
        // Unified 48dp Desktop Titlebar (Brand + Space Switcher + Live Session Tabs + Actions)
        Surface(color = WorkstationColors.SurfaceBase, modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .windowInsetsPadding(
                        WindowInsets.statusBars
                            .union(WindowInsets.displayCutout)
                            .only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal)
                    ),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                        .padding(horizontal = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    RurBrandBadge(size = 26.dp)
                    WorkspaceSpaceSwitcher(
                        activeSpace = activeSpace,
                        compact = false,
                        onSelectSpace = onSelectSpace,
                    )
                    VerticalDivider(
                        thickness = 1.dp,
                        color = WorkstationColors.BorderHairline,
                        modifier = Modifier.height(20.dp),
                    )
                    SessionTabStripRow(
                        sessions = liveSessions,
                        activeIndex = activeSessionIndex,
                        compact = false,
                        onSelect = onSelectSession,
                        onAdd = onAddSession,
                        onCloseSession = onCloseSession,
                        emptyLabel = if (isRosSpace) "Toca + para Ubuntu ROS 2" else "Initializing runtime…",
                        modifier = Modifier.weight(1f),
                    )
                    CompactStatusPill(
                        label = "${refreshHz}Hz · ${if (hasHardwareKeyboard) "HW-KB" else "Desktop"}",
                        healthy = liveSessions.isNotEmpty(),
                    )
                    CompactKeycapButton(
                        label = if (hasHardwareKeyboard) "^⇧P" else "⌘K",
                        onClick = onOpenCommandPalette,
                    )
                    CompactHelpKeycap(onClick = onOpenTutorial)
                    CompactIconAction(
                        icon = WorkstationIcons.PanelZen,
                        contentDescription = "Toggle Zen Mode (Ctrl+Shift+B)",
                        highlighted = !isZenMode,
                        onClick = onToggleZenMode,
                    )
                    CompactIconAction(
                        icon = WorkstationIcons.ClassicShell,
                        contentDescription = "Classic Shell",
                        onClick = onOpenClassicTermux,
                    )
                }
                HorizontalDivider(thickness = 1.dp, color = WorkstationColors.BorderHairline)
            }
        }

        // Split-Pane Desktop Canvas
        Row(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
        ) {
            // Left Primary Terminal Canvas (expands to 100% smoothly in Zen Mode; IME inset excludes nav bar owned by CompactTelemetryBar)
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .windowInsetsPadding(
                        WindowInsets.ime
                            .exclude(WindowInsets.navigationBars)
                            .only(WindowInsetsSides.Bottom)
                    ),
            ) {
                TerminalCanvasHost(
                    surfaceBridge = surfaceBridge,
                    visible = true,
                    contentInsets = startInsets,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                )
                if (!hasHardwareKeyboard) {
                    TactileExtraKeysDock(
                        surfaceBridge = surfaceBridge,
                        slim = false,
                        onQuickCommand = onQuickCommand,
                        contentInsets = startInsets,
                        ros2Mode = isRosSpace,
                    )
                }
            }

            // Right Collapsible Inspector Panel (360dp <-> 0dp)
            if (inspectorWidth > 2.dp) {
                VerticalDivider(thickness = 1.dp, color = WorkstationColors.BorderHairline)
                Box(
                    modifier = Modifier
                        .width(inspectorWidth)
                        .fillMaxHeight()
                        .background(WorkstationColors.SurfaceBase)
                        .clipToBounds(),
                ) {
                    Column(
                        modifier = Modifier
                            .requiredWidth(TabletInspectorExpandedWidth)
                            .fillMaxHeight()
                            .windowInsetsPadding(endInsets)
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        if (isRosSpace) {
                            Ros2InspectorHeader(
                                showSshPi = ros2InspectorShowSsh,
                                onSelectShowSshPi = onSelectRos2InspectorShowSsh,
                            )
                            Box(modifier = Modifier.weight(1f)) {
                                if (ros2InspectorShowSsh) {
                                    renderInspector(InspectorTab.SSH)
                                } else {
                                    renderRurPanel()
                                }
                            }
                        } else {
                            InspectorSegmentedHeader(
                                selected = inspectorTab,
                                onSelect = onSelectInspectorTab,
                            )
                            Box(modifier = Modifier.weight(1f)) {
                                renderInspector(inspectorTab)
                            }
                        }
                    }
                }
            }
        }

        CompactTelemetryBar(
            leftText = if (isRosSpace) {
                "ROS2:${liveSessions.size} · Ubuntu 24.04 Jazzy · Wake:${if (guardianStatus.wakeLockHeld) "ON" else "OFF"} · Free:${storageMetrics.freeFormatted}"
            } else {
                "PTY:${liveSessions.size} · Node:$crdtSiteId · Vault:${if (tokenState.isPresent) tokenState.posixModeOctal else "OFF"} · Wake:${if (guardianStatus.wakeLockHeld) "ON" else "OFF"} · Free:${storageMetrics.freeFormatted}"
            },
            rightNotice = statusNotice,
            compactPhone = false,
            applyBottomInsets = true,
        )
    }
}

private fun PrimaryTab.toInspectorTab(): InspectorTab = when (this) {
    PrimaryTab.AGENTS -> InspectorTab.AGENTS
    PrimaryTab.SSH -> InspectorTab.SSH
    PrimaryTab.SHIELD -> InspectorTab.SHIELD
    PrimaryTab.STORAGE -> InspectorTab.STORAGE
    PrimaryTab.TERMINAL,
    PrimaryTab.ROS_CONTROL -> InspectorTab.AGENTS
}
