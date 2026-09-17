package moe.antimony.hoshi.features.statistics

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import moe.antimony.hoshi.R
import moe.antimony.hoshi.ui.asString

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun StatisticsBookView(
    folder: String,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: StatisticsBookViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var confirmDeleteAll by rememberSaveable { mutableStateOf(false) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, viewModel, folder) {
        val observer = StatisticsLifecycleReloader { viewModel.load(folder) }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(state.closeRequested) { if (state.closeRequested) onClose() }
    BackHandler(enabled = state.isSaving) { }
    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text(state.book?.title ?: stringResource(R.string.statistics_title)) },
                navigationIcon = {
                    IconButton(enabled = !state.isSaving, onClick = onClose) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = null)
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        ) {
            if (state.isLoading) item { CircularProgressIndicator() }
            val book = state.book
            if (!state.isLoading && book == null) {
                item { Text(stringResource(R.string.statistics_book_unavailable)) }
            } else if (book != null) {
                if (book.isArchived) item {
                    Text(stringResource(R.string.statistics_archived_book), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (book.statistics.isEmpty()) item { Text(stringResource(R.string.statistics_no_reading_records)) }
                items(book.statistics, key = { it.dateKey }) { statistic ->
                    ListItem(
                        modifier = Modifier.clickable(enabled = !state.isSaving) { viewModel.edit(statistic.dateKey) },
                        headlineContent = { Text(statisticsEditorDate(statistic.dateKey)) },
                        supportingContent = {
                            Text(stringResource(R.string.statistics_distribution_meta_format,
                                formatStatisticsDuration(statistic.readingTime), formatStatisticsCharacters(statistic.charactersRead)))
                        },
                    )
                }
                if (book.statistics.isNotEmpty()) item {
                    TextButton(enabled = !state.isSaving, onClick = { confirmDeleteAll = true }) {
                        Text(stringResource(R.string.statistics_delete_all), color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        }
    }
    if (confirmDeleteAll) AlertDialog(
        onDismissRequest = { confirmDeleteAll = false },
        title = { Text(stringResource(R.string.statistics_delete_all)) },
        text = { Text(stringResource(R.string.statistics_delete_all_confirmation)) },
        confirmButton = {
            TextButton(enabled = !state.isSaving, onClick = { confirmDeleteAll = false; viewModel.deleteAll() }) {
                Text(stringResource(R.string.action_delete), color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = {
            TextButton(onClick = { confirmDeleteAll = false }) { Text(stringResource(R.string.action_cancel)) }
        },
    )
    state.draft?.let { draft ->
        ModalBottomSheet(
            onDismissRequest = viewModel::cancelEdit,
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true, confirmValueChange = { !state.isSaving }),
        ) {
            Column(Modifier.padding(horizontal = 24.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(statisticsEditorDate(draft.dateKey), style = MaterialTheme.typography.titleLarge)
                OutlinedTextField(
                    value = draft.characters,
                    onValueChange = { value -> viewModel.changeDraft { it.copy(characters = value) } },
                    enabled = !state.isSaving,
                    label = { Text(stringResource(R.string.statistics_characters_read)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(
                        value = draft.hours,
                        onValueChange = { value -> viewModel.changeDraft { it.copy(hours = value) } },
                        enabled = !state.isSaving,
                        label = { Text(stringResource(R.string.statistics_edit_hours)) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedTextField(
                        value = draft.minutes,
                        onValueChange = { value -> viewModel.changeDraft { it.copy(minutes = value) } },
                        enabled = !state.isSaving,
                        label = { Text(stringResource(R.string.statistics_edit_minutes)) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    TextButton(enabled = !state.isSaving, onClick = { viewModel.deleteDay(draft.dateKey) }) {
                        Text(stringResource(R.string.action_delete), color = MaterialTheme.colorScheme.error)
                    }
                    Row {
                        TextButton(enabled = !state.isSaving, onClick = viewModel::cancelEdit) { Text(stringResource(R.string.action_cancel)) }
                        TextButton(enabled = draft.canSave && !state.isSaving, onClick = viewModel::saveDay) { Text(stringResource(R.string.action_save)) }
                    }
                }
            }
        }
    }
    state.error?.let { error ->
        AlertDialog(
            onDismissRequest = viewModel::dismissError,
            text = { Text(error.asString()) },
            confirmButton = { TextButton(onClick = viewModel::dismissError) { Text(stringResource(R.string.action_ok)) } },
        )
    }
}

private fun statisticsEditorDate(dateKey: String): String =
    runCatching { LocalDate.parse(dateKey).format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)) }
        .getOrDefault(dateKey)
