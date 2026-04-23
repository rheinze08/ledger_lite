package com.voiceledger.lite.semantic

import android.content.Context
import com.voiceledger.lite.data.LocalAiSettings
import com.voiceledger.lite.data.RollupGranularity
import java.text.Normalizer
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

class LocalSummaryEngine(
    private val context: Context,
) {
    private val liteRtLmEngine = LocalLiteRtLmEngine(context)

    fun openSummarizer(settings: LocalAiSettings): PreparedSummarizer {
        val normalized = settings.normalized()
        val model = LocalModelLocator.resolveSummaryModel(context, normalized)
            ?: error("Summary model is not installed.")
        val session = runCatching {
            liteRtLmEngine.openSession(model = model, settings = normalized)
        }.getOrElse { exception ->
            throw IllegalStateException(
                "Summary model ${model.label} failed to initialize.",
                exception,
            )
        }
        return ModelSummarizer(
            session = session,
            model = model,
            maxTokens = normalized.maxTokens.coerceIn(MIN_SUMMARY_TOKENS, MAX_SUMMARY_TOKENS),
            topK = normalized.topK.coerceAtMost(SUMMARY_TOP_K),
        )
    }

    suspend fun summarize(
        documents: List<SemanticDocument>,
        granularity: RollupGranularity,
        periodStartEpochMs: Long,
        periodEndEpochMs: Long,
        settings: LocalAiSettings,
    ): AggregateInsight {
        return openSummarizer(settings).use { summarizer ->
            summarizer.summarize(
                documents = documents,
                granularity = granularity,
                periodStartEpochMs = periodStartEpochMs,
                periodEndEpochMs = periodEndEpochMs,
            )
        }
    }

    interface PreparedSummarizer : AutoCloseable {
        suspend fun summarize(
            documents: List<SemanticDocument>,
            granularity: RollupGranularity,
            periodStartEpochMs: Long,
            periodEndEpochMs: Long,
            onDiagnostic: suspend (String) -> Unit = {},
        ): AggregateInsight

        override fun close() = Unit
    }

    private inner class ModelSummarizer(
        private val session: LocalLiteRtLmEngine.PreparedSession,
        private val model: ResolvedLocalModel,
        private val maxTokens: Int,
        private val topK: Int,
    ) : PreparedSummarizer {
        override suspend fun summarize(
            documents: List<SemanticDocument>,
            granularity: RollupGranularity,
            periodStartEpochMs: Long,
            periodEndEpochMs: Long,
            onDiagnostic: suspend (String) -> Unit,
        ): AggregateInsight {
            onDiagnostic(
                "Preparing ${documents.size} source document(s), ${documents.sumOf { it.body.length }} body chars before sanitizing",
            )
            onDiagnostic("Using LiteRT-LM backend ${session.backendLabel}, maxTokens=$maxTokens, topK=$topK")
            val prompt = buildPrompt(documents, granularity, periodStartEpochMs, periodEndEpochMs)
            onDiagnostic(
                "Prompt built for ${granularity.name.lowercase()} ${formatDateRange(periodStartEpochMs, periodEndEpochMs)} (${prompt.length} chars)",
            )
            onDiagnostic("Calling local LiteRT-LM conversation.sendMessage")
            val rawResponse = runCatching { session.generate(prompt) }.getOrElse { exception ->
                throw IllegalStateException(
                    "LiteRT-LM inference failed for ${granularity.name.lowercase()} ${formatDateRange(periodStartEpochMs, periodEndEpochMs)}.",
                    exception,
                )
            }
            onDiagnostic("LiteRT-LM returned ${rawResponse.length} chars")
            val overview = sanitizeGeneratedSummary(rawResponse)
            if (overview.isBlank()) {
                throw IllegalStateException(
                    "LiteRT-LM returned no usable summary text for ${granularity.name.lowercase()} ${formatDateRange(periodStartEpochMs, periodEndEpochMs)}.",
                )
            }
            onDiagnostic("Summary response normalized successfully")
            return AggregateInsight(
                modelLabel = model.label,
                title = defaultTitle(granularity, periodStartEpochMs, periodEndEpochMs),
                overview = overview,
                highlights = emptyList(),
                themes = emptyList(),
            )
        }

        override fun close() {
            session.close()
        }
    }

    private fun buildPrompt(
        documents: List<SemanticDocument>,
        granularity: RollupGranularity,
        periodStartEpochMs: Long,
        periodEndEpochMs: Long,
    ): String {
        val formatter = DateTimeFormatter.ISO_LOCAL_DATE_TIME.withZone(ZoneId.systemDefault())
        val sourceBlock = buildSourceBlock(documents, granularity, formatter)

        return when (granularity) {
            RollupGranularity.DAILY -> """
                Summarize these notes for ${formatDateRange(periodStartEpochMs, periodEndEpochMs)}.
                Write 3 to 5 bullet points. Start each bullet with "- ".
                Each bullet covers one distinct event, mood, decision, or observation.
                Rewrite in your own words. Skip filler, greetings, and repetition.

                $sourceBlock
            """.trimIndent()
            else -> """
                Summarize these notes for ${formatDateRange(periodStartEpochMs, periodEndEpochMs)}.
                Write 4 to 6 bullet points. Start each bullet with "- ".
                Each bullet covers one key theme, pattern, or event from the period.
                Rewrite in your own words. Skip filler, greetings, and repetition.

                $sourceBlock
            """.trimIndent()
        }
    }

    private fun buildSourceBlock(
        documents: List<SemanticDocument>,
        granularity: RollupGranularity,
        formatter: DateTimeFormatter,
    ): String {
        val totalBodyBudget = totalBodyBudget(granularity)
        val perDocumentBudget = (totalBodyBudget / documents.size.coerceAtLeast(1))
            .coerceIn(MIN_SOURCE_BODY_CHARS, MAX_SOURCE_BODY_CHARS)
        var remainingBudget = totalBodyBudget

        return documents.joinToString("\n\n") { document ->
            val bodyLimit = minOf(perDocumentBudget, remainingBudget).coerceAtLeast(MIN_SOURCE_BODY_CHARS)
            val body = sanitizePromptText(document.body, maxChars = bodyLimit)
            val title = sanitizePromptText(document.title, maxChars = 60)
            remainingBudget = (remainingBudget - body.length).coerceAtLeast(0)
            buildString {
                append('[')
                append(formatter.format(Instant.ofEpochMilli(document.createdAtEpochMs)))
                append("] ")
                append(title.ifBlank { "Untitled" })
                append('\n')
                append(body)
            }
        }
    }

    private fun sanitizePromptText(value: String, maxChars: Int = Int.MAX_VALUE): String {
        return Normalizer.normalize(value, Normalizer.Form.NFKC)
            .replace("\r\n", "\n")
            .replace(CONTROL_CHAR_REGEX, " ")
            .replace(EXCESS_BLANK_LINE_REGEX, "\n\n")
            .trim()
            .take(maxChars)
    }

    private fun sanitizeGeneratedSummary(value: String): String {
        return Normalizer.normalize(value, Normalizer.Form.NFKC)
            .replace("\r\n", "\n")
            .replace(CONTROL_CHAR_REGEX, " ")
            .replace(EXCESS_BLANK_LINE_REGEX, "\n\n")
            .trim()
    }

    private fun totalBodyBudget(granularity: RollupGranularity): Int {
        // Gemma 4 E2B context window is 2048 tokens (input + output).
        // With 256 tokens reserved for output and ~20 for special tokens, the input budget
        // is ~1772 tokens ≈ 5000 chars at 3 chars/token (worst-case mixed content).
        // 4000-char body budget leaves ~1000 chars for instruction text, titles, and
        // timestamps, keeping total prompts well within the 5000-char input ceiling.
        return when (granularity) {
            // 8 docs × 500 chars = 4000; total prompt ~4700 chars ≈ 1567 input tokens
            RollupGranularity.DAILY -> 4000
            // Higher-granularity sources are already-summarized text; same body budget
            RollupGranularity.WEEKLY -> 4000
            RollupGranularity.MONTHLY -> 4000
            RollupGranularity.YEARLY -> 4000
        }
    }

    private fun defaultTitle(
        granularity: RollupGranularity,
        periodStartEpochMs: Long,
        periodEndEpochMs: Long,
    ): String {
        return "${granularity.name.lowercase().replaceFirstChar { it.uppercase() }} summary: ${formatDateRange(periodStartEpochMs, periodEndEpochMs)}"
    }

    private fun formatDateRange(startEpochMs: Long, endEpochMs: Long): String {
        val formatter = DateTimeFormatter.ofPattern("MMM d, yyyy").withZone(ZoneId.systemDefault())
        return "${formatter.format(Instant.ofEpochMilli(startEpochMs))} to ${formatter.format(Instant.ofEpochMilli(endEpochMs))}"
    }

    companion object {
        private val CONTROL_CHAR_REGEX = Regex("[\\u0000-\\u0008\\u000B\\u000C\\u000E-\\u001F]")
        private val EXCESS_BLANK_LINE_REGEX = Regex("\\n{3,}")
        private const val MIN_SOURCE_BODY_CHARS = 180
        private const val MAX_SOURCE_BODY_CHARS = 1500
        private const val MIN_SUMMARY_TOKENS = 512
        private const val MAX_SUMMARY_TOKENS = 2048
        private const val SUMMARY_TOP_K = 8
    }
}
