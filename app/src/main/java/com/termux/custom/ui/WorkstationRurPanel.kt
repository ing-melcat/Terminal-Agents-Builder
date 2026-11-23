package com.termux.custom.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.termux.custom.rur.Rur2faSetupPayload
import com.termux.custom.rur.RurControlCatalog
import com.termux.custom.rur.RurControlCenterManager
import com.termux.custom.rur.RurControlState
import com.termux.custom.rur.RurEditableScriptSlot
import com.termux.custom.rur.RurModuleCategory
import com.termux.custom.rur.RurModuleSpec
import com.termux.custom.rur.RurStackSpec
import com.termux.custom.ssh.SshDeviceRecord

// ============================================================================================
// RUR ROS 2 CONTROL CENTER PANEL (PAGE_RUR DB Auth + LiveKit offboard_ws Receivers + AI in ROS)
// ============================================================================================
@Composable
internal fun RurControlCenterPanel(
    state: RurControlState,
    liveRos2Sessions: List<LivePtyTab>,
    sshDevices: List<SshDeviceRecord>,
    isAuthBusy: Boolean,
    authError: String?,
    isSyncBusy: Boolean,
    onLoginOperator: (matricula: String, credential: String, onSuccessClear: () -> Unit) -> Unit,
    onClearAuthError: () -> Unit,
    onLogoutOperator: () -> Unit,
    onSetOfflineBypass: (Boolean) -> Unit,
    onOpen2faSetup: () -> Unit,
    onSyncFleet: () -> Unit,
    onApplyDriftUpdate: () -> Unit,
    onSelectRobot: (String) -> Unit,
    onOpenAddRobotDialog: () -> Unit,
    onOpenConfigDialog: () -> Unit,
    onInstallRos2: () -> Unit,
    onOpenFoxgloveBrowser: () -> Unit,
    onLaunchAiInRos2: (String) -> Unit,
    onOpenCodingModelsDialog: () -> Unit,
    onLaunchModule: (RurModuleSpec) -> Unit,
    onLaunchStack: (RurStackSpec) -> Unit,
    onFocusSession: (Int) -> Unit,
    onCloseSession: (Int) -> Unit,
    onTeardownRosNodes: () -> Unit,
    onOpenLinkedPiSsh: (SshDeviceRecord) -> Unit,
) {
    val haptics = LocalHapticFeedback.current
    var consumerFilter by rememberSaveable { mutableStateOf("ALL") }

    val runningByTitle = remember(liveRos2Sessions) {
        liveRos2Sessions.associateBy { it.title }
    }
    val activeNodeCount = remember(liveRos2Sessions) {
        liveRos2Sessions.count { RurControlCenterManager.isRos2NodeSessionTitle(it.title) }
    }
    val activeLiveKitReceiverCount = remember(liveRos2Sessions) {
        liveRos2Sessions.count {
            RurControlCenterManager.isLiveKitReceiverSessionTitle(it.title) && it.isRunning
        }
    }
    val linkedPiDevice = remember(sshDevices, state.linkedPiSshDeviceId) {
        sshDevices.firstOrNull { it.id == state.linkedPiSshDeviceId }
    }
    val receiverModules = remember { RurControlCatalog.byCategory(RurModuleCategory.LIVEKIT_RECEIVERS) }
    val consumerModules = remember(consumerFilter) {
        when (consumerFilter) {
            "OFFBOARD" -> RurControlCatalog.byCategory(RurModuleCategory.OFFBOARD_STACK)
            "DIAG" -> RurControlCatalog.byCategory(RurModuleCategory.DIAGNOSTICS)
            else -> RurControlCatalog.byCategory(RurModuleCategory.OFFBOARD_STACK) +
                RurControlCatalog.byCategory(RurModuleCategory.DIAGNOSTICS)
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        contentPadding = PaddingValues(bottom = 14.dp),
    ) {
        // 1. Operador RUR (PAGE_RUR DB Auth — ONLY inside ROS 2 Space)
        item(key = "rur_auth_card") {
            RurOperatorAuthCard(
                state = state,
                isAuthBusy = isAuthBusy,
                authError = authError,
                isSyncBusy = isSyncBusy,
                onLogin = onLoginOperator,
                onClearAuthError = onClearAuthError,
                onLogout = onLogoutOperator,
                onSetOfflineBypass = onSetOfflineBypass,
                onOpen2faSetup = onOpen2faSetup,
                onSyncFleet = onSyncFleet,
                onApplyDrift = onApplyDriftUpdate,
                onOpenConfig = onOpenConfigDialog,
            )
        }

        // 2. Entorno Ubuntu 24.04 + ROS 2 Jazzy (offboard_ws)
        item(key = "rur_env_card") {
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = WorkstationColors.SurfaceElevated,
                border = BorderStroke(
                    1.dp,
                    if (state.ros2Ready) WorkstationColors.AccentEmerald.copy(alpha = 0.4f)
                    else WorkstationColors.AccentAmber.copy(alpha = 0.45f),
                ),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(if (state.ros2Ready) WorkstationColors.AccentEmerald else WorkstationColors.AccentAmber),
                        )
                        Text(
                            text = if (state.ros2Ready) "Ubuntu 24.04 · ROS 2 Jazzy READY" else "Entorno Ubuntu 24.04 + ROS 2 Jazzy",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = WorkstationColors.TextPrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = WorkstationColors.SurfaceKeycap,
                        ) {
                            Text(
                                text = "D${state.effectiveRosDomainId}",
                                fontFamily = FontFamily.Monospace,
                                fontSize = 10.sp,
                                color = WorkstationColors.AccentGold,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                            )
                        }
                    }

                    if (!state.ros2Ready) {
                        Text(
                            text = "Instalación opcional vía proot-distro: Ubuntu 24.04 + ROS 2 Jazzy + CycloneDDS + SLAM + Nav2 + Foxglove + puentes agy/opencode.",
                            fontSize = 11.sp,
                            color = WorkstationColors.TextSecondary,
                            lineHeight = 15.sp,
                        )
                        Button(
                            onClick = {
                                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                onInstallRos2()
                            },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = WorkstationColors.AccentEmerald,
                                contentColor = WorkstationColors.VoidCanvas,
                            ),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(
                                text = "Instalar Ubuntu 24.04 + ROS 2 Jazzy",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    } else {
                        Text(
                            text = "offboard_ws: ${
                                when {
                                    state.offboardWsBuilt -> "listo (install/setup.bash)"
                                    state.offboardWsPresent -> "presente (pendiente colcon build)"
                                    else -> "no detectado en ~/${state.offboardRepoPath}"
                                }
                            }",
                            fontFamily = FontFamily.Monospace,
                            fontSize = 10.sp,
                            color = WorkstationColors.TextSecondary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            OutlinedButton(
                                onClick = {
                                    RurControlCatalog.byId("ros2_shell")?.let(onLaunchModule)
                                },
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                                modifier = Modifier
                                    .weight(1f)
                                    .height(32.dp),
                            ) {
                                Text("Shell ROS 2", fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            OutlinedButton(
                                onClick = onOpenFoxgloveBrowser,
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                                modifier = Modifier
                                    .weight(1f)
                                    .height(32.dp),
                            ) {
                                Text("Foxglove 3D", fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
            }
        }

        // 3. Agentes IA dentro de Ubuntu ROS 2 (sobreviven a Teardown & Logout)
        item(key = "rur_ai_card") {
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = WorkstationColors.SurfaceElevated,
                border = BorderStroke(1.dp, WorkstationColors.BorderHairline),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = "Agentes IA en Ubuntu ROS 2",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = WorkstationColors.TextPrimary,
                    )
                    Text(
                        text = "Comparten \$HOME, skills (rur-ros2-rassor) y bóvedas 0600 viendo tus tópicos ROS 2 en vivo.",
                        fontSize = 11.sp,
                        color = WorkstationColors.TextSecondary,
                        lineHeight = 14.sp,
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Button(
                            onClick = { onLaunchAiInRos2("agy") },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = WorkstationColors.AccentGold,
                                contentColor = WorkstationColors.VoidCanvas,
                            ),
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                            modifier = Modifier
                                .weight(1f)
                                .height(34.dp),
                        ) {
                            Text("agy ROS", fontSize = 11.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        Button(
                            onClick = { onLaunchAiInRos2("opencode") },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = WorkstationColors.AccentEmerald,
                                contentColor = WorkstationColors.VoidCanvas,
                            ),
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                            modifier = Modifier
                                .weight(1f)
                                .height(34.dp),
                        ) {
                            Text("opencode", fontSize = 11.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        OutlinedButton(
                            onClick = onOpenCodingModelsDialog,
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                            modifier = Modifier.height(34.dp),
                        ) {
                            Text("Models", fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        }

        // 4. Flota + Teardown ROS + Acceso Directo SSH Pi (v1.2)
        item(key = "rur_fleet_card") {
            Surface(
                shape = RoundedCornerShape(12.dp),
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
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            text = "Flota · ${state.activeRobot.name}",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = WorkstationColors.TextPrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            text = "Sala: ${state.activeLiveKitRoom}",
                            fontFamily = FontFamily.Monospace,
                            fontSize = 10.sp,
                            color = WorkstationColors.TextSecondary,
                        )
                    }

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        state.robots.forEach { robot ->
                            val selected = robot.id == state.activeRobotId
                            val isOnline = robot.status.equals("online", ignoreCase = true)
                            FilterChip(
                                selected = selected,
                                enabled = !isSyncBusy,
                                onClick = {
                                    if (!selected && !isSyncBusy) {
                                        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                        onSelectRobot(robot.id)
                                    }
                                },
                                label = {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                                    ) {
                                        Box(
                                            modifier = Modifier
                                                .size(6.dp)
                                                .clip(CircleShape)
                                                .background(if (isOnline) WorkstationColors.AccentEmerald else WorkstationColors.TextMuted),
                                        )
                                        Text(
                                            text = "${robot.name} (D${robot.rosDomainId})",
                                            fontSize = 11.sp,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    }
                                },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = WorkstationColors.AccentEmeraldWash,
                                    selectedLabelColor = WorkstationColors.AccentEmerald,
                                ),
                            )
                        }
                        OutlinedButton(
                            onClick = onOpenAddRobotDialog,
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                            modifier = Modifier.height(32.dp),
                        ) {
                            Text("+ Robot", fontSize = 11.sp, maxLines = 1)
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        OutlinedButton(
                            onClick = {
                                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                onTeardownRosNodes()
                            },
                            enabled = activeNodeCount > 0,
                            border = BorderStroke(
                                1.dp,
                                if (activeNodeCount > 0) WorkstationColors.AccentRose.copy(alpha = 0.6f)
                                else WorkstationColors.BorderHairline,
                            ),
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                            modifier = Modifier
                                .weight(1f)
                                .height(34.dp),
                        ) {
                            Text(
                                text = "Teardown ROS ($activeNodeCount)",
                                fontSize = 11.sp,
                                color = if (activeNodeCount > 0) WorkstationColors.AccentRose else WorkstationColors.TextMuted,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        OutlinedButton(
                            onClick = {
                                if (linkedPiDevice != null) onOpenLinkedPiSsh(linkedPiDevice)
                                else onOpenConfigDialog()
                            },
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                            modifier = Modifier
                                .weight(1f)
                                .height(34.dp),
                        ) {
                            Text(
                                text = if (linkedPiDevice != null) "SSH: ${linkedPiDevice.name}" else "Vincular SSH Pi",
                                fontSize = 11.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
        }

        // 5. SECCIÓN #1 OBLIGATORIA: Puentes / Receptores LiveKit (offboard_ws)
        item(key = "rur_livekit_header") {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        text = "1. Receptores LiveKit (offboard_ws)",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = WorkstationColors.AccentGold,
                    )
                    TextButton(
                        onClick = onOpenConfigDialog,
                        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp),
                        modifier = Modifier.height(24.dp),
                    ) {
                        Text("Editar scripts", fontSize = 10.sp)
                    }
                }
                Surface(
                    shape = RoundedCornerShape(9.dp),
                    color = if (activeLiveKitReceiverCount > 0) WorkstationColors.AccentEmeraldWash else WorkstationColors.AccentAmberWash,
                    border = BorderStroke(
                        1.dp,
                        if (activeLiveKitReceiverCount > 0) WorkstationColors.AccentEmerald.copy(alpha = 0.45f)
                        else WorkstationColors.AccentAmber.copy(alpha = 0.45f),
                    ),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        text = if (activeLiveKitReceiverCount > 0) {
                            "● $activeLiveKitReceiverCount receptor(es) LiveKit corriendo — tópicos ROS 2 disponibles en consola."
                        } else {
                            "Sin receptor LiveKit activo. Inicia uno abajo para ver tópicos de nube de puntos, cámara y movimiento."
                        },
                        fontSize = 11.sp,
                        color = if (activeLiveKitReceiverCount > 0) WorkstationColors.AccentEmerald else WorkstationColors.AccentAmber,
                        lineHeight = 14.sp,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
                    )
                }
            }
        }

        items(receiverModules, key = { "rx_${it.id}" }) { module ->
            val liveTab = runningByTitle[module.tabTitle]
            RurModuleCard(
                module = module,
                resolvedCmd = state.resolvedCommandFor(module),
                liveTab = liveTab,
                highlightReceiver = true,
                onLaunch = { onLaunchModule(module) },
                onFocus = { liveTab?.let { onFocusSession(it.index) } },
                onStop = { liveTab?.let { onCloseSession(it.index) } },
            )
        }

        // 6. Stacks 1-Toque (Receptor + Consumidor juntos)
        item(key = "rur_stacks_row") {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    text = "2. Stacks 1-Toque (Receptor + Herramienta)",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = WorkstationColors.TextPrimary,
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    RurControlCatalog.stacks.forEach { stack ->
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = WorkstationColors.SurfaceElevated,
                            border = BorderStroke(1.dp, WorkstationColors.BorderStrong),
                            modifier = Modifier
                                .widthIn(min = 164.dp, max = 210.dp)
                                .clickable {
                                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                    onLaunchStack(stack)
                                },
                        ) {
                            Column(
                                modifier = Modifier.padding(10.dp),
                                verticalArrangement = Arrangement.spacedBy(3.dp),
                            ) {
                                Text(
                                    text = stack.label,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = WorkstationColors.AccentGold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    text = stack.subtitle,
                                    fontSize = 10.sp,
                                    color = WorkstationColors.TextSecondary,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                    lineHeight = 13.sp,
                                )
                            }
                        }
                    }
                }
            }
        }

        // 7. Consumidores offboard_ws & Diagnóstico
        item(key = "rur_consumers_header") {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    text = "3. Nodos offboard_ws y Diagnóstico",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = WorkstationColors.TextPrimary,
                )
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    listOf("ALL" to "Todos", "OFFBOARD" to "Offboard ROS 2", "DIAG" to "Diagnóstico").forEach { (key, label) ->
                        FilterChip(
                            selected = consumerFilter == key,
                            onClick = { consumerFilter = key },
                            label = { Text(label, fontSize = 11.sp, maxLines = 1) },
                        )
                    }
                }
            }
        }

        items(consumerModules, key = { "mod_${it.id}" }) { module ->
            val liveTab = runningByTitle[module.tabTitle]
            RurModuleCard(
                module = module,
                resolvedCmd = state.resolvedCommandFor(module),
                liveTab = liveTab,
                highlightReceiver = false,
                onLaunch = { onLaunchModule(module) },
                onFocus = { liveTab?.let { onFocusSession(it.index) } },
                onStop = { liveTab?.let { onCloseSession(it.index) } },
            )
        }
    }
}

@Composable
private fun RurOperatorAuthCard(
    state: RurControlState,
    isAuthBusy: Boolean,
    authError: String?,
    isSyncBusy: Boolean,
    onLogin: (String, String, () -> Unit) -> Unit,
    onClearAuthError: () -> Unit,
    onLogout: () -> Unit,
    onSetOfflineBypass: (Boolean) -> Unit,
    onOpen2faSetup: () -> Unit,
    onSyncFleet: () -> Unit,
    onApplyDrift: () -> Unit,
    onOpenConfig: () -> Unit,
) {
    var matricula by rememberSaveable { mutableStateOf("") }
    var credential by remember { mutableStateOf("") }
    val operator = state.operatorSession
    DisposableEffect(Unit) {
        onDispose { credential = "" }
    }

    Surface(
        shape = RoundedCornerShape(12.dp),
        color = WorkstationColors.SurfaceElevated,
        border = BorderStroke(
            1.dp,
            if (state.isAuthenticated) WorkstationColors.AccentEmerald.copy(alpha = 0.45f)
            else WorkstationColors.BorderStrong,
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (state.isAuthenticated && operator != null) {
                // Authenticated Operator Card
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(WorkstationColors.AccentEmerald),
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = operator.displayName,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = WorkstationColors.TextPrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = "${operator.matricula}${if (operator.grupo.isNotBlank()) " · ${operator.grupo}" else ""}",
                            fontFamily = FontFamily.Monospace,
                            fontSize = 10.sp,
                            color = WorkstationColors.TextSecondary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = if (operator.canOperate) WorkstationColors.AccentGoldWash else WorkstationColors.SurfaceKeycap,
                        border = BorderStroke(1.dp, if (operator.canOperate) WorkstationColors.BorderStrong else WorkstationColors.BorderHairline),
                    ) {
                        Text(
                            text = operator.primaryRoleLabel,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (operator.canOperate) WorkstationColors.AccentGold else WorkstationColors.TextSecondary,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                        )
                    }
                }

                // LiveKit RAM Token + Sync summary
                Text(
                    text = "Token RAM: ${if (state.hasLiveRamToken) "ACTIVO (${state.liveTokenSource})" else "local .env"} · Sync: ${if (state.syncStatus.ok) "OK" else state.syncStatus.code}",
                    fontFamily = FontFamily.Monospace,
                    fontSize = 10.sp,
                    color = if (state.hasLiveRamToken) WorkstationColors.AccentEmerald else WorkstationColors.TextSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )

                if (!operator.totpEnabled || operator.mustChangePassword) {
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = WorkstationColors.AccentAmberWash,
                        border = BorderStroke(1.dp, WorkstationColors.AccentAmber.copy(alpha = 0.45f)),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Text(
                                text = "2FA Authenticator pendiente en PAGE_RUR",
                                fontSize = 10.sp,
                                color = WorkstationColors.AccentAmber,
                                modifier = Modifier.weight(1f),
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                            TextButton(
                                onClick = onOpen2faSetup,
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                                modifier = Modifier.height(26.dp),
                            ) {
                                Text("Activar 2FA", fontSize = 10.sp, color = WorkstationColors.AccentGold)
                            }
                        }
                    }
                }

                state.syncStatus.pendingDriftNotice?.let { drift ->
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = WorkstationColors.AccentAmberWash,
                        border = BorderStroke(1.dp, WorkstationColors.AccentAmber.copy(alpha = 0.5f)),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Text(
                                text = drift,
                                fontSize = 10.sp,
                                color = WorkstationColors.AccentAmber,
                                modifier = Modifier.weight(1f),
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                            TextButton(
                                onClick = onApplyDrift,
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                                modifier = Modifier.height(26.dp),
                            ) {
                                Text("Aplicar", fontSize = 10.sp, color = WorkstationColors.AccentGold)
                            }
                        }
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    OutlinedButton(
                        onClick = onSyncFleet,
                        enabled = !isSyncBusy,
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                        modifier = Modifier
                            .weight(1f)
                            .height(32.dp),
                    ) {
                        Text(if (isSyncBusy) "Sync…" else "Sync BD", fontSize = 11.sp, maxLines = 1)
                    }
                    OutlinedButton(
                        onClick = onOpenConfig,
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                        modifier = Modifier.height(32.dp),
                    ) {
                        Text("⚙", fontSize = 12.sp)
                    }
                    OutlinedButton(
                        onClick = onLogout,
                        border = BorderStroke(1.dp, WorkstationColors.AccentRose.copy(alpha = 0.5f)),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                        modifier = Modifier.height(32.dp),
                    ) {
                        Text("Salir", fontSize = 11.sp, color = WorkstationColors.AccentRose, maxLines = 1)
                    }
                }
            } else if (state.offlineBypassEnabled) {
                // Collapsed Local/Offline Mode Banner
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Modo Local (.env) · Sin sesión PAGE_RUR",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = WorkstationColors.TextPrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = if (state.hasDeviceKey) "Device Key 0600 activa (${state.deviceKeyPreview})" else "Usando variables locales del repo",
                            fontFamily = FontFamily.Monospace,
                            fontSize = 10.sp,
                            color = WorkstationColors.TextSecondary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    TextButton(
                        onClick = { onSetOfflineBypass(false) },
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                        modifier = Modifier.height(30.dp),
                    ) {
                        Text("Login RUR", fontSize = 11.sp, color = WorkstationColors.AccentGold)
                    }
                    OutlinedButton(
                        onClick = onOpenConfig,
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                        modifier = Modifier.height(30.dp),
                    ) {
                        Text("⚙", fontSize = 12.sp)
                    }
                }
            } else {
                // Inline Operator Login paired to PAGE_RUR PostgreSQL `users` table
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Acceso Operador RUR (PAGE_RUR)",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = WorkstationColors.AccentGold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = state.webApiUrl.removePrefix("https://").removePrefix("http://"),
                            fontFamily = FontFamily.Monospace,
                            fontSize = 10.sp,
                            color = WorkstationColors.TextSecondary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    OutlinedButton(
                        onClick = onOpenConfig,
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                        modifier = Modifier.height(30.dp),
                    ) {
                        Text("⚙", fontSize = 12.sp)
                    }
                }

                OutlinedTextField(
                    value = matricula,
                    onValueChange = {
                        matricula = it
                        if (authError != null) onClearAuthError()
                    },
                    label = { Text("Matrícula (BD RUR)", fontSize = 11.sp) },
                    singleLine = true,
                    enabled = !isAuthBusy,
                    textStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.sp),
                    keyboardOptions = KeyboardOptions(
                        autoCorrectEnabled = false,
                        keyboardType = KeyboardType.Ascii,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = credential,
                    onValueChange = {
                        credential = it
                        if (authError != null) onClearAuthError()
                    },
                    label = { Text("Contraseña o código 2FA (6 dígitos)", fontSize = 11.sp) },
                    singleLine = true,
                    enabled = !isAuthBusy,
                    visualTransformation = PasswordVisualTransformation(),
                    textStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.sp),
                    keyboardOptions = KeyboardOptions(
                        autoCorrectEnabled = false,
                        keyboardType = KeyboardType.Password,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )

                if (!authError.isNullOrBlank()) {
                    Text(
                        text = authError,
                        fontSize = 11.sp,
                        color = WorkstationColors.AccentRose,
                        lineHeight = 14.sp,
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Button(
                        onClick = {
                            onLogin(matricula.trim(), credential.trim()) {
                                credential = ""
                            }
                        },
                        enabled = !isAuthBusy && matricula.isNotBlank() && credential.isNotBlank(),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = WorkstationColors.AccentGold,
                            contentColor = WorkstationColors.VoidCanvas,
                        ),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                        modifier = Modifier
                            .weight(1f)
                            .height(36.dp),
                    ) {
                        Text(
                            text = if (isAuthBusy) "Verificando…" else "Iniciar sesión RUR",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    TextButton(
                        onClick = { onSetOfflineBypass(true) },
                        enabled = !isAuthBusy,
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                        modifier = Modifier.height(36.dp),
                    ) {
                        Text("Local", fontSize = 11.sp, color = WorkstationColors.TextSecondary)
                    }
                }
            }
        }
    }
}

@Composable
private fun RurModuleCard(
    module: RurModuleSpec,
    resolvedCmd: String,
    liveTab: LivePtyTab?,
    highlightReceiver: Boolean,
    onLaunch: () -> Unit,
    onFocus: () -> Unit,
    onStop: () -> Unit,
) {
    val haptics = LocalHapticFeedback.current
    val isActive = liveTab != null && liveTab.isRunning

    Surface(
        shape = RoundedCornerShape(11.dp),
        color = WorkstationColors.SurfaceElevated,
        border = BorderStroke(
            1.dp,
            when {
                isActive -> WorkstationColors.AccentEmerald.copy(alpha = 0.55f)
                highlightReceiver -> WorkstationColors.BorderStrong
                else -> WorkstationColors.BorderHairline
            },
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    text = module.label,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = WorkstationColors.TextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Surface(
                    shape = RoundedCornerShape(5.dp),
                    color = if (isActive) WorkstationColors.AccentEmeraldWash else WorkstationColors.SurfaceKeycap,
                ) {
                    Text(
                        text = if (isActive) "● ACTIVO" else module.badge,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 9.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = if (isActive) WorkstationColors.AccentEmerald else WorkstationColors.AccentGold,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                    )
                }
            }

            Text(
                text = module.description,
                fontSize = 11.sp,
                color = WorkstationColors.TextSecondary,
                lineHeight = 14.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )

            if (resolvedCmd.isNotBlank()) {
                Text(
                    text = "$ $resolvedCmd",
                    fontFamily = FontFamily.Monospace,
                    fontSize = 10.sp,
                    color = WorkstationColors.TextMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (isActive) {
                    OutlinedButton(
                        onClick = onFocus,
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp),
                        modifier = Modifier
                            .weight(1f)
                            .height(30.dp),
                    ) {
                        Text("Ver consola", fontSize = 11.sp, maxLines = 1)
                    }
                    OutlinedButton(
                        onClick = {
                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            onStop()
                        },
                        border = BorderStroke(1.dp, WorkstationColors.AccentRose.copy(alpha = 0.55f)),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp),
                        modifier = Modifier.height(30.dp),
                    ) {
                        Text("Detener", fontSize = 11.sp, color = WorkstationColors.AccentRose, maxLines = 1)
                    }
                } else {
                    Button(
                        onClick = {
                            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            onLaunch()
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (highlightReceiver) WorkstationColors.AccentGold else WorkstationColors.SurfaceInteractive,
                            contentColor = if (highlightReceiver) WorkstationColors.VoidCanvas else WorkstationColors.TextPrimary,
                        ),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 2.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(30.dp),
                    ) {
                        Text(
                            text = if (liveTab != null) "Relanzar" else "Lanzar",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                        )
                    }
                }
            }
        }
    }
}

// ============================================================================================
// HOISTED IME-SAFE DIALOGS: RUR CONFIG (WITH EDITABLE RECEIVER SCRIPTS), 2FA SETUP, ADD ROBOT
// ============================================================================================
@Composable
internal fun RurConfigDialog(
    state: RurControlState,
    sshDevices: List<SshDeviceRecord>,
    isSaving: Boolean,
    errorText: String?,
    onDismiss: () -> Unit,
    onSave: (
        webApiUrl: String,
        deviceKeyInput: String?,
        defaultRosDomainId: Int,
        offboardRepoPath: String,
        scriptsPath: String,
        linkedPiSshDeviceId: String,
        customScripts: Map<String, String>,
    ) -> Unit,
) {
    var webApiUrl by rememberSaveable(state.webApiUrl) { mutableStateOf(state.webApiUrl) }
    var domainText by rememberSaveable(state.defaultRosDomainId) { mutableStateOf(state.defaultRosDomainId.toString()) }
    var deviceKeyInput by remember { mutableStateOf("") }
    var clearDeviceKey by remember { mutableStateOf(false) }
    var repoPath by rememberSaveable(state.offboardRepoPath) { mutableStateOf(state.offboardRepoPath) }
    var scriptsPath by rememberSaveable(state.scriptsPath) { mutableStateOf(state.scriptsPath) }
    var linkedPiId by rememberSaveable(state.linkedPiSshDeviceId) { mutableStateOf(state.linkedPiSshDeviceId) }
    val scriptEdits = remember(state.customScripts) {
        mutableStateMapOf<String, String>().apply {
            RurEditableScriptSlot.entries.forEach { slot ->
                put(slot.key, state.customScripts[slot.key].orEmpty().ifBlank { slot.defaultCommand })
            }
        }
    }
    var localErr by remember { mutableStateOf<String?>(null) }

    Dialog(
        onDismissRequest = { if (!isSaving) onDismiss() },
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .imePadding()
                .padding(horizontal = 16.dp, vertical = 10.dp),
            contentAlignment = Alignment.Center,
        ) {
            val isCompact = maxHeight < 380.dp
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = WorkstationColors.SurfaceElevated,
                border = BorderStroke(1.dp, WorkstationColors.BorderStrong),
                modifier = Modifier
                    .fillMaxWidth()
                    .widthIn(max = 540.dp)
                    .heightIn(max = (maxHeight * 0.94f).coerceAtLeast(240.dp)),
            ) {
                Column(
                    modifier = Modifier.padding(if (isCompact) 12.dp else 16.dp),
                    verticalArrangement = Arrangement.spacedBy(if (isCompact) 8.dp else 10.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            text = "Configuración ROS 2 & PAGE_RUR",
                            fontSize = 15.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = WorkstationColors.TextPrimary,
                        )
                        TextButton(
                            onClick = {
                                RurEditableScriptSlot.entries.forEach { slot ->
                                    scriptEdits[slot.key] = slot.defaultCommand
                                }
                            },
                            enabled = !isSaving,
                            contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp),
                        ) {
                            Text("Defaults", fontSize = 11.sp)
                        }
                    }

                    Column(
                        modifier = Modifier
                            .weight(1f, fill = false)
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        OutlinedTextField(
                            value = webApiUrl,
                            onValueChange = { webApiUrl = it; localErr = null },
                            label = { Text("URL Plataforma PAGE_RUR", fontSize = 11.sp) },
                            singleLine = true,
                            textStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.sp),
                            keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, keyboardType = KeyboardType.Uri),
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedTextField(
                                value = domainText,
                                onValueChange = { domainText = it.filter(Char::isDigit).take(3); localErr = null },
                                label = { Text("ROS_DOMAIN_ID", fontSize = 11.sp) },
                                singleLine = true,
                                textStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.sp),
                                keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, keyboardType = KeyboardType.Number),
                                modifier = Modifier.weight(0.4f),
                            )
                            OutlinedTextField(
                                value = repoPath,
                                onValueChange = { repoPath = it; localErr = null },
                                label = { Text("Repo rover_rassor_project-", fontSize = 11.sp) },
                                singleLine = true,
                                textStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 11.sp),
                                keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, keyboardType = KeyboardType.Ascii),
                                modifier = Modifier.weight(0.6f),
                            )
                        }
                        OutlinedTextField(
                            value = scriptsPath,
                            onValueChange = { scriptsPath = it; localErr = null },
                            label = { Text("Carpeta scripts/ (movimiento)", fontSize = 11.sp) },
                            singleLine = true,
                            textStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 11.sp),
                            keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, keyboardType = KeyboardType.Ascii),
                            modifier = Modifier.fillMaxWidth(),
                        )

                        // Optional Control Center Device Key (0600)
                        OutlinedTextField(
                            value = deviceKeyInput,
                            onValueChange = {
                                deviceKeyInput = it
                                clearDeviceKey = false
                                localErr = null
                            },
                            label = {
                                Text(
                                    text = if (state.hasDeviceKey && !clearDeviceKey) {
                                        "Device Key 0600 (${state.deviceKeyPreview}) — opcional"
                                    } else {
                                        "Device Key Centro de Control (0600, opcional)"
                                    },
                                    fontSize = 11.sp,
                                )
                            },
                            singleLine = true,
                            visualTransformation = PasswordVisualTransformation(),
                            textStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 11.sp),
                            keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, keyboardType = KeyboardType.Password),
                            modifier = Modifier.fillMaxWidth(),
                        )
                        if (state.hasDeviceKey && !clearDeviceKey) {
                            TextButton(
                                onClick = { clearDeviceKey = true; deviceKeyInput = "" },
                                contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp),
                                modifier = Modifier.height(24.dp),
                            ) {
                                Text("Eliminar Device Key guardada", fontSize = 10.sp, color = WorkstationColors.AccentRose)
                            }
                        }

                        HorizontalDivider(color = WorkstationColors.BorderHairline)
                        Text(
                            text = "Comandos de Receptores LiveKit (editables)",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = WorkstationColors.AccentGold,
                        )
                        RurEditableScriptSlot.entries.forEach { slot ->
                            OutlinedTextField(
                                value = scriptEdits[slot.key].orEmpty(),
                                onValueChange = { scriptEdits[slot.key] = it; localErr = null },
                                label = { Text(slot.label, fontSize = 10.sp) },
                                singleLine = true,
                                textStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 11.sp),
                                keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, keyboardType = KeyboardType.Ascii),
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }

                        HorizontalDivider(color = WorkstationColors.BorderHairline)
                        Text(
                            text = "Acceso directo SSH Pi (reutiliza tus perfiles SSH Pro)",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = WorkstationColors.TextPrimary,
                        )
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            FilterChip(
                                selected = linkedPiId.isEmpty(),
                                onClick = { linkedPiId = "" },
                                label = { Text("Ninguno", fontSize = 11.sp) },
                            )
                            sshDevices.forEach { dev ->
                                FilterChip(
                                    selected = linkedPiId == dev.id,
                                    onClick = { linkedPiId = dev.id },
                                    label = { Text(dev.name, fontSize = 11.sp, maxLines = 1) },
                                )
                            }
                        }
                    }

                    val shownError = localErr ?: errorText
                    if (!shownError.isNullOrBlank()) {
                        Text(text = shownError, fontSize = 11.sp, color = WorkstationColors.AccentRose)
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        TextButton(onClick = onDismiss, enabled = !isSaving) {
                            Text("Cancelar")
                        }
                        Spacer(modifier = Modifier.size(6.dp))
                        Button(
                            onClick = {
                                val parsedDomain = domainText.toIntOrNull()
                                if (parsedDomain == null || parsedDomain !in 0..232) {
                                    localErr = "ROS_DOMAIN_ID debe estar entre 0 y 232."
                                    return@Button
                                }
                                val keyArg: String? = when {
                                    clearDeviceKey -> ""
                                    deviceKeyInput.isNotBlank() -> deviceKeyInput.trim()
                                    else -> null
                                }
                                onSave(
                                    webApiUrl.trim(),
                                    keyArg,
                                    parsedDomain,
                                    repoPath.trim(),
                                    scriptsPath.trim(),
                                    linkedPiId,
                                    scriptEdits.toMap(),
                                )
                            },
                            enabled = !isSaving,
                        ) {
                            Text(if (isSaving) "Guardando…" else "Guardar")
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun Rur2faSetupDialog(
    payload: Rur2faSetupPayload?,
    isBusy: Boolean,
    errorText: String?,
    onCopySecret: (String) -> Unit,
    onOpenAuthenticatorUri: (String) -> Unit,
    onVerifyCode: (code: String, secret: String) -> Unit,
    onDismiss: () -> Unit,
) {
    var code by remember { mutableStateOf("") }

    Dialog(
        onDismissRequest = { if (!isBusy) onDismiss() },
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .imePadding()
                .padding(horizontal = 16.dp, vertical = 10.dp),
            contentAlignment = Alignment.Center,
        ) {
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = WorkstationColors.SurfaceElevated,
                border = BorderStroke(1.dp, WorkstationColors.BorderStrong),
                modifier = Modifier
                    .fillMaxWidth()
                    .widthIn(max = 460.dp)
                    .heightIn(max = (maxHeight * 0.92f).coerceAtLeast(220.dp)),
            ) {
                Column(
                    modifier = Modifier
                        .padding(16.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text(
                        text = "Activar 2FA (Google Authenticator)",
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = WorkstationColors.AccentGold,
                    )
                    if (payload == null) {
                        Text(
                            text = if (isBusy) "Generando clave 2FA en PAGE_RUR…" else (errorText ?: "Sin respuesta del servidor."),
                            fontSize = 12.sp,
                            color = if (errorText != null) WorkstationColors.AccentRose else WorkstationColors.TextSecondary,
                        )
                    } else {
                        Text(
                            text = "Agrega esta clave en Google Authenticator y luego escribe el código de 6 dígitos:",
                            fontSize = 11.sp,
                            color = WorkstationColors.TextSecondary,
                        )
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = WorkstationColors.SurfaceKeycap,
                            border = BorderStroke(1.dp, WorkstationColors.BorderHairline),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(
                                text = payload.secret,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold,
                                color = WorkstationColors.AccentEmerald,
                                modifier = Modifier.padding(10.dp),
                            )
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(
                                onClick = { onCopySecret(payload.secret) },
                                modifier = Modifier.weight(1f),
                            ) {
                                Text("Copiar clave", fontSize = 11.sp)
                            }
                            if (payload.otpauthUri.startsWith("otpauth://")) {
                                OutlinedButton(
                                    onClick = { onOpenAuthenticatorUri(payload.otpauthUri) },
                                    modifier = Modifier.weight(1f),
                                ) {
                                    Text("Abrir Authenticator", fontSize = 11.sp)
                                }
                            }
                        }
                        OutlinedTextField(
                            value = code,
                            onValueChange = { code = it.filter(Char::isDigit).take(6) },
                            label = { Text("Código de 6 dígitos", fontSize = 11.sp) },
                            singleLine = true,
                            textStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 14.sp),
                            keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, keyboardType = KeyboardType.Number),
                            modifier = Modifier.fillMaxWidth(),
                        )
                        if (!errorText.isNullOrBlank()) {
                            Text(text = errorText, fontSize = 11.sp, color = WorkstationColors.AccentRose)
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                    ) {
                        TextButton(onClick = onDismiss, enabled = !isBusy) {
                            Text("Cerrar")
                        }
                        if (payload != null) {
                            Spacer(modifier = Modifier.size(6.dp))
                            Button(
                                onClick = { onVerifyCode(code, payload.secret) },
                                enabled = !isBusy && code.length == 6,
                            ) {
                                Text(if (isBusy) "Verificando…" else "Activar 2FA")
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun AddLocalRurRobotDialog(
    defaultDomainId: Int,
    isSaving: Boolean,
    errorText: String?,
    onDismiss: () -> Unit,
    onSave: (name: String, room: String, domainId: Int, livekitUrl: String) -> Unit,
) {
    var name by rememberSaveable { mutableStateOf("") }
    var room by rememberSaveable { mutableStateOf("room-1") }
    var domainText by rememberSaveable { mutableStateOf(defaultDomainId.toString()) }
    var livekitUrl by rememberSaveable { mutableStateOf("wss://") }
    var localErr by remember { mutableStateOf<String?>(null) }

    Dialog(
        onDismissRequest = { if (!isSaving) onDismiss() },
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .imePadding()
                .padding(horizontal = 16.dp, vertical = 10.dp),
            contentAlignment = Alignment.Center,
        ) {
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = WorkstationColors.SurfaceElevated,
                border = BorderStroke(1.dp, WorkstationColors.BorderStrong),
                modifier = Modifier
                    .fillMaxWidth()
                    .widthIn(max = 460.dp)
                    .heightIn(max = (maxHeight * 0.92f).coerceAtLeast(220.dp)),
            ) {
                Column(
                    modifier = Modifier
                        .padding(16.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = "Agregar Robot Local",
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = WorkstationColors.TextPrimary,
                    )
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it; localErr = null },
                        label = { Text("Nombre (ej. RASSOR-2)", fontSize = 11.sp) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = room,
                            onValueChange = { room = it; localErr = null },
                            label = { Text("Sala LiveKit", fontSize = 11.sp) },
                            singleLine = true,
                            textStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.sp),
                            keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, keyboardType = KeyboardType.Ascii),
                            modifier = Modifier.weight(0.6f),
                        )
                        OutlinedTextField(
                            value = domainText,
                            onValueChange = { domainText = it.filter(Char::isDigit).take(3); localErr = null },
                            label = { Text("Domain ID", fontSize = 11.sp) },
                            singleLine = true,
                            textStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.sp),
                            keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, keyboardType = KeyboardType.Number),
                            modifier = Modifier.weight(0.4f),
                        )
                    }
                    OutlinedTextField(
                        value = livekitUrl,
                        onValueChange = { livekitUrl = it; localErr = null },
                        label = { Text("URL LiveKit (wss://... opcional)", fontSize = 11.sp) },
                        singleLine = true,
                        textStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 11.sp),
                        keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, keyboardType = KeyboardType.Uri),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    val shownErr = localErr ?: errorText
                    if (!shownErr.isNullOrBlank()) {
                        Text(text = shownErr, fontSize = 11.sp, color = WorkstationColors.AccentRose)
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                    ) {
                        TextButton(onClick = onDismiss, enabled = !isSaving) { Text("Cancelar") }
                        Spacer(modifier = Modifier.size(6.dp))
                        Button(
                            onClick = {
                                val dom = domainText.toIntOrNull()
                                if (dom == null || dom !in 0..232) {
                                    localErr = "ROS_DOMAIN_ID inválido (0..232)"
                                    return@Button
                                }
                                val lk = livekitUrl.trim().takeUnless { it == "wss://" } ?: ""
                                onSave(name.trim(), room.trim(), dom, lk)
                            },
                            enabled = !isSaving && name.isNotBlank() && room.isNotBlank(),
                        ) {
                            Text(if (isSaving) "Guardando…" else "Agregar")
                        }
                    }
                }
            }
        }
    }
}
