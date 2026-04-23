package com.voiceledger.lite.semantic

import android.content.Context
import com.voiceledger.lite.data.LocalAiSettings
import java.text.Normalizer
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

class LocalAnswerEngine(private val context: Context) {
    private val liteRtLmEngine = LocalLiteRtLmEngine(context)

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
        val prompt = buildPrompt(question.trim(), documents)
        val response = runCatching {
            liteRtLmEngine.openSession(model = model, settings = normalized).use { session ->
                session.generate(prompt)
            }
        }.getOrNull()?.trim().orEmpty()
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
                append('[')
                append(formatter.format(Instant.ofEpochMilli(document.createdAtEpochMs)))
                append("] ")
                append(sanitizeText(document.title, 180).ifBlank { "Untitled" })
                append('\n')
                append(sanitizeText(document.body, 700))
            }
        }

        return """
            Answer the question using only the provided notes and summaries.
            If the sources are insufficient, say that directly.
            Keep the answer concise and factual.

            Question:
            ${sanitizeText(question, 400)}

            $sourceBlock
        """.trimIndent()
    }

    private fun sanitizeText(value: String, maxChars: Int): String {
        return Normalizer.normalize(value, Normalizer.Form.NFKC)
            .replace("\r\n", "\n")
            .replace(CONTROL_CHAR_REGEX, " ")
            .replace(EXCESS_BLANK_LINE_REGEX, "\n\n")
            .trim()
            .take(maxChars)
    }

    companion object {
        private val CONTROL_CHAR_REGEX = Regex("[\\u0000-\\u0008\\u000B\\u000C\\u000E-\\u001F]")
        private val EXCESS_BLANK_LINE_REGEX = Regex("\\n{3,}")
    }
}
