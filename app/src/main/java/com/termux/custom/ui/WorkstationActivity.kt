package com.termux.custom.ui

import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
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
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.termux.app.TermuxActivity
import com.termux.app.TermuxInstaller
import com.termux.app.TermuxService
import com.termux.custom.bridge.TermuxViewSurfaceBridge
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
    private var serviceBindError by mutableStateOf<String?>(null)
    private var pendingPanelRoute by mutableStateOf<String?>(null)

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
        // Prevent Android 10+ / HyperOS from drawing a contrasting scrim behind transparent bars.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.isStatusBarContrastEnforced = false
            window.isNavigationBarContrastEnforced = false
        }

        crdtDatabase = LocalFirstCrdtDatabase.getInstance(this)
        tokenWarden = AntigravityTokenWarden(this)
        storageOptimizer = StorageOptimizer()
        releaseUpdater = GitHubReleaseUpdater(this)
        bootstrapReady = CustomBootstrapProvisioner.isBootstrapInstalled()
        consumeIntentExtras(intent)

        startAndBindTermuxService()
        ProcessGuardianService.start(this)

        setContent {
            MaterialTheme(colorScheme = WorkstationDarkColorScheme) {
                WorkstationRoot(
                    activity = this,
                    termuxService = termuxService,
                    bootstrapReady = bootstrapReady,
                    serviceBindError = serviceBindError,
                    pendingPanelRoute = pendingPanelRoute,
                    onConsumePanelRoute = { pendingPanelRoute = null },
                    onEnsureBootstrap = { ensureBootstrapInstalled() },
                    onRetryBindService = { startAndBindTermuxService() },
                    crdtDatabase = crdtDatabase,
                    tokenWarden = tokenWarden,
                    storageOptimizer = storageOptimizer,
                    releaseUpdater = releaseUpdater,
                )
            }
        }
    }

    private fun startAndBindTermuxService() {
        val serviceIntent = Intent(this, TermuxService::class.java)
        serviceBindError = null
        val startResult = runCatching {
            // Match upstream TermuxActivity: startService while Activity is foreground,
            // falling back to startForegroundService if Android restricts background start.
            try {
                startService(serviceIntent)
            } catch (_: IllegalStateException) {
                ContextCompat.startForegroundService(this, serviceIntent)
            }
            var bound = bindService(serviceIntent, this, 0)
            if (!bound) {
                bound = bindService(serviceIntent, this, android.content.Context.BIND_AUTO_CREATE)
            }
            isServiceBound = bound
            if (!bound) {
                serviceBindError = "bindService(TermuxService) returned false"
            }
        }
        startResult.onFailure { err ->
            serviceBindError = "TermuxService: ${err.javaClass.simpleName}: ${err.message ?: "failed"}"
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        consumeIntentExtras(intent)
    }

    private fun consumeIntentExtras(intent: Intent?) {
        val route = intent?.getStringExtra(EXTRA_OPEN_PANEL)?.trim()?.lowercase().orEmpty()
        if (route.isNotEmpty()) {
            pendingPanelRoute = route
        }
    }

    private var isBootstrapInstalling = false

    private fun ensureBootstrapInstalled() {
        if (CustomBootstrapProvisioner.isBootstrapInstalled()) {
            isBootstrapInstalling = false
            bootstrapReady = true
            // Ensure upgraded CLI scripts (v4: agy login/doctor/upgrade) are provisioned on updates.
            CustomBootstrapProvisioner.runPostBootstrapProvisioningAsync(this)
            return
        }
        if (isBootstrapInstalling) return
        isBootstrapInstalling = true
        TermuxInstaller.setupBootstrapIfNeeded(this) {
            isBootstrapInstalling = false
            bootstrapReady = CustomBootstrapProvisioner.isBootstrapInstalled()
        }
    }

    override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
        val boundService = (service as? TermuxService.LocalBinder)?.service
        termuxService = boundService
        serviceBindError = if (boundService == null) "LocalBinder returned null service" else null
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

    companion object {
        const val EXTRA_OPEN_PANEL = "melcat.open_panel"
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun WorkstationRoot(
    activity: WorkstationActivity,
    termuxService: TermuxService?,
    bootstrapReady: Boolean,
    serviceBindError: String?,
    pendingPanelRoute: String?,
    onConsumePanelRoute: () -> Unit,
    onEnsureBootstrap: () -> Unit,
    onRetryBindService: () -> Unit,
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
    val snackbarHostState = remember { SnackbarHostState() }

    val formFactor = remember(configuration.screenWidthDp, configuration.screenHeightDp) {
        resolveFormFactor(configuration.screenWidthDp, configuration.screenHeightDp)
    }
    val hasHardwareKeyboard = configuration.keyboard != Configuration.KEYBOARD_NOKEYS &&
        configuration.hardKeyboardHidden == Configuration.HARDKEYBOARDHIDDEN_NO

    val isImeVisible = WindowInsets.ime.getBottom(density) > 0

    // Decoupled saveable state — synced across phone/tablet rotations
    var primaryTab by rememberSaveable { mutableStateOf(PrimaryTab.TERMINAL) }
    var inspectorTab by rememberSaveable { mutableStateOf(InspectorTab.AGENTS) }
    var isZenMode by rememberSaveable { mutableStateOf(false) }
    var isPortraitSheetOpen by rememberSaveable { mutableStateOf(false) }
    var activeSessionIndex by rememberSaveable { mutableIntStateOf(0) }
    var sessionListVersion by remember { mutableIntStateOf(0) }

    var isCommandPaletteOpen by rememberSaveable { mutableStateOf(false) }
    var isTokenDialogOpen by rememberSaveable { mutableStateOf(false) }
    var tokenDialogError by rememberSaveable { mutableStateOf<String?>(null) }
    var isOtaTokenDialogOpen by rememberSaveable { mutableStateOf(false) }
    var isTutorialOpen by rememberSaveable { mutableStateOf(false) }
    var completedTutorialSteps by remember {
        mutableStateOf(TutorialProgressStore.loadCompletedIds(context))
    }

    var isAgentsBusy by remember { mutableStateOf(false) }
    var isStorageBusy by remember { mutableStateOf(false) }
    var statusNotice by rememberSaveable { mutableStateOf("Native Engine Ready") }

    val crdtSessions by crdtDatabase.sessionsFlow.collectAsState()
    val guardianStatus by ProcessGuardianService.statusFlow.collectAsState()
    val updateState by releaseUpdater.state.collectAsState()
    val hasPrivateRepoToken by releaseUpdater.hasPrivateRepoTokenFlow.collectAsState()

    // Dismiss state resets automatically whenever a distinct updateState arrives
    val bannerKey = when (val s = updateState) {
        is UpdateState.UpdateAvailable -> "avail:${s.release.tagName}"
        is UpdateState.Downloading -> "dl:${s.release.tagName}"
        is UpdateState.ReadyToInstall -> "ready:${s.release.tagName}"
        else -> "none"
    }
    var isOtaBannerDismissed by rememberSaveable(bannerKey) { mutableStateOf(false) }

    fun notify(message: String, showSnackbar: Boolean = false) {
        statusNotice = message
        if (showSnackbar || formFactor == WorkstationFormFactor.PHONE_LANDSCAPE || isImeVisible) {
            scope.launch {
                snackbarHostState.currentSnackbarData?.dismiss()
                snackbarHostState.showSnackbar(
                    message = message,
                    duration = SnackbarDuration.Short,
                )
            }
        }
    }

    fun selectPrimaryTab(tab: PrimaryTab) {
        primaryTab = tab
        when (tab) {
            PrimaryTab.AGENTS -> inspectorTab = InspectorTab.AGENTS
            PrimaryTab.SHIELD -> inspectorTab = InspectorTab.SHIELD
            PrimaryTab.STORAGE -> inspectorTab = InspectorTab.STORAGE
            PrimaryTab.TERMINAL -> Unit
        }
    }

    fun navigateToInspectorTab(tab: InspectorTab) {
        inspectorTab = tab
        when (formFactor) {
            WorkstationFormFactor.PHONE_PORTRAIT,
            WorkstationFormFactor.PHONE_LANDSCAPE -> {
                primaryTab = when (tab) {
                    InspectorTab.AGENTS -> PrimaryTab.AGENTS
                    InspectorTab.SHIELD -> PrimaryTab.SHIELD
                    InspectorTab.STORAGE -> PrimaryTab.STORAGE
                }
            }
            WorkstationFormFactor.TABLET_PORTRAIT -> {
                isPortraitSheetOpen = true
            }
            WorkstationFormFactor.TABLET_LANDSCAPE -> {
                isZenMode = false
            }
        }
    }

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
        if (!bootstrapReady && !CustomBootstrapProvisioner.isBootstrapInstalled()) {
            onEnsureBootstrap()
            notify("Extracting arm64 Linux runtime…")
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
            notify("Opened session ${sessionName ?: "agy-$nextNum"}")
        }
    }

    fun closePtySession(index: Int) {
        val service = termuxService ?: return
        val list = service.termuxSessions
        if (index !in list.indices) return
        val target = list[index].terminalSession
        val label = target.mSessionName?.takeIf { it.isNotBlank() } ?: "pty-${index + 1}"
        runCatching {
            target.finishIfRunning()
            service.removeTermuxSession(target)
        }
        val remaining = service.termuxSessions.size
        activeSessionIndex = when {
            remaining == 0 -> 0
            index < activeSessionIndex -> (activeSessionIndex - 1).coerceAtLeast(0)
            else -> activeSessionIndex.coerceIn(0, remaining - 1)
        }
        sessionListVersion++
        notify("Closed $label", showSnackbar = true)
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
                notify(if (isZenMode) "Zen Mode · Ctrl+Shift+B" else "Inspector Restored")
            },
        )
    }

    // Back navigation: return to Terminal before exiting the activity
    BackHandler(enabled = isPortraitSheetOpen) {
        isPortraitSheetOpen = false
    }
    BackHandler(enabled = !isPortraitSheetOpen && primaryTab != PrimaryTab.TERMINAL) {
        primaryTab = PrimaryTab.TERMINAL
    }
    BackHandler(
        enabled = !isPortraitSheetOpen &&
            primaryTab == PrimaryTab.TERMINAL &&
            formFactor == WorkstationFormFactor.TABLET_LANDSCAPE &&
            isZenMode,
    ) {
        isZenMode = false
    }

    // Route `melcat.open_panel` extra from `agy ui`
    LaunchedEffect(pendingPanelRoute) {
        when (pendingPanelRoute) {
            "agents" -> navigateToInspectorTab(InspectorTab.AGENTS)
            "shield" -> navigateToInspectorTab(InspectorTab.SHIELD)
            "storage" -> navigateToInspectorTab(InspectorTab.STORAGE)
            "tutorial" -> isTutorialOpen = true
            "terminal" -> primaryTab = PrimaryTab.TERMINAL
        }
        if (pendingPanelRoute != null) onConsumePanelRoute()
    }

    // Wire live TerminalSessionClient so stdout/stderr/cursor/clipboard redraw in real time,
    // and reliably reclaim client & focus when returning from Classic TermuxActivity.
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
                    service?.setCustomTerminalSessionClient(client)
                    sessionListVersion++
                    surfaceBridge.setCursorBlinker(true)
                    surfaceBridge.apply120HzFramePacing(activity)
                    surfaceBridge.terminalView.onScreenUpdated()
                    if (primaryTab == PrimaryTab.TERMINAL &&
                        !isCommandPaletteOpen &&
                        !isTokenDialogOpen &&
                        !isOtaTokenDialogOpen &&
                        !isTutorialOpen
                    ) {
                        surfaceBridge.terminalView.post { surfaceBridge.terminalView.requestFocus() }
                    }
                    scope.launch {
                        ProcessGuardianService.refreshSnapshot(context)
                        tokenState = tokenWarden.inspectTokenState()
                        storageMetrics = storageOptimizer.inspectMetrics(storageMetrics.lastGcSummary)
                        releaseUpdater.refreshTokenState()
                    }
                }
                Lifecycle.Event.ON_PAUSE -> {
                    surfaceBridge.setCursorBlinker(false)
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

    var terminalBootError by remember { mutableStateOf<String?>(null) }

    // Initial async hydration and silent OTA check (never block cold-start terminal with a modal)
    LaunchedEffect(Unit) {
        surfaceBridge.apply120HzFramePacing(activity)
        crdtDatabase.seedDefaultsIfFirstRun()
        tokenState = tokenWarden.inspectTokenState()
        storageMetrics = storageOptimizer.inspectMetrics()
        releaseUpdater.refreshTokenState()
        ProcessGuardianService.refreshSnapshot(context)
        releaseUpdater.checkForUpdate(silentOnNetworkError = true)
    }

    // Attach active PTY session once TermuxService & atomic bootstrap are ready
    LaunchedEffect(termuxService, bootstrapReady, activeSessionIndex, sessionListVersion) {
        val service = termuxService ?: return@LaunchedEffect
        if (!bootstrapReady && !CustomBootstrapProvisioner.isBootstrapInstalled()) {
            onEnsureBootstrap()
            notify("Preparing arm64 Linux runtime…")
            return@LaunchedEffect
        }
        if (service.termuxSessions.isEmpty()) {
            val createResult = runCatching {
                service.createTermuxSession(
                    null,
                    null,
                    null,
                    TermuxConstants.TERMUX_HOME_DIR_PATH,
                    false,
                    "agy-main",
                )
            }
            val created = createResult.getOrNull()
            if (created != null) {
                terminalBootError = null
                sessionListVersion++
            } else {
                val errDetail = createResult.exceptionOrNull()?.let {
                    "${it.javaClass.simpleName}: ${it.message}"
                } ?: "createTermuxSession returned null"
                terminalBootError = errDetail
                notify("Terminal error: $errDetail", showSnackbar = true)
                return@LaunchedEffect
            }
        }
        val list = service.termuxSessions
        if (list.isNotEmpty()) {
            terminalBootError = null
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
        runCatching { context.startActivity(Intent(context, TermuxActivity::class.java)) }
            .onFailure { notify("Could not open Classic Termux", showSnackbar = true) }
        Unit
    }

    val dispatchCommandToPty: (String) -> Unit = { cmd ->
        surfaceBridge.sendCommandLine(cmd)
        if (formFactor == WorkstationFormFactor.PHONE_PORTRAIT || formFactor == WorkstationFormFactor.PHONE_LANDSCAPE) {
            primaryTab = PrimaryTab.TERMINAL
        }
        if (formFactor == WorkstationFormFactor.TABLET_PORTRAIT) {
            isPortraitSheetOpen = false
        }
        notify("Sent: ${cmd.take(28)}")
    }

    val onInstallReadyApk: (File) -> Unit = { apkFile ->
        val launched = releaseUpdater.launchApkInstaller(context, apkFile)
        notify(
            if (launched) "Installer opened" else "Enable 'Install unknown apps', then tap Install",
            showSnackbar = true,
        )
    }

    fun handleTutorialAction(step: TutorialStep) {
        isTutorialOpen = false
        when (step) {
            TutorialStep.ARM64_ENGINE_SETUP -> {
                onEnsureBootstrap()
                dispatchCommandToPty("agy setup && agy doctor")
            }
            TutorialStep.ACTIVATE_SKILLS -> {
                dispatchCommandToPty("agy skills")
            }
            TutorialStep.OAUTH_VAULT_0600 -> {
                navigateToInspectorTab(InspectorTab.SHIELD)
                tokenDialogError = null
                isTokenDialogOpen = true
            }
            TutorialStep.HYPEROS_PHANTOM_SHIELD -> {
                scope.launch { ProcessGuardianService.refreshSnapshot(context) }
                navigateToInspectorTab(InspectorTab.SHIELD)
            }
            TutorialStep.PTY_SESSIONS_SHORTCUTS -> {
                spawnNewPtySession()
            }
            TutorialStep.CRDT_WORKSPACES -> {
                navigateToInspectorTab(InspectorTab.AGENTS)
            }
            TutorialStep.STORAGE_ZSTD_SNAPSHOT -> {
                navigateToInspectorTab(InspectorTab.STORAGE)
            }
            TutorialStep.OTA_UPDATER -> {
                navigateToInspectorTab(InspectorTab.SHIELD)
                notify("Checking GitHub Releases…", showSnackbar = true)
                scope.launch { releaseUpdater.checkForUpdate(silentOnNetworkError = false) }
            }
        }
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
                    isBusy = isAgentsBusy,
                    onLaunchSafeCommand = dispatchCommandToPty,
                    onNewContext = { title, agentType, summary ->
                        scope.launch {
                            isAgentsBusy = true
                            try {
                                crdtDatabase.upsertSession(
                                    title = title.trim().ifEmpty { "Agent Session ${crdtSessions.size + 1}" },
                                    agentType = agentType,
                                    compactedSummary = summary.trim().ifEmpty {
                                        "Local-first sovereign context ready for P2P delta sync"
                                    },
                                    contextTokens = 1024,
                                )
                                notify("Created workspace '$title'", showSnackbar = true)
                            } finally {
                                isAgentsBusy = false
                            }
                        }
                    },
                    onExportDeltas = {
                        scope.launch {
                            isAgentsBusy = true
                            try {
                                val dir = File(context.filesDir, "crdt-deltas")
                                val out = crdtDatabase.exportDeltaBundleToFile(dir)
                                notify("Exported ${out.name}", showSnackbar = true)
                            } finally {
                                isAgentsBusy = false
                            }
                        }
                    },
                    onShareDeltas = {
                        scope.launch {
                            isAgentsBusy = true
                            try {
                                val json = crdtDatabase.exportDeltaBundleJson(0L)
                                val shareIntent = Intent(Intent.ACTION_SEND).apply {
                                    type = "application/json"
                                    putExtra(Intent.EXTRA_SUBJECT, "CRDT Deltas (${crdtDatabase.siteId})")
                                    putExtra(Intent.EXTRA_TEXT, json)
                                }
                                runCatching {
                                    context.startActivity(Intent.createChooser(shareIntent, "Share CRDT Deltas"))
                                }.onFailure {
                                    clipboard.setText(AnnotatedString(json))
                                    notify("Copied CRDT JSON to clipboard", showSnackbar = true)
                                }
                            } finally {
                                isAgentsBusy = false
                            }
                        }
                    },
                    onImportDeltas = { json ->
                        scope.launch {
                            isAgentsBusy = true
                            try {
                                val applied = crdtDatabase.mergeRemoteDeltaBundleJson(json)
                                notify(
                                    if (applied > 0) "Merged $applied remote delta(s)" else "No newer deltas found",
                                    showSnackbar = true,
                                )
                            } finally {
                                isAgentsBusy = false
                            }
                        }
                    },
                    onArchiveContext = { id ->
                        scope.launch {
                            isAgentsBusy = true
                            try {
                                crdtDatabase.softDeleteSession(id)
                                notify("Archived workspace", showSnackbar = true)
                            } finally {
                                isAgentsBusy = false
                            }
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
                    hasPrivateRepoToken = hasPrivateRepoToken,
                    onCheckUpdates = {
                        notify("Checking GitHub Releases…", showSnackbar = true)
                        scope.launch { releaseUpdater.checkForUpdate(silentOnNetworkError = false) }
                    },
                    onDownloadUpdate = { rel ->
                        scope.launch { releaseUpdater.downloadAndVerifyUpdate(rel) }
                    },
                    onInstallApk = onInstallReadyApk,
                    onOpenOtaTokenDialog = { isOtaTokenDialogOpen = true },
                    onOpenReleasesBrowser = { releaseUpdater.openReleasesInBrowser(context) },
                    onToggleWakeLock = {
                        ProcessGuardianService.toggleLocks(context)
                        notify(
                            if (guardianStatus.wakeLockHeld && guardianStatus.wifiLockHeld) {
                                "Releasing WakeLock & Wi-Fi lock…"
                            } else {
                                "Acquiring WakeLock & Wi-Fi lock…"
                            },
                            showSnackbar = true,
                        )
                    },
                    onRequestBatteryExclusion = {
                        ProcessGuardianService.requestIgnoreBatteryOptimizations(context)
                        notify("Opening Battery Optimization settings…", showSnackbar = true)
                    },
                    onOpenAutostart = {
                        ProcessGuardianService.openHyperOsAutostartSettings(context)
                        notify("Opening Autostart settings…", showSnackbar = true)
                    },
                    onCopyPhantomAdbFix = {
                        clipboard.setText(AnnotatedString(guardianStatus.adbPhantomFixCommand))
                        notify("Copied ADB phantom fix", showSnackbar = true)
                    },
                    onOpenTokenDialog = {
                        tokenDialogError = null
                        isTokenDialogOpen = true
                    },
                )
                InspectorTab.STORAGE -> StorageBackupsPanel(
                    metrics = storageMetrics,
                    onRunCleanup = {
                        scope.launch {
                            isStorageBusy = true
                            try {
                                storageMetrics = storageOptimizer.runMelcatGarbageCollection()
                                notify(storageMetrics.lastGcSummary, showSnackbar = true)
                            } finally {
                                isStorageBusy = false
                            }
                        }
                    },
                    onCreateBackup = {
                        scope.launch {
                            isStorageBusy = true
                            try {
                                storageMetrics = storageOptimizer.createPortableZstdSnapshot()
                                notify(storageMetrics.lastGcSummary, showSnackbar = true)
                            } finally {
                                isStorageBusy = false
                            }
                        }
                    },
                    isBusy = isStorageBusy,
                    onInstallToolchain = {
                        dispatchCommandToPty("agy setup && agy doctor")
                    },
                )
            }
        }
    }

    val bannerTopPadding = when (formFactor) {
        WorkstationFormFactor.PHONE_PORTRAIT,
        WorkstationFormFactor.TABLET_PORTRAIT -> 92.dp
        WorkstationFormFactor.TABLET_LANDSCAPE -> 54.dp
        WorkstationFormFactor.PHONE_LANDSCAPE -> 44.dp
    }

    // Root container — Obsidian canvas + dismissable OTA banner + SnackbarHost
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
                        onCloseSession = { closePtySession(it) },
                        onOpenTutorial = { isTutorialOpen = true },
                        onSelectTab = { selectPrimaryTab(it) },
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
                        onCloseSession = { closePtySession(it) },
                        onOpenTutorial = { isTutorialOpen = true },
                        onSelectTab = { selectPrimaryTab(it) },
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
                        onCloseSession = { closePtySession(it) },
                        onOpenTutorial = { isTutorialOpen = true },
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
                        onCloseSession = { closePtySession(it) },
                        onOpenTutorial = { isTutorialOpen = true },
                        onSelectInspectorTab = {
                            inspectorTab = it
                            isZenMode = false
                        },
                        onToggleZenMode = {
                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            isZenMode = !isZenMode
                            notify(if (isZenMode) "Zen Mode · Ctrl+Shift+B" else "Split-Pane Restored")
                        },
                        onOpenCommandPalette = { isCommandPaletteOpen = true },
                        onOpenClassicTermux = openClassicTermux,
                        onQuickCommand = dispatchCommandToPty,
                        renderInspector = renderInspectorContent,
                    )
                }
            }

            if (!isOtaBannerDismissed) {
                OtaFloatingBanner(
                    updateState = updateState,
                    onDownload = { rel -> scope.launch { releaseUpdater.downloadAndVerifyUpdate(rel) } },
                    onInstall = onInstallReadyApk,
                    onDismiss = { isOtaBannerDismissed = true },
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .windowInsetsPadding(
                            WindowInsets.statusBars
                                .union(WindowInsets.displayCutout)
                                .only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal)
                        )
                        .padding(top = bannerTopPadding, start = 16.dp, end = 16.dp),
                )
            }

            val bootError = serviceBindError ?: terminalBootError
            if (bootError != null && liveSessions.isEmpty()) {
                Surface(
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(14.dp),
                    color = WorkstationColors.SurfaceElevated,
                    border = androidx.compose.foundation.BorderStroke(1.dp, WorkstationColors.AccentRose),
                    shadowElevation = 8.dp,
                    modifier = Modifier
                        .align(Alignment.Center)
                        .padding(horizontal = 24.dp),
                ) {
                    androidx.compose.foundation.layout.Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(10.dp),
                    ) {
                        androidx.compose.material3.Text(
                            text = "Error al cargar la terminal",
                            color = WorkstationColors.AccentRose,
                            fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
                        )
                        androidx.compose.material3.Text(
                            text = bootError,
                            color = WorkstationColors.TextSecondary,
                            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                        )
                        androidx.compose.foundation.layout.Row(
                            horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp),
                        ) {
                            androidx.compose.material3.Button(
                                onClick = {
                                    terminalBootError = null
                                    if (termuxService == null) {
                                        onRetryBindService()
                                    } else {
                                        onEnsureBootstrap()
                                        spawnNewPtySession("agy-main")
                                    }
                                },
                            ) {
                                androidx.compose.material3.Text("Reintentar")
                            }
                            androidx.compose.material3.OutlinedButton(onClick = openClassicTermux) {
                                androidx.compose.material3.Text("Termux Clásico")
                            }
                        }
                    }
                }
            }

            SnackbarHost(
                hostState = snackbarHostState,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .windowInsetsPadding(
                        WindowInsets.ime
                            .union(WindowInsets.navigationBars)
                            .only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal)
                    )
                    .padding(bottom = if (formFactor == WorkstationFormFactor.PHONE_PORTRAIT && !isImeVisible) 88.dp else 32.dp),
            ) { data ->
                Snackbar(
                    snackbarData = data,
                    containerColor = WorkstationColors.SurfaceElevated,
                    contentColor = WorkstationColors.TextPrimary,
                )
            }
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
                tokenDialogError = null
                isTokenDialogOpen = true
            },
            onCheckAppUpdate = {
                isCommandPaletteOpen = false
                notify("Checking GitHub Releases…", showSnackbar = true)
                scope.launch { releaseUpdater.checkForUpdate(silentOnNetworkError = false) }
            },
            onOpenTutorial = {
                isCommandPaletteOpen = false
                isTutorialOpen = true
            },
        )
    }

    if (isTokenDialogOpen) {
        TokenVaultDialog(
            currentState = tokenState,
            onDismiss = {
                tokenDialogError = null
                isTokenDialogOpen = false
            },
            onSaveToken = { rawInput ->
                scope.launch {
                    tokenWarden.provisionOrSyncToken(rawInput).onSuccess { updated ->
                        tokenState = updated
                        tokenDialogError = null
                        notify("Vault locked (${updated.posixModeOctal})", showSnackbar = true)
                        isTokenDialogOpen = false
                    }.onFailure { err ->
                        tokenDialogError = err.message ?: "Invalid token"
                    }
                }
            },
            onRevokeToken = {
                scope.launch {
                    tokenState = tokenWarden.revokeLocalToken()
                    tokenDialogError = null
                    notify("Vault token cleared", showSnackbar = true)
                    isTokenDialogOpen = false
                }
            },
            errorText = tokenDialogError,
        )
    }

    if (isOtaTokenDialogOpen) {
        PrivateRepoOtaTokenDialog(
            hasExistingToken = hasPrivateRepoToken,
            onDismiss = { isOtaTokenDialogOpen = false },
            onSaveToken = { pat ->
                scope.launch {
                    releaseUpdater.savePrivateRepoToken(pat)
                    isOtaTokenDialogOpen = false
                    notify("GitHub OTA token saved (0600)", showSnackbar = true)
                    releaseUpdater.checkForUpdate(silentOnNetworkError = false)
                }
            },
            onClearToken = {
                scope.launch {
                    releaseUpdater.savePrivateRepoToken("")
                    isOtaTokenDialogOpen = false
                    notify("GitHub OTA token cleared", showSnackbar = true)
                }
            },
        )
    }

    FirstStepsTutorialSheet(
        visible = isTutorialOpen,
        completed = completedTutorialSteps,
        onAction = { step -> handleTutorialAction(step) },
        onToggleDone = { step, done ->
            completedTutorialSteps = TutorialProgressStore.setStepCompleted(context, step, done)
        },
        onDismiss = { isTutorialOpen = false },
    )
}

internal fun computeShieldScore(
    bootstrapReady: Boolean,
    guardianStatus: GuardianStatus,
    tokenState: OAuthTokenState,
): Int {
    var score = 0
    if (bootstrapReady) score += 20
    if (guardianStatus.wakeLockHeld && guardianStatus.wifiLockHeld) score += 20
    if (guardianStatus.batteryOptimizationIgnored) score += 20
    if (tokenState.isPresent) score += 20
    if (guardianStatus.phantomShielded) score += 20
    return score.coerceIn(0, 100)
}
