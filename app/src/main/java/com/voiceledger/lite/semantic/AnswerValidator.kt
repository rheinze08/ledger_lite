package com.voiceledger.lite.semantic

import android.content.Context
import com.voiceledger.lite.data.LocalAiSettings
import java.text.Normalizer
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class AnswerValidator(private val context: Context) {
    private val liteRtLmEngine = LocalLiteRtLmEngine(context)

    suspend fun validate(
        question: String,
        cards: List<EvidenceCard>,
        proposedAnswer: String,
        settings: LocalAiSettings,
    ): AnswerValidation = withContext(Dispatchers.IO) {
        val trimmedAnswer = proposedAnswer.trim()
        if (trimmedAnswer.isBlank() || cards.isEmpty()) {
            return@withContext AnswerValidation(
                verdict = AnswerVerdict.UNSUPPORTED,
                reason = "No evidence or no answer to validate.",
            )
        }
        if (looksLikeIdk(trimmedAnswer)) {
            return@withContext AnswerValidation(
                verdict = AnswerVerdict.UNSUPPORTED,
                reason = "Answer already declined to answer.",
            )
        }

        val normalized = settings.normalized()
        val model = LocalModelLocator.resolveSummaryModel(context, normalized)
            ?: return@withContext AnswerValidation(
                verdict = AnswerVerdict.UNVALIDATED,
                reason = "Validator model not available.",
            )

        val prompt = buildPrompt(question.trim(), cards, trimmedAnswer)
        val response = runCatching {
            liteRtLmEngine.openSession(model = model, settings = validatorSettings(normalized)).use { session ->
                session.generate(prompt)
            }
        }.getOrNull()?.trim().orEmpty()

        if (response.isBlank()) {
            return@withContext AnswerValidation(
                verdict = AnswerVerdict.UNVALIDATED,
                reason = "Validator returned no output.",
            )
        }

        parse(response)
    }

    private fun validatorSettings(base: LocalAiSettings): LocalAiSettings {
        return base.copy(temperature = 0f, topK = 1)
    }

    private fun looksLikeIdk(answer: String): Boolean {
        val trimmed = answer.trim().lowercase()
        return IDK_PATTERNS.any { pattern -> trimmed.startsWith(pattern) }
    }

    private fun buildPrompt(question: String, cards: List<EvidenceCard>, proposedAnswer: String): String {
        val formatter = DateTimeFormatter.ISO_LOCAL_DATE.withZone(ZoneId.systemDefault())
        val evidenceBlock = cards.take(MAX_EVIDENCE_FOR_VALIDATION).joinToString("\n") { card ->
            buildString {
                append("- [")
                append(formatter.format(Instant.ofEpochMilli(card.createdAtEpochMs)))
                append("] (relevance ").append(card.relevance).append(") ")
                append(sanitize(card.fact, 200))
                if (card.quote.isNotBlank()) {
                    append(" — \"")
                    append(sanitize(card.quote, 120))
                    append('"')
                }
            }
        }

        // Framed in the user's own words: this is the best answer I came up with — should we
        // trust it, or fall back to "I don't know"? The model is asked to act as a strict
        // grounding checker rather than as an answerer.
        return """
            You are checking whether a proposed answer to a question is fully supported by the
            evidence below. The evidence is a list of facts extracted from the user's notes,
            each with a date and relevance score (1=passing, 2=partial, 3=direct).

            Be strict. A claim counts as supported only if at least one evidence item states it
            directly or strongly implies it. Numbers, names, and dates must match the evidence
            exactly. If the proposed answer adds details that are not in the evidence, that part
            is unsupported. If the evidence does not actually address the question, the verdict
            is UNSUPPORTED even when the answer sounds plausible.

            Reply in this exact format and nothing else:
            VERDICT: <SUPPORTED|PARTIAL|UNSUPPORTED>
            REASON: <one short sentence>

            Use SUPPORTED when every substantive claim in the answer is grounded in the evidence
            and the answer addresses the question.
            Use PARTIAL when the main claim is grounded but some secondary detail is not in the
            evidence, or when only one weak (relevance 1) item supports the answer.
            Use UNSUPPORTED when the answer makes claims absent from the evidence, when the
            evidence does not address the question, or when the answer contradicts the evidence.

            Question:
            ${sanitize(question, 300)}

            Evidence:
            $evidenceBlock

            Proposed answer:
            ${sanitize(proposedAnswer, 600)}

            Verdict:
        """.trimIndent()
    }

    private fun parse(response: String): AnswerValidation {
        var verdict: AnswerVerdict? = null
        var reason: String? = null
        response.lineSequence().forEach { rawLine ->
            val line = rawLine.trim()
            when {
                line.startsWith("VERDICT", ignoreCase = true) -> {
                    val tail = line.substringAfter(':', "").trim().uppercase()
                    verdict = when {
                        tail.startsWith("SUPPORTED") -> AnswerVerdict.SUPPORTED
                        tail.startsWith("PARTIAL") -> AnswerVerdict.PARTIAL
                        tail.startsWith("UNSUPPORTED") -> AnswerVerdict.UNSUPPORTED
                        else -> verdict
                    }
                }
                line.startsWith("REASON", ignoreCase = true) -> {
                    reason = line.substringAfter(':', "").trim().take(220).ifBlank { null }
                }
            }
        }
        // Fallback: if the model wrote a bare verdict word on its own, accept it.
        if (verdict == null) {
            val firstWord = response.trim().split(WHITESPACE).firstOrNull()?.uppercase()
            verdict = when (firstWord) {
                "SUPPORTED" -> AnswerVerdict.SUPPORTED
                "PARTIAL" -> AnswerVerdict.PARTIAL
                "UNSUPPORTED" -> AnswerVerdict.UNSUPPORTED
                else -> null
            }
        }
        return AnswerValidation(
            verdict = verdict ?: AnswerVerdict.UNVALIDATED,
            reason = reason,
        )
    }

    private fun sanitize(value: String, maxChars: Int): String {
        return Normalizer.normalize(value, Normalizer.Form.NFKC)
            .replace("\r\n", "\n")
            .replace(CONTROL_CHAR_REGEX, " ")
            .replace(EXCESS_BLANK_LINE_REGEX, "\n\n")
            .trim()
            .take(maxChars)
    }

    companion object {
        // Cap to keep the validator prompt within the 2048-token compiled window. Synthesis
        // already trims to a similar count, so this is a defensive cap rather than a primary
        // budget.
        private const val MAX_EVIDENCE_FOR_VALIDATION = 12

        private val WHITESPACE = Regex("\\s+")
        private val CONTROL_CHAR_REGEX = Regex("[\\u0000-\\u0008\\u000B\\u000C\\u000E-\\u001F]")
        private val EXCESS_BLANK_LINE_REGEX = Regex("\\n{3,}")

        private val IDK_PATTERNS = listOf(
            "i don't know",
            "i dont know",
            "i do not know",
            "i couldn't find",
            "i could not find",
            "no information",
            "the notes do not",
            "the notes don't",
            "no relevant",
        )
    }
}
