package com.voiceledger.lite.semantic

import android.content.Context
import com.voiceledger.lite.data.LocalAiSettings
import java.text.Normalizer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class StageEvidenceGate(private val context: Context) {
    private val liteRtLmEngine = LocalLiteRtLmEngine(context)

    suspend fun open(settings: LocalAiSettings): Evaluator? = withContext(Dispatchers.IO) {
        val normalized = settings.normalized()
        val model = LocalModelLocator.resolveSummaryModel(context, normalized) ?: return@withContext null
        val session = runCatching {
            liteRtLmEngine.openSession(model = model, settings = gateSettings(normalized))
        }.getOrNull() ?: return@withContext null
        Evaluator(session)
    }

    private fun gateSettings(base: LocalAiSettings): LocalAiSettings {
        // Greedy decoding — the gate only reads the first letter of the reply.
        return base.normalized().copy(temperature = 0f, topK = 1)
    }

    inner class Evaluator(private val session: LocalLiteRtLmEngine.PreparedSession) : AutoCloseable {

        fun isSufficient(query: String, candidates: List<Candidate>): Boolean {
            if (candidates.isEmpty()) return false
            val prompt = buildPrompt(query, candidates)
            val response = runCatching { session.generate(prompt) }.getOrNull()?.trim()
                ?: return true
            val firstLetter = response.firstOrNull { it.isLetter() }?.uppercaseChar() ?: return true
            return firstLetter == 'Y'
        }

        override fun close() {
            session.close()
        }
    }

    data class Candidate(
        val label: String,
        val title: String,
        val overview: String,
    )

    companion object {
        private val CONTROL_CHAR_REGEX = Regex("[\\u0000-\\u0008\\u000B\\u000C\\u000E-\\u001F]")
        private val EXCESS_BLANK_LINE_REGEX = Regex("\\n{3,}")

        private fun buildPrompt(query: String, candidates: List<Candidate>): String {
            val block = candidates.joinToString("\n\n") { candidate ->
                buildString {
                    append('[')
                    append(sanitize(candidate.label, 40))
                    append("] ")
                    append(sanitize(candidate.title, 160).ifBlank { "Untitled" })
                    append('\n')
                    append(sanitize(candidate.overview, 500))
                }
            }
            return """
                You are deciding whether retrieved journal summaries strongly address a user's question.

                Question:
                ${sanitize(query, 300)}

                Retrieved summaries:
                $block

                Answer Y only when at least one summary clearly discusses the question's subject, so that reading the underlying notes from those periods would likely answer it.
                Answer N if the summaries cover other topics, touch the subject only in passing, or do not mention it at all.
                If unsure, answer N.

                Answer (single letter, Y or N):
            """.trimIndent()
        }

        private fun sanitize(value: String, maxChars: Int): String {
            return Normalizer.normalize(value, Normalizer.Form.NFKC)
                .replace("\r\n", "\n")
                .replace(CONTROL_CHAR_REGEX, " ")
                .replace(EXCESS_BLANK_LINE_REGEX, "\n\n")
                .trim()
                .take(maxChars)
        }
    }
}
