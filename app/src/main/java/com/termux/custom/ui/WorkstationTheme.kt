package com.termux.custom.ui

import androidx.compose.animation.ContentTransform
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp

object WorkstationColors {
    val VoidCanvas = Color(0xFF05070B)
    val TerminalBackdrop = Color(0xFF04060A)
    val SurfaceBase = Color(0xFF0B101B)
    val SurfaceElevated = Color(0xFF111827)
    val SurfaceInteractive = Color(0xFF1A2438)
    val SurfaceKeycap = Color(0xFF141C2B)

    val BorderHairline = Color(0x1AE2E8F0) // ~10% translucent slate hairline
    val BorderStrong = Color(0x3838BDF8)   // ~22% cyan accent stroke

    val AccentCyan = Color(0xFF38BDF8)
    val AccentCyanWash = Color(0x2238BDF8)
    val AccentEmerald = Color(0xFF34D399)
    val AccentEmeraldWash = Color(0x1F34D399)
    val AccentAmber = Color(0xFFFBBF24)
    val AccentAmberWash = Color(0x22FBBF24)
    val AccentRose = Color(0xFFFB7185)
    val AccentRoseWash = Color(0x22FB7185)

    val TextPrimary = Color(0xFFF1F5F9)
    val TextSecondary = Color(0xFF94A3B8)
    val TextMuted = Color(0xFF64748B)
}

val WorkstationDarkColorScheme = darkColorScheme(
    primary = WorkstationColors.AccentCyan,
    onPrimary = WorkstationColors.VoidCanvas,
    primaryContainer = WorkstationColors.SurfaceInteractive,
    onPrimaryContainer = WorkstationColors.TextPrimary,
    secondary = WorkstationColors.AccentEmerald,
    onSecondary = WorkstationColors.VoidCanvas,
    secondaryContainer = WorkstationColors.AccentEmeraldWash,
    onSecondaryContainer = WorkstationColors.AccentEmerald,
    background = WorkstationColors.VoidCanvas,
    onBackground = WorkstationColors.TextPrimary,
    surface = WorkstationColors.SurfaceBase,
    onSurface = WorkstationColors.TextPrimary,
    surfaceVariant = WorkstationColors.SurfaceElevated,
    onSurfaceVariant = WorkstationColors.TextSecondary,
    outline = WorkstationColors.BorderHairline,
    error = WorkstationColors.AccentRose,
    onError = WorkstationColors.VoidCanvas,
)

object MotionTokens {
    val TactileSpring = spring<Float>(
        dampingRatio = 0.72f,
        stiffness = 700f,
    )

    val PaneWidthSpring = spring<Dp>(
        dampingRatio = 0.88f,
        stiffness = 420f,
    )

    fun panelTransition(): ContentTransform =
        (fadeIn(tween(140)) + slideInVertically(spring(0.85f, 520f)) { it / 24 }) togetherWith
            fadeOut(tween(90))

    fun statusTickerTransition(): ContentTransform =
        (fadeIn(tween(150)) + slideInVertically(spring(0.82f, 560f)) { it / 2 }) togetherWith
            (fadeOut(tween(100)) + slideOutVertically { -it / 2 })
}

enum class WorkstationFormFactor {
    PHONE_PORTRAIT,
    PHONE_LANDSCAPE,
    TABLET_PORTRAIT,
    TABLET_LANDSCAPE,
}

fun resolveFormFactor(widthDp: Int, heightDp: Int): WorkstationFormFactor = when {
    heightDp < 500 && widthDp > heightDp -> WorkstationFormFactor.PHONE_LANDSCAPE
    widthDp < 600 -> WorkstationFormFactor.PHONE_PORTRAIT
    widthDp in 600..839 -> WorkstationFormFactor.TABLET_PORTRAIT
    else -> WorkstationFormFactor.TABLET_LANDSCAPE
}

enum class PrimaryTab(val label: String) {
    TERMINAL("Terminal"),
    AGENTS("Agents"),
    SHIELD("Shield"),
    STORAGE("Storage"),
}

enum class InspectorTab(val label: String) {
    AGENTS("Agents"),
    SHIELD("Shield"),
    STORAGE("Storage"),
}
