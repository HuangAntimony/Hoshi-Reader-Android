package moe.antimony.hoshi.features.tts

import moe.antimony.hoshi.content.ContentLanguageProfile
import moe.antimony.hoshi.ui.hoshiContentKeyboardOptions
import android.content.Intent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.automirrored.rounded.MenuBook
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.util.UUID
import kotlinx.coroutines.launch
import moe.antimony.hoshi.R
import moe.antimony.hoshi.features.sasayaki.SasayakiColorRow
import moe.antimony.hoshi.features.sasayaki.SharedReaderPlaybackSettings
import moe.antimony.hoshi.features.reader.ReaderColorPickerDialog
import moe.antimony.hoshi.features.reader.ReaderBottomPanel
import moe.antimony.hoshi.features.reader.ReaderSheetSwitchRow
import moe.antimony.hoshi.features.reader.ReaderSheetTabs
import moe.antimony.hoshi.features.reader.readerSheetStyle
import moe.antimony.hoshi.features.settings.SettingsDetailScaffold
import moe.antimony.hoshi.ui.HoshiAlertDialog as AlertDialog
import moe.antimony.hoshi.ui.HoshiButton
import moe.antimony.hoshi.ui.HoshiDropdownMenu as DropdownMenu
import moe.antimony.hoshi.ui.hoshiOutlinedTextFieldColors
import moe.antimony.hoshi.ui.theme.hoshiSurfaces

@Composable
fun TtsSheet(
    viewModel: TtsViewModel,
    bookId: String,
    onStart: () -> Unit,
    onDismiss: () -> Unit,
) {
    ReaderBottomPanel(sheetStyle = readerSheetStyle(), onDismiss = onDismiss) {
        TtsPanel(viewModel, bookId, onStart, Modifier.fillMaxWidth().weight(1f))
    }
}

@Composable
fun TtsSettingsView(onClose: () -> Unit, modifier: Modifier = Modifier) {
    val viewModel: TtsViewModel = hiltViewModel()
    SettingsDetailScaffold(title = stringResource(R.string.tts_title), onClose = onClose, modifier = modifier) { padding ->
        TtsPanel(viewModel, null, null, Modifier.fillMaxSize().padding(padding))
    }
}

@Composable
internal fun TtsPanel(viewModel: TtsViewModel, bookId: String?, onStart: (() -> Unit)?, modifier: Modifier) {
    val readingSettings by viewModel.readingSettings.collectAsStateWithLifecycle()
    var colorRow by remember { mutableStateOf<SasayakiColorRow?>(null) }
    val preferences by viewModel.preferences.collectAsStateWithLifecycle()
    val catalog by viewModel.catalog.collectAsStateWithLifecycle()
    val engines by viewModel.engines.collectAsStateWithLifecycle()
    val playback by viewModel.playback.state.collectAsStateWithLifecycle()
    val busy by viewModel.busy.collectAsStateWithLifecycle()
    val preview by viewModel.previewState.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()
    val readingError by viewModel.readingError.collectAsStateWithLifecycle()
    var selectedTab by rememberSaveable { mutableIntStateOf(0) }
    var editor by remember { mutableStateOf<TtsWord?>(null) }
    var configureSource by remember { mutableStateOf<TtsOnlineSource?>(null) }
    val context = LocalContext.current
    val sample = stringResource(R.string.tts_sample)
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.refresh() }
    DisposableEffect(viewModel) { onDispose { viewModel.stopPreview() } }
    Column(modifier) {
        if (onStart != null) {
            TtsPlaybackControls(playback, busy,
                onPrevious = { viewModel.stopPreview(); viewModel.playback.previous() }, onNext = { viewModel.stopPreview(); viewModel.playback.next() },
                onRepeat = { viewModel.stopPreview(); viewModel.playback.repeat() },
                onToggle = { viewModel.stopPreview(); if (playback.playing) viewModel.playback.pause() else if (playback.started) viewModel.playback.play() else onStart() })
        }
        ReaderSheetTabs(
            labels = listOf(stringResource(R.string.tts_tab_voice), stringResource(R.string.tts_tab_dictionary), stringResource(R.string.settings_title)),
            selectedIndex = selectedTab, onSelected = { selectedTab = it },
            modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = if (onStart == null) 12.dp else 0.dp, bottom = 10.dp),
        )
        if (selectedTab == 1) {
            TtsDictionaryList(
                words = preferences.words.filter { bookId == null || it.bookId == null || it.bookId == bookId },
                onAdd = { editor = TtsWord(UUID.randomUUID().toString(), "", "", bookId) },
                onEdit = { editor = it }, modifier = Modifier.weight(1f),
            )
        } else if (selectedTab == 2) {
            Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(bottom = 28.dp)) {
                ReaderSheetSwitchRow(stringResource(R.string.tts_use_ruby), preferences.settings.useRubyReadings,
                    onCheckedChange = viewModel::useRubyReadings)
                readingSettings?.let { settings ->
                    SharedReaderPlaybackSettings(settings, viewModel::readingSettings, { colorRow = it })
                } ?: LinearProgressIndicator(Modifier.fillMaxWidth().padding(20.dp))
                readingError?.let { Text(stringResource(it.messageRes()), color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(20.dp)) }
            }
        } else {
            TtsVoiceSettings(preferences.settings, catalog, engines, busy, preview, error ?: playback.error,
                onSettings = viewModel::settings,
                onPreview = { viewModel.preview(TtsSentence(0, 0, 0, sample), bookId) }, onStopPreview = viewModel::stopPreview,
                onRetry = { viewModel.dismissError(); viewModel.refresh(force = true) }, onConfigure = { configureSource = it },
                onSystemSettings = {
                    try { context.startActivity(Intent("com.android.settings.TTS_SETTINGS")) }
                    catch (_: android.content.ActivityNotFoundException) { context.startActivity(Intent(android.provider.Settings.ACTION_SETTINGS)) }
                }, modifier = Modifier.weight(1f))
        }
    }
    configureSource?.let { source ->
        TtsSourceConfigurationSheet(source, preferences.settings.online[source.id] ?: TtsOnlineConfig(), viewModel) { configureSource = null }
    }
    colorRow?.let { row ->
        readingSettings?.let { settings ->
            ReaderColorPickerDialog(title = stringResource(row.labelRes), initialColor = row.color(settings),
                defaultColor = row.defaultColor,
                onColorChange = { viewModel.readingSettings(row.updated(settings, it)); colorRow = null },
                onDismiss = { colorRow = null })
        }
    }
    editor?.let { word -> TtsWordEditor(viewModel, word, bookId ?: word.bookId, null, { editor = null }) }
}

@Composable
private fun TtsPlaybackControls(
    state: TtsPlaybackState, busy: Boolean,
    onPrevious: () -> Unit, onNext: () -> Unit, onRepeat: () -> Unit, onToggle: () -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) {
                IconButton(onClick = onRepeat, enabled = state.started && state.sentence != null) {
                    Icon(Icons.Rounded.Replay, null)
                }
            }
            IconButton(onClick = onPrevious, enabled = state.started && state.index > 0) { Icon(Icons.Rounded.SkipPrevious, null) }
            IconButton(onClick = onToggle, enabled = !busy && state.sentences.isNotEmpty()) {
                Icon(if (state.playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, null, Modifier.size(32.dp))
            }
            IconButton(onClick = onNext, enabled = state.started && state.index < state.sentences.lastIndex) { Icon(Icons.Rounded.SkipNext, null) }
            Box(Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) {
                if (state.preparing) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
            }
        }
    }
}

@Composable
private fun TtsDictionaryList(words: List<TtsWord>, onAdd: () -> Unit, onEdit: (TtsWord) -> Unit, modifier: Modifier) {
    LazyColumn(modifier.fillMaxWidth(), contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 28.dp)) {
        item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.tts_dictionary), style = MaterialTheme.typography.titleSmall,
                    color = hoshiSurfaces.muted, modifier = Modifier.weight(1f))
                IconButton(onClick = onAdd) { Icon(Icons.Rounded.Add, null) }
            }
        }
        if (words.isEmpty()) item {
            Column(Modifier.fillMaxWidth().padding(vertical = 32.dp), horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Icon(Icons.AutoMirrored.Rounded.MenuBook, null, Modifier.size(32.dp), tint = hoshiSurfaces.muted)
                Text(stringResource(R.string.tts_dictionary_empty), style = MaterialTheme.typography.bodyMedium, color = hoshiSurfaces.muted)
                TextButton(onClick = onAdd) { Text(stringResource(R.string.tts_add_word)) }
            }
        }
        items(words, key = { it.id }) { word ->
            Row(Modifier.fillMaxWidth().clickable { onEdit(word) }.padding(vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(word.surface, style = MaterialTheme.typography.bodyLarge)
                    Text(word.replacement, style = MaterialTheme.typography.bodyMedium, color = hoshiSurfaces.muted)
                }
                Text(stringResource(if (word.bookId == null) R.string.tts_global else R.string.tts_book),
                    style = MaterialTheme.typography.labelSmall, color = hoshiSurfaces.muted)
                Icon(Icons.Rounded.ChevronRight, null, Modifier.size(18.dp), tint = hoshiSurfaces.muted)
            }
            HorizontalDivider()
        }
    }
}

@Composable
internal fun TtsChoice(
    label: String, value: String, selected: String, choices: List<TtsChoiceOption>,
    supporting: String? = null, singleLine: Boolean = false, onSelected: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val valueMaxWidth = (maxWidth * .65f - 32.dp).coerceAtLeast(0.dp)
        Row(Modifier.fillMaxWidth().clickable { expanded = true }.heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
            Column(Modifier.widthIn(max = valueMaxWidth), verticalArrangement = Arrangement.spacedBy(3.dp), horizontalAlignment = Alignment.End) {
                Text(value, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.End,
                    maxLines = if (singleLine) 1 else 2,
                    overflow = if (singleLine) TextOverflow.MiddleEllipsis else TextOverflow.Ellipsis)
                supporting?.let { Text(it, style = MaterialTheme.typography.labelSmall, textAlign = TextAlign.End, color = hoshiSurfaces.muted) }
            }
            Box(Modifier.heightIn(min = 48.dp), contentAlignment = Alignment.Center) {
                Icon(Icons.Rounded.ExpandMore, null, Modifier.size(20.dp), tint = hoshiSurfaces.muted)
                DropdownMenu(expanded, onDismissRequest = { expanded = false }, modifier = Modifier.widthIn(max = 320.dp)) {
                    choices.forEach { choice ->
                        DropdownMenuItem(text = {
                            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text(choice.label, style = MaterialTheme.typography.bodyMedium)
                                choice.supporting?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = hoshiSurfaces.muted) }
                            }
                        },
                            trailingIcon = { if (choice.id == selected) Icon(Icons.Rounded.Check, null, Modifier.size(18.dp)) },
                            onClick = { expanded = false; onSelected(choice.id) })
                    }
                }
            }
        }
    }
}

internal data class TtsChoiceOption(val id: String, val label: String, val supporting: String? = null)

@Composable
fun TtsWordEditor(viewModel: TtsViewModel, initial: TtsWord, bookId: String?, sentence: TtsSentence?, onDismiss: () -> Unit) {
    var surface by remember(initial.id) { mutableStateOf(initial.surface) }
    var replacement by remember(initial.id) { mutableStateOf(initial.replacement) }
    var global by remember(initial.id) { mutableStateOf(initial.bookId == null) }
    var error by remember { mutableStateOf<TtsWordError?>(null) }
    var saving by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val words by viewModel.preferences.collectAsStateWithLifecycle()
    val busy by viewModel.busy.collectAsStateWithLifecycle()
    val previewError by viewModel.error.collectAsStateWithLifecycle()
    fun value() = initial.copy(surface = surface.trim(), replacement = replacement, bookId = if (global) null else bookId)
    DisposableEffect(Unit) { onDispose { viewModel.stopPreview() } }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(stringResource(R.string.tts_replacement_title)) }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(surface, { surface = it }, modifier = Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.tts_surface)) }, singleLine = true,
                keyboardOptions = hoshiContentKeyboardOptions(ContentLanguageProfile.Japanese), colors = hoshiOutlinedTextFieldColors())
            OutlinedTextField(replacement, { replacement = it }, modifier = Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.tts_replacement)) }, maxLines = 4,
                keyboardOptions = hoshiContentKeyboardOptions(ContentLanguageProfile.Japanese), colors = hoshiOutlinedTextFieldColors())
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                listOf(true, false).forEachIndexed { index, allBooks ->
                    SegmentedButton(
                        selected = global == allBooks,
                        onClick = { global = allBooks },
                        enabled = allBooks || bookId != null,
                        shape = SegmentedButtonDefaults.itemShape(index, 2),
                        colors = SegmentedButtonDefaults.colors(
                            activeContainerColor = hoshiSurfaces.selected,
                            activeContentColor = hoshiSurfaces.onSelected,
                            inactiveContainerColor = hoshiSurfaces.nested,
                            inactiveContentColor = hoshiSurfaces.muted,
                        ),
                        icon = {},
                    ) {
                        Text(stringResource(if (allBooks) R.string.tts_global else R.string.tts_book))
                    }
                }
            }
            sentence?.let { Text(it.text) }
            HoshiButton(onClick = {
                error = validateTtsWord(value(), words.words.filterNot { it.id == initial.id })
                if (error == null) viewModel.preview(sentence ?: TtsSentence(0, 0, 0, surface), bookId, value())
            }, enabled = !busy && !saving, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                if (busy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                else Icon(Icons.AutoMirrored.Rounded.VolumeUp, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.tts_preview))
            }
            error?.let { Text(stringResource(when (it) { TtsWordError.Surface -> R.string.tts_invalid_surface; TtsWordError.Replacement -> R.string.tts_invalid_replacement; TtsWordError.Duplicate -> R.string.tts_duplicate; TtsWordError.Storage -> R.string.tts_storage_error }), color = MaterialTheme.colorScheme.error) }
            previewError?.let { Text(stringResource(it.messageRes()), color = MaterialTheme.colorScheme.error) }
        }
    }, confirmButton = {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            if (words.words.any { it.id == initial.id }) {
                TextButton(
                    enabled = !saving,
                    onClick = { viewModel.delete(initial.id); onDismiss() },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                ) { Text(stringResource(R.string.action_delete)) }
            }
            Spacer(Modifier.weight(1f))
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
            TextButton(enabled = !saving, onClick = { scope.launch { saving = true; try { error = viewModel.save(value()); if (error == null) onDismiss() } finally { saving = false } } }) { Text(stringResource(R.string.action_save)) }
        }
    })
}

internal fun TtsFailure.messageRes(): Int = when (this) {
    TtsFailure.Configuration -> R.string.tts_configuration_error
    TtsFailure.Authentication -> R.string.tts_authentication_error
    TtsFailure.RateLimited -> R.string.tts_rate_limit_error
    TtsFailure.Network -> R.string.tts_network_error
    TtsFailure.InvalidResponse -> R.string.tts_response_error
    TtsFailure.Storage -> R.string.tts_storage_error
    TtsFailure.NoEngine -> R.string.tts_no_engine
    TtsFailure.MissingJapanese -> R.string.tts_missing_japanese
    TtsFailure.EngineUnavailable -> R.string.tts_engine_unavailable
    TtsFailure.Timeout -> R.string.tts_timeout
    TtsFailure.EmptyText -> R.string.tts_empty_text
    TtsFailure.Synthesis, TtsFailure.AudioUnavailable -> R.string.tts_failed
}
