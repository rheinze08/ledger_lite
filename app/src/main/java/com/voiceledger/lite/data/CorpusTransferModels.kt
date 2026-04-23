package com.voiceledger.lite.data

import kotlinx.serialization.Serializable

@Serializable
data class LedgerCorpusExport(
    val schemaVersion: Int = 1,
    val exportedAtEpochMs: Long,
    val notes: List<LedgerCorpusNote>,
)

@Serializable
data class LedgerCorpusNote(
    val title: String,
    val body: String,
    val createdAtEpochMs: Long? = null,
    val createdAtDate: String? = null,
    val tags: List<String> = emptyList(),
)

data class LedgerCorpusImportResult(
    val importedNotes: Int,
    val skippedNotes: Int,
    val createdTags: Int,
    val earliestImportedEpochMs: Long?,
)
