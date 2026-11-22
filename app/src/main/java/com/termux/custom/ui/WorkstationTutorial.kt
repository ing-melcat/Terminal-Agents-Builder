package com.termux.custom.ui

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

/**
 * Pasos guiados de configuración inicial y beta-test para la estación nativa ARM64.
 */
enum class TutorialStep(
    val id: String,
    val title: String,
    val body: String,
    val actionLabel: String,
) {
    ARM64_ENGINE_SETUP(
        id = "arm64_engine_setup",
        title = "1. Motor ARM64 y agy setup",
        body = "Verifica que el runtime Linux ARM64 esté extraído en \$PREFIX y ejecuta `agy setup` para instalar el toolchain nativo: git, nodejs-lts, zstd, zsh, tmux, ripgrep y jq. Luego `agy doctor` confirma que todo esté [ok] y `agy upgrade` actualiza los paquetes pendientes.",
        actionLabel = "agy setup && agy doctor",
    ),
    ACTIVATE_SKILLS(
        id = "activate_skills",
        title = "2. Activar skills unificadas",
        body = "Las skills viven en ~/.gemini/config/skills y se enlazan simbólicamente a ~/.claude/skills (senior-auditor, motion-framer, caveman). Ejecuta `agy skills` para comprobarlas; agrega nuevas creando una carpeta con SKILL.md.",
        actionLabel = "agy skills",
    ),
    OAUTH_VAULT_0600(
        id = "oauth_vault_0600",
        title = "3. Configurar OAuth Vault 0600",
        body = "Guarda tu token OAuth de forma atómica con permisos POSIX 0600. Puedes hacerlo aquí (diálogo Vault) o en la terminal con `agy login` (entrada oculta). `agy token` verifica el estado y `agy logout` lo borra. Los agentes CLI lo heredan y queda excluido de snapshots.",
        actionLabel = "Abrir Vault",
    ),
    HYPEROS_PHANTOM_SHIELD(
        id = "hyperos_phantom_shield",
        title = "4. Shield HyperOS / Phantom Killer",
        body = "Alcanza el 100% en Shield activando WakeLock + Wi-Fi de baja latencia, excluyendo la app del modo Doze, habilitando Autostart en HyperOS/MIUI y aplicando el comando ADB contra Phantom Process Killer (cópialo y ejecútalo desde una PC con adb).",
        actionLabel = "Ir a Shield",
    ),
    PTY_SESSIONS_SHORTCUTS(
        id = "pty_sessions_shortcuts",
        title = "5. PTY multi-tab, Extra Keys y atajos",
        body = "Abre múltiples pestañas PTY independientes con el botón `+`, cambia entre ellas y ciérralas. Usa la barra táctil Extra Keys (ESC, TAB, CTRL, ALT, flechas) o atajos físicos: ^⇧P paleta, ^⇧T nueva sesión, ^⇧B modo Zen, ^Tab siguiente sesión, ^= / ^- zoom.",
        actionLabel = "Nueva PTY",
    ),
    CRDT_WORKSPACES(
        id = "crdt_workspaces",
        title = "6. Workspaces CRDT (crear/exportar/importar)",
        body = "Gestiona contextos con relojes de Lamport en SQLite WAL: crea workspaces, exporta deltas JSON P2P entre tu móvil y tablet e importa deltas remotos sin conflictos.",
        actionLabel = "Ir a Agents",
    ),
    STORAGE_ZSTD_SNAPSHOT(
        id = "storage_zstd_snapshot",
        title = "7. Storage: limpiar caches y snapshot zstd",
        body = "Libera espacio purgando caches de apt, pip y npm con `melcat-gc` y genera snapshots portables `.tar.zst` (sin tokens OAuth, protegidos con 0600). Requiere zstd (paso 1).",
        actionLabel = "Ir a Storage",
    ),
    OTA_UPDATER(
        id = "ota_updater",
        title = "8. Actualizador OTA y PAT privado",
        body = "Comprueba nuevas versiones en GitHub Releases con verificación SHA-256 usando «Check Now». Si el repositorio es privado, configura un PAT de solo lectura desde «Private PAT».",
        actionLabel = "Comprobar OTA",
    ),
}

/**
 * Persistencia ligera en SharedPreferences ('melcat_tutorial') con funciones puras testeables.
 */
object TutorialProgressStore {
    const val PREFS_NAME = "melcat_tutorial"
    const val KEY_COMPLETED_STEPS = "completed_steps"
    const val KEY_AUTO_OFFERED = "auto_offered"

    private val validIds: Set<String> by lazy {
        TutorialStep.entries.map { it.id }.toSet()
    }

    fun filterValidIds(rawIds: Set<String>): Set<String> =
        rawIds.filterTo(LinkedHashSet()) { it in validIds }

    fun toggleStepId(
        currentCompleted: Set<String>,
        stepId: String,
        done: Boolean,
    ): Set<String> {
        val normalized = filterValidIds(currentCompleted)
        if (stepId !in validIds) return normalized
        return if (done) normalized + stepId else normalized - stepId
    }

    fun computeProgressFraction(completed: Set<String>): Float {
        val total = TutorialStep.entries.size
        if (total == 0) return 0f
        val validCount = filterValidIds(completed).size
        return (validCount.toFloat() / total.toFloat()).coerceIn(0f, 1f)
    }

    fun isAllCompleted(completed: Set<String>): Boolean =
        filterValidIds(completed).size >= TutorialStep.entries.size

    fun shouldAutoOffer(
        autoOffered: Boolean,
        completed: Set<String>,
    ): Boolean = !autoOffered && !isAllCompleted(completed)

    fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun loadCompletedIds(prefs: SharedPreferences): Set<String> =
        filterValidIds(prefs.getStringSet(KEY_COMPLETED_STEPS, emptySet()).orEmpty())

    fun loadCompletedIds(context: Context): Set<String> = loadCompletedIds(prefs(context))

    fun saveCompletedIds(prefs: SharedPreferences, ids: Set<String>): Set<String> {
        val clean = filterValidIds(ids)
        // Copia defensiva: SharedPreferences no garantiza inmutabilidad sobre el Set pasado.
        prefs.edit().putStringSet(KEY_COMPLETED_STEPS, HashSet(clean)).apply()
        return clean
    }

    fun setStepCompleted(prefs: SharedPreferences, step: TutorialStep, done: Boolean): Set<String> {
        val updated = toggleStepId(loadCompletedIds(prefs), step.id, done)
        return saveCompletedIds(prefs, updated)
    }

    fun setStepCompleted(context: Context, step: TutorialStep, done: Boolean): Set<String> =
        setStepCompleted(prefs(context), step, done)

    fun isAutoOffered(prefs: SharedPreferences): Boolean = prefs.getBoolean(KEY_AUTO_OFFERED, false)

    fun setAutoOffered(prefs: SharedPreferences, offered: Boolean = true) {
        prefs.edit().putBoolean(KEY_AUTO_OFFERED, offered).apply()
    }

    fun setAutoOffered(context: Context, offered: Boolean = true) = setAutoOffered(prefs(context), offered)

    fun shouldAutoOffer(context: Context): Boolean {
        val sp = prefs(context)
        return shouldAutoOffer(autoOffered = isAutoOffered(sp), completed = loadCompletedIds(sp))
    }

    fun reset(prefs: SharedPreferences) {
        prefs.edit().remove(KEY_COMPLETED_STEPS).putBoolean(KEY_AUTO_OFFERED, false).apply()
    }

    fun reset(context: Context) = reset(prefs(context))
}

@Composable
fun FirstStepsTutorialSheet(
    visible: Boolean,
    completed: Set<String>,
    onAction: (TutorialStep) -> Unit,
    onToggleDone: (TutorialStep, Boolean) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (!visible) return

    val haptics = LocalHapticFeedback.current
    val validCompleted = TutorialProgressStore.filterValidIds(completed)
    val completedCount = validCompleted.size
    val totalCount = TutorialStep.entries.size
    val allDone = completedCount == totalCount
    val animatedProgress by animateFloatAsState(
        targetValue = TutorialProgressStore.computeProgressFraction(validCompleted),
        animationSpec = MotionTokens.TactileSpring,
        label = "TutorialProgress",
    )

    var expandedStepId by rememberSaveable {
        mutableStateOf<String?>(
            TutorialStep.entries.firstOrNull { it.id !in validCompleted }?.id
                ?: TutorialStep.entries.first().id
        )
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        Box(
            modifier = modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(horizontal = 12.dp, vertical = 10.dp),
            contentAlignment = Alignment.Center,
        ) {
            Surface(
                shape = RoundedCornerShape(18.dp),
                color = WorkstationColors.SurfaceBase,
                border = BorderStroke(1.dp, WorkstationColors.BorderStrong),
                shadowElevation = 12.dp,
                modifier = Modifier.widthIn(max = 720.dp).fillMaxSize(),
            ) {
                Column(modifier = Modifier.fillMaxSize()) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(WorkstationColors.SurfaceElevated)
                            .padding(horizontal = 16.dp, vertical = 14.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(34.dp)
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(WorkstationColors.AccentCyanWash),
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(
                                    imageVector = WorkstationIcons.TerminalPrompt,
                                    contentDescription = null,
                                    tint = WorkstationColors.AccentCyan,
                                    modifier = Modifier.size(18.dp),
                                )
                            }
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "Primeros pasos · Workstation",
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = WorkstationColors.TextPrimary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    text = "$completedCount/$totalCount completados",
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 11.sp,
                                    color = if (allDone) WorkstationColors.AccentEmerald else WorkstationColors.AccentCyan,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            TextButton(
                                onClick = {
                                    haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    onDismiss()
                                },
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                            ) {
                                Text(
                                    text = "Cerrar",
                                    fontSize = 12.sp,
                                    color = WorkstationColors.TextSecondary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }

                        LinearProgressIndicator(
                            progress = { animatedProgress },
                            color = if (allDone) WorkstationColors.AccentEmerald else WorkstationColors.AccentCyan,
                            trackColor = WorkstationColors.SurfaceInteractive,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(6.dp)
                                .clip(RoundedCornerShape(3.dp)),
                        )
                    }

                    HorizontalDivider(thickness = 1.dp, color = WorkstationColors.BorderHairline)

                    LazyColumn(
                        modifier = Modifier.weight(1f).fillMaxWidth(),
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        itemsIndexed(
                            items = TutorialStep.entries,
                            key = { _, step -> step.id },
                        ) { index, step ->
                            val isExpanded = expandedStepId == step.id
                            TutorialStepCard(
                                step = step,
                                stepNumber = index + 1,
                                isDone = step.id in validCompleted,
                                isExpanded = isExpanded,
                                onToggleExpand = {
                                    haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    expandedStepId = if (isExpanded) null else step.id
                                },
                                onToggleDone = { checked ->
                                    haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    onToggleDone(step, checked)
                                },
                                onAction = {
                                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                    onAction(step)
                                },
                            )
                        }
                    }

                    HorizontalDivider(thickness = 1.dp, color = WorkstationColors.BorderHairline)

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(WorkstationColors.SurfaceElevated)
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            text = if (allDone) "Estación lista al 100%" else "Toca un paso para ver detalles y probarlo",
                            fontSize = 11.sp,
                            color = if (allDone) WorkstationColors.AccentEmerald else WorkstationColors.TextMuted,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        OutlinedButton(
                            onClick = onDismiss,
                            border = BorderStroke(1.dp, WorkstationColors.BorderStrong),
                            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
                        ) {
                            Text(
                                text = "Volver a terminal",
                                fontSize = 12.sp,
                                color = WorkstationColors.AccentCyan,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TutorialStepCard(
    step: TutorialStep,
    stepNumber: Int,
    isDone: Boolean,
    isExpanded: Boolean,
    onToggleExpand: () -> Unit,
    onToggleDone: (Boolean) -> Unit,
    onAction: () -> Unit,
) {
    val containerColor by animateColorAsState(
        targetValue = when {
            isExpanded -> WorkstationColors.SurfaceInteractive
            isDone -> WorkstationColors.SurfaceElevated.copy(alpha = 0.78f)
            else -> WorkstationColors.SurfaceElevated
        },
        label = "StepCardBg",
    )
    val borderColor = when {
        isDone -> WorkstationColors.AccentEmerald.copy(alpha = 0.42f)
        isExpanded -> WorkstationColors.BorderStrong
        else -> WorkstationColors.BorderHairline
    }

    Surface(
        shape = RoundedCornerShape(14.dp),
        color = containerColor,
        border = BorderStroke(1.dp, borderColor),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onToggleExpand)
                    .padding(start = 12.dp, end = 6.dp, top = 8.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Box(
                    modifier = Modifier
                        .size(30.dp)
                        .clip(CircleShape)
                        .background(if (isDone) WorkstationColors.AccentEmeraldWash else WorkstationColors.AccentCyanWash),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = if (isDone) WorkstationIcons.CheckCircle else stepIcon(step),
                        contentDescription = null,
                        tint = if (isDone) WorkstationColors.AccentEmerald else WorkstationColors.AccentCyan,
                        modifier = Modifier.size(16.dp),
                    )
                }

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = step.title,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = if (isDone) WorkstationColors.TextSecondary else WorkstationColors.TextPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = "Paso $stepNumber · ${step.actionLabel}",
                        fontFamily = FontFamily.Monospace,
                        fontSize = 10.sp,
                        color = if (isDone) WorkstationColors.AccentEmerald else WorkstationColors.TextMuted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }

                Checkbox(
                    checked = isDone,
                    onCheckedChange = onToggleDone,
                    colors = CheckboxDefaults.colors(
                        checkedColor = WorkstationColors.AccentEmerald,
                        uncheckedColor = WorkstationColors.TextMuted,
                        checkmarkColor = WorkstationColors.VoidCanvas,
                    ),
                )
            }

            AnimatedVisibility(
                visible = isExpanded,
                enter = expandVertically(animationSpec = spring(dampingRatio = 0.85f, stiffness = 480f)) + fadeIn(),
                exit = shrinkVertically(animationSpec = spring(dampingRatio = 0.85f, stiffness = 480f)) + fadeOut(),
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 14.dp, end = 14.dp, bottom = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    HorizontalDivider(thickness = 1.dp, color = WorkstationColors.BorderHairline)

                    Text(
                        text = step.body,
                        fontSize = 12.sp,
                        lineHeight = 17.sp,
                        color = WorkstationColors.TextSecondary,
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = WorkstationColors.SurfaceKeycap,
                            border = BorderStroke(1.dp, WorkstationColors.BorderHairline),
                            modifier = Modifier.weight(1f, fill = false),
                        ) {
                            Text(
                                text = step.actionLabel,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 11.sp,
                                color = WorkstationColors.AccentCyan,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                            )
                        }

                        Spacer(modifier = Modifier.width(10.dp))

                        Button(
                            onClick = onAction,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = WorkstationColors.AccentCyan,
                                contentColor = WorkstationColors.VoidCanvas,
                            ),
                            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
                        ) {
                            Text(
                                text = "Probar ahora",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun stepIcon(step: TutorialStep): ImageVector = when (step) {
    TutorialStep.ARM64_ENGINE_SETUP -> WorkstationIcons.TerminalPrompt
    TutorialStep.ACTIVATE_SKILLS -> WorkstationIcons.CommandSpark
    TutorialStep.OAUTH_VAULT_0600 -> WorkstationIcons.StorageVault
    TutorialStep.HYPEROS_PHANTOM_SHIELD -> WorkstationIcons.ShieldGuard
    TutorialStep.PTY_SESSIONS_SHORTCUTS -> WorkstationIcons.Keyboard
    TutorialStep.CRDT_WORKSPACES -> WorkstationIcons.AgentsLayers
    TutorialStep.STORAGE_ZSTD_SNAPSHOT -> WorkstationIcons.StorageVault
    TutorialStep.OTA_UPDATER -> WorkstationIcons.CheckCircle
}
