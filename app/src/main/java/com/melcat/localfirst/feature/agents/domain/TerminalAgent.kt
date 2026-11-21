package com.melcat.localfirst.feature.agents.domain

import androidx.compose.runtime.Immutable
import com.melcat.localfirst.core.database.SyncStatus

@Immutable
data class TerminalAgent(
    val id: String,
    val name: String,
    val role: String,
    val command: String,
    val systemPrompt: String,
    val skills: List<String>,
    val isActive: Boolean,
    val createdAt: Long,
    val updatedAt: Long,
    val syncStatus: SyncStatus,
)
