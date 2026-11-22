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
    // RUR · Research Unit of Robotics (UNIPOLI) Aerospace Carbon & Gold Design System
    val VoidCanvas = Color(0xFF060A0F)         // Deep carbon void backdrop
    val TerminalBackdrop = Color(0xFF05080D)   // High-contrast terminal well
    val SurfaceBase = Color(0xFF0B1016)        // Exact sampled RUR logo canvas (#0B1016)
    val SurfaceElevated = Color(0xFF111821)    // Elevated cards & inspector panels
    val SurfaceInteractive = Color(0xFF18222F) // Active tabs, hover & selection fills
    val SurfaceKeycap = Color(0xFF141C26)      // Tactile extra-keys & command chips

    val BorderHairline = Color(0x1CF8FAFC)     // ~11% crisp instrument hairline
    val BorderStrong = Color(0x4DF2BA0B)       // ~30% RUR Gold active stroke

    val AccentGold = Color(0xFFF2BA0B)         // Exact sampled RUR Robotics Gold (#F2BA0B)
    val AccentGoldWash = Color(0x24F2BA0B)     // ~14% translucent RUR Gold wash
    val AccentBrass = Color(0xFFD4A017)        // Metallic warm brass secondary accent
    // Semantic primary accent alias so all primary UI surfaces inherit RUR Gold consistently
    val AccentCyan = AccentGold
    val AccentCyanWash = AccentGoldWash

    val AccentEmerald = Color(0xFF34D399)      // Semantic healthy / live PTY / 0600 locked
    val AccentEmeraldWash = Color(0x1F34D399)
    val AccentAmber = Color(0xFFF59E0B)        // Semantic warning / pending action
    val AccentAmberWash = Color(0x22F59E0B)
    val AccentRose = Color(0xFFFB7185)         // Semantic error / destructive action
    val AccentRoseWash = Color(0x22FB7185)

    val TextPrimary = Color(0xFFF8FAFC)        // Crisp instrument white (logo `>_` & typography)
    val TextSecondary = Color(0xFF9BA6B2)      // Code-comment cool slate from logo backdrop
    val TextMuted = Color(0xFF647282)          // Subtle circuit slate
}

val WorkstationDarkColorScheme = darkColorScheme(
    primary = WorkstationColors.AccentGold,
    onPrimary = WorkstationColors.VoidCanvas,
    primaryContainer = WorkstationColors.SurfaceInteractive,
    onPrimaryContainer = WorkstationColors.TextPrimary,
    secondary = WorkstationColors.AccentBrass,
    onSecondary = WorkstationColors.VoidCanvas,
    secondaryContainer = WorkstationColors.AccentGoldWash,
    onSecondaryContainer = WorkstationColors.AccentGold,
    tertiary = WorkstationColors.AccentEmerald,
    onTertiary = WorkstationColors.VoidCanvas,
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
    SSH("SSH"),
    SHIELD("Shield"),
    STORAGE("Storage"),
}

enum class InspectorTab(val label: String) {
    AGENTS("Agents"),
    SSH("SSH"),
    SHIELD("Shield"),
    STORAGE("Storage"),
}
