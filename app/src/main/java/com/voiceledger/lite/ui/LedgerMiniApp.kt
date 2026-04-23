package com.voiceledger.lite.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ListAlt
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.EditNote
import androidx.compose.material.icons.filled.Insights
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.voiceledger.lite.data.LabelEntity
import com.voiceledger.lite.data.LocalStats
import com.voiceledger.lite.data.NoteWithLabels
import com.voiceledger.lite.data.RollupGranularity
import com.voiceledger.lite.semantic.AggregationCheckpoint
import com.voiceledger.lite.semantic.GeneratedAnswer
import com.voiceledger.lite.semantic.LocalModelArtifactStatus
import com.voiceledger.lite.semantic.LocalModelInstallState
import com.voiceledger.lite.semantic.RollupSnapshot
import com.voiceledger.lite.semantic.SearchRouteStep
import com.voiceledger.lite.semantic.SemanticSearchHit
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LedgerMiniApp(
    viewModel: LedgerViewModel,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val exportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/json"),
    ) { uri ->
        uri?.let(viewModel::exportCorpus)
    }
    val importLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri ->
        uri?.let(viewModel::importCorpus)
    }

    state.infoMessage?.let { message ->
        LaunchedEffect(message) {
            snackbarHostState.showSnackbar(message)
            viewModel.clearInfoMessage()
        }
    }
    state.errorMessage?.let { message ->
        LaunchedEffect(message) {
            snackbarHostState.showSnackbar(message)
            viewModel.clearErrorMessage()
        }
    }

    if (!state.isInitialSetupComplete) {
        InitialSetupScreen(
            state = state,
            onRetry = viewModel::retryModelProvisioning,
        )
        return
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Ledger Lite")
                        Text(
                            text = "Phone-local notes, tags, rollups, and search",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
            )
        },
        bottomBar = {
            NavigationBar {
                AppTab.entries.forEach { tab ->
                    NavigationBarItem(
                        selected = state.selectedTab == tab,
                        onClick = { viewModel.selectTab(tab) },
                        icon = {
                            Icon(
                                imageVector = when (tab) {
                                    AppTab.NOTES -> Icons.AutoMirrored.Filled.ListAlt
                                    AppTab.COMPOSE -> Icons.Filled.EditNote
                                    AppTab.INSIGHTS -> Icons.Filled.Insights
                                    AppTab.SUMMARIZE -> Icons.Filled.AutoAwesome
                                },
                                contentDescription = tab.label,
                            )
                        },
                        label = { Text(tab.label) },
                    )
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { innerPadding ->
        when (state.selectedTab) {
            AppTab.NOTES -> NotesScreen(
                state = state,
                paddingValues = innerPadding,
                onSelectCreatedNote = viewModel::selectCreatedNote,
                onSelectCreatedLayer = viewModel::selectCreatedDocumentLayer,
                onSelectGeneratedLayer = viewModel::selectGeneratedDocumentLayer,
                onSelectGeneratedGranularity = viewModel::selectGeneratedGranularity,
                onSelectRollup = viewModel::selectRollup,
                onEditRollup = viewModel::loadRollupIntoComposer,
                onShowRollupSourceNotes = viewModel::showRollupSourceNotes,
                onClearCreatedSourceFilter = viewModel::clearCreatedSourceFilter,
                onEdit = viewModel::loadNoteIntoComposer,
                onDelete = viewModel::deleteNote,
            )
            AppTab.COMPOSE -> ComposeScreen(
                state = state,
                paddingValues = innerPadding,
                onTitleChange = viewModel::updateComposeTitle,
                onBodyChange = viewModel::updateComposeBody,
                onDateChange = viewModel::updateComposeDate,
                onToggleLabel = viewModel::toggleComposeLabel,
                onLabelDraftChange = viewModel::updateLabelDraft,
                onEditLabel = viewModel::editLabel,
                onClearLabelEditor = viewModel::clearLabelEditor,
                onSaveLabel = viewModel::saveLabel,
                onDeleteLabel = viewModel::deleteEditingLabel,
                onSave = viewModel::saveDraft,
                onClear = viewModel::clearComposer,
            )
            AppTab.INSIGHTS -> AskScreen(
                state = state,
                paddingValues = innerPadding,
                onSearchChange = viewModel::updateSearchQuery,
                onToggleSearchLabel = viewModel::toggleSearchLabel,
                onSearch = viewModel::runSearch,
                onOpenSearchHit = viewModel::openSearchHit,
                onBroadScanDateFromChange = viewModel::updateBroadScanDateFrom,
                onBroadScanDateToChange = viewModel::updateBroadScanDateTo,
                onConfirmBroadScan = viewModel::confirmBroadScan,
                onCancelBroadScan = viewModel::cancelBroadScan,
            )
            AppTab.SUMMARIZE -> SummarizeScreen(
                state = state,
                paddingValues = innerPadding,
                onBackgroundProcessingChange = viewModel::updateBackgroundProcessing,
                onBackgroundProcessingTimeChange = viewModel::updateBackgroundProcessingTime,
                onRefresh = viewModel::refreshInsights,
                onRebuildAll = viewModel::rebuildAllHistory,
                onRebuildFromDate = viewModel::rebuildFromDate,
                onRetryModelProvisioning = viewModel::retryModelProvisioning,
                onExportCorpus = {
                    exportLauncher.launch("ledger-lite-export-${LocalDate.now()}.json")
                },
                onImportCorpus = {
                    importLauncher.launch(arrayOf("*/*"))
                },
                onSave = viewModel::saveSettings,
            )
        }
    }
}

@Composable
private fun InitialSetupScreen(
    state: LedgerUiState,
    onRetry: () -> Unit,
) {
    Surface(modifier = Modifier.fillMaxSize()) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            contentAlignment = Alignment.Center,
        ) {
            ElevatedCard(
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(
                    modifier = Modifier.padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    Text("Setting Up Ledger Lite", style = MaterialTheme.typography.headlineMedium)
                    Text(
                        "The app is installing its local AI models. The Gemma 4 summary model is a large first-run download, so this works best on Wi-Fi with several gigabytes of free space. Once the download starts, Android keeps it running through a foreground notification.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    state.modelProvisioning.progressFraction?.let { progress ->
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(
                                "Overall setup ${formatProgressLine(progress, state.modelProvisioning.downloadedBytes, state.modelProvisioning.totalBytes)}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            LinearProgressIndicator(
                                progress = { progress },
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }
                    ModelStatusRow(state.modelProvisioning.summary)
                    ModelStatusRow(state.modelProvisioning.embedding)
                    if (state.isProvisioningModels) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                strokeWidth = 2.dp,
                            )
                            Text(
                                "Downloading and installing models. You can turn the screen off and let Android continue the transfer.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    } else {
                        Row {
                            Button(onClick = onRetry) {
                                Text("Retry setup")
                            }
                        }
                    }
                }
            }
        }
    }
}

private val AppTab.label: String
    get() = when (this) {
        AppTab.NOTES -> "Notes"
        AppTab.COMPOSE -> "Compose"
        AppTab.INSIGHTS -> "Ask"
        AppTab.SUMMARIZE -> "Summarize"
    }

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun NotesScreen(
    state: LedgerUiState,
    paddingValues: PaddingValues,
    onSelectCreatedNote: (Long?) -> Unit,
    onSelectCreatedLayer: () -> Unit,
    onSelectGeneratedLayer: () -> Unit,
    onSelectGeneratedGranularity: (RollupGranularity) -> Unit,
    onSelectRollup: (String?) -> Unit,
    onEditRollup: (RollupSnapshot) -> Unit,
    onShowRollupSourceNotes: (String) -> Unit,
    onClearCreatedSourceFilter: () -> Unit,
    onEdit: (NoteWithLabels) -> Unit,
    onDelete: (Long) -> Unit,
) {
    val createdNotes = state.createdNotesSourceFilterNoteIds?.let { sourceIds ->
        state.notes.filter { it.note.id in sourceIds }
    } ?: state.notes
    val selectedNote = createdNotes.firstOrNull { it.note.id == state.selectedNoteId }
    val recordNotes = if (selectedNote == null) {
        createdNotes
    } else {
        createdNotes.filterNot { it.note.id == selectedNote.note.id }
    }
    val generatedRollups = state.rollups.filter { it.granularity == state.generatedGranularity }
    val selectedRollup = generatedRollups.firstOrNull { it.id == state.selectedRollupId }
        ?: generatedRollups.firstOrNull()
    val recordRollups = if (selectedRollup == null) {
        generatedRollups
    } else {
        generatedRollups.filterNot { it.id == selectedRollup.id }
    }
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(paddingValues),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            StatsRow(state.localStats)
        }
        item {
            NotesDocumentLayerSwitch(
                selectedLayer = state.notesDocumentLayer,
                onSelectCreated = onSelectCreatedLayer,
                onSelectGenerated = onSelectGeneratedLayer,
            )
        }
        if (state.notesDocumentLayer == NotesDocumentLayer.CREATED && state.createdNotesSourceFilterNoteIds != null) {
            item {
                SourceNotesFilterCard(
                    sourceCount = state.createdNotesSourceFilterNoteIds.size,
                    onClear = onClearCreatedSourceFilter,
                )
            }
        }
        if (state.notesDocumentLayer == NotesDocumentLayer.GENERATED) {
            item {
                GeneratedGranularitySwitch(
                    selectedGranularity = state.generatedGranularity,
                    onSelect = onSelectGeneratedGranularity,
                )
            }
        }
        if (state.notesDocumentLayer == NotesDocumentLayer.CREATED) {
            if (selectedNote != null) {
                item {
                    SectionHeader("Active")
                }
                item {
                    CreatedNoteActiveCard(
                        note = selectedNote,
                        onEdit = onEdit,
                        onDelete = onDelete,
                    )
                }
            }
            if (createdNotes.isEmpty()) {
                item {
                    EmptyNotesState(
                        if (state.createdNotesSourceFilterNoteIds == null) {
                            "No notes yet."
                        } else {
                            "No source notes are available for that summary."
                        },
                    )
                }
            } else if (recordNotes.isNotEmpty()) {
                item {
                    SectionHeader("Records")
                }
            }
            items(recordNotes, key = { it.note.id }) { note ->
                CreatedNoteRecordCard(
                    note = note,
                    isSelected = state.selectedNoteId == note.note.id,
                    onSelect = onSelectCreatedNote,
                )
            }
        } else {
            if (selectedRollup != null) {
                item {
                    SectionHeader("Active")
                }
                item {
                    GeneratedRollupActiveCard(
                        rollup = selectedRollup,
                        onEdit = onEditRollup,
                        onShowSourceNotes = onShowRollupSourceNotes,
                    )
                }
            }
            if (generatedRollups.isEmpty()) {
                item {
                    EmptyNotesState(
                        "No ${state.generatedGranularity.notesTabLabel().lowercase()} summaries yet. Run Update or Rebuild in Summarize first.",
                    )
                }
            } else if (recordRollups.isNotEmpty()) {
                item {
                    SectionHeader("Records")
                }
            }
            items(recordRollups, key = RollupSnapshot::id) { rollup ->
                GeneratedRollupRecordCard(
                    rollup = rollup,
                    isSelected = state.selectedRollupId == rollup.id,
                    onSelect = onSelectRollup,
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun NotesDocumentLayerSwitch(
    selectedLayer: NotesDocumentLayer,
    onSelectCreated: () -> Unit,
    onSelectGenerated: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            "Browse your authored notes or generated summaries.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = selectedLayer == NotesDocumentLayer.CREATED,
                onClick = onSelectCreated,
                label = { Text("Created") },
            )
            FilterChip(
                selected = selectedLayer == NotesDocumentLayer.GENERATED,
                onClick = onSelectGenerated,
                label = { Text("Generated") },
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun GeneratedGranularitySwitch(
    selectedGranularity: RollupGranularity,
    onSelect: (RollupGranularity) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            "Generated layers",
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            RollupGranularity.entries.forEach { granularity ->
                FilterChip(
                    selected = selectedGranularity == granularity,
                    onClick = { onSelect(granularity) },
                    label = { Text(granularity.notesTabLabel()) },
                )
            }
        }
    }
}

@Composable
private fun SourceNotesFilterCard(
    sourceCount: Int,
    onClear: () -> Unit,
) {
    ElevatedCard {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text("Source notes", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Showing the $sourceCount authored note(s) that fed the selected generated summary.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            TextButton(onClick = onClear) {
                Text("Show all")
            }
        }
    }
}

@Composable
private fun CreatedNoteActiveCard(
    note: NoteWithLabels,
    onEdit: (NoteWithLabels) -> Unit,
    onDelete: (Long) -> Unit,
) {
    ElevatedCard(
        colors = CardDefaults.elevatedCardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(note.note.title, style = MaterialTheme.typography.titleLarge)
            Text(
                formatTimestamp(note.note.createdAtEpochMs),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (note.labels.isNotEmpty()) {
                LabelStrip(labels = note.labels)
            }
            Text(note.note.body, style = MaterialTheme.typography.bodyMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = { onEdit(note) }) {
                    Text("Edit")
                }
                OutlinedButton(onClick = { onDelete(note.note.id) }) {
                    Text("Delete")
                }
            }
        }
    }
}

@Composable
private fun CreatedNoteRecordCard(
    note: NoteWithLabels,
    isSelected: Boolean,
    onSelect: (Long?) -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onSelect(note.note.id) },
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected) {
                MaterialTheme.colorScheme.secondaryContainer
            } else {
                MaterialTheme.colorScheme.surface
            },
        ),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(note.note.title, style = MaterialTheme.typography.titleMedium)
            Text(
                formatTimestamp(note.note.createdAtEpochMs),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (note.labels.isNotEmpty()) {
                LabelStrip(labels = note.labels)
            }
            Text(
                text = note.note.body,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun GeneratedRollupActiveCard(
    rollup: RollupSnapshot,
    onEdit: (RollupSnapshot) -> Unit,
    onShowSourceNotes: (String) -> Unit,
) {
    ElevatedCard(
        colors = CardDefaults.elevatedCardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(rollup.title, style = MaterialTheme.typography.titleLarge)
            Text(
                "${rollup.granularity.notesTabLabel()} summary | ${formatRollupPeriod(rollup)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                "Generated ${formatTimestamp(rollup.generatedAtEpochMs)} from ${rollup.sourceCount} source item(s) with ${rollup.modelLabel}.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(rollup.overview, style = MaterialTheme.typography.bodyMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = { onEdit(rollup) }) {
                    Text("Edit")
                }
                OutlinedButton(onClick = { onShowSourceNotes(rollup.id) }) {
                    Text("Show source notes")
                }
            }
        }
    }
}

@Composable
private fun GeneratedRollupRecordCard(
    rollup: RollupSnapshot,
    isSelected: Boolean,
    onSelect: (String?) -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onSelect(rollup.id) },
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected) {
                MaterialTheme.colorScheme.secondaryContainer
            } else {
                MaterialTheme.colorScheme.surface
            },
        ),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(rollup.title, style = MaterialTheme.typography.titleMedium)
            Text(
                formatRollupPeriod(rollup),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = rollup.overview,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 4,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun EmptyNotesState(message: String) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        tonalElevation = 1.dp,
    ) {
        Text(
            text = message,
            modifier = Modifier.padding(16.dp),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        fontWeight = FontWeight.SemiBold,
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ComposeScreen(
    state: LedgerUiState,
    paddingValues: PaddingValues,
    onTitleChange: (String) -> Unit,
    onBodyChange: (String) -> Unit,
    onDateChange: (String) -> Unit,
    onToggleLabel: (Long) -> Unit,
    onLabelDraftChange: (String) -> Unit,
    onEditLabel: (Long) -> Unit,
    onClearLabelEditor: () -> Unit,
    onSaveLabel: () -> Unit,
    onDeleteLabel: () -> Unit,
    onSave: () -> Unit,
    onClear: () -> Unit,
) {
    val isEditingGeneratedSummary = state.editingRollupId != null
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(paddingValues)
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text(
            when {
                isEditingGeneratedSummary -> "Edit generated summary"
                state.editingNoteId == null -> "Capture a note"
                else -> "Edit note"
            },
            style = MaterialTheme.typography.headlineSmall,
        )
        Text(
            if (isEditingGeneratedSummary) {
                "This edits the stored generated summary document directly. A future Update or Rebuild can overwrite your changes."
            } else {
                "Notes stay local. Tags help with note-level narrowing, and rollups rebuild from the dirty checkpoint forward."
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedTextField(
            value = state.composeTitle,
            onValueChange = onTitleChange,
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Title") },
            singleLine = true,
        )
        OutlinedTextField(
            value = state.composeDate,
            onValueChange = onDateChange,
            modifier = Modifier.fillMaxWidth(),
            enabled = !isEditingGeneratedSummary,
            label = { Text(if (isEditingGeneratedSummary) "Period start" else "Date") },
            placeholder = { Text("YYYY-MM-DD") },
            singleLine = true,
        )
        OutlinedTextField(
            value = state.composeBody,
            onValueChange = onBodyChange,
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Body") },
            minLines = 10,
        )
        if (!isEditingGeneratedSummary) {
            Text("Tags", style = MaterialTheme.typography.titleMedium)
            if (state.labels.isEmpty()) {
                Text(
                    "Create reusable tags here, then apply them to notes below.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    state.labels.forEach { label ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onToggleLabel(label.id) },
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            val selected = label.id in state.composeSelectedLabelIds
                            Checkbox(
                                checked = selected,
                                onCheckedChange = null,
                            )
                            Text(text = label.name, modifier = Modifier.weight(1f))
                        }
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = onSave) {
                    Text(
                        if (state.editingNoteId == null) "Save note" else "Update note",
                    )
                }
                OutlinedButton(onClick = onClear) {
                    Text(
                        if (state.editingNoteId == null) "Clear" else "Cancel edit",
                    )
                }
            }
            ElevatedCard {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text("Manage tags", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Keep a small reusable set. Notes can carry multiple tags, and Ask can filter by them later.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedTextField(
                        value = state.labelDraft,
                        onValueChange = onLabelDraftChange,
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text(if (state.editingLabelId == null) "New tag" else "Edit tag") },
                        singleLine = true,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Button(
                            onClick = onSaveLabel,
                            enabled = state.editingLabelId != null || state.labels.size < MAX_TAGS,
                        ) {
                            Text(if (state.editingLabelId == null) "Add tag" else "Save tag")
                        }
                        if (state.editingLabelId != null) {
                            OutlinedButton(onClick = onClearLabelEditor) {
                                Text("Cancel")
                            }
                            TextButton(onClick = onDeleteLabel) {
                                Text("Delete")
                            }
                        }
                    }
                    Text(
                        "${state.labels.size} / $MAX_TAGS tags saved",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (state.labels.isNotEmpty()) {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            state.labels.forEach { label ->
                                FilterChip(
                                    selected = state.editingLabelId == label.id,
                                    onClick = { onEditLabel(label.id) },
                                    label = { Text(label.name) },
                                )
                            }
                        }
                    }
                }
            }
        }
        if (isEditingGeneratedSummary) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = onSave) {
                    Text("Update summary")
                }
                OutlinedButton(onClick = onClear) {
                    Text("Cancel edit")
                }
            }
        }
    }
}

@Composable
private fun BroadScanConfirmPanel(
    state: LedgerUiState,
    onDateFromChange: (String) -> Unit,
    onDateToChange: (String) -> Unit,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.secondaryContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                "Full scan required",
                style = MaterialTheme.typography.titleSmall,
            )
            Text(
                "This question needs to scan all your notes and may take a while. Narrow it down with an optional date range, or search everything.",
                style = MaterialTheme.typography.bodySmall,
            )
            OutlinedTextField(
                value = state.broadScanDateFrom,
                onValueChange = onDateFromChange,
                modifier = Modifier.fillMaxWidth(),
                label = { Text("From date (YYYY-MM-DD, optional)") },
                singleLine = true,
            )
            OutlinedTextField(
                value = state.broadScanDateTo,
                onValueChange = onDateToChange,
                modifier = Modifier.fillMaxWidth(),
                label = { Text("To date (YYYY-MM-DD, optional)") },
                singleLine = true,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onConfirm) {
                    Text("Search anyway")
                }
                OutlinedButton(onClick = onCancel) {
                    Text("Cancel")
                }
            }
        }
    }
}

@Composable
private fun AskScreen(
    state: LedgerUiState,
    paddingValues: PaddingValues,
    onSearchChange: (String) -> Unit,
    onToggleSearchLabel: (Long) -> Unit,
    onSearch: () -> Unit,
    onOpenSearchHit: (SemanticSearchHit) -> Unit,
    onBroadScanDateFromChange: (String) -> Unit,
    onBroadScanDateToChange: (String) -> Unit,
    onConfirmBroadScan: () -> Unit,
    onCancelBroadScan: () -> Unit,
) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(paddingValues),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            ElevatedCard {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text("Ask", style = MaterialTheme.typography.headlineSmall)
                    Text(
                        "Ask across notes and rollups with optional tag filters.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    SearchCard(
                        state = state,
                        onSearchChange = onSearchChange,
                        onToggleSearchLabel = onToggleSearchLabel,
                        onSearch = onSearch,
                        onOpenSearchHit = onOpenSearchHit,
                        onBroadScanDateFromChange = onBroadScanDateFromChange,
                        onBroadScanDateToChange = onBroadScanDateToChange,
                        onConfirmBroadScan = onConfirmBroadScan,
                        onCancelBroadScan = onCancelBroadScan,
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SearchCard(
    state: LedgerUiState,
    onSearchChange: (String) -> Unit,
    onToggleSearchLabel: (Long) -> Unit,
    onSearch: () -> Unit,
    onOpenSearchHit: (SemanticSearchHit) -> Unit,
    onBroadScanDateFromChange: (String) -> Unit,
    onBroadScanDateToChange: (String) -> Unit,
    onConfirmBroadScan: () -> Unit,
    onCancelBroadScan: () -> Unit,
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        OutlinedTextField(
            value = state.searchQuery,
            onValueChange = onSearchChange,
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Ask across notes and rollups") },
            singleLine = true,
        )
        if (state.labels.isNotEmpty()) {
            Text(
                "Optional tags narrow the raw-note layer while the timeline routing stays broad.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                state.labels.forEach { label ->
                    FilterChip(
                        selected = label.id in state.searchSelectedLabelIds,
                        onClick = { onToggleSearchLabel(label.id) },
                        label = { Text(label.name) },
                    )
                }
            }
        }
        if (state.pendingBroadScan) {
            BroadScanConfirmPanel(
                state = state,
                onDateFromChange = onBroadScanDateFromChange,
                onDateToChange = onBroadScanDateToChange,
                onConfirm = onConfirmBroadScan,
                onCancel = onCancelBroadScan,
            )
        } else {
            Button(onClick = onSearch, enabled = !state.isSearching) {
                Text(if (state.isSearching) "Searching…" else "Ask")
            }
        }
        state.searchAnswer?.let { answer ->
            AnswerPanel(answer)
        }
        state.searchAnswerNotice?.let { notice ->
            Text(
                notice,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (state.searchRoute.isNotEmpty()) {
            Text("Route", style = MaterialTheme.typography.titleMedium)
            RouteStrip(route = state.searchRoute)
        }
        if (state.searchResults.isEmpty()) {
            Text(
                "Run aggregation first, then ask. Results will show the best route through time before surfacing notes.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            state.searchResults.forEach { hit ->
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onOpenSearchHit(hit) },
                    shape = RoundedCornerShape(18.dp),
                    tonalElevation = 2.dp,
                ) {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(hit.title, style = MaterialTheme.typography.titleSmall)
                        Text(
                            "${hit.kindLabel()} | score ${"%.3f".format(hit.score)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (hit.labels.isNotEmpty()) {
                            LabelNameStrip(labelNames = hit.labels)
                        }
                        Text(hit.preview, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }
    }
}

@Composable
private fun AnswerPanel(answer: GeneratedAnswer) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        tonalElevation = 2.dp,
        color = MaterialTheme.colorScheme.primaryContainer,
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("Answer", style = MaterialTheme.typography.titleMedium)
            Text(
                "Generated with ${answer.modelLabel} from ${answer.sourceCount} retrieved source(s).",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(answer.text, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun SummarizeScreen(
    state: LedgerUiState,
    paddingValues: PaddingValues,
    onBackgroundProcessingChange: (Boolean) -> Unit,
    onBackgroundProcessingTimeChange: (String) -> Unit,
    onRefresh: () -> Unit,
    onRebuildAll: () -> Unit,
    onRebuildFromDate: (String) -> Unit,
    onRetryModelProvisioning: () -> Unit,
    onExportCorpus: () -> Unit,
    onImportCorpus: () -> Unit,
    onSave: () -> Unit,
) {
    var showRebuildDialog by rememberSaveable { mutableStateOf(false) }
    var rebuildDateDraft by rememberSaveable(state.notes.firstOrNull()?.note?.createdAtEpochMs, state.settings.summaryStartDate) {
        mutableStateOf(defaultRebuildDate(state))
    }
    var showRunLog by rememberSaveable { mutableStateOf(false) }
    var showModels by rememberSaveable { mutableStateOf(false) }
    val latestRunEntry = state.progressLog.lastOrNull()
    val latestDebugEntry = state.debugLogTail.lastOrNull()
    val runLogSummary = when {
        latestRunEntry != null && state.lastRunSucceeded == false -> "Latest failure: $latestRunEntry"
        latestRunEntry != null -> "Latest run: $latestRunEntry"
        latestDebugEntry != null -> latestDebugEntry
        else -> "No run data yet."
    }
    val modelSummary = when {
        state.isProvisioningModels -> "Installing local models."
        state.modelProvisioning.allReady -> "Summary and embedding models are ready."
        else -> {
            val summaryState = state.modelProvisioning.summary.state.displayLabel()
            val embeddingState = state.modelProvisioning.embedding.state.displayLabel()
            "Summary model: $summaryState. Embedding model: $embeddingState."
        }
    }

    if (showRebuildDialog) {
        AlertDialog(
            onDismissRequest = { showRebuildDialog = false },
            title = { Text("Rebuild summaries") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        "Rebuild replaces generated summaries in the selected range. Use Update for the normal incremental pass.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    OutlinedTextField(
                        value = rebuildDateDraft,
                        onValueChange = { rebuildDateDraft = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Rebuild from date") },
                        placeholder = { Text("YYYY-MM-DD") },
                        supportingText = {
                            Text("Leave this as-is to rebuild from a specific date, or use Rebuild all to overwrite the full history.")
                        },
                        singleLine = true,
                    )
                }
            },
            confirmButton = {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = {
                            showRebuildDialog = false
                            onRebuildFromDate(rebuildDateDraft)
                        },
                    ) {
                        Text("Rebuild from date")
                    }
                    Button(
                        onClick = {
                            showRebuildDialog = false
                            onRebuildAll()
                        },
                    ) {
                        Text("Rebuild all")
                    }
                }
            },
            dismissButton = {
                TextButton(onClick = { showRebuildDialog = false }) {
                    Text("Cancel")
                }
            },
        )
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(paddingValues),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            ElevatedCard {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text("Create Summary", style = MaterialTheme.typography.headlineSmall)
                    Text(
                        "Create summaries to refresh summary documents and checkpoints. Background processing can keep this current automatically.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Button(onClick = onRefresh, enabled = !state.isRefreshingInsights) {
                            if (state.activeInsightRefreshMode == InsightRefreshMode.UPDATE) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(18.dp),
                                    strokeWidth = 2.dp,
                                )
                                Spacer(Modifier.width(10.dp))
                            }
                            Text("Update")
                        }
                        OutlinedButton(
                            onClick = { showRebuildDialog = true },
                            enabled = !state.isRefreshingInsights,
                        ) {
                            if (state.activeInsightRefreshMode == InsightRefreshMode.REBUILD) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(18.dp),
                                    strokeWidth = 2.dp,
                                )
                                Spacer(Modifier.width(10.dp))
                            }
                            Text("Rebuild")
                        }
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Background processing", style = MaterialTheme.typography.titleMedium)
                            Text(
                                "Runs one daily update around your selected local time. Android can still shift the exact minute slightly.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(
                            checked = state.settings.backgroundProcessingEnabled,
                            onCheckedChange = onBackgroundProcessingChange,
                        )
                    }
                    OutlinedTextField(
                        value = state.settings.backgroundProcessingTime,
                        onValueChange = onBackgroundProcessingTimeChange,
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Daily update time") },
                        placeholder = { Text("HH:MM") },
                        supportingText = {
                            Text("24-hour local time, for example 02:00 or 21:30.")
                        },
                        singleLine = true,
                    )
                    Button(onClick = onSave) {
                        Text("Save changes")
                    }
                    if (state.isRefreshingInsights) {
                        Text(
                            "The summary job is running through Android background work. You can leave the app and come back.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
        if (state.checkpoints.isNotEmpty()) {
            item {
                Text("Checkpoints", style = MaterialTheme.typography.titleLarge)
            }
            items(state.checkpoints, key = { it.granularity.name }) { checkpoint ->
                CheckpointCard(checkpoint)
            }
        }
        item {
            ElevatedCard {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text("Manage Data", style = MaterialTheme.typography.headlineSmall)
                    Text(
                        "Export the base notes with their original dates and tags, or import a JSON corpus. Import matches existing tags by name, creates missing tags up to the global limit, and skips exact duplicate notes.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Button(
                            onClick = onExportCorpus,
                            enabled = !state.isTransferringCorpus,
                        ) {
                            Text("Export notes")
                        }
                        OutlinedButton(
                            onClick = onImportCorpus,
                            enabled = !state.isTransferringCorpus,
                        ) {
                            Text("Import notes")
                        }
                    }
                    if (state.isTransferringCorpus) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                strokeWidth = 2.dp,
                            )
                            Text(
                                "Processing corpus file.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
        item {
            StatsRow(state.localStats)
        }
        if (false && state.progressLog.isNotEmpty()) {
            item {
                ElevatedCard {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Text("Last run log", style = MaterialTheme.typography.titleMedium)
                        state.progressLog.forEachIndexed { index, entry ->
                            val isLastEntry = index == state.progressLog.lastIndex
                            Text(
                                "· $entry",
                                style = MaterialTheme.typography.bodySmall,
                                color = when {
                                    isLastEntry && state.lastRunSucceeded == false ->
                                        MaterialTheme.colorScheme.error
                                    isLastEntry && state.lastRunSucceeded == true ->
                                        MaterialTheme.colorScheme.primary
                                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                                },
                            )
                        }
                    }
                }
            }
        }
        if (false && state.debugLogTail.isNotEmpty()) {
            item {
                ElevatedCard {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text("Debug log", style = MaterialTheme.typography.titleMedium)
                        state.debugLogPath?.let { path ->
                            Text(
                                path,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        state.debugLogTail.forEach { line ->
                            Text(
                                line,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
        item {
            if (false) {
                ModelProvisioningCard(
                    state = state,
                    onRetry = onRetryModelProvisioning,
                )
            }
        }
        item {
            if (false) {
                StatsRow(state.localStats)
            }
        }
        item {
            if (false) {
                ElevatedCard {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Text("Data", style = MaterialTheme.typography.headlineSmall)
                        Text(
                            "Export the base notes with their original dates and tags, or import a JSON corpus. Import matches existing tags by name, creates missing tags up to the global limit, and skips exact duplicate notes.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Button(
                                onClick = onExportCorpus,
                                enabled = !state.isTransferringCorpus,
                            ) {
                                Text("Export notes")
                            }
                            OutlinedButton(
                                onClick = onImportCorpus,
                                enabled = !state.isTransferringCorpus,
                            ) {
                                Text("Import notes")
                            }
                        }
                        if (state.isTransferringCorpus) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(18.dp),
                                    strokeWidth = 2.dp,
                                )
                                Text(
                                    "Processing corpus file.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
        }
        if (false && state.checkpoints.isNotEmpty()) {
            item {
                Text("Checkpoints", style = MaterialTheme.typography.titleLarge)
            }
            items(state.checkpoints, key = { it.granularity.name }) { checkpoint ->
                CheckpointCard(checkpoint)
            }
        }
        item {
            ExpandableSectionCard(
                title = "Summarize Run Log",
                summary = runLogSummary,
                expanded = showRunLog,
                onToggle = { showRunLog = !showRunLog },
            ) {
                if (state.progressLog.isNotEmpty()) {
                    Text("Run steps", style = MaterialTheme.typography.titleMedium)
                    state.progressLog.forEachIndexed { index, entry ->
                        val isLastEntry = index == state.progressLog.lastIndex
                        Text(
                            "- $entry",
                            style = MaterialTheme.typography.bodySmall,
                            color = when {
                                isLastEntry && state.lastRunSucceeded == false ->
                                    MaterialTheme.colorScheme.error
                                isLastEntry && state.lastRunSucceeded == true ->
                                    MaterialTheme.colorScheme.primary
                                else -> MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        )
                    }
                }
                if (state.debugLogTail.isNotEmpty()) {
                    if (state.progressLog.isNotEmpty()) {
                        Spacer(Modifier.size(4.dp))
                    }
                    Text("Debug tail", style = MaterialTheme.typography.titleMedium)
                    state.debugLogPath?.let { path ->
                        Text(
                            path,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    state.debugLogTail.forEach { line ->
                        Text(
                            line,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                if (state.progressLog.isEmpty() && state.debugLogTail.isEmpty()) {
                    Text(
                        "No run data yet.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        item {
            ExpandableSectionCard(
                title = "Models",
                summary = modelSummary,
                expanded = showModels,
                onToggle = { showModels = !showModels },
            ) {
                ModelProvisioningCard(
                    state = state,
                    onRetry = onRetryModelProvisioning,
                    embedded = true,
                )
            }
        }
    }
}

@Composable
private fun ModelProvisioningCard(
    state: LedgerUiState,
    onRetry: () -> Unit,
    embedded: Boolean = false,
) {
    val content: @Composable () -> Unit = {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Models", style = MaterialTheme.typography.headlineSmall)
            Text(
                "The app installs the local summary and embedding models automatically. The Gemma 4 summary model is a multi-gigabyte first-run download, so this works best on Wi-Fi with plenty of free storage. Summary generation needs the summary model. Ask also needs the embedding model.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            state.modelProvisioning.progressFraction?.let { progress ->
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        "Overall install ${formatProgressLine(progress, state.modelProvisioning.downloadedBytes, state.modelProvisioning.totalBytes)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    LinearProgressIndicator(
                        progress = { progress },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
            ModelStatusRow(state.modelProvisioning.summary)
            ModelStatusRow(state.modelProvisioning.embedding)
            if (state.isProvisioningModels) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                    )
                    Text(
                        "Installing models in app storage.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else if (!state.modelProvisioning.allReady) {
                OutlinedButton(onClick = onRetry) {
                    Text("Retry model install")
                }
            }
        }
    }
    if (embedded) {
        content()
    } else {
        ElevatedCard {
            content()
        }
    }
}

@Composable
private fun ExpandableSectionCard(
    title: String,
    summary: String,
    expanded: Boolean,
    onToggle: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    ElevatedCard {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(title, style = MaterialTheme.typography.headlineSmall)
                TextButton(onClick = onToggle) {
                    Text(if (expanded) "Collapse" else "Expand")
                }
            }
            Text(
                summary,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (expanded) {
                content()
            }
        }
    }
}

@Composable
private fun ModelStatusRow(status: LocalModelArtifactStatus) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        tonalElevation = 1.dp,
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(status.displayName, style = MaterialTheme.typography.titleMedium)
            Text(
                "Status: ${status.state.displayLabel()}",
                style = MaterialTheme.typography.bodySmall,
                color = status.state.color(),
            )
            Text(
                status.detail,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            status.progressFraction?.let { progress ->
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    LinearProgressIndicator(
                        progress = { progress },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        formatProgressLine(progress, status.downloadedBytes, status.totalBytes),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            status.modelLabel?.takeIf(String::isNotBlank)?.let { modelLabel ->
                Text(
                    "Model: $modelLabel",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun StatsRow(stats: LocalStats) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        StatsTile("Total", stats.totalNotes.toString())
        StatsTile("7 days", stats.notesThisWeek.toString())
        StatsTile("30 days", stats.notesThisMonth.toString())
    }
}

@Composable
private fun StatsTile(label: String, value: String) {
    Surface(
        modifier = Modifier.width(120.dp),
        shape = RoundedCornerShape(18.dp),
        tonalElevation = 3.dp,
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(label, style = MaterialTheme.typography.labelMedium)
            Text(value, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun CheckpointCard(checkpoint: AggregationCheckpoint) {
    val lastRunLabel = when {
        checkpoint.lastRunFinishedEpochMs != null -> formatTimestamp(checkpoint.lastRunFinishedEpochMs)
        checkpoint.lastRunStartedEpochMs != null -> "${formatTimestamp(checkpoint.lastRunStartedEpochMs)} (running)"
        else -> "Not yet"
    }
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        tonalElevation = 2.dp,
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(checkpoint.granularity.name.lowercase().replaceFirstChar { it.uppercase() }, style = MaterialTheme.typography.titleMedium)
            Text(
                "Last run: $lastRunLabel",
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                "Coverage through: ${checkpoint.lastCompletedEndEpochMs?.let(::formatTimestamp) ?: "Not yet"}",
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                if (checkpoint.dirtyFromEpochMs == null) {
                    "Status: Up to date"
                } else {
                    "Status: Needs update since ${formatTimestamp(checkpoint.dirtyFromEpochMs)}"
                },
                style = MaterialTheme.typography.bodyMedium,
            )
            checkpoint.lastError?.takeIf(String::isNotBlank)?.let { error ->
                Text(
                    error,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

@Composable
private fun RollupCard(rollup: RollupSnapshot) {
    ElevatedCard {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                "${rollup.granularity.displayLabel()} | ${formatTimestamp(rollup.periodStartEpochMs)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(rollup.title, style = MaterialTheme.typography.titleLarge)
            Text(rollup.overview, style = MaterialTheme.typography.bodyLarge)
            Text(
                "Generated from ${rollup.sourceCount} source item(s) with ${rollup.modelLabel}.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun RollupGranularity.displayLabel(): String {
    return name.lowercase().replaceFirstChar { it.uppercase() }
}

private fun RollupGranularity.notesTabLabel(): String {
    return when (this) {
        RollupGranularity.DAILY -> "Daily"
        RollupGranularity.WEEKLY -> "Weekly"
        RollupGranularity.MONTHLY -> "Monthly"
        RollupGranularity.YEARLY -> "Annual"
    }
}

private fun LocalModelInstallState.displayLabel(): String {
    return when (this) {
        LocalModelInstallState.CHECKING -> "Checking"
        LocalModelInstallState.DOWNLOADING -> "Downloading"
        LocalModelInstallState.READY -> "Ready"
        LocalModelInstallState.FAILED -> "Failed"
        LocalModelInstallState.MISSING -> "Missing"
    }
}

@Composable
private fun LocalModelInstallState.color() = when (this) {
    LocalModelInstallState.READY -> MaterialTheme.colorScheme.primary
    LocalModelInstallState.FAILED -> MaterialTheme.colorScheme.error
    LocalModelInstallState.CHECKING,
    LocalModelInstallState.DOWNLOADING,
    LocalModelInstallState.MISSING,
    -> MaterialTheme.colorScheme.onSurfaceVariant
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LabelStrip(labels: List<LabelEntity>) {
    LabelNameStrip(labelNames = labels.map(LabelEntity::name))
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LabelNameStrip(labelNames: List<String>) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        labelNames.forEach { label ->
            Surface(
                shape = RoundedCornerShape(50),
                color = MaterialTheme.colorScheme.secondaryContainer,
            ) {
                Text(
                    text = label,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                    style = MaterialTheme.typography.labelMedium,
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RouteStrip(route: List<SearchRouteStep>) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        route.forEach { step ->
            Surface(
                shape = RoundedCornerShape(16.dp),
                tonalElevation = 2.dp,
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Text(
                        step.granularity.name.lowercase().replaceFirstChar { it.uppercase() },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        step.title,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

private fun SemanticSearchHit.kindLabel(): String {
    return when {
        kind == "note" -> "Note"
        granularity != null -> granularity.name.lowercase().replaceFirstChar { it.uppercase() }
        else -> "Rollup"
    }
}

private fun formatTimestamp(epochMs: Long): String {
    val formatter = DateTimeFormatter.ofPattern("MMM d, yyyy h:mm a").withZone(ZoneId.systemDefault())
    return formatter.format(Instant.ofEpochMilli(epochMs))
}

private fun formatDateOnly(epochMs: Long): String {
    val formatter = DateTimeFormatter.ofPattern("MMM d, yyyy").withZone(ZoneId.systemDefault())
    return formatter.format(Instant.ofEpochMilli(epochMs))
}

private fun defaultRebuildDate(state: LedgerUiState): String {
    return state.settings.summaryStartDate
        .takeIf(String::isNotBlank)
        ?: state.notes.minByOrNull { it.note.createdAtEpochMs }
            ?.note
            ?.createdAtEpochMs
            ?.let { epochMs ->
                DateTimeFormatter.ISO_LOCAL_DATE.format(
                    Instant.ofEpochMilli(epochMs).atZone(ZoneId.systemDefault()).toLocalDate(),
                )
            }
        ?: DateTimeFormatter.ISO_LOCAL_DATE.format(LocalDate.now())
}

private fun formatRollupPeriod(rollup: RollupSnapshot): String {
    val start = formatDateOnly(rollup.periodStartEpochMs)
    val end = formatDateOnly(rollup.periodEndEpochMs)
    return if (start == end) start else "$start to $end"
}

private fun formatProgressPercent(progress: Float): String {
    return "${(progress * 100f).toInt()}%"
}

private fun formatProgressLine(progress: Float, downloadedBytes: Long?, totalBytes: Long?): String {
    val percent = formatProgressPercent(progress)
    if (downloadedBytes == null || totalBytes == null || totalBytes <= 0L) {
        return percent
    }
    return "$percent - ${formatByteCount(downloadedBytes)} / ${formatByteCount(totalBytes)}"
}

private fun formatByteCount(bytes: Long): String {
    val gib = 1024.0 * 1024.0 * 1024.0
    val mib = 1024.0 * 1024.0
    return when {
        bytes >= gib -> String.format("%.2f GB", bytes / gib)
        bytes >= mib -> String.format("%.1f MB", bytes / mib)
        else -> "$bytes B"
    }
}
