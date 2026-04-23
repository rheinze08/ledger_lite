package com.voiceledger.lite.data

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        NoteEntity::class,
        LabelEntity::class,
        NoteLabelCrossRef::class,
        RollupEntity::class,
        SemanticEntryEntity::class,
        AggregationCheckpointEntity::class,
    ],
    version = 3,
    exportSchema = false,
)
abstract class LedgerDatabase : RoomDatabase() {
    abstract fun noteDao(): NoteDao
    abstract fun labelDao(): LabelDao
    abstract fun rollupDao(): RollupDao
    abstract fun semanticEntryDao(): SemanticEntryDao
    abstract fun aggregationCheckpointDao(): AggregationCheckpointDao

    companion object {
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `labels` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `name` TEXT NOT NULL,
                        `normalized_name` TEXT NOT NULL,
                        `created_at_epoch_ms` INTEGER NOT NULL
                    )
                    """.trimIndent(),
                )
                database.execSQL(
                    """
                    CREATE UNIQUE INDEX IF NOT EXISTS `index_labels_normalized_name`
                    ON `labels` (`normalized_name`)
                    """.trimIndent(),
                )
                database.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `note_label_cross_refs` (
                        `note_id` INTEGER NOT NULL,
                        `label_id` INTEGER NOT NULL,
                        PRIMARY KEY(`note_id`, `label_id`),
                        FOREIGN KEY(`note_id`) REFERENCES `notes`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE,
                        FOREIGN KEY(`label_id`) REFERENCES `labels`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent(),
                )
                database.execSQL(
                    """
                    CREATE INDEX IF NOT EXISTS `index_note_label_cross_refs_note_id`
                    ON `note_label_cross_refs` (`note_id`)
                    """.trimIndent(),
                )
                database.execSQL(
                    """
                    CREATE INDEX IF NOT EXISTS `index_note_label_cross_refs_label_id`
                    ON `note_label_cross_refs` (`label_id`)
                    """.trimIndent(),
                )
            }
        }
    }
}
