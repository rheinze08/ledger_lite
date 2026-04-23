package com.voiceledger.lite.semantic

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import com.voiceledger.lite.data.SettingsStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext

class ModelProvisioningWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {
    private val settingsStore = SettingsStore(appContext)
    private val provisioner = LocalModelProvisioner(appContext, settingsStore)

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        setForeground(createForegroundInfo(LocalModelProvisioningStatus.checking()))
        val status = provisioner.ensureInstalled { updatedStatus ->
            runBlocking {
                setProgress(ModelProvisioningScheduler.toWorkData(updatedStatus))
                setForeground(createForegroundInfo(updatedStatus))
            }
        }
        settingsStore.setInitialSetupComplete(status.allReady)
        return@withContext when {
            status.allReady -> Result.success(ModelProvisioningScheduler.toWorkData(status))
            status.hasPermanentFailure() -> Result.failure(ModelProvisioningScheduler.toWorkData(status))
            else -> Result.retry()
        }
    }

    private fun createForegroundInfo(status: LocalModelProvisioningStatus): ForegroundInfo {
        createNotificationChannelIfNeeded()
        val launchIntent = applicationContext.packageManager
            .getLaunchIntentForPackage(applicationContext.packageName)
            ?.apply { flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP }
        val pendingIntent = launchIntent?.let {
            PendingIntent.getActivity(
                applicationContext,
                0,
                it,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        }
        val contentText = when {
            status.summary.progressFraction != null -> {
                "Downloading summary model ${status.summary.downloadedBytes?.let(::formatBytes).orEmpty()} / ${status.summary.totalBytes?.let(::formatBytes).orEmpty()}"
            }
            status.embedding.progressFraction != null -> {
                "Downloading embedding model ${status.embedding.downloadedBytes?.let(::formatBytes).orEmpty()} / ${status.embedding.totalBytes?.let(::formatBytes).orEmpty()}"
            }
            else -> "Preparing local AI models for Ledger Lite."
        }
        val notification = NotificationCompat.Builder(applicationContext, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("Installing Ledger Lite models")
            .setContentText(contentText)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setProgress(
                100,
                (status.progressFraction?.times(100f)?.toInt() ?: 0).coerceIn(0, 100),
                status.progressFraction == null,
            )
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
                "Model downloads",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "Downloads the local AI models required by Ledger Lite."
            },
        )
    }

    companion object {
        private const val NOTIFICATION_CHANNEL_ID = "voice_ledger_model_downloads"
        private const val NOTIFICATION_ID = 1101
    }
}

private fun LocalModelProvisioningStatus.hasPermanentFailure(): Boolean {
    return listOf(summary, embedding).any { artifact ->
        artifact.state == LocalModelInstallState.MISSING ||
            (artifact.state == LocalModelInstallState.FAILED &&
                artifact.detail.contains("HTTP 404", ignoreCase = true))
    }
}

private fun formatBytes(bytes: Long): String {
    val gib = 1024.0 * 1024.0 * 1024.0
    val mib = 1024.0 * 1024.0
    return when {
        bytes >= gib -> String.format("%.2f GB", bytes / gib)
        bytes >= mib -> String.format("%.1f MB", bytes / mib)
        else -> "$bytes B"
    }
}
