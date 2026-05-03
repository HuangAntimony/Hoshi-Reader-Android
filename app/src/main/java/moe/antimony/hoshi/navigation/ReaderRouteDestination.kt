package moe.antimony.hoshi.navigation

import android.view.KeyEvent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import moe.antimony.hoshi.features.reader.ReaderSettings
import moe.antimony.hoshi.features.reader.ReaderWebView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun ReaderRouteDestination(
    bookId: String,
    stateHolder: ReaderRouteStateHolder,
    readerSettings: ReaderSettings,
    onReaderSettingsChange: (ReaderSettings) -> Unit,
    onReaderKeyEventHandlerChange: (((KeyEvent) -> Boolean)?) -> Unit,
    onBookmarkSaved: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val bookmarkScope = rememberCoroutineScope()
    val routeState by produceState<ReaderRouteLoadState>(
        initialValue = ReaderRouteLoadState.Loading,
        key1 = bookId,
        key2 = stateHolder,
    ) {
        value = withContext(Dispatchers.IO) {
            stateHolder.load(bookId)
        }
    }

    when (val state = routeState) {
        ReaderRouteLoadState.Loading -> Box(
            modifier = modifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) {
            CircularProgressIndicator()
        }
        is ReaderRouteLoadState.Error -> Box(
            modifier = modifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) {
            Text(state.message)
        }
        is ReaderRouteLoadState.Ready -> ReaderWebView(
            book = state.book,
            bookRoot = state.bookRoot,
            initialChapterIndex = state.bookmark?.chapterIndex ?: 0,
            initialProgress = state.bookmark?.progress ?: 0.0,
            readerSettings = readerSettings,
            onReaderSettingsChange = onReaderSettingsChange,
            onReaderKeyEventHandlerChange = onReaderKeyEventHandlerChange,
            onSaveBookmark = { chapterIndex, progress ->
                bookmarkScope.launch(Dispatchers.IO) {
                    stateHolder.saveBookmark(
                        state = state,
                        chapterIndex = chapterIndex,
                        progress = progress,
                        onBookmarkSaved = {
                            bookmarkScope.launch(Dispatchers.Main) {
                                onBookmarkSaved()
                            }
                        },
                    )
                }
            },
            onClose = onClose,
            modifier = modifier.fillMaxSize(),
        )
    }
}
