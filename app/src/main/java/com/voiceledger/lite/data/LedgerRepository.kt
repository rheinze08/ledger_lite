package com.voiceledger.lite.data

import androidx.room.withTransaction
import com.voiceledger.lite.semantic.AggregateInsight
import com.voiceledger.lite.semantic.AggregationCheckpoint
import com.voiceledger.lite.semantic.RollupSnapshot
import com.voiceledger.lite.semantic.ThemeBucket
import java.time.Instant
import java.time.LocalDate
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

    suspend fun exportBaseDocumentsJson(): String {
        val notes = noteDao.allWithLabelsAscending().map { noteWithLabels ->
            LedgerCorpusNote(
                title = noteWithLabels.note.title,
                body = noteWithLabels.note.body,
                createdAtEpochMs = noteWithLabels.note.createdAtEpochMs,
                createdAtDate = Instant.ofEpochMilli(noteWithLabels.note.createdAtEpochMs)
                    .atZone(ZoneId.systemDefault())
                    .toLocalDate()
                    .toString(),
                tags = noteWithLabels.labels.map(LabelEntity::name).sorted(),
            )
        }
        return json.encodeToString(
            LedgerCorpusExport(
                exportedAtEpochMs = System.currentTimeMillis(),
                notes = notes,
            ),
        )
    }

    suspend fun importBaseDocumentsJson(
        rawJson: String,
        maxTags: Int,
    ): LedgerCorpusImportResult {
        val payload = runCatching {
            json.decodeFromString<LedgerCorpusExport>(rawJson)
        }.getOrElse { exception ->
            error("Import file could not be read. ${exception.message ?: "Invalid JSON."}")
        }
        require(payload.notes.isNotEmpty()) { "Import file does not contain any notes." }

        val existingLabels = labelDao.all()
        val existingLabelKeys = existingLabels.map(LabelEntity::normalizedName).toMutableSet()
        val importedTagNamesByKey = linkedMapOf<String, String>()
        payload.notes.forEach { note ->
            note.tags.forEach { rawTag ->
                val displayName = normalizeLabelDisplay(rawTag)
                if (displayName.isNotBlank()) {
                    importedTagNamesByKey.putIfAbsent(normalizeLabelKey(displayName), displayName)
                }
            }
        }
        val missingTagNames = importedTagNamesByKey
            .filterKeys { it !in existingLabelKeys }
            .values
            .sortedBy { it.lowercase(Locale.ROOT) }
        if (existingLabels.size + missingTagNames.size > maxTags) {
            error(
                buildString {
                    append("Import would exceed the $maxTags tag limit. ")
                    append("Missing tags: ")
                    append(missingTagNames.joinToString(", "))
                    append(".")
                },
            )
        }

        val normalizedNotes = payload.notes.map(::normalizeImportedNote)
        val existingSignatures = noteDao.allWithLabelsAscending()
            .mapTo(mutableSetOf(), ::noteSignature)
        var createdTags = 0
        var importedNotes = 0
        var skippedNotes = 0
        var earliestImportedEpochMs: Long? = null

        database.withTransaction {
            val labelIdsByKey = labelDao.all().associateBy(LabelEntity::normalizedName).toMutableMap()
            importedTagNamesByKey.forEach { (normalizedName, displayName) ->
                if (normalizedName !in labelIdsByKey) {
                    val labelId = labelDao.insert(
                        LabelEntity(
                            name = displayName,
                            normalizedName = normalizedName,
                            createdAtEpochMs = System.currentTimeMillis(),
                        ),
                    )
                    val created = labelDao.getById(labelId) ?: error("Tag import failed.")
                    labelIdsByKey[normalizedName] = created
                    createdTags += 1
                }
            }

            normalizedNotes.forEach { note ->
                val signature = importedNoteSignature(note)
                if (!existingSignatures.add(signature)) {
                    skippedNotes += 1
                    return@forEach
                }
                val savedId = noteDao.insert(
                    NoteEntity(
                        title = note.title,
                        body = note.body,
                        createdAtEpochMs = note.createdAtEpochMs,
                        updatedAtEpochMs = System.currentTimeMillis(),
                    ),
                )
                val labelRefs = note.tagKeys.mapNotNull { normalizedName ->
                    labelIdsByKey[normalizedName]?.id?.let { labelId ->
                        NoteLabelCrossRef(noteId = savedId, labelId = labelId)
                    }
                }
                if (labelRefs.isNotEmpty()) {
                    noteDao.insertLabelRefs(labelRefs)
                }
                importedNotes += 1
                earliestImportedEpochMs = earliestImportedEpochMs
                    ?.let { min(it, note.createdAtEpochMs) }
                    ?: note.createdAtEpochMs
            }

            earliestImportedEpochMs?.let { dirtyFrom ->
                markDirtyFromInternal(floorToDayStart(dirtyFrom))
            }
        }

        return LedgerCorpusImportResult(
            importedNotes = importedNotes,
            skippedNotes = skippedNotes,
            createdTags = createdTags,
            earliestImportedEpochMs = earliestImportedEpochMs,
        )
    }

    suspend fun getNote(noteId: Long): NoteEntity? = noteDao.getById(noteId)

    suspend fun getNoteWithLabels(noteId: Long): NoteWithLabels? = noteDao.getWithLabelsById(noteId)

    suspend fun allLabels(): List<LabelEntity> = labelDao.all()

    suspend fun saveNote(
        noteId: Long?,
        title: String,
        body: String,
        labelIds: Set<Long>,
        createdAtEpochMs: Long,
    ): Long {
        val now = System.currentTimeMillis()
        val cleanedTitle = title.trim()
        val cleanedBody = body.trim()
        val distinctLabelIds = labelIds.distinct()
        var dirtyEpochMs = createdAtEpochMs
        var savedId = noteId ?: 0

        database.withTransaction {
            savedId = if (noteId == null) {
                noteDao.insert(
                    NoteEntity(
                        title = cleanedTitle,
                        body = cleanedBody,
                        createdAtEpochMs = createdAtEpochMs,
                        updatedAtEpochMs = now,
                    ),
                )
            } else {
                val existing = noteDao.getById(noteId)
                dirtyEpochMs = existing?.createdAtEpochMs?.let { min(it, createdAtEpochMs) } ?: createdAtEpochMs
                val note = if (existing == null) {
                    NoteEntity(
                        id = noteId,
                        title = cleanedTitle,
                        body = cleanedBody,
                        createdAtEpochMs = createdAtEpochMs,
                        updatedAtEpochMs = now,
                    )
                } else {
                    existing.copy(
                        title = cleanedTitle,
                        body = cleanedBody,
                        createdAtEpochMs = createdAtEpochMs,
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

    suspend fun getRollup(rollupId: String): RollupSnapshot? {
        return rollupDao.getById(rollupId)?.let(::toRollupSnapshot)
    }

    suspend fun rollupsByGranularity(granularity: RollupGranularity): List<RollupSnapshot> {
        return rollupDao.byGranularityAscending(granularity.name).map(::toRollupSnapshot)
    }

    suspend fun updateRollupContent(
        rollupId: String,
        title: String,
        overview: String,
    ): RollupSnapshot {
        val cleanedTitle = title.trim()
        val cleanedOverview = overview.trim()
        return database.withTransaction {
            val existing = rollupDao.getById(rollupId)
                ?: error("That generated summary no longer exists.")
            val updated = existing.copy(
                title = cleanedTitle,
                overview = cleanedOverview,
            )
            rollupDao.replace(updated)
            toRollupSnapshot(updated)
        }
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

    private fun normalizeImportedNote(note: LedgerCorpusNote): ImportedNote {
        val title = note.title.trim().ifBlank {
            note.body.lineSequence().firstOrNull()?.trim()?.take(48) ?: "Untitled note"
        }
        val body = note.body.trim()
        require(body.isNotBlank()) { "Imported notes must have a body." }
        val createdAtEpochMs = note.createdAtEpochMs
            ?: note.createdAtDate
                ?.trim()
                ?.takeIf(String::isNotBlank)
                ?.let { dateString ->
                    runCatching {
                        LocalDate.parse(dateString)
                            .atStartOfDay(ZoneId.systemDefault())
                            .toInstant()
                            .toEpochMilli()
                    }.getOrElse {
                        error("Import note date must use YYYY-MM-DD.")
                    }
                }
            ?: error("Imported notes must include a createdAt date.")
        val tagKeys = note.tags.mapNotNull { rawTag ->
            normalizeLabelDisplay(rawTag).takeIf(String::isNotBlank)?.let(::normalizeLabelKey)
        }.distinct().sorted()
        return ImportedNote(
            title = title,
            body = body,
            createdAtEpochMs = createdAtEpochMs,
            tagKeys = tagKeys,
        )
    }

    private fun noteSignature(note: NoteWithLabels): String {
        return signature(
            title = note.note.title,
            body = note.note.body,
            createdAtEpochMs = note.note.createdAtEpochMs,
            tagKeys = note.labels.map(LabelEntity::normalizedName).sorted(),
        )
    }

    private fun importedNoteSignature(note: ImportedNote): String {
        return signature(
            title = note.title,
            body = note.body,
            createdAtEpochMs = note.createdAtEpochMs,
            tagKeys = note.tagKeys,
        )
    }

    private fun signature(
        title: String,
        body: String,
        createdAtEpochMs: Long,
        tagKeys: List<String>,
    ): String {
        return buildString {
            append(title.trim())
            append('\u001F')
            append(body.trim())
            append('\u001F')
            append(createdAtEpochMs)
            append('\u001F')
            append(tagKeys.joinToString("|"))
        }
    }

    private data class ImportedNote(
        val title: String,
        val body: String,
        val createdAtEpochMs: Long,
        val tagKeys: List<String>,
    )
}
