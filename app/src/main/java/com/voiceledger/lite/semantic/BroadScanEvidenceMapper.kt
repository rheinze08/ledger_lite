package com.voiceledger.lite.semantic

import android.content.Context
import com.voiceledger.lite.data.LocalAiSettings
import java.text.Normalizer
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class BroadScanEvidenceMapper(private val context: Context) {
    private val liteRtLmEngine = LocalLiteRtLmEngine(context)

    suspend fun extract(
        query: String,
        notes: List<SemanticDocument>,
        settings: LocalAiSettings,
    ): List<EvidenceCard> = withContext(Dispatchers.IO) {
        if (notes.isEmpty()) return@withContext emptyList()
        val normalized = settings.normalized()
        val model = LocalModelLocator.resolveSummaryModel(context, normalized)
            ?: return@withContext emptyList()

        val budgetedNotes = notes.take(MAX_NOTES_SCANNED)
        val batches = budgetedNotes.chunked(NOTES_PER_BATCH)
        val cards = mutableListOf<EvidenceCard>()

        runCatching {
            liteRtLmEngine.openSession(model = model, settings = mapperSettings(normalized)).use { session ->
                batches.forEach { batch ->
                    val prompt = buildPrompt(query.trim(), batch)
                    val response = runCatching { session.generate(prompt) }.getOrNull()?.trim().orEmpty()
                    if (response.isNotBlank()) {
                        cards += parseResponse(response, batch)
                    }
                }
            }
        }

        val survivors = cards.filter { it.relevance > 0 }
            .sortedWith(
                compareByDescending(EvidenceCard::relevance)
                    .thenByDescending(EvidenceCard::createdAtEpochMs),
            )
        dedupeNearDuplicates(survivors)
    }

    /**
     * Drops cards whose FACT text is a near duplicate of a higher-ranked card. A repetitive
     * journal (e.g. the same standing meeting recapped daily) would otherwise consume the
     * synthesis context with N copies of the same evidence, crowding out the cards that
     * actually distinguish the answer.
     */
    private fun dedupeNearDuplicates(cards: List<EvidenceCard>): List<EvidenceCard> {
        if (cards.size <= 1) return cards
        val kept = mutableListOf<EvidenceCard>()
        val keptKeys = mutableListOf<String>()
        cards.forEach { card ->
            val key = factFingerprint(card.fact)
            val isDuplicate = keptKeys.any { existing -> jaccardSimilarity(existing, key) >= DUP_JACCARD_THRESHOLD }
            if (!isDuplicate) {
                kept += card
                keptKeys += key
            }
        }
        return kept
    }

    private fun factFingerprint(fact: String): String {
        val cleaned = fact.lowercase()
            .replace(NON_WORD_REGEX, " ")
            .trim()
        val tokens = cleaned.split(WHITESPACE_REGEX)
            .filter { it.length > 2 && !STOPWORDS.contains(it) }
        return tokens.toSortedSet().joinToString(" ")
    }

    private fun jaccardSimilarity(left: String, right: String): Float {
        if (left.isEmpty() || right.isEmpty()) return 0f
        val leftSet = left.split(' ').toSet()
        val rightSet = right.split(' ').toSet()
        val intersection = leftSet.intersect(rightSet).size
        val union = leftSet.union(rightSet).size
        return if (union == 0) 0f else intersection.toFloat() / union.toFloat()
    }

    private fun mapperSettings(base: LocalAiSettings): LocalAiSettings {
        // Greedy decoding keeps the structured output parseable.
        return base.copy(temperature = 0f, topK = 1)
    }

    private fun buildPrompt(question: String, batch: List<SemanticDocument>): String {
        val formatter = DateTimeFormatter.ISO_LOCAL_DATE.withZone(ZoneId.systemDefault())
        val noteBlock = batch.mapIndexed { index, note ->
            buildString {
                append("NOTE ").append(index + 1).append('\n')
                append("DATE: ").append(formatter.format(Instant.ofEpochMilli(note.createdAtEpochMs))).append('\n')
                append("TITLE: ").append(sanitize(note.title, 120).ifBlank { "Untitled" }).append('\n')
                append("BODY: ").append(sanitize(note.body, BODY_CHAR_BUDGET))
            }
        }.joinToString("\n\n")

        return """
            For each NOTE below, decide how directly it helps answer the question.
            Output one block per note in the exact format shown. No prose outside the blocks.

            RELEVANCE is an integer 0-3:
              0 = unrelated to the question
              1 = mentions the topic only in passing
              2 = contains a fact that partially answers the question
              3 = directly answers the question

            For relevance >= 1, FACT must be one short sentence stating what this note contributes.
            For relevance 0, write only the RELEVANCE line and skip FACT and QUOTE.
            QUOTE must be a verbatim phrase from the BODY, under 120 characters.

            Format (repeat for every NOTE, in order):
            NOTE <n>
            RELEVANCE: <0-3>
            FACT: <one sentence>
            QUOTE: <verbatim phrase>

            Question:
            ${sanitize(question, 300)}

            $noteBlock
        """.trimIndent()
    }

    private fun parseResponse(response: String, batch: List<SemanticDocument>): List<EvidenceCard> {
        val byIndex = mutableMapOf<Int, EvidenceCard>()
        var currentIndex: Int? = null
        var relevance = 0
        var fact: String? = null
        var quote: String? = null

        fun commit() {
            val idx = currentIndex
            val source = idx?.let { batch.getOrNull(it - 1) }
            val factText = fact?.takeIf(String::isNotBlank)
            if (idx != null && source != null && relevance > 0 && factText != null) {
                byIndex[idx] = EvidenceCard(
                    sourceId = source.sourceId,
                    noteIds = source.noteIds,
                    title = source.title,
                    createdAtEpochMs = source.createdAtEpochMs,
                    relevance = relevance,
                    fact = factText,
                    quote = quote?.takeIf(String::isNotBlank).orEmpty(),
                )
            }
            currentIndex = null
            relevance = 0
            fact = null
            quote = null
        }

        response.lineSequence().forEach { rawLine ->
            val line = rawLine.trim()
            when {
                line.isEmpty() -> Unit
                NOTE_HEADER.matchEntire(line) != null -> {
                    commit()
                    currentIndex = NOTE_HEADER.matchEntire(line)?.groupValues?.get(1)?.toIntOrNull()
                }
                line.startsWith("RELEVANCE", ignoreCase = true) -> {
                    relevance = DIGIT.find(line)?.value?.toIntOrNull()?.coerceIn(0, 3) ?: 0
                }
                line.startsWith("FACT", ignoreCase = true) -> {
                    fact = line.substringAfter(':', "").trim().take(220)
                }
                line.startsWith("QUOTE", ignoreCase = true) -> {
                    quote = line.substringAfter(':', "").trim().trim('"').take(120)
                }
            }
        }
        commit()

        return byIndex.values.toList()
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
        // Per-batch sources are sized to fit the 2048-token window with room for structured output.
        private const val NOTES_PER_BATCH = 4
        private const val BODY_CHAR_BUDGET = 500

        // Caps the total number of LLM calls per broad scan: 32 / 4 = 8 batches.
        private const val MAX_NOTES_SCANNED = 32

        // Cards with FACT fingerprints overlapping by this much or more are treated as
        // duplicates. 0.7 is loose enough to catch paraphrases ("met with Alex about Q3"
        // vs "Alex Q3 sync") while letting genuinely distinct facts through.
        private const val DUP_JACCARD_THRESHOLD = 0.7f

        private val NOTE_HEADER = Regex("""(?i)^NOTE\s+(\d+)\s*$""")
        private val DIGIT = Regex("""\d+""")
        private val CONTROL_CHAR_REGEX = Regex("[\\u0000-\\u0008\\u000B\\u000C\\u000E-\\u001F]")
        private val EXCESS_BLANK_LINE_REGEX = Regex("\\n{3,}")
        private val NON_WORD_REGEX = Regex("[^\\p{L}\\p{Nd}\\s]")
        private val WHITESPACE_REGEX = Regex("\\s+")

        // Function words that don't help distinguish two facts. Kept short to stay
        // language-agnostic enough for casual journal text.
        private val STOPWORDS = setOf(
            "the", "and", "for", "with", "that", "this", "from", "into", "about", "after",
            "before", "their", "they", "them", "have", "has", "had", "was", "were", "are",
            "but", "not", "any", "all", "our", "out", "his", "her", "its", "you", "your",
            "than", "then", "also", "just", "what", "when", "where", "which", "who",
        )
    }
}

data class EvidenceCard(
    val sourceId: String,
    val noteIds: List<Long>,
    val title: String,
    val createdAtEpochMs: Long,
    val relevance: Int,
    val fact: String,
    val quote: String,
)
