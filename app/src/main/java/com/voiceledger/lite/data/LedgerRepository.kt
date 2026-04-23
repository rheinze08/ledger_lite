package com.voiceledger.lite.data

import androidx.room.withTransaction
import com.voiceledger.lite.semantic.AggregateInsight
import com.voiceledger.lite.semantic.AggregationCheckpoint
import com.voiceledger.lite.semantic.RollupSnapshot
import com.voiceledger.lite.semantic.ThemeBucket
import java.time.Instant
import java.time.ZoneId
import java.util.Locale
import kotlin.math.min
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class LedgerRepository(
    private val database: LedgerDatabase,
    private val json: Json = Json { ignoreUnknownKeys = true },
) {
    private val noteDao = database.noteDao()
    private val labelDao = database.labelDao()
    private val rollupDao = database.rollupDao()
    private val semanticEntryDao = database.semanticEntryDao()
    private val checkpointDao = database.aggregationCheckpointDao()

    fun observeNotes(): Flow<List<NoteWithLabels>> = noteDao.observeAllWithLabels()

    fun observeLabels(): Flow<List<LabelEntity>> = labelDao.observeAll()

    fun observeRollups(): Flow<List<RollupSnapshot>> {
        return rollupDao.observeAll().map { entities ->
            entities.map(::toRollupSnapshot)
        }
    }

    fun observeCheckpoints(): Flow<List<AggregationCheckpoint>> {
        return checkpointDao.observeAll().map { entities ->
            entities.map(::toCheckpoint)
        }
    }

    suspend fun allNotesAscending(): List<NoteEntity> = noteDao.allAscending()

    suspend fun getNote(noteId: Long): NoteEntity? = noteDao.getById(noteId)

    suspend fun getNoteWithLabels(noteId: Long): NoteWithLabels? = noteDao.getWithLabelsById(noteId)

    suspend fun allLabels(): List<LabelEntity> = labelDao.all()

    suspend fun saveNote(noteId: Long?, title: String, body: String, labelIds: Set<Long>): Long {
        val now = System.currentTimeMillis()
        val cleanedTitle = title.trim()
        val cleanedBody = body.trim()
        val distinctLabelIds = labelIds.distinct()
        var dirtyEpochMs = now
        var savedId = noteId ?: 0

        database.withTransaction {
            savedId = if (noteId == null) {
                noteDao.insert(
                    NoteEntity(
                        title = cleanedTitle,
                        body = cleanedBody,
                        createdAtEpochMs = now,
                        updatedAtEpochMs = now,
                    ),
                )
            } else {
                val existing = noteDao.getById(noteId)
                dirtyEpochMs = existing?.createdAtEpochMs ?: now
                val note = if (existing == null) {
                    NoteEntity(
                        id = noteId,
                        title = cleanedTitle,
                        body = cleanedBody,
                        createdAtEpochMs = now,
                        updatedAtEpochMs = now,
                    )
                } else {
                    existing.copy(
                        title = cleanedTitle,
                        body = cleanedBody,
                        updatedAtEpochMs = now,
                    )
                }
                if (existing == null) {
                    noteDao.insert(note)
                } else {
                    noteDao.update(note)
                    note.id
                }
            }

            noteDao.deleteLabelRefsForNote(savedId)
            if (distinctLabelIds.isNotEmpty()) {
                noteDao.insertLabelRefs(
                    distinctLabelIds.map { labelId ->
                        NoteLabelCrossRef(
                            noteId = savedId,
                            labelId = labelId,
                        )
                    },
                )
            }

            val dirtyFrom = floorToDayStart(dirtyEpochMs)
            markDirtyFromInternal(dirtyFrom)
        }

        return savedId
    }

    suspend fun deleteNote(noteId: Long) {
        val existing = noteDao.getById(noteId)
        database.withTransaction {
            noteDao.deleteById(noteId)
            semanticEntryDao.deleteById("note:$noteId")
            existing?.let { note ->
                markDirtyFromInternal(floorToDayStart(note.createdAtEpochMs))
            }
        }
    }

    suspend fun saveLabel(labelId: Long?, rawName: String): LabelEntity {
        val cleanedName = normalizeLabelDisplay(rawName)
        require(cleanedName.isNotBlank()) { "Tag name cannot be empty." }

        val normalizedName = normalizeLabelKey(cleanedName)
        val existing = labelDao.getByNormalizedName(normalizedName)
        if (existing != null && existing.id != labelId) {
            error("Tag \"$cleanedName\" already exists.")
        }

        return if (labelId == null) {
            val savedId = labelDao.insert(
                LabelEntity(
                    name = cleanedName,
                    normalizedName = normalizedName,
                    createdAtEpochMs = System.currentTimeMillis(),
                ),
            )
            labelDao.getById(savedId) ?: error("Label save failed.")
        } else {
            val current = labelDao.getById(labelId)
                ?: error("That tag no longer exists.")
            val updated = current.copy(
                name = cleanedName,
                normalizedName = normalizedName,
            )
            labelDao.update(updated)
            updated
        }
    }

    suspend fun deleteLabel(labelId: Long) {
        labelDao.deleteById(labelId)
    }

    suspend fun noteIdsWithAnyLabels(labelIds: Set<Long>): Set<Long> {
        if (labelIds.isEmpty()) {
            return emptySet()
        }
        return labelDao.noteIdsWithAnyLabels(labelIds.toList()).toSet()
    }

    suspend fun notesWithLabelsByIds(noteIds: Collection<Long>): Map<Long, NoteWithLabels> {
        if (noteIds.isEmpty()) {
            return emptyMap()
        }
        return noteDao.byIdsWithLabels(noteIds.distinct()).associateBy { it.note.id }
    }

    suspend fun replaceRollup(
        id: String,
        granularity: RollupGranularity,
        periodStartEpochMs: Long,
        periodEndEpochMs: Long,
        sourceCount: Int,
        insight: AggregateInsight,
        noteIds: List<Long>,
    ) {
        rollupDao.replace(
            RollupEntity(
                id = id,
                granularity = granularity.name,
                periodStartEpochMs = periodStartEpochMs,
                periodEndEpochMs = periodEndEpochMs,
                generatedAtEpochMs = System.currentTimeMillis(),
                modelLabel = insight.modelLabel,
                sourceCount = sourceCount,
                title = insight.title,
                overview = insight.overview,
                highlightsJson = json.encodeToString(insight.highlights),
                themesJson = json.encodeToString(insight.themes),
                noteIdsJson = json.encodeToString(noteIds.distinct()),
            ),
        )
    }

    suspend fun deleteRollup(id: String) {
        rollupDao.deleteById(id)
        semanticEntryDao.deleteBySourceId(id)
    }

    suspend fun allRollups(): List<RollupSnapshot> {
        return rollupDao.observeAllOnce().map(::toRollupSnapshot)
    }

    suspend fun rollupsByGranularity(granularity: RollupGranularity): List<RollupSnapshot> {
        return rollupDao.byGranularityAscending(granularity.name).map(::toRollupSnapshot)
    }

    suspend fun replaceSemanticEntry(entry: SemanticEntryEntity) {
        semanticEntryDao.replace(entry)
    }

    suspend fun allSemanticEntries(): List<SemanticEntryEntity> = semanticEntryDao.all()

    suspend fun checkpoint(granularity: RollupGranularity): AggregationCheckpoint {
        return checkpointDao.get(granularity.name)?.let(::toCheckpoint)
            ?: AggregationCheckpoint(
                granularity = granularity,
                lastCompletedEndEpochMs = null,
                dirtyFromEpochMs = null,
                lastRunStartedEpochMs = null,
                lastRunFinishedEpochMs = null,
                lastError = null,
            )
    }

    suspend fun updateCheckpoint(checkpoint: AggregationCheckpoint) {
        checkpointDao.replace(
            AggregationCheckpointEntity(
                granularity = checkpoint.granularity.name,
                lastCompletedEndEpochMs = checkpoint.lastCompletedEndEpochMs,
                dirtyFromEpochMs = checkpoint.dirtyFromEpochMs,
                lastRunStartedEpochMs = checkpoint.lastRunStartedEpochMs,
                lastRunFinishedEpochMs = checkpoint.lastRunFinishedEpochMs,
                lastError = checkpoint.lastError,
            ),
        )
    }

    suspend fun markDirtyFrom(epochMs: Long) {
        database.withTransaction {
            markDirtyFromInternal(epochMs)
        }
    }

    private suspend fun markDirtyFromInternal(epochMs: Long) {
        RollupGranularity.entries.forEach { granularity ->
            val current = checkpoint(granularity)
            val nextDirtyFrom = current.dirtyFromEpochMs?.let { min(it, epochMs) } ?: epochMs
            updateCheckpoint(
                current.copy(
                    dirtyFromEpochMs = nextDirtyFrom,
                    lastError = null,
                ),
            )
        }
    }

    private fun toRollupSnapshot(entity: RollupEntity): RollupSnapshot {
        return RollupSnapshot(
            id = entity.id,
            granularity = RollupGranularity.valueOf(entity.granularity),
            periodStartEpochMs = entity.periodStartEpochMs,
            periodEndEpochMs = entity.periodEndEpochMs,
            generatedAtEpochMs = entity.generatedAtEpochMs,
            modelLabel = entity.modelLabel,
            sourceCount = entity.sourceCount,
            title = entity.title,
            overview = entity.overview,
            highlights = json.decodeFromString<List<String>>(entity.highlightsJson),
            themes = json.decodeFromString<List<ThemeBucket>>(entity.themesJson),
            noteIds = json.decodeFromString<List<Long>>(entity.noteIdsJson),
        )
    }

    private fun toCheckpoint(entity: AggregationCheckpointEntity): AggregationCheckpoint {
        return AggregationCheckpoint(
            granularity = RollupGranularity.valueOf(entity.granularity),
            lastCompletedEndEpochMs = entity.lastCompletedEndEpochMs,
            dirtyFromEpochMs = entity.dirtyFromEpochMs,
            lastRunStartedEpochMs = entity.lastRunStartedEpochMs,
            lastRunFinishedEpochMs = entity.lastRunFinishedEpochMs,
            lastError = entity.lastError,
        )
    }

    private fun floorToDayStart(epochMs: Long): Long {
        return Instant.ofEpochMilli(epochMs)
            .atZone(ZoneId.systemDefault())
            .toLocalDate()
            .atStartOfDay(ZoneId.systemDefault())
            .toInstant()
            .toEpochMilli()
    }

    private fun normalizeLabelDisplay(value: String): String {
        return value.trim().replace("\\s+".toRegex(), " ")
    }

    private fun normalizeLabelKey(value: String): String {
        return normalizeLabelDisplay(value).lowercase(Locale.ROOT)
    }
}
