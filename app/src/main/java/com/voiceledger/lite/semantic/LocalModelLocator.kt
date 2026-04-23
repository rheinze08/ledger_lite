package com.voiceledger.lite.semantic

import android.content.Context
import com.voiceledger.lite.data.LocalAiSettings
import java.io.File

data class ResolvedLocalModel(
    val path: String,
    val label: String,
)

object LocalModelLocator {
    private const val DEFAULT_LLM_LABEL = "gemma4:e2b"
    private const val DEFAULT_EMBEDDING_LABEL = "local-embedding-model"

    private val summaryCandidates = listOf(
        "gemma4-e2b.task",
        "gemma4_e2b.task",
        "summary-model.task",
    )

    private val embeddingCandidates = listOf(
        "embedding-model.tflite",
        "local-embedding-model.tflite",
    )

    fun resolveSummaryModel(context: Context, settings: LocalAiSettings): ResolvedLocalModel? {
        return resolve(
            context = context,
            explicitPath = settings.summaryModelPath,
            candidateNames = summaryCandidates,
            defaultLabel = DEFAULT_LLM_LABEL,
        )
    }

    fun resolveEmbeddingModel(context: Context, settings: LocalAiSettings): ResolvedLocalModel? {
        return resolve(
            context = context,
            explicitPath = settings.embeddingModelPath,
            candidateNames = embeddingCandidates,
            defaultLabel = DEFAULT_EMBEDDING_LABEL,
        )
    }

    private fun resolve(
        context: Context,
        explicitPath: String,
        candidateNames: List<String>,
        defaultLabel: String,
    ): ResolvedLocalModel? {
        val explicit = explicitPath.trim().takeIf(String::isNotBlank)?.let(::File)
        if (explicit != null && explicit.exists() && explicit.isFile) {
            return ResolvedLocalModel(
                path = explicit.absolutePath,
                label = explicit.nameWithoutExtension.ifBlank { defaultLabel },
            )
        }

        val candidate = modelRoots(context)
            .flatMap { root -> candidateNames.asSequence().map(root::resolve) }
            .firstOrNull { it.exists() && it.isFile }
            ?: return null

        return ResolvedLocalModel(
            path = candidate.absolutePath,
            label = candidate.nameWithoutExtension.ifBlank { defaultLabel },
        )
    }

    private fun modelRoots(context: Context): Sequence<File> {
        return sequence {
            yield(File(context.filesDir, "models"))
            val externalModelsDir = context.getExternalFilesDir("models")
            if (externalModelsDir != null) {
                yield(externalModelsDir)
            }
        }
    }
}
