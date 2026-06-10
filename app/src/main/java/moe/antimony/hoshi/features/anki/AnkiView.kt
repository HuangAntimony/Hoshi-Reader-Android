package moe.antimony.hoshi.features.anki

import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import moe.antimony.hoshi.LocalHoshiUiDependencies
import moe.antimony.hoshi.R
import moe.antimony.hoshi.dictionary.DictionaryType
import moe.antimony.hoshi.features.settings.SettingsDetailScaffold
import moe.antimony.hoshi.ui.asString
import moe.antimony.hoshi.ui.hoshiOutlinedTextFieldColors
import moe.antimony.hoshi.ui.hoshiSingleLineTextFieldLineLimits
import moe.antimony.hoshi.ui.rememberSyncedTextFieldState

@Composable
fun AnkiView(
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val appContainer = LocalHoshiUiDependencies.current
    val viewModel: AnkiViewModel = hiltViewModel()
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val handlebarOptions by produceState(
        initialValue = AnkiHandlebarOptions.forTermDictionaries(emptyList()),
        key1 = appContainer.dictionaryRepository,
    ) {
        value = AnkiHandlebarOptions.forTermDictionaries(
            appContainer.dictionaryRepository
                .loadDictionaries(DictionaryType.Term)
                .map { it.index.title },
        )
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            viewModel.fetchConfiguration()
        } else {
            viewModel.showFetchPermissionDenied()
        }
    }
    val fetchAnki = {
        when (
            ankiFetchAction(
                backendKind = uiState.settings.backendKind,
                isAnkiDroidAvailable = viewModel.isAnkiDroidAvailable(),
                permissionGranted = ContextCompat.checkSelfPermission(
                    context,
                    AnkiDroidReadWritePermission,
                ) == PackageManager.PERMISSION_GRANTED,
            )
        ) {
            AnkiFetchAction.FetchConfiguration -> viewModel.fetchConfiguration()
            AnkiFetchAction.RequestPermission -> permissionLauncher.launch(AnkiDroidReadWritePermission)
            AnkiFetchAction.ShowApiUnavailable -> viewModel.showFetchApiUnavailable()
        }
    }

    SettingsDetailScaffold(
        title = stringResource(R.string.settings_anki),
        onClose = onClose,
        modifier = modifier,
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 16.dp),
        ) {
            item {
                AnkiCard {
                    ListItem(
                        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surface),
                        headlineContent = { Text(if (uiState.settings.backendKind == AnkiBackendKind.AnkiConnect) "AnkiConnect" else "AnkiDroid") },
                        supportingContent = {
                            Column {
                                Text(
                                    uiState.errorMessage?.asString() ?: if (uiState.settings.backendKind == AnkiBackendKind.AnkiConnect) {
                                        stringResource(R.string.anki_fetch_decks_ankiconnect)
                                    } else {
                                        stringResource(R.string.anki_fetch_decks_ankidroid)
                                    },
                                )
                                if (uiState.errorAction == AnkiErrorAction.OpenPermissionSettings) {
                                    TextButton(
                                        onClick = {
                                            runCatching {
                                                context.startActivity(ankiPermissionSettingsIntent(context.packageName))
                                            }
                                        },
                                    ) {
                                        Text(stringResource(R.string.anki_open_app_settings))
                                    }
                                }
                            }
                        },
                        trailingContent = {
                            TextButton(onClick = fetchAnki, enabled = !uiState.isFetching) {
                                Text(
                                    if (uiState.isFetching) {
                                        stringResource(R.string.anki_fetching)
                                    } else {
                                        stringResource(R.string.action_fetch)
                                    },
                                )
                            }
                        },
                    )
                }
            }
            item {
                AnkiCard {
                    AnkiDeckRow(uiState = uiState, onSelect = viewModel::selectDeck)
                    AnkiDivider()
                    AnkiNoteTypeRow(uiState = uiState, onSelect = viewModel::selectNoteType)
                }
            }
            item {
                AnkiCard {
                    AnkiSwitchRow(
                        label = stringResource(R.string.anki_allow_duplicates),
                        checked = uiState.settings.allowDupes,
                        onCheckedChange = viewModel::updateAllowDupes,
                    )
                    AnkiDivider()
                    AnkiSwitchRow(
                        label = stringResource(R.string.anki_check_duplicates_all_models),
                        checked = uiState.settings.checkDuplicatesAcrossAllModels,
                        onCheckedChange = viewModel::updateCheckDuplicatesAcrossAllModels,
                    )
                    AnkiDivider()
                    AnkiDuplicateScopeRow(
                        scope = uiState.settings.duplicateScope,
                        onSelect = viewModel::updateDuplicateScope,
                    )
                    AnkiDivider()
                    AnkiSwitchRow(
                        label = stringResource(R.string.anki_compact_glossaries),
                        checked = uiState.settings.compactGlossaries,
                        onCheckedChange = viewModel::updateCompactGlossaries,
                    )
                    if (uiState.settings.backendKind == AnkiBackendKind.AnkiDroid) {
                        AnkiDivider()
                        AnkiSwitchRow(
                            label = stringResource(R.string.anki_droid_force_sync),
                            supportingText = stringResource(R.string.anki_droid_force_sync_description),
                            checked = uiState.settings.ankiDroidForceSync,
                            onCheckedChange = viewModel::updateAnkiDroidForceSync,
                        )
                    }
                }
            }
            val selectedNoteType = uiState.selectedNoteType
            if (selectedNoteType != null) {
                item {
                    Text(
                        text = stringResource(R.string.anki_fields),
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(top = 18.dp, bottom = 8.dp),
                    )
                }
                items(
                    items = selectedNoteType.fields,
                    key = { field -> field },
                    contentType = { "anki-field-mapping" },
                ) { field ->
                    AnkiFieldMappingRow(
                        field = field,
                        value = uiState.settings.fieldMappings[field].orEmpty(),
                        handlebarOptions = handlebarOptions,
                        onValueChange = { viewModel.updateFieldMapping(field, it) },
                    )
                }
                item {
                    AnkiTextValueRow(
                        label = stringResource(R.string.anki_tags),
                        value = uiState.settings.tags,
                        onValueChange = viewModel::updateTags,
                        dialogLabel = stringResource(R.string.anki_tags),
                    )
                }
            }
        }
    }
}

internal enum class AnkiFetchAction {
    FetchConfiguration,
    RequestPermission,
    ShowApiUnavailable,
}

internal fun ankiFetchAction(
    backendKind: AnkiBackendKind = AnkiBackendKind.AnkiDroid,
    isAnkiDroidAvailable: Boolean,
    permissionGranted: Boolean,
): AnkiFetchAction =
    when {
        backendKind == AnkiBackendKind.AnkiConnect -> AnkiFetchAction.FetchConfiguration
        !isAnkiDroidAvailable -> AnkiFetchAction.ShowApiUnavailable
        permissionGranted -> AnkiFetchAction.FetchConfiguration
        else -> AnkiFetchAction.RequestPermission
    }

internal fun ankiPermissionSettingsIntent(packageName: String): Intent =
    Intent(
        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
        Uri.parse(ankiPermissionSettingsUri(packageName)),
    )

internal fun ankiPermissionSettingsUri(packageName: String): String = "package:$packageName"

@Composable
private fun AnkiDeckRow(
    uiState: AnkiUiState,
    onSelect: (AnkiDeck) -> Unit,
) {
    AnkiDropdownRow(
        label = stringResource(R.string.anki_deck),
        value = uiState.settings.selectedDeckName ?: stringResource(R.string.none),
        enabled = uiState.availableDecks.isNotEmpty(),
        items = uiState.availableDecks,
        itemLabel = { it.name },
        onSelect = onSelect,
    )
}

@Composable
private fun AnkiNoteTypeRow(
    uiState: AnkiUiState,
    onSelect: (AnkiNoteType) -> Unit,
) {
    AnkiDropdownRow(
        label = stringResource(R.string.anki_model),
        value = uiState.settings.selectedNoteTypeName ?: stringResource(R.string.none),
        enabled = uiState.availableNoteTypes.isNotEmpty(),
        items = uiState.availableNoteTypes,
        itemLabel = { it.name },
        onSelect = onSelect,
    )
}

@Composable
private fun AnkiDuplicateScopeRow(
    scope: AnkiDuplicateScope,
    onSelect: (AnkiDuplicateScope) -> Unit,
) {
    AnkiDropdownRow(
        label = stringResource(R.string.anki_duplicate_check_scope),
        value = stringResource(scope.labelRes),
        enabled = true,
        items = AnkiDuplicateScope.entries,
        itemLabel = { stringResource(it.labelRes) },
        onSelect = onSelect,
    )
}

@Composable
private fun <T> AnkiDropdownRow(
    label: String,
    value: String,
    enabled: Boolean,
    items: List<T>,
    itemLabel: @Composable (T) -> String,
    onSelect: (T) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    ListItem(
        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surface),
        headlineContent = { Text(label) },
        supportingContent = { Text(value) },
        trailingContent = {
            TextButton(onClick = { expanded = true }, enabled = enabled) {
                Text(stringResource(R.string.action_choose))
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                items.forEach { item ->
                    DropdownMenuItem(
                        text = { Text(itemLabel(item)) },
                        onClick = {
                            expanded = false
                            onSelect(item)
                        },
                    )
                }
            }
        },
    )
}

@Composable
private fun AnkiSwitchRow(
    label: String,
    supportingText: String? = null,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    ListItem(
        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surface),
        headlineContent = { Text(label) },
        supportingContent = supportingText?.let { text -> { Text(text) } },
        trailingContent = { Switch(checked = checked, onCheckedChange = onCheckedChange) },
    )
}

@Composable
private fun AnkiFieldMappingRow(
    field: String,
    value: String,
    handlebarOptions: List<String>,
    onValueChange: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    AnkiTextValueRow(
        label = field,
        value = value,
        onValueChange = onValueChange,
        dialogLabel = stringResource(R.string.anki_handlebar),
        trailingContent = {
            Column {
                TextButton(onClick = { expanded = true }) { Text("{}") }
                DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                    handlebarOptions.forEach { option ->
                        DropdownMenuItem(
                            text = { Text(option) },
                            onClick = {
                                expanded = false
                                onValueChange(if (option == "-") "" else option)
                            },
                        )
                    }
                }
            }
        },
    )
}

@Composable
private fun AnkiTextValueRow(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    dialogLabel: String,
    trailingContent: (@Composable () -> Unit)? = null,
) {
    var editing by remember { mutableStateOf(false) }
    val noneLabel = stringResource(R.string.none)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { editing = true }
            .padding(horizontal = 16.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = value.ifBlank { noneLabel },
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        trailingContent?.invoke()
    }
    AnkiDivider()
    if (editing) {
        AnkiTextValueDialog(
            title = label,
            textFieldLabel = dialogLabel,
            value = value,
            onDismiss = { editing = false },
            onSave = { newValue ->
                editing = false
                onValueChange(newValue)
            },
        )
    }
}

@Composable
private fun AnkiTextValueDialog(
    title: String,
    textFieldLabel: String,
    value: String,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit,
) {
    var draft by remember(title, value) { mutableStateOf(value) }
    val draftScrollState = rememberScrollState()
    val draftState = rememberSyncedTextFieldState(
        value = draft,
        onValueChange = { draft = it },
        scrollState = draftScrollState,
    )
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                state = draftState,
                label = { Text(textFieldLabel) },
                lineLimits = hoshiSingleLineTextFieldLineLimits(),
                scrollState = draftScrollState,
                colors = hoshiOutlinedTextFieldColors(),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                onKeyboardAction = { onSave(draftState.text.toString()) },
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            Button(onClick = { onSave(draft) }) {
                Text(stringResource(R.string.action_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_cancel))
            }
        },
    )
}

@Composable
private fun AnkiCard(content: @Composable () -> Unit) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 12.dp),
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        tonalElevation = 0.dp,
    ) {
        Column(content = { content() })
    }
}

@Composable
private fun AnkiDivider() {
    HorizontalDivider(
        modifier = Modifier.padding(horizontal = 16.dp),
        color = MaterialTheme.colorScheme.outlineVariant,
    )
}

internal object AnkiHandlebarOptions {
    private val CoreOptions = listOf(
        "-",
        "{expression}",
        "{reading}",
        "{furigana-plain}",
        "{audio}",
        "{glossary}",
        "{glossary-brief}",
        "{glossary-first}",
        "{selected-glossary}",
        "{selected-glossary-fallback}",
        "{popup-selection-text}",
        "{sentence}",
        "{frequencies}",
        "{frequency-harmonic-rank}",
        "{pitch-accent-positions}",
        "{pitch-accent-categories}",
        "{document-title}",
        "{book-cover}",
        "{sasayaki-audio}",
    )

    fun forTermDictionaries(dictionaryNames: List<String>): List<String> =
        CoreOptions + dictionaryNames
            .distinct()
            .map { dictionaryName -> "{single-glossary-$dictionaryName}" }
}

private const val AnkiDroidReadWritePermission = "com.ichi2.anki.permission.READ_WRITE_DATABASE"
