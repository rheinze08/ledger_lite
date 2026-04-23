package com.voiceledger.lite.semantic

import android.content.Context
import androidx.room.Room
import com.voiceledger.lite.data.LedgerDatabase

object LedgerDatabaseFactory {
    fun open(context: Context): LedgerDatabase {
        return Room.databaseBuilder(
            context.applicationContext,
            LedgerDatabase::class.java,
            "voice_ledger_lite.db",
        )
            .enableMultiInstanceInvalidation()
            .addMigrations(LedgerDatabase.MIGRATION_2_3)
            .build()
    }
}
