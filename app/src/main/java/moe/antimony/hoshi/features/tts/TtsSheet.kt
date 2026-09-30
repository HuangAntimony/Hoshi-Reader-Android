package moe.antimony.hoshi.features.tts

import android.content.Intent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
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
import androidx.compose.ui.text.font.FontWeight
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
import moe.antimony.hoshi.features.reader.ReaderSheetCard
import moe.antimony.hoshi.features.reader.ReaderSheetSliderRow
import moe.antimony.hoshi.features.reader.ReaderSheetTabs
import moe.antimony.hoshi.features.reader.readerSheetStyle
import moe.antimony.hoshi.features.settings.SettingsDetailScaffold
import moe.antimony.hoshi.ui.HoshiAlertDialog as AlertDialog
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
private fun TtsPanel(viewModel: TtsViewModel, bookId: String?, onStart: (() -> Unit)?, modifier: Modifier) {
    val readingSettings by viewModel.readingSettings.collectAsStateWithLifecycle()
    var colorRow by remember { mutableStateOf<SasayakiColorRow?>(null) }
    val preferences by viewModel.preferences.collectAsStateWithLifecycle()
    val catalog by viewModel.catalog.collectAsStateWithLifecycle()
    val engines by viewModel.engines.collectAsStateWithLifecycle()
    val playback by viewModel.playback.state.collectAsStateWithLifecycle()
    val busy by viewModel.busy.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()
    var selectedTab by rememberSaveable { mutableIntStateOf(0) }
    var editor by remember { mutableStateOf<TtsWord?>(null) }
    val context = LocalContext.current
    val sample = stringResource(R.string.tts_sample)
    val automaticLabel = stringResource(R.string.tts_system_default)
    val selectedVoice = catalog?.voices?.let { voices ->
        if (preferences.settings.voice.isBlank()) voices.firstOrNull() else voices.firstOrNull { it.id == preferences.settings.voice }
    }
    val voiceLabel = preferences.settings.voice.ifBlank { automaticLabel }
    val networkLabel = stringResource(R.string.tts_network)
    val offlineLabel = stringResource(R.string.tts_offline)
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.refresh() }
    DisposableEffect(viewModel) { onDispose { viewModel.stopPreview() } }
    Column(modifier) {
        if (onStart != null) {
            TtsPlaybackControls(playback, busy,
                onPrevious = viewModel.playback::previous, onNext = viewModel.playback::next,
                onRepeat = viewModel.playback::repeat,
                onToggle = { if (playback.playing) viewModel.playback.pause() else if (playback.started) viewModel.playback.play() else onStart() })
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
                readingSettings?.let { settings ->
                    SharedReaderPlaybackSettings(settings, viewModel::readingSettings, { colorRow = it })
                } ?: LinearProgressIndicator(Modifier.fillMaxWidth().padding(20.dp))
                error?.let { Text(stringResource(it.messageRes()), color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(20.dp)) }
            }
        } else {
            Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(bottom = 28.dp)) {
                ReaderSheetCard {
                    TtsChoice(stringResource(R.string.tts_engine),
                        engines.firstOrNull { it.id == catalog?.selectedEngine }?.label
                            ?: engines.firstOrNull { it.id == preferences.settings.engine }?.label ?: automaticLabel,
                        preferences.settings.engine,
                        listOf("" to automaticLabel) + engines.map { it.id to it.label },
                    ) { viewModel.settings(preferences.settings.copy(engine = it, voice = "")) }
                    HorizontalDivider(Modifier.padding(vertical = 10.dp))
                    TtsChoice(stringResource(R.string.tts_voice), voiceLabel, preferences.settings.voice,
                        listOf("" to automaticLabel) + catalog?.voices.orEmpty().map {
                            it.id to (it.id + " · " + if (it.network) networkLabel else offlineLabel)
                        },
                        supporting = selectedVoice?.let { if (it.network) networkLabel else offlineLabel },
                    ) { viewModel.settings(preferences.settings.copy(voice = it)) }
                }
                var rate by remember(preferences.settings.rate) { mutableFloatStateOf(preferences.settings.rate) }
                ReaderSheetSliderRow(
                    label = stringResource(R.string.tts_rate_label), valueText = stringResource(R.string.tts_rate_value, rate),
                    value = rate, range = .5f..3f, steps = 49, onValueChange = { rate = it },
                    onValueChangeFinished = { viewModel.settings(preferences.settings.copy(rate = rate)) },
                )
                Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = { viewModel.preview(TtsSentence(0, 0, 0, sample), bookId) }, enabled = !busy) {
                        Icon(Icons.AutoMirrored.Rounded.VolumeUp, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(R.string.tts_preview))
                    }
                }
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 20.dp))
                (error ?: playback.error)?.let { failure ->
                    ReaderSheetCard {
                        Text(stringResource(failure.messageRes()), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
                        TextButton(onClick = { viewModel.dismissError(); viewModel.refresh() }) { Text(stringResource(R.string.tts_retry)) }
                    }
                }
                HorizontalDivider(Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
                Row(Modifier.fillMaxWidth().clickable {
                    try { context.startActivity(Intent("com.android.settings.TTS_SETTINGS")) }
                    catch (_: android.content.ActivityNotFoundException) { context.startActivity(Intent(android.provider.Settings.ACTION_SETTINGS)) }
                }.padding(horizontal = 20.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.tts_system_settings), modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                    Icon(Icons.AutoMirrored.Rounded.ArrowForward, null, Modifier.size(18.dp), tint = hoshiSurfaces.muted)
                }

            }
        }
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
                    Text(word.reading, style = MaterialTheme.typography.bodyMedium, color = hoshiSurfaces.muted)
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
private fun TtsChoice(
    label: String, value: String, selected: String, choices: List<Pair<String, String>>,
    supporting: String? = null, onSelected: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        Row(Modifier.fillMaxWidth().clickable { expanded = true }, verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(label, style = MaterialTheme.typography.labelMedium, color = hoshiSurfaces.muted)
                Text(value, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                supporting?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = hoshiSurfaces.muted) }
            }
            Icon(Icons.Rounded.UnfoldMore, null, Modifier.size(20.dp), tint = hoshiSurfaces.muted)
        }
        DropdownMenu(expanded, onDismissRequest = { expanded = false }) {
            choices.forEach { (id, title) ->
                DropdownMenuItem(text = { Text(title) },
                    trailingIcon = { if (id == selected) Icon(Icons.Rounded.Check, null, Modifier.size(18.dp)) },
                    onClick = { expanded = false; onSelected(id) })
            }
        }
    }
}

@Composable
fun TtsWordEditor(viewModel: TtsViewModel, initial: TtsWord, bookId: String?, sentence: TtsSentence?, onDismiss: () -> Unit) {
    var surface by remember(initial.id) { mutableStateOf(initial.surface) }
    var reading by remember(initial.id) { mutableStateOf(initial.reading) }
    var global by remember(initial.id) { mutableStateOf(initial.bookId == null) }
    var error by remember { mutableStateOf<TtsWordError?>(null) }
    var saving by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val words by viewModel.preferences.collectAsStateWithLifecycle()
    val busy by viewModel.busy.collectAsStateWithLifecycle()
    val previewError by viewModel.error.collectAsStateWithLifecycle()
    fun value() = initial.copy(surface = surface.trim(), reading = reading.trim(), bookId = if (global) null else bookId)
    DisposableEffect(Unit) { onDispose { viewModel.stopPreview() } }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(stringResource(R.string.tts_set_reading)) }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(surface, { surface = it }, label = { Text(stringResource(R.string.tts_surface)) }, singleLine = true, colors = hoshiOutlinedTextFieldColors())
            OutlinedTextField(reading, { reading = it }, label = { Text(stringResource(R.string.tts_reading)) }, singleLine = true, colors = hoshiOutlinedTextFieldColors())
            Row {
                FilterChip(global, { global = true }, label = { Text(stringResource(R.string.tts_global)) })
                Spacer(Modifier.width(8.dp))
                FilterChip(!global, { global = false }, enabled = bookId != null, label = { Text(stringResource(R.string.tts_book)) })
            }
            sentence?.let { Text(it.text) }
            TextButton(onClick = {
                error = validateTtsWord(value(), words.words.filterNot { it.id == initial.id })
                if (error == null) viewModel.preview(sentence ?: TtsSentence(0, 0, 0, surface), bookId, value())
            }, enabled = !busy) { Text(stringResource(R.string.tts_preview)) }
            error?.let { Text(stringResource(when (it) { TtsWordError.Surface -> R.string.tts_invalid_surface; TtsWordError.Reading -> R.string.tts_invalid_reading; TtsWordError.Duplicate -> R.string.tts_duplicate; TtsWordError.Storage -> R.string.tts_storage_error }), color = MaterialTheme.colorScheme.error) }
            previewError?.let { Text(stringResource(it.messageRes()), color = MaterialTheme.colorScheme.error) }
            if (words.words.any { it.id == initial.id }) TextButton(onClick = { viewModel.delete(initial.id); onDismiss() }) { Text(stringResource(R.string.action_delete)) }
        }
    }, confirmButton = {
        TextButton(enabled = !saving, onClick = { scope.launch { saving = true; try { error = viewModel.save(value()); if (error == null) onDismiss() } finally { saving = false } } }) { Text(stringResource(R.string.action_save)) }
    }, dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } })
}

internal fun TtsFailure.messageRes(): Int = when (this) {
    TtsFailure.Storage -> R.string.tts_storage_error
    TtsFailure.NoEngine -> R.string.tts_no_engine
    TtsFailure.MissingJapanese -> R.string.tts_missing_japanese
    TtsFailure.EngineUnavailable -> R.string.tts_engine_unavailable
    TtsFailure.Timeout -> R.string.tts_timeout
    TtsFailure.EmptyText -> R.string.tts_empty_text
    TtsFailure.Synthesis, TtsFailure.AudioUnavailable -> R.string.tts_failed
}
