package moe.antimony.hoshi.features.bookshelf

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.LruCache
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.LocalOverscrollFactory
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.MenuBook
import androidx.compose.material.icons.automirrored.rounded.ShowChart
import androidx.compose.material.icons.automirrored.rounded.Sort
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.BugReport
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Done
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Inventory2
import androidx.compose.material.icons.rounded.Keyboard
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.RadioButtonUnchecked
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
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.pullToRefresh
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import moe.antimony.hoshi.LocalHoshiUiDependencies
import moe.antimony.hoshi.R
import moe.antimony.hoshi.content.ContentLanguageProfile
import moe.antimony.hoshi.epub.BookEntry
import moe.antimony.hoshi.epub.BookRepository
import moe.antimony.hoshi.epub.BookShelf
import moe.antimony.hoshi.epub.BookSortOption
import moe.antimony.hoshi.features.reader.ReaderSettings
import moe.antimony.hoshi.features.sync.DriveAuthStatus
import moe.antimony.hoshi.features.sync.SyncDirection
import moe.antimony.hoshi.features.sync.SyncMode
import moe.antimony.hoshi.features.sync.SyncSettings
import moe.antimony.hoshi.importing.DirectoryImportContent
import moe.antimony.hoshi.importing.ImportFileType
import moe.antimony.hoshi.importing.MultipleFileImportContent
import moe.antimony.hoshi.importing.SafImportDirectoryScanner
import moe.antimony.hoshi.profiles.ProfileState
import moe.antimony.hoshi.importing.importDisplayName
import moe.antimony.hoshi.ui.HoshiBlockingProgressOverlay
import moe.antimony.hoshi.ui.UiText
import moe.antimony.hoshi.ui.asString
import moe.antimony.hoshi.ui.hoshiOutlinedTextFieldColors
import moe.antimony.hoshi.ui.hoshiSingleLineTextFieldLineLimits
import moe.antimony.hoshi.ui.rememberSyncedTextFieldState
import moe.antimony.hoshi.ui.replaceTextAndSelectStart
import moe.antimony.hoshi.ui.theme.LocalHoshiDarkTheme
import moe.antimony.hoshi.ui.theme.LocalHoshiEInkMode
import java.io.File
import kotlin.math.max

@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
fun BookshelfView(
    pendingImportUri: Uri? = null,
    onPendingImportConsumed: () -> Unit = {},
    onOpenReader: (String) -> Unit,
    refreshKey: Int = 0,
    layoutSpec: MainShellLayoutSpec,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val appContainer = LocalHoshiUiDependencies.current
    val syncSettings by appContainer.syncSettingsRepository.settings.collectAsStateWithLifecycle(
        initialValue = SyncSettings(),
    )
    var driveAuthStatus by remember { mutableStateOf<DriveAuthStatus?>(null) }
    val readerSettings by appContainer.readerSettingsRepository.settings.collectAsStateWithLifecycle(
        initialValue = ReaderSettings(),
    )
    val sasayakiSettings by appContainer.sasayakiSettingsRepository.settings.collectAsStateWithLifecycle(
        initialValue = moe.antimony.hoshi.features.sasayaki.SasayakiSettings(),
    )
    val booksViewModel: BookshelfViewModel = hiltViewModel()
    val uiState by booksViewModel.uiState.collectAsStateWithLifecycle()
    val profileState by appContainer.profileRepository.state.collectAsStateWithLifecycle()
    var sortMenuExpanded by remember { mutableStateOf(false) }
    var contextMenuTarget by remember { mutableStateOf<BookContextMenuTarget?>(null) }
    var remoteContextMenuTarget by remember { mutableStateOf<RemoteBookContextMenuTarget?>(null) }
    var deleteCandidate by remember { mutableStateOf<BookEntry?>(null) }
    var remoteDeleteCandidate by remember { mutableStateOf<RemoteBookEntry?>(null) }
    var exportCandidate by remember { mutableStateOf<BookEntry?>(null) }
    var markReadCandidate by remember { mutableStateOf<BookEntry?>(null) }
    var renameCandidate by remember { mutableStateOf<BookEntry?>(null) }
    val folderScanner = remember(context) { SafImportDirectoryScanner(context.contentResolver) }
    val renameTextState = rememberTextFieldState()
    val renameScrollState = rememberScrollState()
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

    val folderImporter = rememberLauncherForActivityResult(DirectoryImportContent()) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        booksViewModel.importBookFolderItems {
            withContext(Dispatchers.IO) {
                folderScanner.scan(uri, ImportFileType.Epub).map { file ->
                    BookImportItem(
                        uri = file.key,
                        displayName = file.displayName,
                    )
                }
            }
        }
    }

    val epubExporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/epub+zip")) { uri ->
        val candidate = exportCandidate
        exportCandidate = null
        if (uri == null || candidate == null) return@rememberLauncherForActivityResult
        booksViewModel.exportBook(candidate, uri)
    }

    fun launchBookImporter() {
        importer.launch(ImportFileType.Epub.mimeTypes)
    }

    fun launchBookFolderImporter() {
        folderImporter.launch(Unit)
    }

    LaunchedEffect(refreshKey) {
        booksViewModel.reloadBookEntries()
    }

    LaunchedEffect(syncSettings.enabled, refreshKey) {
        driveAuthStatus = if (syncSettings.enabled) {
            appContainer.deviceCodeDriveAuthorizer.status()
        } else {
            null
        }
    }

    LaunchedEffect(Unit) {
        booksViewModel.rebuildLookupQuery()
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
        remoteBookEntries = uiState.remoteBookEntries,
        sections = uiState.sections,
        bookProgressById = uiState.bookProgressById,
        remoteProgressById = uiState.remoteProgressById,
        remoteImportProgressById = uiState.remoteImportProgressById,
        remoteBusyBookIds = uiState.remoteBusyBookIds,
        coverSourcesById = uiState.coverSourcesById,
        remoteCoverSourcesById = uiState.remoteCoverSourcesById,
        sortOption = uiState.sortOption,
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
        onImportFiles = ::launchBookImporter,
        onImportFolder = ::launchBookFolderImporter,
        onOpenBook = booksViewModel::openBook,
        onRefreshRemoteBooks = booksViewModel::refreshRemoteBooks,
        onImportRemoteBook = { entry ->
            booksViewModel.importRemoteBook(
                entry = entry,
                syncStats = syncSettings.enabled && readerSettings.statisticsSyncEnabled,
                syncAudioBook = sasayakiSettings.enabled && sasayakiSettings.syncEnabled,
            )
        },
        onDeleteRemoteCandidate = { remoteDeleteCandidate = it },
        contextMenuTarget = contextMenuTarget,
        onContextMenuTargetChange = { contextMenuTarget = it },
        remoteContextMenuTarget = remoteContextMenuTarget,
        onRemoteContextMenuTargetChange = { remoteContextMenuTarget = it },
        onDeleteCandidate = { deleteCandidate = it },
        onExportCandidate = { entry ->
            exportCandidate = entry
            epubExporter.launch("${entry.displayTitle.sanitizeExportFileName()}.epub")
        },
        onMarkReadCandidate = { markReadCandidate = it },
        onRenameCandidate = {
            renameCandidate = it
            renameTextState.replaceTextAndSelectStart(it.displayTitle)
        },
        onMoveBook = booksViewModel::moveBook,
        profileState = profileState,
        onSetBookProfile = booksViewModel::setBookProfile,
        syncSettings = syncSettings,
        driveAuthStatus = driveAuthStatus,
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
            text = { Text(message.asString()) },
            confirmButton = {
                TextButton(onClick = booksViewModel::consumeStatusMessage) {
                    Text(stringResource(R.string.action_ok))
                }
            },
        )
    }

    uiState.errorMessage?.let { message ->
        AlertDialog(
            onDismissRequest = booksViewModel::consumeErrorMessage,
            title = { Text(stringResource(R.string.dialog_error_title)) },
            text = { Text(message.asString()) },
            confirmButton = {
                TextButton(onClick = booksViewModel::consumeErrorMessage) {
                    Text(stringResource(R.string.action_ok))
                }
            },
        )
    }

    deleteCandidate?.let { candidate ->
        AlertDialog(
            onDismissRequest = { deleteCandidate = null },
            title = { Text(stringResource(R.string.bookshelf_delete_book_title_format, candidate.displayTitle)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        booksViewModel.deleteBook(candidate)
                        deleteCandidate = null
                    },
                ) {
                    Text(stringResource(R.string.action_delete))
                }
            },
            dismissButton = {
                TextButton(onClick = { deleteCandidate = null }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }

    remoteDeleteCandidate?.let { candidate ->
        AlertDialog(
            onDismissRequest = { remoteDeleteCandidate = null },
            title = { Text(stringResource(R.string.bookshelf_delete_remote_book_title_format, candidate.title)) },
            text = { Text(stringResource(R.string.bookshelf_delete_remote_book_confirmation)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        booksViewModel.deleteRemoteBook(candidate)
                        remoteDeleteCandidate = null
                    },
                ) {
                    Text(stringResource(R.string.action_delete))
                }
            },
            dismissButton = {
                TextButton(onClick = { remoteDeleteCandidate = null }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }

    markReadCandidate?.let { candidate ->
        AlertDialog(
            onDismissRequest = { markReadCandidate = null },
            title = { Text(stringResource(R.string.bookshelf_mark_read_title_format, candidate.displayTitle)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        booksViewModel.markRead(candidate)
                        markReadCandidate = null
                    },
                ) {
                    Text(stringResource(R.string.action_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { markReadCandidate = null }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }

    renameCandidate?.let { candidate ->
        LaunchedEffect(candidate.metadata.id) {
            renameScrollState.scrollTo(0)
        }

        AlertDialog(
            onDismissRequest = { renameCandidate = null },
            title = { Text(stringResource(R.string.action_rename)) },
            text = {
                OutlinedTextField(
                    state = renameTextState,
                    label = { Text(stringResource(R.string.bookshelf_title_label)) },
                    lineLimits = hoshiSingleLineTextFieldLineLimits(),
                    scrollState = renameScrollState,
                    colors = hoshiOutlinedTextFieldColors(),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        booksViewModel.renameBook(candidate, renameTextState.text.toString())
                        renameCandidate = null
                    },
                ) {
                    Text(stringResource(R.string.action_ok))
                }
            },
            dismissButton = {
                TextButton(onClick = { renameCandidate = null }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }

    if (showBulkDeleteConfirmation) {
        AlertDialog(
            onDismissRequest = { showBulkDeleteConfirmation = false },
            title = {
                Text(
                    pluralStringResource(
                        R.plurals.bookshelf_bulk_delete_title,
                        uiState.selectedBookIds.size,
                        uiState.selectedBookIds.size,
                    ),
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        booksViewModel.deleteSelectedBooks()
                        showBulkDeleteConfirmation = false
                    },
                ) {
                    Text(stringResource(R.string.action_delete))
                }
            },
            dismissButton = {
                TextButton(onClick = { showBulkDeleteConfirmation = false }) {
                    Text(stringResource(R.string.action_cancel))
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
            onRenameShelf = booksViewModel::renameShelf,
            onMoveShelf = booksViewModel::moveShelf,
            onDismiss = { showShelfManagement = false },
        )
    }
}

@Composable
internal fun HoshiMainShell(
    selectedTab: MainTab,
    onSelectedTabChange: (MainTab) -> Unit,
    visibleTabs: List<MainTab> = MainTab.entries,
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
                        visibleTabs = visibleTabs,
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
                    visibleTabs.forEach { tab ->
                        item(
                            selected = tab == selectedTab,
                            onClick = { onSelectedTabChange(tab) },
                            icon = { BottomTabGlyph(tab, Modifier.size(24.dp)) },
                            label = { Text(stringResource(tab.labelRes)) },
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
internal const val ShelfManagementShelfListTag = "shelf-management-shelf-list"

@Composable
private fun HoshiCompactBottomNavigation(
    selectedTab: MainTab,
    onSelectedTabChange: (MainTab) -> Unit,
    visibleTabs: List<MainTab>,
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
                visibleTabs.forEach { tab ->
                    NavigationBarItem(
                        selected = tab == selectedTab,
                        onClick = { onSelectedTabChange(tab) },
                        icon = { BottomTabGlyph(tab, Modifier.size(24.dp)) },
                        label = {
                            Text(
                                text = stringResource(tab.labelRes),
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

private fun MainShellTextOverflow.toTextOverflow(): TextOverflow =
    when (this) {
        MainShellTextOverflow.Clip -> TextOverflow.Clip
        MainShellTextOverflow.Ellipsis -> TextOverflow.Ellipsis
    }

internal data class BookContextMenuTarget(
    val sectionKey: String,
    val bookId: String,
)

internal data class RemoteBookContextMenuTarget(
    val remoteBookId: String,
)

internal fun bookContextMenuTarget(
    section: BookshelfSectionModel,
    entry: BookEntry,
): BookContextMenuTarget =
    BookContextMenuTarget(
        sectionKey = section.layoutKey,
        bookId = entry.metadata.id,
    )

internal fun isBookContextMenuExpanded(
    activeTarget: BookContextMenuTarget?,
    section: BookshelfSectionModel,
    entry: BookEntry,
): Boolean =
    activeTarget == bookContextMenuTarget(section, entry)

internal fun remoteBookContextMenuTarget(entry: RemoteBookEntry): RemoteBookContextMenuTarget =
    RemoteBookContextMenuTarget(remoteBookId = entry.id)

internal fun isRemoteBookContextMenuExpanded(
    activeTarget: RemoteBookContextMenuTarget?,
    entry: RemoteBookEntry,
): Boolean =
    activeTarget == remoteBookContextMenuTarget(entry)

internal fun shouldEnableBookshelfPullRefresh(
    syncSettings: SyncSettings,
    authStatus: DriveAuthStatus?,
    hasLoadedBooks: Boolean,
    isSelecting: Boolean,
    fileTaskBlocked: Boolean,
): Boolean =
    shouldLoadRemoteBooks(syncSettings, authStatus ?: DriveAuthStatus.NotConnected) &&
        hasLoadedBooks &&
        !isSelecting &&
        !fileTaskBlocked

private fun LazyGridScope.googleDriveSection(
    remoteBookEntries: List<RemoteBookEntry>,
    remoteProgressById: Map<String, Double>,
    remoteImportProgressById: Map<String, Double>,
    remoteBusyBookIds: Set<String>,
    remoteCoverSourcesById: Map<String, BookCoverSource>,
    layoutSpec: MainShellLayoutSpec,
    contentWidthDp: Int,
    fileTaskBlocked: Boolean,
    isSelecting: Boolean,
    shelfExpansionState: Map<String, Boolean>,
    onShelfExpandedChange: (String, Boolean) -> Unit,
    onImportRemoteBook: (RemoteBookEntry) -> Unit,
    onDeleteRemoteCandidate: (RemoteBookEntry) -> Unit,
    remoteContextMenuTarget: RemoteBookContextMenuTarget?,
    onRemoteContextMenuTargetChange: (RemoteBookContextMenuTarget?) -> Unit,
) {
    if (remoteBookEntries.isEmpty()) return
    val presentation = googleDriveSectionPresentation(shelfExpansionState, isSelecting)
    item(
        key = "header:google-drive",
        contentType = "sectionHeader",
        span = { androidx.compose.foundation.lazy.grid.GridItemSpan(maxLineSpan) },
    ) {
        BookshelfSectionHeader(
            title = stringResource(R.string.bookshelf_section_google_drive),
            count = remoteBookEntries.size,
            layoutSpec = layoutSpec,
            isCollapsible = presentation.isCollapsible,
            isExpanded = presentation.isExpanded,
            enabled = presentation.allowsHitTesting && !fileTaskBlocked,
            onToggle = {
                onShelfExpandedChange(GoogleDriveSectionCollapseKey, !presentation.isExpanded)
            },
            modifier = Modifier.alpha(presentation.alpha),
        )
    }
    if (presentation.isExpanded) {
        items(
            items = remoteBookEntries,
            key = { "google-drive:${it.id}" },
            contentType = { "remoteBook" },
        ) { entry ->
            Box {
                RemoteBookGridCell(
                    entry = entry,
                    progress = remoteProgressById[entry.id] ?: 0.0,
                    downloadProgress = remoteImportProgressById[entry.id],
                    coverSource = remoteCoverSourcesById[entry.id],
                    layoutSpec = layoutSpec,
                    enabled = presentation.allowsHitTesting && !fileTaskBlocked && entry.id !in remoteBusyBookIds,
                    onImport = { onImportRemoteBook(entry) },
                    onOpenContextMenu = { onRemoteContextMenuTargetChange(remoteBookContextMenuTarget(entry)) },
                    modifier = Modifier.alpha(presentation.alpha),
                )
                RemoteBookContextMenu(
                    entry = entry,
                    expanded = isRemoteBookContextMenuExpanded(remoteContextMenuTarget, entry),
                    onDismiss = { onRemoteContextMenuTargetChange(null) },
                    onDeleteCandidate = onDeleteRemoteCandidate,
                )
            }
        }
    } else {
        item(
            key = "preview:google-drive",
            contentType = "collapsedPreview",
            span = { androidx.compose.foundation.lazy.grid.GridItemSpan(maxLineSpan) },
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .alpha(presentation.alpha)
                    .clickable(enabled = presentation.allowsHitTesting && !fileTaskBlocked) {
                        onShelfExpandedChange(GoogleDriveSectionCollapseKey, true)
                    },
                horizontalArrangement = Arrangement.spacedBy(CollapsedShelfCoverSpacingDp.dp),
            ) {
                val collapsedCoverWidthDp = layoutSpec.collapsedShelfPreviewCoverWidthDp(contentWidthDp)
                remoteBookEntries
                    .take(layoutSpec.collapsedShelfPreviewColumns(contentWidthDp))
                    .forEach { entry ->
                        BookCoverCard(
                            coverSource = remoteCoverSourcesById[entry.id],
                            modifier = Modifier.width(collapsedCoverWidthDp.dp),
                        )
                    }
            }
        }
    }
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun BooksTab(
    layoutSpec: MainShellLayoutSpec,
    bookEntries: List<BookEntry>,
    remoteBookEntries: List<RemoteBookEntry>,
    sections: List<BookshelfSectionModel>,
    bookProgressById: Map<String, Double>,
    remoteProgressById: Map<String, Double>,
    remoteImportProgressById: Map<String, Double>,
    remoteBusyBookIds: Set<String>,
    coverSourcesById: Map<String, BookCoverSource>,
    remoteCoverSourcesById: Map<String, BookCoverSource>,
    sortOption: BookSortOption,
    hasLoadedBooks: Boolean,
    isLoading: Boolean,
    blockingProgressMessage: UiText?,
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
    onImportFiles: () -> Unit,
    onImportFolder: () -> Unit,
    onOpenBook: (BookEntry) -> Unit,
    onRefreshRemoteBooks: () -> Unit,
    onImportRemoteBook: (RemoteBookEntry) -> Unit,
    onDeleteRemoteCandidate: (RemoteBookEntry) -> Unit,
    contextMenuTarget: BookContextMenuTarget?,
    onContextMenuTargetChange: (BookContextMenuTarget?) -> Unit,
    remoteContextMenuTarget: RemoteBookContextMenuTarget?,
    onRemoteContextMenuTargetChange: (RemoteBookContextMenuTarget?) -> Unit,
    onDeleteCandidate: (BookEntry) -> Unit,
    onExportCandidate: (BookEntry) -> Unit,
    onMarkReadCandidate: (BookEntry) -> Unit,
    onRenameCandidate: (BookEntry) -> Unit,
    onMoveBook: (BookEntry, String?) -> Unit,
    profileState: ProfileState,
    onSetBookProfile: (BookEntry, String?) -> Unit,
    syncSettings: SyncSettings,
    driveAuthStatus: DriveAuthStatus?,
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
                onImportFiles = onImportFiles,
                onImportFolder = onImportFolder,
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
                hasLoadedBooks && bookEntries.isEmpty() && remoteBookEntries.isEmpty() -> EmptyBooksView(
                    enabled = !fileTaskBlocked,
                    onImport = onImportFiles,
                    modifier = Modifier
                        .align(Alignment.Center)
                        .widthIn(max = layoutSpec.contentMaxWidthDp.dp)
                        .fillMaxWidth()
                        .padding(horizontal = layoutSpec.pageHorizontalPaddingDp.dp),
                )
                else -> CompositionLocalProvider(LocalOverscrollFactory provides null) {
                    val pullRefreshState = rememberPullToRefreshState()
                    val pullRefreshEnabled = shouldEnableBookshelfPullRefresh(
                        syncSettings = syncSettings,
                        authStatus = driveAuthStatus,
                        hasLoadedBooks = hasLoadedBooks,
                        isSelecting = isSelecting,
                        fileTaskBlocked = fileTaskBlocked,
                    )
                    Box(modifier = contentModifier.fillMaxHeight()) {
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(layoutSpec.bookGridColumns(contentWidthDp)),
                        modifier = Modifier
                            .fillMaxSize()
                            .pullToRefresh(
                                isRefreshing = false,
                                state = pullRefreshState,
                                enabled = pullRefreshEnabled,
                                onRefresh = onRefreshRemoteBooks,
                            ),
                        contentPadding = PaddingValues(
                            start = layoutSpec.pageHorizontalPaddingDp.dp,
                            end = layoutSpec.pageHorizontalPaddingDp.dp,
                            top = layoutSpec.bookGridTopPaddingDp.dp,
                            bottom = layoutSpec.bookGridBottomPaddingDp.dp,
                        ),
                        horizontalArrangement = Arrangement.spacedBy(layoutSpec.bookGridSpacingDp.dp),
                        verticalArrangement = Arrangement.spacedBy(layoutSpec.bookGridVerticalSpacingDp.dp),
                    ) {
                        val visibleSections = sections.filter { it.books.isNotEmpty() }
                        val googleDriveIndex = googleDriveSectionInsertionIndex(visibleSections)
                        visibleSections.forEachIndexed { sectionIndex, section ->
                            if (sectionIndex == googleDriveIndex) {
                                googleDriveSection(
                                    remoteBookEntries = remoteBookEntries,
                                    remoteProgressById = remoteProgressById,
                                    remoteImportProgressById = remoteImportProgressById,
                                    remoteBusyBookIds = remoteBusyBookIds,
                                    remoteCoverSourcesById = remoteCoverSourcesById,
                                    layoutSpec = layoutSpec,
                                    contentWidthDp = contentWidthDp,
                                    fileTaskBlocked = fileTaskBlocked,
                                    isSelecting = isSelecting,
                                    shelfExpansionState = shelfExpansionState,
                                    onShelfExpandedChange = onShelfExpandedChange,
                                    onImportRemoteBook = onImportRemoteBook,
                                    onDeleteRemoteCandidate = onDeleteRemoteCandidate,
                                    remoteContextMenuTarget = remoteContextMenuTarget,
                                    onRemoteContextMenuTargetChange = onRemoteContextMenuTargetChange,
                                )
                            }
                            val collapseKey = section.collapseKey
                            val isExpanded = if (!section.isCollapsible) {
                                true
                            } else {
                                collapseKey?.let { key -> shelfExpansionState[key] ?: section.isReading } ?: true
                            }
                            item(
                                key = "header:${section.layoutKey}",
                                contentType = "sectionHeader",
                                span = { androidx.compose.foundation.lazy.grid.GridItemSpan(maxLineSpan) },
                            ) {
                                BookshelfSectionHeader(
                                    title = section.titleRes?.let { stringResource(it) } ?: section.title,
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
                                    key = { "${section.layoutKey}:${it.metadata.id}" },
                                    contentType = { "book" },
                                ) { entry ->
                                    Box {
                                        BookGridCell(
                                            entry = entry,
                                            progress = bookProgressById[entry.metadata.id] ?: 0.0,
                                            coverSource = coverSourcesById[entry.metadata.id],
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
                                            onDismiss = { onContextMenuTargetChange(null) },
                                            onMoveBook = onMoveBook,
                                            onMarkReadCandidate = onMarkReadCandidate,
                                            onRenameCandidate = onRenameCandidate,
                                            onDeleteCandidate = onDeleteCandidate,
                                            onExportCandidate = onExportCandidate,
                                            profileState = profileState,
                                            onSetBookProfile = onSetBookProfile,
                                            syncSettings = syncSettings,
                                            onSyncBook = onSyncBook,
                                        )
                                    }
                                }
                            } else {
                                item(
                                    key = "preview:${section.layoutKey}",
                                    contentType = "collapsedPreview",
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
                                                coverSource = coverSourcesById[entry.metadata.id],
                                                modifier = Modifier.width(collapsedCoverWidthDp.dp),
                                            )
                                        }
                                    }
                                }
                            }
                        }
                        if (googleDriveIndex == visibleSections.size) {
                            googleDriveSection(
                                remoteBookEntries = remoteBookEntries,
                                remoteProgressById = remoteProgressById,
                                remoteImportProgressById = remoteImportProgressById,
                                remoteBusyBookIds = remoteBusyBookIds,
                                remoteCoverSourcesById = remoteCoverSourcesById,
                                layoutSpec = layoutSpec,
                                contentWidthDp = contentWidthDp,
                                fileTaskBlocked = fileTaskBlocked,
                                isSelecting = isSelecting,
                                shelfExpansionState = shelfExpansionState,
                                onShelfExpandedChange = onShelfExpandedChange,
                                onImportRemoteBook = onImportRemoteBook,
                                onDeleteRemoteCandidate = onDeleteRemoteCandidate,
                                remoteContextMenuTarget = remoteContextMenuTarget,
                                onRemoteContextMenuTargetChange = onRemoteContextMenuTargetChange,
                            )
                        }
                    }
                    if (pullRefreshEnabled) {
                        PullToRefreshDefaults.Indicator(
                            modifier = Modifier.align(Alignment.TopCenter),
                            isRefreshing = false,
                            state = pullRefreshState,
                        )
                    }
                    }
                }
            }
            blockingProgressMessage?.let { message ->
                HoshiBlockingProgressOverlay(
                    message = message.asString(),
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
    onImportFiles: () -> Unit,
    onImportFolder: () -> Unit,
) {
    var moveMenuExpanded by remember { mutableStateOf(false) }
    var importMenuExpanded by remember { mutableStateOf(false) }
    CenterAlignedTopAppBar(
        title = {
            Text(
                text = if (isSelecting) {
                    stringResource(R.string.bookshelf_selected_count_format, selectedCount)
                } else {
                    stringResource(R.string.main_tab_books)
                },
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
            )
        },
        navigationIcon = {
            if (isSelecting) {
                TextButton(onClick = onClearSelection, enabled = enabled) {
                    Text(stringResource(R.string.action_done), fontWeight = FontWeight.SemiBold)
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
                                contentDescription = stringResource(R.string.bookshelf_sort_books),
                            )
                        }
                        DropdownMenu(
                            expanded = sortMenuExpanded,
                            onDismissRequest = { onSortMenuExpandedChange(false) },
                        ) {
                            SortMenuHeader(text = stringResource(R.string.bookshelf_sorting_by))
                            HorizontalDivider()
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.bookshelf_sort_recent)) },
                                trailingIcon = selectedSortIcon(BookSortOption.Recent, sortOption),
                                onClick = { onSortChange(BookSortOption.Recent) },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.bookshelf_sort_title)) },
                                trailingIcon = selectedSortIcon(BookSortOption.Title, sortOption),
                                onClick = { onSortChange(BookSortOption.Title) },
                            )
                        }
                    }
                    IconButton(onClick = onStartSelecting, enabled = enabled) {
                        Icon(
                            imageVector = Icons.Rounded.Done,
                            contentDescription = stringResource(R.string.bookshelf_select_books),
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
                            contentDescription = stringResource(R.string.bookshelf_move_selected_books),
                        )
                    }
                    DropdownMenu(
                        expanded = moveMenuExpanded,
                        onDismissRequest = { moveMenuExpanded = false },
                    ) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.bookshelf_no_shelf)) },
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
                        contentDescription = stringResource(R.string.bookshelf_delete_selected_books),
                    )
                }
            } else {
                IconButton(onClick = onManageShelves, enabled = enabled) {
                    Icon(
                        imageVector = Icons.Rounded.FolderOpen,
                        contentDescription = stringResource(R.string.bookshelf_manage_shelves),
                    )
                }
                Box {
                    IconButton(onClick = { importMenuExpanded = true }, enabled = enabled) {
                        Icon(
                            imageVector = Icons.Rounded.Add,
                            contentDescription = stringResource(R.string.bookshelf_import_epub),
                        )
                    }
                    DropdownMenu(
                        expanded = importMenuExpanded,
                        onDismissRequest = { importMenuExpanded = false },
                    ) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.bookshelf_import_files)) },
                            onClick = {
                                importMenuExpanded = false
                                onImportFiles()
                            },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.bookshelf_import_folder)) },
                            onClick = {
                                importMenuExpanded = false
                                onImportFolder()
                            },
                        )
                    }
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
    enabled: Boolean = true,
    onToggle: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val textLayout = bookshelfHeaderTextLayout()
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable(enabled = isCollapsible && enabled, onClick = onToggle)
            .padding(vertical = layoutSpec.shelfHeaderVerticalPaddingDp.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title,
            modifier = if (textLayout.titleUsesRemainingWidth) Modifier.weight(1f, fill = false) else Modifier,
            style = layoutSpec.shelfTitleTextStyle.toTextStyle(),
            fontWeight = layoutSpec.shelfTitleFontWeight.toFontWeight(),
            color = MaterialTheme.colorScheme.onBackground,
            maxLines = textLayout.titleMaxLines,
            overflow = textLayout.titleOverflow.toTextOverflow(),
        )
        Spacer(Modifier.width(12.dp))
        Text(
            text = count.toString(),
            style = layoutSpec.shelfCountTextStyle.toTextStyle(),
            color = Color(0xFF8C8C92),
            maxLines = textLayout.countMaxLines,
            softWrap = textLayout.countSoftWrap,
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
    coverSource: BookCoverSource?,
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
            BookCoverCard(coverSource = coverSource)
            if (isSelecting) {
                Icon(
                    imageVector = if (isSelected) {
                        Icons.Rounded.CheckCircle
                    } else {
                        Icons.Rounded.RadioButtonUnchecked
                    },
                    contentDescription = if (isSelected) {
                        stringResource(R.string.bookshelf_selected)
                    } else {
                        stringResource(R.string.bookshelf_not_selected)
                    },
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
                    contentDescription = stringResource(R.string.bookshelf_read),
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
            text = entry.displayTitle,
            style = layoutSpec.bookTitleTextStyle.toTextStyle(),
            fontWeight = layoutSpec.bookTitleFontWeight.toFontWeight(),
            color = MaterialTheme.colorScheme.onBackground,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun RemoteBookGridCell(
    entry: RemoteBookEntry,
    progress: Double,
    downloadProgress: Double?,
    coverSource: BookCoverSource?,
    layoutSpec: MainShellLayoutSpec,
    enabled: Boolean,
    onImport: () -> Unit,
    onOpenContextMenu: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.combinedClickable(
            enabled = enabled,
            onClick = onImport,
            onLongClick = onOpenContextMenu,
        ),
    ) {
        BookCoverCard(coverSource = coverSource)
        Spacer(Modifier.height(6.dp))
        ReadingProgressPill(progress = progress)
        Spacer(Modifier.height(6.dp))
        Text(
            text = entry.title,
            style = layoutSpec.bookTitleTextStyle.toTextStyle(),
            fontWeight = layoutSpec.bookTitleFontWeight.toFontWeight(),
            color = if (enabled) MaterialTheme.colorScheme.onBackground else MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        downloadProgress?.let { value ->
            Spacer(Modifier.height(4.dp))
            LinearProgressIndicator(
                progress = { value.coerceIn(0.0, 1.0).toFloat() },
                modifier = Modifier.fillMaxWidth(),
            )
        }
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
    coverSource: BookCoverSource?,
    modifier: Modifier = Modifier,
) {
    val cachedBitmap = remember(coverSource?.cacheKey) {
        BookCoverBitmapCache.get(coverSource)
    }
    val bitmap by produceState<Bitmap?>(initialValue = cachedBitmap, key1 = coverSource) {
        if (cachedBitmap == null) {
            value = BookCoverBitmapCache.load(coverSource)
        }
    }
    val outerShape = RoundedCornerShape(7.dp)
    val innerShape = RoundedCornerShape(6.dp)
    val coverPlaceholderColor = Color.Gray.copy(alpha = 0.3f)
    val coverContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.42f)
    val coverBorderColor = if (LocalHoshiDarkTheme.current) {
        Color.White.copy(alpha = 0.18f)
    } else {
        Color.Black.copy(alpha = 0.06f)
    }
    Box(
        modifier = Modifier
            .then(modifier)
            .fillMaxWidth()
            .aspectRatio(BookCoverAspectRatio)
            .clip(outerShape)
            .background(coverContainerColor)
            .border(BorderStroke(1.dp, coverBorderColor), outerShape)
            .padding(3.dp),
        contentAlignment = Alignment.Center,
    ) {
        val coverModifier = Modifier
            .fillMaxSize()
            .clip(innerShape)
        if (bitmap != null) {
            Image(
                bitmap = bitmap!!.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = coverModifier.background(coverPlaceholderColor),
            )
        } else {
            Box(
                modifier = coverModifier.background(coverPlaceholderColor),
            )
        }
    }
}

internal object BookCoverBitmapCache {
    private const val MaxCoverDimensionPx = 768
    private val cache = object : LruCache<String, Bitmap>(24 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }

    fun get(coverSource: BookCoverSource?): Bitmap? {
        coverSource ?: return null
        return synchronized(cache) {
            cache.get(coverSource.cacheKey)
        }
    }

    suspend fun load(coverSource: BookCoverSource?): Bitmap? = withContext(Dispatchers.IO) {
        coverSource ?: return@withContext null
        synchronized(cache) {
            cache.get(coverSource.cacheKey)?.let { return@withContext it }
        }
        val bitmap = decodeSampledCoverBitmap(File(coverSource.path), MaxCoverDimensionPx) ?: return@withContext null
        bitmap.prepareToDraw()
        synchronized(cache) {
            cache.put(coverSource.cacheKey, bitmap)
        }
        bitmap
    }
}

private const val BookCoverAspectRatio = 0.709f

internal fun coverDecodeSampleSize(width: Int, height: Int, maxDimensionPx: Int): Int {
    if (width <= 0 || height <= 0 || maxDimensionPx <= 0) return 1
    var sampleSize = 1
    while (max(width / (sampleSize * 2), height / (sampleSize * 2)) >= maxDimensionPx) {
        sampleSize *= 2
    }
    return sampleSize
}

internal data class CoverThumbnailSize(
    val width: Int,
    val height: Int,
)

internal fun coverThumbnailSize(width: Int, height: Int, maxDimensionPx: Int): CoverThumbnailSize {
    if (width <= 0 || height <= 0 || maxDimensionPx <= 0) {
        return CoverThumbnailSize(width = width, height = height)
    }
    val longest = max(width, height)
    if (longest <= maxDimensionPx) {
        return CoverThumbnailSize(width = width, height = height)
    }
    val scale = maxDimensionPx.toDouble() / longest.toDouble()
    return CoverThumbnailSize(
        width = max(1, (width * scale).toInt()),
        height = max(1, (height * scale).toInt()),
    )
}

internal fun decodeSampledCoverBitmap(file: File, maxDimensionPx: Int): Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.absolutePath, bounds)
    val options = BitmapFactory.Options().apply {
        inSampleSize = coverDecodeSampleSize(bounds.outWidth, bounds.outHeight, maxDimensionPx)
    }
    val decoded = BitmapFactory.decodeFile(file.absolutePath, options) ?: return null
    val targetSize = coverThumbnailSize(decoded.width, decoded.height, maxDimensionPx)
    if (targetSize.width == decoded.width && targetSize.height == decoded.height) {
        return decoded
    }
    val scaled = Bitmap.createScaledBitmap(decoded, targetSize.width, targetSize.height, true)
    if (scaled !== decoded) {
        decoded.recycle()
    }
    return scaled
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
    onDismiss: () -> Unit,
    onMoveBook: (BookEntry, String?) -> Unit,
    onMarkReadCandidate: (BookEntry) -> Unit,
    onRenameCandidate: (BookEntry) -> Unit,
    onDeleteCandidate: (BookEntry) -> Unit,
    onExportCandidate: (BookEntry) -> Unit,
    profileState: ProfileState,
    onSetBookProfile: (BookEntry, String?) -> Unit,
    syncSettings: SyncSettings,
    onSyncBook: (BookEntry, SyncDirection?) -> Unit,
) {
    var moveMenuExpanded by remember { mutableStateOf(false) }
    var syncMenuExpanded by remember { mutableStateOf(false) }
    var profileMenuExpanded by remember { mutableStateOf(false) }
    LaunchedEffect(expanded, hideMove) {
        if (!expanded || hideMove) {
            moveMenuExpanded = false
        }
        if (!expanded) {
            syncMenuExpanded = false
            profileMenuExpanded = false
        }
    }
    DropdownMenu(
        expanded = expanded && !moveMenuExpanded && !syncMenuExpanded && !profileMenuExpanded,
        onDismissRequest = onDismiss,
    ) {
        if (syncSettings.enabled) {
            if (syncSettings.mode == SyncMode.Manual) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.bookshelf_sync)) },
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
                    text = { Text(stringResource(R.string.bookshelf_sync)) },
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
                text = { Text(stringResource(R.string.bookshelf_move)) },
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
        DropdownMenuItem(
            text = { Text(stringResource(R.string.bookshelf_profile)) },
            trailingIcon = {
                Icon(
                    imageVector = Icons.Rounded.ChevronRight,
                    contentDescription = null,
                )
            },
            onClick = { profileMenuExpanded = true },
        )
        HorizontalDivider()
        DropdownMenuItem(
            text = { Text(stringResource(R.string.action_rename)) },
            onClick = {
                onRenameCandidate(entry)
                onDismiss()
            },
        )
        DropdownMenuItem(
            text = { Text(stringResource(R.string.bookshelf_mark_read)) },
            onClick = {
                onMarkReadCandidate(entry)
                onDismiss()
            },
        )
        DropdownMenuItem(
            text = { Text(stringResource(R.string.bookshelf_export_epub)) },
            onClick = {
                onExportCandidate(entry)
                onDismiss()
            },
        )
        DropdownMenuItem(
            text = { Text(stringResource(R.string.action_delete)) },
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
    ProfileDestinationMenu(
        entry = entry,
        profileState = profileState,
        expanded = expanded && profileMenuExpanded,
        onDismiss = onDismiss,
        onSetBookProfile = onSetBookProfile,
    )
    SyncDirectionMenu(
        entry = entry,
        expanded = expanded && syncMenuExpanded,
        onDismiss = onDismiss,
        onSyncBook = onSyncBook,
    )
}

@Composable
private fun RemoteBookContextMenu(
    entry: RemoteBookEntry,
    expanded: Boolean,
    onDismiss: () -> Unit,
    onDeleteCandidate: (RemoteBookEntry) -> Unit,
) {
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismiss,
    ) {
        DropdownMenuItem(
            text = { Text(stringResource(R.string.bookshelf_delete_remote_book)) },
            onClick = {
                onDeleteCandidate(entry)
                onDismiss()
            },
        )
    }
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
        SortMenuHeader(text = stringResource(R.string.bookshelf_sync))
        HorizontalDivider()
        DropdownMenuItem(
            text = { Text(stringResource(R.string.action_import)) },
            onClick = {
                onSyncBook(entry, SyncDirection.ImportFromTtu)
                onDismiss()
            },
        )
        DropdownMenuItem(
            text = { Text(stringResource(R.string.action_export)) },
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
        SortMenuHeader(text = stringResource(R.string.bookshelf_move))
        HorizontalDivider()
        DropdownMenuItem(
            text = { Text(stringResource(R.string.bookshelf_no_shelf)) },
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
private fun ProfileDestinationMenu(
    entry: BookEntry,
    profileState: ProfileState,
    expanded: Boolean,
    onDismiss: () -> Unit,
    onSetBookProfile: (BookEntry, String?) -> Unit,
) {
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismiss,
    ) {
        SortMenuHeader(text = stringResource(R.string.bookshelf_profile))
        HorizontalDivider()
        val automaticProfile = profileState.automaticBookProfile(entry.metadata.bookLanguage)
        val selectedProfileId = selectedBookProfileId(entry.metadata.profileId, profileState)
        DropdownMenuItem(
            text = {
                Column {
                    Text(stringResource(R.string.bookshelf_profile_automatic))
                    Text(
                        text = stringResource(R.string.bookshelf_profile_automatic_uses_format, automaticProfile.name),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            leadingIcon = {
                if (selectedProfileId == null) {
                    Icon(Icons.Rounded.CheckCircle, contentDescription = null)
                }
            },
            onClick = {
                onSetBookProfile(entry, null)
                onDismiss()
            },
        )
        profileState.profiles.forEach { profile ->
            val language = ContentLanguageProfile.fromDictionaryLanguageId(profile.dictionaryLanguageId)
                ?: ContentLanguageProfile.Default
            DropdownMenuItem(
                text = {
                    Column {
                        Text(profile.name)
                        Text(
                            text = stringResource(language.displayNameRes),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                leadingIcon = {
                    if (selectedProfileId == profile.id) {
                        Icon(Icons.Rounded.CheckCircle, contentDescription = null)
                    }
                },
                onClick = {
                    onSetBookProfile(entry, profile.id)
                    onDismiss()
                },
            )
        }
    }
}

internal fun selectedBookProfileId(
    bookProfileId: String?,
    profileState: ProfileState,
): String? = bookProfileId?.takeIf { profileState.profileById(it) != null }

@Composable
internal fun ShelfManagementDialog(
    shelves: List<BookShelf>,
    showReading: Boolean,
    onShowReadingChange: (Boolean) -> Unit,
    onCreateShelf: (String) -> Unit,
    onDeleteShelf: (String) -> Unit,
    onRenameShelf: (String, String) -> Unit,
    onMoveShelf: (Int, Int) -> Unit,
    onDismiss: () -> Unit,
) {
    var newShelfName by remember { mutableStateOf("") }
    val newShelfNameScrollState = rememberScrollState()
    val newShelfNameState = rememberSyncedTextFieldState(
        value = newShelfName,
        onValueChange = { newShelfName = it },
        scrollState = newShelfNameScrollState,
    )
    val trimmedName = newShelfName.trim()
    val shelfNames = remember(shelves) { shelves.mapTo(mutableSetOf()) { it.name } }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.bookshelf_manage_shelves)) },
        text = {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag(ShelfManagementShelfListTag),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item(key = "reading-shelf") {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(stringResource(R.string.bookshelf_reading_shelf), style = MaterialTheme.typography.bodyLarge)
                            Text(
                                stringResource(R.string.bookshelf_reading_shelf_description),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(
                            checked = showReading,
                            onCheckedChange = onShowReadingChange,
                        )
                    }
                }
                item(key = "reading-shelf-divider") {
                    HorizontalDivider()
                }
                item(key = "shelves-heading") {
                    Text(stringResource(R.string.bookshelf_shelves), style = MaterialTheme.typography.titleMedium)
                }
                if (shelves.isEmpty()) {
                    item(key = "empty-shelves") {
                        Text(
                            stringResource(R.string.bookshelf_no_shelves),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                } else {
                    itemsIndexed(
                        items = shelves,
                        key = { _, shelf -> shelf.name },
                    ) { index, shelf ->
                        ShelfManagementShelfRow(
                            shelf = shelf,
                            index = index,
                            lastIndex = shelves.lastIndex,
                            shelfNames = shelfNames,
                            onDeleteShelf = onDeleteShelf,
                            onRenameShelf = onRenameShelf,
                            onMoveShelf = onMoveShelf,
                        )
                    }
                }
                item(key = "new-shelf") {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        OutlinedTextField(
                            state = newShelfNameState,
                            label = { Text(stringResource(R.string.bookshelf_shelf_name)) },
                            lineLimits = hoshiSingleLineTextFieldLineLimits(),
                            scrollState = newShelfNameScrollState,
                            colors = hoshiOutlinedTextFieldColors(),
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
                            Icon(Icons.Rounded.Add, contentDescription = stringResource(R.string.bookshelf_add_shelf))
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_done))
            }
        },
    )
}

@Composable
private fun ShelfManagementShelfRow(
    shelf: BookShelf,
    index: Int,
    lastIndex: Int,
    shelfNames: Set<String>,
    onDeleteShelf: (String) -> Unit,
    onRenameShelf: (String, String) -> Unit,
    onMoveShelf: (Int, Int) -> Unit,
) {
    var isRenaming by remember(shelf.name) { mutableStateOf(false) }
    var draftName by remember(shelf.name) { mutableStateOf(shelf.name) }
    if (isRenaming) {
        val draftScrollState = rememberScrollState()
        val draftNameState = rememberSyncedTextFieldState(
            value = draftName,
            onValueChange = { draftName = it },
            scrollState = draftScrollState,
        )
        val trimmedName = draftName.trim()
        val isDuplicateName = trimmedName != shelf.name && trimmedName in shelfNames
        val canSaveName = trimmedName.isNotEmpty() && !isDuplicateName
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.Top,
        ) {
            Column(Modifier.weight(1f)) {
                OutlinedTextField(
                    state = draftNameState,
                    label = { Text(stringResource(R.string.bookshelf_shelf_name)) },
                    lineLimits = hoshiSingleLineTextFieldLineLimits(),
                    scrollState = draftScrollState,
                    colors = hoshiOutlinedTextFieldColors(),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    isError = isDuplicateName,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (isDuplicateName) {
                    Text(
                        text = stringResource(R.string.bookshelf_shelf_name_exists),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
            IconButton(
                onClick = {
                    if (trimmedName != shelf.name) {
                        onRenameShelf(shelf.name, trimmedName)
                    }
                    isRenaming = false
                },
                enabled = canSaveName,
            ) {
                Icon(Icons.Rounded.Done, contentDescription = stringResource(R.string.bookshelf_save_shelf_name))
            }
        }
    } else {
        var menuExpanded by remember { mutableStateOf(false) }
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
            Box {
                IconButton(onClick = { menuExpanded = true }) {
                    Icon(Icons.Rounded.MoreVert, contentDescription = stringResource(R.string.action_more))
                }
                DropdownMenu(
                    expanded = menuExpanded,
                    onDismissRequest = { menuExpanded = false },
                ) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.bookshelf_rename_shelf)) },
                        leadingIcon = { Icon(Icons.Rounded.Edit, contentDescription = null) },
                        onClick = {
                            draftName = shelf.name
                            isRenaming = true
                            menuExpanded = false
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.bookshelf_move_shelf_up)) },
                        leadingIcon = { Icon(Icons.Rounded.ArrowUpward, contentDescription = null) },
                        enabled = index > 0,
                        onClick = {
                            onMoveShelf(index, index - 1)
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.bookshelf_move_shelf_down)) },
                        leadingIcon = { Icon(Icons.Rounded.ArrowDownward, contentDescription = null) },
                        enabled = index < lastIndex,
                        onClick = {
                            onMoveShelf(index, index + 1)
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.bookshelf_delete_shelf)) },
                        leadingIcon = { Icon(Icons.Rounded.Delete, contentDescription = null) },
                        onClick = {
                            onDeleteShelf(shelf.name)
                            menuExpanded = false
                        },
                    )
                }
            }
        }
    }
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
                        text = stringResource(R.string.main_tab_settings),
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
                text = stringResource(row.labelRes),
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
            text = stringResource(R.string.bookshelf_empty_title),
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.height(10.dp))
        Text(
            text = stringResource(R.string.bookshelf_empty_message),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(24.dp))
        Row(Modifier.fillMaxWidth()) {
            Button(onClick = onImport, enabled = enabled) {
                Text(stringResource(R.string.bookshelf_import_epub))
            }
        }
    }
}

@Composable
private fun BottomTabGlyph(tab: MainTab, modifier: Modifier = Modifier) {
    val icon = when (tab) {
        MainTab.Books -> Icons.AutoMirrored.Rounded.MenuBook
        MainTab.Dictionary -> Icons.Rounded.Translate
        MainTab.Statistics -> Icons.AutoMirrored.Rounded.ShowChart
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
        SettingsDestination.Profiles -> Icons.Rounded.Person
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

private fun String.sanitizeExportFileName(): String =
    split(Regex("[\\\\/:*?\"<>|\\n\\r\\u0000-\\u001F]"))
        .joinToString("_")
        .trim()
        .ifBlank { "book" }
