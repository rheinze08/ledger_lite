package com.voiceledger.lite.semantic

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import androidx.work.multiprocess.RemoteCoroutineWorker
import com.voiceledger.lite.data.LedgerDatabase
import com.voiceledger.lite.data.LedgerRepository
import com.voiceledger.lite.data.SettingsStore
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class AggregationWorker(
    appContext: Context,
    params: WorkerParameters,
) : RemoteCoroutineWorker(appContext, params) {
    override suspend fun doRemoteWork(): Result = withContext(Dispatchers.IO) {
        val database = LedgerDatabaseFactory.open(applicationContext)
        val repository = LedgerRepository(database)
        val settingsStore = SettingsStore(applicationContext)
        val runLogger = AggregationRunLogger(applicationContext)
        val isManualTrigger = AggregationScheduler.isManualTrigger(inputData)
        val rebuildRequested = AggregationScheduler.isRebuildRequested(inputData)
        val rebuildFromEpochMs = AggregationScheduler.rebuildFromEpochMs(inputData)

        val settings = settingsStore.load()
        if (!isManualTrigger && !settings.backgroundProcessingEnabled) {
            return@withContext Result.success()
        }

        return@withContext aggregationMutex.withLock {
            runCatching {
                runLogger.beginRun(
                    isManualTrigger = isManualTrigger,
                    rebuildRequested = rebuildRequested,
                    rebuildFromEpochMs = rebuildFromEpochMs,
                )
                if (isManualTrigger) {
                    val initialMessage = if (rebuildRequested) {
                        rebuildFromEpochMs?.let {
                            "Preparing rebuild from ${formatEpochDate(it)}"
                        } ?: "Preparing full rebuild"
                    } else {
                        "Preparing summary update"
                    }
                    runLogger.append(initialMessage)
                    setProgress(AggregationScheduler.progressData(initialMessage, rebuildRequested))
                    setForegroundAsync(createForegroundInfo(initialMessage, rebuildRequested)).get()
                }
                val message = LocalAggregationCoordinator(applicationContext, repository, settingsStore)
                    .runAggregation(rebuildRequested, rebuildFromEpochMs) { progressMessage ->
                        runLogger.append(progressMessage)
                        if (isManualTrigger) {
                            setProgress(AggregationScheduler.progressData(progressMessage, rebuildRequested))
                            setForegroundAsync(createForegroundInfo(progressMessage, rebuildRequested)).get()
                        }
                    }
                runLogger.append(message)
                if (isManualTrigger) {
                    Result.success(AggregationScheduler.successData(message, rebuildRequested))
                } else {
                    AggregationScheduler.scheduleNextFromWorker(applicationContext, settingsStore.load())
                    Result.success()
                }
            }.getOrElse { exception ->
                if (exception is CancellationException) throw exception
                val cause = (exception as? java.util.concurrent.ExecutionException)?.cause ?: exception
                if (cause is CancellationException) throw cause
                runLogger.appendThrowable("Aggregation failed", cause)
                val message = cause.message?.takeIf { it.isNotBlank() }
                    ?: exception.message?.takeIf { it.isNotBlank() }
                    ?: "${cause.javaClass.simpleName} (no details available)"
                if (isManualTrigger) {
                    Result.failure(AggregationScheduler.failureData(message, rebuildRequested))
                } else {
                    Result.retry()
                }
            }
        }
    }

    private fun createForegroundInfo(message: String, rebuildRequested: Boolean): ForegroundInfo {
        createNotificationChannelIfNeeded()
        val title = if (rebuildRequested) {
            "Rebuilding Ledger Lite summaries"
        } else {
            "Updating Ledger Lite summaries"
        }
        // PendingIntent is intentionally omitted: ForegroundInfo is marshalled to bytes when
        // crossing from the :aggregation process to the main process, and Parcel.marshall()
        // rejects Parcels that contain Binder objects (which PendingIntent holds internally).
        val notification = NotificationCompat.Builder(applicationContext, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle(title)
            .setContentText(message)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setProgress(0, 0, true)
            .build()
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(
                NOTIFICATION_ID,
                notification,
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
        } else {
            ForegroundInfo(NOTIFICATION_ID, notification)
        }
    }

    private fun createNotificationChannelIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return
        }
        val manager = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (manager.getNotificationChannel(NOTIFICATION_CHANNEL_ID) != null) {
            return
        }
        manager.createNotificationChannel(
            NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "Summary rebuilds",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "Builds local summaries and semantic indexes for Ledger Lite."
            },
        )
    }

    companion object {
        private const val NOTIFICATION_CHANNEL_ID = "voice_ledger_aggregation"
        private const val NOTIFICATION_ID = 1102
        private val aggregationMutex = Mutex()
    }
}

private fun formatEpochDate(epochMs: Long): String {
    return DateTimeFormatter.ofPattern("MMM d, yyyy")
        .withZone(ZoneId.systemDefault())
        .format(Instant.ofEpochMilli(epochMs))
}
