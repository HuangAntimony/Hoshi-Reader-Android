package moe.antimony.hoshi.features.bookshelf

import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import moe.antimony.hoshi.epub.Bookmark
import moe.antimony.hoshi.epub.BookEntry
import moe.antimony.hoshi.epub.BookMetadata
import moe.antimony.hoshi.epub.BookStorage
import moe.antimony.hoshi.epub.EpubBook
import moe.antimony.hoshi.epub.EpubBookParser
import moe.antimony.hoshi.features.reader.ReaderWebView
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BookshelfView(
    pendingImportUri: Uri? = null,
    onPendingImportConsumed: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    val bookStorage = remember { BookStorage(context.filesDir) }
    var bookEntries by remember { mutableStateOf<List<BookEntry>>(emptyList()) }
    var selectedBookRoot by remember { mutableStateOf<File?>(null) }
    var book by remember { mutableStateOf<EpubBook?>(null) }
    var bookmark by remember { mutableStateOf<Bookmark?>(null) }
    var isReading by remember { mutableStateOf(false) }
    var isLoading by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    fun reloadBookEntries() {
        bookEntries = bookStorage.loadBookEntries()
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
                    saveMetadata(root, parsedBook)
                    root to parsedBook
                }
            }.onSuccess { (root, parsedBook) ->
                selectedBookRoot = root
                book = parsedBook
                bookmark = bookStorage.loadBookmark(root)
                reloadBookEntries()
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

    LaunchedEffect(Unit) {
        reloadBookEntries()
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
            onSaveBookmark = { chapterIndex, progress ->
                val file = selectedBookRoot ?: return@ReaderWebView
                val savedBookmark = Bookmark(
                    chapterIndex = chapterIndex,
                    progress = progress,
                    characterCount = 0,
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

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text("Books") },
                actions = {
                    TextButton(onClick = { importer.launch(arrayOf("application/epub+zip", "application/octet-stream")) }) {
                        Text("+")
                    }
                },
            )
        },
    ) { innerPadding ->
        Surface(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            color = MaterialTheme.colorScheme.background,
        ) {
            when {
                isLoading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
                bookEntries.isEmpty() -> EmptyBooksView(
                    errorMessage = errorMessage,
                    onImport = { importer.launch(arrayOf("application/epub+zip", "application/octet-stream")) },
                )
                else -> Column(Modifier.fillMaxSize()) {
                    bookEntries.forEach { entry ->
                        ListItem(
                            leadingContent = {
                                BookCoverThumbnail(
                                    entry = entry,
                                    bookStorage = bookStorage,
                                )
                            },
                            headlineContent = {
                                Text(
                                    text = entry.metadata.title ?: entry.root.name,
                                    fontWeight = FontWeight.Medium,
                                )
                            },
                            supportingContent = { Text(entry.metadata.folder ?: entry.root.name) },
                            modifier = Modifier.clickable {
                                parseBook(entry.root, openReader = true, refreshAccess = true)
                            },
                        )
                    }
                    if (errorMessage != null) {
                        Text(
                            text = requireNotNull(errorMessage),
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(16.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun BookCoverThumbnail(entry: BookEntry, bookStorage: BookStorage) {
    val coverFile = remember(entry) { bookStorage.coverFile(entry) }
    val bitmap = remember(coverFile) {
        coverFile?.absolutePath?.let(BitmapFactory::decodeFile)
    }
    val shape = androidx.compose.foundation.shape.RoundedCornerShape(6.dp)
    Box(
        modifier = Modifier
            .height(78.dp)
            .aspectRatio(0.709f)
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceVariant),
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
private fun EmptyBooksView(errorMessage: String?, onImport: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(28.dp),
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
