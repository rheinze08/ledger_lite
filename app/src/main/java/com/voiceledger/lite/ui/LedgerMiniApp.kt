package com.voiceledger.lite.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.EditNote
import androidx.compose.material.icons.filled.Insights
import androidx.compose.material.icons.filled.ListAlt
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
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
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.voiceledger.lite.data.LabelEntity
import com.voiceledger.lite.data.LocalStats
import com.voiceledger.lite.data.NoteWithLabels
import com.voiceledger.lite.semantic.AggregationCheckpoint
import com.voiceledger.lite.semantic.GeneratedAnswer
import com.voiceledger.lite.semantic.RollupSnapshot
import com.voiceledger.lite.semantic.SearchRouteStep
import com.voiceledger.lite.semantic.SemanticSearchHit
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
fun LedgerMiniApp(
    viewModel: LedgerViewModel,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

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

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Voice Ledger Lite")
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
                                    AppTab.NOTES -> Icons.Filled.ListAlt
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
                onSelect = viewModel::selectNote,
                onEdit = viewModel::loadNoteIntoComposer,
                onDelete = viewModel::deleteNote,
            )
            AppTab.COMPOSE -> ComposeScreen(
                state = state,
                paddingValues = innerPadding,
                onTitleChange = viewModel::updateComposeTitle,
                onBodyChange = viewModel::updateComposeBody,
                onToggleLabel = viewModel::toggleComposeLabel,
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
            )
            AppTab.SUMMARIZE -> SummarizeScreen(
                state = state,
                paddingValues = innerPadding,
                onLabelDraftChange = viewModel::updateLabelDraft,
                onEditLabel = viewModel::editLabel,
                onClearLabelEditor = viewModel::clearLabelEditor,
                onSaveLabel = viewModel::saveLabel,
                onDeleteLabel = viewModel::deleteEditingLabel,
                onBackgroundProcessingChange = viewModel::updateBackgroundProcessing,
                onRefresh = { viewModel.refreshInsights(false) },
                onRebuild = { viewModel.refreshInsights(true) },
                onSave = viewModel::saveSettings,
                onOpenNote = viewModel::selectNote,
            )
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
    onSelect: (Long?) -> Unit,
    onEdit: (NoteWithLabels) -> Unit,
    onDelete: (Long) -> Unit,
) {
    val selectedNote = state.notes.firstOrNull { it.note.id == state.selectedNoteId }
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
        if (selectedNote != null) {
            item {
                ElevatedCard(
                    colors = CardDefaults.elevatedCardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant,
                    ),
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Text(selectedNote.note.title, style = MaterialTheme.typography.titleLarge)
                        Text(
                            formatTimestamp(selectedNote.note.createdAtEpochMs),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (selectedNote.labels.isNotEmpty()) {
                            LabelStrip(labels = selectedNote.labels)
                        }
                        Text(selectedNote.note.body, style = MaterialTheme.typography.bodyMedium)
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Button(onClick = { onEdit(selectedNote) }) {
                                Text("Edit")
                            }
                            OutlinedButton(onClick = { onDelete(selectedNote.note.id) }) {
                                Text("Delete")
                            }
                        }
                    }
                }
            }
        }
        items(state.notes, key = { it.note.id }) { note ->
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onSelect(note.note.id) },
                colors = CardDefaults.cardColors(
                    containerColor = if (state.selectedNoteId == note.note.id) {
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
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ComposeScreen(
    state: LedgerUiState,
    paddingValues: PaddingValues,
    onTitleChange: (String) -> Unit,
    onBodyChange: (String) -> Unit,
    onToggleLabel: (Long) -> Unit,
    onSave: () -> Unit,
    onClear: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(paddingValues)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text(
            if (state.editingNoteId == null) "Capture a note" else "Edit note",
            style = MaterialTheme.typography.headlineSmall,
        )
        Text(
            "Notes stay local. Tags help with note-level narrowing, and rollups rebuild from the dirty checkpoint forward.",
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
            value = state.composeBody,
            onValueChange = onBodyChange,
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            label = { Text("Body") },
            minLines = 10,
        )
        Text("Tags", style = MaterialTheme.typography.titleMedium)
        if (state.labels.isEmpty()) {
            Text(
                "Create reusable tags in Summarize, then come back here to apply them.",
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
                Text(if (state.editingNoteId == null) "Save note" else "Update note")
            }
            OutlinedButton(onClick = onClear) {
                Text(if (state.editingNoteId == null) "Clear" else "Cancel edit")
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
        Button(onClick = onSearch, enabled = !state.isSearching) {
            Text(if (state.isSearching) "Searching" else "Ask")
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

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SummarizeScreen(
    state: LedgerUiState,
    paddingValues: PaddingValues,
    onLabelDraftChange: (String) -> Unit,
    onEditLabel: (Long) -> Unit,
    onClearLabelEditor: () -> Unit,
    onSaveLabel: () -> Unit,
    onDeleteLabel: () -> Unit,
    onBackgroundProcessingChange: (Boolean) -> Unit,
    onRefresh: () -> Unit,
    onRebuild: () -> Unit,
    onOpenNote: (Long?) -> Unit,
    onSave: () -> Unit,
) {
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
                        "Create summaries to refresh rollups and checkpoints. Background processing can keep this current automatically.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Button(onClick = onRefresh, enabled = !state.isRefreshingInsights) {
                            if (state.isRefreshingInsights) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(18.dp),
                                    strokeWidth = 2.dp,
                                )
                                Spacer(Modifier.width(10.dp))
                            }
                            Text(if (state.isRefreshingInsights) "Creating" else "Create summary")
                        }
                        OutlinedButton(onClick = onRebuild, enabled = !state.isRefreshingInsights) {
                            Text("Rebuild since start")
                        }
                    }
                }
            }
        }
        item {
            StatsRow(state.localStats)
        }
        if (state.checkpoints.isNotEmpty()) {
            item {
                Text("Checkpoints", style = MaterialTheme.typography.titleLarge)
            }
            items(state.checkpoints, key = { it.granularity.name }) { checkpoint ->
                CheckpointCard(checkpoint)
            }
        }
        if (state.latestRollups.isNotEmpty()) {
            item {
                Text("Latest rollups", style = MaterialTheme.typography.titleLarge)
            }
            items(state.latestRollups, key = RollupSnapshot::id) { rollup ->
                RollupCard(rollup, onOpenNote)
            }
        }
        item {
            ElevatedCard {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text("Tags", style = MaterialTheme.typography.headlineSmall)
                    Text(
                        "Keep a small reusable set. Notes can carry multiple tags, and search can filter by them later.",
                        style = MaterialTheme.typography.bodyMedium,
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
                    if (state.labels.isEmpty()) {
                        Text(
                            "No tags yet.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            state.labels.forEach { label ->
                                FilterChip(
                                    selected = state.editingLabelId == label.id,
                                    onClick = { onEditLabel(label.id) },
                                    label = { Text(label.name) },
                                )
                            }
                        }
                        if (state.editingLabelId == null && state.labels.size >= MAX_TAGS) {
                            Text(
                                "You have reached the tag limit. Edit or delete an existing tag to make room for a new one.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
        item {
            Text(
                "Background processing keeps summaries and semantic search refreshed when the device is charging.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Background processing", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Runs daily when charging so rollups and vector search can catch up off-hours.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = state.settings.backgroundProcessingEnabled,
                    onCheckedChange = onBackgroundProcessingChange,
                )
            }
        }
        item {
            Button(onClick = onSave) {
                Text("Save changes")
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
                "Last completed: ${checkpoint.lastCompletedEndEpochMs?.let(::formatTimestamp) ?: "Not yet"}",
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                if (checkpoint.dirtyFromEpochMs == null) {
                    "Status: Up to date"
                } else {
                    "Status: Needs rebuild since ${formatTimestamp(checkpoint.dirtyFromEpochMs)}"
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

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RollupCard(rollup: RollupSnapshot, onOpenNote: (Long?) -> Unit) {
    ElevatedCard {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(
                "${rollup.granularity.name.lowercase().replaceFirstChar { it.uppercase() }} | ${formatTimestamp(rollup.periodStartEpochMs)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(rollup.title, style = MaterialTheme.typography.titleLarge)
            Text(rollup.overview, style = MaterialTheme.typography.bodyLarge)
            Text("Highlights", style = MaterialTheme.typography.titleMedium)
            rollup.highlights.forEach { highlight ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box(
                        modifier = Modifier
                            .padding(top = 7.dp)
                            .size(6.dp)
                            .clip(RoundedCornerShape(50))
                            .background(MaterialTheme.colorScheme.secondary),
                    )
                    Text(highlight, style = MaterialTheme.typography.bodyMedium)
                }
            }
            Text("Themes", style = MaterialTheme.typography.titleMedium)
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                rollup.themes.forEach { theme ->
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(18.dp),
                        tonalElevation = 2.dp,
                    ) {
                        Column(
                            modifier = Modifier.padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Text(theme.label, style = MaterialTheme.typography.titleSmall)
                            Text(theme.summary, style = MaterialTheme.typography.bodyMedium)
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                theme.noteIds.forEach { noteId ->
                                    FilterChip(
                                        selected = false,
                                        onClick = { onOpenNote(noteId) },
                                        label = { Text("Note $noteId") },
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
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
