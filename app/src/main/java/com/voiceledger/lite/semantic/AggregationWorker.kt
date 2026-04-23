package com.voiceledger.lite.semantic

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.voiceledger.lite.data.LedgerDatabase
import com.voiceledger.lite.data.LedgerRepository
import com.voiceledger.lite.data.SettingsStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class AggregationWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val database = LedgerDatabaseFactory.open(applicationContext)
        val repository = LedgerRepository(database)
        val settingsStore = SettingsStore(applicationContext)
        if (!settingsStore.load().backgroundProcessingEnabled) {
            return@withContext Result.success()
        }

        return@withContext runCatching {
            LocalAggregationCoordinator(applicationContext, repository, settingsStore)
                .runAggregation()
            Result.success()
        }.getOrElse {
            Result.retry()
        }
    }
}
