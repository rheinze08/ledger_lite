@file:Suppress("DEPRECATION")

package com.voiceledger.lite.semantic

import android.content.Context
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import com.voiceledger.lite.data.LocalAiSettings
import com.voiceledger.lite.data.RollupGranularity
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json

class LocalSummaryEngine(
    private val context: Context,
    private val json: Json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    },
) {
    suspend fun summarize(
        documents: List<SemanticDocument>,
        granularity: RollupGranularity,
        periodStartEpochMs: Long,
        periodEndEpochMs: Long,
        settings: LocalAiSettings,
    ): AggregateInsight {
        val normalized = settings.normalized()
        val model = LocalModelLocator.resolveSummaryModel(context, normalized)
        if (model != null) {
            runCatching {
                return summarizeWithModel(
                    documents = documents,
                    granularity = granularity,
                    periodStartEpochMs = periodStartEpochMs,
                    periodEndEpochMs = periodEndEpochMs,
                    settings = normalized,
                    model = model,
                )
            }
        }
        return summarizeHeuristically(documents, granularity, periodStartEpochMs, periodEndEpochMs)
    }

    private fun summarizeWithModel(
        documents: List<SemanticDocument>,
        granularity: RollupGranularity,
        periodStartEpochMs: Long,
        periodEndEpochMs: Long,
        settings: LocalAiSettings,
        model: ResolvedLocalModel,
    ): AggregateInsight {
        val options = LlmInference.LlmInferenceOptions.builder()
            .setModelPath(model.path)
            .setMaxTokens(settings.maxTokens)
            .setMaxTopK(settings.topK)
            .build()

        val prompt = buildPrompt(documents, granularity, periodStartEpochMs, periodEndEpochMs)
        return LlmInference.createFromOptions(context, options).use { inference ->
            val rawResponse = inference.generateResponse(prompt)
            val parsed = json.decodeFromString<ModelInsightPayload>(extractJsonObject(rawResponse))
            AggregateInsight(
                modelLabel = model.label,
                title = parsed.title.ifBlank { defaultTitle(granularity, periodStartEpochMs, periodEndEpochMs) },
                overview = parsed.overview.trim(),
                highlights = parsed.highlights.map(String::trim).filter(String::isNotBlank).take(5),
                themes = parsed.themes
                    .map { ThemeBucket(it.label.trim(), it.summary.trim(), it.noteIds.distinct()) }
                    .filter { it.label.isNotBlank() && it.summary.isNotBlank() },
            )
        }
    }

    private fun summarizeHeuristically(
        documents: List<SemanticDocument>,
        granularity: RollupGranularity,
        periodStartEpochMs: Long,
        periodEndEpochMs: Long,
    ): AggregateInsight {
        val allText = documents.joinToString(" ") { "${it.title} ${it.body}" }
        val keywords = topKeywords(allText)
        val highlights = documents
            .sortedByDescending { it.createdAtEpochMs }
            .take(4)
            .map { document ->
                buildString {
                    append(document.title.ifBlank { "Untitled" })
                    if (document.body.isNotBlank()) {
                        append(": ")
                        append(document.body.lineSequence().firstOrNull()?.take(100) ?: document.body.take(100))
                    }
                }
            }
            .ifEmpty { listOf("No source notes were available for this rollup.") }

        val themes = keywords.take(3).mapNotNull { keyword ->
            val matchingDocs = documents.filter { document ->
                val lowered = "${document.title} ${document.body}".lowercase()
                lowered.contains(keyword)
            }
            if (matchingDocs.isEmpty()) {
                null
            } else {
                ThemeBucket(
                    label = keyword.replaceFirstChar { it.uppercase() },
                    summary = "Notes repeatedly mention $keyword across ${matchingDocs.size} item(s).",
                    noteIds = matchingDocs.flatMap(SemanticDocument::noteIds).distinct(),
                )
            }
        }.ifEmpty {
            listOf(
                ThemeBucket(
                    label = "General",
                    summary = "This rollup combines ${documents.size} item(s) from the selected period.",
                    noteIds = documents.flatMap(SemanticDocument::noteIds).distinct(),
                ),
            )
        }

        val rangeLabel = formatDateRange(periodStartEpochMs, periodEndEpochMs)
        val overview = buildString {
            append("Aggregated ${documents.size} item(s) for $rangeLabel.")
            if (keywords.isNotEmpty()) {
                append(" Recurring threads: ")
                append(keywords.take(4).joinToString(", "))
                append('.')
            }
        }

        return AggregateInsight(
            modelLabel = "Built-in local summarizer",
            title = defaultTitle(granularity, periodStartEpochMs, periodEndEpochMs),
            overview = overview,
            highlights = highlights,
            themes = themes,
        )
    }

    private fun buildPrompt(
        documents: List<SemanticDocument>,
        granularity: RollupGranularity,
        periodStartEpochMs: Long,
        periodEndEpochMs: Long,
    ): String {
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
                append(document.body.take(700))
            }
        }

        return """
            Summarize the following ${granularity.name.lowercase()} journal material from ${formatDateRange(periodStartEpochMs, periodEndEpochMs)}.
            Return strict JSON in this exact shape:
            {
              "title": "short title",
              "overview": "short paragraph",
              "highlights": ["bullet", "bullet"],
              "themes": [
                {
                  "label": "theme name",
                  "summary": "why the notes belong together",
                  "noteIds": [1, 2]
                }
              ]
            }

            Rules:
            - Keep the overview concise and grounded in the source text.
            - Use 2 to 5 highlights.
            - Use 1 to 4 themes.
            - noteIds must only contain ids from the provided note_ids values.
            - Do not wrap the JSON in markdown fences.

            Sources:
            $sourceBlock
        """.trimIndent()
    }

    private fun topKeywords(text: String): List<String> {
        val stopWords = setOf(
            "about", "after", "again", "along", "also", "been", "being", "between", "could",
            "from", "have", "into", "just", "note", "notes", "that", "them", "then", "there",
            "they", "this", "today", "with", "were", "will", "would", "your", "journal",
        )
        return text.lowercase()
            .split(Regex("[^a-z0-9]+"))
            .filter { token -> token.length >= 4 && token !in stopWords }
            .groupingBy { it }
            .eachCount()
            .entries
            .sortedByDescending { it.value }
            .map { it.key }
            .distinct()
    }

    private fun defaultTitle(
        granularity: RollupGranularity,
        periodStartEpochMs: Long,
        periodEndEpochMs: Long,
    ): String {
        return "${granularity.name.lowercase().replaceFirstChar { it.uppercase() }} rollup: ${formatDateRange(periodStartEpochMs, periodEndEpochMs)}"
    }

    private fun formatDateRange(startEpochMs: Long, endEpochMs: Long): String {
        val formatter = DateTimeFormatter.ofPattern("MMM d, yyyy").withZone(ZoneId.systemDefault())
        return "${formatter.format(Instant.ofEpochMilli(startEpochMs))} to ${formatter.format(Instant.ofEpochMilli(endEpochMs))}"
    }

    private fun extractJsonObject(raw: String): String {
        val startIndex = raw.indexOf('{')
        val endIndex = raw.lastIndexOf('}')
        if (startIndex == -1 || endIndex <= startIndex) {
            return raw.trim()
        }
        return raw.substring(startIndex, endIndex + 1)
    }

    @Serializable
    private data class ModelInsightPayload(
        val title: String = "",
        val overview: String,
        val highlights: List<String>,
        val themes: List<ModelThemePayload>,
    )

    @Serializable
    private data class ModelThemePayload(
        val label: String,
        val summary: String,
        val noteIds: List<Long>,
    )
}
