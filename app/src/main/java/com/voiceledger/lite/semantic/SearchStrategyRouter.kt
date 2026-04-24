package com.voiceledger.lite.semantic

import android.content.Context
import com.voiceledger.lite.data.LocalAiSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class SearchStrategyRouter(private val context: Context) {
    private val liteRtLmEngine = LocalLiteRtLmEngine(context)

    suspend fun classify(query: String, settings: LocalAiSettings): SearchStrategy = withContext(Dispatchers.IO) {
        val model = LocalModelLocator.resolveSummaryModel(context, settings.normalized())
            ?: return@withContext SearchStrategy.BROAD_SCAN

        val prompt = buildPrompt(query)
        val response = runCatching {
            liteRtLmEngine.openSession(model = model, settings = routerSettings(settings)).use { session ->
                session.generate(prompt)
            }
        }.getOrNull()?.trim() ?: return@withContext SearchStrategy.BROAD_SCAN

        // Broad scan is the superset that can always produce a correct answer,
        // so anything short of a confident "S" falls back to it.
        val firstLetter = response.firstOrNull { it.isLetter() }?.uppercaseChar()
        if (firstLetter == 'S') SearchStrategy.SEMANTIC else SearchStrategy.BROAD_SCAN
    }

    private fun buildPrompt(query: String): String {
        val sanitized = query.trim().take(300)
        return """
            Decide how a local notes search should answer the query below. The notes are a personal journal.

            Two strategies are available:
              S = Topical lookup. A handful of the most topically similar notes will fully contain the answer.
              B = Broad scan. The answer could live in notes on unrelated topics, so many or all notes must be checked.

            Pick S only when the answer is about one clear topic and reading a few of those notes is enough.
            Pick B when the query asks for an extreme (max, min, heaviest, lowest, best, worst), an aggregate (count, total, sum, average, how many times), anything qualified by "ever", "any time", "always", "never", or any event that could be mentioned inside notes about unrelated topics.

            If you are unsure, answer B.

            Examples:
              "do I have a dog?" -> S
              "what did I say about my sister?" -> S
              "did I book the dentist?" -> S
              "what is my heaviest bench press ever?" -> B
              "how many times have I been sick this year?" -> B
              "what was my lowest weight?" -> B
              "have I ever mentioned Paris?" -> B

            Query: $sanitized

            Answer (single letter, S or B):
        """.trimIndent()
    }

    private fun routerSettings(base: LocalAiSettings): LocalAiSettings {
        // Greedy decoding — we only need one token
        return base.normalized().copy(temperature = 0f, topK = 1)
    }
}
