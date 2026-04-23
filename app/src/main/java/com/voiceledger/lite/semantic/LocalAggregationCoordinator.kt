package com.voiceledger.lite.semantic

import android.content.Context
import com.voiceledger.lite.data.LabelEntity
import com.voiceledger.lite.data.LedgerRepository
import com.voiceledger.lite.data.LocalAiSettings
import com.voiceledger.lite.data.NoteEntity
import com.voiceledger.lite.data.NoteWithLabels
import com.voiceledger.lite.data.RollupGranularity
import com.voiceledger.lite.data.SemanticEntryEntity
import com.voiceledger.lite.data.SettingsStore
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.YearMonth
import kotlin.math.max
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class LocalAggregationCoordinator(
    context: Context,
    private val repository: LedgerRepository,
    private val settingsStore: SettingsStore,
    private val json: Json = Json { ignoreUnknownKeys = true },
) {
    private val summaryEngine = LocalSummaryEngine(context)
    private val embeddingEngine = LocalEmbeddingEngine(context)
    private val answerEngine = LocalAnswerEngine(context)
    private val zoneId = ZoneId.systemDefault()

    suspend fun runAggregation(rebuildFromStartDate: Boolean = false): String {
        val settings = settingsStore.load().normalized()
        val notes = repository.allNotesAscending()
        reindexNotes(notes, settings)

        if (notes.isEmpty()) {
            return "No notes yet. Create Summary will begin after the first note."
        }

        val configuredFloor = settings.summaryStartDate
            .takeIf(String::isNotBlank)
            ?.let { LocalDate.parse(it).atStartOfDay(zoneId).toInstant().toEpochMilli() }

        if (rebuildFromStartDate && configuredFloor != null) {
            repository.markDirtyFrom(configuredFloor)
        }

        var dailySources = notes.map { note ->
            SemanticDocument(
                sourceId = "note:${note.id}",
                title = note.title,
                body = note.body,
                noteIds = listOf(note.id),
                createdAtEpochMs = note.createdAtEpochMs,
            )
        }

        RollupGranularity.entries.forEach { granularity ->
            val checkpoint = repository.checkpoint(granularity)
            repository.updateCheckpoint(
                checkpoint.copy(
                    lastRunStartedEpochMs = System.currentTimeMillis(),
                    lastError = null,
                ),
            )

            val sourceFloor = checkpoint.dirtyFromEpochMs
                ?: checkpoint.lastCompletedEndEpochMs
                ?: dailySources.first().createdAtEpochMs
            val effectiveFloor = configuredFloor?.let { max(it, sourceFloor) } ?: sourceFloor

            try {
                val lastProcessedEnd = processGranularity(
                    granularity = granularity,
                    sourceDocuments = dailySources,
                    floorEpochMs = effectiveFloor,
                    settings = settings,
                )
                val refreshedCheckpoint = repository.checkpoint(granularity)
                repository.updateCheckpoint(
                    refreshedCheckpoint.copy(
                        dirtyFromEpochMs = null,
                        lastCompletedEndEpochMs = lastProcessedEnd ?: refreshedCheckpoint.lastCompletedEndEpochMs,
                        lastRunFinishedEpochMs = System.currentTimeMillis(),
                        lastError = null,
                    ),
                )
            } catch (exception: Exception) {
                val refreshedCheckpoint = repository.checkpoint(granularity)
                repository.updateCheckpoint(
                    refreshedCheckpoint.copy(
                        lastRunFinishedEpochMs = System.currentTimeMillis(),
                        lastError = exception.message ?: "Aggregation failed.",
                    ),
                )
                throw exception
            }

            dailySources = repository.rollupsByGranularity(granularity).map { rollup ->
                SemanticDocument(
                    sourceId = rollup.id,
                    title = rollup.title,
                    body = buildString {
                        appendLine(rollup.overview)
                        rollup.highlights.forEach { appendLine(it) }
                    }.trim(),
                    noteIds = rollup.noteIds,
                    createdAtEpochMs = rollup.periodStartEpochMs,
                )
            }
        }

        return "Summaries and semantic search index refreshed."
    }

    suspend fun search(query: String, labelIds: Set<Long> = emptySet()): SemanticSearchResponse {
        val normalized = query.trim()
        if (normalized.isBlank()) {
            return SemanticSearchResponse(route = emptyList(), hits = emptyList())
        }

        val settings = settingsStore.load().normalized()
        val queryVector = embeddingEngine.embed(normalized, settings)
        val decodedEntries = repository.allSemanticEntries().mapNotNull(::decodeEntry)
        if (decodedEntries.isEmpty()) {
            return SemanticSearchResponse(route = emptyList(), hits = emptyList())
        }

        val noteLabelScope = if (labelIds.isEmpty()) {
            null
        } else {
            repository.noteIdsWithAnyLabels(labelIds)
        }
        if (noteLabelScope != null && noteLabelScope.isEmpty()) {
            return SemanticSearchResponse(route = emptyList(), hits = emptyList())
        }

        val noteEntries = decodedEntries.filter { it.entry.kind == "note" && it.entry.noteId != null }
        val rollupEntriesById = decodedEntries
            .filter { it.entry.kind == "rollup" }
            .associateBy { it.entry.rollupId ?: it.entry.sourceId }
        val rollups = repository.allRollups()
        val rollupsById = rollups.associateBy(RollupSnapshot::id)

        val topYears = selectStage(
            rollups = rollups.filter { it.granularity == RollupGranularity.YEARLY },
            rollupEntriesById = rollupEntriesById,
            queryVector = queryVector,
            labelScope = noteLabelScope,
            limit = 2,
        )
        val topMonths = selectStage(
            rollups = rollups.filter { it.granularity == RollupGranularity.MONTHLY && withinParents(it, topYears) },
            rollupEntriesById = rollupEntriesById,
            queryVector = queryVector,
            labelScope = noteLabelScope,
            limit = 3,
        )
        val topWeeks = selectStage(
            rollups = rollups.filter { it.granularity == RollupGranularity.WEEKLY && withinParents(it, topMonths) },
            rollupEntriesById = rollupEntriesById,
            queryVector = queryVector,
            labelScope = noteLabelScope,
            limit = 4,
        )
        val topDays = selectStage(
            rollups = rollups.filter { it.granularity == RollupGranularity.DAILY && withinParents(it, topWeeks) },
            rollupEntriesById = rollupEntriesById,
            queryVector = queryVector,
            labelScope = noteLabelScope,
            limit = 5,
        )

        val candidateNoteIds = buildCandidateNoteIds(
            topDays = topDays,
            topWeeks = topWeeks,
            topMonths = topMonths,
            topYears = topYears,
            labelScope = noteLabelScope,
            allNoteIds = noteEntries.mapNotNull { it.entry.noteId }.toSet(),
        )
        val noteMap = repository.notesWithLabelsByIds(candidateNoteIds)

        val noteHits = noteEntries
            .filter { decoded ->
                val noteId = decoded.entry.noteId ?: return@filter false
                candidateNoteIds.contains(noteId)
            }
            .map { decoded ->
                val noteId = decoded.entry.noteId ?: error("Note id missing.")
                val note = noteMap[noteId]
                SemanticSearchHit(
                    entryId = decoded.entry.entryId,
                    kind = "note",
                    title = decoded.entry.title,
                    preview = note?.note?.body?.take(220) ?: decoded.entry.body,
                    score = cosineSimilarity(queryVector, decoded.embedding),
                    noteId = noteId,
                    rollupId = null,
                    granularity = null,
                    labels = note?.labels?.map(LabelEntity::name).orEmpty(),
                )
            }
            .sortedByDescending(SemanticSearchHit::score)
            .take(settings.searchResultLimit)

        val route = listOfNotNull(
            topYears.firstOrNull(),
            topMonths.firstOrNull(),
            topWeeks.firstOrNull(),
            topDays.firstOrNull(),
        ).map { scored ->
            SearchRouteStep(
                granularity = scored.rollup.granularity,
                title = scored.rollup.title,
                score = scored.score,
            )
        }

        val remainingResultSlots = (settings.searchResultLimit - noteHits.size).coerceAtLeast(0)
        val rollupHits = (topDays + topWeeks + topMonths + topYears)
            .distinctBy { it.rollup.id }
            .map { scored ->
                SemanticSearchHit(
                    entryId = "rollup:${scored.rollup.id}",
                    kind = "rollup",
                    title = scored.rollup.title,
                    preview = scored.rollup.overview,
                    score = scored.score,
                    noteId = scored.rollup.noteIds.firstOrNull(),
                    rollupId = scored.rollup.id,
                    granularity = scored.rollup.granularity,
                )
            }
            .filter { hit ->
                noteHits.none { existing -> existing.entryId == hit.entryId }
            }
            .take(remainingResultSlots)

        val hits = if (noteHits.size >= settings.searchResultLimit) {
            noteHits
        } else {
            noteHits + rollupHits
        }
        val answerDocuments = buildAnswerDocuments(
            hits = hits,
            notesById = noteMap,
            rollupsById = rollupsById,
        )
        val answer = runCatching {
            answerEngine.answer(normalized, answerDocuments, settings)
        }.getOrNull()

        return SemanticSearchResponse(
            route = route,
            hits = hits,
            answer = answer,
            answerNotice = if (hits.isNotEmpty() && answer == null) {
                "No local answer model was found. Ask is showing retrieved notes and summaries only."
            } else {
                null
            },
        )
    }

    private suspend fun reindexNotes(notes: List<NoteEntity>, settings: LocalAiSettings) {
        notes.forEach { note ->
            val embedding = embeddingEngine.embed("${note.title}\n${note.body}", settings)
            repository.replaceSemanticEntry(
                SemanticEntryEntity(
                    entryId = "note:${note.id}",
                    kind = "note",
                    sourceId = "note:${note.id}",
                    title = note.title,
                    body = note.body.take(220),
                    noteId = note.id,
                    rollupId = null,
                    granularity = null,
                    embeddingJson = json.encodeToString(embedding.toList()),
                    updatedAtEpochMs = System.currentTimeMillis(),
                ),
            )
        }
    }

    private suspend fun processGranularity(
        granularity: RollupGranularity,
        sourceDocuments: List<SemanticDocument>,
        floorEpochMs: Long,
        settings: LocalAiSettings,
    ): Long? {
        val floorStart = startOfPeriod(floorEpochMs, granularity)
        val existingBucketStarts = repository.rollupsByGranularity(granularity)
            .map(RollupSnapshot::periodStartEpochMs)
            .filter { it >= floorStart }
        val sourceBucketStarts = sourceDocuments
            .map { startOfPeriod(it.createdAtEpochMs, granularity) }
            .filter { it >= floorStart }
        val bucketStarts = (sourceBucketStarts + existingBucketStarts)
            .distinct()
            .sorted()

        if (bucketStarts.isEmpty()) {
            return null
        }

        var lastProcessedEnd: Long? = null
        bucketStarts.forEach { bucketStart ->
            val bucketEnd = endOfPeriod(bucketStart, granularity)
            val bucketDocuments = sourceDocuments
                .filter { document ->
                    val sourceStart = startOfPeriod(document.createdAtEpochMs, granularity)
                    sourceStart == bucketStart
                }
                .sortedByDescending(SemanticDocument::createdAtEpochMs)
            val docsForSummary = bucketDocuments.take(settings.maxSourcesPerRollup)

            val rollupId = "${granularity.name.lowercase()}:$bucketStart"
            if (docsForSummary.isEmpty()) {
                repository.deleteRollup(rollupId)
                return@forEach
            }

            val insight = summaryEngine.summarize(
                documents = docsForSummary,
                granularity = granularity,
                periodStartEpochMs = bucketStart,
                periodEndEpochMs = bucketEnd,
                settings = settings,
            )
            val noteIds = bucketDocuments.flatMap(SemanticDocument::noteIds).distinct()

            repository.replaceRollup(
                id = rollupId,
                granularity = granularity,
                periodStartEpochMs = bucketStart,
                periodEndEpochMs = bucketEnd,
                sourceCount = bucketDocuments.size,
                insight = insight,
                noteIds = noteIds,
            )

            val rollupEmbedding = embeddingEngine.embed(
                buildString {
                    appendLine(insight.title)
                    appendLine(insight.overview)
                    insight.highlights.forEach { appendLine(it) }
                },
                settings,
            )
            repository.replaceSemanticEntry(
                SemanticEntryEntity(
                    entryId = "rollup:$rollupId",
                    kind = "rollup",
                    sourceId = rollupId,
                    title = insight.title,
                    body = insight.overview.take(220),
                    noteId = noteIds.firstOrNull(),
                    rollupId = rollupId,
                    granularity = granularity.name,
                    embeddingJson = json.encodeToString(rollupEmbedding.toList()),
                    updatedAtEpochMs = System.currentTimeMillis(),
                ),
            )
            lastProcessedEnd = bucketEnd
        }
        return lastProcessedEnd
    }

    private fun buildCandidateNoteIds(
        topDays: List<ScoredRollup>,
        topWeeks: List<ScoredRollup>,
        topMonths: List<ScoredRollup>,
        topYears: List<ScoredRollup>,
        labelScope: Set<Long>?,
        allNoteIds: Set<Long>,
    ): Set<Long> {
        val timelineIds = when {
            topDays.isNotEmpty() -> topDays.flatMap { it.rollup.noteIds }
            topWeeks.isNotEmpty() -> topWeeks.flatMap { it.rollup.noteIds }
            topMonths.isNotEmpty() -> topMonths.flatMap { it.rollup.noteIds }
            topYears.isNotEmpty() -> topYears.flatMap { it.rollup.noteIds }
            else -> emptyList()
        }.toSet()

        return when {
            labelScope == null && timelineIds.isNotEmpty() -> timelineIds
            labelScope == null -> allNoteIds
            timelineIds.isEmpty() -> labelScope
            else -> timelineIds.intersect(labelScope).ifEmpty { labelScope }
        }
    }

    private fun buildAnswerDocuments(
        hits: List<SemanticSearchHit>,
        notesById: Map<Long, NoteWithLabels>,
        rollupsById: Map<String, RollupSnapshot>,
    ): List<SemanticDocument> {
        return hits.asSequence()
            .mapNotNull { hit ->
                when {
                    hit.noteId != null -> {
                        val note = notesById[hit.noteId] ?: return@mapNotNull null
                        SemanticDocument(
                            sourceId = "note:${note.note.id}",
                            title = note.note.title,
                            body = note.note.body,
                            noteIds = listOf(note.note.id),
                            createdAtEpochMs = note.note.createdAtEpochMs,
                        )
                    }
                    hit.rollupId != null -> {
                        val rollup = rollupsById[hit.rollupId] ?: return@mapNotNull null
                        SemanticDocument(
                            sourceId = rollup.id,
                            title = rollup.title,
                            body = buildString {
                                appendLine(rollup.overview)
                                rollup.highlights.forEach { appendLine(it) }
                            }.trim(),
                            noteIds = rollup.noteIds,
                            createdAtEpochMs = rollup.periodStartEpochMs,
                        )
                    }
                    else -> null
                }
            }
            .distinctBy(SemanticDocument::sourceId)
            .take(6)
            .toList()
    }

    private fun selectStage(
        rollups: List<RollupSnapshot>,
        rollupEntriesById: Map<String, DecodedEntry>,
        queryVector: FloatArray,
        labelScope: Set<Long>?,
        limit: Int,
    ): List<ScoredRollup> {
        return rollups.mapNotNull { rollup ->
            val decoded = rollupEntriesById[rollup.id] ?: return@mapNotNull null
            val semanticScore = cosineSimilarity(queryVector, decoded.embedding)
            val labelBoost = if (labelScope.isNullOrEmpty() || rollup.noteIds.isEmpty()) {
                0f
            } else {
                val matchingCount = rollup.noteIds.count(labelScope::contains)
                0.15f * (matchingCount.toFloat() / rollup.noteIds.size.toFloat())
            }
            ScoredRollup(
                rollup = rollup,
                score = semanticScore + labelBoost,
            )
        }
            .sortedByDescending(ScoredRollup::score)
            .take(limit)
    }

    private fun withinParents(candidate: RollupSnapshot, parents: List<ScoredRollup>): Boolean {
        if (parents.isEmpty()) {
            return true
        }
        return parents.any { parent ->
            candidate.periodStartEpochMs >= parent.rollup.periodStartEpochMs &&
                candidate.periodEndEpochMs <= parent.rollup.periodEndEpochMs
        }
    }

    private fun decodeEntry(entry: SemanticEntryEntity): DecodedEntry? {
        val embedding = runCatching {
            json.decodeFromString<List<Float>>(entry.embeddingJson).toFloatArray()
        }.getOrNull() ?: return null
        return DecodedEntry(entry = entry, embedding = embedding)
    }

    private fun startOfPeriod(epochMs: Long, granularity: RollupGranularity): Long {
        val dateTime = Instant.ofEpochMilli(epochMs).atZone(zoneId).toLocalDateTime()
        return when (granularity) {
            RollupGranularity.DAILY -> dateTime.toLocalDate().atStartOfDay(zoneId).toInstant().toEpochMilli()
            RollupGranularity.WEEKLY -> {
                val start = dateTime.toLocalDate().with(DayOfWeek.MONDAY)
                start.atStartOfDay(zoneId).toInstant().toEpochMilli()
            }
            RollupGranularity.MONTHLY -> {
                YearMonth.from(dateTime).atDay(1).atStartOfDay(zoneId).toInstant().toEpochMilli()
            }
            RollupGranularity.YEARLY -> {
                LocalDate.of(dateTime.year, 1, 1).atStartOfDay(zoneId).toInstant().toEpochMilli()
            }
        }
    }

    private fun endOfPeriod(startEpochMs: Long, granularity: RollupGranularity): Long {
        val start = Instant.ofEpochMilli(startEpochMs).atZone(zoneId).toLocalDateTime()
        val next = when (granularity) {
            RollupGranularity.DAILY -> start.plusDays(1)
            RollupGranularity.WEEKLY -> start.plusWeeks(1)
            RollupGranularity.MONTHLY -> start.plusMonths(1)
            RollupGranularity.YEARLY -> start.plusYears(1)
        }
        return next.atZone(zoneId).toInstant().toEpochMilli() - 1
    }

    private fun cosineSimilarity(left: FloatArray, right: FloatArray): Float {
        if (left.isEmpty() || right.isEmpty() || left.size != right.size) {
            return 0f
        }
        var sum = 0f
        for (index in left.indices) {
            sum += left[index] * right[index]
        }
        return sum
    }
}

private data class DecodedEntry(
    val entry: SemanticEntryEntity,
    val embedding: FloatArray,
)

private data class ScoredRollup(
    val rollup: RollupSnapshot,
    val score: Float,
)
