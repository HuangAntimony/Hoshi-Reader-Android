package moe.antimony.hoshi.features.anki

import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import moe.antimony.hoshi.LocalHoshiAppContainer
import moe.antimony.hoshi.features.settings.SettingsDetailScaffold

@Composable
fun AnkiView(
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val appContainer = LocalHoshiAppContainer.current
    val viewModel: AnkiViewModel = viewModel(
        factory = remember(appContainer) {
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    AnkiViewModel(appContainer.ankiRepository) as T
            }
        },
    )
    val uiState by viewModel.uiState.collectAsState()
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) viewModel.fetchConfiguration()
    }
    val fetchAnki = {
        if (ContextCompat.checkSelfPermission(
                context,
                AnkiDroidReadWritePermission,
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            viewModel.fetchConfiguration()
        } else {
            permissionLauncher.launch(AnkiDroidReadWritePermission)
        }
    }

    SettingsDetailScaffold(
        title = "Anki",
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
                        headlineContent = { Text("AnkiDroid") },
                        supportingContent = {
                            Text(uiState.errorMessage ?: "Fetch decks and note types from AnkiDroid.")
                        },
                        trailingContent = {
                            TextButton(onClick = fetchAnki, enabled = !uiState.isFetching) {
                                Text(if (uiState.isFetching) "Fetching" else "Fetch")
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
                        label = "Allow Duplicates",
                        checked = uiState.settings.allowDupes,
                        onCheckedChange = viewModel::updateAllowDupes,
                    )
                    AnkiDivider()
                    AnkiSwitchRow(
                        label = "Compact Glossaries",
                        checked = uiState.settings.compactGlossaries,
                        onCheckedChange = viewModel::updateCompactGlossaries,
                    )
                }
            }
            val selectedNoteType = uiState.selectedNoteType
            if (selectedNoteType != null) {
                item {
                    Text(
                        text = "Fields",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(top = 18.dp, bottom = 8.dp),
                    )
                }
                selectedNoteType.fields.forEach { field ->
                    item {
                        AnkiFieldMappingRow(
                            field = field,
                            value = uiState.settings.fieldMappings[field].orEmpty(),
                            onValueChange = { viewModel.updateFieldMapping(field, it) },
                        )
                    }
                }
                item {
                    OutlinedTextField(
                        value = uiState.settings.tags,
                        onValueChange = viewModel::updateTags,
                        label = { Text("Tags") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun AnkiDeckRow(
    uiState: AnkiUiState,
    onSelect: (AnkiDeck) -> Unit,
) {
    AnkiDropdownRow(
        label = "Deck",
        value = uiState.settings.selectedDeckName ?: "None",
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
        label = "Model",
        value = uiState.settings.selectedNoteTypeName ?: "None",
        enabled = uiState.availableNoteTypes.isNotEmpty(),
        items = uiState.availableNoteTypes,
        itemLabel = { it.name },
        onSelect = onSelect,
    )
}

@Composable
private fun <T> AnkiDropdownRow(
    label: String,
    value: String,
    enabled: Boolean,
    items: List<T>,
    itemLabel: (T) -> String,
    onSelect: (T) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    ListItem(
        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surface),
        headlineContent = { Text(label) },
        supportingContent = { Text(value) },
        trailingContent = {
            TextButton(onClick = { expanded = true }, enabled = enabled) {
                Text("Choose")
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
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    ListItem(
        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surface),
        headlineContent = { Text(label) },
        trailingContent = { Switch(checked = checked, onCheckedChange = onCheckedChange) },
    )
}

@Composable
private fun AnkiFieldMappingRow(
    field: String,
    value: String,
    onValueChange: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(field) },
        singleLine = true,
        trailingIcon = {
            TextButton(onClick = { expanded = true }) { Text("{}") }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                AnkiHandlebarOptions.forEach { option ->
                    DropdownMenuItem(
                        text = { Text(option) },
                        onClick = {
                            expanded = false
                            onValueChange(if (option == "-") "" else option)
                        },
                    )
                }
            }
        },
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = {}),
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
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

private val AnkiHandlebarOptions = listOf(
    "-",
    "{expression}",
    "{reading}",
    "{furigana-plain}",
    "{audio}",
    "{glossary}",
    "{glossary-first}",
    "{selected-glossary}",
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

private const val AnkiDroidReadWritePermission = "com.ichi2.anki.permission.READ_WRITE_DATABASE"
