package com.voiceledger.lite.ui

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.voiceledger.lite.data.LabelEntity
import com.voiceledger.lite.data.LedgerRepository
import com.voiceledger.lite.data.LocalAiSettings
import com.voiceledger.lite.data.LocalStats
import com.voiceledger.lite.data.NoteWithLabels
import com.voiceledger.lite.data.RollupGranularity
import com.voiceledger.lite.data.SettingsStore
import com.voiceledger.lite.semantic.AggregationCheckpoint
import com.voiceledger.lite.semantic.AggregationScheduler
import com.voiceledger.lite.semantic.GeneratedAnswer
import com.voiceledger.lite.semantic.LocalAggregationCoordinator
import com.voiceledger.lite.semantic.RollupSnapshot
import com.voiceledger.lite.semantic.SearchRouteStep
import com.voiceledger.lite.semantic.SemanticSearchHit
import java.time.Duration
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class AppTab {
    NOTES,
    COMPOSE,
    INSIGHTS,
    SUMMARIZE,
}

const val MAX_TAGS = 5

data class LedgerUiState(
    val selectedTab: AppTab = AppTab.NOTES,
    val notes: List<NoteWithLabels> = emptyList(),
    val labels: List<LabelEntity> = emptyList(),
    val selectedNoteId: Long? = null,
    val editingNoteId: Long? = null,
    val composeTitle: String = "",
    val composeBody: String = "",
    val composeSelectedLabelIds: Set<Long> = emptySet(),
    val labelDraft: String = "",
    val editingLabelId: Long? = null,
    val settings: LocalAiSettings = LocalAiSettings(),
    val localStats: LocalStats = LocalStats(),
    val latestRollups: List<RollupSnapshot> = emptyList(),
    val checkpoints: List<AggregationCheckpoint> = emptyList(),
    val searchQuery: String = "",
    val searchSelectedLabelIds: Set<Long> = emptySet(),
    val searchRoute: List<SearchRouteStep> = emptyList(),
    val searchResults: List<SemanticSearchHit> = emptyList(),
    val searchAnswer: GeneratedAnswer? = null,
    val searchAnswerNotice: String? = null,
    val isRefreshingInsights: Boolean = false,
    val isSearching: Boolean = false,
    val infoMessage: String? = null,
    val errorMessage: String? = null,
)

class LedgerViewModel(
    private val appContext: Context,
    private val repository: LedgerRepository,
    private val settingsStore: SettingsStore,
    private val coordinator: LocalAggregationCoordinator,
) : ViewModel() {
    private val _uiState = MutableStateFlow(LedgerUiState(settings = settingsStore.load()))
    val uiState: StateFlow<LedgerUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            repository.observeNotes().collect { notes ->
                _uiState.update { state ->
                    val selectedStillExists = notes.any { it.note.id == state.selectedNoteId }
                    state.copy(
                        notes = notes,
                        localStats = calculateStats(notes),
                        selectedNoteId = if (selectedStillExists) state.selectedNoteId else notes.firstOrNull()?.note?.id,
                    )
                }
            }
        }
        viewModelScope.launch {
            repository.observeLabels().collect { labels ->
                val labelIds = labels.map(LabelEntity::id).toSet()
                _uiState.update { state ->
                    val editingStillExists = state.editingLabelId?.takeIf(labelIds::contains)
                    state.copy(
                        labels = labels,
                        composeSelectedLabelIds = state.composeSelectedLabelIds.intersect(labelIds),
                        searchSelectedLabelIds = state.searchSelectedLabelIds.intersect(labelIds),
                        editingLabelId = editingStillExists,
                        labelDraft = if (editingStillExists == null) "" else state.labelDraft,
                    )
                }
            }
        }
        viewModelScope.launch {
            repository.observeRollups().collect { rollups ->
                val latestByGranularity = RollupGranularity.entries.mapNotNull { granularity ->
                    rollups.filter { it.granularity == granularity }.maxByOrNull(RollupSnapshot::periodEndEpochMs)
                }
                _uiState.update { it.copy(latestRollups = latestByGranularity) }
            }
        }
        viewModelScope.launch {
            repository.observeCheckpoints().collect { checkpoints ->
                _uiState.update { it.copy(checkpoints = checkpoints.sortedBy(AggregationCheckpoint::granularity)) }
            }
        }
    }

    fun selectTab(tab: AppTab) {
        _uiState.update { it.copy(selectedTab = tab) }
    }

    fun selectNote(noteId: Long?) {
        _uiState.update { it.copy(selectedNoteId = noteId, selectedTab = AppTab.NOTES) }
    }

    fun updateComposeTitle(value: String) {
        _uiState.update { it.copy(composeTitle = value) }
    }

    fun updateComposeBody(value: String) {
        _uiState.update { it.copy(composeBody = value) }
    }

    fun toggleComposeLabel(labelId: Long) {
        _uiState.update { state ->
            val next = if (labelId in state.composeSelectedLabelIds) {
                state.composeSelectedLabelIds - labelId
            } else {
                state.composeSelectedLabelIds + labelId
            }
            state.copy(composeSelectedLabelIds = next)
        }
    }

    fun loadNoteIntoComposer(note: NoteWithLabels) {
        _uiState.update {
            it.copy(
                selectedTab = AppTab.COMPOSE,
                editingNoteId = note.note.id,
                composeTitle = note.note.title,
                composeBody = note.note.body,
                composeSelectedLabelIds = note.labels.map(LabelEntity::id).toSet(),
                selectedNoteId = note.note.id,
            )
        }
    }

    fun clearComposer() {
        _uiState.update {
            it.copy(
                editingNoteId = null,
                composeTitle = "",
                composeBody = "",
                composeSelectedLabelIds = emptySet(),
            )
        }
    }

    fun saveDraft() {
        val current = _uiState.value
        val body = current.composeBody.trim()
        if (body.isBlank()) {
            _uiState.update { it.copy(errorMessage = "Body is required before you save a note.") }
            return
        }
        val title = current.composeTitle.trim().ifBlank {
            body.lineSequence().firstOrNull()?.take(48) ?: "Untitled note"
        }

        viewModelScope.launch {
            val savedId = repository.saveNote(
                noteId = current.editingNoteId,
                title = title,
                body = body,
                labelIds = current.composeSelectedLabelIds,
            )
            _uiState.update {
                it.copy(
                    selectedTab = AppTab.NOTES,
                    selectedNoteId = savedId,
                    editingNoteId = null,
                    composeTitle = "",
                    composeBody = "",
                    composeSelectedLabelIds = emptySet(),
                    infoMessage = if (current.editingNoteId == null) {
                        "Note saved locally. Aggregation is now dirty from this note onward."
                    } else {
                        "Note updated. Dependent rollups will be rebuilt locally."
                    },
                )
            }
        }
    }

    fun deleteNote(noteId: Long) {
        viewModelScope.launch {
            repository.deleteNote(noteId)
            _uiState.update {
                it.copy(
                    selectedNoteId = it.notes.firstOrNull { note -> note.note.id != noteId }?.note?.id,
                    infoMessage = "Note deleted. Dependent rollups were marked dirty.",
                )
            }
        }
    }

    fun updateLabelDraft(value: String) {
        _uiState.update { it.copy(labelDraft = value) }
    }

    fun editLabel(labelId: Long) {
        val label = _uiState.value.labels.firstOrNull { it.id == labelId } ?: return
        _uiState.update {
            it.copy(
                editingLabelId = label.id,
                labelDraft = label.name,
                selectedTab = AppTab.SUMMARIZE,
            )
        }
    }

    fun clearLabelEditor() {
        _uiState.update { it.copy(editingLabelId = null, labelDraft = "") }
    }

    fun saveLabel() {
        val current = _uiState.value
        val labelDraft = current.labelDraft.trim()
        if (labelDraft.isBlank()) {
            _uiState.update { it.copy(errorMessage = "Tag name cannot be empty.") }
            return
        }
        if (current.editingLabelId == null && current.labels.size >= MAX_TAGS) {
            _uiState.update { it.copy(errorMessage = "You can save up to $MAX_TAGS tags.") }
            return
        }

        viewModelScope.launch {
            runCatching {
                repository.saveLabel(current.editingLabelId, labelDraft)
            }.onSuccess { saved ->
                _uiState.update {
                    it.copy(
                        editingLabelId = null,
                        labelDraft = "",
                        infoMessage = if (current.editingLabelId == null) {
                            "\"${saved.name}\" added."
                        } else {
                            "\"${saved.name}\" updated."
                        },
                    )
                }
            }.onFailure { exception ->
                _uiState.update {
                    it.copy(errorMessage = exception.message ?: "Tag save failed.")
                }
            }
        }
    }

    fun deleteEditingLabel() {
        val labelId = _uiState.value.editingLabelId ?: return
        val labelName = _uiState.value.labels.firstOrNull { it.id == labelId }?.name ?: "Tag"
        viewModelScope.launch {
            repository.deleteLabel(labelId)
            _uiState.update {
                it.copy(
                    editingLabelId = null,
                    labelDraft = "",
                    infoMessage = "\"$labelName\" deleted.",
                )
            }
        }
    }

    fun updateBackgroundProcessing(enabled: Boolean) {
        _uiState.update { it.copy(settings = it.settings.copy(backgroundProcessingEnabled = enabled)) }
    }

    fun saveSettings() {
        val normalized = _uiState.value.settings.normalized()
        settingsStore.save(normalized)
        if (normalized.backgroundProcessingEnabled) {
            AggregationScheduler.schedulePeriodic(appContext)
        } else {
            AggregationScheduler.cancelPeriodic(appContext)
        }
        _uiState.update {
            it.copy(
                settings = normalized,
                infoMessage = "Summarize settings saved.",
            )
        }
    }

    fun updateSearchQuery(value: String) {
        _uiState.update { it.copy(searchQuery = value) }
    }

    fun toggleSearchLabel(labelId: Long) {
        _uiState.update { state ->
            val next = if (labelId in state.searchSelectedLabelIds) {
                state.searchSelectedLabelIds - labelId
            } else {
                state.searchSelectedLabelIds + labelId
            }
            state.copy(searchSelectedLabelIds = next)
        }
    }

    fun runSearch() {
        val current = _uiState.value
        val query = current.searchQuery.trim()
        if (query.isBlank()) {
            _uiState.update {
                it.copy(
                    searchRoute = emptyList(),
                    searchResults = emptyList(),
                    searchAnswer = null,
                    searchAnswerNotice = null,
                )
            }
            return
        }
        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    isSearching = true,
                    errorMessage = null,
                    searchAnswer = null,
                    searchAnswerNotice = null,
                )
            }
            try {
                val response = coordinator.search(query, current.searchSelectedLabelIds)
                _uiState.update {
                    it.copy(
                        isSearching = false,
                        searchRoute = response.route,
                        searchResults = response.hits,
                        searchAnswer = response.answer,
                        searchAnswerNotice = response.answerNotice,
                        infoMessage = if (response.hits.isEmpty()) "No semantic matches found for that query." else null,
                    )
                }
            } catch (exception: Exception) {
                _uiState.update {
                    it.copy(
                        isSearching = false,
                        searchAnswer = null,
                        searchAnswerNotice = null,
                        errorMessage = exception.message ?: "Search failed.",
                    )
                }
            }
        }
    }

    fun openSearchHit(hit: SemanticSearchHit) {
        hit.noteId?.let(::selectNote)
    }

    fun refreshInsights(rebuildFromStartDate: Boolean = false) {
        if (_uiState.value.isRefreshingInsights) {
            return
        }
        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    isRefreshingInsights = true,
                    errorMessage = null,
                )
            }
            try {
                val message = coordinator.runAggregation(rebuildFromStartDate)
                _uiState.update {
                    it.copy(
                        isRefreshingInsights = false,
                        infoMessage = message,
                    )
                }
            } catch (exception: Exception) {
                _uiState.update {
                    it.copy(
                        isRefreshingInsights = false,
                        errorMessage = exception.message ?: "Local aggregation failed.",
                    )
                }
            }
        }
    }

    fun clearInfoMessage() {
        _uiState.update { it.copy(infoMessage = null) }
    }

    fun clearErrorMessage() {
        _uiState.update { it.copy(errorMessage = null) }
    }

    private fun calculateStats(notes: List<NoteWithLabels>): LocalStats {
        val now = System.currentTimeMillis()
        val weekAgo = now - Duration.ofDays(7).toMillis()
        val monthAgo = now - Duration.ofDays(30).toMillis()
        return LocalStats(
            totalNotes = notes.size,
            notesThisWeek = notes.count { it.note.createdAtEpochMs >= weekAgo },
            notesThisMonth = notes.count { it.note.createdAtEpochMs >= monthAgo },
        )
    }
}
