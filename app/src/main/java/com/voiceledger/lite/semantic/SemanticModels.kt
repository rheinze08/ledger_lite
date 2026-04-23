package com.voiceledger.lite.semantic

import com.voiceledger.lite.data.RollupGranularity
import kotlinx.serialization.Serializable

@Serializable
data class ThemeBucket(
    val label: String,
    val summary: String,
    val noteIds: List<Long>,
)

data class AggregateInsight(
    val modelLabel: String,
    val title: String,
    val overview: String,
    val highlights: List<String>,
    val themes: List<ThemeBucket>,
)

data class RollupSnapshot(
    val id: String,
    val granularity: RollupGranularity,
    val periodStartEpochMs: Long,
    val periodEndEpochMs: Long,
    val generatedAtEpochMs: Long,
    val modelLabel: String,
    val sourceCount: Int,
    val title: String,
    val overview: String,
    val highlights: List<String>,
    val themes: List<ThemeBucket>,
    val noteIds: List<Long>,
)

data class AggregationCheckpoint(
    val granularity: RollupGranularity,
    val lastCompletedEndEpochMs: Long?,
    val dirtyFromEpochMs: Long?,
    val lastRunStartedEpochMs: Long?,
    val lastRunFinishedEpochMs: Long?,
    val lastError: String?,
)

data class SemanticSearchHit(
    val entryId: String,
    val kind: String,
    val title: String,
    val preview: String,
    val score: Float,
    val noteId: Long?,
    val rollupId: String?,
    val granularity: RollupGranularity?,
    val labels: List<String> = emptyList(),
)

data class SearchRouteStep(
    val granularity: RollupGranularity,
    val title: String,
    val score: Float,
)

data class GeneratedAnswer(
    val text: String,
    val modelLabel: String,
    val sourceCount: Int,
)

data class SemanticSearchResponse(
    val route: List<SearchRouteStep>,
    val hits: List<SemanticSearchHit>,
    val answer: GeneratedAnswer? = null,
    val answerNotice: String? = null,
)

data class SemanticDocument(
    val sourceId: String,
    val title: String,
    val body: String,
    val noteIds: List<Long>,
    val createdAtEpochMs: Long,
)
