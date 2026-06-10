package moe.antimony.hoshi.features.dictionary

import android.content.Intent
import android.net.Uri
import androidx.compose.animation.core.Animatable
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.AnchoredDraggableDefaults
import androidx.compose.foundation.gestures.AnchoredDraggableState
import androidx.compose.foundation.gestures.DraggableAnchors
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.anchoredDraggable
import androidx.compose.foundation.gestures.animateTo
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.DataObject
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material.icons.rounded.TextFields
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date
import moe.antimony.hoshi.LocalHoshiUiDependencies
import moe.antimony.hoshi.R
import moe.antimony.hoshi.dictionary.DictionaryInfo
import moe.antimony.hoshi.dictionary.DictionaryType
import moe.antimony.hoshi.dictionary.RecommendedDictionaries
import moe.antimony.hoshi.features.settings.SettingsDetailScaffold
import moe.antimony.hoshi.features.reader.ReaderFontManager
import moe.antimony.hoshi.importing.ImportFileType
import moe.antimony.hoshi.importing.MultipleFileImportContent
import moe.antimony.hoshi.importing.importDisplayName
import moe.antimony.hoshi.ui.HoshiBlockingProgressOverlay
import moe.antimony.hoshi.ui.asString
import moe.antimony.hoshi.ui.hoshiTextFieldCursorBrush
import kotlin.math.roundToInt

private val DictionarySwitchColor = Color(0xFF34C759)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DictionaryView(
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val appContainer = LocalHoshiUiDependencies.current
    val dictionaryViewModel: DictionaryViewModel = hiltViewModel()
    val uiState by dictionaryViewModel.uiState.collectAsStateWithLifecycle()
    var destination by remember { mutableStateOf<DictionaryDestination?>(null) }
    var showUpdateConfirmation by remember { mutableStateOf(false) }
    var showDownloadConfirmation by remember { mutableStateOf(false) }
    var intervalMenuExpanded by remember { mutableStateOf(false) }

    val importer = rememberLauncherForActivityResult(MultipleFileImportContent()) { uris: List<Uri> ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        val importItems = uris.map { uri ->
            val displayName = context.contentResolver.importDisplayName(uri)
            DictionaryImportItem(
                uri = uri,
                displayName = displayName.ifBlank { uri.lastPathSegment.orEmpty() },
            )
        }
        uris.forEach { uri ->
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION,
                )
            }
        }
        dictionaryViewModel.importDictionaries(importItems)
    }

    val selectedType = uiState.selectedType
    val currentDictionaries = uiState.currentDictionaries
    val settings = uiState.settings
    val isBusy = uiState.isMutationInProgress || uiState.isImporting || uiState.isUpdating
    val lastDictionaryUpdateText = settings.lastDictionaryUpdateEpochMillis
        ?.let { millis ->
            remember(millis) {
                DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(millis))
            }
        }
        ?: stringResource(R.string.dictionary_last_update_never)
    val listLayout = DictionaryListLayout.from(uiState.errorMessage)
    val listState = rememberLazyListState()
    val density = LocalDensity.current
    val autoScrollEdgeThresholdPx = with(density) { 72.dp.toPx() }
    val autoScrollMaxDeltaPx = with(density) { 28.dp.toPx() }
    var draggedFileName by remember { mutableStateOf<String?>(null) }
    var dragStartIndex by remember { mutableIntStateOf(-1) }
    var dragStartTop by remember { mutableFloatStateOf(0f) }
    var dragStartHeight by remember { mutableFloatStateOf(0f) }
    var dragOffsetY by remember { mutableFloatStateOf(0f) }
    var dragWorkingDictionaries by remember { mutableStateOf<List<DictionaryInfo>?>(null) }
    var isDragSettling by remember { mutableStateOf(false) }
    val coroutineScope = rememberCoroutineScope()
    var revealedFileName by remember(selectedType) { mutableStateOf<String?>(null) }
    val layoutDictionaries = dragWorkingDictionaries ?: currentDictionaries
    val dictionaryStartGlobalIndex = listLayout.dictionaryStartGlobalIndex
    val dragTargetIndex by remember(
        listState,
        layoutDictionaries,
        dictionaryStartGlobalIndex,
        draggedFileName,
        dragStartTop,
        dragStartHeight,
        dragOffsetY,
    ) {
        derivedStateOf {
            val draggedIndex = layoutDictionaries.indexOfFirst { it.path.name == draggedFileName }
            if (draggedIndex !in layoutDictionaries.indices) {
                draggedIndex
            } else {
                val visibleRows = listState.layoutInfo.visibleItemsInfo.mapNotNull { item ->
                    val dictionaryIndex = item.index - dictionaryStartGlobalIndex
                    if (dictionaryIndex in layoutDictionaries.indices) {
                        DictionaryDragReorder.RowBounds(
                            index = dictionaryIndex,
                            top = item.offset.toFloat(),
                            bottom = (item.offset + item.size).toFloat(),
                        )
                    } else {
                        null
                    }
                }
                val draggedItem = listState.layoutInfo.visibleItemsInfo.firstOrNull {
                    it.index == dictionaryStartGlobalIndex + draggedIndex
                }
                val draggedCenterY = if (draggedItem != null) {
                    draggedItem.offset + draggedItem.size / 2f + dragOffsetY
                } else {
                    dragStartTop + dragStartHeight / 2f + dragOffsetY
                }
                DictionaryDragReorder.targetIndex(
                    startIndex = draggedIndex,
                    draggedCenterY = draggedCenterY,
                    visibleRows = visibleRows,
                )
            }
        }
    }
    LaunchedEffect(listState.isScrollInProgress) {
        if (listState.isScrollInProgress && draggedFileName == null) {
            revealedFileName = null
        }
    }

    LaunchedEffect(currentDictionaries, revealedFileName) {
        if (revealedFileName != null && currentDictionaries.none { it.path.name == revealedFileName }) {
            revealedFileName = null
        }
    }

    LaunchedEffect(draggedFileName) {
        while (draggedFileName != null) {
            val pointerY = dragStartTop + dragStartHeight / 2f + dragOffsetY
            val delta = DictionaryDragReorder.autoScrollDelta(
                pointerY = pointerY,
                viewportStart = listState.layoutInfo.viewportStartOffset.toFloat(),
                viewportEnd = listState.layoutInfo.viewportEndOffset.toFloat(),
                edgeThreshold = autoScrollEdgeThresholdPx,
                maxDelta = autoScrollMaxDeltaPx,
            )
            if (delta != 0f) {
                listState.scrollBy(delta)
            }
            delay(16)
        }
    }

    fun resetDrag() {
        draggedFileName = null
        dragStartIndex = -1
        dragStartTop = 0f
        dragStartHeight = 0f
        dragOffsetY = 0f
        dragWorkingDictionaries = null
        isDragSettling = false
    }

    fun startDrag(index: Int, fileName: String) {
        revealedFileName = null
        if (isDragSettling) return
        val globalIndex = dictionaryStartGlobalIndex + index
        val item = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.index == globalIndex } ?: return
        dragWorkingDictionaries = currentDictionaries
        draggedFileName = fileName
        dragStartIndex = index
        dragStartTop = item.offset.toFloat()
        dragStartHeight = item.size.toFloat()
        dragOffsetY = 0f
    }

    fun updateDrag(delta: Float) {
        val fileName = draggedFileName ?: return
        val workingDictionaries = dragWorkingDictionaries ?: return
        val fromIndex = workingDictionaries.indexOfFirst { it.path.name == fileName }
        if (fromIndex !in workingDictionaries.indices) return
        dragOffsetY += delta
        val toIndex = dragTargetIndex
        if (toIndex in workingDictionaries.indices && toIndex != fromIndex) {
            val itemSize = listState.layoutInfo.visibleItemsInfo
                .firstOrNull { it.index == dictionaryStartGlobalIndex + fromIndex }
                ?.size
                ?.toFloat()
                ?: dragStartHeight
            dragWorkingDictionaries = DictionaryDragReorder.previewOrder(
                items = workingDictionaries,
                fromIndex = fromIndex,
                toIndex = toIndex,
            )
            dragOffsetY = DictionaryDragReorder.adjustedDragOffsetAfterMove(
                dragOffset = dragOffsetY,
                fromIndex = fromIndex,
                toIndex = toIndex,
                itemSize = itemSize,
            )
        }
    }

    LaunchedEffect(Unit) {
        dictionaryViewModel.reload()
    }

    when (destination) {
        DictionaryDestination.Settings -> {
            DictionarySettingsView(
                settings = uiState.settings,
                termDictionaries = uiState.dictionaries[DictionaryType.Term].orEmpty(),
                onSettingsChange = dictionaryViewModel::updateSettings,
                onClose = { destination = null },
                modifier = modifier,
            )
            return
        }
        DictionaryDestination.CustomCss -> {
            DictionaryCustomCssView(
                settings = uiState.settings,
                termDictionaries = uiState.dictionaries[DictionaryType.Term].orEmpty(),
                fontManager = appContainer.readerFontManager,
                onSettingsChange = dictionaryViewModel::updateSettings,
                onClose = { destination = null },
                modifier = modifier,
            )
            return
        }
        null -> Unit
    }

    val colorScheme = MaterialTheme.colorScheme

    SettingsDetailScaffold(
        title = stringResource(R.string.settings_dictionaries),
        onClose = {
            if (!isBusy) {
                onClose()
            }
        },
        modifier = modifier.fillMaxSize(),
        containerColor = colorScheme.background,
        contentColor = colorScheme.onBackground,
        actions = {
            IconButton(
                onClick = { destination = DictionaryDestination.CustomCss },
                enabled = !isBusy,
            ) {
                Icon(
                    imageVector = Icons.Rounded.DataObject,
                    contentDescription = stringResource(R.string.dictionary_custom_css),
                )
            }
            IconButton(
                onClick = { importer.launch(ImportFileType.DictionaryArchive.mimeTypes) },
                enabled = !isBusy,
            ) {
                if (isBusy) {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp))
                } else {
                    Icon(
                        imageVector = Icons.Rounded.Add,
                        contentDescription = stringResource(R.string.dictionary_import_action),
                    )
                }
            }
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
                                    headlineContent = {
                                        Text(
                                            text = stringResource(R.string.dictionary_download_recommended),
                                            color = colorScheme.primary,
                                        )
                                    },
                                    modifier = Modifier.clickable(enabled = !isBusy) {
                                        showDownloadConfirmation = true
                                    },
                                )
                            }
                        }
                        Text(
                            text = stringResource(R.string.dictionary_supported_archive_types),
                            color = colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                        )
                        Spacer(modifier = Modifier.height(18.dp))
                        if (uiState.updatableDictionaries.isNotEmpty()) {
                            Text(
                                text = stringResource(R.string.dictionary_updates_section),
                                color = colorScheme.onSurfaceVariant,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.padding(start = 16.dp, bottom = 8.dp),
                            )
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
                                        headlineContent = { Text(stringResource(R.string.dictionary_update_automatically)) },
                                        trailingContent = {
                                            HoshiSwitch(
                                                checked = settings.autoUpdateDictionaries,
                                                onCheckedChange = { checked ->
                                                    dictionaryViewModel.updateSettings {
                                                        it.copy(autoUpdateDictionaries = checked)
                                                    }
                                                },
                                                enabled = !isBusy,
                                            )
                                        },
                                    )
                                    if (settings.autoUpdateDictionaries) {
                                        HorizontalDivider(
                                            modifier = Modifier.padding(horizontal = 16.dp),
                                            color = colorScheme.outlineVariant,
                                        )
                                        ListItem(
                                            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                                            headlineContent = { Text(stringResource(R.string.dictionary_update_interval)) },
                                            trailingContent = {
                                                Box {
                                                    TextButton(
                                                        onClick = { intervalMenuExpanded = true },
                                                        enabled = !isBusy,
                                                    ) {
                                                        Text(stringResource(settings.dictionaryUpdateInterval.labelRes))
                                                    }
                                                    DropdownMenu(
                                                        expanded = intervalMenuExpanded,
                                                        onDismissRequest = { intervalMenuExpanded = false },
                                                    ) {
                                                        DictionaryUpdateInterval.entries.forEach { interval ->
                                                            DropdownMenuItem(
                                                                text = { Text(stringResource(interval.labelRes)) },
                                                                onClick = {
                                                                    intervalMenuExpanded = false
                                                                    dictionaryViewModel.updateSettings {
                                                                        it.copy(dictionaryUpdateInterval = interval)
                                                                    }
                                                                },
                                                            )
                                                        }
                                                    }
                                                }
                                            },
                                        )
                                    }
                                    HorizontalDivider(
                                        modifier = Modifier.padding(horizontal = 16.dp),
                                        color = colorScheme.outlineVariant,
                                    )
                                    ListItem(
                                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                                        headlineContent = { Text(stringResource(R.string.dictionary_last_update)) },
                                        trailingContent = {
                                            Text(
                                                text = lastDictionaryUpdateText,
                                                color = colorScheme.onSurfaceVariant,
                                            )
                                        },
                                    )
                                    HorizontalDivider(
                                        modifier = Modifier.padding(horizontal = 16.dp),
                                        color = colorScheme.outlineVariant,
                                    )
                                    ListItem(
                                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                                        headlineContent = {
                                            Text(
                                                text = stringResource(R.string.action_update),
                                                color = colorScheme.primary,
                                            )
                                        },
                                        modifier = Modifier.clickable(enabled = !isBusy) {
                                            showUpdateConfirmation = true
                                        },
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.height(18.dp))
                        }
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
                                    headlineContent = { Text(stringResource(R.string.dictionary_default_tab)) },
                                    trailingContent = {
                                        Switch(
                                            checked = uiState.settings.dictionaryTabDefault,
                                            onCheckedChange = { checked ->
                                                dictionaryViewModel.updateSettings { it.copy(dictionaryTabDefault = checked) }
                                            },
                                            enabled = !isBusy,
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
                                    headlineContent = { Text(stringResource(R.string.settings_title)) },
                                    trailingContent = {
                                        Icon(
                                            imageVector = Icons.Rounded.ChevronRight,
                                            contentDescription = null,
                                            tint = colorScheme.onSurfaceVariant,
                                        )
                                    },
                                    modifier = Modifier.clickable(enabled = !isBusy) {
                                        destination = DictionaryDestination.Settings
                                    },
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
                                    onClick = { dictionaryViewModel.selectType(type) },
                                    enabled = !isBusy,
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
                                    icon = {},
                                ) {
                                    Text(stringResource(type.displayNameRes))
                                }
                            }
                        }
                    }
                }
                if (currentDictionaries.isEmpty()) {
                    item {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 80.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(stringResource(R.string.dictionary_empty_type_format, stringResource(selectedType.displayNameRes)))
                        }
                    }
                } else {
                    itemsIndexed(
                        items = layoutDictionaries,
                        key = { _, dictionary -> dictionary.path.name },
                    ) { index, dictionary ->
                        val isDragging = draggedFileName == dictionary.path.name
                        val reorderModifier = if (isDragging) {
                            Modifier.graphicsLayer { translationY = dragOffsetY }
                        } else {
                            Modifier.animateItem()
                        }
                        DictionaryRow(
                            dictionary = dictionary,
                            onEnabledChange = { dictionaryViewModel.setDictionaryEnabled(dictionary, it) },
                            onDelete = {
                                revealedFileName = null
                                dictionaryViewModel.deleteDictionary(dictionary)
                            },
                            isRevealed = revealedFileName == dictionary.path.name,
                            onRevealChange = { revealed ->
                                revealedFileName = if (revealed) {
                                    dictionary.path.name
                                } else {
                                    revealedFileName.takeUnless { it == dictionary.path.name }
                                }
                            },
                            enabled = !isBusy,
                            swipeEnabled = draggedFileName == null && !isDragSettling && !isBusy,
                            modifier = reorderModifier
                                .zIndex(if (isDragging) 1f else 0f)
                                .padding(horizontal = 16.dp, vertical = 6.dp),
                            onDragStart = {
                                startDrag(index, dictionary.path.name)
                            },
                            onDrag = { delta ->
                                updateDrag(delta)
                            },
                            onDragEnd = {
                                if (!isDragSettling) {
                                    val fromIndex = dragStartIndex
                                    val toIndex = layoutDictionaries.indexOfFirst {
                                        it.path.name == draggedFileName
                                    }
                                    val releasedOffset = dragOffsetY
                                    coroutineScope.launch {
                                        isDragSettling = true
                                        Animatable(releasedOffset).animateTo(0f, tween(durationMillis = 180)) {
                                            dragOffsetY = value
                                        }
                                        if (fromIndex in currentDictionaries.indices &&
                                            toIndex in layoutDictionaries.indices &&
                                            toIndex != fromIndex
                                        ) {
                                            dictionaryViewModel.moveDictionary(fromIndex, toIndex)
                                        }
                                        resetDrag()
                                    }
                                }
                            },
                        )
                    }
                }
            }
            if (uiState.showBlockingProgress || uiState.isImporting || uiState.isUpdating) {
                HoshiBlockingProgressOverlay(
                    message = uiState.currentImportMessage?.asString() ?: stringResource(R.string.loading),
                    modifier = Modifier
                        .fillMaxSize()
                        .zIndex(2f),
                )
            }
        }
    }
    if (listLayout.showErrorDialog) {
        uiState.errorMessage?.let { message ->
            AlertDialog(
                onDismissRequest = dictionaryViewModel::consumeErrorMessage,
                title = { Text(stringResource(R.string.dialog_error_title)) },
                text = { Text(message.asString()) },
                confirmButton = {
                    TextButton(onClick = dictionaryViewModel::consumeErrorMessage) {
                        Text(stringResource(R.string.action_ok))
                    }
                },
            )
        }
    }
    if (showUpdateConfirmation) {
        AlertDialog(
            onDismissRequest = { showUpdateConfirmation = false },
            title = { Text(stringResource(R.string.dictionary_update_title)) },
            text = {
                Text(
                    stringResource(
                        R.string.dictionary_update_confirmation_format,
                        uiState.updatableDictionaries.joinToString(separator = "\n") { it.dictionary.index.title },
                    ),
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showUpdateConfirmation = false
                        dictionaryViewModel.updateDictionaries()
                    },
                ) {
                    Text(stringResource(R.string.action_update))
                }
            },
            dismissButton = {
                TextButton(onClick = { showUpdateConfirmation = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }
    if (showDownloadConfirmation) {
        AlertDialog(
            onDismissRequest = { showDownloadConfirmation = false },
            title = { Text(stringResource(R.string.dictionary_download_title)) },
            text = {
                Column {
                    Text(stringResource(R.string.dictionary_download_prompt))
                    Spacer(modifier = Modifier.height(8.dp))
                    RecommendedDictionaries.forEach { dictionary ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    showDownloadConfirmation = false
                                    dictionaryViewModel.importRecommendedDictionaries(listOf(dictionary))
                                }
                                .padding(vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                stringResource(
                                    R.string.dictionary_download_item_format,
                                    dictionary.name,
                                    stringResource(dictionary.type.displayNameRes),
                                ),
                            )
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { showDownloadConfirmation = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }
}

private enum class DictionaryDestination {
    Settings,
    CustomCss,
}

private val DictionaryType.displayNameRes: Int
    get() = when (this) {
        DictionaryType.Term -> R.string.dictionary_type_term
        DictionaryType.Frequency -> R.string.dictionary_type_frequency
        DictionaryType.Pitch -> R.string.dictionary_type_pitch
    }

private enum class DictionarySwipeRevealValue {
    Covered,
    Revealed,
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DictionaryRow(
    dictionary: DictionaryInfo,
    onEnabledChange: (Boolean) -> Unit,
    onDelete: () -> Unit,
    isRevealed: Boolean,
    onRevealChange: (Boolean) -> Unit,
    enabled: Boolean,
    swipeEnabled: Boolean,
    modifier: Modifier = Modifier,
    onDragStart: () -> Unit,
    onDrag: (Float) -> Unit,
    onDragEnd: () -> Unit,
) {
    val colorScheme = MaterialTheme.colorScheme
    val density = LocalDensity.current
    val actionWidth = 84.dp
    val actionWidthPx = with(density) { actionWidth.toPx() }
    val anchors = remember(actionWidthPx) {
        DraggableAnchors {
            DictionarySwipeRevealValue.Covered at 0f
            DictionarySwipeRevealValue.Revealed at -actionWidthPx
        }
    }
    val revealState = remember(dictionary.path.name, anchors) {
        AnchoredDraggableState<DictionarySwipeRevealValue>(
            initialValue = DictionarySwipeRevealValue.Covered,
            anchors = anchors,
        )
    }
    val flingBehavior = AnchoredDraggableDefaults.flingBehavior(
        state = revealState,
        positionalThreshold = { distance -> distance * 0.45f },
        animationSpec = tween(durationMillis = 180),
    )

    LaunchedEffect(isRevealed) {
        val target = if (isRevealed) {
            DictionarySwipeRevealValue.Revealed
        } else {
            DictionarySwipeRevealValue.Covered
        }
        revealState.animateTo(target, tween(durationMillis = 180))
    }

    LaunchedEffect(revealState.currentValue) {
        onRevealChange(revealState.currentValue == DictionarySwipeRevealValue.Revealed)
    }

    Box(modifier = modifier) {
        Row(
            modifier = Modifier.matchParentSize(),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Surface(
                modifier = Modifier
                    .width(actionWidth)
                    .fillMaxHeight()
                    .clickable(enabled = enabled) { onDelete() },
                shape = RoundedCornerShape(20.dp),
                color = colorScheme.error,
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Delete,
                        contentDescription = stringResource(R.string.dictionary_delete_action),
                        tint = colorScheme.onError,
                    )
                }
            }
        }

        val offsetX = revealState.requireOffset().roundToInt()
        Surface(
            modifier = Modifier
                .offset { IntOffset(offsetX, 0) }
                .anchoredDraggable(
                    state = revealState,
                    orientation = Orientation.Horizontal,
                    enabled = swipeEnabled,
                    flingBehavior = flingBehavior,
                )
                .clickable(
                    enabled = isRevealed,
                    onClick = { onRevealChange(false) },
                ),
            shape = RoundedCornerShape(20.dp),
            color = colorScheme.surface,
            border = BorderStroke(1.dp, colorScheme.outlineVariant),
            tonalElevation = 0.dp,
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(72.dp)
                    .padding(start = 10.dp, end = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val reorderDragState = rememberDraggableState { delta -> onDrag(delta) }
                DictionaryDragHandle(
                    modifier = Modifier.draggable(
                        state = reorderDragState,
                        orientation = Orientation.Vertical,
                        enabled = enabled,
                        startDragImmediately = true,
                        onDragStarted = { onDragStart() },
                        onDragStopped = { onDragEnd() },
                    ),
                )
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .combinedClickable(
                            enabled = enabled &&
                                DictionaryRowInteraction.canRevealDeleteOnLongPress(
                                    DictionaryRowInteraction.Area.Content,
                                ),
                            onClick = {},
                            onLongClick = { onRevealChange(true) },
                        )
                        .padding(start = 10.dp, end = 12.dp),
                    verticalArrangement = Arrangement.Center,
                ) {
                    Text(
                        text = dictionary.index.title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = dictionary.index.revision.ifBlank { dictionary.path.name },
                        color = colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                HoshiSwitch(
                    checked = dictionary.isEnabled,
                    onCheckedChange = onEnabledChange,
                    enabled = enabled,
                )
            }
        }
    }
}

@Composable
private fun DictionaryDragHandle(modifier: Modifier = Modifier) {
    val color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.72f)
    val reorderDescription = stringResource(R.string.dictionary_reorder_action)
    Box(
        modifier = modifier
            .width(32.dp)
            .height(56.dp)
            .semantics { contentDescription = reorderDescription },
        contentAlignment = Alignment.Center,
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(4.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            repeat(3) {
                Box(
                    modifier = Modifier
                        .width(20.dp)
                        .height(2.dp)
                        .background(color, RoundedCornerShape(1.dp)),
                )
            }
        }
    }
}

@Composable
private fun HoshiSwitch(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    enabled: Boolean = true,
) {
    Switch(
        checked = checked,
        onCheckedChange = onCheckedChange,
        enabled = enabled,
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
            contentDescription = stringResource(R.string.action_back),
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
    termDictionaries: List<DictionaryInfo>,
    onSettingsChange: ((DictionarySettings) -> DictionarySettings) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var showCollapsedDictionaries by remember { mutableStateOf(false) }
    if (showCollapsedDictionaries) {
        CollapsedDictionariesView(
            dictionaries = termDictionaries,
            collapsedDictionaries = settings.collapsedDictionaries,
            onToggleDictionary = { title ->
                onSettingsChange { current ->
                    val collapsed = current.collapsedDictionaries.toMutableSet()
                    if (!collapsed.add(title)) {
                        collapsed.remove(title)
                    }
                    current.copy(collapsedDictionaries = collapsed)
                }
            },
            onClose = { showCollapsedDictionaries = false },
            modifier = modifier,
        )
        return
    }
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
                title = { Text(stringResource(R.string.settings_title)) },
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
                SectionLabel(stringResource(R.string.dictionary_settings_lookup))
                SettingsGroup {
                    ToggleRow(stringResource(R.string.dictionary_scan_non_japanese), settings.scanNonJapaneseText) {
                        onSettingsChange { current -> current.copy(scanNonJapaneseText = it) }
                    }
                    GroupDivider()
                    StepperRow(
                        title = stringResource(R.string.dictionary_max_results),
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
                        title = stringResource(R.string.dictionary_scan_length),
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
                SectionLabel(stringResource(R.string.dictionary_settings_import))
                SettingsGroup {
                    ToggleRow(
                        title = stringResource(R.string.dictionary_low_ram_import),
                        checked = settings.lowRamDictionaryImport,
                        supportingText = stringResource(R.string.dictionary_low_ram_import_description),
                    ) {
                        onSettingsChange { current -> current.copy(lowRamDictionaryImport = it) }
                    }
                }
                SectionLabel(stringResource(R.string.dictionary_collapse_dictionaries))
                SettingsGroup {
                    ListItem(
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                        headlineContent = { Text(stringResource(R.string.dictionary_mode)) },
                        supportingContent = {
                            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                                DictionaryCollapseMode.entries.forEachIndexed { index, mode ->
                                    SegmentedButton(
                                        selected = settings.collapseMode == mode,
                                        onClick = { onSettingsChange { current -> current.copy(collapseMode = mode) } },
                                        shape = SegmentedButtonDefaults.itemShape(
                                            index = index,
                                            count = DictionaryCollapseMode.entries.size,
                                        ),
                                        icon = {},
                                    ) {
                                        Text(stringResource(mode.labelRes))
                                    }
                                }
                            }
                        },
                    )
                    if (settings.collapseMode != DictionaryCollapseMode.ExpandAll) {
                        GroupDivider()
                        ToggleRow(stringResource(R.string.dictionary_expand_first), settings.expandFirstDictionary) {
                            onSettingsChange { current -> current.copy(expandFirstDictionary = it) }
                        }
                    }
                    if (settings.collapseMode == DictionaryCollapseMode.Custom) {
                        GroupDivider()
                        ListItem(
                            modifier = Modifier.clickable { showCollapsedDictionaries = true },
                            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                            headlineContent = { Text(stringResource(R.string.action_configure)) },
                            trailingContent = {
                                Icon(
                                    imageVector = Icons.Rounded.ChevronRight,
                                    contentDescription = null,
                                )
                            },
                        )
                    }
                }
                SectionLabel(stringResource(R.string.dictionary_settings_behaviour))
                SettingsGroup {
                    ToggleRow(stringResource(R.string.dictionary_compact_glossaries), settings.compactGlossaries) {
                        onSettingsChange { current -> current.copy(compactGlossaries = it) }
                    }
                    GroupDivider()
                    ToggleRow(stringResource(R.string.dictionary_show_expression_tags), settings.showExpressionTags) {
                        onSettingsChange { current -> current.copy(showExpressionTags = it) }
                    }
                    GroupDivider()
                    ToggleRow(stringResource(R.string.dictionary_harmonic_frequency), settings.harmonicFrequency) {
                        onSettingsChange { current -> current.copy(harmonicFrequency = it) }
                    }
                    GroupDivider()
                    ToggleRow(stringResource(R.string.dictionary_deduplicate_pitch), settings.deduplicatePitchAccents) {
                        onSettingsChange { current -> current.copy(deduplicatePitchAccents = it) }
                    }
                    GroupDivider()
                    ToggleRow(stringResource(R.string.dictionary_compact_pitch), settings.compactPitchAccents) {
                        onSettingsChange { current -> current.copy(compactPitchAccents = it) }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CollapsedDictionariesView(
    dictionaries: List<DictionaryInfo>,
    collapsedDictionaries: Set<String>,
    onToggleDictionary: (String) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    BackHandler(onBack = onClose)
    val colorScheme = MaterialTheme.colorScheme
    SettingsDetailScaffold(
        title = stringResource(R.string.dictionary_collapse_dictionaries),
        onClose = onClose,
        modifier = modifier.fillMaxSize(),
        containerColor = colorScheme.background,
        contentColor = colorScheme.onBackground,
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 16.dp),
        ) {
            if (dictionaries.isEmpty()) {
                item {
                    Text(
                        text = stringResource(R.string.dictionary_no_term_dictionaries),
                        color = colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.padding(16.dp),
                    )
                }
            } else {
                items(dictionaries, key = { it.path.name }) { dictionary ->
                    val title = dictionary.index.title
                    ListItem(
                        modifier = Modifier.clickable { onToggleDictionary(title) },
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                        headlineContent = {
                            Text(
                                text = title,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        },
                        trailingContent = {
                            Text(
                                text = if (collapsedDictionaries.contains(title)) {
                                    stringResource(R.string.dictionary_collapsed)
                                } else {
                                    stringResource(R.string.dictionary_expanded)
                                },
                                color = colorScheme.onSurfaceVariant,
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        },
                    )
                    GroupDivider()
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DictionaryCustomCssView(
    settings: DictionarySettings,
    termDictionaries: List<DictionaryInfo>,
    fontManager: ReaderFontManager,
    onSettingsChange: ((DictionarySettings) -> DictionarySettings) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    BackHandler(onBack = onClose)
    val colorScheme = MaterialTheme.colorScheme
    val fontNames = remember(fontManager) { fontManager.allFontNames() }
    var fontMenuExpanded by remember { mutableStateOf(false) }
    var selectorMenuExpanded by remember { mutableStateOf(false) }
    var cssFieldValue by remember {
        mutableStateOf(
            TextFieldValue(
                text = settings.customCSS,
                selection = TextRange(settings.customCSS.length),
            ),
        )
    }

    LaunchedEffect(settings.customCSS) {
        if (settings.customCSS != cssFieldValue.text) {
            cssFieldValue = TextFieldValue(
                text = settings.customCSS,
                selection = TextRange(settings.customCSS.length),
            )
        }
    }

    fun insertCssText(text: String) {
        val nextValue = insertCustomCssText(cssFieldValue, text)
        cssFieldValue = nextValue
        onSettingsChange { it.copy(customCSS = nextValue.text) }
    }

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
                title = { Text(stringResource(R.string.dictionary_custom_css)) },
                navigationIcon = { HoshiIconBackButton(onClose) },
                actions = {
                    TextButton(onClick = { onSettingsChange { it.copy(customCSS = "") } }) {
                        Text(stringResource(R.string.action_reset))
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
            Column(modifier = Modifier.fillMaxSize()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box {
                        TextButton(onClick = { fontMenuExpanded = true }) {
                            Icon(
                                imageVector = Icons.Rounded.TextFields,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(stringResource(R.string.dictionary_custom_css_font))
                        }
                        DropdownMenu(
                            expanded = fontMenuExpanded,
                            onDismissRequest = { fontMenuExpanded = false },
                        ) {
                            fontNames.forEach { fontName ->
                                DropdownMenuItem(
                                    text = { Text(fontName) },
                                    onClick = {
                                        fontMenuExpanded = false
                                        insertCssText(
                                            fontFamilyCssDeclaration(fontManager.cssFontName(fontName)),
                                        )
                                    },
                                )
                            }
                        }
                    }
                    Box {
                        TextButton(
                            onClick = { selectorMenuExpanded = true },
                            enabled = termDictionaries.isNotEmpty(),
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.DataObject,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(stringResource(R.string.dictionary_custom_css_selector))
                        }
                        DropdownMenu(
                            expanded = selectorMenuExpanded,
                            onDismissRequest = { selectorMenuExpanded = false },
                        ) {
                            termDictionaries.forEach { dictionary ->
                                val title = dictionary.index.title
                                DropdownMenuItem(
                                    text = { Text(title) },
                                    onClick = {
                                        selectorMenuExpanded = false
                                        insertCssText(dictionarySelectorCssSnippet(title))
                                    },
                                )
                            }
                        }
                    }
                }
                HorizontalDivider(color = colorScheme.outlineVariant)
                BasicTextField(
                    value = cssFieldValue,
                    onValueChange = { value ->
                        cssFieldValue = value
                        onSettingsChange { it.copy(customCSS = value.text) }
                    },
                    textStyle = MaterialTheme.typography.bodyLarge.copy(color = colorScheme.onSurface),
                    cursorBrush = hoshiTextFieldCursorBrush(colorScheme.onSurface),
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(16.dp),
                )
            }
        }
    }
}

internal fun insertCustomCssText(
    value: TextFieldValue,
    insertedText: String,
): TextFieldValue {
    val start = value.selection.min.coerceIn(0, value.text.length)
    val end = value.selection.max.coerceIn(start, value.text.length)
    val nextText = buildString {
        append(value.text.substring(0, start))
        append(insertedText)
        append(value.text.substring(end))
    }
    return TextFieldValue(nextText, selection = TextRange(start + insertedText.length))
}

internal fun dictionarySelectorCssSnippet(dictionaryTitle: String): String =
    "[data-dictionary=\"${dictionaryTitle.cssDoubleQuotedContent()}\"] {\n    \n}\n"

internal fun fontFamilyCssDeclaration(fontFamily: String): String =
    """font-family: "${fontFamily.cssDoubleQuotedContent()}" !important;"""

private fun String.cssDoubleQuotedContent(): String =
    buildString(length) {
        this@cssDoubleQuotedContent.forEach { ch ->
            when (ch) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '\n' -> append("\\a ")
                '\r' -> Unit
                else -> append(ch)
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
                Text(
                    text = value.toString(),
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold,
                )
                Surface(
                    shape = RoundedCornerShape(24.dp),
                    color = colorScheme.surfaceVariant,
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = onDecrease, enabled = canDecrease) {
                            Icon(
                                imageVector = Icons.Rounded.Remove,
                                contentDescription = stringResource(R.string.action_decrease),
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
                                contentDescription = stringResource(R.string.action_increase),
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
    supportingText: String? = null,
    onCheckedChange: (Boolean) -> Unit,
) {
    ListItem(
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        headlineContent = { Text(title) },
        supportingContent = supportingText?.let { text -> { Text(text) } },
        trailingContent = {
            HoshiSwitch(
                checked = checked,
                onCheckedChange = onCheckedChange,
            )
        },
    )
}
