package com.termux.custom.ui

import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.content.res.Configuration
import android.net.Uri
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
import androidx.compose.runtime.rememberUpdatedState
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
import com.termux.custom.agents.CodingAgentsManager
import com.termux.custom.agents.CodingAgentsState
import com.termux.custom.bridge.TermuxViewSurfaceBridge
import com.termux.custom.data.LocalFirstCrdtDatabase
import com.termux.custom.guardian.GuardianStatus
import com.termux.custom.guardian.ProcessGuardianService
import com.termux.custom.provisioning.CustomBootstrapProvisioner
import com.termux.custom.rur.Rur2faSetupPayload
import com.termux.custom.rur.RurControlCatalog
import com.termux.custom.rur.RurControlCenterManager
import com.termux.custom.rur.RurControlState
import com.termux.custom.rur.RurModuleSpec
import com.termux.custom.rur.RurStackSpec
import com.termux.custom.security.AntigravityTokenWarden
import com.termux.custom.security.OAuthTokenState
import com.termux.custom.skills.SkillsRepoManager
import com.termux.custom.skills.SkillsSummaryState
import com.termux.custom.ssh.SshDeviceRecord
import com.termux.custom.ssh.SshDevicesManager
import com.termux.custom.ssh.SshPanelState
import com.termux.custom.storage.StorageMetrics
import com.termux.custom.storage.StorageOptimizer
import com.termux.custom.updater.ConflictingAddonInfo
import com.termux.custom.updater.GitHubReleaseUpdater
import com.termux.custom.updater.UpdateState
import com.termux.shared.termux.TermuxConstants
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class WorkstationActivity : ComponentActivity(), ServiceConnection {

    private var termuxService by mutableStateOf<TermuxService?>(null)
    private var isServiceBound = false
    private var bootstrapReady by mutableStateOf(false)
    private var bootstrapError by mutableStateOf<String?>(null)
    private var serviceBindError by mutableStateOf<String?>(null)
    private var pendingPanelRoute by mutableStateOf<String?>(null)

    private lateinit var crdtDatabase: LocalFirstCrdtDatabase
    private lateinit var tokenWarden: AntigravityTokenWarden
    private lateinit var codingAgentsManager: CodingAgentsManager
    private lateinit var skillsRepoManager: SkillsRepoManager
    private lateinit var sshDevicesManager: SshDevicesManager
    private lateinit var rurControlManager: RurControlCenterManager
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
        codingAgentsManager = CodingAgentsManager()
        skillsRepoManager = SkillsRepoManager()
        sshDevicesManager = SshDevicesManager(context = this)
        rurControlManager = RurControlCenterManager()
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
                    bootstrapError = bootstrapError,
                    serviceBindError = serviceBindError,
                    pendingPanelRoute = pendingPanelRoute,
                    onConsumePanelRoute = { pendingPanelRoute = null },
                    onEnsureBootstrap = { ensureBootstrapInstalled() },
                    onRetryBindService = { startAndBindTermuxService() },
                    crdtDatabase = crdtDatabase,
                    tokenWarden = tokenWarden,
                    codingAgentsManager = codingAgentsManager,
                    skillsRepoManager = skillsRepoManager,
                    sshDevicesManager = sshDevicesManager,
                    rurControlManager = rurControlManager,
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
            bootstrapError = null
            // Ensure upgraded CLI scripts (v4: agy login/doctor/upgrade) are provisioned on updates.
            CustomBootstrapProvisioner.runPostBootstrapProvisioningAsync(this)
            return
        }
        if (isBootstrapInstalling) return
        isBootstrapInstalling = true
        bootstrapError = null
        TermuxInstaller.setupBootstrapIfNeeded(
            this,
            {
                isBootstrapInstalling = false
                val installed = CustomBootstrapProvisioner.isBootstrapInstalled()
                bootstrapReady = installed
                if (!installed) {
                    bootstrapError = "Bootstrap incomplete: \$PREFIX/bin/sh or bin/login missing"
                }
            },
            {
                isBootstrapInstalling = false
                bootstrapReady = CustomBootstrapProvisioner.isBootstrapInstalled()
                if (!bootstrapReady) {
                    bootstrapError = "Bootstrap installation failed"
                }
            },
        )
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
        isServiceBound = false
        serviceBindError = "TermuxService disconnected"
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
    bootstrapError: String?,
    serviceBindError: String?,
    pendingPanelRoute: String?,
    onConsumePanelRoute: () -> Unit,
    onEnsureBootstrap: () -> Unit,
    onRetryBindService: () -> Unit,
    crdtDatabase: LocalFirstCrdtDatabase,
    tokenWarden: AntigravityTokenWarden,
    codingAgentsManager: CodingAgentsManager,
    skillsRepoManager: SkillsRepoManager,
    sshDevicesManager: SshDevicesManager,
    rurControlManager: RurControlCenterManager,
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
    var activeSpace by rememberSaveable { mutableStateOf(WorkstationSpace.DEV_AI) }
    var ros2InspectorShowSsh by rememberSaveable { mutableStateOf(false) }
    var primaryTab by rememberSaveable { mutableStateOf(PrimaryTab.TERMINAL) }
    var inspectorTab by rememberSaveable { mutableStateOf(InspectorTab.AGENTS) }
    var isZenMode by rememberSaveable { mutableStateOf(false) }
    var isPortraitSheetOpen by rememberSaveable { mutableStateOf(false) }
    var activeSessionIndex by rememberSaveable { mutableIntStateOf(0) }
    var lastDevSessionIndex by rememberSaveable { mutableIntStateOf(0) }
    var lastRos2SessionIndex by rememberSaveable { mutableIntStateOf(-1) }
    var sessionListVersion by remember { mutableIntStateOf(0) }
    var sessionRespawnTick by remember { mutableIntStateOf(0) }

    var isCommandPaletteOpen by rememberSaveable { mutableStateOf(false) }
    var isTokenDialogOpen by rememberSaveable { mutableStateOf(false) }
    var tokenDialogError by rememberSaveable { mutableStateOf<String?>(null) }
    var isCodingModelsDialogOpen by rememberSaveable { mutableStateOf(false) }
    var codingModelsDialogError by rememberSaveable { mutableStateOf<String?>(null) }
    var isAddSkillRepoDialogOpen by rememberSaveable { mutableStateOf(false) }
    var isOtaTokenDialogOpen by rememberSaveable { mutableStateOf(false) }
    var isTutorialOpen by rememberSaveable { mutableStateOf(false) }
    var completedTutorialSteps by remember {
        mutableStateOf(TutorialProgressStore.loadCompletedIds(context))
    }

    // RUR ROS 2 Control Center state
    var rurState by remember { mutableStateOf(RurControlState()) }
    var isRurAuthBusy by remember { mutableStateOf(false) }
    var rurAuthError by remember { mutableStateOf<String?>(null) }
    var isRurSyncBusy by remember { mutableStateOf(false) }
    var isRurConfigDialogOpen by rememberSaveable { mutableStateOf(false) }
    var isRurConfigSaving by remember { mutableStateOf(false) }
    var rurConfigError by remember { mutableStateOf<String?>(null) }
    var isAddRurRobotDialogOpen by rememberSaveable { mutableStateOf(false) }
    var isAddRurRobotSaving by remember { mutableStateOf(false) }
    var addRurRobotError by remember { mutableStateOf<String?>(null) }
    var isRur2faDialogOpen by rememberSaveable { mutableStateOf(false) }
    var isRur2faBusy by remember { mutableStateOf(false) }
    var rur2faPayload by remember { mutableStateOf<Rur2faSetupPayload?>(null) }
    var rur2faError by remember { mutableStateOf<String?>(null) }

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
            PrimaryTab.SSH -> inspectorTab = InspectorTab.SSH
            PrimaryTab.SHIELD -> inspectorTab = InspectorTab.SHIELD
            PrimaryTab.STORAGE -> inspectorTab = InspectorTab.STORAGE
            PrimaryTab.ROS_CONTROL,
            PrimaryTab.TERMINAL -> Unit
        }
    }

    fun navigateToInspectorTab(tab: InspectorTab) {
        inspectorTab = tab
        if (activeSpace == WorkstationSpace.ROS2_CONTROL && tab == InspectorTab.SSH) {
            ros2InspectorShowSsh = true
        } else if (activeSpace == WorkstationSpace.ROS2_CONTROL && tab != InspectorTab.SHIELD) {
            activeSpace = WorkstationSpace.DEV_AI
        }
        when (formFactor) {
            WorkstationFormFactor.PHONE_PORTRAIT,
            WorkstationFormFactor.PHONE_LANDSCAPE -> {
                primaryTab = when (tab) {
                    InspectorTab.AGENTS -> PrimaryTab.AGENTS
                    InspectorTab.SSH -> PrimaryTab.SSH
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
    var codingAgentsState by remember {
        mutableStateOf(
            CodingAgentsState(
                agyInstalled = false,
                opencodeInstalled = false,
                activeOpenCodeModel = null,
                ollamaBaseUrl = null,
                providerStatuses = emptyMap(),
            )
        )
    }
    var skillsState by remember { mutableStateOf(SkillsSummaryState()) }
    var skillsRefreshJob by remember { mutableStateOf<Job?>(null) }
    var resumeRefreshJob by remember { mutableStateOf<Job?>(null) }
    var editingSkillName by rememberSaveable { mutableStateOf<String?>(null) }
    var editingSkillContent by remember { mutableStateOf("") }
    var editingSkillOriginalContent by remember { mutableStateOf("") }
    var isSkillEditorLoading by remember { mutableStateOf(false) }
    var isSkillEditorLoadFailed by remember { mutableStateOf(false) }
    var isSkillEditorSaving by remember { mutableStateOf(false) }
    var skillEditorError by remember { mutableStateOf<String?>(null) }
    var skillLoadGen by remember { mutableIntStateOf(0) }
    var conflictingAddons by remember { mutableStateOf<List<ConflictingAddonInfo>>(emptyList()) }
    var sshPanelState by remember { mutableStateOf(SshPanelState()) }
    var sshScanJob by remember { mutableStateOf<Job?>(null) }
    var sshScanGen by remember { mutableIntStateOf(0) }
    var sshFilterName by rememberSaveable { mutableStateOf(SshFilter.ALL.name) }
    var isSshEditorOpen by rememberSaveable { mutableStateOf(false) }
    var isSshDeviceSaving by remember { mutableStateOf(false) }
    var editingSshDeviceId by rememberSaveable { mutableStateOf<String?>(null) }
    var pendingDeleteSshDeviceId by rememberSaveable { mutableStateOf<String?>(null) }
    var isSshKeygenBusy by remember { mutableStateOf(false) }

    val isSshPanelVisible = when (formFactor) {
        WorkstationFormFactor.PHONE_PORTRAIT,
        WorkstationFormFactor.PHONE_LANDSCAPE -> primaryTab == PrimaryTab.SSH
        WorkstationFormFactor.TABLET_PORTRAIT -> isPortraitSheetOpen && (
            (activeSpace == WorkstationSpace.DEV_AI && inspectorTab == InspectorTab.SSH) ||
                (activeSpace == WorkstationSpace.ROS2_CONTROL && ros2InspectorShowSsh)
            )
        WorkstationFormFactor.TABLET_LANDSCAPE -> !isZenMode && (
            (activeSpace == WorkstationSpace.DEV_AI && inspectorTab == InspectorTab.SSH) ||
                (activeSpace == WorkstationSpace.ROS2_CONTROL && ros2InspectorShowSsh)
            )
    }
    val isAgentsPanelVisible = activeSpace == WorkstationSpace.DEV_AI && when (formFactor) {
        WorkstationFormFactor.PHONE_PORTRAIT,
        WorkstationFormFactor.PHONE_LANDSCAPE -> primaryTab == PrimaryTab.AGENTS
        WorkstationFormFactor.TABLET_PORTRAIT -> isPortraitSheetOpen && inspectorTab == InspectorTab.AGENTS
        WorkstationFormFactor.TABLET_LANDSCAPE -> !isZenMode && inspectorTab == InspectorTab.AGENTS
    }
    val latestSshPanelVisible by rememberUpdatedState(isSshPanelVisible)

    fun refreshSkillsState() {
        skillsRefreshJob?.cancel()
        skillsRefreshJob = scope.launch {
            skillsState = skillsRepoManager.inspectSkills()
        }
    }

    fun cancelSshScan() {
        sshScanJob?.cancel()
        sshScanJob = null
        sshScanGen++
        if (sshPanelState.isScanning) {
            sshPanelState = sshPanelState.copy(isScanning = false)
        }
    }

    fun triggerSshScan(showFeedback: Boolean = false) {
        sshScanJob?.cancel()
        val gen = ++sshScanGen
        sshScanJob = scope.launch {
            sshPanelState = sshPanelState.copy(isScanning = true)
            try {
                val scanned = sshDevicesManager.probeAllDevices(sshPanelState)
                if (gen == sshScanGen) {
                    sshPanelState = scanned
                    if (showFeedback) {
                        notify("SSH: ${scanned.onlineCount}/${scanned.devices.size} disponibles")
                    }
                }
            } finally {
                if (gen == sshScanGen && sshPanelState.isScanning) {
                    sshPanelState = sshPanelState.copy(isScanning = false)
                }
            }
        }
    }

    fun openTailscaleApp() {
        val launchIntent = context.packageManager.getLaunchIntentForPackage("com.tailscale.ipn")
        if (launchIntent != null) {
            launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            runCatching { context.startActivity(launchIntent) }
                .onFailure { notify("No se pudo abrir Tailscale", showSnackbar = true) }
        } else {
            val downloadIntent = Intent(
                Intent.ACTION_VIEW,
                Uri.parse("https://tailscale.com/download/android"),
            ).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            runCatching { context.startActivity(downloadIntent) }
                .onSuccess { notify("Tailscale no instalado — abriendo descarga", showSnackbar = true) }
                .onFailure { notify("Instala Tailscale desde Play Store o F-Droid", showSnackbar = true) }
        }
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

    val latestTermuxService by rememberUpdatedState(termuxService)
    val latestRurState by rememberUpdatedState(rurState)
    val latestActiveSpace by rememberUpdatedState(activeSpace)

    fun spawnNewPtySession(sessionName: String? = null) {
        val service = latestTermuxService ?: return
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
            val newIdx = (service.termuxSessions.size - 1).coerceAtLeast(0)
            activeSessionIndex = newIdx
            lastDevSessionIndex = newIdx
            sessionListVersion++
            primaryTab = PrimaryTab.TERMINAL
            notify("Opened session ${sessionName ?: "agy-$nextNum"}")
        }
    }

    fun spawnInstallRos2Session() {
        val service = latestTermuxService
        if (!bootstrapReady && !CustomBootstrapProvisioner.isBootstrapInstalled()) {
            onEnsureBootstrap()
            notify("Extracting arm64 Linux runtime…")
            return
        }
        if (service == null) {
            notify("TermuxService aún iniciando…", showSnackbar = true)
            return
        }
        val existingIdx = service.termuxSessions.indexOfFirst {
            it.terminalSession.mSessionName == "ros:install" && it.terminalSession.isRunning
        }
        if (existingIdx >= 0) {
            activeSpace = WorkstationSpace.ROS2_CONTROL
            activeSessionIndex = existingIdx
            lastRos2SessionIndex = existingIdx
            primaryTab = PrimaryTab.TERMINAL
            isPortraitSheetOpen = false
            notify("Instalación ROS 2 Jazzy ya en curso", showSnackbar = true)
            return
        }
        val (shBin, argv) = rurControlManager.buildInstallRos2PtyExec()
        val created = runCatching {
            service.createTermuxSession(
                shBin,
                argv,
                null,
                TermuxConstants.TERMUX_HOME_DIR_PATH,
                false,
                "ros:install",
            )
        }.getOrNull()
        if (created != null) {
            val newIdx = (service.termuxSessions.size - 1).coerceAtLeast(0)
            activeSpace = WorkstationSpace.ROS2_CONTROL
            activeSessionIndex = newIdx
            lastRos2SessionIndex = newIdx
            sessionListVersion++
            primaryTab = PrimaryTab.TERMINAL
            isPortraitSheetOpen = false
            notify("Instalando Ubuntu 24.04 + ROS 2 Jazzy…", showSnackbar = true)
        } else {
            notify("No se pudo abrir la pestaña de instalación ROS 2", showSnackbar = true)
        }
    }

    fun spawnRos2ShellSession() {
        val service = latestTermuxService
        if (!bootstrapReady && !CustomBootstrapProvisioner.isBootstrapInstalled()) {
            onEnsureBootstrap()
            notify("Extracting arm64 Linux runtime…")
            return
        }
        if (service == null) {
            notify("TermuxService aún iniciando…", showSnackbar = true)
            return
        }
        val currentRur = latestRurState
        if (!currentRur.ros2Ready) {
            spawnInstallRos2Session()
            return
        }
        scope.launch {
            val execPair = runCatching {
                withContext(Dispatchers.IO) { rurControlManager.buildRos2InteractiveShellExec(currentRur) }
            }.getOrElse { err ->
                notify(err.message ?: "Error preparando shell ROS 2", showSnackbar = true)
                return@launch
            }
            val liveService = latestTermuxService
            if (liveService == null) {
                withContext(Dispatchers.IO) { rurControlManager.cleanupEphemeralEnvFromArgv(execPair.second) }
                return@launch
            }
            val existingShells = liveService.termuxSessions.count {
                (it.terminalSession.mSessionName ?: "").startsWith("ros:shell")
            }
            val title = if (existingShells == 0) "ros:shell" else "ros:shell-${existingShells + 1}"
            val created = runCatching {
                liveService.createTermuxSession(
                    execPair.first,
                    execPair.second,
                    null,
                    TermuxConstants.TERMUX_HOME_DIR_PATH,
                    false,
                    title,
                )
            }.getOrNull()
            if (created != null) {
                val newIdx = (liveService.termuxSessions.size - 1).coerceAtLeast(0)
                activeSpace = WorkstationSpace.ROS2_CONTROL
                activeSessionIndex = newIdx
                lastRos2SessionIndex = newIdx
                sessionListVersion++
                primaryTab = PrimaryTab.TERMINAL
                isPortraitSheetOpen = false
                notify("Ubuntu 24.04 · ROS 2 Jazzy ($title)")
            } else {
                withContext(Dispatchers.IO) { rurControlManager.cleanupEphemeralEnvFromArgv(execPair.second) }
                notify("No se pudo abrir la shell Ubuntu ROS 2", showSnackbar = true)
            }
        }
    }

    fun spawnRos2AiAgentSession(agentBinary: String) {
        val service = latestTermuxService
        if (!bootstrapReady && !CustomBootstrapProvisioner.isBootstrapInstalled()) {
            onEnsureBootstrap()
            notify("Extracting arm64 Linux runtime…")
            return
        }
        if (service == null) {
            notify("TermuxService aún iniciando…", showSnackbar = true)
            return
        }
        val currentRur = latestRurState
        scope.launch {
            val execPair = runCatching {
                withContext(Dispatchers.IO) { rurControlManager.buildRos2AiAgentPtyExec(agentBinary, currentRur) }
            }.getOrElse { err ->
                notify(err.message ?: "Error iniciando $agentBinary en ROS 2", showSnackbar = true)
                return@launch
            }
            val liveService = latestTermuxService
            if (liveService == null) {
                withContext(Dispatchers.IO) { rurControlManager.cleanupEphemeralEnvFromArgv(execPair.second) }
                return@launch
            }
            val prefix = "ros-ai:$agentBinary"
            val existingCount = liveService.termuxSessions.count {
                (it.terminalSession.mSessionName ?: "").startsWith(prefix)
            }
            val title = if (existingCount == 0) prefix else "$prefix-${existingCount + 1}"
            val created = runCatching {
                liveService.createTermuxSession(
                    execPair.first,
                    execPair.second,
                    null,
                    TermuxConstants.TERMUX_HOME_DIR_PATH,
                    false,
                    title,
                )
            }.getOrNull()
            if (created != null) {
                val newIdx = (liveService.termuxSessions.size - 1).coerceAtLeast(0)
                activeSpace = WorkstationSpace.ROS2_CONTROL
                activeSessionIndex = newIdx
                lastRos2SessionIndex = newIdx
                sessionListVersion++
                primaryTab = PrimaryTab.TERMINAL
                isPortraitSheetOpen = false
                notify("$agentBinary abierto en Ubuntu ROS 2", showSnackbar = true)
            } else {
                withContext(Dispatchers.IO) { rurControlManager.cleanupEphemeralEnvFromArgv(execPair.second) }
                notify("No se pudo abrir $agentBinary en ROS 2", showSnackbar = true)
            }
        }
    }

    suspend fun spawnRos2ModuleSuspending(
        module: RurModuleSpec,
        focusTerminal: Boolean = true,
        suppressMissingReceiverHint: Boolean = false,
    ): Boolean {
        val service = latestTermuxService
        if (!bootstrapReady && !CustomBootstrapProvisioner.isBootstrapInstalled()) {
            onEnsureBootstrap()
            notify("Extracting arm64 Linux runtime…")
            return false
        }
        if (service == null) {
            notify("TermuxService aún iniciando…", showSnackbar = true)
            return false
        }
        val currentRur = latestRurState
        if (!currentRur.ros2Ready) {
            spawnInstallRos2Session()
            return false
        }
        // Anti-duplicate guard: if module is already running, focus it cleanly
        val runningIdx = service.termuxSessions.indexOfFirst {
            it.terminalSession.mSessionName == module.tabTitle && it.terminalSession.isRunning
        }
        if (runningIdx >= 0) {
            activeSpace = WorkstationSpace.ROS2_CONTROL
            activeSessionIndex = runningIdx
            lastRos2SessionIndex = runningIdx
            if (focusTerminal) {
                primaryTab = PrimaryTab.TERMINAL
                isPortraitSheetOpen = false
            }
            if (!suppressMissingReceiverHint) {
                notify("${module.label} ya está activo", showSnackbar = true)
            }
            return true
        }

        val hasActiveReceiver = service.termuxSessions.any {
            RurControlCenterManager.isLiveKitReceiverSessionTitle(it.terminalSession.mSessionName) &&
                it.terminalSession.isRunning
        }
        val missingReceiverHint = !suppressMissingReceiverHint && module.requiresLiveKitReceiver && !hasActiveReceiver

        val execPair = runCatching {
            withContext(Dispatchers.IO) { rurControlManager.buildModulePtyExec(module, currentRur) }
        }.getOrElse { err ->
            notify(err.message ?: "Error preparando ${module.label}", showSnackbar = true)
            return false
        }
        val liveService = latestTermuxService
        if (liveService == null) {
            withContext(Dispatchers.IO) { rurControlManager.cleanupEphemeralEnvFromArgv(execPair.second) }
            return false
        }
        // Re-check anti-duplicate after IO hop
        val raceIdx = liveService.termuxSessions.indexOfFirst {
            it.terminalSession.mSessionName == module.tabTitle && it.terminalSession.isRunning
        }
        if (raceIdx >= 0) {
            withContext(Dispatchers.IO) { rurControlManager.cleanupEphemeralEnvFromArgv(execPair.second) }
            activeSessionIndex = raceIdx
            lastRos2SessionIndex = raceIdx
            return true
        }
        val created = runCatching {
            liveService.createTermuxSession(
                execPair.first,
                execPair.second,
                null,
                TermuxConstants.TERMUX_HOME_DIR_PATH,
                false,
                module.tabTitle,
            )
        }.getOrNull()
        if (created != null) {
            val newIdx = (liveService.termuxSessions.size - 1).coerceAtLeast(0)
            activeSpace = WorkstationSpace.ROS2_CONTROL
            activeSessionIndex = newIdx
            lastRos2SessionIndex = newIdx
            sessionListVersion++
            if (focusTerminal) {
                primaryTab = PrimaryTab.TERMINAL
                isPortraitSheetOpen = false
            }
            if (missingReceiverHint) {
                notify("Aviso: sin receptor LiveKit activo — inicia uno arriba para ver tópicos", showSnackbar = true)
            } else if (!suppressMissingReceiverHint) {
                notify("ROS 2 → ${module.label}")
            }
            return true
        } else {
            withContext(Dispatchers.IO) { rurControlManager.cleanupEphemeralEnvFromArgv(execPair.second) }
            notify("No se pudo abrir ${module.label}", showSnackbar = true)
            return false
        }
    }

    fun spawnRos2ModuleSession(module: RurModuleSpec, focusTerminal: Boolean = true) {
        scope.launch { spawnRos2ModuleSuspending(module, focusTerminal = focusTerminal) }
    }

    fun spawnRos2Stack(stack: RurStackSpec) {
        if (!latestRurState.ros2Ready) {
            spawnInstallRos2Session()
            return
        }
        val mods = stack.moduleIds.mapNotNull { RurControlCatalog.byId(it) }
        if (mods.isEmpty()) return
        scope.launch {
            mods.forEachIndexed { i, mod ->
                spawnRos2ModuleSuspending(
                    module = mod,
                    focusTerminal = i == mods.lastIndex,
                    suppressMissingReceiverHint = true,
                )
                if (i < mods.lastIndex) {
                    delay(350L)
                }
            }
            notify("Stack lanzado: ${stack.label}", showSnackbar = true)
        }
    }

    /**
     * Stops ONLY `ros:*` node/receiver sessions. Explicitly preserves `ros-ai:*` AI sessions
     * and ALL `Dev & IA` Termux sessions!
     */
    fun teardownRurNodeSessions(reason: String = "Teardown ROS"): Int {
        val service = latestTermuxService ?: return 0
        var stopped = 0
        for (i in service.termuxSessions.lastIndex downTo 0) {
            val session = service.termuxSessions[i].terminalSession
            if (RurControlCenterManager.isRos2NodeSessionTitle(session.mSessionName)) {
                runCatching {
                    session.finishIfRunning()
                    service.removeTermuxSession(session)
                }
                stopped++
            }
        }
        if (stopped > 0) {
            val remaining = service.termuxSessions
            val nextIdx = remaining.indexOfFirst {
                RurControlCenterManager.isRos2SessionTitle(it.terminalSession.mSessionName)
            }.takeIf { it >= 0 } ?: remaining.indices.firstOrNull() ?: 0
            activeSessionIndex = nextIdx
            if (remaining.isEmpty()) {
                sessionRespawnTick++
            }
            sessionListVersion++
            notify("$reason: $stopped nodo(s) detenidos (IA preservada)", showSnackbar = true)
        } else {
            notify("$reason: sin nodos ROS 2 activos")
        }
        return stopped
    }

    fun selectSpace(target: WorkstationSpace) {
        if (activeSpace == target) return
        val sessions = latestTermuxService?.termuxSessions.orEmpty()
        if (activeSpace == WorkstationSpace.DEV_AI) {
            lastDevSessionIndex = activeSessionIndex
        } else {
            lastRos2SessionIndex = activeSessionIndex
        }
        activeSpace = target
        if (target == WorkstationSpace.ROS2_CONTROL) {
            ros2InspectorShowSsh = false
            if (primaryTab == PrimaryTab.AGENTS || primaryTab == PrimaryTab.STORAGE) {
                primaryTab = PrimaryTab.ROS_CONTROL
            }
            val rosIndices = sessions.indices.filter {
                RurControlCenterManager.isRos2SessionTitle(sessions[it].terminalSession.mSessionName)
            }
            if (rosIndices.isNotEmpty()) {
                val restored = if (lastRos2SessionIndex in rosIndices) lastRos2SessionIndex else rosIndices.first()
                activeSessionIndex = restored
                lastRos2SessionIndex = restored
            } else if (latestRurState.ros2Ready) {
                spawnRos2ShellSession()
            } else {
                // Guide user cleanly to Login + 1-tap Ubuntu ROS 2 installer
                when (formFactor) {
                    WorkstationFormFactor.PHONE_PORTRAIT,
                    WorkstationFormFactor.PHONE_LANDSCAPE -> primaryTab = PrimaryTab.ROS_CONTROL
                    WorkstationFormFactor.TABLET_PORTRAIT -> isPortraitSheetOpen = true
                    WorkstationFormFactor.TABLET_LANDSCAPE -> isZenMode = false
                }
            }
            scope.launch { rurState = rurControlManager.inspectState() }
        } else {
            if (primaryTab == PrimaryTab.ROS_CONTROL) {
                primaryTab = PrimaryTab.AGENTS
            }
            val devIndices = sessions.indices.filter {
                !RurControlCenterManager.isRos2SessionTitle(sessions[it].terminalSession.mSessionName)
            }
            if (devIndices.isNotEmpty()) {
                val restored = if (lastDevSessionIndex in devIndices) lastDevSessionIndex else devIndices.first()
                activeSessionIndex = restored
                lastDevSessionIndex = restored
            }
        }
    }

    fun addSessionForActiveSpace() {
        if (latestActiveSpace == WorkstationSpace.ROS2_CONTROL) {
            spawnRos2ShellSession()
        } else {
            spawnNewPtySession()
        }
    }

    fun closePtySession(index: Int) {
        val service = latestTermuxService ?: return
        val list = service.termuxSessions
        if (index !in list.indices) return
        val target = list[index].terminalSession
        val label = target.mSessionName?.takeIf { it.isNotBlank() } ?: "pty-${index + 1}"
        runCatching {
            target.finishIfRunning()
            service.removeTermuxSession(target)
        }
        val remaining = service.termuxSessions
        val wantRos = latestActiveSpace == WorkstationSpace.ROS2_CONTROL
        val sameSpaceIndices = remaining.indices.filter {
            RurControlCenterManager.isRos2SessionTitle(remaining[it].terminalSession.mSessionName) == wantRos
        }
        activeSessionIndex = when {
            remaining.isEmpty() -> 0
            sameSpaceIndices.isNotEmpty() -> {
                sameSpaceIndices.firstOrNull { it >= index } ?: sameSpaceIndices.last()
            }
            index < activeSessionIndex -> (activeSessionIndex - 1).coerceAtLeast(0)
            else -> activeSessionIndex.coerceIn(0, remaining.lastIndex)
        }
        if (remaining.isEmpty()) {
            sessionRespawnTick++
        }
        sessionListVersion++
        notify("Closed $label", showSnackbar = true)
    }

    val latestAddSessionAction by rememberUpdatedState(::addSessionForActiveSpace)
    val surfaceBridge = remember {
        TermuxViewSurfaceBridge(
            context = context,
            onShortcutCommandPalette = { isCommandPaletteOpen = true },
            onShortcutNewSession = { latestAddSessionAction() },
            onShortcutCycleSession = {
                val sessions = latestTermuxService?.termuxSessions.orEmpty()
                val wantRos = latestActiveSpace == WorkstationSpace.ROS2_CONTROL
                val spaceIndices = sessions.indices.filter {
                    RurControlCenterManager.isRos2SessionTitle(sessions[it].terminalSession.mSessionName) == wantRos
                }
                if (spaceIndices.size > 1) {
                    val pos = spaceIndices.indexOf(activeSessionIndex).coerceAtLeast(0)
                    activeSessionIndex = spaceIndices[(pos + 1) % spaceIndices.size]
                    sessionListVersion++
                }
            },
            onShortcutSelectSession = { targetPos ->
                val sessions = latestTermuxService?.termuxSessions.orEmpty()
                val wantRos = latestActiveSpace == WorkstationSpace.ROS2_CONTROL
                val spaceIndices = sessions.indices.filter {
                    RurControlCenterManager.isRos2SessionTitle(sessions[it].terminalSession.mSessionName) == wantRos
                }
                if (targetPos in spaceIndices.indices) {
                    activeSessionIndex = spaceIndices[targetPos]
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

    // Route `melcat.open_panel` extra from `workstation ui`
    LaunchedEffect(pendingPanelRoute) {
        when (pendingPanelRoute) {
            "ros2", "rur" -> {
                selectSpace(WorkstationSpace.ROS2_CONTROL)
                when (formFactor) {
                    WorkstationFormFactor.PHONE_PORTRAIT,
                    WorkstationFormFactor.PHONE_LANDSCAPE -> primaryTab = PrimaryTab.ROS_CONTROL
                    WorkstationFormFactor.TABLET_PORTRAIT -> isPortraitSheetOpen = true
                    WorkstationFormFactor.TABLET_LANDSCAPE -> isZenMode = false
                }
            }
            "agents" -> navigateToInspectorTab(InspectorTab.AGENTS)
            "ssh" -> navigateToInspectorTab(InspectorTab.SSH)
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
            onSessionsChanged = {
                sessionListVersion++
                if (termuxService?.termuxSessions?.isEmpty() == true) {
                    sessionRespawnTick++
                }
            },
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
                        !isPortraitSheetOpen &&
                        !isCommandPaletteOpen &&
                        !isTokenDialogOpen &&
                        !isCodingModelsDialogOpen &&
                        !isAddSkillRepoDialogOpen &&
                        editingSkillName == null &&
                        !isOtaTokenDialogOpen &&
                        !isTutorialOpen &&
                        !isSshEditorOpen &&
                        pendingDeleteSshDeviceId == null &&
                        !isRurConfigDialogOpen &&
                        !isRur2faDialogOpen &&
                        !isAddRurRobotDialogOpen
                    ) {
                        surfaceBridge.terminalView.post {
                            if (surfaceBridge.terminalView.hasWindowFocus()) {
                                surfaceBridge.terminalView.requestFocus()
                            }
                        }
                    }
                    refreshSkillsState()
                    resumeRefreshJob?.cancel()
                    resumeRefreshJob = scope.launch {
                        // Refresh conflicting add-ons immediately so returning from Android uninstall dismisses banner fast
                        conflictingAddons = releaseUpdater.detectConflictingTermuxAddons()
                        ProcessGuardianService.refreshSnapshot(context)
                        tokenState = tokenWarden.inspectTokenState()
                        codingAgentsState = codingAgentsManager.inspectState()
                        val wasScanning = sshPanelState.isScanning
                        sshPanelState = sshDevicesManager.inspectEnvironmentAndDevices(sshPanelState.probes)
                            .copy(isScanning = wasScanning)
                        rurState = rurControlManager.inspectState()
                        storageMetrics = storageOptimizer.inspectMetrics(storageMetrics.lastGcSummary)
                        releaseUpdater.refreshTokenState()
                        if (latestSshPanelVisible && !wasScanning) {
                            triggerSshScan()
                        }
                    }
                }
                Lifecycle.Event.ON_PAUSE -> {
                    surfaceBridge.setCursorBlinker(false)
                }
                Lifecycle.Event.ON_STOP -> {
                    cancelSshScan()
                    skillsRefreshJob?.cancel()
                    skillsRefreshJob = null
                    resumeRefreshJob?.cancel()
                    resumeRefreshJob = null
                    surfaceBridge.setCursorBlinker(false)
                    service?.setCustomTerminalSessionClient(null)
                }
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        if (lifecycleOwner.lifecycle.currentState != Lifecycle.State.DESTROYED) {
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
        refreshSkillsState()
        conflictingAddons = releaseUpdater.detectConflictingTermuxAddons()
        tokenState = tokenWarden.inspectTokenState()
        codingAgentsState = codingAgentsManager.inspectState()
        sshPanelState = sshDevicesManager.inspectEnvironmentAndDevices(sshPanelState.probes)
        rurState = rurControlManager.inspectState()
        storageMetrics = storageOptimizer.inspectMetrics()
        releaseUpdater.refreshTokenState()
        ProcessGuardianService.refreshSnapshot(context)
        releaseUpdater.checkForUpdate(silentOnNetworkError = true)
    }

    // Periodic 20s PAGE_RUR fleet & drift sync ONLY while inside ROS 2 space (zero battery drain in Dev & IA)
    LaunchedEffect(activeSpace, rurState.isAuthenticated, rurState.hasDeviceKey) {
        if (activeSpace == WorkstationSpace.ROS2_CONTROL && (rurState.isAuthenticated || rurState.hasDeviceKey)) {
            while (isActive) {
                rurState = rurControlManager.syncFleetFromPageRur(forceTokens = false)
                delay(20_000L)
            }
        }
    }

    // Load SKILL.md safely with monotonic generation guard so rapid switch/dismiss can never clobber state
    LaunchedEffect(editingSkillName) {
        val targetName = editingSkillName ?: return@LaunchedEffect
        val gen = ++skillLoadGen
        isSkillEditorLoading = true
        isSkillEditorLoadFailed = false
        skillEditorError = null
        editingSkillContent = ""
        editingSkillOriginalContent = ""
        try {
            skillsRepoManager.readSkillMarkdown(targetName)
                .onSuccess { text ->
                    if (gen == skillLoadGen && editingSkillName == targetName) {
                        editingSkillContent = text
                        editingSkillOriginalContent = text
                        isSkillEditorLoadFailed = false
                    }
                }
                .onFailure { err ->
                    if (gen == skillLoadGen && editingSkillName == targetName) {
                        isSkillEditorLoadFailed = true
                        skillEditorError = err.message ?: "No se pudo leer SKILL.md"
                    }
                }
        } finally {
            if (gen == skillLoadGen && editingSkillName == targetName) {
                isSkillEditorLoading = false
            }
        }
    }

    // Auto-refresh Agents & Skills status whenever the Agents panel becomes visible or PTY sessions change while visible
    LaunchedEffect(isAgentsPanelVisible, sessionListVersion) {
        if (isAgentsPanelVisible) {
            refreshSkillsState()
            codingAgentsState = codingAgentsManager.inspectState()
        }
    }

    // Automatically probe live SSH reachability & Tailscale status whenever the SSH panel opens
    LaunchedEffect(isSshPanelVisible) {
        if (isSshPanelVisible) {
            triggerSshScan()
        } else {
            cancelSshScan()
        }
    }

    // Attach active PTY session once TermuxService & atomic bootstrap are ready (keyed by sessionListVersion so respawned tabs always attach!)
    LaunchedEffect(termuxService, bootstrapReady, activeSessionIndex, sessionListVersion, sessionRespawnTick) {
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
            val target = list[safeIdx].terminalSession
            if (surfaceBridge.attachedSessionHandle != target.mHandle) {
                surfaceBridge.attachSession(target)
            }
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
    val devSessions = remember(liveSessions) {
        liveSessions.filter { !RurControlCenterManager.isRos2SessionTitle(it.title) }
    }
    val ros2Sessions = remember(liveSessions) {
        liveSessions.filter { RurControlCenterManager.isRos2SessionTitle(it.title) }
    }
    val visibleSessions = if (activeSpace == WorkstationSpace.ROS2_CONTROL) ros2Sessions else devSessions

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

    fun spawnInstallOpenSshSession() {
        if (!bootstrapReady && !CustomBootstrapProvisioner.isBootstrapInstalled()) {
            onEnsureBootstrap()
            notify("Extracting arm64 Linux runtime…")
            return
        }
        val service = termuxService
        if (service == null) {
            notify("TermuxService aún iniciando…", showSnackbar = true)
            return
        }
        val (shBin, argv) = sshDevicesManager.buildInstallOpenSshPtyExec()
        val created = runCatching {
            service.createTermuxSession(
                shBin,
                argv,
                null,
                TermuxConstants.TERMUX_HOME_DIR_PATH,
                false,
                "install-ssh",
            )
        }.getOrNull()
        if (created != null) {
            activeSessionIndex = (service.termuxSessions.size - 1).coerceAtLeast(0)
            sessionListVersion++
            primaryTab = PrimaryTab.TERMINAL
            isPortraitSheetOpen = false
            notify("Instalando OpenSSH nativo en pestaña local…", showSnackbar = true)
        } else {
            notify("No se pudo abrir la pestaña de instalación SSH", showSnackbar = true)
        }
    }

    fun spawnSshPtySession(device: SshDeviceRecord) {
        if (!bootstrapReady && !CustomBootstrapProvisioner.isBootstrapInstalled()) {
            onEnsureBootstrap()
            notify("Extracting arm64 Linux runtime…")
            return
        }
        if (!sshPanelState.opensshInstalled) {
            spawnInstallOpenSshSession()
            return
        }
        val service = termuxService
        if (service == null) {
            notify("TermuxService aún iniciando…", showSnackbar = true)
            return
        }
        val execPair = runCatching { sshDevicesManager.buildPtySessionExec(device) }.getOrElse { err ->
            notify(err.message ?: "Dispositivo SSH inválido", showSnackbar = true)
            return
        }
        val tabTitle = if (latestActiveSpace == WorkstationSpace.ROS2_CONTROL) {
            "ros-ssh:${device.safeTabTitle.removePrefix("ssh:")}"
        } else {
            device.safeTabTitle
        }
        val created = runCatching {
            service.createTermuxSession(
                execPair.first,
                execPair.second,
                null,
                TermuxConstants.TERMUX_HOME_DIR_PATH,
                false,
                tabTitle,
            )
        }.getOrNull()
        if (created != null) {
            cancelSshScan()
            val newIdx = (service.termuxSessions.size - 1).coerceAtLeast(0)
            activeSessionIndex = newIdx
            if (latestActiveSpace == WorkstationSpace.ROS2_CONTROL) {
                lastRos2SessionIndex = newIdx
            } else {
                lastDevSessionIndex = newIdx
            }
            sessionListVersion++
            primaryTab = PrimaryTab.TERMINAL
            isPortraitSheetOpen = false
            val tsHint = if (device.transport == com.termux.custom.ssh.SshTransport.TAILSCALE && !sshPanelState.isTailscaleActive) {
                " (verifica Tailscale)"
            } else {
                ""
            }
            notify("SSH → ${device.name}$tsHint", showSnackbar = true)
            scope.launch {
                sshPanelState = sshDevicesManager.markDeviceConnected(device.id, sshPanelState.probes)
            }
        } else {
            notify("No se pudo abrir la sesión SSH", showSnackbar = true)
        }
    }

    fun spawnSshCopyIdPtySession(device: SshDeviceRecord) {
        if (!bootstrapReady && !CustomBootstrapProvisioner.isBootstrapInstalled()) {
            onEnsureBootstrap()
            notify("Extracting arm64 Linux runtime…")
            return
        }
        if (!sshPanelState.opensshInstalled) {
            spawnInstallOpenSshSession()
            return
        }
        val currentService = latestTermuxService
        if (currentService == null) {
            notify("TermuxService aún iniciando…", showSnackbar = true)
            return
        }

        fun launchCopyIdTab(service: TermuxService) {
            val execPair = runCatching { sshDevicesManager.buildCopyIdPtyExec(device) }.getOrElse { err ->
                notify(err.message ?: "No se encontró una clave pública SSH", showSnackbar = true)
                return
            }
            val tabTitle = "key:${device.safeTabTitle.removePrefix("ssh:")}"
            val created = runCatching {
                service.createTermuxSession(
                    execPair.first,
                    execPair.second,
                    null,
                    TermuxConstants.TERMUX_HOME_DIR_PATH,
                    false,
                    tabTitle,
                )
            }.getOrNull()
            if (created != null) {
                cancelSshScan()
                activeSessionIndex = (service.termuxSessions.size - 1).coerceAtLeast(0)
                sessionListVersion++
                primaryTab = PrimaryTab.TERMINAL
                isPortraitSheetOpen = false
                notify("Enviando clave pública → ${device.name}", showSnackbar = true)
            } else {
                notify("No se pudo abrir la pestaña ssh-copy-id", showSnackbar = true)
            }
        }

        if (sshPanelState.defaultPublicKey == null && device.identityFile.isBlank()) {
            if (isSshKeygenBusy) return
            isSshKeygenBusy = true
            scope.launch {
                try {
                    sshDevicesManager.generateDefaultEd25519Key(sshPanelState.probes)
                        .onSuccess { updated ->
                            sshPanelState = updated
                            val liveService = latestTermuxService
                            if (liveService != null) {
                                launchCopyIdTab(liveService)
                            } else {
                                notify("Clave ED25519 lista — vuelve a tocar Enviar Key", showSnackbar = true)
                            }
                        }
                        .onFailure { err ->
                            notify(err.message ?: "Error al crear clave ED25519", showSnackbar = true)
                        }
                } finally {
                    isSshKeygenBusy = false
                }
            }
        } else {
            launchCopyIdTab(currentService)
        }
    }

    fun spawnSkillsPtySession(subcommand: String, extraArgs: List<String> = emptyList()) {
        if (!bootstrapReady && !CustomBootstrapProvisioner.isBootstrapInstalled()) {
            onEnsureBootstrap()
            notify("Extracting arm64 Linux runtime…")
            return
        }
        val service = termuxService
        if (service == null) {
            notify("TermuxService aún iniciando…", showSnackbar = true)
            return
        }
        val execPair = runCatching { skillsRepoManager.buildSkillsPtyExec(subcommand, extraArgs) }.getOrElse { err ->
            notify(err.message ?: "Parámetros de skills inválidos", showSnackbar = true)
            return
        }
        val created = runCatching {
            service.createTermuxSession(
                execPair.first,
                execPair.second,
                null,
                TermuxConstants.TERMUX_HOME_DIR_PATH,
                false,
                "skills:$subcommand",
            )
        }.getOrNull()
        if (created != null) {
            activeSessionIndex = (service.termuxSessions.size - 1).coerceAtLeast(0)
            sessionListVersion++
            primaryTab = PrimaryTab.TERMINAL
            isPortraitSheetOpen = false
            notify("Skills → workstation skills $subcommand", showSnackbar = true)
        } else {
            notify("No se pudo abrir la pestaña de skills", showSnackbar = true)
        }
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
                dispatchCommandToPty("workstation setup && workstation doctor")
            }
            TutorialStep.ACTIVATE_SKILLS -> {
                spawnSkillsPtySession("list")
            }
            TutorialStep.OAUTH_VAULT_0600 -> {
                dispatchCommandToPty("agy")
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
                    codingAgentsState = codingAgentsState,
                    skillsState = skillsState,
                    onRefreshAgentsState = {
                        scope.launch {
                            codingAgentsState = codingAgentsManager.inspectState()
                            skillsState = skillsRepoManager.inspectSkills()
                            notify("Agent & skills status refreshed")
                        }
                    },
                    onOpenCodingModelsDialog = {
                        codingModelsDialogError = null
                        scope.launch { codingAgentsState = codingAgentsManager.inspectState() }
                        isCodingModelsDialogOpen = true
                    },
                    onOpenAddSkillRepoDialog = {
                        isAddSkillRepoDialogOpen = true
                    },
                    onOpenSkillEditor = { sk ->
                        skillEditorError = null
                        editingSkillName = sk.name
                    },
                    onRunSkillsAction = { sub ->
                        spawnSkillsPtySession(sub)
                    },
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
                InspectorTab.SSH -> SshDevicesPanel(
                    state = sshPanelState,
                    activeFilterName = sshFilterName,
                    isKeygenBusy = isSshKeygenBusy,
                    onSelectFilter = { sshFilterName = it },
                    onScanDevices = { triggerSshScan(showFeedback = true) },
                    onInstallOpenSsh = { spawnInstallOpenSshSession() },
                    onOpenTailscaleApp = { openTailscaleApp() },
                    onCopyPublicKey = {
                        sshPanelState.defaultPublicKey?.let { key ->
                            clipboard.setText(AnnotatedString(key))
                            notify("Clave pública SSH copiada al portapapeles", showSnackbar = true)
                        }
                    },
                    onGenerateSshKey = {
                        if (!isSshKeygenBusy) {
                            isSshKeygenBusy = true
                            scope.launch {
                                try {
                                    sshDevicesManager.generateDefaultEd25519Key(sshPanelState.probes)
                                        .onSuccess { updated ->
                                            sshPanelState = updated
                                            updated.defaultPublicKey?.let { key ->
                                                clipboard.setText(AnnotatedString(key))
                                            }
                                            notify("Clave ED25519 creada y copiada al portapapeles", showSnackbar = true)
                                        }
                                        .onFailure { err ->
                                            notify(err.message ?: "Error al crear clave SSH", showSnackbar = true)
                                        }
                                } finally {
                                    isSshKeygenBusy = false
                                }
                            }
                        }
                    },
                    onConnectDevice = { device -> spawnSshPtySession(device) },
                    onPushPublicKey = { device -> spawnSshCopyIdPtySession(device) },
                    onRequestAddDevice = {
                        editingSshDeviceId = null
                        isSshEditorOpen = true
                    },
                    onRequestEditDevice = { device ->
                        editingSshDeviceId = device.id
                        isSshEditorOpen = true
                    },
                    onRequestDeleteDevice = { device ->
                        pendingDeleteSshDeviceId = device.id
                    },
                )
                InspectorTab.SHIELD -> ShieldAndVaultPanel(
                    shieldScore = shieldScore,
                    bootstrapReady = bootstrapReady || liveSessions.isNotEmpty(),
                    guardianStatus = guardianStatus,
                    tokenState = tokenState,
                    codingAgentsState = codingAgentsState,
                    installedReleaseTag = releaseUpdater.installedTag,
                    updateState = updateState,
                    hasPrivateRepoToken = hasPrivateRepoToken,
                    conflictingAddons = conflictingAddons,
                    onUninstallAddon = { pkg ->
                        if (!releaseUpdater.openAddonUninstall(context, pkg)) {
                            notify("No se pudo abrir desinstalador para $pkg", showSnackbar = true)
                        }
                    },
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
                    onOpenCodingModelsDialog = {
                        codingModelsDialogError = null
                        scope.launch { codingAgentsState = codingAgentsManager.inspectState() }
                        isCodingModelsDialogOpen = true
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
                        dispatchCommandToPty("workstation setup && workstation doctor")
                    },
                )
            }
        }
    }

    val renderRurPanel: @Composable () -> Unit = {
        RurControlCenterPanel(
            state = rurState,
            liveRos2Sessions = ros2Sessions,
            sshDevices = sshPanelState.devices,
            isAuthBusy = isRurAuthBusy,
            authError = rurAuthError,
            isSyncBusy = isRurSyncBusy,
            onLoginOperator = { mat, cred, onSuccessClear ->
                if (!isRurAuthBusy) {
                    isRurAuthBusy = true
                    rurAuthError = null
                    scope.launch {
                        try {
                            rurControlManager.loginOperator(mat, cred)
                                .onSuccess { updated ->
                                    rurState = updated
                                    rurAuthError = null
                                    onSuccessClear()
                                    val op = updated.operatorSession
                                    notify(
                                        "Sesión RUR activa: ${op?.displayName ?: mat} (${op?.primaryRoleLabel ?: "OK"})",
                                        showSnackbar = true,
                                    )
                                }
                                .onFailure { err ->
                                    rurAuthError = err.message ?: "Error al iniciar sesión en PAGE_RUR"
                                }
                        } finally {
                            isRurAuthBusy = false
                        }
                    }
                }
            },
            onClearAuthError = { rurAuthError = null },
            onLogoutOperator = {
                if (!isRurAuthBusy) {
                    isRurAuthBusy = true
                    scope.launch {
                        try {
                            teardownRurNodeSessions("Cierre de sesión RUR")
                            rurState = rurControlManager.logoutOperator()
                            rurAuthError = null
                            notify("Sesión de operador cerrada (0600 purgada)", showSnackbar = true)
                        } finally {
                            isRurAuthBusy = false
                        }
                    }
                }
            },
            onSetOfflineBypass = { enabled ->
                scope.launch {
                    rurState = rurControlManager.setOfflineBypass(enabled)
                    rurAuthError = null
                }
            },
            onOpen2faSetup = {
                rur2faError = null
                rur2faPayload = null
                isRur2faDialogOpen = true
                isRur2faBusy = true
                scope.launch {
                    try {
                        rurControlManager.generateOperator2fa()
                            .onSuccess { payload -> rur2faPayload = payload }
                            .onFailure { err -> rur2faError = err.message ?: "Error generando 2FA" }
                    } finally {
                        isRur2faBusy = false
                    }
                }
            },
            onSyncFleet = {
                if (!isRurSyncBusy) {
                    isRurSyncBusy = true
                    scope.launch {
                        try {
                            rurState = rurControlManager.syncFleetFromPageRur(forceTokens = true)
                            val st = rurState.syncStatus
                            notify(
                                if (st.ok) "Flota sincronizada (${rurState.robots.size} robots)" else (st.error ?: st.code),
                                showSnackbar = true,
                            )
                        } finally {
                            isRurSyncBusy = false
                        }
                    }
                }
            },
            onApplyDriftUpdate = {
                if (!isRurSyncBusy) {
                    isRurSyncBusy = true
                    scope.launch {
                        try {
                            teardownRurNodeSessions("Actualización PAGE_RUR")
                            rurState = rurControlManager.syncFleetFromPageRur(forceTokens = true)
                            notify("Configuración actualizada aplicada", showSnackbar = true)
                        } finally {
                            isRurSyncBusy = false
                        }
                    }
                }
            },
            onSelectRobot = { robotId ->
                if (!isRurSyncBusy) {
                    isRurSyncBusy = true
                    scope.launch {
                        try {
                            teardownRurNodeSessions("Cambio de robot")
                            rurState = rurControlManager.selectActiveRobot(robotId)
                            notify("Robot activo: ${rurState.activeRobot.name} (D${rurState.effectiveRosDomainId})", showSnackbar = true)
                        } finally {
                            isRurSyncBusy = false
                        }
                    }
                }
            },
            onOpenAddRobotDialog = {
                addRurRobotError = null
                isAddRurRobotDialogOpen = true
            },
            onOpenConfigDialog = {
                rurConfigError = null
                isRurConfigDialogOpen = true
            },
            onInstallRos2 = { spawnInstallRos2Session() },
            onOpenFoxgloveBrowser = {
                val url = "https://app.foxglove.dev/?ds=foxglove-websocket&ds.url=ws%3A%2F%2F127.0.0.1%3A8765"
                runCatching {
                    context.startActivity(
                        Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                    )
                }.onFailure {
                    notify("Abre Foxglove Studio en ws://127.0.0.1:8765", showSnackbar = true)
                }
            },
            onLaunchAiInRos2 = { bin -> spawnRos2AiAgentSession(bin) },
            onOpenCodingModelsDialog = {
                codingModelsDialogError = null
                scope.launch { codingAgentsState = codingAgentsManager.inspectState() }
                isCodingModelsDialogOpen = true
            },
            onLaunchModule = { mod -> spawnRos2ModuleSession(mod) },
            onLaunchStack = { stack -> spawnRos2Stack(stack) },
            onFocusSession = { idx ->
                activeSessionIndex = idx
                lastRos2SessionIndex = idx
                primaryTab = PrimaryTab.TERMINAL
                isPortraitSheetOpen = false
            },
            onCloseSession = { idx -> closePtySession(idx) },
            onTeardownRosNodes = { teardownRurNodeSessions("Teardown ROS") },
            onOpenLinkedPiSsh = { dev -> spawnSshPtySession(dev) },
        )
    }

    val bannerTopPadding = when (formFactor) {
        WorkstationFormFactor.PHONE_PORTRAIT,
        WorkstationFormFactor.TABLET_PORTRAIT -> 92.dp
        WorkstationFormFactor.TABLET_LANDSCAPE -> 54.dp
        WorkstationFormFactor.PHONE_LANDSCAPE -> 44.dp
    }

    val onQuickCommandForActiveSpace: (String) -> Unit = { cmd ->
        if (activeSpace == WorkstationSpace.ROS2_CONTROL && ros2Sessions.isEmpty()) {
            when (cmd) {
                "agy" -> spawnRos2AiAgentSession("agy")
                "opencode" -> spawnRos2AiAgentSession("opencode")
                else -> spawnRos2ShellSession()
            }
        } else {
            dispatchCommandToPty(cmd)
        }
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
                        activeSpace = activeSpace,
                        refreshHz = refreshHzInt,
                        liveSessions = visibleSessions,
                        activeSessionIndex = activeSessionIndex,
                        primaryTab = primaryTab,
                        isImeVisible = isImeVisible,
                        hasHardwareKeyboard = hasHardwareKeyboard,
                        surfaceBridge = surfaceBridge,
                        shieldScore = shieldScore,
                        tokenPresent = tokenState.isPresent,
                        freeStorage = storageMetrics.freeFormatted,
                        statusNotice = statusNotice,
                        onSelectSpace = { selectSpace(it) },
                        onSelectSession = { activeSessionIndex = it },
                        onAddSession = { addSessionForActiveSpace() },
                        onCloseSession = { closePtySession(it) },
                        onOpenTutorial = { isTutorialOpen = true },
                        onSelectTab = { selectPrimaryTab(it) },
                        onOpenCommandPalette = { isCommandPaletteOpen = true },
                        onOpenClassicTermux = openClassicTermux,
                        onQuickCommand = onQuickCommandForActiveSpace,
                        renderInspector = renderInspectorContent,
                        renderRurPanel = renderRurPanel,
                    )
                }

                WorkstationFormFactor.PHONE_LANDSCAPE -> {
                    PhoneLandscapeLayout(
                        activeSpace = activeSpace,
                        refreshHz = refreshHzInt,
                        liveSessions = visibleSessions,
                        activeSessionIndex = activeSessionIndex,
                        primaryTab = primaryTab,
                        hasHardwareKeyboard = hasHardwareKeyboard,
                        surfaceBridge = surfaceBridge,
                        onSelectSpace = { selectSpace(it) },
                        onSelectSession = { activeSessionIndex = it },
                        onAddSession = { addSessionForActiveSpace() },
                        onCloseSession = { closePtySession(it) },
                        onOpenTutorial = { isTutorialOpen = true },
                        onSelectTab = { selectPrimaryTab(it) },
                        onOpenCommandPalette = { isCommandPaletteOpen = true },
                        onOpenClassicTermux = openClassicTermux,
                        onQuickCommand = onQuickCommandForActiveSpace,
                        renderInspector = renderInspectorContent,
                        renderRurPanel = renderRurPanel,
                    )
                }

                WorkstationFormFactor.TABLET_PORTRAIT -> {
                    TabletPortraitLayout(
                        activeSpace = activeSpace,
                        refreshHz = refreshHzInt,
                        hasHardwareKeyboard = hasHardwareKeyboard,
                        liveSessions = visibleSessions,
                        activeSessionIndex = activeSessionIndex,
                        inspectorTab = inspectorTab,
                        ros2InspectorShowSsh = ros2InspectorShowSsh,
                        isSheetOpen = isPortraitSheetOpen,
                        surfaceBridge = surfaceBridge,
                        crdtSiteId = crdtDatabase.siteId,
                        tokenState = tokenState,
                        guardianStatus = guardianStatus,
                        storageMetrics = storageMetrics,
                        statusNotice = statusNotice,
                        onSelectSpace = { selectSpace(it) },
                        onSelectSession = { activeSessionIndex = it },
                        onAddSession = { addSessionForActiveSpace() },
                        onCloseSession = { closePtySession(it) },
                        onOpenTutorial = { isTutorialOpen = true },
                        onToggleSheet = { isPortraitSheetOpen = !isPortraitSheetOpen },
                        onSelectInspectorTab = { inspectorTab = it },
                        onSelectRos2InspectorShowSsh = { ros2InspectorShowSsh = it },
                        onOpenCommandPalette = { isCommandPaletteOpen = true },
                        onOpenClassicTermux = openClassicTermux,
                        onQuickCommand = onQuickCommandForActiveSpace,
                        renderInspector = renderInspectorContent,
                        renderRurPanel = renderRurPanel,
                    )
                }

                WorkstationFormFactor.TABLET_LANDSCAPE -> {
                    TabletLandscapeLayout(
                        activeSpace = activeSpace,
                        refreshHz = refreshHzInt,
                        hasHardwareKeyboard = hasHardwareKeyboard,
                        liveSessions = visibleSessions,
                        activeSessionIndex = activeSessionIndex,
                        inspectorTab = inspectorTab,
                        ros2InspectorShowSsh = ros2InspectorShowSsh,
                        isZenMode = isZenMode,
                        surfaceBridge = surfaceBridge,
                        crdtSiteId = crdtDatabase.siteId,
                        tokenState = tokenState,
                        guardianStatus = guardianStatus,
                        storageMetrics = storageMetrics,
                        statusNotice = statusNotice,
                        onSelectSpace = { selectSpace(it) },
                        onSelectSession = { activeSessionIndex = it },
                        onAddSession = { addSessionForActiveSpace() },
                        onCloseSession = { closePtySession(it) },
                        onOpenTutorial = { isTutorialOpen = true },
                        onSelectInspectorTab = {
                            inspectorTab = it
                            isZenMode = false
                        },
                        onSelectRos2InspectorShowSsh = {
                            ros2InspectorShowSsh = it
                            isZenMode = false
                        },
                        onToggleZenMode = {
                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            isZenMode = !isZenMode
                            notify(if (isZenMode) "Zen Mode · Ctrl+Shift+B" else "Split-Pane Restored")
                        },
                        onOpenCommandPalette = { isCommandPaletteOpen = true },
                        onOpenClassicTermux = openClassicTermux,
                        onQuickCommand = onQuickCommandForActiveSpace,
                        renderInspector = renderInspectorContent,
                        renderRurPanel = renderRurPanel,
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

            val bootError = serviceBindError ?: bootstrapError ?: terminalBootError
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
                                        sessionRespawnTick++
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
                    .padding(bottom = if (formFactor == WorkstationFormFactor.PHONE_PORTRAIT && !isImeVisible) 86.dp else 32.dp),
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
                when (cmd) {
                    "workstation install-ssh" -> spawnInstallOpenSshSession()
                    "workstation install-ros2" -> spawnInstallRos2Session()
                    "workstation skills update" -> spawnSkillsPtySession("update")
                    "workstation skills diff" -> spawnSkillsPtySession("diff")
                    "workstation skills push" -> spawnSkillsPtySession("push")
                    else -> dispatchCommandToPty(cmd)
                }
                isCommandPaletteOpen = false
            },
            onNewPtySession = {
                addSessionForActiveSpace()
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
            onOpenCodingModelsVault = {
                isCommandPaletteOpen = false
                codingModelsDialogError = null
                scope.launch { codingAgentsState = codingAgentsManager.inspectState() }
                isCodingModelsDialogOpen = true
            },
            onOpenSshPanel = {
                isCommandPaletteOpen = false
                navigateToInspectorTab(InspectorTab.SSH)
            },
            onOpenRos2Space = {
                isCommandPaletteOpen = false
                selectSpace(WorkstationSpace.ROS2_CONTROL)
            },
            onLaunchRos2Ai = { bin ->
                isCommandPaletteOpen = false
                spawnRos2AiAgentSession(bin)
            },
            onTeardownRos2Nodes = {
                isCommandPaletteOpen = false
                teardownRurNodeSessions("Teardown ROS")
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

    if (isCodingModelsDialogOpen) {
        CodingModelsVaultDialog(
            state = codingAgentsState,
            errorText = codingModelsDialogError,
            onDismiss = {
                codingModelsDialogError = null
                isCodingModelsDialogOpen = false
            },
            onSelectModel = { preset ->
                scope.launch {
                    codingAgentsManager.selectCodingModel(preset).onSuccess { updated ->
                        codingAgentsState = updated
                        codingModelsDialogError = null
                        val needsSetupHint = !updated.isProviderReady(preset.provider)
                        notify(
                            if (needsSetupHint) {
                                "Selected ${preset.displayName} — add ${preset.provider.displayName} key/URL above"
                            } else {
                                "Active coding model: ${preset.displayName}"
                            },
                            showSnackbar = true,
                        )
                    }.onFailure { err ->
                        codingModelsDialogError = err.message ?: "Failed to save coding model"
                    }
                }
            },
            onSaveApiKey = { provider, rawKey, onSaved ->
                scope.launch {
                    codingAgentsManager.saveProviderApiKey(provider, rawKey).onSuccess { updated ->
                        codingAgentsState = updated
                        codingModelsDialogError = null
                        onSaved()
                        notify("${provider.displayName} key locked (0600)", showSnackbar = true)
                    }.onFailure { err ->
                        codingModelsDialogError = err.message ?: "Invalid API key"
                    }
                }
            },
            onClearApiKey = { provider ->
                scope.launch {
                    codingAgentsState = codingAgentsManager.clearProviderApiKey(provider)
                    codingModelsDialogError = null
                    notify("${provider.displayName} key cleared", showSnackbar = true)
                }
            },
            onSaveOllamaUrl = { rawUrl ->
                scope.launch {
                    codingAgentsManager.saveOllamaBaseUrl(rawUrl).onSuccess { updated ->
                        codingAgentsState = updated
                        codingModelsDialogError = null
                        notify("Ollama LAN saved: ${updated.ollamaBaseUrl}", showSnackbar = true)
                    }.onFailure { err ->
                        codingModelsDialogError = err.message ?: "Invalid Ollama URL"
                    }
                }
            },
            onClearOllamaUrl = {
                scope.launch {
                    codingAgentsState = codingAgentsManager.clearOllamaBaseUrl()
                    codingModelsDialogError = null
                    notify("Ollama endpoint cleared", showSnackbar = true)
                }
            },
            onLaunchOrInstallOpenCode = {
                codingModelsDialogError = null
                isCodingModelsDialogOpen = false
                dispatchCommandToPty(
                    if (codingAgentsState.opencodeInstalled) "opencode" else "workstation install-opencode"
                )
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
            onLaunchAgySignIn = {
                tokenDialogError = null
                isTokenDialogOpen = false
                dispatchCommandToPty("agy")
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

    if (isSshEditorOpen) {
        val editingDevice = editingSshDeviceId?.let { id ->
            sshPanelState.devices.firstOrNull { it.id == id }
        }
        AddEditSshDeviceDialog(
            initial = editingDevice,
            isSaving = isSshDeviceSaving,
            onDismiss = {
                if (!isSshDeviceSaving) {
                    isSshEditorOpen = false
                    editingSshDeviceId = null
                }
            },
            onSave = { name, host, port, user, transport, identity, tags, localPortForwards, startupCommand, onErr ->
                if (isSshDeviceSaving) return@AddEditSshDeviceDialog
                isSshDeviceSaving = true
                scope.launch {
                    try {
                        cancelSshScan()
                        val existingSavedId = editingDevice
                            ?.takeIf { it.source == com.termux.custom.ssh.SshDeviceSource.SAVED }
                            ?.id
                        sshDevicesManager.upsertDevice(
                            id = existingSavedId,
                            name = name,
                            host = host,
                            port = port,
                            username = user,
                            transport = transport,
                            identityFile = identity,
                            tags = tags,
                            localPortForwards = localPortForwards,
                            startupCommand = startupCommand,
                            existingProbes = sshPanelState.probes,
                        ).onSuccess { updated ->
                            sshPanelState = updated
                            isSshEditorOpen = false
                            editingSshDeviceId = null
                            notify("Guardado: $name", showSnackbar = true)
                            triggerSshScan()
                        }.onFailure { err ->
                            onErr(err.message ?: "Error al guardar dispositivo")
                        }
                    } finally {
                        isSshDeviceSaving = false
                    }
                }
            },
        )
    }

    val pendingDeleteDevice = pendingDeleteSshDeviceId?.let { id ->
        sshPanelState.devices.firstOrNull { it.id == id }
    }
    if (pendingDeleteDevice != null) {
        DeleteSshDeviceConfirmDialog(
            device = pendingDeleteDevice,
            onDismiss = { pendingDeleteSshDeviceId = null },
            onConfirmDelete = {
                val target = pendingDeleteDevice
                pendingDeleteSshDeviceId = null
                scope.launch {
                    cancelSshScan()
                    sshDevicesManager.deleteDevice(target.id, sshPanelState.probes)
                        .onSuccess { updated ->
                            sshPanelState = updated
                            notify("Eliminado: ${target.name}", showSnackbar = true)
                        }
                        .onFailure { err ->
                            notify(err.message ?: "Error al eliminar dispositivo", showSnackbar = true)
                        }
                }
            },
        )
    }

    if (isAddSkillRepoDialogOpen) {
        AddSkillRepoDialog(
            isRootGitRepo = skillsState.isRootGitRepo,
            onDismiss = { isAddSkillRepoDialogOpen = false },
            onCloneSubRepo = { gitUrl, folderName ->
                isAddSkillRepoDialogOpen = false
                val args = if (folderName.isNotBlank()) listOf(gitUrl, folderName) else listOf(gitUrl)
                spawnSkillsPtySession("add", args)
            },
            onConnectRootRepo = { gitUrl ->
                isAddSkillRepoDialogOpen = false
                spawnSkillsPtySession("init", listOf(gitUrl))
            },
        )
    }

    val activeSkillName = editingSkillName
    if (activeSkillName != null) {
        val activeSkillInfo = skillsState.skills.firstOrNull { it.name == activeSkillName }
        val hasUnsavedSkillEdits = !isSkillEditorLoading &&
            !isSkillEditorLoadFailed &&
            editingSkillContent != editingSkillOriginalContent
        SkillMarkdownEditorDialog(
            skillName = activeSkillName,
            skillInfo = activeSkillInfo,
            content = editingSkillContent,
            isLoading = isSkillEditorLoading,
            isLoadFailed = isSkillEditorLoadFailed,
            isSaving = isSkillEditorSaving,
            hasUnsavedChanges = hasUnsavedSkillEdits,
            errorText = skillEditorError,
            onContentChange = { next ->
                editingSkillContent = next
                skillEditorError = null
            },
            onSave = {
                if (!isSkillEditorSaving && !isSkillEditorLoading && !isSkillEditorLoadFailed) {
                    val targetName = activeSkillName
                    val contentToSave = editingSkillContent
                    isSkillEditorSaving = true
                    skillEditorError = null
                    scope.launch {
                        try {
                            skillsRepoManager.saveSkillMarkdown(targetName, contentToSave)
                                .onSuccess {
                                    editingSkillOriginalContent = contentToSave
                                    editingSkillName = null
                                    refreshSkillsState()
                                    notify("Guardado $targetName/SKILL.md", showSnackbar = true)
                                }
                                .onFailure { err ->
                                    skillEditorError = err.message ?: "Error al guardar SKILL.md"
                                }
                        } finally {
                            isSkillEditorSaving = false
                        }
                    }
                }
            },
            onOpenDiff = {
                if (!isSkillEditorSaving) {
                    editingSkillName = null
                    skillEditorError = null
                    spawnSkillsPtySession("diff")
                }
            },
            onDismiss = {
                if (!isSkillEditorSaving) {
                    editingSkillName = null
                    skillEditorError = null
                }
            },
        )
    }

    if (isRurConfigDialogOpen) {
        RurConfigDialog(
            state = rurState,
            sshDevices = sshPanelState.devices,
            isSaving = isRurConfigSaving,
            errorText = rurConfigError,
            onDismiss = {
                if (!isRurConfigSaving) {
                    isRurConfigDialogOpen = false
                    rurConfigError = null
                }
            },
            onSave = { webUrl, devKey, domainId, repoPath, scriptsPath, linkedPiId, customScripts ->
                if (isRurConfigSaving) return@RurConfigDialog
                isRurConfigSaving = true
                rurConfigError = null
                scope.launch {
                    try {
                        rurControlManager.saveSettings(
                            webApiUrl = webUrl,
                            deviceKeyInput = devKey,
                            defaultRosDomainId = domainId,
                            offboardRepoPath = repoPath,
                            scriptsPath = scriptsPath,
                            linkedPiSshDeviceId = linkedPiId,
                            customScripts = customScripts,
                        ).onSuccess { updated ->
                            rurState = updated
                            isRurConfigDialogOpen = false
                            rurConfigError = null
                            notify("Configuración ROS 2 guardada", showSnackbar = true)
                            if (updated.isAuthenticated || updated.hasDeviceKey) {
                                rurState = rurControlManager.syncFleetFromPageRur(forceTokens = true)
                            }
                        }.onFailure { err ->
                            rurConfigError = err.message ?: "Error guardando configuración ROS 2"
                        }
                    } finally {
                        isRurConfigSaving = false
                    }
                }
            },
        )
    }

    if (isRur2faDialogOpen) {
        Rur2faSetupDialog(
            payload = rur2faPayload,
            isBusy = isRur2faBusy,
            errorText = rur2faError,
            onCopySecret = { secret ->
                clipboard.setText(AnnotatedString(secret))
                notify("Clave 2FA copiada al portapapeles", showSnackbar = true)
            },
            onOpenAuthenticatorUri = { uri ->
                runCatching {
                    context.startActivity(
                        Intent(Intent.ACTION_VIEW, Uri.parse(uri)).apply {
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                    )
                }.onFailure {
                    notify("Copia la clave manualmente en Google Authenticator", showSnackbar = true)
                }
            },
            onVerifyCode = { code, secret ->
                if (!isRur2faBusy) {
                    isRur2faBusy = true
                    rur2faError = null
                    scope.launch {
                        try {
                            rurControlManager.enableOperator2fa(code, secret)
                                .onSuccess { updated ->
                                    rurState = updated
                                    isRur2faDialogOpen = false
                                    rur2faPayload = null
                                    rur2faError = null
                                    notify("2FA Google Authenticator activado en PAGE_RUR", showSnackbar = true)
                                }
                                .onFailure { err ->
                                    rur2faError = err.message ?: "Código 2FA inválido"
                                }
                        } finally {
                            isRur2faBusy = false
                        }
                    }
                }
            },
            onDismiss = {
                if (!isRur2faBusy) {
                    isRur2faDialogOpen = false
                    rur2faPayload = null
                    rur2faError = null
                }
            },
        )
    }

    if (isAddRurRobotDialogOpen) {
        AddLocalRurRobotDialog(
            defaultDomainId = rurState.defaultRosDomainId,
            isSaving = isAddRurRobotSaving,
            errorText = addRurRobotError,
            onDismiss = {
                if (!isAddRurRobotSaving) {
                    isAddRurRobotDialogOpen = false
                    addRurRobotError = null
                }
            },
            onSave = { name, room, domainId, lkUrl ->
                if (isAddRurRobotSaving) return@AddLocalRurRobotDialog
                isAddRurRobotSaving = true
                addRurRobotError = null
                scope.launch {
                    try {
                        rurControlManager.upsertLocalRobot(name, room, domainId, lkUrl)
                            .onSuccess { updated ->
                                rurState = updated
                                isAddRurRobotDialogOpen = false
                                addRurRobotError = null
                                notify("Robot agregado: $name (D$domainId)", showSnackbar = true)
                            }
                            .onFailure { err ->
                                addRurRobotError = err.message ?: "Error al agregar robot"
                            }
                    } finally {
                        isAddRurRobotSaving = false
                    }
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
