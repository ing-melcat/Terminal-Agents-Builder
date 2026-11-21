package com.melcat.localfirst.feature.agents.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.cash.turbine.test
import com.melcat.localfirst.core.common.AppResult
import com.melcat.localfirst.core.common.DispatchersProvider
import com.melcat.localfirst.core.database.AppDatabase
import com.melcat.localfirst.core.database.SyncStatus
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TerminalAgentDaoAndRepositoryTest {

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var database: AppDatabase
    private lateinit var dao: TerminalAgentDao
    private lateinit var repository: TerminalAgentRepositoryImpl

    private val testDispatchers = object : DispatchersProvider {
        override val main: CoroutineDispatcher = testDispatcher
        override val io: CoroutineDispatcher = testDispatcher
        override val default: CoroutineDispatcher = testDispatcher
    }

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = database.terminalAgentDao()
        repository = TerminalAgentRepositoryImpl(dao = dao, dispatchers = testDispatchers)
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun `upsert and observeAgents emits active agents sorted by active status and timestamp`() = runTest(testDispatcher) {
        repository.observeAgents().test {
            assertEquals(emptyList<Any>(), awaitItem())

            val upsertResult = repository.upsertAgent(
                name = "agy-auditor",
                role = "Senior Code Auditor",
                command = "claude --agent senior-auditor",
                systemPrompt = "Audit concurrency, SQL indices, and memory leaks.",
                skills = listOf("senior-auditor", "caveman"),
            )
            assertTrue(upsertResult is AppResult.Success)
            val created = (upsertResult as AppResult.Success).data
            assertEquals(SyncStatus.PENDING, created.syncStatus)
            assertEquals(listOf("senior-auditor", "caveman"), created.skills)

            val listAfterInsert = awaitItem()
            assertEquals(1, listAfterInsert.size)
            assertEquals("agy-auditor", listAfterInsert.first().name)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `softDeleteAgent hides agent from active flow and restoreAgent brings it back`() = runTest(testDispatcher) {
        val created = (repository.upsertAgent(
            name = "motion-bot",
            role = "Compose Motion Engineer",
            command = "agy --skill motion-framer",
            systemPrompt = "Use spring physics in all transitions.",
            skills = listOf("motion-framer"),
        ) as AppResult.Success).data

        repository.softDeleteAgent(created.id)

        repository.observeAgents().test {
            assertTrue(awaitItem().isEmpty())
            cancelAndIgnoreRemainingEvents()
        }
        assertNull((repository.getAgentById(created.id) as AppResult.Success).data)

        // Pending sync still includes soft-deleted record for differential propagation
        val pendingEntities = dao.getAgentsBySyncStatus(SyncStatus.PENDING)
        assertEquals(1, pendingEntities.size)
        assertTrue(pendingEntities.first().isDeleted)

        repository.restoreAgent(created.id)
        val restored = (repository.getAgentById(created.id) as AppResult.Success).data
        assertNotNull(restored)
        assertEquals("motion-bot", restored?.name)
    }

    @Test
    fun `toggleAgentActive and syncPendingAgents transitions PENDING to SYNCED and purges synced deletes`() = runTest(testDispatcher) {
        val agentA = (repository.upsertAgent(
            name = "agent-alpha",
            role = "Builder",
            command = "agy run alpha",
            systemPrompt = "Build clean architecture modules.",
            skills = listOf("caveman"),
        ) as AppResult.Success).data

        val agentB = (repository.upsertAgent(
            name = "agent-beta",
            role = "Temp Worker",
            command = "agy run beta",
            systemPrompt = "Temporary agent.",
            skills = emptyList(),
        ) as AppResult.Success).data

        repository.toggleAgentActive(agentA.id, isActive = false)
        repository.softDeleteAgent(agentB.id)

        val syncResult = repository.syncPendingAgents()
        assertTrue(syncResult is AppResult.Success)
        assertEquals(2, (syncResult as AppResult.Success).data)

        val refreshedA = (repository.getAgentById(agentA.id) as AppResult.Success).data
        assertNotNull(refreshedA)
        assertFalse(refreshedA!!.isActive)
        assertEquals(SyncStatus.SYNCED, refreshedA.syncStatus)

        // Soft-deleted & synced record is purged cleanly
        assertTrue(dao.getAgentsBySyncStatus(SyncStatus.PENDING).isEmpty())
    }
}
