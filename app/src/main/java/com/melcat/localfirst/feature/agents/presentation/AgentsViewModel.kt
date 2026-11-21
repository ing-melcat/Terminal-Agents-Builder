package com.melcat.localfirst.feature.agents.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.melcat.localfirst.core.common.AppResult
import com.melcat.localfirst.feature.agents.domain.ObserveAgentsUseCase
import com.melcat.localfirst.feature.agents.domain.RestoreAgentUseCase
import com.melcat.localfirst.feature.agents.domain.SoftDeleteAgentUseCase
import com.melcat.localfirst.feature.agents.domain.SyncPendingAgentsUseCase
import com.melcat.localfirst.feature.agents.domain.TerminalAgent
import com.melcat.localfirst.feature.agents.domain.ToggleAgentActiveUseCase
import com.melcat.localfirst.feature.agents.domain.UpsertAgentUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

private data class LocalViewState(
    val searchQuery: String = "",
    val activeFilterOnly: Boolean = false,
    val expandedAgentId: String? = null,
    val isEditorVisible: Boolean = false,
    val draft: AgentDraftState = AgentDraftState(),
    val isSyncing: Boolean = false,
    val lastDeletedAgentId: String? = null,
    val lastDeletedAgentName: String? = null,
    val errorMessage: String? = null,
)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class AgentsViewModel @Inject constructor(
    private val observeAgents: ObserveAgentsUseCase,
    private val upsertAgent: UpsertAgentUseCase,
    private val toggleAgentActive: ToggleAgentActiveUseCase,
    private val softDeleteAgent: SoftDeleteAgentUseCase,
    private val restoreAgent: RestoreAgentUseCase,
    private val syncPendingAgents: SyncPendingAgentsUseCase,
) : ViewModel() {

    private val localState = MutableStateFlow(LocalViewState())

    private val dbAgentsFlow = localState
        .map { it.searchQuery }
        .distinctUntilChanged()
        .flatMapLatest { query -> observeAgents(query) }

    val uiState: StateFlow<AgentsUiState> = combine(
        dbAgentsFlow,
        localState,
    ) { agents, local ->
        val filtered = if (local.activeFilterOnly) {
            agents.filter(TerminalAgent::isActive)
        } else {
            agents
        }
        AgentsUiState(
            agents = filtered,
            searchQuery = local.searchQuery,
            activeFilterOnly = local.activeFilterOnly,
            expandedAgentId = local.expandedAgentId,
            isEditorVisible = local.isEditorVisible,
            draft = local.draft,
            isSyncing = local.isSyncing,
            lastDeletedAgentId = local.lastDeletedAgentId,
            lastDeletedAgentName = local.lastDeletedAgentName,
            errorMessage = local.errorMessage,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = AgentsUiState(),
    )

    fun onEvent(event: AgentsUiEvent) {
        when (event) {
            is AgentsUiEvent.SearchQueryChanged -> {
                localState.update { it.copy(searchQuery = event.query) }
            }

            is AgentsUiEvent.ToggleActiveFilter -> {
                localState.update { it.copy(activeFilterOnly = event.activeOnly) }
            }

            is AgentsUiEvent.ToggleExpandCard -> {
                localState.update { current ->
                    val nextId = if (current.expandedAgentId == event.agentId) null else event.agentId
                    current.copy(expandedAgentId = nextId)
                }
            }

            AgentsUiEvent.OpenNewAgentEditor -> {
                localState.update {
                    it.copy(
                        isEditorVisible = true,
                        draft = AgentDraftState(),
                        errorMessage = null,
                    )
                }
            }

            is AgentsUiEvent.OpenEditAgentEditor -> {
                val agent = event.agent
                localState.update {
                    it.copy(
                        isEditorVisible = true,
                        draft = AgentDraftState(
                            id = agent.id,
                            name = agent.name,
                            role = agent.role,
                            command = agent.command,
                            systemPrompt = agent.systemPrompt,
                            skillsInput = agent.skills.joinToString(", "),
                            isActive = agent.isActive,
                        ),
                        errorMessage = null,
                    )
                }
            }

            AgentsUiEvent.DismissEditor -> {
                localState.update { it.copy(isEditorVisible = false, errorMessage = null) }
            }

            is AgentsUiEvent.DraftNameChanged -> {
                localState.update { it.copy(draft = it.draft.copy(name = event.name)) }
            }

            is AgentsUiEvent.DraftRoleChanged -> {
                localState.update { it.copy(draft = it.draft.copy(role = event.role)) }
            }

            is AgentsUiEvent.DraftCommandChanged -> {
                localState.update { it.copy(draft = it.draft.copy(command = event.command)) }
            }

            is AgentsUiEvent.DraftSystemPromptChanged -> {
                localState.update { it.copy(draft = it.draft.copy(systemPrompt = event.systemPrompt)) }
            }

            is AgentsUiEvent.DraftSkillsChanged -> {
                localState.update { it.copy(draft = it.draft.copy(skillsInput = event.skills)) }
            }

            AgentsUiEvent.SaveDraftAgent -> saveCurrentDraft()

            AgentsUiEvent.SeedPresetAgents -> seedPresets()

            is AgentsUiEvent.ToggleAgentActive -> {
                viewModelScope.launch {
                    when (val result = toggleAgentActive(event.agentId, event.isActive)) {
                        is AppResult.Success -> Unit
                        is AppResult.Error -> localState.update { it.copy(errorMessage = result.message) }
                    }
                }
            }

            is AgentsUiEvent.SoftDeleteAgent -> {
                viewModelScope.launch {
                    when (val result = softDeleteAgent(event.agent.id)) {
                        is AppResult.Success -> {
                            localState.update {
                                it.copy(
                                    lastDeletedAgentId = event.agent.id,
                                    lastDeletedAgentName = event.agent.name,
                                    expandedAgentId = if (it.expandedAgentId == event.agent.id) null else it.expandedAgentId,
                                )
                            }
                        }
                        is AppResult.Error -> localState.update { it.copy(errorMessage = result.message) }
                    }
                }
            }

            AgentsUiEvent.UndoSoftDelete -> {
                val idToRestore = localState.value.lastDeletedAgentId ?: return
                viewModelScope.launch {
                    when (val result = restoreAgent(idToRestore)) {
                        is AppResult.Success -> {
                            localState.update {
                                it.copy(
                                    lastDeletedAgentId = null,
                                    lastDeletedAgentName = null,
                                )
                            }
                        }
                        is AppResult.Error -> localState.update { it.copy(errorMessage = result.message) }
                    }
                }
            }

            AgentsUiEvent.DismissUndoBanner -> {
                localState.update {
                    it.copy(
                        lastDeletedAgentId = null,
                        lastDeletedAgentName = null,
                    )
                }
            }

            AgentsUiEvent.TriggerDifferentialSync -> {
                viewModelScope.launch {
                    localState.update { it.copy(isSyncing = true, errorMessage = null) }
                    when (val result = syncPendingAgents()) {
                        is AppResult.Success -> {
                            localState.update {
                                it.copy(
                                    isSyncing = false,
                                    lastDeletedAgentId = null,
                                    lastDeletedAgentName = null,
                                )
                            }
                        }
                        is AppResult.Error -> {
                            localState.update {
                                it.copy(
                                    isSyncing = false,
                                    errorMessage = result.message,
                                )
                            }
                        }
                    }
                }
            }

            AgentsUiEvent.ClearError -> {
                localState.update { it.copy(errorMessage = null) }
            }
        }
    }

    private fun saveCurrentDraft() {
        val currentDraft = localState.value.draft
        if (!currentDraft.isValid) {
            localState.update { it.copy(errorMessage = "Name and CLI command are required") }
            return
        }
        viewModelScope.launch {
            when (
                val result = upsertAgent(
                    id = currentDraft.id,
                    name = currentDraft.name,
                    role = currentDraft.role,
                    command = currentDraft.command,
                    systemPrompt = currentDraft.systemPrompt,
                    skillsInput = currentDraft.skillsInput,
                    isActive = currentDraft.isActive,
                )
            ) {
                is AppResult.Success -> {
                    localState.update {
                        it.copy(
                            isEditorVisible = false,
                            draft = AgentDraftState(),
                            errorMessage = null,
                        )
                    }
                }
                is AppResult.Error -> {
                    localState.update { it.copy(errorMessage = result.message) }
                }
            }
        }
    }

    private fun seedPresets() {
        viewModelScope.launch {
            upsertAgent(
                name = "agy-senior-auditor",
                role = "Concurrency & SQLite Auditor",
                command = "agy --skill senior-auditor --strict",
                systemPrompt = "Audit Dispatchers.IO race conditions, SQLite composite indices, and zero TODOs.",
                skillsInput = "senior-auditor, understand-diff, caveman",
                isActive = true,
            )
            upsertAgent(
                name = "agy-motion-framer",
                role = "Compose Spring Physics Specialist",
                command = "agy --skill motion-framer --compose",
                systemPrompt = "Enforce spring() physics, AnimatedContent, and animateItem() transitions.",
                skillsInput = "motion-framer, generative_ui",
                isActive = true,
            )
            upsertAgent(
                name = "agy-understand-graph",
                role = "Codebase Knowledge Graph Builder",
                command = "agy /understand --full",
                systemPrompt = "Build and query interactive architecture graphs with Understand-Anything.",
                skillsInput = "understand, understand-domain, understand-explain",
                isActive = true,
            )
        }
    }
}
