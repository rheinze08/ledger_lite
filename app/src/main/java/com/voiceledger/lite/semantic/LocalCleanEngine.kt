package com.voiceledger.lite.semantic

import android.content.Context
import com.voiceledger.lite.data.LocalAiSettings
import java.text.Normalizer

class LocalCleanEngine(private val context: Context) {
    private val liteRtLmEngine = LocalLiteRtLmEngine(context)

    suspend fun clean(body: String, settings: LocalAiSettings): String? {
        val trimmed = body.trim()
        if (trimmed.isBlank()) return null

        val normalized = settings.normalized()
        val model = LocalModelLocator.resolveSummaryModel(context, normalized) ?: return null
        val prompt = buildPrompt(trimmed)

        return runCatching {
            liteRtLmEngine.openSession(model = model, settings = normalized).use { session ->
                session.generate(prompt)
            }
        }.getOrNull()?.trim()?.takeIf { it.isNotBlank() }
    }

    private fun buildPrompt(body: String): String {
        val sanitized = sanitizeText(body, MAX_BODY_CHARS)
        return """
            The following text was composed by a user, potentially using speech-to-text, and may contain formatting errors, misspellings, wrong words from dictation (such as homophones), missing punctuation, or awkward phrasing from dictation artifacts.

            Lightly clean and correct the text. Fix spelling, punctuation, capitalization, and obvious wrong words. Preserve the user's original meaning, voice, and all factual content exactly. Do not add new information. Do not summarize or shorten.

            Return only the corrected text with no preamble, explanation, or commentary.

            Text:
            $sanitized
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

        // Gemma 4 E2B has a 2048-token context window shared by input and output.
        // The instruction block is ~100 tokens; mirroring the input for output leaves
        // ~900 tokens per side, which maps to roughly 2700 chars at 3 chars/token.
        private const val MAX_BODY_CHARS = 2700
    }
}
