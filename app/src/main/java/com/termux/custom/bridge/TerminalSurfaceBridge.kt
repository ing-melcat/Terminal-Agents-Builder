package com.termux.custom.bridge

import android.app.Activity
import android.content.Context
import android.graphics.Typeface
import android.os.Build
import android.util.TypedValue
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.WindowManager
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.termux.app.TermuxService
import com.termux.shared.interact.ShareUtils
import com.termux.shared.termux.TermuxConstants
import com.termux.shared.termux.terminal.TermuxTerminalSessionClientBase
import com.termux.shared.view.KeyboardUtils
import com.termux.terminal.TerminalSession
import com.termux.view.TerminalView
import com.termux.view.TerminalViewClient

enum class RendererBackend {
    /** Phase 1: Termux TerminalView with 120Hz frame pacing, NerdFont, and live SessionClient */
    TERMUX_VIEW_120HZ,

    /** Phase 2 extension hook: GPU native renderer (alacritty_terminal / libghostty-vt) attached to PTY FD */
    NATIVE_GPU_FD_BRIDGE,
}

interface TerminalSurfaceBridge {
    val backend: RendererBackend
    val activeRefreshRateHz: Float
    val currentFontSizePx: Int
    val stickyCtrl: Boolean
    val stickyAlt: Boolean
    fun attachSession(session: TerminalSession)
    fun createSessionClient(
        serviceProvider: () -> TermuxService?,
        onSessionsChanged: () -> Unit,
    ): TermuxTerminalSessionClientBase
    fun toggleStickyCtrl()
    fun toggleStickyAlt()
    fun showSoftKeyboard()
    fun toggleSoftKeyboard()
    fun sendTextToPty(text: String)
    fun sendCommandLine(command: String)
    fun sendControlKey(key: Char)
    fun sendEscapeSequence(seq: String)
    fun apply120HzFramePacing(activity: Activity)
    fun setFontSizePx(sizePx: Int)
    fun adjustFontSize(deltaPx: Int)
    fun setCursorBlinker(enabled: Boolean)
}

class TermuxViewSurfaceBridge(
    context: Context,
    private val onShortcutCommandPalette: () -> Unit = {},
    private val onShortcutNewSession: () -> Unit = {},
    private val onShortcutCycleSession: () -> Unit = {},
    private val onShortcutToggleZenMode: () -> Unit = {},
) : TerminalSurfaceBridge {

    override val backend: RendererBackend = RendererBackend.TERMUX_VIEW_120HZ

    private val appContext = context.applicationContext
    private var currentSession: TerminalSession? = null

    private var _fontSizePx by mutableIntStateOf(
        TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_SP,
            13f,
            context.resources.displayMetrics,
        ).toInt().coerceIn(20, 56)
    )
    override val currentFontSizePx: Int
        get() = _fontSizePx

    private var _refreshRateHz by mutableFloatStateOf(60f)
    override val activeRefreshRateHz: Float
        get() = _refreshRateHz

    private var _stickyCtrl by mutableStateOf(false)
    override val stickyCtrl: Boolean
        get() = _stickyCtrl

    private var _stickyAlt by mutableStateOf(false)
    override val stickyAlt: Boolean
        get() = _stickyAlt

    private var _isSessionAttached by mutableStateOf(false)
    val isSessionAttached: Boolean
        get() = _isSessionAttached

    val terminalView: TerminalView = TerminalView(context, null).apply {
        isFocusable = true
        isFocusableInTouchMode = true
        setTextSize(_fontSizePx)
        setTypeface(resolveMonospaceNerdFont())
        setTerminalViewClient(BridgeViewClient())
        setTerminalCursorBlinkerRate(500)
    }

    override fun attachSession(session: TerminalSession) {
        currentSession = session
        _isSessionAttached = true
        terminalView.attachSession(session)
        terminalView.setTerminalCursorBlinkerState(true, true)
        terminalView.onScreenUpdated()
    }

    override fun createSessionClient(
        serviceProvider: () -> TermuxService?,
        onSessionsChanged: () -> Unit,
    ): TermuxTerminalSessionClientBase = object : TermuxTerminalSessionClientBase() {
        override fun onTextChanged(changedSession: TerminalSession) {
            if (terminalView.currentSession === changedSession) {
                terminalView.onScreenUpdated()
            }
        }

        override fun onTitleChanged(updatedSession: TerminalSession) {
            onSessionsChanged()
        }

        override fun onSessionFinished(finishedSession: TerminalSession) {
            val service = serviceProvider()
            if (service != null) {
                val termuxSession = service.getTermuxSessionForTerminalSession(finishedSession)
                if (termuxSession != null && (finishedSession.exitStatus == 0 || finishedSession.exitStatus == 130)) {
                    service.removeTermuxSession(finishedSession)
                }
            }
            onSessionsChanged()
        }

        override fun onCopyTextToClipboard(session: TerminalSession, text: String?) {
            if (!text.isNullOrEmpty()) {
                ShareUtils.copyTextToClipboard(appContext, text)
            }
        }

        override fun onPasteTextFromClipboard(session: TerminalSession?) {
            val clip = ShareUtils.getTextStringFromClipboardIfSet(appContext, true)
            if (!clip.isNullOrEmpty()) {
                terminalView.mEmulator?.paste(clip)
            }
        }

        override fun onColorsChanged(changedSession: TerminalSession) {
            if (terminalView.currentSession === changedSession) {
                terminalView.onScreenUpdated()
            }
        }

        override fun onTerminalCursorStateChange(state: Boolean) {
            terminalView.setTerminalCursorBlinkerState(state, false)
        }

        override fun setTerminalShellPid(session: TerminalSession, pid: Int) {
            val service = serviceProvider() ?: return
            val termuxSession = service.getTermuxSessionForTerminalSession(session)
            termuxSession?.executionCommand?.mPid = pid
            onSessionsChanged()
        }
    }

    override fun toggleStickyCtrl() {
        _stickyCtrl = !_stickyCtrl
    }

    override fun toggleStickyAlt() {
        _stickyAlt = !_stickyAlt
    }

    override fun showSoftKeyboard() {
        terminalView.requestFocus()
        KeyboardUtils.showSoftKeyboard(terminalView.context, terminalView)
    }

    override fun toggleSoftKeyboard() {
        terminalView.requestFocus()
        KeyboardUtils.toggleSoftKeyboard(terminalView.context)
    }

    override fun sendTextToPty(text: String) {
        val bytes = text.toByteArray(Charsets.UTF_8)
        currentSession?.write(bytes, 0, bytes.size)
        terminalView.onScreenUpdated()
    }

    override fun sendCommandLine(command: String) {
        sendTextToPty(command.trimEnd() + "\n")
    }

    override fun sendControlKey(key: Char) {
        val upper = key.uppercaseChar()
        if (upper in 'A'..'Z') {
            val ctrlByte = byteArrayOf((upper.code - 64).toByte())
            currentSession?.write(ctrlByte, 0, 1)
            terminalView.onScreenUpdated()
        }
    }

    override fun sendEscapeSequence(seq: String) {
        sendTextToPty(seq)
    }

    override fun apply120HzFramePacing(activity: Activity) {
        runCatching {
            val display = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                activity.display
            } else {
                @Suppress("DEPRECATION")
                activity.windowManager.defaultDisplay
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && display != null) {
                val highestMode = display.supportedModes.maxByOrNull { it.refreshRate }
                if (highestMode != null) {
                    val params: WindowManager.LayoutParams = activity.window.attributes
                    params.preferredDisplayModeId = highestMode.modeId
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        params.preferredRefreshRate = highestMode.refreshRate
                    }
                    activity.window.attributes = params
                    _refreshRateHz = highestMode.refreshRate
                } else {
                    _refreshRateHz = display.refreshRate
                }
            }
        }
    }

    override fun setFontSizePx(sizePx: Int) {
        _fontSizePx = sizePx.coerceIn(18, 72)
        terminalView.setTextSize(_fontSizePx)
    }

    override fun adjustFontSize(deltaPx: Int) {
        setFontSizePx(_fontSizePx + deltaPx)
    }

    override fun setCursorBlinker(enabled: Boolean) {
        terminalView.setTerminalCursorBlinkerState(enabled, false)
    }

    private fun resolveMonospaceNerdFont(): Typeface {
        val customFontFile = TermuxConstants.TERMUX_FONT_FILE
        if (customFontFile.isFile && customFontFile.length() > 0L) {
            runCatching { return Typeface.createFromFile(customFontFile) }
        }
        return Typeface.MONOSPACE
    }

    private inner class BridgeViewClient : TerminalViewClient {
        override fun onScale(scale: Float): Float {
            if (scale < 0.9f || scale > 1.1f) {
                val increase = scale > 1.0f
                adjustFontSize(if (increase) 2 else -2)
                return 1.0f
            }
            return scale
        }

        override fun onSingleTapUp(e: MotionEvent?) {
            terminalView.requestFocus()
            val emulator = currentSession?.emulator
            val isMouseEvent = e?.isFromSource(InputDevice.SOURCE_MOUSE) == true
            if ((emulator == null || !emulator.isMouseTrackingActive) && !isMouseEvent) {
                KeyboardUtils.showSoftKeyboard(terminalView.context, terminalView)
            }
        }

        override fun shouldBackButtonBeMappedToEscape(): Boolean = false

        override fun shouldEnforceCharBasedInput(): Boolean = true

        override fun shouldUseCtrlSpaceWorkaround(): Boolean = true

        override fun isTerminalViewSelected(): Boolean = true

        override fun copyModeChanged(copyMode: Boolean) = Unit

        override fun onKeyDown(keyCode: Int, e: KeyEvent?, session: TerminalSession?): Boolean {
            if (e == null) return false
            val ctrl = e.isCtrlPressed
            val shift = e.isShiftPressed

            if (ctrl && shift && keyCode == KeyEvent.KEYCODE_P) {
                onShortcutCommandPalette()
                return true
            }
            if (ctrl && shift && keyCode == KeyEvent.KEYCODE_T) {
                onShortcutNewSession()
                return true
            }
            if (ctrl && shift && keyCode == KeyEvent.KEYCODE_B) {
                onShortcutToggleZenMode()
                return true
            }
            if (ctrl && keyCode == KeyEvent.KEYCODE_TAB) {
                onShortcutCycleSession()
                return true
            }
            if (ctrl && (keyCode == KeyEvent.KEYCODE_EQUALS || keyCode == KeyEvent.KEYCODE_PLUS)) {
                adjustFontSize(2)
                return true
            }
            if (ctrl && keyCode == KeyEvent.KEYCODE_MINUS) {
                adjustFontSize(-2)
                return true
            }
            return false
        }

        override fun onKeyUp(keyCode: Int, e: KeyEvent?): Boolean = false

        override fun onLongPress(event: MotionEvent?): Boolean = false

        override fun readControlKey(): Boolean {
            val active = _stickyCtrl
            if (active) _stickyCtrl = false
            return active
        }

        override fun readAltKey(): Boolean {
            val active = _stickyAlt
            if (active) _stickyAlt = false
            return active
        }

        override fun readShiftKey(): Boolean = false

        override fun readFnKey(): Boolean = false

        override fun onCodePoint(codePoint: Int, ctrlDown: Boolean, session: TerminalSession?): Boolean = false

        override fun onEmulatorSet() {
            terminalView.setTerminalCursorBlinkerState(true, true)
            terminalView.onScreenUpdated()
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
