package moe.antimony.hoshi.features.bookshelf

import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.MenuBook
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Checklist
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Inventory2
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.ReportProblem
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Translate
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import moe.antimony.hoshi.dictionary.DictionaryRepository
import moe.antimony.hoshi.epub.BookEntry
import moe.antimony.hoshi.epub.BookMetadata
import moe.antimony.hoshi.epub.BookSortOption
import moe.antimony.hoshi.epub.BookStorage
import moe.antimony.hoshi.epub.Bookmark
import moe.antimony.hoshi.epub.EpubBook
import moe.antimony.hoshi.epub.EpubBookParser
import moe.antimony.hoshi.features.audio.AdvancedSettingsView
import moe.antimony.hoshi.features.dictionary.DictionaryView
import moe.antimony.hoshi.features.dictionary.DictionarySearchView
import moe.antimony.hoshi.features.dictionary.DictionarySettingsStore
import moe.antimony.hoshi.features.reader.ReaderAppearanceScreen
import moe.antimony.hoshi.features.reader.ReaderFontManager
import moe.antimony.hoshi.features.reader.ReaderSettings
import moe.antimony.hoshi.features.reader.ReaderWebView
import java.io.File

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun BookshelfView(
    pendingImportUri: Uri? = null,
    onPendingImportConsumed: () -> Unit = {},
    readerSettings: ReaderSettings,
    onReaderSettingsChange: (ReaderSettings) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val bookStorage = remember { BookStorage(context.filesDir) }
    val dictionaryRepository = remember { DictionaryRepository(context.filesDir, context.cacheDir) }
    val dictionarySettingsStore = remember { DictionarySettingsStore(context) }
    val readerFontManager = remember { ReaderFontManager(context.filesDir) }
    var selectedTab by remember {
        mutableStateOf(
            if (dictionarySettingsStore.load().dictionaryTabDefault) {
                MainTab.Dictionary
            } else {
                MainTab.Books
            },
        )
    }
    var settingsDestination by remember { mutableStateOf<SettingsDestination?>(null) }
    var bookEntries by remember { mutableStateOf<List<BookEntry>>(emptyList()) }
    var sortOption by remember { mutableStateOf(BookSortOption.Recent) }
    var sortMenuExpanded by remember { mutableStateOf(false) }
    var contextMenuEntry by remember { mutableStateOf<BookEntry?>(null) }
    var deleteCandidate by remember { mutableStateOf<BookEntry?>(null) }
    var selectedBookRoot by remember { mutableStateOf<File?>(null) }
    var book by remember { mutableStateOf<EpubBook?>(null) }
    var bookmark by remember { mutableStateOf<Bookmark?>(null) }
    var isReading by remember { mutableStateOf(false) }
    var isLoading by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    fun reloadBookEntries() {
        bookEntries = bookStorage.loadBookEntries(sortOption)
    }

    fun saveMetadata(root: File, parsedBook: EpubBook, previous: BookMetadata? = null) {
        val metadata = BookMetadata(
            id = previous?.id ?: root.name,
            title = parsedBook.title,
            cover = parsedBook.coverHref,
            folder = root.name,
            lastAccess = bookStorage.currentAppleReferenceDateSeconds(),
        )
        bookStorage.saveMetadata(root, metadata)
    }

    fun saveBookInfo(root: File, parsedBook: EpubBook) {
        bookStorage.saveBookInfo(root, parsedBook.bookInfo)
    }

    fun parseBook(file: File, openReader: Boolean, refreshAccess: Boolean) {
        scope.launch {
            isLoading = true
            errorMessage = null
            runCatching {
                withContext(Dispatchers.IO) {
                    val parsedBook = EpubBookParser().parse(file)
                    if (refreshAccess) {
                        saveMetadata(file, parsedBook, bookStorage.loadMetadata(file))
                    }
                    saveBookInfo(file, parsedBook)
                    parsedBook
                }
            }.onSuccess { parsedBook ->
                selectedBookRoot = file
                book = parsedBook
                bookmark = bookStorage.loadBookmark(file)
                reloadBookEntries()
                isReading = openReader
            }.onFailure {
                errorMessage = it.localizedMessage ?: "Failed to open EPUB."
            }
            isLoading = false
        }
    }

    fun importBook(uri: Uri) {
        scope.launch {
            isLoading = true
            errorMessage = null
            runCatching {
                withContext(Dispatchers.IO) {
                    val root = bookStorage.importBook(context.contentResolver, uri)
                    val parsedBook = EpubBookParser().parse(root)
                    saveMetadata(root, parsedBook, bookStorage.loadMetadata(root))
                    saveBookInfo(root, parsedBook)
                    root to parsedBook
                }
            }.onSuccess { (root, parsedBook) ->
                selectedBookRoot = root
                book = parsedBook
                bookmark = bookStorage.loadBookmark(root)
                reloadBookEntries()
                selectedTab = MainTab.Books
                isReading = true
                isLoading = false
            }.onFailure {
                errorMessage = it.localizedMessage ?: "Failed to import EPUB."
                isLoading = false
            }
        }
    }

    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION,
                )
            }
            importBook(uri)
        }
    }

    fun launchBookImporter() {
        importer.launch(arrayOf("application/epub+zip", "application/octet-stream"))
    }

    fun updateReaderSettings(settings: ReaderSettings) {
        onReaderSettingsChange(settings)
    }

    LaunchedEffect(Unit) {
        reloadBookEntries()
        withContext(Dispatchers.IO) {
            runCatching { dictionaryRepository.rebuildLookupQuery() }
        }
    }

    LaunchedEffect(pendingImportUri) {
        val uri = pendingImportUri ?: return@LaunchedEffect
        onPendingImportConsumed()
        importBook(uri)
    }

    if (isReading && book != null) {
        ReaderWebView(
            book = requireNotNull(book),
            initialChapterIndex = bookmark?.chapterIndex ?: 0,
            initialProgress = bookmark?.progress ?: 0.0,
            readerSettings = readerSettings,
            onReaderSettingsChange = ::updateReaderSettings,
            onSaveBookmark = { chapterIndex, progress ->
                val file = selectedBookRoot ?: return@ReaderWebView
                val parsedBook = book ?: return@ReaderWebView
                val savedBookmark = Bookmark(
                    chapterIndex = chapterIndex,
                    progress = progress,
                    characterCount = parsedBook.characterCountAt(chapterIndex, progress),
                    lastModified = bookStorage.currentAppleReferenceDateSeconds(),
                )
                bookmark = savedBookmark
                scope.launch(Dispatchers.IO) {
                    bookStorage.saveBookmark(file, savedBookmark)
                }
            },
            onClose = { isReading = false },
            modifier = modifier.fillMaxSize(),
        )
        return
    }

    if (settingsDestination == SettingsDestination.Dictionaries) {
        DictionaryView(
            onClose = { settingsDestination = null },
            modifier = modifier.fillMaxSize(),
        )
        return
    }

    if (settingsDestination == SettingsDestination.Appearance) {
        ReaderAppearanceScreen(
            settings = readerSettings,
            onSettingsChange = ::updateReaderSettings,
            fontManager = readerFontManager,
            onClose = { settingsDestination = null },
            modifier = modifier.fillMaxSize(),
        )
        return
    }

    if (settingsDestination == SettingsDestination.Advanced) {
        AdvancedSettingsView(
            onClose = { settingsDestination = null },
            modifier = modifier.fillMaxSize(),
        )
        return
    }

    HoshiMainShell(
        selectedTab = selectedTab,
        onSelectedTabChange = {
            selectedTab = it
            settingsDestination = null
        },
        modifier = modifier,
    ) { contentModifier ->
        when (selectedTab) {
            MainTab.Books -> BooksTab(
                modifier = contentModifier,
                bookEntries = bookEntries,
                bookStorage = bookStorage,
                isLoading = isLoading,
                errorMessage = errorMessage,
                sortOption = sortOption,
                sortMenuExpanded = sortMenuExpanded,
                onSortMenuExpandedChange = { sortMenuExpanded = it },
                onSortChange = {
                    sortOption = it
                    sortMenuExpanded = false
                    reloadBookEntries()
                },
                onImport = ::launchBookImporter,
                onOpenBook = { parseBook(it.root, openReader = true, refreshAccess = true) },
                contextMenuEntry = contextMenuEntry,
                onContextMenuEntryChange = { contextMenuEntry = it },
                onDeleteCandidate = { deleteCandidate = it },
            )
            MainTab.Dictionary -> DictionarySearchView(
                readerSettings = readerSettings,
                modifier = contentModifier.fillMaxSize(),
            )
            MainTab.Settings -> SettingsTab(
                modifier = contentModifier,
                onDestination = { destination ->
                    when (destination) {
                        SettingsDestination.Dictionaries -> settingsDestination = destination
                        SettingsDestination.ReportIssue -> context.startActivity(
                            Intent(
                                Intent.ACTION_VIEW,
                                Uri.parse(ReportIssueUrl),
                            ),
                        )
                        else -> settingsDestination = destination
                    }
                },
            )
        }
    }

    settingsDestination?.takeIf {
        it != SettingsDestination.Dictionaries &&
            it != SettingsDestination.Appearance &&
            it != SettingsDestination.Advanced
    }?.let { destination ->
        AlertDialog(
            onDismissRequest = { settingsDestination = null },
            title = { Text(destination.placeholderTitle()) },
            text = { Text("This settings page is not implemented yet.") },
            confirmButton = {
                TextButton(onClick = { settingsDestination = null }) {
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
                        scope.launch(Dispatchers.IO) {
                            bookStorage.deleteBook(candidate.root)
                            withContext(Dispatchers.Main) {
                                if (selectedBookRoot == candidate.root) {
                                    selectedBookRoot = null
                                    book = null
                                    bookmark = null
                                    isReading = false
                                }
                                reloadBookEntries()
                                deleteCandidate = null
                            }
                        }
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
private fun HoshiMainShell(
    selectedTab: MainTab,
    onSelectedTabChange: (MainTab) -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable (Modifier) -> Unit,
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        content(Modifier.fillMaxSize())
        HoshiBottomTabs(
            selectedTab = selectedTab,
            onSelectedTabChange = onSelectedTabChange,
            modifier = Modifier.align(Alignment.BottomCenter),
        )
    }
}

@Composable
private fun BooksTab(
    bookEntries: List<BookEntry>,
    bookStorage: BookStorage,
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
    modifier: Modifier = Modifier,
) {
    val sections = remember(bookEntries) { bookshelfSections(bookEntries) }

    Box(modifier = modifier.fillMaxSize()) {
        when {
            isLoading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            bookEntries.isEmpty() -> EmptyBooksView(
                errorMessage = errorMessage,
                onImport = onImport,
                modifier = Modifier.fillMaxSize(),
            )
            else -> LazyVerticalGrid(
                columns = GridCells.Fixed(2),
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 28.dp, end = 28.dp, top = 128.dp, bottom = 164.dp),
                horizontalArrangement = Arrangement.spacedBy(30.dp),
                verticalArrangement = Arrangement.spacedBy(24.dp),
            ) {
                sections.forEach { section ->
                    item(span = { androidx.compose.foundation.lazy.grid.GridItemSpan(maxLineSpan) }) {
                        BookshelfSectionHeader(
                            title = section.title,
                            count = section.books.size,
                        )
                    }
                    items(
                        items = section.books,
                        key = { it.metadata.id },
                    ) { entry ->
                        Box {
                            BookGridCell(
                                entry = entry,
                                bookStorage = bookStorage,
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

        BooksTopChrome(
            sortOption = sortOption,
            sortMenuExpanded = sortMenuExpanded,
            onSortMenuExpandedChange = onSortMenuExpandedChange,
            onSortChange = onSortChange,
            onImport = onImport,
            modifier = Modifier.align(Alignment.TopCenter),
        )
    }
}

@Composable
private fun BooksTopChrome(
    sortOption: BookSortOption,
    sortMenuExpanded: Boolean,
    onSortMenuExpandedChange: (Boolean) -> Unit,
    onSortChange: (BookSortOption) -> Unit,
    onImport: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(top = 18.dp, start = 28.dp, end = 28.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FrostedCapsule {
            Box {
                ChromeIconButton(onClick = { onSortMenuExpandedChange(true) }) {
                    SortGlyph(MaterialTheme.colorScheme.onSurface)
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
            ChromeIconButton(onClick = { onSortChange(if (sortOption == BookSortOption.Recent) BookSortOption.Title else BookSortOption.Recent) }) {
                ListCheckGlyph(MaterialTheme.colorScheme.onSurface)
            }
        }
        Spacer(Modifier.weight(1f))
        Text(
            text = "Books",
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onBackground,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(top = 8.dp),
        )
        Spacer(Modifier.weight(1f))
        FrostedCircle(onClick = {}) {
            FolderGlyph(MaterialTheme.colorScheme.onSurface)
        }
        Spacer(Modifier.width(12.dp))
        FrostedCircle(onClick = onImport) {
            PlusGlyph(MaterialTheme.colorScheme.onSurface)
        }
    }
}

@Composable
private fun BookshelfSectionHeader(title: String, count: Int) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 4.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Black,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Spacer(Modifier.width(12.dp))
        Text(
            text = count.toString(),
            style = MaterialTheme.typography.headlineSmall,
            color = Color(0xFF8C8C92),
        )
        Spacer(Modifier.width(8.dp))
        ChevronRightGlyph(MaterialTheme.colorScheme.onBackground, Modifier.size(20.dp))
    }
}

@Composable
private fun BookGridCell(
    entry: BookEntry,
    bookStorage: BookStorage,
    onOpen: () -> Unit,
    onLongPress: () -> Unit,
) {
    Column(
        modifier = Modifier.combinedClickable(
            onClick = onOpen,
            onLongClick = onLongPress,
        ),
    ) {
        BookCoverCard(entry = entry, bookStorage = bookStorage)
        Spacer(Modifier.height(6.dp))
        ReadingProgressPill(
            progress = remember(entry) { bookStorage.loadReadingProgress(entry.root) },
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = entry.metadata.title ?: entry.root.name,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onBackground,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun BookCoverCard(entry: BookEntry, bookStorage: BookStorage) {
    val coverFile = remember(entry) { bookStorage.coverFile(entry) }
    val bitmap = remember(coverFile) {
        coverFile?.absolutePath?.let(BitmapFactory::decodeFile)
    }
    val shape = RoundedCornerShape(10.dp)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(0.72f)
            .shadow(10.dp, shape, ambientColor = Color.Black.copy(alpha = 0.12f), spotColor = Color.Black.copy(alpha = 0.12f))
            .clip(shape)
            .background(Color.White)
            .border(BorderStroke(1.dp, Color.White.copy(alpha = 0.9f)), shape),
        contentAlignment = Alignment.Center,
    ) {
        if (bitmap != null) {
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

@Composable
private fun ReadingProgressPill(progress: Double, modifier: Modifier = Modifier) {
    val clamped = progress.coerceIn(0.0, 1.0).toFloat()
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .height(5.dp)
                .weight(1f)
                .clip(RoundedCornerShape(100))
                .background(Color(0xFFD7D7DB)),
        ) {
            if (clamped > 0f) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(clamped)
                        .height(5.dp)
                        .clip(RoundedCornerShape(100))
                        .background(Color(0xFFAFAFB4)),
                )
            }
        }
        Spacer(Modifier.width(8.dp))
        Text(
            text = "${(clamped * 100).coerceAtMost(99.9f).formatOneDecimal()}%",
            style = MaterialTheme.typography.labelLarge,
            color = Color(0xFF76767C),
            fontWeight = FontWeight.Bold,
        )
    }
}

@Composable
private fun SettingsTab(
    onDestination: (SettingsDestination) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
        contentPadding = PaddingValues(start = 28.dp, end = 28.dp, top = 112.dp, bottom = 230.dp),
        verticalArrangement = Arrangement.spacedBy(34.dp),
    ) {
        item {
            Text(
                text = "Settings",
                style = MaterialTheme.typography.displayMedium,
                fontWeight = FontWeight.Black,
                color = MaterialTheme.colorScheme.onBackground,
            )
        }
        settingsGroups().forEach { group ->
            item {
                SettingsGroupCard(rows = group, onDestination = onDestination)
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
        shape = RoundedCornerShape(32.dp),
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(vertical = 8.dp),
        ) {
            rows.forEachIndexed { index, row ->
                SettingsRow(row = row, onClick = { onDestination(row.destination) })
                if (index != rows.lastIndex) {
                    Box(
                        modifier = Modifier
                            .padding(start = 94.dp, end = 34.dp)
                            .height(1.dp)
                            .fillMaxWidth()
                            .background(MaterialTheme.colorScheme.outlineVariant),
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
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 32.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SettingsGlyph(row.destination, tint, Modifier.size(32.dp))
        Spacer(Modifier.width(26.dp))
        Text(
            text = row.label,
            style = MaterialTheme.typography.headlineSmall,
            color = tint,
        )
    }
}

@Composable
private fun HoshiBottomTabs(
    selectedTab: MainTab,
    onSelectedTabChange: (MainTab) -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier
            .navigationBarsPadding()
            .padding(bottom = 20.dp)
            .shadow(22.dp, RoundedCornerShape(38.dp), ambientColor = Color.Black.copy(alpha = 0.12f), spotColor = Color.Black.copy(alpha = 0.12f)),
        shape = RoundedCornerShape(38.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.86f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f)),
    ) {
        Row(
            modifier = Modifier.padding(7.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            MainTab.entries.forEach { tab ->
                val selected = tab == selectedTab
                val interactionSource = remember(tab) { MutableInteractionSource() }
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(34.dp))
                        .background(
                            if (selected) {
                                MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.8f)
                            } else {
                                Color.Transparent
                            },
                        )
                        .clickable(
                            interactionSource = interactionSource,
                            indication = null,
                            onClick = { onSelectedTabChange(tab) },
                        )
                        .padding(horizontal = 22.dp, vertical = 9.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        BottomTabGlyph(tab, Modifier.size(28.dp))
                        Text(
                            text = tab.label,
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun FrostedCapsule(content: @Composable RowScope.() -> Unit) {
    Surface(
        shape = RoundedCornerShape(34.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.82f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f)),
        shadowElevation = 12.dp,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 7.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            content = content,
        )
    }
}

@Composable
private fun FrostedCircle(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    Surface(
        modifier = modifier
            .size(50.dp)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick,
            ),
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.84f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f)),
        shadowElevation = 12.dp,
    ) {
        Box(contentAlignment = Alignment.Center) {
            content()
        }
    }
}

@Composable
private fun ChromeIconButton(onClick: () -> Unit, content: @Composable () -> Unit) {
    val interactionSource = remember { MutableInteractionSource() }
    Box(
        modifier = Modifier
            .size(40.dp)
            .clip(CircleShape)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        content()
    }
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
            color = MaterialTheme.colorScheme.onSurface,
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
        tint = MaterialTheme.colorScheme.onSurface,
        modifier = modifier,
    )
}

@Composable
private fun SettingsGlyph(destination: SettingsDestination, color: Color, modifier: Modifier = Modifier) {
    val icon = when (destination) {
        SettingsDestination.Dictionaries -> Icons.AutoMirrored.Rounded.MenuBook
        SettingsDestination.Anki -> Icons.Rounded.Inventory2
        SettingsDestination.Appearance -> Icons.Rounded.Palette
        SettingsDestination.Advanced -> Icons.Rounded.Settings
        SettingsDestination.ReportIssue -> Icons.Rounded.ReportProblem
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
private fun SortGlyph(color: Color) {
    Row(horizontalArrangement = Arrangement.spacedBy((-10).dp)) {
        Icon(
            imageVector = Icons.Rounded.ArrowUpward,
            contentDescription = null,
            tint = color,
            modifier = Modifier.size(22.dp),
        )
        Icon(
            imageVector = Icons.Rounded.ArrowDownward,
            contentDescription = null,
            tint = color,
            modifier = Modifier.size(22.dp),
        )
    }
}

@Composable
private fun ListCheckGlyph(color: Color) {
    Icon(
        imageVector = Icons.Rounded.Checklist,
        contentDescription = null,
        tint = color,
        modifier = Modifier.size(24.dp),
    )
}

@Composable
private fun FolderGlyph(color: Color) {
    Icon(
        imageVector = Icons.Rounded.FolderOpen,
        contentDescription = null,
        tint = color,
        modifier = Modifier.size(24.dp),
    )
}

@Composable
private fun PlusGlyph(color: Color) {
    Icon(
        imageVector = Icons.Rounded.Add,
        contentDescription = null,
        tint = color,
        modifier = Modifier.size(26.dp),
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

private fun SettingsDestination.placeholderTitle(): String = when (this) {
    SettingsDestination.Anki -> "Anki"
    SettingsDestination.Appearance -> "Appearance"
    SettingsDestination.Advanced -> "Advanced"
    SettingsDestination.About -> "About"
    SettingsDestination.Dictionaries -> "Dictionaries"
    SettingsDestination.ReportIssue -> "Report an Issue"
}

private fun Float.formatOneDecimal(): String =
    String.format(java.util.Locale.US, "%.1f", this)
