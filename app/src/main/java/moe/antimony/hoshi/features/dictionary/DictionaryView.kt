package moe.antimony.hoshi.features.dictionary

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.DataObject
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import moe.antimony.hoshi.dictionary.DictionaryInfo
import moe.antimony.hoshi.dictionary.DictionaryRepository
import moe.antimony.hoshi.dictionary.DictionaryType
import moe.antimony.hoshi.importing.ImportFileType
import moe.antimony.hoshi.importing.MultipleFileImportContent
import moe.antimony.hoshi.importing.validateImportFile

private val DictionarySwitchColor = Color(0xFF34C759)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DictionaryView(
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    val repository = remember { DictionaryRepository(context.filesDir, context.cacheDir) }
    val settingsStore = remember { DictionarySettingsStore(context) }
    var selectedType by remember { mutableStateOf(DictionaryType.Term) }
    var importType by remember { mutableStateOf(DictionaryType.Term) }
    var importMenuExpanded by remember { mutableStateOf(false) }
    var destination by remember { mutableStateOf<DictionaryDestination?>(null) }
    var dictionaries by remember { mutableStateOf<Map<DictionaryType, List<DictionaryInfo>>>(emptyMap()) }
    var dictionarySettings by remember { mutableStateOf(settingsStore.load()) }
    var isImporting by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    fun reload() {
        dictionaries = DictionaryType.entries.associateWith { repository.loadDictionaries(it) }
        runCatching { repository.rebuildLookupQuery() }
    }

    fun importDictionaries(uris: List<Uri>, type: DictionaryType) {
        scope.launch {
            isImporting = true
            errorMessage = null
            runCatching {
                withContext(Dispatchers.IO) {
                    uris.forEach { uri ->
                        repository.importDictionary(context.contentResolver, uri, type)
                    }
                }
            }.onSuccess {
                reload()
            }.onFailure {
                errorMessage = it.localizedMessage ?: "Failed to import dictionary."
            }
            isImporting = false
        }
    }

    fun setDictionaryEnabled(dictionary: DictionaryInfo, enabled: Boolean) {
        scope.launch {
            withContext(Dispatchers.IO) {
                repository.setDictionaryEnabled(selectedType, dictionary.path.name, enabled)
            }
            reload()
        }
    }

    fun deleteDictionary(dictionary: DictionaryInfo) {
        scope.launch {
            withContext(Dispatchers.IO) {
                repository.deleteDictionary(selectedType, dictionary.path.name)
            }
            reload()
        }
    }

    fun moveDictionary(fromIndex: Int, toIndex: Int) {
        scope.launch {
            withContext(Dispatchers.IO) {
                repository.moveDictionary(selectedType, fromIndex, toIndex)
            }
            reload()
        }
    }

    fun updateSettings(transform: (DictionarySettings) -> DictionarySettings) {
        dictionarySettings = transform(dictionarySettings).normalized()
        settingsStore.save(dictionarySettings)
    }

    val importer = rememberLauncherForActivityResult(MultipleFileImportContent()) { uris: List<Uri> ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        runCatching {
            uris.forEach { uri ->
                context.contentResolver.validateImportFile(uri, ImportFileType.DictionaryArchive)
            }
        }.onFailure { error ->
            errorMessage = error.localizedMessage ?: "Select a .zip dictionary archive."
            return@rememberLauncherForActivityResult
        }
        uris.forEach { uri ->
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION,
                )
            }
        }
        importDictionaries(uris, importType)
    }

    val currentDictionaries = dictionaries[selectedType].orEmpty()
    val listState = rememberLazyListState()
    var draggedFileName by remember { mutableStateOf<String?>(null) }
    var dragStartIndex by remember { mutableIntStateOf(-1) }
    var dragStartTop by remember { mutableFloatStateOf(0f) }
    var dragStartHeight by remember { mutableFloatStateOf(0f) }
    var dragOffsetY by remember { mutableFloatStateOf(0f) }
    val dictionaryStartGlobalIndex = 1 + if (errorMessage != null) 1 else 0
    val dragTargetIndex by remember(
        listState,
        currentDictionaries,
        dictionaryStartGlobalIndex,
        dragStartIndex,
        dragStartTop,
        dragStartHeight,
        dragOffsetY,
    ) {
        derivedStateOf {
            if (dragStartIndex !in currentDictionaries.indices) {
                dragStartIndex
            } else {
                val visibleRows = listState.layoutInfo.visibleItemsInfo.mapNotNull { item ->
                    val dictionaryIndex = item.index - dictionaryStartGlobalIndex
                    if (dictionaryIndex in currentDictionaries.indices) {
                        DictionaryDragReorder.RowBounds(
                            index = dictionaryIndex,
                            top = item.offset.toFloat(),
                            bottom = (item.offset + item.size).toFloat(),
                        )
                    } else {
                        null
                    }
                }
                DictionaryDragReorder.targetIndex(
                    startIndex = dragStartIndex,
                    draggedCenterY = dragStartTop + dragStartHeight / 2f + dragOffsetY,
                    visibleRows = visibleRows,
                )
            }
        }
    }

    fun resetDrag() {
        draggedFileName = null
        dragStartIndex = -1
        dragStartTop = 0f
        dragStartHeight = 0f
        dragOffsetY = 0f
    }

    fun startDrag(index: Int, fileName: String) {
        val globalIndex = dictionaryStartGlobalIndex + index
        val item = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.index == globalIndex } ?: return
        draggedFileName = fileName
        dragStartIndex = index
        dragStartTop = item.offset.toFloat()
        dragStartHeight = item.size.toFloat()
        dragOffsetY = 0f
    }

    LaunchedEffect(Unit) {
        reload()
    }

    when (destination) {
        DictionaryDestination.Settings -> {
            DictionarySettingsView(
                settings = dictionarySettings,
                onSettingsChange = ::updateSettings,
                onClose = { destination = null },
                modifier = modifier,
            )
            return
        }
        DictionaryDestination.CustomCss -> {
            DictionaryCustomCssView(
                settings = dictionarySettings,
                onSettingsChange = ::updateSettings,
                onClose = { destination = null },
                modifier = modifier,
            )
            return
        }
        null -> Unit
    }

    BackHandler(onBack = onClose)
    val colorScheme = MaterialTheme.colorScheme

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = colorScheme.background,
        topBar = {
            LargeTopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = colorScheme.background,
                    scrolledContainerColor = colorScheme.background,
                    titleContentColor = colorScheme.onBackground,
                    navigationIconContentColor = colorScheme.onBackground,
                    actionIconContentColor = colorScheme.onBackground,
                ),
                title = {
                    Text(
                        text = "Dictionaries",
                        fontWeight = FontWeight.SemiBold,
                    )
                },
                navigationIcon = {
                    HoshiIconBackButton(onClose)
                },
                actions = {
                    IconButton(onClick = { destination = DictionaryDestination.CustomCss }) {
                        Icon(
                            imageVector = Icons.Rounded.DataObject,
                            contentDescription = "Custom CSS",
                        )
                    }
                    Box {
                        IconButton(
                            onClick = { importMenuExpanded = true },
                            enabled = !isImporting,
                        ) {
                            if (isImporting) {
                                CircularProgressIndicator(modifier = Modifier.size(24.dp))
                            } else {
                                Icon(
                                    imageVector = Icons.Rounded.Add,
                                    contentDescription = "Import Dictionary",
                                )
                            }
                        }
                        DropdownMenu(
                            expanded = importMenuExpanded,
                            onDismissRequest = { importMenuExpanded = false },
                        ) {
                            DictionaryType.entries.forEach { type ->
                                DropdownMenuItem(
                                    text = { Text(type.displayName) },
                                    onClick = {
                                        importMenuExpanded = false
                                        importType = type
                                        importer.launch(ImportFileType.DictionaryArchive.mimeTypes)
                                    },
                                )
                            }
                        }
                    }
                },
            )
        },
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                state = listState,
            ) {
                item {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                    ) {
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(24.dp),
                            color = colorScheme.surface,
                            border = BorderStroke(1.dp, colorScheme.outlineVariant),
                            tonalElevation = 0.dp,
                        ) {
                            Column {
                                ListItem(
                                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                                    headlineContent = { Text("Default to Dictionary Tab") },
                                    trailingContent = {
                                        Switch(
                                            checked = dictionarySettings.dictionaryTabDefault,
                                            onCheckedChange = { checked ->
                                                updateSettings { it.copy(dictionaryTabDefault = checked) }
                                            },
                                            colors = hoshiSwitchColors(),
                                        )
                                    },
                                )
                                HorizontalDivider(
                                    modifier = Modifier.padding(horizontal = 16.dp),
                                    color = colorScheme.outlineVariant,
                                )
                                ListItem(
                                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                                    leadingContent = {
                                        Icon(
                                            imageVector = Icons.Rounded.Tune,
                                            contentDescription = null,
                                            tint = colorScheme.onSurfaceVariant,
                                        )
                                    },
                                    headlineContent = { Text("Settings") },
                                    trailingContent = {
                                        Icon(
                                            imageVector = Icons.Rounded.ChevronRight,
                                            contentDescription = null,
                                            tint = colorScheme.onSurfaceVariant,
                                        )
                                    },
                                    modifier = Modifier.clickable { destination = DictionaryDestination.Settings },
                                )
                            }
                        }
                        Spacer(modifier = Modifier.height(18.dp))
                        SingleChoiceSegmentedButtonRow(
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            DictionaryType.entries.forEachIndexed { index, type ->
                                SegmentedButton(
                                    selected = selectedType == type,
                                    onClick = { selectedType = type },
                                    shape = SegmentedButtonDefaults.itemShape(
                                        index = index,
                                        count = DictionaryType.entries.size,
                                    ),
                                    colors = SegmentedButtonDefaults.colors(
                                        activeContainerColor = colorScheme.secondaryContainer,
                                        activeContentColor = colorScheme.onSecondaryContainer,
                                        activeBorderColor = colorScheme.outline,
                                        inactiveContainerColor = Color.Transparent,
                                        inactiveContentColor = colorScheme.onSurface,
                                        inactiveBorderColor = colorScheme.outline,
                                    ),
                                ) {
                                    Text(type.displayName)
                                }
                            }
                        }
                        Text(
                            text = "Yomitan term, frequency and pitch dictionaries (.zip) are supported",
                            color = colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(top = 10.dp),
                        )
                    }
                }
                errorMessage?.let { item { Text(it, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) } }
                if (currentDictionaries.isEmpty()) {
                    item {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 80.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text("No ${selectedType.displayName} Dictionaries")
                        }
                    }
                } else {
                    itemsIndexed(
                        items = currentDictionaries,
                        key = { _, dictionary -> dictionary.path.name },
                    ) { index, dictionary ->
                        val isDragging = draggedFileName == dictionary.path.name
                        DictionaryRow(
                            dictionary = dictionary,
                            onEnabledChange = { setDictionaryEnabled(dictionary, it) },
                            onDelete = { deleteDictionary(dictionary) },
                            modifier = Modifier
                                .zIndex(if (isDragging) 1f else 0f)
                                .graphicsLayer {
                                    translationY = if (isDragging) dragOffsetY else 0f
                                },
                            onDragStart = { startDrag(index, dictionary.path.name) },
                            onDrag = { delta ->
                                dragOffsetY += delta
                            },
                            onDragEnd = {
                                if (dragStartIndex in currentDictionaries.indices &&
                                    dragTargetIndex in currentDictionaries.indices &&
                                    dragTargetIndex != dragStartIndex
                                ) {
                                    moveDictionary(dragStartIndex, dragTargetIndex)
                                }
                                resetDrag()
                            },
                        )
                    }
                }
            }
        }
    }
}

private enum class DictionaryDestination {
    Settings,
    CustomCss,
}

private val DictionaryType.displayName: String
    get() = when (this) {
        DictionaryType.Term -> "Term"
        DictionaryType.Frequency -> "Frequency"
        DictionaryType.Pitch -> "Pitch"
    }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DictionaryRow(
    dictionary: DictionaryInfo,
    onEnabledChange: (Boolean) -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
    onDragStart: () -> Unit,
    onDrag: (Float) -> Unit,
    onDragEnd: () -> Unit,
) {
    val colorScheme = MaterialTheme.colorScheme
    val dismissState = rememberSwipeToDismissBoxState()

    LaunchedEffect(dismissState.currentValue) {
        if (dismissState.currentValue == SwipeToDismissBoxValue.EndToStart) {
            onDelete()
        }
    }

    SwipeToDismissBox(
        state = dismissState,
        enableDismissFromStartToEnd = false,
        modifier = modifier.padding(horizontal = 16.dp, vertical = 6.dp),
        backgroundContent = {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .fillMaxHeight(),
                shape = RoundedCornerShape(20.dp),
                color = colorScheme.error,
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 24.dp),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Delete,
                        contentDescription = null,
                        tint = colorScheme.onError,
                    )
                    Spacer(modifier = Modifier.size(8.dp))
                    Text("Delete", color = colorScheme.onError)
                }
            }
        },
    ) {
        Surface(
            modifier = Modifier.pointerInput(dictionary.path.name) {
                detectDragGesturesAfterLongPress(
                    onDragStart = { _: Offset -> onDragStart() },
                    onDragCancel = onDragEnd,
                    onDragEnd = onDragEnd,
                    onDrag = { _, dragAmount ->
                        onDrag(dragAmount.y)
                    },
                )
            },
            shape = RoundedCornerShape(20.dp),
            color = colorScheme.surface,
            border = BorderStroke(1.dp, colorScheme.outlineVariant),
            tonalElevation = 0.dp,
        ) {
            ListItem(
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                headlineContent = {
                    Text(
                        text = dictionary.index.title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Medium,
                    )
                },
                supportingContent = {
                    Text(
                        text = dictionary.index.revision.ifBlank { dictionary.path.name },
                        color = colorScheme.onSurfaceVariant,
                    )
                },
                trailingContent = {
                    HoshiSwitch(
                        checked = dictionary.isEnabled,
                        onCheckedChange = onEnabledChange,
                    )
                },
            )
        }
    }
}

@Composable
private fun HoshiSwitch(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Switch(
        checked = checked,
        onCheckedChange = onCheckedChange,
        colors = hoshiSwitchColors(),
    )
}

@Composable
private fun hoshiSwitchColors() = SwitchDefaults.colors(
    checkedThumbColor = Color.White,
    checkedTrackColor = DictionarySwitchColor,
    uncheckedThumbColor = MaterialTheme.colorScheme.onSurfaceVariant,
    uncheckedTrackColor = MaterialTheme.colorScheme.surfaceVariant,
    uncheckedBorderColor = MaterialTheme.colorScheme.outline,
)

@Composable
private fun HoshiIconBackButton(onClick: () -> Unit) {
    IconButton(onClick = onClick) {
        Icon(
            imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
            contentDescription = "Back",
        )
    }
}

@Composable
private fun GroupDivider() {
    HorizontalDivider(
        modifier = Modifier.padding(horizontal = 16.dp),
        color = MaterialTheme.colorScheme.outlineVariant,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DictionarySettingsView(
    settings: DictionarySettings,
    onSettingsChange: ((DictionarySettings) -> DictionarySettings) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    BackHandler(onBack = onClose)
    val colorScheme = MaterialTheme.colorScheme
    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = colorScheme.background,
        topBar = {
            CenterAlignedTopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = colorScheme.background,
                    scrolledContainerColor = colorScheme.background,
                    titleContentColor = colorScheme.onBackground,
                    navigationIconContentColor = colorScheme.onBackground,
                ),
                title = { Text("Settings") },
                navigationIcon = { HoshiIconBackButton(onClose) },
            )
        },
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .background(colorScheme.background)
                .padding(innerPadding)
                .padding(horizontal = 16.dp),
        ) {
            item {
                SectionLabel("Lookup")
                SettingsGroup {
                    StepperRow(
                        title = "Max Results",
                        value = settings.maxResults,
                        onDecrease = {
                            onSettingsChange { it.copy(maxResults = it.maxResults - 1) }
                        },
                        onIncrease = {
                            onSettingsChange { it.copy(maxResults = it.maxResults + 1) }
                        },
                        canDecrease = settings.maxResults > DictionarySettings.MIN_MAX_RESULTS,
                        canIncrease = settings.maxResults < DictionarySettings.MAX_MAX_RESULTS,
                    )
                    GroupDivider()
                    StepperRow(
                        title = "Scan Length",
                        value = settings.scanLength,
                        onDecrease = {
                            onSettingsChange { it.copy(scanLength = it.scanLength - 1) }
                        },
                        onIncrease = {
                            onSettingsChange { it.copy(scanLength = it.scanLength + 1) }
                        },
                        canDecrease = settings.scanLength > DictionarySettings.MIN_SCAN_LENGTH,
                        canIncrease = settings.scanLength < DictionarySettings.MAX_SCAN_LENGTH,
                    )
                }
                SectionLabel("Behaviour")
                SettingsGroup {
                    ToggleRow("Auto-collapse Dictionaries", settings.collapseDictionaries) {
                        onSettingsChange { current -> current.copy(collapseDictionaries = it) }
                    }
                    GroupDivider()
                    ToggleRow("Compact Glossaries", settings.compactGlossaries) {
                        onSettingsChange { current -> current.copy(compactGlossaries = it) }
                    }
                    GroupDivider()
                    ToggleRow("Show Expression Tags", settings.showExpressionTags) {
                        onSettingsChange { current -> current.copy(showExpressionTags = it) }
                    }
                    GroupDivider()
                    ToggleRow("Harmonic Frequency", settings.harmonicFrequency) {
                        onSettingsChange { current -> current.copy(harmonicFrequency = it) }
                    }
                    GroupDivider()
                    ToggleRow("Deduplicate Pitch Accents", settings.deduplicatePitchAccents) {
                        onSettingsChange { current -> current.copy(deduplicatePitchAccents = it) }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DictionaryCustomCssView(
    settings: DictionarySettings,
    onSettingsChange: ((DictionarySettings) -> DictionarySettings) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    BackHandler(onBack = onClose)
    val colorScheme = MaterialTheme.colorScheme
    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = colorScheme.background,
        topBar = {
            CenterAlignedTopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = colorScheme.background,
                    scrolledContainerColor = colorScheme.background,
                    titleContentColor = colorScheme.onBackground,
                    navigationIconContentColor = colorScheme.onBackground,
                ),
                title = { Text("Custom CSS") },
                navigationIcon = { HoshiIconBackButton(onClose) },
                actions = {
                    TextButton(onClick = { onSettingsChange { it.copy(customCSS = "") } }) {
                        Text("Reset")
                    }
                },
            )
        },
    ) { innerPadding ->
        Surface(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(16.dp),
            shape = RoundedCornerShape(18.dp),
            color = colorScheme.surface,
            border = BorderStroke(1.dp, colorScheme.outlineVariant),
            tonalElevation = 0.dp,
        ) {
            BasicTextField(
                value = settings.customCSS,
                onValueChange = { value ->
                    onSettingsChange { it.copy(customCSS = value) }
                },
                textStyle = MaterialTheme.typography.bodyLarge.copy(color = colorScheme.onSurface),
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp),
            )
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        style = MaterialTheme.typography.titleLarge,
        modifier = Modifier.padding(start = 8.dp, top = 24.dp, bottom = 8.dp),
    )
}

@Composable
private fun SettingsGroup(content: @Composable ColumnScope.() -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(28.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        tonalElevation = 0.dp,
    ) {
        Column(content = content)
    }
}

@Composable
private fun StepperRow(
    title: String,
    value: Int,
    onDecrease: () -> Unit,
    onIncrease: () -> Unit,
    canDecrease: Boolean,
    canIncrease: Boolean,
) {
    val colorScheme = MaterialTheme.colorScheme
    ListItem(
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        headlineContent = { Text(title) },
        trailingContent = {
            Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(value.toString())
                Surface(
                    shape = RoundedCornerShape(24.dp),
                    color = colorScheme.surfaceVariant,
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = onDecrease, enabled = canDecrease) {
                            Icon(
                                imageVector = Icons.Rounded.Remove,
                                contentDescription = "Decrease",
                                tint = if (canDecrease) {
                                    colorScheme.onSurface
                                } else {
                                    colorScheme.onSurfaceVariant.copy(alpha = 0.38f)
                                },
                            )
                        }
                        Box(
                            modifier = Modifier
                                .size(width = 1.dp, height = 28.dp)
                                .background(colorScheme.onSurfaceVariant.copy(alpha = 0.35f)),
                        )
                        IconButton(onClick = onIncrease, enabled = canIncrease) {
                            Icon(
                                imageVector = Icons.Rounded.Add,
                                contentDescription = "Increase",
                                tint = if (canIncrease) {
                                    colorScheme.onSurface
                                } else {
                                    colorScheme.onSurfaceVariant.copy(alpha = 0.38f)
                                },
                            )
                        }
                    }
                }
            }
        },
    )
}

@Composable
private fun ToggleRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    ListItem(
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        headlineContent = { Text(title) },
        trailingContent = {
            HoshiSwitch(
                checked = checked,
                onCheckedChange = onCheckedChange,
            )
        },
    )
}
