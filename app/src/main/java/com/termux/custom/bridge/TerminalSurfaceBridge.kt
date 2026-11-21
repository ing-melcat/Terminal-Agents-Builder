package com.termux.custom.bridge

import android.app.Activity
import android.content.Context
import android.graphics.Typeface
import android.os.Build
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import com.termux.terminal.TerminalSession
import com.termux.view.TerminalView
import com.termux.view.TerminalViewClient

enum class RendererBackend {
    /** Phase 1: Hardware-accelerated Termux TerminalView with 120Hz & NerdFont support */
    TERMUX_VIEW_120HZ,

    /** Phase 2 extension hook: GPU native renderer (alacritty_terminal / libghostty-vt) attached to PTY FD */
    NATIVE_GPU_FD_BRIDGE,
}

interface TerminalSurfaceBridge {
    val backend: RendererBackend
    val activeRefreshRateHz: Float
    fun attachSession(session: TerminalSession)
    fun sendTextToPty(text: String)
    fun sendCommandLine(command: String)
    fun sendControlKey(key: Char)
    fun sendEscapeSequence(seq: String)
    fun apply120HzFramePacing(activity: Activity)
    fun setFontSizePx(sizePx: Int)
}

class TermuxViewSurfaceBridge(
    context: Context,
    private val onShortcutCommandPalette: () -> Unit = {},
    private val onShortcutNewSession: () -> Unit = {},
    private val onShortcutCycleSession: () -> Unit = {},
) : TerminalSurfaceBridge {

    override val backend: RendererBackend = RendererBackend.TERMUX_VIEW_120HZ

    private var currentSession: TerminalSession? = null
    private var currentFontSizePx: Int = 34
    private var currentRefreshHz: Float = 120f

    override val activeRefreshRateHz: Float
        get() = currentRefreshHz

    val terminalView: TerminalView = TerminalView(context, null).apply {
        isFocusable = true
        isFocusableInTouchMode = true
        setLayerType(View.LAYER_TYPE_HARDWARE, null)
        setTextSize(currentFontSizePx)
        setTypeface(resolveMonospaceNerdFont(context))
        setTerminalViewClient(BridgeViewClient())
    }

    override fun attachSession(session: TerminalSession) {
        currentSession = session
        terminalView.attachSession(session)
        terminalView.onScreenUpdated()
    }

    override fun sendTextToPty(text: String) {
        val bytes = text.toByteArray(Charsets.UTF_8)
        currentSession?.write(bytes, 0, bytes.size)
    }

    override fun sendCommandLine(command: String) {
        sendTextToPty(command.trimEnd() + "\n")
    }

    override fun sendControlKey(key: Char) {
        val upper = key.uppercaseChar()
        if (upper in 'A'..'Z') {
            val ctrlByte = byteArrayOf((upper.code - 64).toByte())
            currentSession?.write(ctrlByte, 0, 1)
        }
    }

    override fun sendEscapeSequence(seq: String) {
        sendTextToPty(seq)
    }

    override fun apply120HzFramePacing(activity: Activity) {
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val display = activity.display
                val highestMode = display?.supportedModes?.maxByOrNull { it.refreshRate }
                if (highestMode != null) {
                    val params = activity.window.attributes
                    params.preferredDisplayModeId = highestMode.modeId
                    params.preferredRefreshRate = highestMode.refreshRate
                    activity.window.attributes = params
                    currentRefreshHz = highestMode.refreshRate
                }
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                @Suppress("DEPRECATION")
                val display = activity.windowManager.defaultDisplay
                val highestMode = display?.supportedModes?.maxByOrNull { it.refreshRate }
                if (highestMode != null) {
                    val params: WindowManager.LayoutParams = activity.window.attributes
                    params.preferredDisplayModeId = highestMode.modeId
                    activity.window.attributes = params
                    currentRefreshHz = highestMode.refreshRate
                }
            }
        }
    }

    override fun setFontSizePx(sizePx: Int) {
        currentFontSizePx = sizePx.coerceIn(18, 72)
        terminalView.setTextSize(currentFontSizePx)
    }

    private fun resolveMonospaceNerdFont(context: Context): Typeface {
        val customFontFile = java.io.File("/data/data/com.termux/files/home/.termux/font.ttf")
        if (customFontFile.exists() && customFontFile.length() > 0) {
            runCatching { return Typeface.createFromFile(customFontFile) }
        }
        return Typeface.MONOSPACE
    }

    private inner class BridgeViewClient : TerminalViewClient {
        override fun onScale(scale: Float): Float {
            val next = (currentFontSizePx * scale).toInt().coerceIn(18, 72)
            setFontSizePx(next)
            return 1.0f
        }

        override fun onSingleTapUp(e: MotionEvent?) {
            terminalView.requestFocus()
        }

        override fun shouldBackButtonBeMappedToEscape(): Boolean = true

        override fun shouldEnforceCharBasedInput(): Boolean = true

        override fun shouldUseCtrlSpaceWorkaround(): Boolean = true

        override fun isTerminalViewSelected(): Boolean = true

        override fun copyModeChanged(copyMode: Boolean) = Unit

        override fun onKeyDown(keyCode: Int, e: KeyEvent?, session: TerminalSession?): Boolean {
            if (e == null) return false
            val ctrl = e.isCtrlPressed
            val shift = e.isShiftPressed

            // Physical keyboard chords for Xiaomi Pad 6 Workstation
            if (ctrl && shift && keyCode == KeyEvent.KEYCODE_P) {
                onShortcutCommandPalette()
                return true
            }
            if (ctrl && shift && keyCode == KeyEvent.KEYCODE_T) {
                onShortcutNewSession()
                return true
            }
            if (ctrl && keyCode == KeyEvent.KEYCODE_TAB) {
                onShortcutCycleSession()
                return true
            }
            return false
        }

        override fun onKeyUp(keyCode: Int, e: KeyEvent?): Boolean = false

        override fun onLongPress(event: MotionEvent?): Boolean = false

        override fun readControlKey(): Boolean = false

        override fun readAltKey(): Boolean = false

        override fun readShiftKey(): Boolean = false

        override fun readFnKey(): Boolean = false

        override fun onCodePoint(codePoint: Int, ctrlDown: Boolean, session: TerminalSession?): Boolean = false

        override fun onEmulatorSet() {
            terminalView.invalidate()
        }

        override fun logError(tag: String?, message: String?) = Unit
        override fun logWarn(tag: String?, message: String?) = Unit
        override fun logInfo(tag: String?, message: String?) = Unit
        override fun logDebug(tag: String?, message: String?) = Unit
        override fun logVerbose(tag: String?, message: String?) = Unit
        override fun logStackTraceWithMessage(tag: String?, message: String?, e: Exception?) = Unit
        override fun logStackTrace(tag: String?, e: Exception?) = Unit
    }
}
