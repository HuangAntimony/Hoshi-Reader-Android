package moe.antimony.hoshi.features.bookshelf

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.LruCache
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.LocalOverscrollFactory
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.MenuBook
import androidx.compose.material.icons.automirrored.rounded.Sort
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.BugReport
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Done
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Inventory2
import androidx.compose.material.icons.rounded.Keyboard
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.ReportProblem
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Translate
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffold
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteType
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import moe.antimony.hoshi.LocalHoshiAppContainer
import moe.antimony.hoshi.epub.BookEntry
import moe.antimony.hoshi.epub.BookRepository
import moe.antimony.hoshi.epub.BookShelf
import moe.antimony.hoshi.epub.BookSortOption
import moe.antimony.hoshi.features.reader.ReaderSettings
import moe.antimony.hoshi.features.sync.SyncDirection
import moe.antimony.hoshi.features.sync.SyncMode
import moe.antimony.hoshi.features.sync.SyncSettings
import moe.antimony.hoshi.importing.ImportFileType
import moe.antimony.hoshi.importing.MultipleFileImportContent
import moe.antimony.hoshi.importing.importDisplayName
import moe.antimony.hoshi.ui.HoshiBlockingProgressOverlay
import moe.antimony.hoshi.ui.theme.LocalHoshiDarkTheme
import moe.antimony.hoshi.ui.theme.LocalHoshiEInkMode
import java.io.File
import kotlin.math.max

data class SasayakiMatchRequest(
    val bookId: String,
    val bookEntry: BookEntry,
)

@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
fun BookshelfView(
    pendingImportUri: Uri? = null,
    onPendingImportConsumed: () -> Unit = {},
    onOpenReader: (String) -> Unit,
    onOpenSasayakiMatch: (SasayakiMatchRequest) -> Unit,
    refreshKey: Int = 0,
    layoutSpec: MainShellLayoutSpec,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val appContainer = LocalHoshiAppContainer.current
    val bookRepository = appContainer.bookRepository
    val syncSettings by appContainer.syncSettingsRepository.settings.collectAsState(initial = SyncSettings())
    val readerSettings by appContainer.readerSettingsRepository.settings.collectAsState(initial = ReaderSettings())
    val sasayakiSettings by appContainer.sasayakiSettingsRepository.settings.collectAsState(
        initial = moe.antimony.hoshi.features.sasayaki.SasayakiSettings(),
    )
    val booksViewModel: BookshelfViewModel = viewModel(
        factory = remember(context, appContainer) {
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    BookshelfViewModel(
                        appContainer.bookshelfRepository(context.contentResolver),
                    ) as T
            }
        },
    )
    val uiState by booksViewModel.uiState.collectAsState()
    var sortMenuExpanded by remember { mutableStateOf(false) }
    var contextMenuTarget by remember { mutableStateOf<BookContextMenuTarget?>(null) }
    var deleteCandidate by remember { mutableStateOf<BookEntry?>(null) }
    var markReadCandidate by remember { mutableStateOf<BookEntry?>(null) }
    var showBulkDeleteConfirmation by remember { mutableStateOf(false) }
    var showShelfManagement by remember { mutableStateOf(false) }

    val importer = rememberLauncherForActivityResult(MultipleFileImportContent()) { uris: List<Uri> ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        val imports = uris.map { uri ->
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION,
                )
            }
            BookImportItem(
                uri = uri,
                displayName = context.contentResolver.importDisplayName(uri),
            )
        }
        booksViewModel.importBooks(imports)
    }

    fun launchBookImporter() {
        importer.launch(ImportFileType.Epub.mimeTypes)
    }

    LaunchedEffect(refreshKey) {
        booksViewModel.reloadBookEntries()
    }

    LaunchedEffect(Unit) {
        booksViewModel.rebuildLookupQuery()
    }

    LaunchedEffect(sasayakiSettings.enabled) {
        booksViewModel.setSasayakiEnabled(sasayakiSettings.enabled)
    }

    LaunchedEffect(pendingImportUri) {
        val uri = pendingImportUri ?: return@LaunchedEffect
        onPendingImportConsumed()
        booksViewModel.importBook(uri)
    }

    LaunchedEffect(uiState.openReaderBookId) {
        val bookId = uiState.openReaderBookId ?: return@LaunchedEffect
        onOpenReader(bookId)
        booksViewModel.consumeOpenReaderEvent()
    }

    BooksTab(
        modifier = modifier,
        layoutSpec = layoutSpec,
        bookEntries = uiState.bookEntries,
        sections = uiState.sections,
        bookProgressById = uiState.bookProgressById,
        sortOption = uiState.sortOption,
        bookRepository = bookRepository,
        hasLoadedBooks = uiState.hasLoadedBooks,
        isLoading = uiState.isLoading,
        blockingProgressMessage = uiState.blockingProgressMessage,
        shelves = uiState.shelves,
        isSelecting = uiState.isSelecting,
        selectedBookIds = uiState.selectedBookIds,
        shelfExpansionState = uiState.shelfExpansionState,
        sortMenuExpanded = sortMenuExpanded,
        onSortMenuExpandedChange = { sortMenuExpanded = it },
        onSortChange = {
            sortMenuExpanded = false
            booksViewModel.changeSort(it)
        },
        onStartSelecting = booksViewModel::startSelecting,
        onClearSelection = booksViewModel::clearSelection,
        onToggleSelectedBook = booksViewModel::toggleSelectedBook,
        onShelfExpandedChange = booksViewModel::setShelfExpanded,
        onMoveSelectedBooks = booksViewModel::moveSelectedBooks,
        onDeleteSelectedBooks = { showBulkDeleteConfirmation = true },
        onManageShelves = { showShelfManagement = true },
        onImport = ::launchBookImporter,
        onOpenBook = booksViewModel::openBook,
        contextMenuTarget = contextMenuTarget,
        onContextMenuTargetChange = { contextMenuTarget = it },
        onDeleteCandidate = { deleteCandidate = it },
        onMarkReadCandidate = { markReadCandidate = it },
        onMoveBook = booksViewModel::moveBook,
        sasayakiEnabled = uiState.sasayakiEnabled,
        onMatchSasayaki = { entry ->
            onOpenSasayakiMatch(SasayakiMatchRequest(entry.metadata.id, entry))
        },
        syncSettings = syncSettings,
        onSyncBook = { entry, direction ->
            booksViewModel.syncBook(
                entry = entry,
                direction = direction,
                syncStats = readerSettings.statisticsSyncEnabled,
                statsSyncMode = readerSettings.statisticsSyncMode,
                syncAudioBook = sasayakiSettings.enabled && sasayakiSettings.syncEnabled,
            )
        },
    )

    uiState.statusMessage?.let { message ->
        AlertDialog(
            onDismissRequest = booksViewModel::consumeStatusMessage,
            text = { Text(message) },
            confirmButton = {
                TextButton(onClick = booksViewModel::consumeStatusMessage) {
                    Text("OK")
                }
            },
        )
    }

    uiState.errorMessage?.let { message ->
        AlertDialog(
            onDismissRequest = booksViewModel::consumeErrorMessage,
            title = { Text("Error") },
            text = { Text(message) },
            confirmButton = {
                TextButton(onClick = booksViewModel::consumeErrorMessage) {
                    Text("OK")
                }
            },
        )
    }

    deleteCandidate?.let { candidate ->
        AlertDialog(
            onDismissRequest = { deleteCandidate = null },
            title = { Text("Delete \"${candidate.metadata.title ?: ""}\"?") },
            confirmButton = {
                TextButton(
                    onClick = {
                        booksViewModel.deleteBook(candidate)
                        deleteCandidate = null
                    },
                ) {
                    Text("Delete")
                }
            },
            dismissButton = {
                TextButton(onClick = { deleteCandidate = null }) {
                    Text("Cancel")
                }
            },
        )
    }

    markReadCandidate?.let { candidate ->
        AlertDialog(
            onDismissRequest = { markReadCandidate = null },
            title = { Text("Mark \"${candidate.metadata.title ?: ""}\" as read?") },
            confirmButton = {
                TextButton(
                    onClick = {
                        booksViewModel.markRead(candidate)
                        markReadCandidate = null
                    },
                ) {
                    Text("Confirm")
                }
            },
            dismissButton = {
                TextButton(onClick = { markReadCandidate = null }) {
                    Text("Cancel")
                }
            },
        )
    }

    if (showBulkDeleteConfirmation) {
        AlertDialog(
            onDismissRequest = { showBulkDeleteConfirmation = false },
            title = { Text("Delete ${uiState.selectedBookIds.size} book(s)?") },
            confirmButton = {
                TextButton(
                    onClick = {
                        booksViewModel.deleteSelectedBooks()
                        showBulkDeleteConfirmation = false
                    },
                ) {
                    Text("Delete")
                }
            },
            dismissButton = {
                TextButton(onClick = { showBulkDeleteConfirmation = false }) {
                    Text("Cancel")
                }
            },
        )
    }

    if (showShelfManagement) {
        ShelfManagementDialog(
            shelves = uiState.shelves,
            showReading = uiState.showReading,
            onShowReadingChange = booksViewModel::changeShowReading,
            onCreateShelf = booksViewModel::createShelf,
            onDeleteShelf = booksViewModel::deleteShelf,
            onMoveShelf = booksViewModel::moveShelf,
            onDismiss = { showShelfManagement = false },
        )
    }
}

@Composable
internal fun HoshiMainShell(
    selectedTab: MainTab,
    onSelectedTabChange: (MainTab) -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable (Modifier, MainShellLayoutSpec) -> Unit,
) {
    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val layoutSpec = MainShellLayoutSpec.forWidthDp(maxWidth.value.toInt())
        if (layoutSpec.navigationLayout == MainShellNavigationLayout.BottomBar) {
            Scaffold(
                modifier = Modifier.fillMaxSize(),
                containerColor = MaterialTheme.colorScheme.background,
                contentColor = MaterialTheme.colorScheme.onBackground,
                contentWindowInsets = WindowInsets(0.dp, 0.dp, 0.dp, 0.dp),
                bottomBar = {
                    HoshiCompactBottomNavigation(
                        selectedTab = selectedTab,
                        onSelectedTabChange = onSelectedTabChange,
                        layoutSpec = layoutSpec,
                    )
                },
            ) { innerPadding ->
                Box(Modifier.fillMaxSize()) {
                    content(
                        Modifier
                            .fillMaxSize()
                            .padding(innerPadding),
                        layoutSpec,
                    )
                    HorizontalDivider(
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .padding(bottom = innerPadding.calculateBottomPadding()),
                        color = MaterialTheme.colorScheme.outlineVariant,
                    )
                }
            }
        } else {
            NavigationSuiteScaffold(
                modifier = Modifier.fillMaxSize(),
                layoutType = layoutSpec.toNavigationSuiteType(),
                navigationSuiteItems = {
                    MainTab.entries.forEach { tab ->
                        item(
                            selected = tab == selectedTab,
                            onClick = { onSelectedTabChange(tab) },
                            icon = { BottomTabGlyph(tab, Modifier.size(24.dp)) },
                            label = { Text(tab.label) },
                        )
                    }
                },
                containerColor = MaterialTheme.colorScheme.background,
                contentColor = MaterialTheme.colorScheme.onBackground,
            ) {
                content(
                    Modifier.fillMaxSize(),
                    layoutSpec,
                )
            }
        }
    }
}

internal const val CompactNavigationBarTag = "compact-navigation-bar"

@Composable
private fun HoshiCompactBottomNavigation(
    selectedTab: MainTab,
    onSelectedTabChange: (MainTab) -> Unit,
    layoutSpec: MainShellLayoutSpec,
) {
    val containerColor = MaterialTheme.colorScheme.background
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .testTag(CompactNavigationBarTag),
        color = containerColor,
        contentColor = MaterialTheme.colorScheme.onSurface,
    ) {
        Column {
            Spacer(Modifier.height(4.dp))
            NavigationBar(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(layoutSpec.compactNavigationHeightDp.dp),
                containerColor = containerColor,
                contentColor = MaterialTheme.colorScheme.onSurface,
                tonalElevation = 0.dp,
                windowInsets = WindowInsets(0.dp, 0.dp, 0.dp, 0.dp),
            ) {
                MainTab.entries.forEach { tab ->
                    NavigationBarItem(
                        selected = tab == selectedTab,
                        onClick = { onSelectedTabChange(tab) },
                        icon = { BottomTabGlyph(tab, Modifier.size(24.dp)) },
                        label = {
                            Text(
                                text = tab.label,
                                style = MaterialTheme.typography.labelMedium,
                            )
                        },
                    )
                }
            }
            Spacer(
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding(),
            )
        }
    }
}

private fun MainShellLayoutSpec.toNavigationSuiteType(): NavigationSuiteType =
    when (navigationLayout) {
        MainShellNavigationLayout.BottomBar -> NavigationSuiteType.NavigationBar
        MainShellNavigationLayout.NavigationRail -> NavigationSuiteType.NavigationRail
    }

@Composable
private fun MainShellTextStyle.toTextStyle(): TextStyle =
    when (this) {
        MainShellTextStyle.BodyLarge -> MaterialTheme.typography.bodyLarge
        MainShellTextStyle.TitleMedium -> MaterialTheme.typography.titleMedium
        MainShellTextStyle.TitleLarge -> MaterialTheme.typography.titleLarge
    }

private fun MainShellFontWeight.toFontWeight(): FontWeight =
    when (this) {
        MainShellFontWeight.Normal -> FontWeight.Normal
        MainShellFontWeight.Medium -> FontWeight.Medium
        MainShellFontWeight.SemiBold -> FontWeight.SemiBold
    }

internal data class BookContextMenuTarget(
    val sectionKey: String,
    val bookId: String,
)

internal fun bookContextMenuTarget(
    section: BookshelfSectionModel,
    entry: BookEntry,
): BookContextMenuTarget =
    BookContextMenuTarget(
        sectionKey = when {
            section.isReading -> "reading"
            section.shelfName != null -> "shelf:${section.shelfName}"
            else -> "unshelved"
        },
        bookId = entry.metadata.id,
    )

internal fun isBookContextMenuExpanded(
    activeTarget: BookContextMenuTarget?,
    section: BookshelfSectionModel,
    entry: BookEntry,
): Boolean =
    activeTarget == bookContextMenuTarget(section, entry)

@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun BooksTab(
    layoutSpec: MainShellLayoutSpec,
    bookEntries: List<BookEntry>,
    sections: List<BookshelfSectionModel>,
    bookProgressById: Map<String, Double>,
    sortOption: BookSortOption,
    bookRepository: BookRepository,
    hasLoadedBooks: Boolean,
    isLoading: Boolean,
    blockingProgressMessage: String?,
    shelves: List<BookShelf>,
    isSelecting: Boolean,
    selectedBookIds: Set<String>,
    shelfExpansionState: Map<String, Boolean>,
    sortMenuExpanded: Boolean,
    onSortMenuExpandedChange: (Boolean) -> Unit,
    onSortChange: (BookSortOption) -> Unit,
    onStartSelecting: () -> Unit,
    onClearSelection: () -> Unit,
    onToggleSelectedBook: (BookEntry) -> Unit,
    onShelfExpandedChange: (String, Boolean) -> Unit,
    onMoveSelectedBooks: (String?) -> Unit,
    onDeleteSelectedBooks: () -> Unit,
    onManageShelves: () -> Unit,
    onImport: () -> Unit,
    onOpenBook: (BookEntry) -> Unit,
    contextMenuTarget: BookContextMenuTarget?,
    onContextMenuTargetChange: (BookContextMenuTarget?) -> Unit,
    onDeleteCandidate: (BookEntry) -> Unit,
    onMarkReadCandidate: (BookEntry) -> Unit,
    onMoveBook: (BookEntry, String?) -> Unit,
    sasayakiEnabled: Boolean,
    onMatchSasayaki: (BookEntry) -> Unit,
    syncSettings: SyncSettings,
    onSyncBook: (BookEntry, SyncDirection?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val fileTaskBlocked = blockingProgressMessage != null
    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        contentColor = MaterialTheme.colorScheme.onBackground,
        contentWindowInsets = WindowInsets(0.dp, 0.dp, 0.dp, 0.dp),
        topBar = {
            BooksTopAppBar(
                layoutSpec = layoutSpec,
                sortMenuExpanded = sortMenuExpanded,
                sortOption = sortOption,
                onSortMenuExpandedChange = onSortMenuExpandedChange,
                onSortChange = onSortChange,
                shelves = shelves,
                isSelecting = isSelecting,
                selectedCount = selectedBookIds.size,
                enabled = !fileTaskBlocked,
                onStartSelecting = onStartSelecting,
                onClearSelection = onClearSelection,
                onMoveSelectedBooks = onMoveSelectedBooks,
                onDeleteSelectedBooks = onDeleteSelectedBooks,
                onManageShelves = onManageShelves,
                onImport = onImport,
            )
        },
    ) { innerPadding ->
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            val contentWidthDp = layoutSpec.constrainedContentWidthDp(maxWidth.value.toInt())
            val bookContentAlignment = if (layoutSpec.navigationLayout == MainShellNavigationLayout.NavigationRail) {
                Alignment.TopStart
            } else {
                Alignment.TopCenter
            }
            val contentModifier = Modifier
                .align(bookContentAlignment)
                .widthIn(max = layoutSpec.contentMaxWidthDp.dp)
                .fillMaxWidth()

            when {
                isLoading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
                !hasLoadedBooks -> Box(Modifier.fillMaxSize())
                hasLoadedBooks && bookEntries.isEmpty() -> EmptyBooksView(
                    enabled = !fileTaskBlocked,
                    onImport = onImport,
                    modifier = Modifier
                        .align(Alignment.Center)
                        .widthIn(max = layoutSpec.contentMaxWidthDp.dp)
                        .fillMaxWidth()
                        .padding(horizontal = layoutSpec.pageHorizontalPaddingDp.dp),
                )
                else -> CompositionLocalProvider(LocalOverscrollFactory provides null) {
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(layoutSpec.bookGridColumns(contentWidthDp)),
                        modifier = contentModifier.fillMaxHeight(),
                        contentPadding = PaddingValues(
                            start = layoutSpec.pageHorizontalPaddingDp.dp,
                            end = layoutSpec.pageHorizontalPaddingDp.dp,
                            top = layoutSpec.bookGridTopPaddingDp.dp,
                            bottom = layoutSpec.bookGridBottomPaddingDp.dp,
                        ),
                        horizontalArrangement = Arrangement.spacedBy(layoutSpec.bookGridSpacingDp.dp),
                        verticalArrangement = Arrangement.spacedBy(layoutSpec.bookGridVerticalSpacingDp.dp),
                    ) {
                        sections.filter { it.books.isNotEmpty() }.forEach { section ->
                            val collapseKey = section.collapseKey
                            val isExpanded = if (!section.isCollapsible) {
                                true
                            } else {
                                collapseKey?.let { key -> shelfExpansionState[key] ?: section.isReading } ?: true
                            }
                            item(
                                key = "header:${section.title}",
                                span = { androidx.compose.foundation.lazy.grid.GridItemSpan(maxLineSpan) },
                            ) {
                                BookshelfSectionHeader(
                                    title = section.title,
                                    count = section.books.size,
                                    layoutSpec = layoutSpec,
                                    isCollapsible = section.isCollapsible,
                                    isExpanded = isExpanded,
                                    onToggle = {
                                        collapseKey?.let { key ->
                                            onShelfExpandedChange(key, !isExpanded)
                                        }
                                    },
                                )
                            }
                            if (isExpanded) {
                                items(
                                    items = section.books,
                                    key = { "${section.title}:${it.metadata.id}" },
                                ) { entry ->
                                    Box {
                                        BookGridCell(
                                            entry = entry,
                                            progress = bookProgressById[entry.metadata.id] ?: 0.0,
                                            bookRepository = bookRepository,
                                            layoutSpec = layoutSpec,
                                            isSelecting = isSelecting,
                                            isSelected = entry.metadata.id in selectedBookIds,
                                            enabled = !fileTaskBlocked,
                                            onOpen = { onOpenBook(entry) },
                                            onToggleSelected = { onToggleSelectedBook(entry) },
                                            onLongPress = {
                                                if (!isSelecting) {
                                                    onContextMenuTargetChange(bookContextMenuTarget(section, entry))
                                                }
                                            },
                                        )
                                        BookContextMenu(
                                            entry = entry,
                                            shelves = shelves,
                                            currentShelfName = section.shelfName,
                                            hideMove = section.isReading,
                                            expanded = isBookContextMenuExpanded(contextMenuTarget, section, entry),
                                            sasayakiEnabled = sasayakiEnabled,
                                            onDismiss = { onContextMenuTargetChange(null) },
                                            onMoveBook = onMoveBook,
                                            onMatchSasayaki = onMatchSasayaki,
                                            onMarkReadCandidate = onMarkReadCandidate,
                                            onDeleteCandidate = onDeleteCandidate,
                                            syncSettings = syncSettings,
                                            onSyncBook = onSyncBook,
                                        )
                                    }
                                }
                            } else {
                                item(
                                    key = "preview:${section.title}",
                                    span = { androidx.compose.foundation.lazy.grid.GridItemSpan(maxLineSpan) },
                                ) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clickable(enabled = !fileTaskBlocked) {
                                                collapseKey?.let { key ->
                                                    onShelfExpandedChange(key, true)
                                                }
                                            },
                                        horizontalArrangement = Arrangement.spacedBy(CollapsedShelfCoverSpacingDp.dp),
                                    ) {
                                        val collapsedCoverWidthDp =
                                            layoutSpec.collapsedShelfPreviewCoverWidthDp(contentWidthDp)
                                        section.books.take(layoutSpec.collapsedShelfPreviewColumns(contentWidthDp)).forEach { entry ->
                                            BookCoverCard(
                                                entry = entry,
                                                bookRepository = bookRepository,
                                                modifier = Modifier.width(collapsedCoverWidthDp.dp),
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
            blockingProgressMessage?.let { message ->
                HoshiBlockingProgressOverlay(
                    message = message,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun BooksTopAppBar(
    layoutSpec: MainShellLayoutSpec,
    sortMenuExpanded: Boolean,
    sortOption: BookSortOption,
    onSortMenuExpandedChange: (Boolean) -> Unit,
    onSortChange: (BookSortOption) -> Unit,
    shelves: List<BookShelf>,
    isSelecting: Boolean,
    selectedCount: Int,
    enabled: Boolean,
    onStartSelecting: () -> Unit,
    onClearSelection: () -> Unit,
    onMoveSelectedBooks: (String?) -> Unit,
    onDeleteSelectedBooks: () -> Unit,
    onManageShelves: () -> Unit,
    onImport: () -> Unit,
) {
    var moveMenuExpanded by remember { mutableStateOf(false) }
    CenterAlignedTopAppBar(
        title = {
            Text(
                text = if (isSelecting) "$selectedCount Selected" else "Books",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
            )
        },
        navigationIcon = {
            if (isSelecting) {
                TextButton(onClick = onClearSelection, enabled = enabled) {
                    Text("Done", fontWeight = FontWeight.SemiBold)
                }
            } else {
                Row {
                    Box {
                        IconButton(
                            onClick = { onSortMenuExpandedChange(true) },
                            enabled = enabled,
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Rounded.Sort,
                                contentDescription = "Sort books",
                            )
                        }
                        DropdownMenu(
                            expanded = sortMenuExpanded,
                            onDismissRequest = { onSortMenuExpandedChange(false) },
                        ) {
                            SortMenuHeader(text = "Sorting by...")
                            HorizontalDivider()
                            DropdownMenuItem(
                                text = { Text("Recent") },
                                trailingIcon = selectedSortIcon(BookSortOption.Recent, sortOption),
                                onClick = { onSortChange(BookSortOption.Recent) },
                            )
                            DropdownMenuItem(
                                text = { Text("Title") },
                                trailingIcon = selectedSortIcon(BookSortOption.Title, sortOption),
                                onClick = { onSortChange(BookSortOption.Title) },
                            )
                        }
                    }
                    IconButton(onClick = onStartSelecting, enabled = enabled) {
                        Icon(
                            imageVector = Icons.Rounded.Done,
                            contentDescription = "Select books",
                        )
                    }
                }
            }
        },
        actions = {
            if (isSelecting) {
                Box {
                    IconButton(
                        onClick = { moveMenuExpanded = true },
                        enabled = enabled && selectedCount > 0,
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.FolderOpen,
                            contentDescription = "Move selected books",
                        )
                    }
                    DropdownMenu(
                        expanded = moveMenuExpanded,
                        onDismissRequest = { moveMenuExpanded = false },
                    ) {
                        DropdownMenuItem(
                            text = { Text("None") },
                            onClick = {
                                moveMenuExpanded = false
                                onMoveSelectedBooks(null)
                            },
                        )
                        shelves.forEach { shelf ->
                            DropdownMenuItem(
                                text = { Text(shelf.name) },
                                onClick = {
                                    moveMenuExpanded = false
                                    onMoveSelectedBooks(shelf.name)
                                },
                            )
                        }
                    }
                }
                IconButton(
                    onClick = onDeleteSelectedBooks,
                    enabled = enabled && selectedCount > 0,
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Delete,
                        contentDescription = "Delete selected books",
                    )
                }
            } else {
                IconButton(onClick = onManageShelves, enabled = enabled) {
                    Icon(
                        imageVector = Icons.Rounded.FolderOpen,
                        contentDescription = "Manage Shelves",
                    )
                }
                IconButton(onClick = onImport, enabled = enabled) {
                    Icon(
                        imageVector = Icons.Rounded.Add,
                        contentDescription = "Import EPUB",
                    )
                }
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = MaterialTheme.colorScheme.background,
            scrolledContainerColor = MaterialTheme.colorScheme.background,
        ),
    )
}

private fun selectedSortIcon(
    option: BookSortOption,
    selectedOption: BookSortOption,
): (@Composable () -> Unit)? =
    if (option == selectedOption) {
        {
            Icon(
                imageVector = Icons.Rounded.Done,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
            )
        }
    } else {
        null
    }

@Composable
private fun SortMenuHeader(text: String) {
    Text(
        text = text,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun BookshelfSectionHeader(
    title: String,
    count: Int,
    layoutSpec: MainShellLayoutSpec,
    isCollapsible: Boolean = false,
    isExpanded: Boolean = true,
    onToggle: () -> Unit = {},
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = isCollapsible, onClick = onToggle)
            .padding(vertical = layoutSpec.shelfHeaderVerticalPaddingDp.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title,
            style = layoutSpec.shelfTitleTextStyle.toTextStyle(),
            fontWeight = layoutSpec.shelfTitleFontWeight.toFontWeight(),
            color = MaterialTheme.colorScheme.onBackground,
        )
        Spacer(Modifier.width(12.dp))
        Text(
            text = count.toString(),
            style = layoutSpec.shelfCountTextStyle.toTextStyle(),
            color = Color(0xFF8C8C92),
        )
        if (isCollapsible) {
            Spacer(Modifier.width(8.dp))
            ChevronRightGlyph(
                MaterialTheme.colorScheme.onBackground,
                Modifier
                    .size(20.dp)
                    .rotate(if (isExpanded) 90f else 0f),
            )
        }
    }
}

@Composable
private fun BookGridCell(
    entry: BookEntry,
    progress: Double,
    bookRepository: BookRepository,
    layoutSpec: MainShellLayoutSpec,
    isSelecting: Boolean,
    isSelected: Boolean,
    enabled: Boolean,
    onOpen: () -> Unit,
    onToggleSelected: () -> Unit,
    onLongPress: () -> Unit,
) {
    Column(
        modifier = Modifier.combinedClickable(
            enabled = enabled,
            onClick = {
                if (isSelecting) {
                    onToggleSelected()
                } else {
                    onOpen()
                }
            },
            onLongClick = onLongPress,
        ),
    ) {
        Box {
            BookCoverCard(entry = entry, bookRepository = bookRepository)
            if (isSelecting) {
                Icon(
                    imageVector = Icons.Rounded.CheckCircle,
                    contentDescription = if (isSelected) "Selected" else "Not selected",
                    tint = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(8.dp)
                        .background(MaterialTheme.colorScheme.background, RoundedCornerShape(100))
                        .size(28.dp),
                )
            } else if (isBookCompleted(progress)) {
                Icon(
                    imageVector = Icons.Rounded.CheckCircle,
                    contentDescription = "Read",
                    tint = Color(0xFF8C8C92),
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(8.dp)
                        .background(MaterialTheme.colorScheme.background, RoundedCornerShape(100))
                        .size(28.dp),
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        ReadingProgressPill(
            progress = progress,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = entry.metadata.title ?: entry.root.name,
            style = layoutSpec.bookTitleTextStyle.toTextStyle(),
            fontWeight = layoutSpec.bookTitleFontWeight.toFontWeight(),
            color = MaterialTheme.colorScheme.onBackground,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

internal suspend fun loadBookProgressById(
    entries: List<BookEntry>,
    bookRepository: BookRepository,
): Map<String, Double> =
    entries.associate { entry ->
        entry.metadata.id to bookRepository.loadReadingProgress(entry.root)
    }

@Composable
private fun BookCoverCard(
    entry: BookEntry,
    bookRepository: BookRepository,
    modifier: Modifier = Modifier,
) {
    val coverFile by produceState<File?>(initialValue = null, key1 = entry, key2 = bookRepository) {
        value = bookRepository.coverFile(entry)
    }
    val bitmap by produceState<Bitmap?>(initialValue = null, key1 = coverFile) {
        value = BookCoverBitmapCache.load(coverFile)
    }
    val shape = RoundedCornerShape(10.dp)
    val coverPlaceholderColor = MaterialTheme.colorScheme.background
    val coverBorderColor = if (LocalHoshiDarkTheme.current) {
        Color.White.copy(alpha = 0.9f)
    } else {
        Color.Black.copy(alpha = 0.18f)
    }
    Box(
        modifier = Modifier
            .then(modifier)
            .fillMaxWidth()
            .aspectRatio(0.72f)
            .clip(shape)
            .background(coverPlaceholderColor)
            .border(BorderStroke(1.dp, coverBorderColor), shape),
        contentAlignment = Alignment.Center,
    ) {
        bitmap?.let { coverBitmap ->
            Image(
                bitmap = coverBitmap.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

private object BookCoverBitmapCache {
    private const val MaxCoverDimensionPx = 900
    private val cache = object : LruCache<String, Bitmap>(24 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }

    suspend fun load(coverFile: File?): Bitmap? = withContext(Dispatchers.IO) {
        coverFile ?: return@withContext null
        val key = coverFile.cacheKey()
        synchronized(cache) {
            cache.get(key)?.let { return@withContext it }
        }
        val bitmap = decodeSampledCoverBitmap(coverFile, MaxCoverDimensionPx) ?: return@withContext null
        synchronized(cache) {
            cache.put(key, bitmap)
        }
        bitmap
    }

    private fun File.cacheKey(): String = "$absolutePath:${lastModified()}:${length()}"
}

internal fun coverDecodeSampleSize(width: Int, height: Int, maxDimensionPx: Int): Int {
    if (width <= 0 || height <= 0 || maxDimensionPx <= 0) return 1
    var sampleSize = 1
    while (max(width / sampleSize, height / sampleSize) > maxDimensionPx) {
        sampleSize *= 2
    }
    return sampleSize
}

private fun decodeSampledCoverBitmap(file: File, maxDimensionPx: Int): Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.absolutePath, bounds)
    val options = BitmapFactory.Options().apply {
        inSampleSize = coverDecodeSampleSize(bounds.outWidth, bounds.outHeight, maxDimensionPx)
    }
    return BitmapFactory.decodeFile(file.absolutePath, options)
}

@Composable
private fun ReadingProgressPill(progress: Double, modifier: Modifier = Modifier) {
    val clamped = progress.coerceIn(0.0, 1.0).toFloat()
    val eInkMode = LocalHoshiEInkMode.current
    val colorScheme = MaterialTheme.colorScheme
    val progressTrackColor = if (eInkMode) colorScheme.surface else Color(0xFFD7D7DB)
    val progressFillColor = if (eInkMode) colorScheme.onSurface else Color(0xFFAFAFB4)
    val progressBorderColor = if (eInkMode) colorScheme.outline else Color.Transparent
    val progressTextColor = if (eInkMode) colorScheme.onBackground else Color(0xFF76767C)
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .height(5.dp)
                .weight(1f)
                .clip(RoundedCornerShape(100))
                .background(progressTrackColor)
                .border(BorderStroke(1.dp, progressBorderColor), RoundedCornerShape(100)),
        ) {
            if (clamped > 0f) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(clamped)
                        .height(5.dp)
                        .clip(RoundedCornerShape(100))
                        .background(progressFillColor),
                )
            }
        }
        Spacer(Modifier.width(8.dp))
        Text(
            text = bookshelfProgressText(progress),
            style = MaterialTheme.typography.labelLarge,
            color = progressTextColor,
            fontWeight = FontWeight.Bold,
        )
    }
}

@Composable
private fun BookContextMenu(
    entry: BookEntry,
    shelves: List<BookShelf>,
    currentShelfName: String?,
    hideMove: Boolean,
    expanded: Boolean,
    sasayakiEnabled: Boolean,
    onDismiss: () -> Unit,
    onMoveBook: (BookEntry, String?) -> Unit,
    onMatchSasayaki: (BookEntry) -> Unit,
    onMarkReadCandidate: (BookEntry) -> Unit,
    onDeleteCandidate: (BookEntry) -> Unit,
    syncSettings: SyncSettings,
    onSyncBook: (BookEntry, SyncDirection?) -> Unit,
) {
    var moveMenuExpanded by remember { mutableStateOf(false) }
    var syncMenuExpanded by remember { mutableStateOf(false) }
    LaunchedEffect(expanded, hideMove) {
        if (!expanded || hideMove) {
            moveMenuExpanded = false
        }
        if (!expanded) {
            syncMenuExpanded = false
        }
    }
    DropdownMenu(
        expanded = expanded && !moveMenuExpanded && !syncMenuExpanded,
        onDismissRequest = onDismiss,
    ) {
        if (syncSettings.enabled) {
            if (syncSettings.mode == SyncMode.Manual) {
                DropdownMenuItem(
                    text = { Text("Sync") },
                    trailingIcon = {
                        Icon(
                            imageVector = Icons.Rounded.ChevronRight,
                            contentDescription = null,
                        )
                    },
                    onClick = { syncMenuExpanded = true },
                )
            } else {
                DropdownMenuItem(
                    text = { Text("Sync") },
                    onClick = {
                        onSyncBook(entry, null)
                        onDismiss()
                    },
                )
            }
            HorizontalDivider()
        }
        if (!hideMove) {
            DropdownMenuItem(
                text = { Text("Move") },
                trailingIcon = {
                    Icon(
                        imageVector = Icons.Rounded.ChevronRight,
                        contentDescription = null,
                    )
                },
                onClick = { moveMenuExpanded = true },
            )
            HorizontalDivider()
        }
        if (sasayakiEnabled) {
            DropdownMenuItem(
                text = { Text("Match Sasayaki") },
                onClick = {
                    onMatchSasayaki(entry)
                    onDismiss()
                },
            )
        }
        DropdownMenuItem(
            text = { Text("Mark Read") },
            onClick = {
                onMarkReadCandidate(entry)
                onDismiss()
            },
        )
        DropdownMenuItem(
            text = { Text("Delete") },
            onClick = {
                onDeleteCandidate(entry)
                onDismiss()
            },
        )
    }
    MoveDestinationMenu(
        entry = entry,
        shelves = shelves,
        currentShelfName = currentShelfName,
        expanded = expanded && moveMenuExpanded,
        onDismiss = onDismiss,
        onMoveBook = onMoveBook,
    )
    SyncDirectionMenu(
        entry = entry,
        expanded = expanded && syncMenuExpanded,
        onDismiss = onDismiss,
        onSyncBook = onSyncBook,
    )
}

@Composable
private fun SyncDirectionMenu(
    entry: BookEntry,
    expanded: Boolean,
    onDismiss: () -> Unit,
    onSyncBook: (BookEntry, SyncDirection?) -> Unit,
) {
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismiss,
    ) {
        SortMenuHeader(text = "Sync")
        HorizontalDivider()
        DropdownMenuItem(
            text = { Text("Import") },
            onClick = {
                onSyncBook(entry, SyncDirection.ImportFromTtu)
                onDismiss()
            },
        )
        DropdownMenuItem(
            text = { Text("Export") },
            onClick = {
                onSyncBook(entry, SyncDirection.ExportToTtu)
                onDismiss()
            },
        )
    }
}

@Composable
private fun MoveDestinationMenu(
    entry: BookEntry,
    shelves: List<BookShelf>,
    currentShelfName: String?,
    expanded: Boolean,
    onDismiss: () -> Unit,
    onMoveBook: (BookEntry, String?) -> Unit,
) {
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismiss,
    ) {
        SortMenuHeader(text = "Move")
        HorizontalDivider()
        DropdownMenuItem(
            text = { Text("None") },
            enabled = currentShelfName != null,
            onClick = {
                onMoveBook(entry, null)
                onDismiss()
            },
        )
        shelves.forEach { shelf ->
            DropdownMenuItem(
                text = { Text(shelf.name) },
                enabled = shelf.name != currentShelfName,
                onClick = {
                    onMoveBook(entry, shelf.name)
                    onDismiss()
                },
            )
        }
    }
}

@Composable
private fun ShelfManagementDialog(
    shelves: List<BookShelf>,
    showReading: Boolean,
    onShowReadingChange: (Boolean) -> Unit,
    onCreateShelf: (String) -> Unit,
    onDeleteShelf: (String) -> Unit,
    onMoveShelf: (Int, Int) -> Unit,
    onDismiss: () -> Unit,
) {
    var newShelfName by remember { mutableStateOf("") }
    val trimmedName = newShelfName.trim()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Manage Shelves") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("Reading Shelf", style = MaterialTheme.typography.bodyLarge)
                        Text(
                            "Shows books you've started but not finished.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = showReading,
                        onCheckedChange = onShowReadingChange,
                    )
                }
                HorizontalDivider()
                Text("Shelves", style = MaterialTheme.typography.titleMedium)
                if (shelves.isEmpty()) {
                    Text(
                        "No shelves",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    shelves.forEachIndexed { index, shelf ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = shelf.name,
                                modifier = Modifier.weight(1f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            IconButton(
                                onClick = { onMoveShelf(index, index - 1) },
                                enabled = index > 0,
                            ) {
                                Icon(Icons.Rounded.ArrowUpward, contentDescription = "Move shelf up")
                            }
                            IconButton(
                                onClick = { onMoveShelf(index, index + 1) },
                                enabled = index < shelves.lastIndex,
                            ) {
                                Icon(Icons.Rounded.ArrowDownward, contentDescription = "Move shelf down")
                            }
                            IconButton(onClick = { onDeleteShelf(shelf.name) }) {
                                Icon(Icons.Rounded.Delete, contentDescription = "Delete shelf")
                            }
                        }
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    OutlinedTextField(
                        value = newShelfName,
                        onValueChange = { newShelfName = it },
                        label = { Text("Shelf name") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(
                        onClick = {
                            onCreateShelf(trimmedName)
                            newShelfName = ""
                        },
                        enabled = trimmedName.isNotEmpty(),
                    ) {
                        Icon(Icons.Rounded.Add, contentDescription = "Add shelf")
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Done")
            }
        },
    )
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
internal fun SettingsTab(
    onDestination: (SettingsDestination) -> Unit,
    layoutSpec: MainShellLayoutSpec,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        contentColor = MaterialTheme.colorScheme.onBackground,
        contentWindowInsets = WindowInsets(0.dp, 0.dp, 0.dp, 0.dp),
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Text(
                        text = "Settings",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold,
                    )
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    scrolledContainerColor = MaterialTheme.colorScheme.background,
                ),
            )
        },
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            LazyColumn(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .widthIn(max = layoutSpec.contentMaxWidthDp.dp)
                    .fillMaxWidth(),
                contentPadding = PaddingValues(
                    start = layoutSpec.pageHorizontalPaddingDp.dp,
                    end = layoutSpec.pageHorizontalPaddingDp.dp,
                    top = 16.dp,
                    bottom = 24.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                settingsGroups().forEach { group ->
                    item {
                        SettingsGroupCard(rows = group, onDestination = onDestination)
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingsGroupCard(
    rows: List<SettingsRowModel>,
    onDestination: (SettingsDestination) -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        tonalElevation = 0.dp,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column {
            rows.forEachIndexed { index, row ->
                SettingsRow(row = row, onClick = { onDestination(row.destination) })
                if (index != rows.lastIndex) {
                    HorizontalDivider(
                        color = MaterialTheme.colorScheme.outlineVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun SettingsRow(row: SettingsRowModel, onClick: () -> Unit) {
    val tint = if (row.destination == SettingsDestination.ReportIssue) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.onSurface
    }
    ListItem(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        leadingContent = {
            SettingsGlyph(row.destination, tint, Modifier.size(24.dp))
        },
        headlineContent = {
            Text(
                text = row.label,
                style = MaterialTheme.typography.bodyLarge,
                color = tint,
            )
        },
        trailingContent = {
            ChevronRightGlyph(
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp),
            )
        },
    )
}

@Composable
private fun EmptyBooksView(
    onImport: () -> Unit,
    enabled: Boolean = true,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.padding(28.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = "No Books",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.height(10.dp))
        Text(
            text = "Import an EPUB using the + button to start reading.",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(24.dp))
        Row(Modifier.fillMaxWidth()) {
            Button(onClick = onImport, enabled = enabled) {
                Text("Import EPUB")
            }
        }
    }
}

@Composable
private fun BottomTabGlyph(tab: MainTab, modifier: Modifier = Modifier) {
    val icon = when (tab) {
        MainTab.Books -> Icons.AutoMirrored.Rounded.MenuBook
        MainTab.Dictionary -> Icons.Rounded.Translate
        MainTab.Settings -> Icons.Rounded.Settings
    }
    Icon(
        imageVector = icon,
        contentDescription = null,
        tint = LocalContentColor.current,
        modifier = modifier,
    )
}

@Composable
private fun SettingsGlyph(destination: SettingsDestination, color: Color, modifier: Modifier = Modifier) {
    val icon = when (destination) {
        SettingsDestination.Dictionaries -> Icons.AutoMirrored.Rounded.MenuBook
        SettingsDestination.Anki -> Icons.Rounded.Inventory2
        SettingsDestination.Appearance -> Icons.Rounded.Palette
        SettingsDestination.Behavior -> Icons.Rounded.Keyboard
        SettingsDestination.Advanced -> Icons.Rounded.Settings
        SettingsDestination.ReportIssue -> Icons.Rounded.ReportProblem
        SettingsDestination.Diagnostics -> Icons.Rounded.BugReport
        SettingsDestination.About -> Icons.Rounded.Info
    }
    Icon(
        imageVector = icon,
        contentDescription = null,
        tint = color,
        modifier = modifier,
    )
}

@Composable
private fun ChevronRightGlyph(color: Color, modifier: Modifier = Modifier) {
    Icon(
        imageVector = Icons.Rounded.ChevronRight,
        contentDescription = null,
        tint = color,
        modifier = modifier,
    )
}
