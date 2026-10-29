package moe.antimony.hoshi.features.bookshelf

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import moe.antimony.hoshi.R
import moe.antimony.hoshi.epub.BookEntry
import moe.antimony.hoshi.epub.BookMetadata
import moe.antimony.hoshi.epub.BookReadingState
import moe.antimony.hoshi.features.sync.SyncSettings
import moe.antimony.hoshi.profiles.ProfileState
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** In-memory UI only; never opens the installed library or changes preferences. */
@RunWith(AndroidJUnit4::class)
class BookReadActionsTest {
    @get:Rule val compose = createComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private var entry by mutableStateOf(BookEntry(File("fixture"), BookMetadata("fixture", "Fixture book", lastAccess = 0.0)))
    private var readingState by mutableStateOf(BookReadingState())
    private var expanded by mutableStateOf(true)
    private var unreadCandidate by mutableStateOf<BookEntry?>(null)
    private var confirmed = 0
    private var markedRead = 0

    @Test fun menuUsesStoredCapabilitiesForLocalAndCloudBooks() {
        setContent()
        assertMainMenu()
        compose.onNodeWithText(context.getString(R.string.bookshelf_mark)).performClick()
        val cases = listOf(
            null to BookReadingState(0.0, true, false),
            "book.epub" to BookReadingState(0.5, false, true),
            null to BookReadingState(0.0, false, true),
            null to BookReadingState(),
        )
        for ((epub, state) in cases) {
            compose.runOnIdle { entry = entry.copy(metadata = entry.metadata.copy(epub = epub)); readingState = state }
            val read = compose.onNodeWithText(context.getString(R.string.bookshelf_read))
            val unread = compose.onNodeWithText(context.getString(R.string.bookshelf_unread))
            if (state.canMarkRead) read.assertIsEnabled() else read.assertIsNotEnabled()
            if (state.canMarkUnread) unread.assertIsEnabled() else unread.assertIsNotEnabled()
        }
        compose.runOnIdle { readingState = BookReadingState(canMarkRead = true) }
        compose.onNodeWithText(context.getString(R.string.bookshelf_read)).performClick()
        compose.runOnIdle { assertEquals(1, markedRead); expanded = true }
        assertMainMenu()
    }

    @Test fun unreadRequiresConfirmationAndCancelDoesNotReset() {
        readingState = BookReadingState(0.5, true, true)
        setContent()
        compose.onNodeWithText(context.getString(R.string.bookshelf_mark)).performClick()
        compose.onNodeWithText(context.getString(R.string.bookshelf_unread)).performClick()
        compose.onNodeWithText(context.getString(R.string.bookshelf_mark_unread_title_format, "Fixture book")).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.bookshelf_mark_unread_confirmation)).assertIsDisplayed()
        compose.runOnIdle { assertEquals(0, confirmed) }
        compose.onNodeWithText(context.getString(R.string.action_cancel)).performClick()
        compose.runOnIdle { assertEquals(0, confirmed); expanded = true }
        assertMainMenu()
        compose.onNodeWithText(context.getString(R.string.bookshelf_mark)).performClick()
        compose.onNodeWithText(context.getString(R.string.bookshelf_unread)).performClick()
        compose.onNodeWithText(context.getString(R.string.bookshelf_mark_unread)).performClick()
        compose.runOnIdle { assertEquals(1, confirmed) }
    }

    private fun assertMainMenu() {
        compose.onNodeWithText(context.getString(R.string.bookshelf_mark)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.bookshelf_read)).assertDoesNotExist()
        compose.onNodeWithText(context.getString(R.string.bookshelf_unread)).assertDoesNotExist()
    }

    private fun setContent() {
        compose.setContent {
            MaterialTheme {
                BookContextMenu(
                    entry = entry, readingState = readingState, shelves = emptyList(), currentShelfName = null,
                    hideMove = false, expanded = expanded, onDismiss = { expanded = false },
                    onMoveBook = { _, _ -> }, onCreateShelfForBook = {}, onMarkReadCandidate = { markedRead++ },
                    onMarkUnreadCandidate = { unreadCandidate = it }, onRenameCandidate = {},
                    onDeleteCandidate = {}, onExportCandidate = {},
                    profileState = ProfileState(emptyList(), "fixture", "fixture", null, emptyMap()),
                    onSetBookProfile = { _, _ -> }, syncSettings = SyncSettings(), onSyncBook = { _, _ -> }, onDownloadBook = {},
                )
                unreadCandidate?.let {
                    MarkUnreadConfirmationDialog(it, onConfirm = { confirmed++; unreadCandidate = null },
                        onDismiss = { unreadCandidate = null })
                }
            }
        }
    }
}
