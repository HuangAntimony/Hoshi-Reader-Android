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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.MenuBook
import androidx.compose.material.icons.automirrored.rounded.Sort
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.BugReport
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Inventory2
import androidx.compose.material.icons.rounded.Keyboard
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.ReportProblem
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.SwapVert
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
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import moe.antimony.hoshi.dictionary.DictionaryRepository
import moe.antimony.hoshi.epub.BookEntry
import moe.antimony.hoshi.epub.BookRepository
import moe.antimony.hoshi.epub.BookSortOption
import moe.antimony.hoshi.epub.BookStorage
import moe.antimony.hoshi.features.sasayaki.SasayakiSettingsStore
import moe.antimony.hoshi.importing.FileImportContent
import moe.antimony.hoshi.importing.ImportFileType
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
    sasayakiSettingsReloadKey: Int = 0,
    refreshKey: Int = 0,
    layoutSpec: MainShellLayoutSpec,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val bookRepository = remember { BookRepository(context.filesDir) }
    val booksViewModel: BookshelfViewModel = viewModel(
        factory = remember(context, bookRepository) {
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    BookshelfViewModel(
                        AndroidBookshelfRepository(
                            contentResolver = context.contentResolver,
                            bookRepository = bookRepository,
                            dictionaryRepository = DictionaryRepository(context.filesDir, context.cacheDir),
                            sasayakiSettingsStore = SasayakiSettingsStore(context),
                        ),
                    ) as T
            }
        },
    )
    val uiState by booksViewModel.uiState.collectAsState()
    var sortMenuExpanded by remember { mutableStateOf(false) }
    var contextMenuEntry by remember { mutableStateOf<BookEntry?>(null) }
    var deleteCandidate by remember { mutableStateOf<BookEntry?>(null) }

    val importer = rememberLauncherForActivityResult(FileImportContent()) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        runCatching {
            context.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
        }
        booksViewModel.importBook(uri)
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

    LaunchedEffect(sasayakiSettingsReloadKey) {
        booksViewModel.reloadSasayakiSettings()
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
        bookProgressById = uiState.bookProgressById,
        bookRepository = bookRepository,
        isLoading = uiState.isLoading,
        errorMessage = uiState.errorMessage,
        sortOption = uiState.sortOption,
        sortMenuExpanded = sortMenuExpanded,
        onSortMenuExpandedChange = { sortMenuExpanded = it },
        onSortChange = {
            sortMenuExpanded = false
            booksViewModel.changeSort(it)
        },
        onImport = ::launchBookImporter,
        onOpenBook = booksViewModel::openBook,
        contextMenuEntry = contextMenuEntry,
        onContextMenuEntryChange = { contextMenuEntry = it },
        onDeleteCandidate = { deleteCandidate = it },
        sasayakiEnabled = uiState.sasayakiEnabled,
        onMatchSasayaki = { entry ->
            onOpenSasayakiMatch(SasayakiMatchRequest(entry.metadata.id, entry))
        },
    )

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

@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun BooksTab(
    layoutSpec: MainShellLayoutSpec,
    bookEntries: List<BookEntry>,
    bookProgressById: Map<String, Double>,
    bookRepository: BookRepository,
    isLoading: Boolean,
    errorMessage: String?,
    sortOption: BookSortOption,
    sortMenuExpanded: Boolean,
    onSortMenuExpandedChange: (Boolean) -> Unit,
    onSortChange: (BookSortOption) -> Unit,
    onImport: () -> Unit,
    onOpenBook: (BookEntry) -> Unit,
    contextMenuEntry: BookEntry?,
    onContextMenuEntryChange: (BookEntry?) -> Unit,
    onDeleteCandidate: (BookEntry) -> Unit,
    sasayakiEnabled: Boolean,
    onMatchSasayaki: (BookEntry) -> Unit,
    modifier: Modifier = Modifier,
) {
    val sections = remember(bookEntries) { bookshelfSections(bookEntries) }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        contentColor = MaterialTheme.colorScheme.onBackground,
        contentWindowInsets = WindowInsets(0.dp, 0.dp, 0.dp, 0.dp),
        topBar = {
            BooksTopAppBar(
                layoutSpec = layoutSpec,
                sortOption = sortOption,
                sortMenuExpanded = sortMenuExpanded,
                onSortMenuExpandedChange = onSortMenuExpandedChange,
                onSortChange = onSortChange,
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
                bookEntries.isEmpty() -> EmptyBooksView(
                    errorMessage = errorMessage,
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
                        sections.forEach { section ->
                            item(span = { androidx.compose.foundation.lazy.grid.GridItemSpan(maxLineSpan) }) {
                                BookshelfSectionHeader(
                                    title = section.title,
                                    count = section.books.size,
                                    layoutSpec = layoutSpec,
                                )
                            }
                            items(
                                items = section.books,
                                key = { it.metadata.id },
                            ) { entry ->
                                Box {
                                    BookGridCell(
                                        entry = entry,
                                        progress = bookProgressById[entry.metadata.id] ?: 0.0,
                                        bookRepository = bookRepository,
                                        layoutSpec = layoutSpec,
                                        onOpen = { onOpenBook(entry) },
                                        onLongPress = { onContextMenuEntryChange(entry) },
                                    )
                                    DropdownMenu(
                                        expanded = contextMenuEntry?.metadata?.id == entry.metadata.id,
                                        onDismissRequest = { onContextMenuEntryChange(null) },
                                    ) {
                                        DropdownMenuItem(
                                            text = { Text("Delete") },
                                            onClick = {
                                                contextMenuEntry?.let(onDeleteCandidate)
                                                onContextMenuEntryChange(null)
                                            },
                                        )
                                        if (sasayakiEnabled) {
                                            DropdownMenuItem(
                                                text = { Text("Match Sasayaki") },
                                                onClick = {
                                                    contextMenuEntry?.let(onMatchSasayaki)
                                                    onContextMenuEntryChange(null)
                                                },
                                            )
                                        }
                                    }
                                }
                            }
                        }
                        errorMessage?.let { message ->
                            item(span = { androidx.compose.foundation.lazy.grid.GridItemSpan(maxLineSpan) }) {
                                Text(
                                    text = message,
                                    color = MaterialTheme.colorScheme.error,
                                    modifier = Modifier.padding(top = 8.dp),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun BooksTopAppBar(
    layoutSpec: MainShellLayoutSpec,
    sortOption: BookSortOption,
    sortMenuExpanded: Boolean,
    onSortMenuExpandedChange: (Boolean) -> Unit,
    onSortChange: (BookSortOption) -> Unit,
    onImport: () -> Unit,
) {
    CenterAlignedTopAppBar(
        title = {
            Text(
                text = "Books",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
            )
        },
        navigationIcon = {
            Box {
                IconButton(onClick = { onSortMenuExpandedChange(true) }) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Rounded.Sort,
                        contentDescription = "Sort books",
                    )
                }
                DropdownMenu(
                    expanded = sortMenuExpanded,
                    onDismissRequest = { onSortMenuExpandedChange(false) },
                ) {
                    DropdownMenuItem(
                        text = { Text("Recent") },
                        onClick = { onSortChange(BookSortOption.Recent) },
                    )
                    DropdownMenuItem(
                        text = { Text("Title") },
                        onClick = { onSortChange(BookSortOption.Title) },
                    )
                }
            }
        },
        actions = {
            IconButton(
                onClick = {
                    onSortChange(
                        if (sortOption == BookSortOption.Recent) {
                            BookSortOption.Title
                        } else {
                            BookSortOption.Recent
                        },
                    )
                },
            ) {
                Icon(
                    imageVector = Icons.Rounded.SwapVert,
                    contentDescription = "Toggle book sort",
                )
            }
            IconButton(onClick = {}) {
                Icon(
                    imageVector = Icons.Rounded.FolderOpen,
                    contentDescription = "Shelves",
                )
            }
            IconButton(onClick = onImport) {
                Icon(
                    imageVector = Icons.Rounded.Add,
                    contentDescription = "Import EPUB",
                )
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = MaterialTheme.colorScheme.background,
            scrolledContainerColor = MaterialTheme.colorScheme.background,
        ),
    )
}

@Composable
private fun BookshelfSectionHeader(
    title: String,
    count: Int,
    layoutSpec: MainShellLayoutSpec,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
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
        Spacer(Modifier.width(8.dp))
        ChevronRightGlyph(MaterialTheme.colorScheme.onBackground, Modifier.size(20.dp))
    }
}

@Composable
private fun BookGridCell(
    entry: BookEntry,
    progress: Double,
    bookRepository: BookRepository,
    layoutSpec: MainShellLayoutSpec,
    onOpen: () -> Unit,
    onLongPress: () -> Unit,
) {
    Column(
        modifier = Modifier.combinedClickable(
            onClick = onOpen,
            onLongClick = onLongPress,
        ),
    ) {
        BookCoverCard(entry = entry, bookRepository = bookRepository)
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

internal fun loadBookProgressById(
    entries: List<BookEntry>,
    bookStorage: BookStorage,
): Map<String, Double> =
    entries.associate { entry ->
        entry.metadata.id to bookStorage.loadReadingProgress(entry.root)
    }

internal fun loadBookProgressById(
    entries: List<BookEntry>,
    bookRepository: BookRepository,
): Map<String, Double> =
    entries.associate { entry ->
        entry.metadata.id to bookRepository.loadReadingProgress(entry.root)
    }

@Composable
private fun BookCoverCard(entry: BookEntry, bookRepository: BookRepository) {
    val coverFile = remember(entry) { bookRepository.coverFile(entry) }
    val bitmap by produceState<Bitmap?>(initialValue = null, key1 = coverFile) {
        value = BookCoverBitmapCache.load(coverFile)
    }
    val shape = RoundedCornerShape(10.dp)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(0.72f)
            .clip(shape)
            .background(Color.White)
            .border(BorderStroke(1.dp, Color.White.copy(alpha = 0.9f)), shape),
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
            text = "${(clamped * 100).coerceAtMost(99.9f).formatOneDecimal()}%",
            style = MaterialTheme.typography.labelLarge,
            color = progressTextColor,
            fontWeight = FontWeight.Bold,
        )
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
    errorMessage: String?,
    onImport: () -> Unit,
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
            Button(onClick = onImport) {
                Text("Import EPUB")
            }
        }
        if (errorMessage != null) {
            Spacer(Modifier.height(18.dp))
            Text(errorMessage, color = MaterialTheme.colorScheme.error)
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

private fun Float.formatOneDecimal(): String =
    String.format(java.util.Locale.US, "%.1f", this)
