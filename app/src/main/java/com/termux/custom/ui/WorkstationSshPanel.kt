package com.termux.custom.ui

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.spring
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.termux.custom.ssh.SshDeviceProbe
import com.termux.custom.ssh.SshDeviceRecord
import com.termux.custom.ssh.SshDeviceSource
import com.termux.custom.ssh.SshDevicesManager
import com.termux.custom.ssh.SshPanelState
import com.termux.custom.ssh.SshReachability
import com.termux.custom.ssh.SshTransport

internal enum class SshFilter(val label: String) {
    ALL("Todos"),
    ONLINE("Disponibles"),
    TAILSCALE("Tailscale"),
    DIRECT("Directo"),
}

@Composable
internal fun SshDevicesPanel(
    state: SshPanelState,
    activeFilterName: String,
    isKeygenBusy: Boolean,
    onSelectFilter: (String) -> Unit,
    onScanDevices: () -> Unit,
    onInstallOpenSsh: () -> Unit,
    onOpenTailscaleApp: () -> Unit,
    onCopyPublicKey: () -> Unit,
    onGenerateSshKey: () -> Unit,
    onConnectDevice: (SshDeviceRecord) -> Unit,
    onPushPublicKey: (SshDeviceRecord) -> Unit,
    onRequestAddDevice: () -> Unit,
    onRequestEditDevice: (SshDeviceRecord) -> Unit,
    onRequestDeleteDevice: (SshDeviceRecord) -> Unit,
) {
    val activeFilter = SshFilter.entries.firstOrNull { it.name == activeFilterName } ?: SshFilter.ALL

    val sortedDevices = remember(state.devices, state.probes) { state.sortedDevices }
    val filteredDevices = remember(sortedDevices, state.probes, activeFilter) {
        when (activeFilter) {
            SshFilter.ALL -> sortedDevices
            SshFilter.ONLINE -> sortedDevices.filter {
                state.probes[it.id]?.reachability == SshReachability.ONLINE
            }
            SshFilter.TAILSCALE -> sortedDevices.filter { it.transport == SshTransport.TAILSCALE }
            SshFilter.DIRECT -> sortedDevices.filter { it.transport == SshTransport.DIRECT }
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        // 1. SSH & Tailscale Environment Hub Card
        item(key = "ssh_hub_header") {
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = WorkstationColors.SurfaceElevated,
                border = BorderStroke(1.dp, WorkstationColors.BorderStrong),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(9.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = "Dispositivos SSH",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = WorkstationColors.TextPrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = if (state.onlineCount > 0) WorkstationColors.AccentEmeraldWash else WorkstationColors.SurfaceKeycap,
                        ) {
                            Text(
                                text = "${state.onlineCount}/${state.devices.size} ONLINE",
                                fontFamily = FontFamily.Monospace,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (state.onlineCount > 0) WorkstationColors.AccentEmerald else WorkstationColors.TextSecondary,
                                maxLines = 1,
                                softWrap = false,
                                modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp),
                            )
                        }
                    }

                    // Tailscale VPN + OpenSSH live indicators (compact 28dp pills)
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = WorkstationColors.SurfaceKeycap,
                        border = BorderStroke(1.dp, WorkstationColors.BorderHairline),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                            verticalArrangement = Arrangement.spacedBy(7.dp),
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(7.dp),
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(7.dp)
                                        .clip(CircleShape)
                                        .background(
                                            if (state.isTailscaleActive) WorkstationColors.AccentEmerald
                                            else WorkstationColors.TextMuted
                                        )
                                )
                                Text(
                                    text = if (state.isTailscaleActive) {
                                        "Tailscale VPN · ${state.tailscaleLocalIp}"
                                    } else {
                                        "Tailscale VPN · Inactiva"
                                    },
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 10.sp,
                                    color = if (state.isTailscaleActive) WorkstationColors.AccentEmerald else WorkstationColors.TextSecondary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f),
                                )
                                CompactHubActionPill(
                                    label = if (state.isTailscaleActive) "Tailscale" else "Activar",
                                    accentColor = WorkstationColors.AccentGold,
                                    onClick = onOpenTailscaleApp,
                                )
                            }

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(7.dp),
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(7.dp)
                                        .clip(CircleShape)
                                        .background(
                                            if (state.opensshInstalled) WorkstationColors.AccentEmerald
                                            else WorkstationColors.AccentAmber
                                        )
                                )
                                Text(
                                    text = when {
                                        !state.opensshInstalled -> "OpenSSH no instalado"
                                        state.defaultPublicKeyName != null -> "OpenSSH listo · ~/.ssh/${state.defaultPublicKeyName}"
                                        else -> "OpenSSH listo · sin clave ED25519"
                                    },
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 10.sp,
                                    color = if (state.opensshInstalled) WorkstationColors.TextSecondary else WorkstationColors.AccentAmber,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f),
                                )
                                when {
                                    !state.opensshInstalled -> {
                                        CompactHubActionPill(
                                            label = "Instalar",
                                            accentColor = WorkstationColors.AccentAmber,
                                            onClick = onInstallOpenSsh,
                                        )
                                    }
                                    state.defaultPublicKey != null -> {
                                        CompactHubActionPill(
                                            label = "Copiar Key",
                                            accentColor = WorkstationColors.AccentGold,
                                            onClick = onCopyPublicKey,
                                        )
                                    }
                                    else -> {
                                        CompactHubActionPill(
                                            label = if (isKeygenBusy) "Creando…" else "Crear Key",
                                            accentColor = WorkstationColors.AccentGold,
                                            enabled = !isKeygenBusy,
                                            onClick = onGenerateSshKey,
                                        )
                                    }
                                }
                            }
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Button(
                            onClick = onRequestAddDevice,
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp),
                            modifier = Modifier.weight(1f),
                        ) {
                            Text(
                                text = "+ Agregar",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                softWrap = false,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        OutlinedButton(
                            onClick = onScanDevices,
                            enabled = !state.isScanning,
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp),
                            border = BorderStroke(1.dp, WorkstationColors.BorderStrong),
                            modifier = Modifier.weight(1f),
                        ) {
                            Text(
                                text = if (state.isScanning) "Probando…" else "Escanear",
                                fontSize = 12.sp,
                                color = WorkstationColors.AccentGold,
                                maxLines = 1,
                                softWrap = false,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
        }

        // 2. Filter Chips (All / Online Now / Tailscale / Direct)
        item(key = "ssh_filter_chips") {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                SshFilter.entries.forEach { filter ->
                    val selected = filter == activeFilter
                    val count = when (filter) {
                        SshFilter.ALL -> state.devices.size
                        SshFilter.ONLINE -> state.onlineCount
                        SshFilter.TAILSCALE -> state.devices.count { it.transport == SshTransport.TAILSCALE }
                        SshFilter.DIRECT -> state.devices.count { it.transport == SshTransport.DIRECT }
                    }
                    FilterChip(
                        selected = selected,
                        onClick = { onSelectFilter(filter.name) },
                        label = {
                            Text(
                                text = "${filter.label} ($count)",
                                fontFamily = FontFamily.Monospace,
                                fontSize = 10.sp,
                                maxLines = 1,
                                softWrap = false,
                                overflow = TextOverflow.Ellipsis,
                            )
                        },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = WorkstationColors.AccentGoldWash,
                            selectedLabelColor = WorkstationColors.AccentGold,
                            containerColor = WorkstationColors.SurfaceKeycap,
                            labelColor = WorkstationColors.TextSecondary,
                        ),
                        border = FilterChipDefaults.filterChipBorder(
                            enabled = true,
                            selected = selected,
                            borderColor = WorkstationColors.BorderHairline,
                            selectedBorderColor = WorkstationColors.BorderStrong,
                        ),
                    )
                }
            }
        }

        // 3. Devices List or Empty State
        if (filteredDevices.isEmpty()) {
            item(key = "ssh_empty_state") {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = WorkstationColors.SurfaceElevated,
                    border = BorderStroke(1.dp, WorkstationColors.BorderHairline),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            text = if (state.devices.isEmpty()) {
                                "Sin dispositivos SSH agregados"
                            } else {
                                "Ningún dispositivo coincide con \"${activeFilter.label}\""
                            },
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = WorkstationColors.TextPrimary,
                        )
                        Text(
                            text = if (state.devices.isEmpty()) {
                                "Agrega tus Jetson, Raspberry Pi, PCs o servidores por IP directa (LAN) o por Tailscale (100.x.y.z / MagicDNS). También se detectan automáticamente los hosts de ~/.ssh/config."
                            } else if (activeFilter == SshFilter.ONLINE) {
                                "Toca \"Escanear\" para comprobar qué equipos responden ahora mismo en su puerto SSH, o cambia el filtro a \"Todos\"."
                            } else {
                                "Cambia de filtro o agrega un nuevo dispositivo."
                            },
                            fontSize = 11.sp,
                            color = WorkstationColors.TextSecondary,
                        )
                        if (activeFilter != SshFilter.ALL && state.devices.isNotEmpty()) {
                            OutlinedButton(
                                onClick = { onSelectFilter(SshFilter.ALL.name) },
                                border = BorderStroke(1.dp, WorkstationColors.BorderStrong),
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                            ) {
                                Text("Ver todos (${state.devices.size})", fontSize = 11.sp, color = WorkstationColors.AccentGold)
                            }
                        }
                    }
                }
            }
        } else {
            items(filteredDevices, key = { it.id }) { device ->
                SshDeviceCard(
                    device = device,
                    probe = state.probes[device.id],
                    isScanning = state.isScanning,
                    opensshInstalled = state.opensshInstalled,
                    hasPublicKey = state.defaultPublicKey != null || device.identityFile.isNotBlank(),
                    onConnect = { onConnectDevice(device) },
                    onPushKey = { onPushPublicKey(device) },
                    onEdit = { onRequestEditDevice(device) },
                    onDelete = { onRequestDeleteDevice(device) },
                )
            }
        }
    }
}

@Composable
private fun CompactHubActionPill(
    label: String,
    accentColor: Color,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(7.dp),
        color = accentColor.copy(alpha = if (enabled) 0.14f else 0.06f),
        border = BorderStroke(1.dp, accentColor.copy(alpha = if (enabled) 0.45f else 0.2f)),
        modifier = Modifier
            .height(26.dp)
            .clip(RoundedCornerShape(7.dp))
            .clickable(enabled = enabled, onClick = onClick),
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.padding(horizontal = 8.dp),
        ) {
            Text(
                text = label,
                fontSize = 10.sp,
                fontWeight = FontWeight.SemiBold,
                color = if (enabled) accentColor else WorkstationColors.TextMuted,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun SshDeviceCard(
    device: SshDeviceRecord,
    probe: SshDeviceProbe?,
    isScanning: Boolean,
    opensshInstalled: Boolean,
    hasPublicKey: Boolean,
    onConnect: () -> Unit,
    onPushKey: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    val reachability = when {
        isScanning -> SshReachability.CHECKING
        probe != null -> probe.reachability
        else -> null
    }
    val statusDotColor = when (reachability) {
        SshReachability.ONLINE -> WorkstationColors.AccentEmerald
        SshReachability.CHECKING -> WorkstationColors.AccentGold
        SshReachability.NEEDS_TAILSCALE -> WorkstationColors.AccentAmber
        SshReachability.OFFLINE, null -> WorkstationColors.TextMuted
    }
    val statusBadgeBg = when (reachability) {
        SshReachability.ONLINE -> WorkstationColors.AccentEmeraldWash
        SshReachability.CHECKING -> WorkstationColors.AccentGoldWash
        SshReachability.NEEDS_TAILSCALE -> WorkstationColors.AccentAmberWash
        SshReachability.OFFLINE, null -> WorkstationColors.SurfaceKeycap
    }
    val statusLabel = when (reachability) {
        SshReachability.ONLINE -> probe?.latencyMs?.let { "ONLINE · ${it}ms" } ?: "ONLINE"
        SshReachability.CHECKING -> "PROBANDO…"
        SshReachability.NEEDS_TAILSCALE -> "TAILSCALE OFF"
        SshReachability.OFFLINE -> "OFFLINE"
        null -> "SIN PROBAR"
    }

    Surface(
        shape = RoundedCornerShape(12.dp),
        color = WorkstationColors.SurfaceElevated,
        border = BorderStroke(
            1.dp,
            when (reachability) {
                SshReachability.ONLINE -> WorkstationColors.AccentEmerald.copy(alpha = 0.35f)
                else -> WorkstationColors.BorderHairline
            },
        ),
        modifier = Modifier
            .fillMaxWidth()
            .animateContentSize(spring(0.86f, 480f)),
    ) {
        Column(
            modifier = Modifier.padding(11.dp),
            verticalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            // Row 1: Status dot + Device name + Reachability badge
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(7.dp),
            ) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(statusDotColor)
                )
                Text(
                    text = device.name,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = WorkstationColors.TextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Surface(
                    shape = RoundedCornerShape(5.dp),
                    color = statusBadgeBg,
                ) {
                    Text(
                        text = statusLabel,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold,
                        color = statusDotColor,
                        maxLines = 1,
                        softWrap = false,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                    )
                }
            }

            // Row 2: Target monospace + transport pill
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    text = device.targetDisplay,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    color = WorkstationColors.TextSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Surface(
                    shape = RoundedCornerShape(5.dp),
                    color = if (device.transport == SshTransport.TAILSCALE) {
                        WorkstationColors.AccentGoldWash
                    } else {
                        WorkstationColors.SurfaceKeycap
                    },
                ) {
                    Text(
                        text = device.transport.shortBadge,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 9.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = if (device.transport == SshTransport.TAILSCALE) {
                            WorkstationColors.AccentGold
                        } else {
                            WorkstationColors.TextSecondary
                        },
                        maxLines = 1,
                        softWrap = false,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                    )
                }
            }

            if (device.tags.isNotBlank() || device.identityFile.isNotBlank() || device.localPortForwards.isNotBlank() || device.startupCommand.isNotBlank()) {
                val meta = buildString {
                    if (device.tags.isNotBlank()) append(device.tags)
                    if (device.identityFile.isNotBlank()) {
                        if (isNotEmpty()) append(" · ")
                        append("key: ${device.identityFile}")
                    }
                    if (device.localPortForwards.isNotBlank()) {
                        if (isNotEmpty()) append(" · ")
                        append("-L ${device.localPortForwards}")
                    }
                    if (device.startupCommand.isNotBlank()) {
                        if (isNotEmpty()) append(" · ")
                        append("cmd: ${device.startupCommand}")
                    }
                }
                Text(
                    text = meta,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 10.sp,
                    color = WorkstationColors.TextMuted,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            // Row 3: Primary Connect + 1-tap ssh-copy-id (Key ->)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (reachability == SshReachability.ONLINE) {
                    Button(
                        onClick = onConnect,
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                        modifier = Modifier.weight(1f),
                    ) {
                        Text(
                            text = if (opensshInstalled) "Conectar" else "Instalar SSH",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            softWrap = false,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                } else {
                    OutlinedButton(
                        onClick = onConnect,
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                        border = BorderStroke(1.dp, WorkstationColors.BorderStrong),
                        modifier = Modifier.weight(1f),
                    ) {
                        Text(
                            text = if (opensshInstalled) "Conectar" else "Instalar SSH",
                            fontSize = 11.sp,
                            color = WorkstationColors.AccentGold,
                            maxLines = 1,
                            softWrap = false,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }

                if (opensshInstalled) {
                    OutlinedButton(
                        onClick = onPushKey,
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp),
                        border = BorderStroke(1.dp, WorkstationColors.BorderHairline),
                    ) {
                        Text(
                            text = if (hasPublicKey) "Enviar Key" else "Key →",
                            fontSize = 10.sp,
                            color = WorkstationColors.AccentCyan,
                            maxLines = 1,
                            softWrap = false,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }

            // Row 4: Secondary compact Edit/Save + Delete
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(
                    onClick = onEdit,
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                ) {
                    Text(
                        text = if (device.source == SshDeviceSource.SSH_CONFIG) "Guardar perfil" else "Editar",
                        fontSize = 11.sp,
                        color = WorkstationColors.TextSecondary,
                        maxLines = 1,
                        softWrap = false,
                    )
                }

                if (device.source == SshDeviceSource.SAVED) {
                    TextButton(
                        onClick = onDelete,
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                    ) {
                        Text(
                            text = "Borrar",
                            fontSize = 11.sp,
                            color = WorkstationColors.AccentRose,
                            maxLines = 1,
                            softWrap = false,
                        )
                    }
                }
            }
        }
    }
}

@Composable
internal fun DeleteSshDeviceConfirmDialog(
    device: SshDeviceRecord,
    onDismiss: () -> Unit,
    onConfirmDelete: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = WorkstationColors.SurfaceElevated,
        title = {
            Text(
                text = "Eliminar dispositivo SSH",
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                color = WorkstationColors.TextPrimary,
            )
        },
        text = {
            Text(
                text = "¿Eliminar \"${device.name}\" (${device.targetDisplay}) de tu lista de dispositivos conocidos?",
                fontSize = 12.sp,
                color = WorkstationColors.TextSecondary,
            )
        },
        confirmButton = {
            Button(
                onClick = onConfirmDelete,
                colors = ButtonDefaults.buttonColors(
                    containerColor = WorkstationColors.AccentRose,
                    contentColor = WorkstationColors.VoidCanvas,
                ),
            ) {
                Text("Eliminar", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancelar", fontSize = 12.sp, color = WorkstationColors.TextSecondary)
            }
        },
    )
}

@Composable
internal fun AddEditSshDeviceDialog(
    initial: SshDeviceRecord?,
    isSaving: Boolean = false,
    onDismiss: () -> Unit,
    onSave: (
        name: String,
        host: String,
        port: Int,
        user: String,
        transport: SshTransport,
        identity: String,
        tags: String,
        localPortForwards: String,
        startupCommand: String,
        onError: (String) -> Unit,
    ) -> Unit,
) {
    val formKey = initial?.id ?: "new"
    var name by rememberSaveable(formKey) { mutableStateOf(initial?.name ?: "") }
    var host by rememberSaveable(formKey) { mutableStateOf(initial?.host ?: "") }
    var portText by rememberSaveable(formKey) { mutableStateOf((initial?.port ?: 22).toString()) }
    var username by rememberSaveable(formKey) { mutableStateOf(initial?.username ?: "") }
    var transportName by rememberSaveable(formKey) {
        mutableStateOf((initial?.transport ?: SshTransport.DIRECT).name)
    }
    var userTouchedTransport by rememberSaveable(formKey) { mutableStateOf(initial != null) }
    var identityFile by rememberSaveable(formKey) { mutableStateOf(initial?.identityFile ?: "") }
    var tags by rememberSaveable(formKey) {
        mutableStateOf(
            if (initial?.source == SshDeviceSource.SSH_CONFIG) "" else initial?.tags ?: ""
        )
    }
    var localPortForwards by rememberSaveable(formKey) { mutableStateOf(initial?.localPortForwards ?: "") }
    var startupCommand by rememberSaveable(formKey) { mutableStateOf(initial?.startupCommand ?: "") }
    var errorText by rememberSaveable(formKey) { mutableStateOf<String?>(null) }

    val selectedTransport = SshTransport.entries.firstOrNull { it.name == transportName }
        ?: SshTransport.DIRECT

    val titleText = if (initial != null && initial.source == SshDeviceSource.SAVED) {
        "Editar dispositivo SSH"
    } else {
        "Agregar dispositivo SSH"
    }

    val fieldsContent: @Composable () -> Unit = {
        OutlinedTextField(
            value = name,
            onValueChange = {
                name = it.take(48)
                errorText = null
            },
            enabled = !isSaving,
            label = { Text("Nombre (ej. Jetson Orin, PC Lab)", fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )

        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                text = "Tipo de conexión",
                fontSize = 11.sp,
                color = WorkstationColors.TextSecondary,
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                SshTransport.entries.forEach { t ->
                    val selected = t == selectedTransport
                    FilterChip(
                        selected = selected,
                        enabled = !isSaving,
                        onClick = {
                            transportName = t.name
                            userTouchedTransport = true
                        },
                        label = {
                            Text(
                                text = t.label,
                                fontSize = 11.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = WorkstationColors.AccentGoldWash,
                            selectedLabelColor = WorkstationColors.AccentGold,
                            containerColor = WorkstationColors.SurfaceKeycap,
                            labelColor = WorkstationColors.TextSecondary,
                        ),
                        border = FilterChipDefaults.filterChipBorder(
                            enabled = !isSaving,
                            selected = selected,
                            borderColor = WorkstationColors.BorderHairline,
                            selectedBorderColor = WorkstationColors.BorderStrong,
                        ),
                    )
                }
            }
        }

        OutlinedTextField(
            value = host,
            onValueChange = { next ->
                host = next.trim().take(253)
                errorText = null
                if (!userTouchedTransport && next.isNotBlank()) {
                    transportName = if (SshDevicesManager.looksLikeTailscaleHost(next)) {
                        SshTransport.TAILSCALE.name
                    } else {
                        SshTransport.DIRECT.name
                    }
                }
            },
            enabled = !isSaving,
            label = {
                Text(
                    if (selectedTransport == SshTransport.TAILSCALE) {
                        "IP Tailscale (100.x.y.z) o MagicDNS (*.ts.net)"
                    } else {
                        "Host o IP (ej. 192.168.1.40)"
                    },
                    fontSize = 11.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            },
            singleLine = true,
            keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, keyboardType = KeyboardType.Uri),
            modifier = Modifier.fillMaxWidth(),
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedTextField(
                value = username,
                onValueChange = {
                    username = it.trim().take(64)
                    errorText = null
                },
                enabled = !isSaving,
                label = { Text("Usuario (ej. ubuntu)", fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, keyboardType = KeyboardType.Ascii),
                modifier = Modifier.weight(1f),
            )
            OutlinedTextField(
                value = portText,
                onValueChange = {
                    portText = it.filter { ch -> ch.isDigit() }.take(5)
                    errorText = null
                },
                enabled = !isSaving,
                label = { Text("Puerto", fontSize = 11.sp, maxLines = 1, softWrap = false) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.width(104.dp),
            )
        }

        OutlinedTextField(
            value = identityFile,
            onValueChange = {
                identityFile = it.trim().take(64)
                errorText = null
            },
            enabled = !isSaving,
            label = { Text("Clave en ~/.ssh/ (opcional, ej. id_ed25519)", fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, keyboardType = KeyboardType.Ascii),
            modifier = Modifier.fillMaxWidth(),
        )

        OutlinedTextField(
            value = localPortForwards,
            onValueChange = {
                localPortForwards = it.take(120)
                errorText = null
            },
            enabled = !isSaving,
            label = { Text("Túneles locales -L (ej. 11434:11434, 8888:8888)", fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, keyboardType = KeyboardType.Ascii),
            modifier = Modifier.fillMaxWidth(),
        )

        OutlinedTextField(
            value = startupCommand,
            onValueChange = {
                startupCommand = it.take(160)
                errorText = null
            },
            enabled = !isSaving,
            label = { Text("Comando al conectar (ej. tmux attach || tmux new)", fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, keyboardType = KeyboardType.Ascii),
            modifier = Modifier.fillMaxWidth(),
        )

        OutlinedTextField(
            value = tags,
            onValueChange = { tags = it.take(64) },
            enabled = !isSaving,
            label = { Text("Nota / Rol (opcional, ej. ROS2 · Lab)", fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )

        if (!errorText.isNullOrBlank()) {
            Text(
                text = errorText!!,
                fontSize = 11.sp,
                color = WorkstationColors.AccentRose,
            )
        }
    }

    val actionRow: @Composable () -> Unit = {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onDismiss, enabled = !isSaving) {
                Text("Cancelar", fontSize = 12.sp, color = WorkstationColors.TextSecondary)
            }
            Spacer(modifier = Modifier.width(6.dp))
            Button(
                onClick = {
                    val parsedPort = portText.toIntOrNull()
                    if (parsedPort == null || parsedPort !in 1..65535) {
                        errorText = "Puerto inválido (1-65535)"
                    } else {
                        onSave(
                            name,
                            host,
                            parsedPort,
                            username,
                            selectedTransport,
                            identityFile,
                            tags,
                            localPortForwards,
                            startupCommand,
                        ) { err -> errorText = err }
                    }
                },
                colors = ButtonDefaults.buttonColors(
                    containerColor = WorkstationColors.AccentGold,
                    contentColor = WorkstationColors.VoidCanvas,
                ),
                enabled = !isSaving && name.isNotBlank() && host.isNotBlank(),
            ) {
                Text(if (isSaving) "Guardando…" else "Guardar", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            }
        }
    }

    Dialog(
        onDismissRequest = { if (!isSaving) onDismiss() },
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        androidx.compose.foundation.layout.BoxWithConstraints(
            modifier = Modifier
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(horizontal = 10.dp, vertical = 8.dp),
            contentAlignment = Alignment.Center,
        ) {
            val isCompactHeight = maxHeight < 380.dp
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = WorkstationColors.SurfaceElevated,
                border = BorderStroke(1.dp, WorkstationColors.BorderStrong),
                modifier = Modifier
                    .fillMaxWidth()
                    .widthIn(max = 520.dp)
                    .heightIn(max = 620.dp),
            ) {
                if (isCompactHeight) {
                    // Tight post-IME / Split-Screen / Landscape height: unified scroll prevents 0dp input collapse
                    Column(
                        modifier = Modifier
                            .padding(10.dp)
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(7.dp),
                    ) {
                        Text(
                            text = titleText,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = WorkstationColors.TextPrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        fieldsContent()
                        HorizontalDivider(thickness = 1.dp, color = WorkstationColors.BorderHairline)
                        actionRow()
                    }
                } else {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Text(
                            text = titleText,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = WorkstationColors.TextPrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Column(
                            modifier = Modifier
                                .weight(1f, fill = false)
                                .verticalScroll(rememberScrollState()),
                            verticalArrangement = Arrangement.spacedBy(9.dp),
                        ) {
                            fieldsContent()
                        }
                        HorizontalDivider(thickness = 1.dp, color = WorkstationColors.BorderHairline)
                        actionRow()
                    }
                }
            }
        }
    }
}
