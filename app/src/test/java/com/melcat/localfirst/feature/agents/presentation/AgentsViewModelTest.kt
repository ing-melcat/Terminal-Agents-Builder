package com.melcat.localfirst.feature.agents.presentation

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.cash.turbine.test
import com.melcat.localfirst.core.common.DispatchersProvider
import com.melcat.localfirst.core.database.AppDatabase
import com.melcat.localfirst.core.database.SyncStatus
import com.melcat.localfirst.feature.agents.data.TerminalAgentRepositoryImpl
import com.melcat.localfirst.feature.agents.domain.ObserveAgentsUseCase
import com.melcat.localfirst.feature.agents.domain.RestoreAgentUseCase
import com.melcat.localfirst.feature.agents.domain.SoftDeleteAgentUseCase
import com.melcat.localfirst.feature.agents.domain.SyncPendingAgentsUseCase
import com.melcat.localfirst.feature.agents.domain.ToggleAgentActiveUseCase
import com.melcat.localfirst.feature.agents.domain.UpsertAgentUseCase
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
class AgentsViewModelTest {

    private val testDispatcher = UnconfinedTestDispatcher()
    private lateinit var database: AppDatabase
    private lateinit var viewModel: AgentsViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()

        val dispatchers = object : DispatchersProvider {
            override val main: CoroutineDispatcher = testDispatcher
            override val io: CoroutineDispatcher = testDispatcher
            override val default: CoroutineDispatcher = testDispatcher
        }

        val repository = TerminalAgentRepositoryImpl(
            dao = database.terminalAgentDao(),
            dispatchers = dispatchers,
        )

        viewModel = AgentsViewModel(
            observeAgents = ObserveAgentsUseCase(repository),
            upsertAgent = UpsertAgentUseCase(repository),
            toggleAgentActive = ToggleAgentActiveUseCase(repository),
            softDeleteAgent = SoftDeleteAgentUseCase(repository),
            restoreAgent = RestoreAgentUseCase(repository),
            syncPendingAgents = SyncPendingAgentsUseCase(repository),
        )
    }

    @After
    fun tearDown() {
        database.close()
        Dispatchers.resetMain()
    }

    private suspend fun app.cash.turbine.TurbineTestContext<AgentsUiState>.awaitMatching(
        predicate: (AgentsUiState) -> Boolean,
    ): AgentsUiState {
        while (true) {
            val item = awaitItem()
            if (predicate(item)) return item
        }
    }

    @Test
    fun `unidirectional flow creates agent, soft deletes, restores on undo, and syncs`() = runTest(testDispatcher) {
        viewModel.uiState.test {
            val initial = awaitItem()
            assertTrue(initial.agents.isEmpty())

            viewModel.onEvent(AgentsUiEvent.OpenNewAgentEditor)
            viewModel.onEvent(AgentsUiEvent.DraftNameChanged("agy-caveman"))
            viewModel.onEvent(AgentsUiEvent.DraftRoleChanged("Ultra-Concise CLI Runner"))
            viewModel.onEvent(AgentsUiEvent.DraftCommandChanged("agy --skill caveman"))
            viewModel.onEvent(AgentsUiEvent.DraftSystemPromptChanged("No fluff. Executable code first."))
            viewModel.onEvent(AgentsUiEvent.DraftSkillsChanged("caveman, senior-auditor"))
            viewModel.onEvent(AgentsUiEvent.SaveDraftAgent)

            val stateWithAgent = awaitMatching { !it.isEditorVisible && it.agents.size == 1 }
            assertFalse(stateWithAgent.isEditorVisible)
            val agent = stateWithAgent.agents.first()
            assertEquals("agy-caveman", agent.name)
            assertEquals(SyncStatus.PENDING, agent.syncStatus)

            // Soft delete and verify undo banner state
            viewModel.onEvent(AgentsUiEvent.SoftDeleteAgent(agent))
            val afterDelete = awaitMatching { it.agents.isEmpty() && it.lastDeletedAgentId == agent.id }
            assertEquals(agent.id, afterDelete.lastDeletedAgentId)

            // Undo soft delete
            viewModel.onEvent(AgentsUiEvent.UndoSoftDelete)
            val afterUndo = awaitMatching { it.agents.size == 1 && it.lastDeletedAgentId == null }
            assertNull(afterUndo.lastDeletedAgentId)

            // Differential sync marks PENDING -> SYNCED
            viewModel.onEvent(AgentsUiEvent.TriggerDifferentialSync)
            val afterSync = awaitMatching {
                !it.isSyncing && it.agents.firstOrNull()?.syncStatus == SyncStatus.SYNCED
            }
            assertEquals(SyncStatus.SYNCED, afterSync.agents.first().syncStatus)
            assertEquals(0, afterSync.pendingSyncCount)

            cancelAndIgnoreRemainingEvents()
        }
    }
}
