package com.termux.custom.ui

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.res.Configuration
import android.os.Bundle
import android.os.IBinder
import android.view.View
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.displayCutoutPadding
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.NavigationRailItemDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.VerticalDivider
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.termux.app.TermuxActivity
import com.termux.app.TermuxInstaller
import com.termux.app.TermuxService
import com.termux.custom.bridge.TermuxViewSurfaceBridge
import com.termux.custom.data.AgentSessionRecord
import com.termux.custom.data.LocalFirstCrdtDatabase
import com.termux.custom.guardian.GuardianStatus
import com.termux.custom.guardian.ProcessGuardianService
import com.termux.custom.provisioning.CustomBootstrapProvisioner
import com.termux.custom.security.AntigravityTokenWarden
import com.termux.custom.security.OAuthTokenState
import com.termux.custom.storage.StorageMetrics
import com.termux.custom.storage.StorageOptimizer
import com.termux.custom.updater.GitHubReleaseUpdater
import com.termux.custom.updater.UpdateState
import com.termux.shared.termux.TermuxConstants
import kotlinx.coroutines.launch
import java.io.File

class WorkstationActivity : ComponentActivity(), ServiceConnection {

    private var termuxService by mutableStateOf<TermuxService?>(null)
    private var isServiceBound = false
    private var bootstrapReady by mutableStateOf(false)

    private lateinit var crdtDatabase: LocalFirstCrdtDatabase
    private lateinit var tokenWarden: AntigravityTokenWarden
    private lateinit var storageOptimizer: StorageOptimizer
    private lateinit var releaseUpdater: GitHubReleaseUpdater

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )

        crdtDatabase = LocalFirstCrdtDatabase.getInstance(this)
        tokenWarden = AntigravityTokenWarden(this)
        storageOptimizer = StorageOptimizer()
        releaseUpdater = GitHubReleaseUpdater(this)
        bootstrapReady = CustomBootstrapProvisioner.isBootstrapInstalled()

        // Start & bind TermuxService safely without BIND_AUTO_CREATE (respects Exit notification)
        val serviceIntent = Intent(this, TermuxService::class.java)
        runCatching {
            ContextCompat.startForegroundService(this, serviceIntent)
            isServiceBound = bindService(serviceIntent, this, 0)
        }
        ProcessGuardianService.start(this)

        setContent {
            MaterialTheme(colorScheme = WorkstationDarkColorScheme) {
                WorkstationRoot(
                    activity = this,
                    termuxService = termuxService,
                    bootstrapReady = bootstrapReady,
                    onEnsureBootstrap = { ensureBootstrapInstalled() },
                    crdtDatabase = crdtDatabase,
                    tokenWarden = tokenWarden,
                    storageOptimizer = storageOptimizer,
                    releaseUpdater = releaseUpdater,
                )
            }
        }
    }

    private fun ensureBootstrapInstalled() {
        if (CustomBootstrapProvisioner.isBootstrapInstalled()) {
            bootstrapReady = true
            return
        }
        TermuxInstaller.setupBootstrapIfNeeded(this) {
            bootstrapReady = CustomBootstrapProvisioner.isBootstrapInstalled()
        }
    }

    override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
        val boundService = (service as? TermuxService.LocalBinder)?.service
        termuxService = boundService
        if (boundService != null) {
            ensureBootstrapInstalled()
        }
    }

    override fun onServiceDisconnected(name: ComponentName?) {
        termuxService = null
    }

    override fun onDestroy() {
        termuxService?.setCustomTerminalSessionClient(null)
        if (isServiceBound) {
            runCatching { unbindService(this) }
            isServiceBound = false
        }
        super.onDestroy()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun WorkstationRoot(
    activity: WorkstationActivity,
    termuxService: TermuxService?,
    bootstrapReady: Boolean,
    onEnsureBootstrap: () -> Unit,
    crdtDatabase: LocalFirstCrdtDatabase,
    tokenWarden: AntigravityTokenWarden,
    storageOptimizer: StorageOptimizer,
    releaseUpdater: GitHubReleaseUpdater,
) {
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val haptics = LocalHapticFeedback.current
    val clipboard = LocalClipboardManager.current
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()

    val formFactor = remember(configuration.screenWidthDp, configuration.screenHeightDp) {
        resolveFormFactor(configuration.screenWidthDp, configuration.screenHeightDp)
    }
    val hasHardwareKeyboard = configuration.keyboard != Configuration.KEYBOARD_NOKEYS &&
        configuration.hardKeyboardHidden == Configuration.HARDKEYBOARDHIDDEN_NO

    val isImeVisible = WindowInsets.ime.getBottom(density) > 0

    // Decoupled saveable state — rotating between Phone & Tablet never corrupts active tab
    var primaryTab by rememberSaveable { mutableStateOf(PrimaryTab.TERMINAL) }
    var inspectorTab by rememberSaveable { mutableStateOf(InspectorTab.AGENTS) }
    var isZenMode by rememberSaveable { mutableStateOf(false) }
    var isPortraitSheetOpen by rememberSaveable { mutableStateOf(false) }
    var activeSessionIndex by rememberSaveable { mutableIntStateOf(0) }
    var sessionListVersion by remember { mutableIntStateOf(0) }

    var isCommandPaletteOpen by rememberSaveable { mutableStateOf(false) }
    var isTokenDialogOpen by rememberSaveable { mutableStateOf(false) }
    var isOtaTokenDialogOpen by rememberSaveable { mutableStateOf(false) }
    var statusNotice by remember { mutableStateOf("Native Engine Ready") }

    val crdtSessions by crdtDatabase.sessionsFlow.collectAsState()
    val guardianStatus by ProcessGuardianService.statusFlow.collectAsState()
    val updateState by releaseUpdater.state.collectAsState()

    var tokenState by remember {
        mutableStateOf(
            OAuthTokenState(
                isPresent = false,
                maskedPreview = "Checking…",
                fingerprintSha256 = "none",
                posixModeOctal = "----",
                storagePath = "",
                syncedCliTargets = emptyList(),
            )
        )
    }
    var storageMetrics by remember {
        mutableStateOf(
            StorageMetrics(
                prefixPath = TermuxConstants.TERMUX_PREFIX_DIR_PATH,
                freeBytes = 0L,
                totalBytes = 1L,
                snapshotsCount = 0,
                latestSnapshotPath = null,
                zstdAvailable = false,
                lastGcSummary = "Ready",
            )
        )
    }

    fun spawnNewPtySession(sessionName: String? = null) {
        val service = termuxService ?: return
        if (!CustomBootstrapProvisioner.isBootstrapInstalled()) {
            onEnsureBootstrap()
            statusNotice = "Extracting arm64 Linux runtime…"
            return
        }
        val nextNum = service.termuxSessions.size + 1
        val created = runCatching {
            service.createTermuxSession(
                null,
                null,
                null,
                TermuxConstants.TERMUX_HOME_DIR_PATH,
                false,
                sessionName ?: "agy-$nextNum",
            )
        }.getOrNull()
        if (created != null) {
            activeSessionIndex = (service.termuxSessions.size - 1).coerceAtLeast(0)
            sessionListVersion++
            primaryTab = PrimaryTab.TERMINAL
            statusNotice = "Opened session ${sessionName ?: "agy-$nextNum"}"
        }
    }

    val surfaceBridge = remember {
        TermuxViewSurfaceBridge(
            context = context,
            onShortcutCommandPalette = { isCommandPaletteOpen = true },
            onShortcutNewSession = { spawnNewPtySession() },
            onShortcutCycleSession = {
                val count = termuxService?.termuxSessions?.size ?: 0
                if (count > 1) {
                    activeSessionIndex = (activeSessionIndex + 1) % count
                    sessionListVersion++
                }
            },
            onShortcutToggleZenMode = {
                isZenMode = !isZenMode
                statusNotice = if (isZenMode) "Zen Mode · Ctrl+Shift+B" else "Inspector Restored"
            },
        )
    }

    // Wire live TerminalSessionClient so stdout/stderr/cursor/clipboard redraw in real time
    DisposableEffect(termuxService, lifecycleOwner) {
        val service = termuxService
        val client = surfaceBridge.createSessionClient(
            serviceProvider = { termuxService },
            onSessionsChanged = { sessionListVersion++ },
        )
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> {
                    service?.setCustomTerminalSessionClient(client)
                    surfaceBridge.setCursorBlinker(true)
                }
                Lifecycle.Event.ON_RESUME -> {
                    surfaceBridge.apply120HzFramePacing(activity)
                    surfaceBridge.terminalView.onScreenUpdated()
                    scope.launch {
                        ProcessGuardianService.refreshSnapshot(context)
                        tokenState = tokenWarden.inspectTokenState()
                        storageMetrics = storageOptimizer.inspectMetrics(storageMetrics.lastGcSummary)
                    }
                }
                Lifecycle.Event.ON_STOP -> {
                    surfaceBridge.setCursorBlinker(false)
                    service?.setCustomTerminalSessionClient(null)
                }
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        if (lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
            service?.setCustomTerminalSessionClient(client)
        }
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            service?.setCustomTerminalSessionClient(null)
        }
    }

    // Initial async hydration + silent OTA release check
    LaunchedEffect(Unit) {
        surfaceBridge.apply120HzFramePacing(activity)
        crdtDatabase.seedDefaultsIfFirstRun()
        tokenState = tokenWarden.inspectTokenState()
        storageMetrics = storageOptimizer.inspectMetrics()
        ProcessGuardianService.refreshSnapshot(context)
        releaseUpdater.checkForUpdate(silentOnNetworkError = true)
    }

    // Attach active PTY session once TermuxService & atomic bootstrap are ready
    LaunchedEffect(termuxService, bootstrapReady, activeSessionIndex, sessionListVersion) {
        val service = termuxService ?: return@LaunchedEffect
        if (!bootstrapReady && !CustomBootstrapProvisioner.isBootstrapInstalled()) {
            onEnsureBootstrap()
            statusNotice = "Preparing arm64 Linux runtime…"
            return@LaunchedEffect
        }
        if (service.termuxSessions.isEmpty()) {
            runCatching {
                service.createTermuxSession(
                    null,
                    null,
                    null,
                    TermuxConstants.TERMUX_HOME_DIR_PATH,
                    false,
                    "agy-main",
                )
            }
            sessionListVersion++
        }
        val list = service.termuxSessions
        if (list.isNotEmpty()) {
            val safeIdx = activeSessionIndex.coerceIn(0, list.lastIndex)
            if (safeIdx != activeSessionIndex) activeSessionIndex = safeIdx
            surfaceBridge.attachSession(list[safeIdx].terminalSession)
        }
    }

    val liveSessions = remember(termuxService, sessionListVersion) {
        termuxService?.termuxSessions.orEmpty().mapIndexed { idx, ts ->
            val rawName = ts.terminalSession.mSessionName?.takeIf { it.isNotBlank() }
                ?: ts.terminalSession.title?.takeIf { it.isNotBlank() }
                ?: "pty-${idx + 1}"
            LivePtyTab(index = idx, title = rawName, isRunning = ts.terminalSession.isRunning)
        }
    }

    val refreshHzInt = surfaceBridge.activeRefreshRateHz.toInt().coerceAtLeast(60)
    val shieldScore = computeShieldScore(
        bootstrapReady = bootstrapReady || liveSessions.isNotEmpty(),
        guardianStatus = guardianStatus,
        tokenState = tokenState,
    )

    val openClassicTermux = {
        context.startActivity(Intent(context, TermuxActivity::class.java))
    }

    val dispatchCommandToPty: (String) -> Unit = { cmd ->
        surfaceBridge.sendCommandLine(cmd)
        if (formFactor == WorkstationFormFactor.PHONE_PORTRAIT || formFactor == WorkstationFormFactor.PHONE_LANDSCAPE) {
            primaryTab = PrimaryTab.TERMINAL
        }
        statusNotice = "Sent: ${cmd.take(28)}"
    }

    val onInstallReadyApk: (File) -> Unit = { apkFile ->
        val launched = releaseUpdater.launchApkInstaller(context, apkFile)
        statusNotice = if (launched) "Installer opened" else "Enable 'Install unknown apps', then tap Install"
    }

    // Reusable panel renderer shared across Phone overlay, Portrait Sheet, and Landscape Inspector
    val renderInspectorContent: @Composable (InspectorTab) -> Unit = { tab ->
        AnimatedContent(
            targetState = tab,
            transitionSpec = { MotionTokens.panelTransition() },
            label = "InspectorContent",
            modifier = Modifier.fillMaxSize(),
        ) { active ->
            when (active) {
                InspectorTab.AGENTS -> AgentsWorkspacePanel(
                    sessions = crdtSessions,
                    siteId = crdtDatabase.siteId,
                    onLaunchSafeCommand = dispatchCommandToPty,
                    onNewContext = {
                        scope.launch {
                            crdtDatabase.upsertSession(
                                title = "Agent Session ${crdtSessions.size + 1}",
                                agentType = "agy",
                                compactedSummary = "Local-first sovereign context ready for P2P delta sync",
                                contextTokens = 1024,
                            )
                            statusNotice = "Created local context"
                        }
                    },
                    onExportDeltas = {
                        scope.launch {
                            val dir = File(context.filesDir, "crdt-deltas")
                            val out = crdtDatabase.exportDeltaBundleToFile(dir)
                            statusNotice = "Exported ${out.name}"
                        }
                    },
                    onArchiveContext = { id ->
                        scope.launch {
                            crdtDatabase.softDeleteSession(id)
                            statusNotice = "Archived session"
                        }
                    },
                )
                InspectorTab.SHIELD -> ShieldAndVaultPanel(
                    shieldScore = shieldScore,
                    bootstrapReady = bootstrapReady || liveSessions.isNotEmpty(),
                    guardianStatus = guardianStatus,
                    tokenState = tokenState,
                    installedReleaseTag = releaseUpdater.installedTag,
                    updateState = updateState,
                    hasPrivateRepoToken = releaseUpdater.hasPrivateRepoToken(),
                    onCheckUpdates = {
                        scope.launch { releaseUpdater.checkForUpdate(silentOnNetworkError = false) }
                    },
                    onDownloadUpdate = { rel ->
                        scope.launch { releaseUpdater.downloadAndVerifyUpdate(rel) }
                    },
                    onInstallApk = onInstallReadyApk,
                    onOpenOtaTokenDialog = { isOtaTokenDialogOpen = true },
                    onOpenReleasesBrowser = { releaseUpdater.openReleasesInBrowser(context) },
                    onToggleWakeLock = { ProcessGuardianService.toggleLocks(context) },
                    onRequestBatteryExclusion = { ProcessGuardianService.requestIgnoreBatteryOptimizations(context) },
                    onOpenAutostart = { ProcessGuardianService.openHyperOsAutostartSettings(context) },
                    onCopyPhantomAdbFix = {
                        clipboard.setText(AnnotatedString(guardianStatus.adbPhantomFixCommand))
                        statusNotice = "Copied ADB phantom fix"
                    },
                    onOpenTokenDialog = { isTokenDialogOpen = true },
                )
                InspectorTab.STORAGE -> StorageBackupsPanel(
                    metrics = storageMetrics,
                    onRunCleanup = {
                        scope.launch {
                            storageMetrics = storageOptimizer.runMelcatGarbageCollection()
                            statusNotice = storageMetrics.lastGcSummary
                        }
                    },
                    onCreateBackup = {
                        scope.launch {
                            storageMetrics = storageOptimizer.createPortableZstdSnapshot()
                            statusNotice = storageMetrics.lastGcSummary
                        }
                    },
                )
            }
        }
    }

    // Root container — Obsidian canvas + non-intrusive OTA banner when newer GitHub Release exists
    Surface(
        color = WorkstationColors.VoidCanvas,
        modifier = Modifier.fillMaxSize(),
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            when (formFactor) {
                WorkstationFormFactor.PHONE_PORTRAIT -> {
                    PhonePortraitLayout(
                        refreshHz = refreshHzInt,
                        liveSessions = liveSessions,
                        activeSessionIndex = activeSessionIndex,
                        primaryTab = primaryTab,
                        isImeVisible = isImeVisible,
                        hasHardwareKeyboard = hasHardwareKeyboard,
                        surfaceBridge = surfaceBridge,
                        shieldScore = shieldScore,
                        tokenPresent = tokenState.isPresent,
                        freeStorage = storageMetrics.freeFormatted,
                        statusNotice = statusNotice,
                        onSelectSession = { activeSessionIndex = it },
                        onAddSession = { spawnNewPtySession() },
                        onSelectTab = { primaryTab = it },
                        onOpenCommandPalette = { isCommandPaletteOpen = true },
                        onOpenClassicTermux = openClassicTermux,
                        onQuickCommand = dispatchCommandToPty,
                        renderInspector = renderInspectorContent,
                    )
                }

                WorkstationFormFactor.PHONE_LANDSCAPE -> {
                    PhoneLandscapeLayout(
                        refreshHz = refreshHzInt,
                        liveSessions = liveSessions,
                        activeSessionIndex = activeSessionIndex,
                        primaryTab = primaryTab,
                        hasHardwareKeyboard = hasHardwareKeyboard,
                        surfaceBridge = surfaceBridge,
                        onSelectSession = { activeSessionIndex = it },
                        onAddSession = { spawnNewPtySession() },
                        onSelectTab = { primaryTab = it },
                        onOpenCommandPalette = { isCommandPaletteOpen = true },
                        onOpenClassicTermux = openClassicTermux,
                        onQuickCommand = dispatchCommandToPty,
                        renderInspector = renderInspectorContent,
                    )
                }

                WorkstationFormFactor.TABLET_PORTRAIT -> {
                    TabletPortraitLayout(
                        refreshHz = refreshHzInt,
                        hasHardwareKeyboard = hasHardwareKeyboard,
                        liveSessions = liveSessions,
                        activeSessionIndex = activeSessionIndex,
                        inspectorTab = inspectorTab,
                        isSheetOpen = isPortraitSheetOpen,
                        surfaceBridge = surfaceBridge,
                        crdtSiteId = crdtDatabase.siteId,
                        tokenState = tokenState,
                        guardianStatus = guardianStatus,
                        storageMetrics = storageMetrics,
                        statusNotice = statusNotice,
                        onSelectSession = { activeSessionIndex = it },
                        onAddSession = { spawnNewPtySession() },
                        onToggleSheet = { isPortraitSheetOpen = !isPortraitSheetOpen },
                        onSelectInspectorTab = { inspectorTab = it },
                        onOpenCommandPalette = { isCommandPaletteOpen = true },
                        onOpenClassicTermux = openClassicTermux,
                        onQuickCommand = dispatchCommandToPty,
                        renderInspector = renderInspectorContent,
                    )
                }

                WorkstationFormFactor.TABLET_LANDSCAPE -> {
                    TabletLandscapeLayout(
                        refreshHz = refreshHzInt,
                        hasHardwareKeyboard = hasHardwareKeyboard,
                        liveSessions = liveSessions,
                        activeSessionIndex = activeSessionIndex,
                        inspectorTab = inspectorTab,
                        isZenMode = isZenMode,
                        surfaceBridge = surfaceBridge,
                        crdtSiteId = crdtDatabase.siteId,
                        tokenState = tokenState,
                        guardianStatus = guardianStatus,
                        storageMetrics = storageMetrics,
                        statusNotice = statusNotice,
                        onSelectSession = { activeSessionIndex = it },
                        onAddSession = { spawnNewPtySession() },
                        onSelectInspectorTab = {
                            inspectorTab = it
                            isZenMode = false
                        },
                        onToggleZenMode = {
                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            isZenMode = !isZenMode
                            statusNotice = if (isZenMode) "Zen Mode · Ctrl+Shift+B" else "Split-Pane Restored"
                        },
                        onOpenCommandPalette = { isCommandPaletteOpen = true },
                        onOpenClassicTermux = openClassicTermux,
                        onQuickCommand = dispatchCommandToPty,
                        renderInspector = renderInspectorContent,
                    )
                }
            }

            // Sleek OTA Update floating pill (visible ONLY when a newer GitHub Release is detected)
            OtaFloatingBanner(
                updateState = updateState,
                onDownload = { rel -> scope.launch { releaseUpdater.downloadAndVerifyUpdate(rel) } },
                onInstall = onInstallReadyApk,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .windowInsetsPadding(WindowInsets.statusBars.only(WindowInsetsSides.Top))
                    .padding(top = 56.dp, start = 16.dp, end = 16.dp),
            )
        }
    }

    if (isCommandPaletteOpen) {
        SearchableCommandPaletteDialog(
            isZenMode = isZenMode,
            onDismiss = { isCommandPaletteOpen = false },
            onExecuteShellCommand = { cmd ->
                dispatchCommandToPty(cmd)
                isCommandPaletteOpen = false
            },
            onNewPtySession = {
                spawnNewPtySession()
                isCommandPaletteOpen = false
            },
            onToggleZen = {
                isZenMode = !isZenMode
                isCommandPaletteOpen = false
            },
            onOpenTokenVault = {
                isCommandPaletteOpen = false
                isTokenDialogOpen = true
            },
            onCheckAppUpdate = {
                isCommandPaletteOpen = false
                statusNotice = "Checking GitHub Releases…"
                scope.launch { releaseUpdater.checkForUpdate(silentOnNetworkError = false) }
            },
        )
    }

    if (isTokenDialogOpen) {
        TokenVaultDialog(
            currentState = tokenState,
            onDismiss = { isTokenDialogOpen = false },
            onSaveToken = { rawInput ->
                scope.launch {
                    tokenWarden.provisionOrSyncToken(rawInput).onSuccess { updated ->
                        tokenState = updated
                        statusNotice = "Vault locked (${updated.posixModeOctal})"
                        isTokenDialogOpen = false
                    }.onFailure { err ->
                        statusNotice = err.message ?: "Invalid token"
                    }
                }
            },
            onRevokeToken = {
                scope.launch {
                    tokenState = tokenWarden.revokeLocalToken()
                    statusNotice = "Vault token cleared"
                    isTokenDialogOpen = false
                }
            },
        )
    }

    if (isOtaTokenDialogOpen) {
        PrivateRepoOtaTokenDialog(
            hasExistingToken = releaseUpdater.hasPrivateRepoToken(),
            onDismiss = { isOtaTokenDialogOpen = false },
            onSaveToken = { pat ->
                scope.launch {
                    releaseUpdater.savePrivateRepoToken(pat)
                    isOtaTokenDialogOpen = false
                    statusNotice = "GitHub OTA token saved (0600)"
                    releaseUpdater.checkForUpdate(silentOnNetworkError = false)
                }
            },
            onClearToken = {
                scope.launch {
                    releaseUpdater.savePrivateRepoToken("")
                    isOtaTokenDialogOpen = false
                    statusNotice = "GitHub OTA token cleared"
                }
            },
        )
    }
}

private data class LivePtyTab(
    val index: Int,
    val title: String,
    val isRunning: Boolean,
)

private fun computeShieldScore(
    bootstrapReady: Boolean,
    guardianStatus: GuardianStatus,
    tokenState: OAuthTokenState,
): Int {
    var score = 0
    if (bootstrapReady) score += 25
    if (guardianStatus.wakeLockHeld && guardianStatus.wifiLockHeld) score += 25
    if (guardianStatus.batteryOptimizationIgnored) score += 25
    if (tokenState.isPresent || guardianStatus.phantomShielded) score += 25
    return score.coerceIn(0, 100)
}

// ============================================================================================
// LAYOUT 1: PHONE PORTRAIT (360-599dp wide, vertical)
// ============================================================================================
@Composable
private fun PhonePortraitLayout(
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
    onSelectSession: (Int) -> Unit,
    onAddSession: () -> Unit,
    onSelectTab: (PrimaryTab) -> Unit,
    onOpenCommandPalette: () -> Unit,
    onOpenClassicTermux: () -> Unit,
    onQuickCommand: (String) -> Unit,
    renderInspector: @Composable (InspectorTab) -> Unit,
) {
    val haptics = LocalHapticFeedback.current

    Column(modifier = Modifier.fillMaxSize()) {
        // Strict single-row 52dp header + statusBarsPadding (impossible to explode vertically!)
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
                        .padding(horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(
                        imageVector = WorkstationIcons.TerminalPrompt,
                        contentDescription = null,
                        tint = WorkstationColors.AccentCyan,
                        modifier = Modifier.size(20.dp),
                    )
                    Text(
                        text = "Terminal Agents",
                        color = WorkstationColors.TextPrimary,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        softWrap = false,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    CompactStatusPill(
                        label = "${refreshHz}Hz",
                        healthy = liveSessions.isNotEmpty(),
                    )
                    CompactIconAction(
                        icon = WorkstationIcons.CommandSpark,
                        contentDescription = "Command Palette",
                        onClick = {
                            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            onOpenCommandPalette()
                        },
                    )
                    CompactIconAction(
                        icon = WorkstationIcons.ClassicShell,
                        contentDescription = "Classic Shell",
                        onClick = {
                            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            onOpenClassicTermux()
                        },
                    )
                }
                HorizontalDivider(thickness = 1.dp, color = WorkstationColors.BorderHairline)
            }
        }

        // Main Workspace Area — imePadding lifts Extra-Keys Dock right above soft keyboard
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .imePadding(),
        ) {
            // Base layer: Always-mounted Terminal viewport (zero reparenting crashes!)
            Column(modifier = Modifier.fillMaxSize()) {
                SessionTabStrip(
                    sessions = liveSessions,
                    activeIndex = activeSessionIndex,
                    compact = false,
                    onSelect = onSelectSession,
                    onAdd = onAddSession,
                )
                TerminalCanvasHost(
                    surfaceBridge = surfaceBridge,
                    visible = primaryTab == PrimaryTab.TERMINAL,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                )
                if (!hasHardwareKeyboard && primaryTab == PrimaryTab.TERMINAL) {
                    TactileExtraKeysDock(
                        surfaceBridge = surfaceBridge,
                        slim = false,
                        onQuickCommand = onQuickCommand,
                    )
                }
            }

            // Opaque panel overlay for Agents / Shield / Storage tabs
            if (primaryTab != PrimaryTab.TERMINAL) {
                Surface(
                    color = WorkstationColors.VoidCanvas,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                ) {
                    val mapped = when (primaryTab) {
                        PrimaryTab.AGENTS -> InspectorTab.AGENTS
                        PrimaryTab.SHIELD -> InspectorTab.SHIELD
                        PrimaryTab.STORAGE -> InspectorTab.STORAGE
                        PrimaryTab.TERMINAL -> InspectorTab.AGENTS
                    }
                    renderInspector(mapped)
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
                    leftText = "PTY:${liveSessions.size} · Shield:${shieldScore}% · ${if (tokenPresent) "Vault:ON" else "Vault:OFF"} · $freeStorage",
                    rightNotice = statusNotice,
                    compactPhone = true,
                )
                HorizontalDivider(thickness = 1.dp, color = WorkstationColors.BorderHairline)
                NavigationBar(
                    containerColor = WorkstationColors.SurfaceBase,
                    tonalElevation = 0.dp,
                    modifier = Modifier.navigationBarsPadding(),
                ) {
                    PrimaryTab.entries.forEach { tab ->
                        val selected = primaryTab == tab
                        NavigationBarItem(
                            selected = selected,
                            onClick = {
                                haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                onSelectTab(tab)
                            },
                            icon = {
                                Icon(
                                    imageVector = tabIcon(tab),
                                    contentDescription = tab.label,
                                    modifier = Modifier.size(20.dp),
                                )
                            },
                            label = {
                                Text(
                                    text = tab.label,
                                    fontSize = 11.sp,
                                    maxLines = 1,
                                    softWrap = false,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            },
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = WorkstationColors.AccentCyan,
                                selectedTextColor = WorkstationColors.AccentCyan,
                                unselectedIconColor = WorkstationColors.TextSecondary,
                                unselectedTextColor = WorkstationColors.TextSecondary,
                                indicatorColor = WorkstationColors.AccentCyanWash,
                            ),
                        )
                    }
                }
            }
        }
    }
}

// ============================================================================================
// LAYOUT 2: PHONE LANDSCAPE (height < 500dp, width > height — Left NavigationRail + 38dp Top)
// ============================================================================================
@Composable
private fun PhoneLandscapeLayout(
    refreshHz: Int,
    liveSessions: List<LivePtyTab>,
    activeSessionIndex: Int,
    primaryTab: PrimaryTab,
    hasHardwareKeyboard: Boolean,
    surfaceBridge: TermuxViewSurfaceBridge,
    onSelectSession: (Int) -> Unit,
    onAddSession: () -> Unit,
    onSelectTab: (PrimaryTab) -> Unit,
    onOpenCommandPalette: () -> Unit,
    onOpenClassicTermux: () -> Unit,
    onQuickCommand: (String) -> Unit,
    renderInspector: @Composable (InspectorTab) -> Unit,
) {
    val haptics = LocalHapticFeedback.current

    Row(
        modifier = Modifier
            .fillMaxSize()
            .displayCutoutPadding()
            .navigationBarsPadding(),
    ) {
        // Slim 56dp Left NavigationRail (ZERO bottom bar eating landscape vertical lines!)
        NavigationRail(
            containerColor = WorkstationColors.SurfaceBase,
            modifier = Modifier
                .width(56.dp)
                .fillMaxHeight(),
        ) {
            Spacer(modifier = Modifier.height(6.dp))
            PrimaryTab.entries.forEach { tab ->
                NavigationRailItem(
                    selected = primaryTab == tab,
                    onClick = {
                        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        onSelectTab(tab)
                    },
                    icon = {
                        Icon(
                            imageVector = tabIcon(tab),
                            contentDescription = tab.label,
                            modifier = Modifier.size(20.dp),
                        )
                    },
                    alwaysShowLabel = false,
                    colors = NavigationRailItemDefaults.colors(
                        selectedIconColor = WorkstationColors.AccentCyan,
                        unselectedIconColor = WorkstationColors.TextSecondary,
                        indicatorColor = WorkstationColors.AccentCyanWash,
                    ),
                )
            }
        }

        VerticalDivider(thickness = 1.dp, color = WorkstationColors.BorderHairline)

        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                .imePadding(),
        ) {
            // Merged 38dp Landscape Strip (Session Tabs + Actions in ONE ultra-compact strip!)
            Surface(
                color = WorkstationColors.SurfaceBase,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
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
                        modifier = Modifier.weight(1f),
                    )
                    CompactStatusPill(label = "${refreshHz}Hz", healthy = liveSessions.isNotEmpty())
                    CompactIconAction(
                        icon = WorkstationIcons.CommandSpark,
                        contentDescription = "Commands",
                        size = 30.dp,
                        onClick = onOpenCommandPalette,
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
                    .fillMaxWidth(),
            ) {
                Column(modifier = Modifier.fillMaxSize()) {
                    TerminalCanvasHost(
                        surfaceBridge = surfaceBridge,
                        visible = primaryTab == PrimaryTab.TERMINAL,
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth(),
                    )
                    if (!hasHardwareKeyboard && primaryTab == PrimaryTab.TERMINAL) {
                        TactileExtraKeysDock(
                            surfaceBridge = surfaceBridge,
                            slim = true,
                            onQuickCommand = onQuickCommand,
                        )
                    }
                }

                if (primaryTab != PrimaryTab.TERMINAL) {
                    Surface(
                        color = WorkstationColors.VoidCanvas,
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                    ) {
                        val mapped = when (primaryTab) {
                            PrimaryTab.AGENTS -> InspectorTab.AGENTS
                            PrimaryTab.SHIELD -> InspectorTab.SHIELD
                            PrimaryTab.STORAGE -> InspectorTab.STORAGE
                            PrimaryTab.TERMINAL -> InspectorTab.AGENTS
                        }
                        renderInspector(mapped)
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
private fun TabletPortraitLayout(
    refreshHz: Int,
    hasHardwareKeyboard: Boolean,
    liveSessions: List<LivePtyTab>,
    activeSessionIndex: Int,
    inspectorTab: InspectorTab,
    isSheetOpen: Boolean,
    surfaceBridge: TermuxViewSurfaceBridge,
    crdtSiteId: String,
    tokenState: OAuthTokenState,
    guardianStatus: GuardianStatus,
    storageMetrics: StorageMetrics,
    statusNotice: String,
    onSelectSession: (Int) -> Unit,
    onAddSession: () -> Unit,
    onToggleSheet: () -> Unit,
    onSelectInspectorTab: (InspectorTab) -> Unit,
    onOpenCommandPalette: () -> Unit,
    onOpenClassicTermux: () -> Unit,
    onQuickCommand: (String) -> Unit,
    renderInspector: @Composable (InspectorTab) -> Unit,
) {
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
                    Icon(
                        imageVector = WorkstationIcons.TerminalPrompt,
                        contentDescription = null,
                        tint = WorkstationColors.AccentCyan,
                        modifier = Modifier.size(20.dp),
                    )
                    Text(
                        text = "Terminal Agents",
                        color = WorkstationColors.TextPrimary,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    CompactStatusPill(
                        label = "${refreshHz}Hz · ${if (hasHardwareKeyboard) "KB" else "Touch"}",
                        healthy = liveSessions.isNotEmpty(),
                    )
                    CompactKeycapButton(label = "⌘K", onClick = onOpenCommandPalette)
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
        )

        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .imePadding(),
        ) {
            TerminalCanvasHost(
                surfaceBridge = surfaceBridge,
                visible = true,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
            )
            if (!hasHardwareKeyboard) {
                TactileExtraKeysDock(
                    surfaceBridge = surfaceBridge,
                    slim = false,
                    onQuickCommand = onQuickCommand,
                )
            }
        }

        CompactTelemetryBar(
            leftText = "PTY:${liveSessions.size} · Node:$crdtSiteId · Vault:${if (tokenState.isPresent) tokenState.posixModeOctal else "OFF"} · Wake:${if (guardianStatus.wakeLockHeld) "ON" else "OFF"} · Free:${storageMetrics.freeFormatted}",
            rightNotice = statusNotice,
            compactPhone = false,
            modifier = Modifier.navigationBarsPadding(),
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
                    .heightIn(min = 340.dp, max = 560.dp)
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
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

// ============================================================================================
// LAYOUT 4: TABLET LANDSCAPE (width >= 840dp — Xiaomi Pad 6 120Hz Desktop + Zen Mode ^⇧B)
// ============================================================================================
@Composable
private fun TabletLandscapeLayout(
    refreshHz: Int,
    hasHardwareKeyboard: Boolean,
    liveSessions: List<LivePtyTab>,
    activeSessionIndex: Int,
    inspectorTab: InspectorTab,
    isZenMode: Boolean,
    surfaceBridge: TermuxViewSurfaceBridge,
    crdtSiteId: String,
    tokenState: OAuthTokenState,
    guardianStatus: GuardianStatus,
    storageMetrics: StorageMetrics,
    statusNotice: String,
    onSelectSession: (Int) -> Unit,
    onAddSession: () -> Unit,
    onSelectInspectorTab: (InspectorTab) -> Unit,
    onToggleZenMode: () -> Unit,
    onOpenCommandPalette: () -> Unit,
    onOpenClassicTermux: () -> Unit,
    onQuickCommand: (String) -> Unit,
    renderInspector: @Composable (InspectorTab) -> Unit,
) {
    val inspectorWidth by animateDpAsState(
        targetValue = if (isZenMode) 0.dp else 360.dp,
        animationSpec = MotionTokens.PaneWidthSpring,
        label = "ZenInspectorWidth",
    )

    Column(modifier = Modifier.fillMaxSize()) {
        // Unified 48dp Desktop Titlebar (Brand + Live Session Tabs + Actions)
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
                    Icon(
                        imageVector = WorkstationIcons.TerminalPrompt,
                        contentDescription = null,
                        tint = WorkstationColors.AccentCyan,
                        modifier = Modifier.size(20.dp),
                    )
                    Text(
                        text = "Agents",
                        color = WorkstationColors.TextPrimary,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        softWrap = false,
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
                .fillMaxWidth()
                .imePadding(),
        ) {
            // Left Primary Terminal Canvas (expands to 100% smoothly in Zen Mode)
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight(),
            ) {
                TerminalCanvasHost(
                    surfaceBridge = surfaceBridge,
                    visible = true,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                )
                if (!hasHardwareKeyboard) {
                    TactileExtraKeysDock(
                        surfaceBridge = surfaceBridge,
                        slim = false,
                        onQuickCommand = onQuickCommand,
                    )
                }
            }

            // Right Collapsible Inspector Panel (360dp <-> 0dp)
            if (inspectorWidth > 2.dp) {
                VerticalDivider(thickness = 1.dp, color = WorkstationColors.BorderHairline)
                Column(
                    modifier = Modifier
                        .width(inspectorWidth)
                        .fillMaxHeight()
                        .background(WorkstationColors.SurfaceBase)
                        .padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
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

        CompactTelemetryBar(
            leftText = "PTY:${liveSessions.size} · Node:$crdtSiteId · Vault:${if (tokenState.isPresent) tokenState.posixModeOctal else "OFF"} · Wake:${if (guardianStatus.wakeLockHeld) "ON" else "OFF"} · Free:${storageMetrics.freeFormatted}",
            rightNotice = statusNotice,
            compactPhone = false,
            modifier = Modifier.navigationBarsPadding(),
        )
    }
}

// ============================================================================================
// SHARED PREMIUM COMPONENTS
// ============================================================================================
@Composable
private fun TerminalCanvasHost(
    surfaceBridge: TermuxViewSurfaceBridge,
    visible: Boolean,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier.background(WorkstationColors.TerminalBackdrop),
    ) {
        AndroidView(
            factory = {
                val view = surfaceBridge.terminalView
                (view.parent as? ViewGroup)?.removeView(view)
                view
            },
            update = { view ->
                view.visibility = if (visible) View.VISIBLE else View.INVISIBLE
                if (visible) {
                    view.onScreenUpdated()
                }
            },
            onRelease = { view ->
                (view.parent as? ViewGroup)?.removeView(view)
            },
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 6.dp, vertical = 4.dp),
        )
    }
}

@Composable
private fun SessionTabStrip(
    sessions: List<LivePtyTab>,
    activeIndex: Int,
    compact: Boolean,
    onSelect: (Int) -> Unit,
    onAdd: () -> Unit,
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
                modifier = Modifier
                    .fillMaxWidth()
                    .height(if (compact) 32.dp else 36.dp)
                    .padding(horizontal = 10.dp),
            )
            HorizontalDivider(thickness = 1.dp, color = WorkstationColors.BorderHairline)
        }
    }
}

@Composable
private fun SessionTabStripRow(
    sessions: List<LivePtyTab>,
    activeIndex: Int,
    compact: Boolean,
    onSelect: (Int) -> Unit,
    onAdd: () -> Unit,
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
                    )
                }
            }
        } else {
            sessions.forEach { tab ->
                val isSelected = tab.index == activeIndex
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
                        modifier = Modifier.padding(horizontal = 9.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
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
private fun TactileExtraKeysDock(
    surfaceBridge: TermuxViewSurfaceBridge,
    slim: Boolean,
    onQuickCommand: (String) -> Unit,
) {
    val haptics = LocalHapticFeedback.current
    val keyHeight = if (slim) 27.dp else 32.dp

    Surface(
        color = WorkstationColors.SurfaceBase,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column {
            HorizontalDivider(thickness = 1.dp, color = WorkstationColors.BorderHairline)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 6.dp, vertical = if (slim) 4.dp else 5.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(modifier = Modifier.weight(1f)) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState())
                            .padding(end = 18.dp),
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
                    }

                    // Subtle right fade hint showing dock is scrollable
                    Box(
                        modifier = Modifier
                            .align(Alignment.CenterEnd)
                            .width(18.dp)
                            .height(keyHeight)
                            .background(
                                Brush.horizontalGradient(
                                    colors = listOf(Color.Transparent, WorkstationColors.SurfaceBase)
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
    height: androidx.compose.ui.unit.Dp,
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
            )
        }
    }
}

@Composable
private fun CompactTelemetryBar(
    leftText: String,
    rightNotice: String,
    compactPhone: Boolean,
    modifier: Modifier = Modifier,
) {
    Surface(
        color = WorkstationColors.VoidCanvas,
        modifier = modifier.fillMaxWidth(),
    ) {
        Column {
            HorizontalDivider(thickness = 1.dp, color = WorkstationColors.BorderHairline)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
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
private fun InspectorSegmentedHeader(
    selected: InspectorTab,
    onSelect: (InspectorTab) -> Unit,
) {
    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
        InspectorTab.entries.forEachIndexed { idx, tab ->
            SegmentedButton(
                selected = selected == tab,
                onClick = { onSelect(tab) },
                shape = SegmentedButtonDefaults.itemShape(index = idx, count = InspectorTab.entries.size),
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
private fun CompactStatusPill(
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
            )
        }
    }
}

@Composable
private fun CompactKeycapButton(
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
            )
        }
    }
}

@Composable
private fun CompactIconAction(
    icon: ImageVector,
    contentDescription: String,
    highlighted: Boolean = false,
    size: androidx.compose.ui.unit.Dp = 34.dp,
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

private fun tabIcon(tab: PrimaryTab): ImageVector = when (tab) {
    PrimaryTab.TERMINAL -> WorkstationIcons.TerminalPrompt
    PrimaryTab.AGENTS -> WorkstationIcons.AgentsLayers
    PrimaryTab.SHIELD -> WorkstationIcons.ShieldGuard
    PrimaryTab.STORAGE -> WorkstationIcons.StorageVault
}

// ============================================================================================
// INSPECTOR PANELS (COMMERCIAL BUYER-READY POLISH)
// ============================================================================================
@Composable
private fun AgentsWorkspacePanel(
    sessions: List<AgentSessionRecord>,
    siteId: String,
    onLaunchSafeCommand: (String) -> Unit,
    onNewContext: () -> Unit,
    onExportDeltas: () -> Unit,
    onArchiveContext: (String) -> Unit,
) {
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
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Button(
                        onClick = onNewContext,
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp),
                        modifier = Modifier.weight(1f),
                    ) {
                        Text("New Workspace", fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    OutlinedButton(
                        onClick = onExportDeltas,
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp),
                        border = BorderStroke(1.dp, WorkstationColors.BorderHairline),
                        modifier = Modifier.weight(1f),
                    ) {
                        Text("Sync P2P", fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
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
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                                border = BorderStroke(1.dp, WorkstationColors.BorderStrong),
                                modifier = Modifier.weight(1f),
                            ) {
                                Text("Open in Terminal", fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            TextButton(
                                onClick = { onArchiveContext(session.id) },
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                            ) {
                                Text("Archive", fontSize = 11.sp, color = WorkstationColors.AccentRose, maxLines = 1)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ShieldAndVaultPanel(
    shieldScore: Int,
    bootstrapReady: Boolean,
    guardianStatus: GuardianStatus,
    tokenState: OAuthTokenState,
    installedReleaseTag: String,
    updateState: UpdateState,
    hasPrivateRepoToken: Boolean,
    onCheckUpdates: () -> Unit,
    onDownloadUpdate: (com.termux.custom.updater.ReleaseInfo) -> Unit,
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
                        )
                        Text(
                            text = "$shieldScore%",
                            fontFamily = FontFamily.Monospace,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (shieldScore >= 75) WorkstationColors.AccentEmerald else WorkstationColors.AccentAmber,
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
                                text = if (guardianStatus.wakeLockHeld) "Release Locks" else "Hold Locks",
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
private fun StorageBackupsPanel(
    metrics: StorageMetrics,
    onRunCleanup: () -> Unit,
    onCreateBackup: () -> Unit,
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
            )
            Text(
                text = "Free: ${metrics.freeFormatted} (${metrics.usedPercent}% used) · Backups: ${metrics.snapshotsCount} · zstd: ${if (metrics.zstdAvailable) "Active" else "Gzip fallback"}",
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
                color = WorkstationColors.AccentEmerald,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
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
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp),
                    modifier = Modifier.weight(1f),
                ) {
                    Text("Clean Caches", fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                OutlinedButton(
                    onClick = onCreateBackup,
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

// ============================================================================================
// DIALOGS: SEARCHABLE COMMAND PALETTE & SECURE PASSWORD-MASKED VAULT DIALOG
// ============================================================================================
private data class PaletteAction(
    val title: String,
    val subtitle: String,
    val shortcut: String = "",
    val run: () -> Unit,
)

@Composable
private fun SearchableCommandPaletteDialog(
    isZenMode: Boolean,
    onDismiss: () -> Unit,
    onExecuteShellCommand: (String) -> Unit,
    onNewPtySession: () -> Unit,
    onToggleZen: () -> Unit,
    onOpenTokenVault: () -> Unit,
    onCheckAppUpdate: () -> Unit,
) {
    var query by remember { mutableStateOf("") }

    val actions = remember(isZenMode) {
        listOf(
            PaletteAction("agy", "Show native Antigravity workstation status & skills", "", { onExecuteShellCommand("agy") }),
            PaletteAction("agy setup", "Install native arm64 toolchain (git, node, zstd, zsh, tmux)", "", { onExecuteShellCommand("agy setup") }),
            PaletteAction("claude", "Launch Claude CLI interactive session", "", { onExecuteShellCommand("claude") }),
            PaletteAction("zellij", "Start Zellij terminal multiplexer", "", { onExecuteShellCommand("zellij") }),
            PaletteAction("New Terminal Session", "Spawn an isolated native PTY shell tab", "^⇧T", onNewPtySession),
            PaletteAction(if (isZenMode) "Exit Zen Mode" else "Enter Zen Mode", "Expand terminal canvas across full display", "^⇧B", onToggleZen),
            PaletteAction("Clean Caches (melcat-gc)", "Purge apt/pip/npm caches and zstd-compress old logs", "", { onExecuteShellCommand("melcat-gc") }),
            PaletteAction("Portable Snapshot", "Create secret-free tar.zst workspace archive", "", { onExecuteShellCommand("melcat-gc --snapshot") }),
            PaletteAction("OAuth Credentials Vault", "Provision or rotate 0600 OAuth token", "", onOpenTokenVault),
            PaletteAction("Check for App Update (OTA)", "Query GitHub Releases and verify SHA-256", "", onCheckAppUpdate),
        )
    }

    val filtered = remember(query, actions) {
        val q = query.trim().lowercase()
        if (q.isEmpty()) actions else actions.filter {
            it.title.lowercase().contains(q) || it.subtitle.lowercase().contains(q)
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
                    placeholder = { Text("Search commands, agents, actions…", fontSize = 13.sp) },
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
private fun OtaFloatingBanner(
    updateState: UpdateState,
    onDownload: (com.termux.custom.updater.ReleaseInfo) -> Unit,
    onInstall: (File) -> Unit,
    modifier: Modifier = Modifier,
) {
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
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        Button(
                            onClick = { onDownload(updateState.release) },
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                            modifier = Modifier.height(30.dp),
                        ) {
                            Text("Update", fontSize = 11.sp, maxLines = 1)
                        }
                    }
                    is UpdateState.Downloading -> {
                        Text(
                            text = "Downloading ${updateState.release.tagName} (${updateState.progressPercent}%)",
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp,
                            color = WorkstationColors.AccentCyan,
                            maxLines = 1,
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
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        Button(
                            onClick = { onInstall(updateState.apkFile) },
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                            modifier = Modifier.height(30.dp),
                        ) {
                            Text("Install", fontSize = 11.sp, maxLines = 1)
                        }
                    }
                    else -> Unit
                }
            }
        }
    }
}

@Composable
private fun TokenVaultDialog(
    currentState: OAuthTokenState,
    onDismiss: () -> Unit,
    onSaveToken: (String) -> Unit,
    onRevokeToken: () -> Unit,
) {
    var inputToken by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = WorkstationColors.SurfaceElevated,
        title = { Text("OAuth Credentials Vault", fontSize = 16.sp, fontWeight = FontWeight.SemiBold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = "Stored atomically in private app storage with POSIX 0600 permissions and inherited by CLI agents. Excluded from all backup snapshots.",
                    fontSize = 12.sp,
                    color = WorkstationColors.TextSecondary,
                )
                OutlinedTextField(
                    value = inputToken,
                    onValueChange = { inputToken = it },
                    label = { Text("OAuth Token") },
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
                onClick = { onSaveToken(inputToken) },
                enabled = inputToken.trim().length >= 8,
            ) {
                Text("Lock 0600 & Sync")
            }
        },
        dismissButton = {
            Row {
                if (currentState.isPresent) {
                    TextButton(
                        onClick = onRevokeToken,
                        colors = ButtonDefaults.textButtonColors(contentColor = WorkstationColors.AccentRose),
                    ) {
                        Text("Revoke")
                    }
                }
                TextButton(onClick = onDismiss) {
                    Text("Cancel")
                }
            }
        },
    )
}

@Composable
private fun PrivateRepoOtaTokenDialog(
    hasExistingToken: Boolean,
    onDismiss: () -> Unit,
    onSaveToken: (String) -> Unit,
    onClearToken: () -> Unit,
) {
    var inputPat by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = WorkstationColors.SurfaceElevated,
        title = { Text("Private GitHub OTA Token", fontSize = 16.sp, fontWeight = FontWeight.SemiBold) },
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
                    label = { Text("GitHub Read-Only PAT") },
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
                Text("Save & Check")
            }
        },
        dismissButton = {
            Row {
                if (hasExistingToken) {
                    TextButton(
                        onClick = onClearToken,
                        colors = ButtonDefaults.textButtonColors(contentColor = WorkstationColors.AccentRose),
                    ) {
                        Text("Clear")
                    }
                }
                TextButton(onClick = onDismiss) {
                    Text("Cancel")
                }
            }
        },
    )
}
