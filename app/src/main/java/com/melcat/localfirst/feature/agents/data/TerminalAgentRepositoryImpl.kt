package com.melcat.localfirst.feature.agents.data

import com.melcat.localfirst.core.common.AppResult
import com.melcat.localfirst.core.common.DispatchersProvider
import com.melcat.localfirst.core.common.runCatchingAppResult
import com.melcat.localfirst.core.database.SyncStatus
import com.melcat.localfirst.feature.agents.domain.TerminalAgent
import com.melcat.localfirst.feature.agents.domain.TerminalAgentRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TerminalAgentRepositoryImpl @Inject constructor(
    private val dao: TerminalAgentDao,
    private val dispatchers: DispatchersProvider,
) : TerminalAgentRepository {

    override fun observeAgents(searchQuery: String): Flow<List<TerminalAgent>> {
        val trimmedQuery = searchQuery.trim()
        val sourceFlow = if (trimmedQuery.isEmpty()) {
            dao.observeActiveAgents()
        } else {
            dao.searchAgents(trimmedQuery)
        }
        return sourceFlow
            .map { entities -> entities.map(TerminalAgentEntity::toDomain) }
            .flowOn(dispatchers.io)
    }

    override suspend fun getAgentById(id: String): AppResult<TerminalAgent?> =
        withContext(dispatchers.io) {
            runCatchingAppResult {
                dao.getAgentById(id)?.toDomain()
            }
        }

    override suspend fun upsertAgent(
        id: String?,
        name: String,
        role: String,
        command: String,
        systemPrompt: String,
        skills: List<String>,
        isActive: Boolean,
    ): AppResult<TerminalAgent> =
        withContext(dispatchers.io) {
            runCatchingAppResult {
                val cleanName = name.trim()
                val cleanRole = role.trim()
                val cleanCommand = command.trim()
                require(cleanName.isNotEmpty()) { "Agent name cannot be blank" }
                require(cleanCommand.isNotEmpty()) { "CLI command cannot be blank" }

                val now = System.currentTimeMillis()
                val existing = id?.let { dao.getAgentById(it) }
                val entity = TerminalAgentEntity(
                    id = existing?.id ?: id ?: UUID.randomUUID().toString(),
                    name = cleanName,
                    role = cleanRole.ifEmpty { "Autonomous CLI Agent" },
                    command = cleanCommand,
                    systemPrompt = systemPrompt.trim(),
                    skillsCsv = skills.toSkillsCsv(),
                    isActive = isActive,
                    createdAt = existing?.createdAt ?: now,
                    updatedAt = now,
                    isDeleted = false,
                    syncStatus = SyncStatus.PENDING,
                )
                dao.upsertAgent(entity)
                entity.toDomain()
            }
        }

    override suspend fun toggleAgentActive(
        id: String,
        isActive: Boolean,
    ): AppResult<Unit> =
        withContext(dispatchers.io) {
            runCatchingAppResult {
                dao.updateActiveStatus(
                    id = id,
                    isActive = isActive,
                    updatedAt = System.currentTimeMillis(),
                    syncStatus = SyncStatus.PENDING,
                )
                Unit
            }
        }

    override suspend fun softDeleteAgent(id: String): AppResult<Unit> =
        withContext(dispatchers.io) {
            runCatchingAppResult {
                dao.softDeleteAgent(
                    id = id,
                    updatedAt = System.currentTimeMillis(),
                    syncStatus = SyncStatus.PENDING,
                )
                Unit
            }
        }

    override suspend fun restoreAgent(id: String): AppResult<Unit> =
        withContext(dispatchers.io) {
            runCatchingAppResult {
                dao.restoreAgent(
                    id = id,
                    updatedAt = System.currentTimeMillis(),
                    syncStatus = SyncStatus.PENDING,
                )
                Unit
            }
        }

    override suspend fun syncPendingAgents(): AppResult<Int> =
        withContext(dispatchers.io) {
            runCatchingAppResult {
                val pending = dao.getAgentsBySyncStatus(SyncStatus.PENDING)
                if (pending.isEmpty()) {
                    0
                } else {
                    val syncedCount = dao.markSyncStatus(
                        ids = pending.map { it.id },
                        newStatus = SyncStatus.SYNCED,
                    )
                    dao.purgeSyncedSoftDeletes(SyncStatus.SYNCED)
                    syncedCount
                }
            }
        }
}
