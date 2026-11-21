package com.melcat.localfirst.feature.agents.data

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.melcat.localfirst.core.database.SyncStatus
import kotlinx.coroutines.flow.Flow

@Dao
interface TerminalAgentDao {

    @Query(
        """
        SELECT * FROM terminal_agents
        WHERE is_deleted = 0
        ORDER BY is_active DESC, updated_at DESC
        """
    )
    fun observeActiveAgents(): Flow<List<TerminalAgentEntity>>

    @Query(
        """
        SELECT * FROM terminal_agents
        WHERE is_deleted = 0
          AND (
              name LIKE '%' || :query || '%'
              OR role LIKE '%' || :query || '%'
              OR command LIKE '%' || :query || '%'
              OR skills_csv LIKE '%' || :query || '%'
          )
        ORDER BY is_active DESC, updated_at DESC
        """
    )
    fun searchAgents(query: String): Flow<List<TerminalAgentEntity>>

    @Query("SELECT * FROM terminal_agents WHERE id = :id AND is_deleted = 0 LIMIT 1")
    suspend fun getAgentById(id: String): TerminalAgentEntity?

    @Upsert
    suspend fun upsertAgent(entity: TerminalAgentEntity)

    @Query(
        """
        UPDATE terminal_agents
        SET is_active = :isActive,
            updated_at = :updatedAt,
            sync_status = :syncStatus
        WHERE id = :id AND is_deleted = 0
        """
    )
    suspend fun updateActiveStatus(
        id: String,
        isActive: Boolean,
        updatedAt: Long,
        syncStatus: SyncStatus = SyncStatus.PENDING,
    ): Int

    @Query(
        """
        UPDATE terminal_agents
        SET is_deleted = 1,
            updated_at = :updatedAt,
            sync_status = :syncStatus
        WHERE id = :id AND is_deleted = 0
        """
    )
    suspend fun softDeleteAgent(
        id: String,
        updatedAt: Long,
        syncStatus: SyncStatus = SyncStatus.PENDING,
    ): Int

    @Query(
        """
        UPDATE terminal_agents
        SET is_deleted = 0,
            updated_at = :updatedAt,
            sync_status = :syncStatus
        WHERE id = :id AND is_deleted = 1
        """
    )
    suspend fun restoreAgent(
        id: String,
        updatedAt: Long,
        syncStatus: SyncStatus = SyncStatus.PENDING,
    ): Int

    @Query("SELECT * FROM terminal_agents WHERE sync_status = :status ORDER BY updated_at ASC")
    suspend fun getAgentsBySyncStatus(status: SyncStatus = SyncStatus.PENDING): List<TerminalAgentEntity>

    @Query(
        """
        UPDATE terminal_agents
        SET sync_status = :newStatus
        WHERE id IN (:ids)
        """
    )
    suspend fun markSyncStatus(
        ids: List<String>,
        newStatus: SyncStatus = SyncStatus.SYNCED,
    ): Int

    @Query("DELETE FROM terminal_agents WHERE is_deleted = 1 AND sync_status = :syncedStatus")
    suspend fun purgeSyncedSoftDeletes(
        syncedStatus: SyncStatus = SyncStatus.SYNCED,
    ): Int
}
