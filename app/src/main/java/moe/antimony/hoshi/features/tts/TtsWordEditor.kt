package moe.antimony.hoshi.features.tts

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import moe.antimony.hoshi.R
import moe.antimony.hoshi.content.ContentLanguageProfile
import moe.antimony.hoshi.features.reader.ReaderSheetSwitchRow
import moe.antimony.hoshi.ui.HoshiAlertDialog
import moe.antimony.hoshi.ui.HoshiButton
import moe.antimony.hoshi.ui.hoshiContentKeyboardOptions
import moe.antimony.hoshi.ui.hoshiOutlinedTextFieldColors
import moe.antimony.hoshi.ui.theme.hoshiSurfaces

@Composable
fun TtsWordEditor(viewModel: TtsViewModel, initial: TtsWord, bookId: String?, sentence: TtsSentence?, onDismiss: () -> Unit) {
    val preferences by viewModel.preferences.collectAsStateWithLifecycle()
    val busy by viewModel.busy.collectAsStateWithLifecycle()
    val previewError by viewModel.error.collectAsStateWithLifecycle()
    DisposableEffect(Unit) { onDispose { viewModel.stopPreview() } }
    TtsWordEditorDialog(initial, bookId, sentence, preferences, busy, previewError,
        onPreview = { preview -> viewModel.preview(preview.sentence, bookId, snapshot = preview.preferences) },
        onSave = viewModel::save, onDelete = viewModel::delete, onDismiss = onDismiss)
}

@Composable
internal fun TtsWordEditorDialog(
    initial: TtsWord, bookId: String?, sentence: TtsSentence?, preferences: TtsPreferences,
    busy: Boolean, previewError: TtsFailure?, onPreview: (TtsWordPreview) -> Unit,
    onSave: suspend (TtsWord) -> TtsWordError?, onDelete: (String) -> Unit, onDismiss: () -> Unit,
    previewDispatcher: CoroutineDispatcher = Dispatchers.Default,
) {
    var surface by remember(initial.id) { mutableStateOf(initial.surface) }
    var replacement by remember(initial.id) { mutableStateOf(initial.replacement) }
    var regex by remember(initial.id) { mutableStateOf(initial.regex) }
    var global by remember(initial.id) { mutableStateOf(initial.bookId == null) }
    var sample by remember(initial.id) { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<TtsWordError?>(null) }
    var saving by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val draft = initial.copy(surface = if (regex) surface else surface.trim(), replacement = replacement,
        bookId = if (global) null else bookId, regex = regex)
    val original = sentence ?: TtsSentence(0, 0, 0, sample ?: if (regex) "" else surface)
    val prepared by produceState<TtsWordPreview?>(null, draft, original, preferences, bookId) {
        value = withContext(previewDispatcher) { prepareTtsWordPreview(draft, original, bookId, preferences) }
    }
    // Never enable playback of an old draft while the new text is still being computed.
    val preview = prepared?.takeIf { it.word == draft && it.sentence == original && it.sourcePreferences == preferences }
    LaunchedEffect(draft) { error = null }
    // Open with a complete initial layout. Later computations retain the last
    // result for display, while only the current snapshot can enable playback.
    val displayedPreview = prepared ?: return
    HoshiAlertDialog(onDismissRequest = onDismiss, title = { Text(stringResource(R.string.tts_replacement_title)) }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            ReaderSheetSwitchRow(stringResource(R.string.tts_regex_enabled), regex, { regex = it })
            OutlinedTextField(surface, { surface = it }, modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(if (regex) R.string.tts_regex_pattern else R.string.tts_surface)) }, singleLine = true,
                keyboardOptions = hoshiContentKeyboardOptions(ContentLanguageProfile.Japanese), colors = hoshiOutlinedTextFieldColors())
            OutlinedTextField(replacement, { replacement = it }, modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.tts_replacement)) }, maxLines = 4,
                keyboardOptions = hoshiContentKeyboardOptions(ContentLanguageProfile.Japanese), colors = hoshiOutlinedTextFieldColors())
            if (regex) Text(stringResource(R.string.tts_regex_help), style = MaterialTheme.typography.bodySmall, color = hoshiSurfaces.muted)
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                listOf(true, false).forEachIndexed { index, allBooks ->
                    SegmentedButton(selected = global == allBooks, onClick = { global = allBooks }, enabled = allBooks || bookId != null,
                        shape = SegmentedButtonDefaults.itemShape(index, 2),
                        colors = SegmentedButtonDefaults.colors(activeContainerColor = MaterialTheme.colorScheme.primary,
                            activeContentColor = MaterialTheme.colorScheme.onPrimary, inactiveContainerColor = hoshiSurfaces.nested,
                            inactiveContentColor = hoshiSurfaces.muted), icon = {},
                    ) { Text(stringResource(if (allBooks) R.string.tts_global else R.string.tts_book)) }
                }
            }
            Text(stringResource(R.string.tts_replacement_before), style = MaterialTheme.typography.labelLarge, color = hoshiSurfaces.muted)
            if (sentence != null) SelectionContainer { Text(sentence.text) }
            else OutlinedTextField(original.text, { sample = it }, modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.tts_replacement_sample)) },
                placeholder = { Text(if (regex) stringResource(R.string.tts_replacement_sample_hint) else surface) },
                keyboardOptions = hoshiContentKeyboardOptions(ContentLanguageProfile.Japanese), colors = hoshiOutlinedTextFieldColors())
            Text(stringResource(R.string.tts_replacement_after), style = MaterialTheme.typography.labelLarge, color = hoshiSurfaces.muted)
            displayedPreview.text?.let { SelectionContainer { Text(it) } }
            (error ?: displayedPreview.error)?.let { Text(stringResource(it.messageRes()), color = MaterialTheme.colorScheme.error) }
            HoshiButton(onClick = { preview?.let(onPreview) }, enabled = !busy && !saving && preview?.text?.isNotBlank() == true,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                if (busy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                else Icon(Icons.AutoMirrored.Rounded.VolumeUp, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.tts_preview))
            }
            previewError?.let { Text(stringResource(it.messageRes()), color = MaterialTheme.colorScheme.error) }
        }
    }, confirmButton = {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            if (preferences.words.any { it.id == initial.id }) TextButton(enabled = !saving,
                onClick = { onDelete(initial.id); onDismiss() }, colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
            ) { Text(stringResource(R.string.action_delete)) }
            Spacer(Modifier.weight(1f))
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
            TextButton(enabled = !saving, onClick = { scope.launch {
                saving = true
                try { error = onSave(draft); if (error == null) onDismiss() } finally { saving = false }
            } }) { Text(stringResource(R.string.action_save)) }
        }
    })
}

internal fun TtsWordError.messageRes(): Int = when (this) {
    TtsWordError.Surface -> R.string.tts_invalid_surface
    TtsWordError.Replacement -> R.string.tts_invalid_replacement
    TtsWordError.Duplicate -> R.string.tts_duplicate
    TtsWordError.RegexPattern -> R.string.tts_invalid_regex
    TtsWordError.RegexReplacement -> R.string.tts_invalid_regex_replacement
    TtsWordError.Storage -> R.string.tts_storage_error
}
