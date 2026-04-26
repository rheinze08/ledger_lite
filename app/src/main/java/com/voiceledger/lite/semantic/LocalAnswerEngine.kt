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

    suspend fun answerFromEvidence(
        question: String,
        cards: List<EvidenceCard>,
        settings: LocalAiSettings,
    ): GeneratedAnswer? {
        if (cards.isEmpty()) {
            return null
        }

        val normalized = settings.normalized()
        val model = LocalModelLocator.resolveSummaryModel(context, normalized) ?: return null
        val prompt = buildEvidencePrompt(question.trim(), cards)
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
            sourceCount = cards.size,
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

    private fun buildEvidencePrompt(question: String, cards: List<EvidenceCard>): String {
        val formatter = DateTimeFormatter.ISO_LOCAL_DATE.withZone(ZoneId.systemDefault())
        val evidenceBlock = cards.joinToString("\n") { card ->
            buildString {
                append("- [")
                append(formatter.format(Instant.ofEpochMilli(card.createdAtEpochMs)))
                append("] (relevance ").append(card.relevance).append(") ")
                append(sanitizeText(card.fact, 220))
                if (card.quote.isNotBlank()) {
                    append(" — \"")
                    append(sanitizeText(card.quote, 140))
                    append('"')
                }
            }
        }

        return """
            Answer the question using only the evidence items below. Each item is a fact extracted
            from one note, with its date and a relevance score from 1 (passing mention) to 3 (direct).
            Prefer higher-relevance items. If items disagree, surface the disagreement briefly. If the
            evidence does not actually answer the question, say so directly. Keep the answer concise.

            Question:
            ${sanitizeText(question, 400)}

            Evidence:
            $evidenceBlock
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
