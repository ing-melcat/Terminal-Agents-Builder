package com.melcat.localfirst.feature.agents.presentation

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Clear
import androidx.compose.material.icons.outlined.CloudSync
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.melcat.localfirst.R
import com.melcat.localfirst.core.designsystem.MotionTokens
import com.melcat.localfirst.feature.agents.data.AgentDifferentialSyncWorker
import com.melcat.localfirst.feature.agents.presentation.components.AgentEditorSheet
import com.melcat.localfirst.feature.agents.presentation.components.TerminalAgentCard

@Composable
fun AgentsRoute(
    viewModel: AgentsViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current

    AgentsScreen(
        uiState = uiState,
        onEvent = { event ->
            viewModel.onEvent(event)
            if (event is AgentsUiEvent.TriggerDifferentialSync) {
                AgentDifferentialSyncWorker.enqueue(context)
            }
        },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AgentsScreen(
    uiState: AgentsUiState,
    onEvent: (AgentsUiEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    val haptics = LocalHapticFeedback.current

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = stringResource(R.string.app_name),
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Text(
                            text = "SQLite WAL · ${uiState.activeCount}/${uiState.totalCount} active · ${uiState.pendingSyncCount} pending sync",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                },
                actions = {
                    IconButton(
                        onClick = {
                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            onEvent(AgentsUiEvent.TriggerDifferentialSync)
                        },
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.CloudSync,
                            contentDescription = "Run differential sync",
                            tint = if (uiState.pendingSyncCount > 0) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        )
                    }
                },
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = {
                    haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    onEvent(AgentsUiEvent.OpenNewAgentEditor)
                },
                icon = {
                    Icon(
                        imageVector = Icons.Outlined.Add,
                        contentDescription = null,
                    )
                },
                text = { Text("New Agent") },
            )
        },
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 16.dp),
            ) {
                SearchAndFilterBar(
                    searchQuery = uiState.searchQuery,
                    activeFilterOnly = uiState.activeFilterOnly,
                    onSearchChange = { onEvent(AgentsUiEvent.SearchQueryChanged(it)) },
                    onToggleActiveFilter = {
                        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        onEvent(AgentsUiEvent.ToggleActiveFilter(it))
                    },
                    onSeedPresets = {
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        onEvent(AgentsUiEvent.SeedPresetAgents)
                    },
                )

                Spacer(modifier = Modifier.height(12.dp))

                AnimatedContent(
                    targetState = uiState.agents.isEmpty(),
                    transitionSpec = {
                        (fadeIn(animationSpec = MotionTokens.SnappySpringFloat) +
                            scaleIn(initialScale = 0.96f, animationSpec = MotionTokens.ExpressiveSpringFloat))
                            .togetherWith(fadeOut(animationSpec = MotionTokens.SnappySpringFloat))
                    },
                    label = "AgentsListOrEmptyState",
                    modifier = Modifier.weight(1f),
                ) { isEmpty ->
                    if (isEmpty) {
                        EmptyAgentsState(
                            hasQuery = uiState.searchQuery.isNotBlank() || uiState.activeFilterOnly,
                            onSeedPresets = {
                                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                onEvent(AgentsUiEvent.SeedPresetAgents)
                            },
                            onCreateNew = {
                                haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                onEvent(AgentsUiEvent.OpenNewAgentEditor)
                            },
                        )
                    } else {
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(bottom = 96.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            items(
                                items = uiState.agents,
                                key = { it.id },
                            ) { agent ->
                                TerminalAgentCard(
                                    agent = agent,
                                    isExpanded = uiState.expandedAgentId == agent.id,
                                    onToggleExpand = {
                                        onEvent(AgentsUiEvent.ToggleExpandCard(agent.id))
                                    },
                                    onToggleActive = { active ->
                                        onEvent(AgentsUiEvent.ToggleAgentActive(agent.id, active))
                                    },
                                    onEdit = {
                                        onEvent(AgentsUiEvent.OpenEditAgentEditor(agent))
                                    },
                                    onDelete = {
                                        onEvent(AgentsUiEvent.SoftDeleteAgent(agent))
                                    },
                                    modifier = Modifier.animateItem(
                                        fadeInSpec = MotionTokens.SnappySpringFloat,
                                        placementSpec = MotionTokens.ListPlacementSpring,
                                        fadeOutSpec = MotionTokens.SnappySpringFloat,
                                    ),
                                )
                            }
                        }
                    }
                }
            }

            AnimatedVisibility(
                visible = uiState.lastDeletedAgentId != null,
                enter = slideInVertically { it } + fadeIn(),
                exit = slideOutVertically { it } + fadeOut(),
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(horizontal = 16.dp, vertical = 88.dp),
            ) {
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = MaterialTheme.colorScheme.inverseSurface,
                    tonalElevation = 6.dp,
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            text = "Archived ${uiState.lastDeletedAgentName.orEmpty()} (soft-delete)",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.inverseOnSurface,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(
                            onClick = {
                                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                onEvent(AgentsUiEvent.UndoSoftDelete)
                            },
                        ) {
                            Text("UNDO")
                        }
                    }
                }
            }
        }
    }

    if (uiState.isEditorVisible) {
        AgentEditorSheet(
            draft = uiState.draft,
            errorMessage = uiState.errorMessage,
            onEvent = onEvent,
        )
    }
}

@Composable
private fun SearchAndFilterBar(
    searchQuery: String,
    activeFilterOnly: Boolean,
    onSearchChange: (String) -> Unit,
    onToggleActiveFilter: (Boolean) -> Unit,
    onSeedPresets: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = searchQuery,
            onValueChange = onSearchChange,
            placeholder = { Text("Search agents, CLI commands, or skills...") },
            leadingIcon = {
                Icon(
                    imageVector = Icons.Outlined.Search,
                    contentDescription = null,
                )
            },
            trailingIcon = {
                if (searchQuery.isNotEmpty()) {
                    IconButton(onClick = { onSearchChange("") }) {
                        Icon(
                            imageVector = Icons.Outlined.Clear,
                            contentDescription = "Clear search",
                        )
                    }
                }
            },
            singleLine = true,
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier.fillMaxWidth(),
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            FilterChip(
                selected = activeFilterOnly,
                onClick = { onToggleActiveFilter(!activeFilterOnly) },
                label = { Text("Active only") },
            )
            FilterChip(
                selected = false,
                onClick = onSeedPresets,
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Outlined.AutoAwesome,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                    )
                },
                label = { Text("Load agy presets") },
            )
        }
    }
}

@Composable
private fun EmptyAgentsState(
    hasQuery: Boolean,
    onSeedPresets: () -> Unit,
    onCreateNew: () -> Unit,
) {
    val pulseScale by animateFloatAsState(
        targetValue = 1f,
        animationSpec = MotionTokens.ExpressiveSpringFloat,
        label = "EmptyStatePulse",
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp)
            .graphicsLayer {
                scaleX = pulseScale
                scaleY = pulseScale
            },
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            imageVector = Icons.Outlined.Terminal,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(56.dp),
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = if (hasQuery) "No matching terminal agents" else "Local-First Agent Registry Empty",
            style = MaterialTheme.typography.headlineMedium,
            textAlign = TextAlign.Center,
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "All reads and writes hit Room (SQLite WAL) with zero network latency.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(modifier = Modifier.height(20.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = onSeedPresets) {
                Icon(
                    imageVector = Icons.Outlined.AutoAwesome,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text("Seed agy Agents")
            }
            TextButton(onClick = onCreateNew) {
                Text("Custom Agent")
            }
        }
    }
}
