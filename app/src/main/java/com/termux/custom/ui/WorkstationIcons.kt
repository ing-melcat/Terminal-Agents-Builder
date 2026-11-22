package com.termux.custom.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathBuilder
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/**
 * Zero-dependency crisp 24dp stroked vector iconography tuned for Obsidian / HyperOS Pro dark UI.
 */
object WorkstationIcons {

    private fun strokedIcon(
        name: String,
        strokeWidth: Float = 1.85f,
        block: PathBuilder.() -> Unit,
    ): ImageVector = ImageVector.Builder(
        name = name,
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).apply {
        path(
            fill = null,
            stroke = SolidColor(Color.White),
            strokeLineWidth = strokeWidth,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round,
            pathFillType = PathFillType.NonZero,
            pathBuilder = block,
        )
    }.build()

    val TerminalPrompt: ImageVector by lazy {
        strokedIcon("TerminalPrompt") {
            // Rounded frame
            moveTo(4f, 5f)
            lineTo(20f, 5f)
            quadTo(21.5f, 5f, 21.5f, 6.5f)
            lineTo(21.5f, 17.5f)
            quadTo(21.5f, 19f, 20f, 19f)
            lineTo(4f, 19f)
            quadTo(2.5f, 19f, 2.5f, 17.5f)
            lineTo(2.5f, 6.5f)
            quadTo(2.5f, 5f, 4f, 5f)
            close()
            // Prompt chevron >
            moveTo(6.8f, 9.5f)
            lineTo(10.2f, 12f)
            lineTo(6.8f, 14.5f)
            // Underscore _
            moveTo(12.2f, 14.5f)
            lineTo(17.2f, 14.5f)
        }
    }

    val AgentsLayers: ImageVector by lazy {
        strokedIcon("AgentsLayers") {
            // Top diamond
            moveTo(12f, 3.5f)
            lineTo(20.5f, 8f)
            lineTo(12f, 12.5f)
            lineTo(3.5f, 8f)
            close()
            // Mid layer
            moveTo(3.5f, 12.2f)
            lineTo(12f, 16.7f)
            lineTo(20.5f, 12.2f)
            // Bottom layer
            moveTo(3.5f, 16.2f)
            lineTo(12f, 20.7f)
            lineTo(20.5f, 16.2f)
        }
    }

    val ShieldGuard: ImageVector by lazy {
        strokedIcon("ShieldGuard") {
            moveTo(12f, 2.8f)
            lineTo(19.5f, 5.8f)
            lineTo(19.5f, 11.4f)
            curveTo(19.5f, 16.2f, 16.3f, 19.6f, 12f, 21.2f)
            curveTo(7.7f, 19.6f, 4.5f, 16.2f, 4.5f, 11.4f)
            lineTo(4.5f, 5.8f)
            close()
            // Checkmark inside shield
            moveTo(8.8f, 12.1f)
            lineTo(11f, 14.3f)
            lineTo(15.5f, 9.6f)
        }
    }

    val StorageVault: ImageVector by lazy {
        strokedIcon("StorageVault") {
            // Top ellipse approximation
            moveTo(4.5f, 6.5f)
            curveTo(4.5f, 4.7f, 7.9f, 3.5f, 12f, 3.5f)
            curveTo(16.1f, 3.5f, 19.5f, 4.7f, 19.5f, 6.5f)
            curveTo(19.5f, 8.3f, 16.1f, 9.5f, 12f, 9.5f)
            curveTo(7.9f, 9.5f, 4.5f, 8.3f, 4.5f, 6.5f)
            close()
            // Left & right walls + bottom curve
            moveTo(4.5f, 6.5f)
            lineTo(4.5f, 17.5f)
            curveTo(4.5f, 19.3f, 7.9f, 20.5f, 12f, 20.5f)
            curveTo(16.1f, 20.5f, 19.5f, 19.3f, 19.5f, 17.5f)
            lineTo(19.5f, 6.5f)
            // Middle seam
            moveTo(4.5f, 12f)
            curveTo(4.5f, 13.8f, 7.9f, 15f, 12f, 15f)
            curveTo(16.1f, 15f, 19.5f, 13.8f, 19.5f, 12f)
        }
    }

    val CommandSpark: ImageVector by lazy {
        strokedIcon("CommandSpark") {
            // Command / search bolt hybrid
            moveTo(13.2f, 3f)
            lineTo(5.8f, 13.2f)
            lineTo(11.6f, 13.2f)
            lineTo(10.8f, 21f)
            lineTo(18.2f, 10.8f)
            lineTo(12.4f, 10.8f)
            close()
        }
    }

    val PanelZen: ImageVector by lazy {
        strokedIcon("PanelZen") {
            moveTo(4f, 4.5f)
            lineTo(20f, 4.5f)
            quadTo(21f, 4.5f, 21f, 5.5f)
            lineTo(21f, 18.5f)
            quadTo(21f, 19.5f, 20f, 19.5f)
            lineTo(4f, 19.5f)
            quadTo(3f, 19.5f, 3f, 18.5f)
            lineTo(3f, 5.5f)
            quadTo(3f, 4.5f, 4f, 4.5f)
            close()
            moveTo(14.5f, 4.5f)
            lineTo(14.5f, 19.5f)
        }
    }

    val ClassicShell: ImageVector by lazy {
        strokedIcon("ClassicShell") {
            moveTo(14f, 4f)
            lineTo(20f, 4f)
            lineTo(20f, 10f)
            moveTo(10.5f, 13.5f)
            lineTo(20f, 4f)
            moveTo(18f, 13.5f)
            lineTo(18f, 18.5f)
            quadTo(18f, 20f, 16.5f, 20f)
            lineTo(5.5f, 20f)
            quadTo(4f, 20f, 4f, 18.5f)
            lineTo(4f, 7.5f)
            quadTo(4f, 6f, 5.5f, 6f)
            lineTo(10.5f, 6f)
        }
    }

    val Plus: ImageVector by lazy {
        strokedIcon("Plus") {
            moveTo(12f, 5f)
            lineTo(12f, 19f)
            moveTo(5f, 12f)
            lineTo(19f, 12f)
        }
    }

    val Keyboard: ImageVector by lazy {
        strokedIcon("Keyboard") {
            moveTo(3.5f, 6f)
            lineTo(20.5f, 6f)
            quadTo(21.5f, 6f, 21.5f, 7f)
            lineTo(21.5f, 17f)
            quadTo(21.5f, 18f, 20.5f, 18f)
            lineTo(3.5f, 18f)
            quadTo(2.5f, 18f, 2.5f, 17f)
            lineTo(2.5f, 7f)
            quadTo(2.5f, 6f, 3.5f, 6f)
            close()
            moveTo(6.5f, 10f)
            lineTo(8f, 10f)
            moveTo(11f, 10f)
            lineTo(13f, 10f)
            moveTo(16f, 10f)
            lineTo(17.5f, 10f)
            moveTo(8f, 14.5f)
            lineTo(16f, 14.5f)
        }
    }

    val CheckCircle: ImageVector by lazy {
        strokedIcon("CheckCircle") {
            moveTo(12f, 3f)
            curveTo(17f, 3f, 21f, 7f, 21f, 12f)
            curveTo(21f, 17f, 17f, 21f, 12f, 21f)
            curveTo(7f, 21f, 3f, 17f, 3f, 12f)
            curveTo(3f, 7f, 7f, 3f, 12f, 3f)
            close()
            moveTo(8.5f, 12.2f)
            lineTo(10.8f, 14.5f)
            lineTo(15.8f, 9.5f)
        }
    }

    val SshNodes: ImageVector by lazy {
        strokedIcon("SshNodes") {
            // Top server rack box
            moveTo(4.5f, 4.5f)
            lineTo(19.5f, 4.5f)
            quadTo(20.5f, 4.5f, 20.5f, 5.5f)
            lineTo(20.5f, 9.5f)
            quadTo(20.5f, 10.5f, 19.5f, 10.5f)
            lineTo(4.5f, 10.5f)
            quadTo(3.5f, 10.5f, 3.5f, 9.5f)
            lineTo(3.5f, 5.5f)
            quadTo(3.5f, 4.5f, 4.5f, 4.5f)
            close()
            moveTo(7f, 7.5f)
            lineTo(7.5f, 7.5f)
            moveTo(10.5f, 7.5f)
            lineTo(16.5f, 7.5f)
            // Vertical network link + bottom node bar
            moveTo(12f, 10.5f)
            lineTo(12f, 16.5f)
            moveTo(5.5f, 16.5f)
            lineTo(18.5f, 16.5f)
            // Left & right endpoint ticks
            moveTo(5.5f, 16.5f)
            lineTo(5.5f, 19.5f)
            moveTo(12f, 16.5f)
            lineTo(12f, 19.5f)
            moveTo(18.5f, 16.5f)
            lineTo(18.5f, 19.5f)
        }
    }
}
