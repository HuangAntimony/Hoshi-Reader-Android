package moe.antimony.hoshi.features.tts

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import moe.antimony.hoshi.R
import moe.antimony.hoshi.content.ContentLanguageProfile
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TtsWordEditorDialog(
    initial: TtsWord, bookId: String?, sentence: TtsSentence?, preferences: TtsPreferences,
    busy: Boolean, previewError: TtsFailure?, onPreview: (TtsWordPreview) -> Unit,
    onSave: suspend (TtsWord) -> TtsWordError?, onDelete: (String) -> Unit, onDismiss: () -> Unit,
    previewDispatcher: CoroutineDispatcher = Dispatchers.Default,
) {
    var surface by remember(initial.id) { mutableStateOf(initial.surface) }
    val replacementState = remember(initial.id) { TextFieldState(initial.replacement) }
    val replacement = replacementState.text.toString()
    var regex by remember(initial.id) { mutableStateOf(initial.regex) }
    var global by remember(initial.id) { mutableStateOf(initial.bookId == null) }
    var sample by remember(initial.id) { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<TtsWordError?>(null) }
    var saving by remember { mutableStateOf(false) }
    var surfaceEdited by remember(initial.id) { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val draft = initial.copy(surface = if (regex) surface else surface.trim(), replacement = replacement,
        bookId = if (global) null else bookId, regex = regex)
    val original = sentence ?: TtsSentence(0, 0, 0, sample ?: if (regex) "" else surface)
    val prepared by produceState<TtsWordPreview?>(null, draft, original, preferences, bookId) {
        value = withContext(previewDispatcher) { prepareTtsWordPreview(draft, original, bookId, preferences) }
    }
    // Playback can only use the current snapshot; a click during computation waits for it.
    val preview = prepared?.takeIf { it.word == draft && it.sentence == original && it.sourcePreferences == preferences }
    var previewRequested by remember(draft, original, preferences) { mutableStateOf(false) }
    LaunchedEffect(preview, previewRequested, busy, saving) {
        if (previewRequested && preview != null) {
            previewRequested = false
            if (!busy && !saving && !preview.text.isNullOrBlank()) onPreview(preview)
        }
    }
    LaunchedEffect(draft) { error = null }
    // Open with a complete initial layout. Later computations retain the last
    // result for display; playback still waits for the current snapshot.
    val displayedPreview = prepared ?: return
    val validation = error ?: displayedPreview.error?.takeUnless { it == TtsWordError.Surface && !surfaceEdited }
    HoshiAlertDialog(onDismissRequest = onDismiss, title = { Text(stringResource(R.string.tts_replacement_title)) }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.tts_replacement_rule), Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                Text(stringResource(R.string.tts_regex_enabled), style = MaterialTheme.typography.bodySmall)
                Switch(checked = regex, onCheckedChange = { regex = it })
            }
            OutlinedTextField(surface, { surface = it; surfaceEdited = true }, modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(if (regex) R.string.tts_regex_pattern else R.string.tts_surface)) }, singleLine = true,
                isError = validation == TtsWordError.Surface || validation == TtsWordError.RegexPattern,
                keyboardOptions = hoshiContentKeyboardOptions(ContentLanguageProfile.Japanese), colors = hoshiOutlinedTextFieldColors())
            OutlinedTextField(state = replacementState, modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.tts_replacement)) },
                labelPosition = TextFieldLabelPosition.Attached(alwaysMinimize = true),
                lineLimits = TextFieldLineLimits.MultiLine(maxHeightInLines = 4),
                placeholder = { Text(stringResource(R.string.tts_replacement_delete_hint)) },
                isError = validation == TtsWordError.RegexReplacement,
                keyboardOptions = hoshiContentKeyboardOptions(ContentLanguageProfile.Japanese), colors = hoshiOutlinedTextFieldColors())
            if (regex) Text(stringResource(R.string.tts_regex_help), style = MaterialTheme.typography.bodySmall, color = hoshiSurfaces.muted)
            validation?.let { Text(stringResource(it.messageRes()), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.tts_replacement_scope), Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                SingleChoiceSegmentedButtonRow {
                    listOf(true, false).forEachIndexed { index, allBooks ->
                        SegmentedButton(selected = global == allBooks, onClick = { global = allBooks }, enabled = allBooks || bookId != null,
                            shape = SegmentedButtonDefaults.itemShape(index, 2),
                            colors = SegmentedButtonDefaults.colors(activeContainerColor = MaterialTheme.colorScheme.primary,
                                activeContentColor = MaterialTheme.colorScheme.onPrimary, inactiveContainerColor = Color.Transparent,
                                inactiveContentColor = hoshiSurfaces.content), icon = {},
                        ) { Text(stringResource(if (allBooks) R.string.tts_global else R.string.tts_book)) }
                    }
                }
            }
            HorizontalDivider(color = hoshiSurfaces.outline)
            Text(stringResource(R.string.tts_replacement_preview), style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold, color = hoshiSurfaces.content)
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(stringResource(R.string.tts_replacement_before), style = MaterialTheme.typography.labelMedium, color = hoshiSurfaces.muted)
                if (sentence != null) SelectionContainer {
                    Text(sentence.text, style = MaterialTheme.typography.bodyMedium, color = hoshiSurfaces.muted)
                }
                else OutlinedTextField(original.text, { sample = it }, modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.tts_replacement_sample)) },
                    placeholder = { Text(if (regex) stringResource(R.string.tts_replacement_sample_hint) else surface) },
                    keyboardOptions = hoshiContentKeyboardOptions(ContentLanguageProfile.Japanese), colors = hoshiOutlinedTextFieldColors())
            }
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(stringResource(R.string.tts_replacement_after), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                SelectionContainer {
                    Text(displayedPreview.text?.takeIf { it.isNotBlank() }
                        ?: stringResource(if (displayedPreview.error == null) R.string.tts_replacement_empty_preview else R.string.tts_replacement_pending_preview),
                        style = MaterialTheme.typography.bodyLarge, color = if (displayedPreview.text.isNullOrBlank()) hoshiSurfaces.muted else hoshiSurfaces.content)
                }
            }
            HoshiButton(onClick = { if (preview != null) onPreview(preview) else previewRequested = true },
                enabled = !busy && !saving && !displayedPreview.text.isNullOrBlank(),
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                colors = ButtonDefaults.filledTonalButtonColors()) {
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
            HoshiButton(enabled = !saving && displayedPreview.error == null, onClick = { scope.launch {
                saving = true
                try { error = onSave(draft); if (error == null) onDismiss() } finally { saving = false }
            } }) { Text(stringResource(R.string.action_save)) }
        }
    })
}

internal fun TtsWordError.messageRes(): Int = when (this) {
    TtsWordError.Surface -> R.string.tts_invalid_surface
    TtsWordError.Duplicate -> R.string.tts_duplicate
    TtsWordError.RegexPattern -> R.string.tts_invalid_regex
    TtsWordError.RegexReplacement -> R.string.tts_invalid_regex_replacement
    TtsWordError.Storage -> R.string.tts_storage_error
}
