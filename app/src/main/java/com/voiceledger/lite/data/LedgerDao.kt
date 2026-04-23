package com.voiceledger.lite.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface NoteDao {
    @Transaction
    @Query("SELECT * FROM notes ORDER BY created_at_epoch_ms DESC")
    fun observeAllWithLabels(): Flow<List<NoteWithLabels>>

    @Query("SELECT * FROM notes ORDER BY created_at_epoch_ms DESC")
    fun observeAll(): Flow<List<NoteEntity>>

    @Query("SELECT * FROM notes ORDER BY created_at_epoch_ms ASC")
    suspend fun allAscending(): List<NoteEntity>

    @Transaction
    @Query("SELECT * FROM notes WHERE id = :noteId LIMIT 1")
    suspend fun getWithLabelsById(noteId: Long): NoteWithLabels?

    @Query("SELECT * FROM notes WHERE id = :noteId LIMIT 1")
    suspend fun getById(noteId: Long): NoteEntity?

    @Transaction
    @Query("SELECT * FROM notes WHERE id IN (:noteIds)")
    suspend fun byIdsWithLabels(noteIds: List<Long>): List<NoteWithLabels>

    @Query(
        """
        SELECT * FROM notes
        WHERE created_at_epoch_ms >= :sinceEpochMs
        ORDER BY created_at_epoch_ms DESC
        LIMIT :limit
        """,
    )
    suspend fun recentSince(sinceEpochMs: Long, limit: Int): List<NoteEntity>

    @Insert
    suspend fun insert(note: NoteEntity): Long

    @Update
    suspend fun update(note: NoteEntity)

    @Query("DELETE FROM notes WHERE id = :noteId")
    suspend fun deleteById(noteId: Long)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertLabelRefs(refs: List<NoteLabelCrossRef>)

    @Query("DELETE FROM note_label_cross_refs WHERE note_id = :noteId")
    suspend fun deleteLabelRefsForNote(noteId: Long)
}

@Dao
interface LabelDao {
    @Query("SELECT * FROM labels ORDER BY name COLLATE NOCASE ASC")
    fun observeAll(): Flow<List<LabelEntity>>

    @Query("SELECT * FROM labels ORDER BY name COLLATE NOCASE ASC")
    suspend fun all(): List<LabelEntity>

    @Query("SELECT * FROM labels WHERE normalized_name = :normalizedName LIMIT 1")
    suspend fun getByNormalizedName(normalizedName: String): LabelEntity?

    @Query("SELECT * FROM labels WHERE id = :labelId LIMIT 1")
    suspend fun getById(labelId: Long): LabelEntity?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(label: LabelEntity): Long

    @Update
    suspend fun update(label: LabelEntity)

    @Query("DELETE FROM labels WHERE id = :labelId")
    suspend fun deleteById(labelId: Long)

    @Query(
        """
        SELECT DISTINCT note_id FROM note_label_cross_refs
        WHERE label_id IN (:labelIds)
        """,
    )
    suspend fun noteIdsWithAnyLabels(labelIds: List<Long>): List<Long>
}

@Dao
interface RollupDao {
    @Query("SELECT * FROM rollups ORDER BY period_end_epoch_ms DESC")
    fun observeAll(): Flow<List<RollupEntity>>

    @Query("SELECT * FROM rollups ORDER BY period_end_epoch_ms DESC")
    suspend fun observeAllOnce(): List<RollupEntity>

    @Query(
        """
        SELECT * FROM rollups
        WHERE granularity = :granularity
        ORDER BY period_end_epoch_ms DESC
        LIMIT 1
        """,
    )
    fun observeLatest(granularity: String): Flow<RollupEntity?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun replace(rollup: RollupEntity)

    @Query("SELECT * FROM rollups WHERE granularity = :granularity ORDER BY period_start_epoch_ms ASC")
    suspend fun byGranularityAscending(granularity: String): List<RollupEntity>

    @Query("DELETE FROM rollups WHERE id = :rollupId")
    suspend fun deleteById(rollupId: String)
}

@Dao
interface SemanticEntryDao {
    @Query("SELECT * FROM semantic_entries")
    suspend fun all(): List<SemanticEntryEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun replace(entry: SemanticEntryEntity)

    @Query("DELETE FROM semantic_entries WHERE source_id = :sourceId")
    suspend fun deleteBySourceId(sourceId: String)

    @Query("DELETE FROM semantic_entries WHERE entryId = :entryId")
    suspend fun deleteById(entryId: String)
}

@Dao
interface AggregationCheckpointDao {
    @Query("SELECT * FROM aggregation_checkpoints ORDER BY granularity ASC")
    fun observeAll(): Flow<List<AggregationCheckpointEntity>>

    @Query("SELECT * FROM aggregation_checkpoints WHERE granularity = :granularity LIMIT 1")
    suspend fun get(granularity: String): AggregationCheckpointEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun replace(checkpoint: AggregationCheckpointEntity)
}
