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
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import moe.antimony.hoshi.LocalHoshiAppContainer
import moe.antimony.hoshi.dictionary.DictionaryInfo
import moe.antimony.hoshi.dictionary.DictionaryType
import moe.antimony.hoshi.features.settings.SettingsDetailScaffold
import moe.antimony.hoshi.importing.ImportFileType
import moe.antimony.hoshi.importing.MultipleFileImportContent
import moe.antimony.hoshi.importing.validateImportFile
import moe.antimony.hoshi.ui.HoshiBlockingProgressOverlay
import kotlin.math.roundToInt

private val DictionarySwitchColor = Color(0xFF34C759)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DictionaryView(
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val appContainer = LocalHoshiAppContainer.current
    val dictionaryViewModel: DictionaryViewModel = viewModel(
        factory = remember(context, appContainer) {
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    DictionaryViewModel(
                        appContainer.dictionaryViewModelRepository(context.contentResolver),
                    ) as T
            }
        },
    )
    val uiState by dictionaryViewModel.uiState.collectAsState()
    var importType by remember { mutableStateOf(DictionaryType.Term) }
    var importMenuExpanded by remember { mutableStateOf(false) }
    var destination by remember { mutableStateOf<DictionaryDestination?>(null) }

    val importer = rememberLauncherForActivityResult(MultipleFileImportContent()) { uris: List<Uri> ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        val importItems = runCatching {
            uris.map { uri ->
                val displayName = context.contentResolver.validateImportFile(uri, ImportFileType.DictionaryArchive)
                DictionaryImportItem(
                    uri = uri,
                    displayName = displayName.ifBlank { uri.lastPathSegment.orEmpty() },
                )
            }
        }.onFailure { error ->
            dictionaryViewModel.showError(error.localizedMessage ?: "Select a .zip dictionary archive.")
            return@rememberLauncherForActivityResult
        }.getOrThrow()
        uris.forEach { uri ->
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION,
                )
            }
        }
        dictionaryViewModel.importDictionaries(importItems, importType)
    }

    val selectedType = uiState.selectedType
    val currentDictionaries = uiState.currentDictionaries
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
    val dictionaryStartGlobalIndex = 1 + if (uiState.errorMessage != null) 1 else 0
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
        title = "Dictionaries",
        onClose = {
            if (!uiState.isImporting) {
                onClose()
            }
        },
        modifier = modifier.fillMaxSize(),
        containerColor = colorScheme.background,
        contentColor = colorScheme.onBackground,
        actions = {
            IconButton(
                onClick = { destination = DictionaryDestination.CustomCss },
                enabled = !uiState.isImporting,
            ) {
                Icon(
                    imageVector = Icons.Rounded.DataObject,
                    contentDescription = "Custom CSS",
                )
            }
            Box {
                IconButton(
                    onClick = { importMenuExpanded = true },
                    enabled = !uiState.isImporting,
                ) {
                    if (uiState.isImporting) {
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
                                            checked = uiState.settings.dictionaryTabDefault,
                                            onCheckedChange = { checked ->
                                                dictionaryViewModel.updateSettings { it.copy(dictionaryTabDefault = checked) }
                                            },
                                            enabled = !uiState.isImporting,
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
                                    modifier = Modifier.clickable(enabled = !uiState.isImporting) {
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
                                    enabled = !uiState.isImporting,
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
                uiState.errorMessage?.let { item { Text(it, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) } }
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
                            enabled = !uiState.isImporting,
                            swipeEnabled = draggedFileName == null && !isDragSettling && !uiState.isImporting,
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
            if (uiState.isImporting) {
                HoshiBlockingProgressOverlay(
                    message = uiState.currentImportMessage ?: "Loading...",
                    modifier = Modifier
                        .fillMaxSize()
                        .zIndex(2f),
                )
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
                        contentDescription = "Delete Dictionary",
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
    Box(
        modifier = modifier
            .width(32.dp)
            .height(56.dp)
            .semantics { contentDescription = "Reorder Dictionary" },
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
                    ToggleRow("Scan Non-Japanese Text", settings.scanNonJapaneseText) {
                        onSettingsChange { current -> current.copy(scanNonJapaneseText = it) }
                    }
                    GroupDivider()
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
                SectionLabel("Collapse Dictionaries")
                SettingsGroup {
                    ListItem(
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                        headlineContent = { Text("Mode") },
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
                                    ) {
                                        Text(mode.rawValue)
                                    }
                                }
                            }
                        },
                    )
                    if (settings.collapseMode != DictionaryCollapseMode.ExpandAll) {
                        GroupDivider()
                        ToggleRow("Expand First Dictionary", settings.expandFirstDictionary) {
                            onSettingsChange { current -> current.copy(expandFirstDictionary = it) }
                        }
                    }
                    if (settings.collapseMode == DictionaryCollapseMode.Custom) {
                        GroupDivider()
                        ListItem(
                            modifier = Modifier.clickable { showCollapsedDictionaries = true },
                            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                            headlineContent = { Text("Configure") },
                            trailingContent = {
                                Icon(
                                    imageVector = Icons.Rounded.ChevronRight,
                                    contentDescription = null,
                                )
                            },
                        )
                    }
                }
                SectionLabel("Behaviour")
                SettingsGroup {
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
                    GroupDivider()
                    ToggleRow("Compact Pitch Accents", settings.compactPitchAccents) {
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
        title = "Collapse Dictionaries",
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
                        text = "No term dictionaries",
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
                                text = if (collapsedDictionaries.contains(title)) "Collapsed" else "Expanded",
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
