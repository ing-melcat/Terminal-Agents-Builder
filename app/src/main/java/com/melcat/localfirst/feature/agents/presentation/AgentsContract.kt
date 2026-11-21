package com.melcat.localfirst.feature.agents.presentation

import androidx.compose.runtime.Immutable
import com.melcat.localfirst.core.database.SyncStatus
import com.melcat.localfirst.feature.agents.domain.TerminalAgent

@Immutable
data class AgentDraftState(
    val id: String? = null,
    val name: String = "",
    val role: String = "",
    val command: String = "",
    val systemPrompt: String = "",
    val skillsInput: String = "senior-auditor, motion-framer, caveman",
    val isActive: Boolean = true,
) {
    val isValid: Boolean
        get() = name.isNotBlank() && command.isNotBlank()
}

@Immutable
data class AgentsUiState(
    val agents: List<TerminalAgent> = emptyList(),
    val searchQuery: String = "",
    val activeFilterOnly: Boolean = false,
    val expandedAgentId: String? = null,
    val isEditorVisible: Boolean = false,
    val draft: AgentDraftState = AgentDraftState(),
    val isSyncing: Boolean = false,
    val lastDeletedAgentId: String? = null,
    val lastDeletedAgentName: String? = null,
    val errorMessage: String? = null,
) {
    val totalCount: Int
        get() = agents.size

    val activeCount: Int
        get() = agents.count { it.isActive }

    val pendingSyncCount: Int
        get() = agents.count { it.syncStatus == SyncStatus.PENDING }
}

sealed interface AgentsUiEvent {
    data class SearchQueryChanged(val query: String) : AgentsUiEvent
    data class ToggleActiveFilter(val activeOnly: Boolean) : AgentsUiEvent
    data class ToggleExpandCard(val agentId: String) : AgentsUiEvent
    data object OpenNewAgentEditor : AgentsUiEvent
    data class OpenEditAgentEditor(val agent: TerminalAgent) : AgentsUiEvent
    data object DismissEditor : AgentsUiEvent
    data class DraftNameChanged(val name: String) : AgentsUiEvent
    data class DraftRoleChanged(val role: String) : AgentsUiEvent
    data class DraftCommandChanged(val command: String) : AgentsUiEvent
    data class DraftSystemPromptChanged(val systemPrompt: String) : AgentsUiEvent
    data class DraftSkillsChanged(val skills: String) : AgentsUiEvent
    data object SaveDraftAgent : AgentsUiEvent
    data object SeedPresetAgents : AgentsUiEvent
    data class ToggleAgentActive(val agentId: String, val isActive: Boolean) : AgentsUiEvent
    data class SoftDeleteAgent(val agent: TerminalAgent) : AgentsUiEvent
    data object UndoSoftDelete : AgentsUiEvent
    data object DismissUndoBanner : AgentsUiEvent
    data object TriggerDifferentialSync : AgentsUiEvent
    data object ClearError : AgentsUiEvent
}
