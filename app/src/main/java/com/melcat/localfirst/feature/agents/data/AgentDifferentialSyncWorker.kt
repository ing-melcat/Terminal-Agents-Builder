package com.melcat.localfirst.feature.agents.data

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.BackoffPolicy
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.melcat.localfirst.core.common.AppResult
import com.melcat.localfirst.feature.agents.domain.TerminalAgentRepository
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.util.concurrent.TimeUnit

@HiltWorker
class AgentDifferentialSyncWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted workerParams: WorkerParameters,
    private val repository: TerminalAgentRepository,
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result =
        when (repository.syncPendingAgents()) {
            is AppResult.Success -> Result.success()
            is AppResult.Error -> if (runAttemptCount < MAX_RETRIES) {
                Result.retry()
            } else {
                Result.failure()
            }
        }

    companion object {
        const val UNIQUE_WORK_NAME = "terminal_agents_differential_sync"
        private const val MAX_RETRIES = 3

        fun enqueue(context: Context) {
            val request = OneTimeWorkRequestBuilder<AgentDifferentialSyncWorker>()
                .setBackoffCriteria(
                    BackoffPolicy.EXPONENTIAL,
                    15,
                    TimeUnit.SECONDS,
                )
                .build()

            WorkManager.getInstance(context).enqueueUniqueWork(
                UNIQUE_WORK_NAME,
                ExistingWorkPolicy.KEEP,
                request,
            )
        }
    }
}
