package com.voiceledger.lite.data

import android.content.Context

class SettingsStore(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun load(): LocalAiSettings {
        return LocalAiSettings(
            summaryModelPath = prefs.getString(KEY_SUMMARY_MODEL_PATH, null) ?: DEFAULTS.summaryModelPath,
            embeddingModelPath = prefs.getString(KEY_EMBEDDING_MODEL_PATH, null) ?: DEFAULTS.embeddingModelPath,
            summaryStartDate = prefs.getString(KEY_SUMMARY_START_DATE, null) ?: DEFAULTS.summaryStartDate,
            backgroundProcessingTime = prefs.getString(
                KEY_BACKGROUND_PROCESSING_TIME,
                null,
            ) ?: DEFAULTS.backgroundProcessingTime,
            maxSourcesPerRollup = prefs.getInt(KEY_MAX_SOURCES_PER_ROLLUP, DEFAULTS.maxSourcesPerRollup),
            embeddingDimensions = prefs.getInt(KEY_EMBEDDING_DIMENSIONS, DEFAULTS.embeddingDimensions),
            searchResultLimit = prefs.getInt(KEY_SEARCH_RESULT_LIMIT, DEFAULTS.searchResultLimit),
            maxTokens = prefs.getInt(KEY_MAX_TOKENS, DEFAULTS.maxTokens),
            topK = prefs.getInt(KEY_TOP_K, DEFAULTS.topK),
            temperature = prefs.getFloat(KEY_TEMPERATURE, DEFAULTS.temperature),
            backgroundProcessingEnabled = prefs.getBoolean(
                KEY_BACKGROUND_PROCESSING_ENABLED,
                DEFAULTS.backgroundProcessingEnabled,
            ),
        ).normalized()
    }

    fun save(settings: LocalAiSettings) {
        val normalized = settings.normalized()
        prefs.edit()
            .putString(KEY_SUMMARY_MODEL_PATH, normalized.summaryModelPath)
            .putString(KEY_EMBEDDING_MODEL_PATH, normalized.embeddingModelPath)
            .putString(KEY_SUMMARY_START_DATE, normalized.summaryStartDate)
            .putString(KEY_BACKGROUND_PROCESSING_TIME, normalized.backgroundProcessingTime)
            .putInt(KEY_MAX_SOURCES_PER_ROLLUP, normalized.maxSourcesPerRollup)
            .putInt(KEY_EMBEDDING_DIMENSIONS, normalized.embeddingDimensions)
            .putInt(KEY_SEARCH_RESULT_LIMIT, normalized.searchResultLimit)
            .putInt(KEY_MAX_TOKENS, normalized.maxTokens)
            .putInt(KEY_TOP_K, normalized.topK)
            .putFloat(KEY_TEMPERATURE, normalized.temperature)
            .putBoolean(KEY_BACKGROUND_PROCESSING_ENABLED, normalized.backgroundProcessingEnabled)
            .apply()
    }

    fun isInitialSetupComplete(): Boolean {
        return prefs.getBoolean(KEY_INITIAL_SETUP_COMPLETE, false)
    }

    fun setInitialSetupComplete(value: Boolean) {
        prefs.edit()
            .putBoolean(KEY_INITIAL_SETUP_COMPLETE, value)
            .apply()
    }

    companion object {
        private const val PREFS_NAME = "voice_ledger_lite_settings"
        private const val KEY_SUMMARY_MODEL_PATH = "summary_model_path"
        private const val KEY_EMBEDDING_MODEL_PATH = "embedding_model_path"
        private const val KEY_SUMMARY_START_DATE = "summary_start_date"
        private const val KEY_BACKGROUND_PROCESSING_TIME = "background_processing_time"
        private const val KEY_MAX_SOURCES_PER_ROLLUP = "max_sources_per_rollup"
        private const val KEY_EMBEDDING_DIMENSIONS = "embedding_dimensions"
        private const val KEY_SEARCH_RESULT_LIMIT = "search_result_limit"
        private const val KEY_MAX_TOKENS = "max_tokens"
        private const val KEY_TOP_K = "top_k"
        private const val KEY_TEMPERATURE = "temperature"
        private const val KEY_BACKGROUND_PROCESSING_ENABLED = "background_processing_enabled"
        private const val KEY_INITIAL_SETUP_COMPLETE = "initial_setup_complete"

        private val DEFAULTS = LocalAiSettings()
    }
}
