package com.melcat.localfirst.feature.agents.domain

import com.melcat.localfirst.core.common.AppResult
import kotlinx.coroutines.flow.Flow

interface TerminalAgentRepository {
    fun observeAgents(searchQuery: String = ""): Flow<List<TerminalAgent>>

    suspend fun getAgentById(id: String): AppResult<TerminalAgent?>

    suspend fun upsertAgent(
        id: String? = null,
        name: String,
        role: String,
        command: String,
        systemPrompt: String,
        skills: List<String>,
        isActive: Boolean = true,
    ): AppResult<TerminalAgent>

    suspend fun toggleAgentActive(
        id: String,
        isActive: Boolean,
    ): AppResult<Unit>

    suspend fun softDeleteAgent(id: String): AppResult<Unit>

    suspend fun restoreAgent(id: String): AppResult<Unit>

    suspend fun syncPendingAgents(): AppResult<Int>
}
