package com.melcat.localfirst.feature.agents.data

import com.melcat.localfirst.core.database.SyncStatus
import com.melcat.localfirst.feature.agents.domain.TerminalAgent

fun TerminalAgentEntity.toDomain(): TerminalAgent =
    TerminalAgent(
        id = id,
        name = name,
        role = role,
        command = command,
        systemPrompt = systemPrompt,
        skills = skillsCsv
            .split(",")
            .map { it.trim() }
            .filter { it.isNotEmpty() },
        isActive = isActive,
        createdAt = createdAt,
        updatedAt = updatedAt,
        syncStatus = syncStatus,
    )

fun List<String>.toSkillsCsv(): String =
    asSequence()
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .distinct()
        .joinToString(separator = ",")

fun TerminalAgent.toEntity(
    isDeleted: Boolean = false,
    overrideSyncStatus: SyncStatus = syncStatus,
): TerminalAgentEntity =
    TerminalAgentEntity(
        id = id,
        name = name,
        role = role,
        command = command,
        systemPrompt = systemPrompt,
        skillsCsv = skills.toSkillsCsv(),
        isActive = isActive,
        createdAt = createdAt,
        updatedAt = updatedAt,
        isDeleted = isDeleted,
        syncStatus = overrideSyncStatus,
    )
