package com.melcat.localfirst.feature.agents.domain

import com.melcat.localfirst.core.common.AppResult
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject

class ObserveAgentsUseCase @Inject constructor(
    private val repository: TerminalAgentRepository,
) {
    operator fun invoke(searchQuery: String = ""): Flow<List<TerminalAgent>> =
        repository.observeAgents(searchQuery)
}

class UpsertAgentUseCase @Inject constructor(
    private val repository: TerminalAgentRepository,
) {
    suspend operator fun invoke(
        id: String? = null,
        name: String,
        role: String,
        command: String,
        systemPrompt: String,
        skillsInput: String,
        isActive: Boolean = true,
    ): AppResult<TerminalAgent> {
        val parsedSkills = skillsInput
            .split(",")
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()

        return repository.upsertAgent(
            id = id,
            name = name,
            role = role,
            command = command,
            systemPrompt = systemPrompt,
            skills = parsedSkills,
            isActive = isActive,
        )
    }
}

class ToggleAgentActiveUseCase @Inject constructor(
    private val repository: TerminalAgentRepository,
) {
    suspend operator fun invoke(
        id: String,
        isActive: Boolean,
    ): AppResult<Unit> = repository.toggleAgentActive(id = id, isActive = isActive)
}

class SoftDeleteAgentUseCase @Inject constructor(
    private val repository: TerminalAgentRepository,
) {
    suspend operator fun invoke(id: String): AppResult<Unit> =
        repository.softDeleteAgent(id = id)
}

class RestoreAgentUseCase @Inject constructor(
    private val repository: TerminalAgentRepository,
) {
    suspend operator fun invoke(id: String): AppResult<Unit> =
        repository.restoreAgent(id = id)
}

class SyncPendingAgentsUseCase @Inject constructor(
    private val repository: TerminalAgentRepository,
) {
    suspend operator fun invoke(): AppResult<Int> =
        repository.syncPendingAgents()
}
