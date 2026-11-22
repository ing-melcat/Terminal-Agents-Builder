package com.termux.custom.ui

import android.view.View
import android.view.ViewGroup
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.termux.custom.bridge.TermuxViewSurfaceBridge
import com.termux.custom.updater.ReleaseInfo
import com.termux.custom.updater.UpdateState
import java.io.File

internal data class LivePtyTab(
    val index: Int,
    val title: String,
    val isRunning: Boolean,
)

// ============================================================================================
// SHARED PREMIUM COMPONENTS
// ============================================================================================
@Composable
internal fun TerminalCanvasHost(
    surfaceBridge: TermuxViewSurfaceBridge,
    visible: Boolean,
    modifier: Modifier = Modifier,
    contentInsets: WindowInsets = WindowInsets(0),
) {
    Box(
        modifier = modifier
            .background(Color.Black)
            .clipToBounds(),
    ) {
        AndroidView(
            factory = { ctx ->
                android.widget.FrameLayout(ctx).apply {
                    val view = surfaceBridge.terminalView
                    (view.parent as? ViewGroup)?.removeView(view)
                    view.visibility = if (visible) View.VISIBLE else View.INVISIBLE
                    addView(
                        view,
                        android.widget.FrameLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT,
                        ),
                    )
                    view.post {
                        view.updateSize()
                        view.onScreenUpdated()
                        if (visible && !view.hasFocus()) {
                            view.requestFocus()
                        }
                    }
                }
            },
            update = { container ->
                val view = surfaceBridge.terminalView
                if (view.parent !== container) {
                    (view.parent as? ViewGroup)?.removeView(view)
                    container.addView(
                        view,
                        android.widget.FrameLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT,
                        ),
                    )
                }
                val wasInvisible = view.visibility != View.VISIBLE
                view.visibility = if (visible) View.VISIBLE else View.INVISIBLE
                if (visible) {
                    if (wasInvisible) {
                        view.post {
                            view.requestFocus()
                            view.updateSize()
                            view.onScreenUpdated()
                        }
                    } else {
                        view.onScreenUpdated()
                    }
                } else if (view.hasFocus()) {
                    view.clearFocus()
                }
            },
            onRelease = { container ->
                // Only remove from THIS container; never detach if already moved to a new layout slot.
                val view = surfaceBridge.terminalView
                if (view.parent === container) {
                    container.removeView(view)
                }
            },
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(contentInsets)
                .padding(horizontal = 6.dp, vertical = 4.dp),
        )
    }
}

@Composable
internal fun SessionTabStrip(
    sessions: List<LivePtyTab>,
    activeIndex: Int,
    compact: Boolean,
    onSelect: (Int) -> Unit,
    onAdd: () -> Unit,
    onCloseSession: (Int) -> Unit,
) {
    Surface(
        color = WorkstationColors.SurfaceBase,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column {
            SessionTabStripRow(
                sessions = sessions,
                activeIndex = activeIndex,
                compact = compact,
                onSelect = onSelect,
                onAdd = onAdd,
                onCloseSession = onCloseSession,
                modifier = Modifier
                    .fillMaxWidth()
                    .windowInsetsPadding(
                        WindowInsets.navigationBars
                            .union(WindowInsets.displayCutout)
                            .only(WindowInsetsSides.Horizontal)
                    )
                    .height(if (compact) 32.dp else 36.dp)
                    .padding(horizontal = 10.dp),
            )
            HorizontalDivider(thickness = 1.dp, color = WorkstationColors.BorderHairline)
        }
    }
}

@Composable
internal fun SessionTabStripRow(
    sessions: List<LivePtyTab>,
    activeIndex: Int,
    compact: Boolean,
    onSelect: (Int) -> Unit,
    onAdd: () -> Unit,
    onCloseSession: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val haptics = LocalHapticFeedback.current
    Row(
        modifier = modifier.horizontalScroll(rememberScrollState()),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (sessions.isEmpty()) {
            Surface(
                shape = RoundedCornerShape(7.dp),
                color = WorkstationColors.SurfaceInteractive,
                border = BorderStroke(1.dp, WorkstationColors.BorderHairline),
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Box(
                        modifier = Modifier
                            .size(6.dp)
                            .clip(CircleShape)
                            .background(WorkstationColors.AccentAmber)
                    )
                    Text(
                        text = "Initializing runtime…",
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        color = WorkstationColors.TextSecondary,
                        maxLines = 1,
                        softWrap = false,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        } else {
            sessions.forEach { tab ->
                val isSelected = tab.index == activeIndex
                val isExited = !tab.isRunning
                Surface(
                    shape = RoundedCornerShape(7.dp),
                    color = if (isSelected) WorkstationColors.SurfaceInteractive else Color.Transparent,
                    border = if (isSelected) BorderStroke(1.dp, WorkstationColors.BorderStrong) else BorderStroke(1.dp, WorkstationColors.BorderHairline),
                    modifier = Modifier
                        .height(if (compact) 26.dp else 28.dp)
                        .clickable {
                            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            onSelect(tab.index)
                        },
                ) {
                    Row(
                        modifier = Modifier.padding(start = 9.dp, end = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(5.dp),
                    ) {
                        Box(
                            modifier = Modifier
                                .size(6.dp)
                                .clip(CircleShape)
                                .background(
                                    when {
                                        isSelected && tab.isRunning -> WorkstationColors.AccentEmerald
                                        tab.isRunning -> WorkstationColors.AccentCyan
                                        else -> WorkstationColors.TextMuted
                                    }
                                )
                        )
                        Text(
                            text = tab.title,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp,
                            color = if (isSelected) WorkstationColors.TextPrimary else WorkstationColors.TextSecondary,
                            maxLines = 1,
                            softWrap = false,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.widthIn(max = 124.dp),
                        )
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier
                                .size(24.dp)
                                .clip(RoundedCornerShape(5.dp))
                                .semantics { contentDescription = "Close ${tab.title}" }
                                .clickable {
                                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                    onCloseSession(tab.index)
                                },
                        ) {
                            Box(
                                contentAlignment = Alignment.Center,
                                modifier = Modifier
                                    .size(18.dp)
                                    .clip(RoundedCornerShape(4.dp))
                                    .background(if (isExited) WorkstationColors.AccentRoseWash else Color.Transparent),
                            ) {
                                Text(
                                    text = "×",
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 13.sp,
                                    fontWeight = if (isExited) FontWeight.Bold else FontWeight.Medium,
                                    color = when {
                                        isExited -> WorkstationColors.AccentRose
                                        isSelected -> WorkstationColors.TextSecondary
                                        else -> WorkstationColors.TextMuted
                                    },
                                    maxLines = 1,
                                    softWrap = false,
                                )
                            }
                        }
                    }
                }
            }
        }

        Surface(
            shape = RoundedCornerShape(7.dp),
            color = WorkstationColors.SurfaceKeycap,
            border = BorderStroke(1.dp, WorkstationColors.BorderHairline),
            modifier = Modifier
                .size(if (compact) 26.dp else 28.dp)
                .clickable {
                    haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    onAdd()
                },
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = WorkstationIcons.Plus,
                    contentDescription = "New PTY Session",
                    tint = WorkstationColors.AccentCyan,
                    modifier = Modifier.size(15.dp),
                )
            }
        }
    }
}

@Composable
internal fun TactileExtraKeysDock(
    surfaceBridge: TermuxViewSurfaceBridge,
    slim: Boolean,
    onQuickCommand: (String) -> Unit,
    contentInsets: WindowInsets = WindowInsets(0),
) {
    val haptics = LocalHapticFeedback.current
    val keyHeight = if (slim) 27.dp else 32.dp
    val dockBgColor = WorkstationColors.SurfaceBase

    Surface(
        color = dockBgColor,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column {
            HorizontalDivider(thickness = 1.dp, color = WorkstationColors.BorderHairline)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .windowInsetsPadding(contentInsets)
                    .padding(horizontal = 6.dp, vertical = if (slim) 4.dp else 5.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(modifier = Modifier.weight(1f)) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(5.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        TactileKeycap("ESC", height = keyHeight) {
                            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            surfaceBridge.sendEscapeSequence("\u001b")
                        }
                        TactileKeycap("TAB", height = keyHeight) {
                            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            surfaceBridge.sendTextToPty("\t")
                        }
                        TactileKeycap(
                            label = "CTRL",
                            height = keyHeight,
                            armed = surfaceBridge.stickyCtrl,
                        ) {
                            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            surfaceBridge.toggleStickyCtrl()
                        }
                        TactileKeycap(
                            label = "ALT",
                            height = keyHeight,
                            armed = surfaceBridge.stickyAlt,
                        ) {
                            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            surfaceBridge.toggleStickyAlt()
                        }
                        TactileKeycap(
                            label = "^C",
                            height = keyHeight,
                            accentColor = WorkstationColors.AccentRose,
                        ) {
                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            surfaceBridge.sendControlKey('C')
                        }
                        TactileKeycap("^D", height = keyHeight) {
                            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            surfaceBridge.sendControlKey('D')
                        }
                        TactileKeycap("↑", height = keyHeight) { surfaceBridge.sendEscapeSequence("\u001b[A") }
                        TactileKeycap("↓", height = keyHeight) { surfaceBridge.sendEscapeSequence("\u001b[B") }
                        TactileKeycap("←", height = keyHeight) { surfaceBridge.sendEscapeSequence("\u001b[D") }
                        TactileKeycap("→", height = keyHeight) { surfaceBridge.sendEscapeSequence("\u001b[C") }
                        TactileKeycap("/", height = keyHeight) { surfaceBridge.sendTextToPty("/") }
                        TactileKeycap("-", height = keyHeight) { surfaceBridge.sendTextToPty("-") }
                        TactileKeycap("|", height = keyHeight) { surfaceBridge.sendTextToPty("|") }
                        TactileKeycap("~", height = keyHeight) { surfaceBridge.sendTextToPty("~") }

                        VerticalDivider(
                            thickness = 1.dp,
                            color = WorkstationColors.BorderHairline,
                            modifier = Modifier.height(18.dp),
                        )

                        TactileKeycap("agy", height = keyHeight, accentColor = WorkstationColors.AccentCyan) {
                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            onQuickCommand("agy")
                        }
                        TactileKeycap("claude", height = keyHeight, accentColor = WorkstationColors.AccentEmerald) {
                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            onQuickCommand("claude")
                        }
                        TactileKeycap("zellij", height = keyHeight) {
                            onQuickCommand("zellij")
                        }
                        Spacer(modifier = Modifier.width(18.dp))
                    }

                    // Subtle right fade hint showing dock is scrollable
                    Box(
                        modifier = Modifier
                            .align(Alignment.CenterEnd)
                            .width(18.dp)
                            .height(keyHeight)
                            .background(
                                Brush.horizontalGradient(
                                    colors = listOf(Color.Transparent, dockBgColor)
                                )
                            )
                    )
                }

                Spacer(modifier = Modifier.width(6.dp))

                // Always-visible IME toggle keycap
                Surface(
                    shape = RoundedCornerShape(7.dp),
                    color = WorkstationColors.SurfaceInteractive,
                    border = BorderStroke(1.dp, WorkstationColors.BorderStrong),
                    modifier = Modifier
                        .size(width = 36.dp, height = keyHeight)
                        .clickable {
                            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            surfaceBridge.toggleSoftKeyboard()
                        },
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = WorkstationIcons.Keyboard,
                            contentDescription = "Toggle Keyboard",
                            tint = WorkstationColors.AccentCyan,
                            modifier = Modifier.size(16.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun TactileKeycap(
    label: String,
    height: Dp,
    armed: Boolean = false,
    accentColor: Color? = null,
    onClick: () -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.92f else 1f,
        animationSpec = MotionTokens.TactileSpring,
        label = "KeycapScale",
    )
    val bgColor by animateColorAsState(
        targetValue = when {
            armed -> WorkstationColors.AccentAmberWash
            else -> WorkstationColors.SurfaceKeycap
        },
        label = "KeycapBg",
    )
    val borderColor = when {
        armed -> WorkstationColors.AccentAmber
        accentColor != null -> accentColor.copy(alpha = 0.35f)
        else -> WorkstationColors.BorderHairline
    }

    Surface(
        shape = RoundedCornerShape(7.dp),
        color = bgColor,
        border = BorderStroke(1.dp, borderColor),
        modifier = Modifier
            .height(height)
            .widthIn(min = 34.dp)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick,
            ),
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.padding(horizontal = 9.dp),
        ) {
            Text(
                text = label,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.SemiBold,
                fontSize = 11.sp,
                color = when {
                    armed -> WorkstationColors.AccentAmber
                    accentColor != null -> accentColor
                    else -> WorkstationColors.TextPrimary
                },
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
internal fun CompactTelemetryBar(
    leftText: String,
    rightNotice: String,
    compactPhone: Boolean,
    applyBottomInsets: Boolean = true,
    modifier: Modifier = Modifier,
) {
    val insetSides = if (applyBottomInsets) {
        WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal
    } else {
        WindowInsetsSides.Horizontal
    }

    Surface(
        color = WorkstationColors.VoidCanvas,
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            HorizontalDivider(thickness = 1.dp, color = WorkstationColors.BorderHairline)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .windowInsetsPadding(
                        WindowInsets.navigationBars
                            .union(WindowInsets.displayCutout)
                            .only(insetSides)
                    )
                    .height(22.dp)
                    .padding(horizontal = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = leftText,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 10.sp,
                    color = WorkstationColors.TextSecondary,
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(if (compactPhone) 1.35f else 1.5f),
                )
                AnimatedContent(
                    targetState = rightNotice,
                    transitionSpec = { MotionTokens.statusTickerTransition() },
                    label = "StatusTicker",
                    modifier = Modifier.weight(1f),
                ) { notice ->
                    Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
                        Text(
                            text = notice,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 10.sp,
                            color = WorkstationColors.AccentEmerald,
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun InspectorSegmentedHeader(
    selected: InspectorTab,
    onSelect: (InspectorTab) -> Unit,
) {
    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
        InspectorTab.entries.forEachIndexed { idx, tab ->
            SegmentedButton(
                selected = selected == tab,
                onClick = { onSelect(tab) },
                shape = SegmentedButtonDefaults.itemShape(index = idx, count = InspectorTab.entries.size),
                icon = {},
                colors = SegmentedButtonDefaults.colors(
                    activeContainerColor = WorkstationColors.AccentCyanWash,
                    activeContentColor = WorkstationColors.AccentCyan,
                    inactiveContainerColor = WorkstationColors.SurfaceElevated,
                    inactiveContentColor = WorkstationColors.TextSecondary,
                    activeBorderColor = WorkstationColors.BorderStrong,
                    inactiveBorderColor = WorkstationColors.BorderHairline,
                ),
            ) {
                Text(
                    text = tab.label,
                    fontSize = 12.sp,
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
internal fun CompactStatusPill(
    label: String,
    healthy: Boolean,
) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = if (healthy) WorkstationColors.AccentEmeraldWash else WorkstationColors.AccentAmberWash,
        border = BorderStroke(
            1.dp,
            if (healthy) WorkstationColors.AccentEmerald.copy(alpha = 0.35f)
            else WorkstationColors.AccentAmber.copy(alpha = 0.35f),
        ),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(6.dp)
                    .clip(CircleShape)
                    .background(if (healthy) WorkstationColors.AccentEmerald else WorkstationColors.AccentAmber)
            )
            Text(
                text = label,
                fontFamily = FontFamily.Monospace,
                fontSize = 10.sp,
                fontWeight = FontWeight.Medium,
                color = if (healthy) WorkstationColors.AccentEmerald else WorkstationColors.AccentAmber,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
internal fun CompactKeycapButton(
    label: String,
    onClick: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = WorkstationColors.SurfaceKeycap,
        border = BorderStroke(1.dp, WorkstationColors.BorderHairline),
        modifier = Modifier
            .height(32.dp)
            .clickable(onClick = onClick),
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.padding(horizontal = 10.dp),
        ) {
            Text(
                text = label,
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                color = WorkstationColors.TextPrimary,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
internal fun CompactIconAction(
    icon: ImageVector,
    contentDescription: String,
    highlighted: Boolean = false,
    size: Dp = 34.dp,
    onClick: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(9.dp),
        color = if (highlighted) WorkstationColors.AccentCyanWash else WorkstationColors.SurfaceKeycap,
        border = BorderStroke(
            1.dp,
            if (highlighted) WorkstationColors.BorderStrong else WorkstationColors.BorderHairline,
        ),
        modifier = Modifier
            .size(size)
            .clickable(onClick = onClick),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                imageVector = icon,
                contentDescription = contentDescription,
                tint = if (highlighted) WorkstationColors.AccentCyan else WorkstationColors.TextSecondary,
                modifier = Modifier.size(17.dp),
            )
        }
    }
}

@Composable
internal fun CompactHelpKeycap(
    size: Dp = 34.dp,
    onClick: () -> Unit,
) {
    val haptics = LocalHapticFeedback.current
    Surface(
        shape = RoundedCornerShape(9.dp),
        color = WorkstationColors.SurfaceKeycap,
        border = BorderStroke(1.dp, WorkstationColors.BorderHairline),
        modifier = Modifier
            .size(size)
            .semantics { contentDescription = "Tutorial" }
            .clickable {
                haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                onClick()
            },
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                text = "?",
                fontFamily = FontFamily.Monospace,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                color = WorkstationColors.TextSecondary,
                maxLines = 1,
                softWrap = false,
            )
        }
    }
}

internal fun tabIcon(tab: PrimaryTab): ImageVector = when (tab) {
    PrimaryTab.TERMINAL -> WorkstationIcons.TerminalPrompt
    PrimaryTab.AGENTS -> WorkstationIcons.AgentsLayers
    PrimaryTab.SHIELD -> WorkstationIcons.ShieldGuard
    PrimaryTab.STORAGE -> WorkstationIcons.StorageVault
}

@Composable
internal fun OtaFloatingBanner(
    updateState: UpdateState,
    onDownload: (ReleaseInfo) -> Unit,
    onInstall: (File) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val haptics = LocalHapticFeedback.current
    val visible = updateState is UpdateState.UpdateAvailable ||
        updateState is UpdateState.Downloading ||
        updateState is UpdateState.ReadyToInstall

    AnimatedVisibility(
        visible = visible,
        enter = fadeIn() + expandVertically(),
        exit = fadeOut() + shrinkVertically(),
        modifier = modifier,
    ) {
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = WorkstationColors.SurfaceElevated,
            border = BorderStroke(1.dp, WorkstationColors.BorderStrong),
            shadowElevation = 6.dp,
            modifier = Modifier.widthIn(max = 460.dp),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(
                            if (updateState is UpdateState.ReadyToInstall) WorkstationColors.AccentEmerald
                            else WorkstationColors.AccentCyan
                        )
                )
                when (updateState) {
                    is UpdateState.UpdateAvailable -> {
                        Text(
                            text = "Update ${updateState.release.tagName} ready",
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp,
                            color = WorkstationColors.TextPrimary,
                            maxLines = 1,
                            softWrap = false,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        Button(
                            onClick = { onDownload(updateState.release) },
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                            modifier = Modifier.height(30.dp),
                        ) {
                            Text("Update", fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                    is UpdateState.Downloading -> {
                        Text(
                            text = "Downloading ${updateState.release.tagName} (${updateState.progressPercent}%)",
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp,
                            color = WorkstationColors.AccentCyan,
                            maxLines = 1,
                            softWrap = false,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                    }
                    is UpdateState.ReadyToInstall -> {
                        Text(
                            text = "${updateState.release.tagName} verified · ready",
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp,
                            color = WorkstationColors.AccentEmerald,
                            maxLines = 1,
                            softWrap = false,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        Button(
                            onClick = { onInstall(updateState.apkFile) },
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                            modifier = Modifier.height(30.dp),
                        ) {
                            Text("Install", fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                    else -> Unit
                }
                Surface(
                    shape = RoundedCornerShape(7.dp),
                    color = WorkstationColors.SurfaceKeycap,
                    border = BorderStroke(1.dp, WorkstationColors.BorderHairline),
                    modifier = Modifier
                        .size(28.dp)
                        .semantics { contentDescription = "Dismiss update banner" }
                        .clickable {
                            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            onDismiss()
                        },
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text(
                            text = "×",
                            fontFamily = FontFamily.Monospace,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = WorkstationColors.TextSecondary,
                            maxLines = 1,
                            softWrap = false,
                        )
                    }
                }
            }
        }
    }
}
