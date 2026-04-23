package com.voiceledger.lite.semantic

import android.content.Context
import com.voiceledger.lite.data.LocalAiSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class SearchStrategyRouter(private val context: Context) {
    private val liteRtLmEngine = LocalLiteRtLmEngine(context)

    suspend fun classify(query: String, settings: LocalAiSettings): SearchStrategy = withContext(Dispatchers.IO) {
        val model = LocalModelLocator.resolveSummaryModel(context, settings.normalized())
            ?: return@withContext SearchStrategy.SEMANTIC

        val prompt = buildPrompt(query)
        val response = runCatching {
            liteRtLmEngine.openSession(model = model, settings = routerSettings(settings)).use { session ->
                session.generate(prompt)
            }
        }.getOrNull()?.trim() ?: return@withContext SearchStrategy.SEMANTIC

        // Look at the first letter-class character in the response.
        // Prompt instructs the model to reply Y for broad scan, X for semantic.
        // Default to SEMANTIC if unclear so the faster path is always the fallback.
        val firstLetter = response.firstOrNull { it.isLetter() }?.uppercaseChar()
        if (firstLetter == 'Y') SearchStrategy.BROAD_SCAN else SearchStrategy.SEMANTIC
    }

    private fun buildPrompt(query: String): String {
        val sanitized = query.trim().take(300)
        return """
            Classify the search query below. Reply with a single letter only: X or Y.

            Reply X if the answer is likely contained in the most semantically similar notes (topical lookup, e.g. "do I have a dog?", "what did I say about my sister?").
            Reply Y if answering requires exhaustively scanning all notes regardless of topic, such as finding a maximum, minimum, total, count, or any event that could appear anywhere in time (e.g. "what is my heaviest bench press ever?", "how many times have I been sick?", "what was my lowest weight?").

            Query: $sanitized

            Answer (X or Y):
        """.trimIndent()
    }

    private fun routerSettings(base: LocalAiSettings): LocalAiSettings {
        // Greedy decoding — we only need one token
        return base.normalized().copy(temperature = 0f, topK = 1)
    }
}
