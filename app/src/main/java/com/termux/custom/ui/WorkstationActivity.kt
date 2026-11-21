package com.termux.custom.ui

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.res.Configuration
import android.os.Bundle
import android.os.IBinder
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.termux.app.TermuxActivity
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
import com.termux.shared.termux.TermuxConstants
import kotlinx.coroutines.launch
import java.io.File

private val HyperDarkScheme = darkColorScheme(
    primary = Color(0xFF38BDF8),
    onPrimary = Color(0xFF0B1120),
    secondary = Color(0xFF34D399),
    onSecondary = Color(0xFF0B1120),
    background = Color(0xFF070B14),
    onBackground = Color(0xFFF8FAFC),
    surface = Color(0xFF0F172A),
    onSurface = Color(0xFFF8FAFC),
    surfaceVariant = Color(0xFF1E293B),
    onSurfaceVariant = Color(0xFFCBD5E1),
    error = Color(0xFFFB7185),
)

class WorkstationActivity : ComponentActivity(), ServiceConnection {

    private var termuxService by mutableStateOf<TermuxService?>(null)
    private lateinit var crdtDatabase: LocalFirstCrdtDatabase
    private lateinit var tokenWarden: AntigravityTokenWarden
    private lateinit var storageOptimizer: StorageOptimizer

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        crdtDatabase = LocalFirstCrdtDatabase(this)
        tokenWarden = AntigravityTokenWarden(this)
        storageOptimizer = StorageOptimizer()

        // Start TermuxService (Layer 0 PTY owner) & ProcessGuardianService (Layer 1 survival)
        val serviceIntent = Intent(this, TermuxService::class.java)
        startService(serviceIntent)
        bindService(serviceIntent, this, Context.BIND_AUTO_CREATE)
        ProcessGuardianService.start(this)

        setContent {
            MaterialTheme(colorScheme = HyperDarkScheme) {
                WorkstationRoot(
                    activity = this,
                    termuxService = termuxService,
                    crdtDatabase = crdtDatabase,
                    tokenWarden = tokenWarden,
                    storageOptimizer = storageOptimizer,
                )
            }
        }
    }

    override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
        termuxService = runCatching {
            val field = service?.javaClass?.getDeclaredField("service")
            field?.isAccessible = true
            field?.get(service) as? TermuxService
        }.getOrNull()
    }

    override fun onServiceDisconnected(name: ComponentName?) {
        termuxService = null
    }

    override fun onDestroy() {
        runCatching { unbindService(this) }
        super.onDestroy()
    }
}

@Composable
private fun WorkstationRoot(
    activity: WorkstationActivity,
    termuxService: TermuxService?,
    crdtDatabase: LocalFirstCrdtDatabase,
    tokenWarden: AntigravityTokenWarden,
    storageOptimizer: StorageOptimizer,
) {
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    val haptics = LocalHapticFeedback.current
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()

    val isTabletOrDesktop = configuration.screenWidthDp >= 600
    val hasHardwareKeyboard = configuration.keyboard == Configuration.KEYBOARD_QWERTY

    val sessions by crdtDatabase.sessionsFlow.collectAsState()
    val guardianStatus by ProcessGuardianService.statusFlow.collectAsState()

    var tokenState by remember {
        mutableStateOf(
            OAuthTokenState(
                isPresent = false,
                maskedPreview = "Checking...",
                fingerprintSha256 = "none",
                posixModeOctal = "0600",
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

    var selectedTabIndex by remember { mutableIntStateOf(0) }
    var activeSessionIndex by remember { mutableIntStateOf(0) }
    var isCommandPaletteOpen by remember { mutableStateOf(false) }
    var isTokenDialogOpen by remember { mutableStateOf(false) }
    var statusNotice by remember { mutableStateOf("Layer 0 PTY Ready · targetSdk=28 (W^X Exec Enabled)") }

    val surfaceBridge = remember {
        TermuxViewSurfaceBridge(
            context = context,
            onShortcutCommandPalette = { isCommandPaletteOpen = true },
            onShortcutNewSession = {
                scope.launch {
                    crdtDatabase.upsertSession(
                        title = "agy-workspace-${sessions.size + 1}",
                        agentType = "agy",
                        compactedSummary = "Interactive arm64-v8a agent session",
                    )
                }
            },
            onShortcutCycleSession = {
                val count = termuxService?.termuxSessions?.size ?: 0
                if (count > 0) {
                    activeSessionIndex = (activeSessionIndex + 1) % count
                }
            },
        )
    }

    LaunchedEffect(Unit) {
        surfaceBridge.apply120HzFramePacing(activity)
        CustomBootstrapProvisioner.runPostBootstrapProvisioningAsync(context)
        crdtDatabase.refreshSessions()
        if (crdtDatabase.sessionsFlow.value.isEmpty()) {
            crdtDatabase.upsertSession(
                title = "agy-architect",
                agentType = "agy",
                compactedSummary = "Layer 0-3 Architecture & CRDT P2P Delta Sync",
                contextTokens = 4096,
            )
            crdtDatabase.upsertSession(
                title = "claude-auditor",
                agentType = "claude",
                compactedSummary = "Senior Auditor + Security & Token Warden (chmod 0600)",
                contextTokens = 2048,
            )
        }
        tokenState = tokenWarden.inspectTokenState()
        storageMetrics = storageOptimizer.inspectMetrics()
        ProcessGuardianService.refreshSnapshot(context)
    }

    // Bind active PTY session from TermuxService when available (or create one if none exists yet)
    DisposableEffect(termuxService, activeSessionIndex) {
        val service = termuxService
        if (service != null) {
            if (service.termuxSessions.isEmpty() && File(TermuxConstants.TERMUX_PREFIX_DIR_PATH).exists()) {
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
            }
            val termuxSessions = service.termuxSessions
            if (!termuxSessions.isNullOrEmpty()) {
                val safeIndex = activeSessionIndex.coerceIn(0, termuxSessions.lastIndex)
                surfaceBridge.attachSession(termuxSessions[safeIndex].terminalSession)
            }
        }
        onDispose { }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            WorkstationTopBar(
                isTablet = isTabletOrDesktop,
                hasHardwareKeyboard = hasHardwareKeyboard,
                refreshRateHz = surfaceBridge.activeRefreshRateHz.toInt(),
                sessions = sessions,
                onOpenCommandPalette = {
                    haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    isCommandPaletteOpen = true
                },
                onLaunchClassicTermux = {
                    context.startActivity(Intent(context, TermuxActivity::class.java))
                },
            )
        },
        bottomBar = {
            Column {
                WorkstationStatusBar(
                    ptyCount = termuxService?.termuxSessions?.size ?: 0,
                    crdtSiteId = crdtDatabase.siteId,
                    tokenState = tokenState,
                    guardianStatus = guardianStatus,
                    storageMetrics = storageMetrics,
                    statusNotice = statusNotice,
                )
                if (!isTabletOrDesktop) {
                    NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                        listOf("Terminal", "CRDT Sessions", "Guardian & Token", "Storage zstd").forEachIndexed { idx, label ->
                            NavigationBarItem(
                                selected = selectedTabIndex == idx,
                                onClick = {
                                    haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    selectedTabIndex = idx
                                },
                                icon = {
                                    Box(
                                        modifier = Modifier
                                            .size(8.dp)
                                            .clip(CircleShape)
                                            .background(
                                                if (selectedTabIndex == idx) MaterialTheme.colorScheme.primary
                                                else MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                    )
                                },
                                label = { Text(label, fontSize = 11.sp) },
                            )
                        }
                    }
                }
            }
        },
    ) { innerPadding ->
        if (isTabletOrDesktop) {
            // Xiaomi Pad 6 120Hz Desktop / Split-Pane Mode
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .padding(12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Column(
                    modifier = Modifier
                        .weight(0.62f)
                        .fillMaxHeight(),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    TerminalSurfacePane(
                        surfaceBridge = surfaceBridge,
                        showTouchExtraKeys = !hasHardwareKeyboard,
                        modifier = Modifier.weight(1f),
                    )
                }

                Column(
                    modifier = Modifier
                        .weight(0.38f)
                        .fillMaxHeight(),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf("CRDT", "Guardian", "Storage").forEachIndexed { idx, tab ->
                            FilterChip(
                                selected = selectedTabIndex == idx,
                                onClick = { selectedTabIndex = idx },
                                label = { Text(tab) },
                            )
                        }
                    }

                    AnimatedContent(
                        targetState = selectedTabIndex,
                        transitionSpec = {
                            fadeIn(spring(stiffness = Spring.StiffnessMediumLow)) togetherWith
                                fadeOut(spring(stiffness = Spring.StiffnessMedium))
                        },
                        label = "TabletSidePanel",
                        modifier = Modifier.weight(1f),
                    ) { panelIndex ->
                        when (panelIndex) {
                            0 -> CrdtSessionsPanel(
                                sessions = sessions,
                                siteId = crdtDatabase.siteId,
                                onRunAgentCommand = { cmd ->
                                    surfaceBridge.sendCommandLine(cmd)
                                    statusNotice = "Sent to PTY: $cmd"
                                },
                                onNewSession = {
                                    scope.launch {
                                        crdtDatabase.upsertSession(
                                            title = "agy-node-${sessions.size + 1}",
                                            agentType = "agy",
                                            compactedSummary = "Local-First CRDT sovereign context",
                                            contextTokens = 1024,
                                        )
                                    }
                                },
                                onExportP2pDeltas = {
                                    scope.launch {
                                        val dir = File(context.filesDir, "crdt-deltas")
                                        val out = crdtDatabase.exportDeltaBundleToFile(dir)
                                        statusNotice = "Exported CRDT delta bundle: ${out.name}"
                                    }
                                },
                                onArchiveSession = { id ->
                                    scope.launch { crdtDatabase.softDeleteSession(id) }
                                },
                            )
                            1 -> GuardianAndTokenPanel(
                                guardianStatus = guardianStatus,
                                tokenState = tokenState,
                                onToggleWakeLock = { ProcessGuardianService.toggleLocks(context) },
                                onRequestBatteryExclusion = { ProcessGuardianService.requestIgnoreBatteryOptimizations(context) },
                                onOpenHyperOsAutostart = { ProcessGuardianService.openHyperOsAutostartSettings(context) },
                                onCopyPhantomAdbCommand = {
                                    clipboard.setText(AnnotatedString(guardianStatus.adbPhantomFixCommand))
                                    statusNotice = "Copied max_phantom_processes ADB command"
                                },
                                onOpenTokenProvisionDialog = { isTokenDialogOpen = true },
                            )
                            else -> StorageOptimizerPanel(
                                metrics = storageMetrics,
                                onRunMelcatGc = {
                                    scope.launch {
                                        storageMetrics = storageOptimizer.runMelcatGarbageCollection()
                                        statusNotice = storageMetrics.lastGcSummary
                                    }
                                },
                                onCreateZstdSnapshot = {
                                    scope.launch {
                                        storageMetrics = storageOptimizer.createPortableZstdSnapshot()
                                        statusNotice = storageMetrics.lastGcSummary
                                    }
                                },
                            )
                        }
                    }
                }
            }
        } else {
            // Redmi Note 13 Pro Compact Phone Mode
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .padding(horizontal = 10.dp, vertical = 6.dp),
            ) {
                when (selectedTabIndex) {
                    0 -> TerminalSurfacePane(
                        surfaceBridge = surfaceBridge,
                        showTouchExtraKeys = true,
                        modifier = Modifier.fillMaxSize(),
                    )
                    1 -> CrdtSessionsPanel(
                        sessions = sessions,
                        siteId = crdtDatabase.siteId,
                        onRunAgentCommand = { cmd ->
                            surfaceBridge.sendCommandLine(cmd)
                            selectedTabIndex = 0
                            statusNotice = "Executed: $cmd"
                        },
                        onNewSession = {
                            scope.launch {
                                crdtDatabase.upsertSession(
                                    title = "agy-mobile-${sessions.size + 1}",
                                    agentType = "agy",
                                    compactedSummary = "Mobile arm64-v8a agent context",
                                    contextTokens = 1024,
                                )
                            }
                        },
                        onExportP2pDeltas = {
                            scope.launch {
                                val dir = File(context.filesDir, "crdt-deltas")
                                val out = crdtDatabase.exportDeltaBundleToFile(dir)
                                statusNotice = "CRDT delta exported: ${out.name}"
                            }
                        },
                        onArchiveSession = { id ->
                            scope.launch { crdtDatabase.softDeleteSession(id) }
                        },
                    )
                    2 -> GuardianAndTokenPanel(
                        guardianStatus = guardianStatus,
                        tokenState = tokenState,
                        onToggleWakeLock = { ProcessGuardianService.toggleLocks(context) },
                        onRequestBatteryExclusion = { ProcessGuardianService.requestIgnoreBatteryOptimizations(context) },
                        onOpenHyperOsAutostart = { ProcessGuardianService.openHyperOsAutostartSettings(context) },
                        onCopyPhantomAdbCommand = {
                            clipboard.setText(AnnotatedString(guardianStatus.adbPhantomFixCommand))
                            statusNotice = "Copied ADB phantom fix command"
                        },
                        onOpenTokenProvisionDialog = { isTokenDialogOpen = true },
                    )
                    else -> StorageOptimizerPanel(
                        metrics = storageMetrics,
                        onRunMelcatGc = {
                            scope.launch {
                                storageMetrics = storageOptimizer.runMelcatGarbageCollection()
                                statusNotice = storageMetrics.lastGcSummary
                            }
                        },
                        onCreateZstdSnapshot = {
                            scope.launch {
                                storageMetrics = storageOptimizer.createPortableZstdSnapshot()
                                statusNotice = storageMetrics.lastGcSummary
                            }
                        },
                    )
                }
            }
        }
    }

    if (isCommandPaletteOpen) {
        CommandPaletteDialog(
            onDismiss = { isCommandPaletteOpen = false },
            onExecuteCommand = { cmd ->
                surfaceBridge.sendCommandLine(cmd)
                statusNotice = "Dispatched: $cmd"
                isCommandPaletteOpen = false
            },
        )
    }

    if (isTokenDialogOpen) {
        TokenProvisionDialog(
            currentState = tokenState,
            onDismiss = { isTokenDialogOpen = false },
            onSaveToken = { rawInput ->
                scope.launch {
                    tokenWarden.provisionOrSyncToken(rawInput).onSuccess { updated ->
                        tokenState = updated
                        statusNotice = "antigravity-oauth-token locked (${updated.posixModeOctal})"
                        isTokenDialogOpen = false
                    }.onFailure { err ->
                        statusNotice = "Token error: ${err.message}"
                    }
                }
            },
            onRevokeToken = {
                scope.launch {
                    tokenState = tokenWarden.revokeLocalToken()
                    statusNotice = "Local OAuth token revoked"
                    isTokenDialogOpen = false
                }
            },
        )
    }
}

@Composable
private fun WorkstationTopBar(
    isTablet: Boolean,
    hasHardwareKeyboard: Boolean,
    refreshRateHz: Int,
    sessions: List<AgentSessionRecord>,
    onOpenCommandPalette: () -> Unit,
    onLaunchClassicTermux: () -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 4.dp,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "Terminal Agents Builder",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.16f),
                    ) {
                        Text(
                            text = "${refreshRateHz}Hz · ${if (isTablet) "PAD-6 DESKTOP" else "COMPACT"} · ${if (hasHardwareKeyboard) "HW-KB" else "TOUCH"}",
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                        )
                    }
                }
                Text(
                    text = "com.termux · arm64-v8a Native PTY · ${sessions.size} CRDT contexts",
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AssistChip(
                    onClick = onOpenCommandPalette,
                    label = {
                        Text(
                            text = "⌘K / Ctrl+Shift+P",
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp,
                        )
                    },
                )
                OutlinedButton(onClick = onLaunchClassicTermux) {
                    Text("Termux PTY", fontSize = 12.sp)
                }
            }
        }
    }
}

@Composable
private fun TerminalSurfacePane(
    surfaceBridge: TermuxViewSurfaceBridge,
    showTouchExtraKeys: Boolean,
    modifier: Modifier = Modifier,
) {
    val haptics = LocalHapticFeedback.current

    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Surface(
            shape = RoundedCornerShape(14.dp),
            color = Color(0xFF04070E),
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color(0xFF0B1324))
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "TerminalSurfaceBridge · /data/data/com.termux/files/usr/bin/zsh",
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        color = Color(0xFF38BDF8),
                    )
                    Text(
                        text = "FD PTY Attached",
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        color = Color(0xFF34D399),
                    )
                }

                AndroidView(
                    factory = { surfaceBridge.terminalView },
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(8.dp),
                )
            }
        }

        AnimatedVisibility(visible = showTouchExtraKeys) {
            TouchExtraKeysBar(
                onSendEscape = {
                    haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    surfaceBridge.sendEscapeSequence("\u001b")
                },
                onSendTab = {
                    haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    surfaceBridge.sendTextToPty("\t")
                },
                onSendCtrlC = {
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    surfaceBridge.sendControlKey('C')
                },
                onSendCtrlD = {
                    haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    surfaceBridge.sendControlKey('D')
                },
                onSendQuickCommand = { cmd ->
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    surfaceBridge.sendCommandLine(cmd)
                },
            )
        }
    }
}

@Composable
private fun TouchExtraKeysBar(
    onSendEscape: () -> Unit,
    onSendTab: () -> Unit,
    onSendCtrlC: () -> Unit,
    onSendCtrlD: () -> Unit,
    onSendQuickCommand: (String) -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 8.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            AssistChip(onClick = onSendEscape, label = { Text("ESC", fontFamily = FontFamily.Monospace) })
            AssistChip(onClick = onSendTab, label = { Text("TAB", fontFamily = FontFamily.Monospace) })
            AssistChip(onClick = onSendCtrlC, label = { Text("CTRL+C", fontFamily = FontFamily.Monospace) })
            AssistChip(onClick = onSendCtrlD, label = { Text("CTRL+D", fontFamily = FontFamily.Monospace) })
            AssistChip(onClick = { onSendQuickCommand("agy --skill senior-auditor") }, label = { Text("agy audit", fontFamily = FontFamily.Monospace) })
            AssistChip(onClick = { onSendQuickCommand("claude") }, label = { Text("claude", fontFamily = FontFamily.Monospace) })
            AssistChip(onClick = { onSendQuickCommand("zellij") }, label = { Text("zellij", fontFamily = FontFamily.Monospace) })
            AssistChip(onClick = { onSendQuickCommand("melcat-gc --snapshot") }, label = { Text("zstd-snap", fontFamily = FontFamily.Monospace) })
        }
    }
}

@Composable
private fun CrdtSessionsPanel(
    sessions: List<AgentSessionRecord>,
    siteId: String,
    onRunAgentCommand: (String) -> Unit,
    onNewSession: () -> Unit,
    onExportP2pDeltas: () -> Unit,
    onArchiveSession: (String) -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Text(
                    text = "Local-First CRDT Store (WAL + Lamport Clock)",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = "Node: $siteId · Sovereign P2P Delta Sync",
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.primary,
                )
                Spacer(modifier = Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = onNewSession) {
                        Text("+ New Context", fontSize = 12.sp)
                    }
                    OutlinedButton(onClick = onExportP2pDeltas) {
                        Text("Export P2P Deltas", fontSize = 12.sp)
                    }
                }
            }
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(sessions, key = { it.id }) { session ->
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = "${session.title} (${session.agentType})",
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                text = "L#${session.lamportVersion} · ${session.siteId}",
                                fontFamily = FontFamily.Monospace,
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.secondary,
                            )
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = session.compactedSummary,
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            AssistChip(
                                onClick = { onRunAgentCommand("${session.agentType} --resume ${session.id.take(8)}") },
                                label = { Text("Dispatch to PTY", fontSize = 11.sp) },
                            )
                            TextButton(onClick = { onArchiveSession(session.id) }) {
                                Text("Tombstone", fontSize = 11.sp, color = MaterialTheme.colorScheme.error)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun GuardianAndTokenPanel(
    guardianStatus: GuardianStatus,
    tokenState: OAuthTokenState,
    onToggleWakeLock: () -> Unit,
    onRequestBatteryExclusion: () -> Unit,
    onOpenHyperOsAutostart: () -> Unit,
    onCopyPhantomAdbCommand: () -> Unit,
    onOpenTokenProvisionDialog: () -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Security & Token Warden (chmod 0600)", fontWeight = FontWeight.Bold)
                    Text(
                        text = "Token: ${tokenState.maskedPreview} · Mode: ${tokenState.posixModeOctal} · SHA256: ${tokenState.fingerprintSha256}",
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        color = if (tokenState.isPresent) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.error,
                    )
                    Text(
                        text = "Path: ${tokenState.storagePath}",
                        fontFamily = FontFamily.Monospace,
                        fontSize = 10.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Button(onClick = onOpenTokenProvisionDialog) {
                        Text(if (tokenState.isPresent) "Rotate / Sync OAuth Token" else "Provision antigravity-oauth-token")
                    }
                }
            }
        }

        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("ProcessGuardian (HyperOS / Phantom Killer Shield)", fontWeight = FontWeight.Bold)
                    Text(
                        text = "WakeLock: ${if (guardianStatus.wakeLockHeld) "HELD" else "OFF"} · High-Perf Wi-Fi: ${if (guardianStatus.wifiLockHeld) "HELD" else "OFF"} · Battery Unrestricted: ${guardianStatus.batteryOptimizationIgnored}",
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Text(
                        text = "Phantom Process Limit: ${guardianStatus.maxPhantomProcesses}",
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = onToggleWakeLock) {
                            Text(if (guardianStatus.wakeLockHeld) "Release Locks" else "Acquire Wake+WiFi")
                        }
                        OutlinedButton(onClick = onRequestBatteryExclusion) {
                            Text("No Battery Doze")
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = onOpenHyperOsAutostart) {
                            Text("HyperOS Autostart")
                        }
                        OutlinedButton(onClick = onCopyPhantomAdbCommand) {
                            Text("Copy Phantom ADB Fix")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun StorageOptimizerPanel(
    metrics: StorageMetrics,
    onRunMelcatGc: () -> Unit,
    onCreateZstdSnapshot: () -> Unit,
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("StorageOptimizer (zstd -T0 + melcat-gc)", fontWeight = FontWeight.Bold)
            Text(
                text = "Prefix: ${metrics.prefixPath}",
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = "Free: ${metrics.freeFormatted} (${metrics.usedPercent}% used) · Snapshots: ${metrics.snapshotsCount} · zstd bin: ${if (metrics.zstdAvailable) "YES" else "FALLBACK"}",
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.secondary,
            )
            Text(
                text = "Status: ${metrics.lastGcSummary}",
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onRunMelcatGc) {
                    Text("Run melcat-gc")
                }
                OutlinedButton(onClick = onCreateZstdSnapshot) {
                    Text("Snapshot (tar --zstd)")
                }
            }
        }
    }
}

@Composable
private fun WorkstationStatusBar(
    ptyCount: Int,
    crdtSiteId: String,
    tokenState: OAuthTokenState,
    guardianStatus: GuardianStatus,
    storageMetrics: StorageMetrics,
    statusNotice: String,
) {
    Surface(
        color = Color(0xFF050811),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "PTY:$ptyCount | CRDT:$crdtSiteId | OAuth:${if (tokenState.isPresent) "0600" else "NONE"} | Wake:${if (guardianStatus.wakeLockHeld) "ON" else "OFF"} | Free:${storageMetrics.freeFormatted}",
                fontFamily = FontFamily.Monospace,
                fontSize = 10.sp,
                color = MaterialTheme.colorScheme.primary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = statusNotice,
                fontFamily = FontFamily.Monospace,
                fontSize = 10.sp,
                color = MaterialTheme.colorScheme.secondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun CommandPaletteDialog(
    onDismiss: () -> Unit,
    onExecuteCommand: (String) -> Unit,
) {
    val commands = listOf(
        "agy --skill senior-auditor" to "Run Senior Auditor check in current workspace",
        "agy --skill motion-framer" to "Launch Motion Framer Compose assistant",
        "agy --skill caveman" to "Run ultra-concise Caveman terminal mode",
        "claude" to "Start interactive Claude CLI session",
        "melcat-gc --snapshot" to "Create portable zstd workspace snapshot",
        "melcat-gc" to "Purge apt/pip/npm caches and compress old logs with zstd -T0",
        "zellij" to "Launch Zellij multiplexer (120Hz hardware keyboard ready)",
    )

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Workstation Command Palette (⌘K)") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                commands.forEach { (cmd, desc) ->
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onExecuteCommand(cmd) },
                    ) {
                        Column(modifier = Modifier.padding(10.dp)) {
                            Text(cmd, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                            Text(desc, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Close") }
        },
    )
}

@Composable
private fun TokenProvisionDialog(
    currentState: OAuthTokenState,
    onDismiss: () -> Unit,
    onSaveToken: (String) -> Unit,
    onRevokeToken: () -> Unit,
) {
    var inputToken by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Antigravity OAuth Token Warden") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "Stores antigravity-oauth-token in private app storage with strict 0600 permissions and syncs to ~/.config/antigravity/ for CLI inheritance.",
                    fontSize = 12.sp,
                )
                OutlinedTextField(
                    value = inputToken,
                    onValueChange = { inputToken = it },
                    label = { Text("Paste antigravity-oauth-token") },
                    singleLine = true,
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
                    TextButton(onClick = onRevokeToken) {
                        Text("Revoke", color = MaterialTheme.colorScheme.error)
                    }
                }
                TextButton(onClick = onDismiss) {
                    Text("Cancel")
                }
            }
        },
    )
}
