package com.voiceledger.lite.semantic

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.workDataOf
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.Flow

object ModelProvisioningScheduler {
    private const val WORK_NAME = "voice-ledger-model-provisioning"

    private const val SUMMARY_PREFIX = "summary"
    private const val EMBEDDING_PREFIX = "embedding"

    fun ensureRunning(context: Context) {
        enqueue(context, ExistingWorkPolicy.KEEP)
    }

    fun retry(context: Context) {
        enqueue(context, ExistingWorkPolicy.REPLACE)
    }

    fun workInfosFlow(context: Context): Flow<List<WorkInfo>> {
        return WorkManager.getInstance(context).getWorkInfosForUniqueWorkFlow(WORK_NAME)
    }

    fun isActive(workInfos: List<WorkInfo>): Boolean {
        return workInfos.any { info ->
            info.state == WorkInfo.State.ENQUEUED ||
                info.state == WorkInfo.State.BLOCKED ||
                info.state == WorkInfo.State.RUNNING
        }
    }

    fun statusFromWorkInfos(workInfos: List<WorkInfo>): LocalModelProvisioningStatus? {
        val mostRelevant = workInfos
            .sortedWith(
                compareByDescending<WorkInfo> { it.state.sortOrder() }
                    .thenByDescending { it.runAttemptCount },
            )
            .firstOrNull()
            ?: return null
        val data = when {
            mostRelevant.progress.keyValueMap.isNotEmpty() -> mostRelevant.progress
            mostRelevant.outputData.keyValueMap.isNotEmpty() -> mostRelevant.outputData
            mostRelevant.state == WorkInfo.State.ENQUEUED || mostRelevant.state == WorkInfo.State.BLOCKED ->
                return LocalModelProvisioningStatus.checking()
            else -> return null
        }
        return data.toProvisioningStatus()
    }

    fun toWorkData(status: LocalModelProvisioningStatus): Data {
        return workDataOf(
            "${SUMMARY_PREFIX}_state" to status.summary.state.name,
            "${SUMMARY_PREFIX}_detail" to status.summary.detail,
            "${SUMMARY_PREFIX}_file_name" to status.summary.fileName,
            "${SUMMARY_PREFIX}_installed_path" to status.summary.installedPath,
            "${SUMMARY_PREFIX}_model_label" to status.summary.modelLabel,
            "${SUMMARY_PREFIX}_downloaded_bytes" to (status.summary.downloadedBytes ?: -1L),
            "${SUMMARY_PREFIX}_total_bytes" to (status.summary.totalBytes ?: -1L),
            "${EMBEDDING_PREFIX}_state" to status.embedding.state.name,
            "${EMBEDDING_PREFIX}_detail" to status.embedding.detail,
            "${EMBEDDING_PREFIX}_file_name" to status.embedding.fileName,
            "${EMBEDDING_PREFIX}_installed_path" to status.embedding.installedPath,
            "${EMBEDDING_PREFIX}_model_label" to status.embedding.modelLabel,
            "${EMBEDDING_PREFIX}_downloaded_bytes" to (status.embedding.downloadedBytes ?: -1L),
            "${EMBEDDING_PREFIX}_total_bytes" to (status.embedding.totalBytes ?: -1L),
        )
    }

    private fun Data.toProvisioningStatus(): LocalModelProvisioningStatus {
        return LocalModelProvisioningStatus(
            summary = artifactStatusFromData(
                prefix = SUMMARY_PREFIX,
                displayName = "Summary model",
                fallbackFileName = LocalModelProvisioner.SUMMARY_FILE_NAME,
            ),
            embedding = artifactStatusFromData(
                prefix = EMBEDDING_PREFIX,
                displayName = "Embedding model",
                fallbackFileName = LocalModelProvisioner.EMBEDDING_FILE_NAME,
            ),
        )
    }

    private fun Data.artifactStatusFromData(
        prefix: String,
        displayName: String,
        fallbackFileName: String,
    ): LocalModelArtifactStatus {
        val stateName = getString("${prefix}_state") ?: LocalModelInstallState.CHECKING.name
        val state = runCatching { LocalModelInstallState.valueOf(stateName) }
            .getOrDefault(LocalModelInstallState.CHECKING)
        return LocalModelArtifactStatus(
            displayName = displayName,
            fileName = getString("${prefix}_file_name") ?: fallbackFileName,
            state = state,
            detail = getString("${prefix}_detail") ?: "Checking local installation.",
            installedPath = getString("${prefix}_installed_path"),
            modelLabel = getString("${prefix}_model_label"),
            downloadedBytes = getLong("${prefix}_downloaded_bytes", -1L).takeIf { it >= 0L },
            totalBytes = getLong("${prefix}_total_bytes", -1L).takeIf { it >= 0L },
        )
    }

    private fun enqueue(context: Context, policy: ExistingWorkPolicy) {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .setRequiresStorageNotLow(true)
            .build()
        val request = OneTimeWorkRequestBuilder<ModelProvisioningWorker>()
            .setConstraints(constraints)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            WORK_NAME,
            policy,
            request,
        )
    }
}

private fun WorkInfo.State.sortOrder(): Int {
    return when (this) {
        WorkInfo.State.RUNNING -> 5
        WorkInfo.State.ENQUEUED -> 4
        WorkInfo.State.BLOCKED -> 3
        WorkInfo.State.FAILED -> 2
        WorkInfo.State.SUCCEEDED -> 1
        WorkInfo.State.CANCELLED -> 0
    }
}
