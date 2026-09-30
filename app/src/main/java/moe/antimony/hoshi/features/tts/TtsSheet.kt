package moe.antimony.hoshi.features.tts

import android.content.Intent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import moe.antimony.hoshi.ui.HoshiAlertDialog as AlertDialog
import moe.antimony.hoshi.ui.HoshiButton as Button
import moe.antimony.hoshi.ui.HoshiDropdownMenu as DropdownMenu
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import java.util.UUID
import kotlinx.coroutines.launch
import moe.antimony.hoshi.R
import moe.antimony.hoshi.features.settings.SettingsDetailScaffold

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TtsSheet(
    viewModel: TtsViewModel,
    bookId: String,
    onStart: () -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        TtsPanel(viewModel, bookId, onStart, Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(20.dp))
    }
}

@Composable
fun TtsSettingsView(onClose: () -> Unit, modifier: Modifier = Modifier) {
    val viewModel: TtsViewModel = hiltViewModel()
    SettingsDetailScaffold(title = stringResource(R.string.tts_title), onClose = onClose, modifier = modifier) { padding ->
        TtsPanel(viewModel, null, null, Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(20.dp))
    }
}

@Composable
private fun TtsPanel(viewModel: TtsViewModel, bookId: String?, onStart: (() -> Unit)?, modifier: Modifier) {
    val preferences by viewModel.preferences.collectAsStateWithLifecycle()
    val catalog by viewModel.catalog.collectAsStateWithLifecycle()
    val engines by viewModel.engines.collectAsStateWithLifecycle()
    val playback by viewModel.playback.state.collectAsStateWithLifecycle()
    val busy by viewModel.busy.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()
    var dictionary by remember { mutableStateOf(false) }
    var editor by remember { mutableStateOf<TtsWord?>(null) }
    val context = LocalContext.current
    val networkLabel = stringResource(R.string.tts_network)
    val offlineLabel = stringResource(R.string.tts_offline)
    val sample = stringResource(R.string.tts_sample)
    val visibleWords = preferences.words.filter { bookId == null || it.bookId == null || it.bookId == bookId }
    val automaticLabel = stringResource(R.string.tts_system_default)
    val selectedVoice = catalog?.voices?.let { voices ->
        if (preferences.settings.voice.isBlank()) voices.firstOrNull() else voices.firstOrNull { it.id == preferences.settings.voice }
    }
    val voiceLabel = (preferences.settings.voice.ifBlank { automaticLabel }) +
        (selectedVoice?.let { " · " + if (it.network) networkLabel else offlineLabel }.orEmpty())
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.refresh() }
    DisposableEffect(viewModel) { onDispose { viewModel.stopPreview() } }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(stringResource(if (dictionary) R.string.tts_dictionary else R.string.tts_title), style = MaterialTheme.typography.headlineSmall)
        if (dictionary) {
            TextButton(onClick = { dictionary = false }) { Text(stringResource(R.string.action_back)) }
            TextButton(onClick = { editor = TtsWord(UUID.randomUUID().toString(), "", "", bookId) }) { Text(stringResource(R.string.tts_add_word)) }
            visibleWords.forEach { word ->
                ListItem(headlineContent = { Text(word.surface) }, supportingContent = { Text(word.reading) },
                    trailingContent = { Text(stringResource(if (word.bookId == null) R.string.tts_global else R.string.tts_book)) },
                    modifier = Modifier.clickable { editor = word })
            }
            if (visibleWords.isEmpty()) Text(stringResource(R.string.tts_dictionary_empty))
        } else {
            if (onStart != null) {
                playback.sentence?.let { Text(it.text, style = MaterialTheme.typography.bodyLarge) }
                if (playback.preparing) LinearProgressIndicator(Modifier.fillMaxWidth())
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    TextButton(onClick = viewModel.playback::previous, enabled = playback.index > 0) { Text(stringResource(R.string.tts_previous)) }
                    Button(onClick = { if (playback.playing) viewModel.playback.pause() else if (playback.started) viewModel.playback.play() else onStart() }, enabled = !busy && playback.sentences.isNotEmpty()) {
                        Text(stringResource(if (playback.playing) R.string.tts_pause else R.string.tts_play))
                    }
                    TextButton(onClick = viewModel.playback::next, enabled = playback.index < playback.sentences.lastIndex) { Text(stringResource(R.string.tts_next)) }
                }
                TextButton(onClick = viewModel.playback::repeat, enabled = playback.sentence != null) { Text(stringResource(R.string.tts_repeat)) }
            }
            TtsChoice(stringResource(R.string.tts_engine), catalog?.let { c -> engines.firstOrNull { it.id == c.selectedEngine }?.label }.orEmpty(),
                listOf("" to stringResource(R.string.tts_system_default)) + engines.map { it.id to it.label }) {
                viewModel.settings(preferences.settings.copy(engine = it, voice = ""))
            }
            TtsChoice(stringResource(R.string.tts_voice), voiceLabel,
                listOf("" to stringResource(R.string.tts_system_default)) + catalog?.voices.orEmpty().map { voice ->
                    voice.id to (voice.id + if (voice.network) " · " + networkLabel else " · " + offlineLabel)
                }) { viewModel.settings(preferences.settings.copy(voice = it)) }
            var rate by remember(preferences.settings.rate) { mutableFloatStateOf(preferences.settings.rate) }
            Text(stringResource(R.string.tts_rate, rate))
            Slider(value = rate, onValueChange = { rate = it }, valueRange = .5f..3f, steps = 49,
                onValueChangeFinished = { viewModel.settings(preferences.settings.copy(rate = rate)) })
            TextButton(onClick = { viewModel.preview(TtsSentence(0, 0, 0, sample), bookId) }, enabled = !busy) { Text(stringResource(R.string.tts_preview)) }
            TextButton(onClick = { dictionary = true }) { Text(stringResource(R.string.tts_dictionary)) }
            Text(stringResource(R.string.tts_shared_behaviors), style = MaterialTheme.typography.bodySmall)
        }
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        (error ?: playback.error)?.let { failure ->
            Text(stringResource(failure.messageRes()), color = MaterialTheme.colorScheme.error)
            TextButton(onClick = { viewModel.dismissError(); viewModel.refresh() }) { Text(stringResource(R.string.tts_retry)) }
        }
        TextButton(onClick = {
            val intent = Intent("com.android.settings.TTS_SETTINGS")
            try { context.startActivity(intent) } catch (_: android.content.ActivityNotFoundException) {
                context.startActivity(Intent(android.provider.Settings.ACTION_SETTINGS))
            }
        }) { Text(stringResource(R.string.tts_system_settings)) }
        Spacer(Modifier.height(12.dp))
    }
    editor?.let { word -> TtsWordEditor(viewModel, word, bookId ?: word.bookId, null, { editor = null }) }
}

@Composable
private fun TtsChoice(label: String, value: String, choices: List<Pair<String, String>>, onSelected: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Column {
        Text(label, style = MaterialTheme.typography.labelLarge)
        Box {
            OutlinedButton(onClick = { expanded = true }) { Text(value.ifBlank { stringResource(R.string.tts_system_default) }) }
            DropdownMenu(expanded, onDismissRequest = { expanded = false }) {
                choices.forEach { (id, title) -> DropdownMenuItem(text = { Text(title) }, onClick = { expanded = false; onSelected(id) }) }
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
            OutlinedTextField(surface, { surface = it }, label = { Text(stringResource(R.string.tts_surface)) }, singleLine = true)
            OutlinedTextField(reading, { reading = it }, label = { Text(stringResource(R.string.tts_reading)) }, singleLine = true)
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
