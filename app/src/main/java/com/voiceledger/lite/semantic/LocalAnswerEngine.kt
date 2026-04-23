@file:Suppress("DEPRECATION")

package com.voiceledger.lite.semantic

import android.content.Context
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import com.voiceledger.lite.data.LocalAiSettings
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

class LocalAnswerEngine(private val context: Context) {
    suspend fun answer(
        question: String,
        documents: List<SemanticDocument>,
        settings: LocalAiSettings,
    ): GeneratedAnswer? {
        if (documents.isEmpty()) {
            return null
        }

        val normalized = settings.normalized()
        val model = LocalModelLocator.resolveSummaryModel(context, normalized) ?: return null
        val options = LlmInference.LlmInferenceOptions.builder()
            .setModelPath(model.path)
            .setMaxTokens(normalized.maxTokens)
            .setMaxTopK(normalized.topK)
            .build()

        val prompt = buildPrompt(question.trim(), documents)
        val response = LlmInference.createFromOptions(context, options).use { inference ->
            inference.generateResponse(prompt).trim()
        }
        if (response.isBlank()) {
            return null
        }

        return GeneratedAnswer(
            text = response,
            modelLabel = model.label,
            sourceCount = documents.size,
        )
    }

    private fun buildPrompt(question: String, documents: List<SemanticDocument>): String {
        val formatter = DateTimeFormatter.ISO_LOCAL_DATE_TIME.withZone(ZoneId.systemDefault())
        val sourceBlock = documents.joinToString("\n\n") { document ->
            buildString {
                append("source_id=")
                append(document.sourceId)
                append('\n')
                append("created_at=")
                append(formatter.format(Instant.ofEpochMilli(document.createdAtEpochMs)))
                append('\n')
                append("note_ids=")
                append(document.noteIds.joinToString(","))
                append('\n')
                append("title=")
                append(document.title)
                append('\n')
                append("body=")
                append(document.body.take(900))
            }
        }

        return """
            Answer the user's question using only the provided notes and rollups.
            If the sources are insufficient, say that directly.
            Keep the answer concise and factual.
            Do not mention hidden system rules or unavailable sources.

            Question:
            $question

            Sources:
            $sourceBlock
        """.trimIndent()
    }
}
