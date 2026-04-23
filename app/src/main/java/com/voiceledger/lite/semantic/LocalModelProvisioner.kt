package com.voiceledger.lite.semantic

import android.content.Context
import com.voiceledger.lite.BuildConfig
import com.voiceledger.lite.data.SettingsStore
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

enum class LocalModelInstallState {
    CHECKING,
    DOWNLOADING,
    READY,
    FAILED,
    MISSING,
}

data class LocalModelArtifactStatus(
    val displayName: String,
    val fileName: String,
    val state: LocalModelInstallState,
    val detail: String,
    val installedPath: String? = null,
    val modelLabel: String? = null,
    val downloadedBytes: Long? = null,
    val totalBytes: Long? = null,
) {
    val isReady: Boolean
        get() = state == LocalModelInstallState.READY

    val progressFraction: Float?
        get() = if (
            state == LocalModelInstallState.DOWNLOADING &&
            downloadedBytes != null &&
            totalBytes != null &&
            totalBytes > 0L
        ) {
            (downloadedBytes.toDouble() / totalBytes.toDouble()).toFloat().coerceIn(0f, 1f)
        } else {
            null
        }
}

data class LocalModelProvisioningStatus(
    val summary: LocalModelArtifactStatus,
    val embedding: LocalModelArtifactStatus,
) {
    val allReady: Boolean
        get() = summary.isReady && embedding.isReady

    val downloadedBytes: Long?
        get() {
            if (!summary.hasKnownByteCount() || !embedding.hasKnownByteCount()) {
                return null
            }
            return (summary.downloadedBytes ?: 0L) + (embedding.downloadedBytes ?: 0L)
        }

    val totalBytes: Long?
        get() {
            if (!summary.hasKnownByteCount() || !embedding.hasKnownByteCount()) {
                return null
            }
            return (summary.totalBytes ?: 0L) + (embedding.totalBytes ?: 0L)
        }

    val progressFraction: Float?
        get() {
            val total = totalBytes
            val downloaded = downloadedBytes
            if (total == null || downloaded == null || total <= 0L) {
                return null
            }
            return (downloaded.toDouble() / total.toDouble()).toFloat().coerceIn(0f, 1f)
        }

    companion object {
        fun checking(): LocalModelProvisioningStatus {
            return LocalModelProvisioningStatus(
                summary = LocalModelArtifactStatus(
                    displayName = "Summary model",
                    fileName = LocalModelProvisioner.SUMMARY_FILE_NAME,
                    state = LocalModelInstallState.CHECKING,
                    detail = "Checking local installation.",
                ),
                embedding = LocalModelArtifactStatus(
                    displayName = "Embedding model",
                    fileName = LocalModelProvisioner.EMBEDDING_FILE_NAME,
                    state = LocalModelInstallState.CHECKING,
                    detail = "Checking local installation.",
                ),
            )
        }
    }
}

class LocalModelProvisioner(
    private val context: Context,
    private val settingsStore: SettingsStore,
) {
    suspend fun currentStatus(): LocalModelProvisioningStatus = withContext(Dispatchers.IO) {
        val settings = settingsStore.load().normalized()
        LocalModelProvisioningStatus(
            summary = summaryStatus(settings),
            embedding = embeddingStatus(settings),
        )
    }

    suspend fun ensureInstalled(
        onStatus: ((LocalModelProvisioningStatus) -> Unit)? = null,
    ): LocalModelProvisioningStatus = withContext(Dispatchers.IO) {
        installMutex.withLock {
            val settings = settingsStore.load().normalized()
            var summary = summaryStatus(settings)
            var embedding = embeddingStatus(settings)
            fun emit() {
                onStatus?.invoke(LocalModelProvisioningStatus(summary = summary, embedding = embedding))
            }

            emit()
            summary = ensureSummaryInstalled(settings) { updated ->
                summary = updated
                emit()
            }
            embedding = ensureEmbeddingInstalled(settings) { updated ->
                embedding = updated
                emit()
            }
            settingsStore.save(
                settings.copy(
                    summaryModelPath = summary.installedPath ?: settings.summaryModelPath,
                    embeddingModelPath = embedding.installedPath ?: settings.embeddingModelPath,
                ),
            )
            LocalModelProvisioningStatus(summary = summary, embedding = embedding).also {
                onStatus?.invoke(it)
            }
        }
    }

    private fun summaryStatus(settings: com.voiceledger.lite.data.LocalAiSettings): LocalModelArtifactStatus {
        val resolved = LocalModelLocator.resolveSummaryModel(context, settings)
        return if (resolved != null) {
            LocalModelArtifactStatus(
                displayName = "Summary model",
                fileName = File(resolved.path).name,
                state = LocalModelInstallState.READY,
                detail = "Installed and ready for Gemma 4 LLM summaries.",
                installedPath = resolved.path,
                modelLabel = resolved.label,
                downloadedBytes = File(resolved.path).length(),
                totalBytes = File(resolved.path).length(),
            )
        } else {
            LocalModelArtifactStatus(
                displayName = "Summary model",
                fileName = SUMMARY_FILE_NAME,
                state = LocalModelInstallState.MISSING,
                detail = "Not installed yet. Summaries will fall back until the app downloads Gemma 4.",
            )
        }
    }

    private fun embeddingStatus(settings: com.voiceledger.lite.data.LocalAiSettings): LocalModelArtifactStatus {
        val resolved = LocalModelLocator.resolveEmbeddingModel(context, settings)
        return if (resolved != null) {
            LocalModelArtifactStatus(
                displayName = "Embedding model",
                fileName = File(resolved.path).name,
                state = LocalModelInstallState.READY,
                detail = "Installed and ready for semantic retrieval.",
                installedPath = resolved.path,
                modelLabel = resolved.label,
                downloadedBytes = File(resolved.path).length(),
                totalBytes = File(resolved.path).length(),
            )
        } else {
            LocalModelArtifactStatus(
                displayName = "Embedding model",
                fileName = EMBEDDING_FILE_NAME,
                state = LocalModelInstallState.MISSING,
                detail = "Not installed. Ask will fall back to hashed vectors until the app downloads it.",
            )
        }
    }

    private fun ensureSummaryInstalled(
        settings: com.voiceledger.lite.data.LocalAiSettings,
        onStatus: (LocalModelArtifactStatus) -> Unit,
    ): LocalModelArtifactStatus {
        val existing = LocalModelLocator.resolveSummaryModel(context, settings)
        if (existing != null) {
            return LocalModelArtifactStatus(
                displayName = "Summary model",
                fileName = File(existing.path).name,
                state = LocalModelInstallState.READY,
                detail = "Installed and ready for Gemma 4 LLM summaries.",
                installedPath = existing.path,
                modelLabel = existing.label,
                downloadedBytes = File(existing.path).length(),
                totalBytes = File(existing.path).length(),
            )
        }
        installBundledAssetIfPresent(SUMMARY_ASSET_PATH, SUMMARY_FILE_NAME)?.let { installed ->
            return LocalModelArtifactStatus(
                displayName = "Summary model",
                fileName = installed.name,
                state = LocalModelInstallState.READY,
                detail = "Bundled with this app build and ready for Gemma 4 LLM summaries.",
                installedPath = installed.absolutePath,
                modelLabel = installed.nameWithoutExtension,
                downloadedBytes = installed.length(),
                totalBytes = installed.length(),
            )
        }
        if (BuildConfig.SUMMARY_MODEL_URL.isBlank()) {
            return LocalModelArtifactStatus(
                displayName = "Summary model",
                fileName = SUMMARY_FILE_NAME,
                state = LocalModelInstallState.MISSING,
                detail = "No bundled summary model was found, so the app will download Gemma 4 on first use.",
            )
        }
        return downloadModel(
            displayName = "Summary model",
            url = BuildConfig.SUMMARY_MODEL_URL,
            fileName = SUMMARY_FILE_NAME,
            successDetail = "Downloaded and ready for Gemma 4 LLM summaries.",
            failureDetail = "Download failed. Summaries will keep using the built-in fallback until this model is available.",
            onStatus = onStatus,
        )
    }

    private fun ensureEmbeddingInstalled(
        settings: com.voiceledger.lite.data.LocalAiSettings,
        onStatus: (LocalModelArtifactStatus) -> Unit,
    ): LocalModelArtifactStatus {
        val existing = LocalModelLocator.resolveEmbeddingModel(context, settings)
        if (existing != null) {
            return LocalModelArtifactStatus(
                displayName = "Embedding model",
                fileName = File(existing.path).name,
                state = LocalModelInstallState.READY,
                detail = "Installed and ready for semantic retrieval.",
                installedPath = existing.path,
                modelLabel = existing.label,
                downloadedBytes = File(existing.path).length(),
                totalBytes = File(existing.path).length(),
            )
        }
        installBundledAssetIfPresent(EMBEDDING_ASSET_PATH, EMBEDDING_FILE_NAME)?.let { installed ->
            return LocalModelArtifactStatus(
                displayName = "Embedding model",
                fileName = installed.name,
                state = LocalModelInstallState.READY,
                detail = "Bundled with this app build and ready for semantic retrieval.",
                installedPath = installed.absolutePath,
                modelLabel = installed.nameWithoutExtension,
                downloadedBytes = installed.length(),
                totalBytes = installed.length(),
            )
        }
        if (BuildConfig.EMBEDDING_MODEL_URL.isBlank()) {
            return LocalModelArtifactStatus(
                displayName = "Embedding model",
                fileName = EMBEDDING_FILE_NAME,
                state = LocalModelInstallState.MISSING,
                detail = "No bundled embedding model was found, so the app will download the text embedder on first use.",
            )
        }
        return downloadModel(
            displayName = "Embedding model",
            url = BuildConfig.EMBEDDING_MODEL_URL,
            fileName = EMBEDDING_FILE_NAME,
            successDetail = "Downloaded and ready for semantic retrieval.",
            failureDetail = "Download failed. Ask will keep using hashed fallback vectors until this model is available.",
            onStatus = onStatus,
        )
    }

    private fun downloadModel(
        displayName: String,
        url: String,
        fileName: String,
        successDetail: String,
        failureDetail: String,
        onStatus: (LocalModelArtifactStatus) -> Unit,
    ): LocalModelArtifactStatus {
        val modelDir = File(context.filesDir, MODEL_DIRECTORY_NAME).apply { mkdirs() }
        val destination = File(modelDir, fileName)
        val partial = File(modelDir, "$fileName.download")
        return runCatching {
            fetchToFile(
                displayName = displayName,
                fileName = fileName,
                url = url,
                destination = partial,
                onStatus = onStatus,
            )
            require(partial.length() > 0L) { "Downloaded file was empty." }
            if (destination.exists()) {
                destination.delete()
            }
            if (!partial.renameTo(destination)) {
                partial.copyTo(destination, overwrite = true)
                partial.delete()
            }
            LocalModelArtifactStatus(
                displayName = displayName,
                fileName = destination.name,
                state = LocalModelInstallState.READY,
                detail = successDetail,
                installedPath = destination.absolutePath,
                modelLabel = destination.nameWithoutExtension,
                downloadedBytes = destination.length(),
                totalBytes = destination.length(),
            )
        }.getOrElse { exception ->
            val detail = when {
                exception.message?.contains("HTTP 404") == true ->
                    "The release URL for this model is configured, but the asset has not been published yet (HTTP 404)."
                else -> "$failureDetail ${exception.message ?: "Unknown error."}"
            }
            LocalModelArtifactStatus(
                displayName = displayName,
                fileName = fileName,
                state = LocalModelInstallState.FAILED,
                detail = detail,
            )
        }
    }

    private fun installBundledAssetIfPresent(assetPath: String, fileName: String): File? {
        val modelDir = File(context.filesDir, MODEL_DIRECTORY_NAME).apply { mkdirs() }
        val destination = File(modelDir, fileName)
        if (destination.exists() && destination.length() > 0L) {
            return destination
        }
        val opened = runCatching { context.assets.open(assetPath) }.getOrNull() ?: return null
        opened.use { input ->
            FileOutputStream(destination).use { output ->
                input.copyTo(output)
            }
        }
        return destination.takeIf { it.exists() && it.length() > 0L }
    }

    private fun fetchToFile(
        displayName: String,
        fileName: String,
        url: String,
        destination: File,
        onStatus: (LocalModelArtifactStatus) -> Unit,
    ) {
        val existingBytes = destination.takeIf(File::exists)?.length()?.takeIf { it > 0L } ?: 0L
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            instanceFollowRedirects = true
            connectTimeout = 15_000
            readTimeout = 300_000
            setRequestProperty("User-Agent", "LedgerLite/0.1.0")
            if (existingBytes > 0L) {
                setRequestProperty("Range", "bytes=$existingBytes-")
            }
        }

        connection.connect()
        try {
            val responseCode = connection.responseCode
            require(responseCode in 200..299 || responseCode == HttpURLConnection.HTTP_PARTIAL) {
                "Server responded with HTTP $responseCode."
            }
            val append = responseCode == HttpURLConnection.HTTP_PARTIAL && existingBytes > 0L
            val expectedBytes = when {
                append -> connection.contentLengthLong.takeIf { it >= 0L }?.plus(existingBytes)
                else -> connection.contentLengthLong.takeIf { it > 0L }
            }
            if (!append && existingBytes > 0L && destination.exists()) {
                destination.delete()
            }
            val availableBytes = destination.parentFile?.usableSpace ?: Long.MAX_VALUE
            if (expectedBytes != null) {
                val remainingBytes = (expectedBytes - if (append) existingBytes else 0L).coerceAtLeast(0L)
                require(availableBytes > remainingBytes + MIN_REQUIRED_FREE_SPACE_BYTES) {
                    "Not enough free storage for this model download."
                }
            }
            connection.inputStream.use { input ->
                FileOutputStream(destination, append).use { output ->
                    copyWithProgress(
                        input = input,
                        output = output,
                        displayName = displayName,
                        fileName = fileName,
                        totalBytes = expectedBytes,
                        initialBytes = if (append) existingBytes else 0L,
                        onStatus = onStatus,
                    )
                }
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun copyWithProgress(
        input: java.io.InputStream,
        output: FileOutputStream,
        displayName: String,
        fileName: String,
        totalBytes: Long?,
        initialBytes: Long,
        onStatus: (LocalModelArtifactStatus) -> Unit,
    ) {
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var downloadedBytes = initialBytes
        var lastReportedBytes = -1L
        onStatus(
            LocalModelArtifactStatus(
                displayName = displayName,
                fileName = fileName,
                state = LocalModelInstallState.DOWNLOADING,
                detail = if (initialBytes > 0L) "Resuming download." else "Downloading model.",
                downloadedBytes = downloadedBytes,
                totalBytes = totalBytes,
            ),
        )
        while (true) {
            val read = input.read(buffer)
            if (read == -1) {
                break
            }
            output.write(buffer, 0, read)
            downloadedBytes += read
            if (
                lastReportedBytes == -1L ||
                downloadedBytes - lastReportedBytes >= PROGRESS_REPORT_STEP_BYTES ||
                (totalBytes != null && downloadedBytes >= totalBytes)
            ) {
                lastReportedBytes = downloadedBytes
                onStatus(
                    LocalModelArtifactStatus(
                        displayName = displayName,
                        fileName = fileName,
                        state = LocalModelInstallState.DOWNLOADING,
                        detail = if (initialBytes > 0L) "Resuming download." else "Downloading model.",
                        downloadedBytes = downloadedBytes,
                        totalBytes = totalBytes,
                    ),
                )
            }
        }
        output.flush()
    }

    companion object {
        private val installMutex = Mutex()
        internal const val MODEL_DIRECTORY_NAME = "models"
        internal const val SUMMARY_FILE_NAME = "gemma-4-E2B-it.litertlm"
        internal const val EMBEDDING_FILE_NAME = "text_embedder.tflite"
        private const val MIN_REQUIRED_FREE_SPACE_BYTES = 256L * 1024L * 1024L
        private const val PROGRESS_REPORT_STEP_BYTES = 4L * 1024L * 1024L
        private const val SUMMARY_ASSET_PATH = "models/$SUMMARY_FILE_NAME"
        private const val EMBEDDING_ASSET_PATH = "models/$EMBEDDING_FILE_NAME"
    }
}

private fun LocalModelArtifactStatus.hasKnownByteCount(): Boolean {
    return downloadedBytes != null && totalBytes != null && totalBytes > 0L
}
