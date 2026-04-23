package com.voiceledger.lite.data

import androidx.room.ColumnInfo
import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Junction
import androidx.room.PrimaryKey
import androidx.room.Relation
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.format.ResolverStyle

@Entity(tableName = "notes")
data class NoteEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val body: String,
    @ColumnInfo(name = "created_at_epoch_ms") val createdAtEpochMs: Long,
    @ColumnInfo(name = "updated_at_epoch_ms") val updatedAtEpochMs: Long,
)

@Entity(
    tableName = "labels",
    indices = [
        Index(value = ["normalized_name"], unique = true),
    ],
)
data class LabelEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    @ColumnInfo(name = "normalized_name") val normalizedName: String,
    @ColumnInfo(name = "created_at_epoch_ms") val createdAtEpochMs: Long,
)

@Entity(
    tableName = "note_label_cross_refs",
    primaryKeys = ["note_id", "label_id"],
    foreignKeys = [
        ForeignKey(
            entity = NoteEntity::class,
            parentColumns = ["id"],
            childColumns = ["note_id"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = LabelEntity::class,
            parentColumns = ["id"],
            childColumns = ["label_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["note_id"]),
        Index(value = ["label_id"]),
    ],
)
data class NoteLabelCrossRef(
    @ColumnInfo(name = "note_id") val noteId: Long,
    @ColumnInfo(name = "label_id") val labelId: Long,
)

data class NoteWithLabels(
    @Embedded val note: NoteEntity,
    @Relation(
        parentColumn = "id",
        entityColumn = "id",
        associateBy = Junction(
            value = NoteLabelCrossRef::class,
            parentColumn = "note_id",
            entityColumn = "label_id",
        ),
    )
    val labels: List<LabelEntity>,
)

enum class RollupGranularity {
    DAILY,
    WEEKLY,
    MONTHLY,
    YEARLY,
}

@Entity(tableName = "rollups")
data class RollupEntity(
    @PrimaryKey val id: String,
    val granularity: String,
    @ColumnInfo(name = "period_start_epoch_ms") val periodStartEpochMs: Long,
    @ColumnInfo(name = "period_end_epoch_ms") val periodEndEpochMs: Long,
    @ColumnInfo(name = "generated_at_epoch_ms") val generatedAtEpochMs: Long,
    @ColumnInfo(name = "model_label") val modelLabel: String,
    @ColumnInfo(name = "source_count") val sourceCount: Int,
    val title: String,
    val overview: String,
    @ColumnInfo(name = "highlights_json") val highlightsJson: String,
    @ColumnInfo(name = "themes_json") val themesJson: String,
    @ColumnInfo(name = "note_ids_json") val noteIdsJson: String,
)

@Entity(tableName = "semantic_entries")
data class SemanticEntryEntity(
    @PrimaryKey val entryId: String,
    val kind: String,
    @ColumnInfo(name = "source_id") val sourceId: String,
    val title: String,
    val body: String,
    @ColumnInfo(name = "note_id") val noteId: Long?,
    @ColumnInfo(name = "rollup_id") val rollupId: String?,
    val granularity: String?,
    @ColumnInfo(name = "embedding_json") val embeddingJson: String,
    @ColumnInfo(name = "updated_at_epoch_ms") val updatedAtEpochMs: Long,
)

@Entity(tableName = "aggregation_checkpoints")
data class AggregationCheckpointEntity(
    @PrimaryKey val granularity: String,
    @ColumnInfo(name = "last_completed_end_epoch_ms") val lastCompletedEndEpochMs: Long?,
    @ColumnInfo(name = "dirty_from_epoch_ms") val dirtyFromEpochMs: Long?,
    @ColumnInfo(name = "last_run_started_epoch_ms") val lastRunStartedEpochMs: Long?,
    @ColumnInfo(name = "last_run_finished_epoch_ms") val lastRunFinishedEpochMs: Long?,
    @ColumnInfo(name = "last_error") val lastError: String?,
)

data class LocalAiSettings(
    val summaryModelPath: String = "",
    val embeddingModelPath: String = "",
    val summaryStartDate: String = "",
    val backgroundProcessingTime: String = DEFAULT_BACKGROUND_PROCESSING_TIME,
    val maxSourcesPerRollup: Int = 8,
    val embeddingDimensions: Int = 192,
    val searchResultLimit: Int = 8,
    val maxTokens: Int = 2048,
    val topK: Int = 24,
    val temperature: Float = 0.2f,
    val backgroundProcessingEnabled: Boolean = true,
) {
    fun normalized(): LocalAiSettings {
        val normalizedStartDate = summaryStartDate.trim().takeIf {
            runCatching { LocalDate.parse(it) }.isSuccess
        }.orEmpty()
        val normalizedBackgroundTime = normalizeBackgroundProcessingTime(backgroundProcessingTime)
        return copy(
            summaryModelPath = summaryModelPath.trim(),
            embeddingModelPath = embeddingModelPath.trim(),
            summaryStartDate = normalizedStartDate,
            backgroundProcessingTime = normalizedBackgroundTime,
            maxSourcesPerRollup = maxSourcesPerRollup.coerceIn(4, 12),
            embeddingDimensions = embeddingDimensions.coerceIn(32, 768),
            searchResultLimit = searchResultLimit.coerceIn(3, 20),
            maxTokens = maxTokens.coerceIn(512, 4096),
            topK = topK.coerceIn(1, 32),
            temperature = temperature.coerceIn(0f, 1f),
        )
    }
}

data class LocalStats(
    val totalNotes: Int = 0,
    val notesThisWeek: Int = 0,
    val notesThisMonth: Int = 0,
)

const val DEFAULT_BACKGROUND_PROCESSING_TIME = "02:00"

fun isValidBackgroundProcessingTime(value: String): Boolean {
    return runCatching {
        LocalTime.parse(
            value.trim(),
            DateTimeFormatter.ofPattern("HH:mm").withResolverStyle(ResolverStyle.STRICT),
        )
    }.isSuccess
}

fun normalizeBackgroundProcessingTime(value: String): String {
    return value.trim()
        .takeIf(::isValidBackgroundProcessingTime)
        ?: DEFAULT_BACKGROUND_PROCESSING_TIME
}
