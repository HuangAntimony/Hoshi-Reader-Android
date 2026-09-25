package moe.antimony.hoshi.features.statistics

import moe.antimony.hoshi.ui.theme.hoshiContainerOutline
import moe.antimony.hoshi.ui.theme.hoshiGroupOutline
import moe.antimony.hoshi.ui.theme.hoshiSurfaces
import moe.antimony.hoshi.ui.theme.hoshiContainerBorder
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import moe.antimony.hoshi.ui.HoshiAlertDialog as AlertDialog
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.Surface
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import moe.antimony.hoshi.R
import moe.antimony.hoshi.epub.ReadingSession
import java.time.Instant
import java.time.ZoneId
import moe.antimony.hoshi.ui.asString
import moe.antimony.hoshi.ui.theme.LocalHoshiEInkMode

private val StatisticsDayGroupCornerRadius = 24.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun StatisticsBookView(
    folder: String,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: StatisticsBookViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val eInkMode = LocalHoshiEInkMode.current
    val outlineColor = MaterialTheme.colorScheme.outlineVariant
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
        containerColor = hoshiSurfaces.page,
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Text(
                        state.book?.title ?: stringResource(R.string.statistics_title),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = hoshiSurfaces.page, scrolledContainerColor = hoshiSurfaces.page),
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
            val sessions = book?.sessions.orEmpty().entries.filter { it.value.value != null }.sortedBy { it.value.value!!.startedAt }
            if (!state.isLoading && book == null) {
                item { Text(stringResource(R.string.statistics_book_unavailable)) }
            } else if (book != null) {
                if (sessions.isEmpty()) item { Text(stringResource(R.string.statistics_no_reading_records)) }
                if (sessions.isNotEmpty()) item {
                    StatisticsSectionHeading(stringResource(R.string.statistics_sessions_heading), Modifier.padding(start = 16.dp, end = 16.dp, bottom = 8.dp))
                }
                itemsIndexed(sessions, key = { _, statistic -> statistic.key }) { index, statistic ->
                    val first = index == 0
                    val last = index == sessions.lastIndex
                    Surface(
                        modifier = if (eInkMode) Modifier.hoshiGroupOutline(first, last, outlineColor, StatisticsDayGroupCornerRadius) else Modifier,
                        shape = RoundedCornerShape(
                            topStart = if (first) StatisticsDayGroupCornerRadius else 0.dp,
                            topEnd = if (first) StatisticsDayGroupCornerRadius else 0.dp,
                            bottomStart = if (last) StatisticsDayGroupCornerRadius else 0.dp,
                            bottomEnd = if (last) StatisticsDayGroupCornerRadius else 0.dp,
                        ),
                        color = hoshiSurfaces.group,
                    ) {
                        Column {
                            StatisticsSessionRow(statistic.value.value!!, enabled = !state.isSaving, onClick = { viewModel.edit(statistic.key) })
                            if (!last) HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.outlineVariant)
                        }
                    }
                }
                if (sessions.isNotEmpty()) item {
                    Surface(
                        modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
                        shape = RoundedCornerShape(24.dp),
                        color = hoshiSurfaces.group,
                        border = if (eInkMode) BorderStroke(1.dp, outlineColor) else null,
                    ) {
                        TextButton(enabled = !state.isSaving, onClick = { confirmDeleteAll = true }, contentPadding = PaddingValues(16.dp)) {
                            Text(stringResource(R.string.statistics_delete_all), modifier = Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.error)
                        }
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
            modifier = Modifier.hoshiContainerOutline(androidx.compose.material3.BottomSheetDefaults.ExpandedShape),
            containerColor = hoshiSurfaces.overlay,
            tonalElevation = 0.dp,
            onDismissRequest = viewModel::cancelEdit,
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true, confirmValueChange = { !state.isSaving }),
        ) {
            Column(Modifier.padding(horizontal = 24.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(statisticsEditorDate(draft.startedAt), style = MaterialTheme.typography.titleLarge)
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
                    TextButton(enabled = !state.isSaving, onClick = { viewModel.deleteSession(draft.id) }) {
                        Text(stringResource(R.string.action_delete), color = MaterialTheme.colorScheme.error)
                    }
                    Row {
                        TextButton(enabled = !state.isSaving, onClick = viewModel::cancelEdit) { Text(stringResource(R.string.action_cancel)) }
                        TextButton(enabled = draft.canSave && !state.isSaving, onClick = viewModel::saveSession) { Text(stringResource(R.string.action_save)) }
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

@Composable
private fun StatisticsSessionRow(statistic: ReadingSession, enabled: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(enabled = enabled, onClick = onClick).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                statisticsEditorDate(statistic.startedAt), style = MaterialTheme.typography.bodyLarge,
                autoSize = TextAutoSize.StepBased(minFontSize = 12.sp, maxFontSize = 16.sp), maxLines = 1,
            )
            Text(formatStatisticsGroupedCount(statistic.charactersRead), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text(
            formatStatisticsDuration(statistic.readingTime), modifier = Modifier.widthIn(max = 124.dp),
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            autoSize = TextAutoSize.StepBased(minFontSize = 12.sp, maxFontSize = 14.sp), maxLines = 1,
        )
        Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
    }
}

private fun statisticsEditorDate(startedAt: Long): String =
    Instant.ofEpochMilli(startedAt).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT))
