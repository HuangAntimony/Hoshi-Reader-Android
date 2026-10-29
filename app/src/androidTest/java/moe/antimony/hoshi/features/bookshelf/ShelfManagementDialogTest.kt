package moe.antimony.hoshi.features.bookshelf

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import moe.antimony.hoshi.R
import moe.antimony.hoshi.epub.BookShelf
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ShelfManagementDialogTest {
    @get:Rule
    val composeRule = createComposeRule()

    private fun text(id: Int) = InstrumentationRegistry.getInstrumentation().targetContext.getString(id)

    @Test
    fun renameRejectsCanonicalDuplicateAndSubmitsCanonicalNewName() {
        val renamed = mutableListOf<Pair<String, String>>()
        setShelfManagementDialogContent(
            shelves = listOf(BookShelf("で", emptyList()), BookShelf("Other", emptyList())),
            onRenameShelf = { old, new -> renamed += old to new },
        )
        composeRule.onAllNodesWithContentDescription(text(R.string.action_more))[1].performClick()
        composeRule.onNodeWithText(text(R.string.bookshelf_rename_shelf)).performClick()
        val input = composeRule.onAllNodes(hasSetTextAction())[0]

        input.performTextReplacement("て\u3099")
        composeRule.onNodeWithContentDescription(text(R.string.bookshelf_save_shelf_name)).assertIsNotEnabled()
        composeRule.onNodeWithText(text(R.string.bookshelf_shelf_name_exists)).assertIsDisplayed()
        assertEquals(emptyList<Pair<String, String>>(), renamed)

        input.performTextReplacement(" Cafe\u0301 ")
        composeRule.onNodeWithContentDescription(text(R.string.bookshelf_save_shelf_name)).performClick()
        assertEquals(listOf("Other" to "Café"), renamed)
    }

    @Test
    fun creationRejectsCanonicalDuplicateAndSubmitsCanonicalNewName() {
        val created = mutableListOf<String>()
        setShelfManagementDialogContent(
            shelves = listOf(BookShelf("で", emptyList())),
            onCreateShelf = { created += it },
        )
        composeRule.onNodeWithTag(ShelfManagementShelfListTag).performScrollToNode(hasSetTextAction())
        val input = composeRule.onNode(hasSetTextAction())

        input.performTextReplacement("て\u3099")
        composeRule.onNodeWithContentDescription(text(R.string.bookshelf_add_shelf)).assertIsNotEnabled()
        assertEquals(emptyList<String>(), created)

        input.performTextReplacement(" Cafe\u0301 ")
        composeRule.onNodeWithContentDescription(text(R.string.bookshelf_add_shelf)).performClick()
        assertEquals(listOf("Café"), created)
    }

    @Test
    fun manyShelfRowsAreComposedLazilyAndRemainReachable() {
        val shelves = (1..40).map { index ->
            BookShelf(name = "Shelf $index", bookIds = emptyList())
        }

        composeRule.setContent {
            MaterialTheme {
                Box(
                    modifier = Modifier
                        .requiredSize(width = 360.dp, height = 640.dp),
                ) {
                    ShelfManagementDialog(
                        shelves = shelves,
                        showReading = true,
                        coverMode = BookshelfCoverMode.Show,
                        hideCollapsedShelfThumbnails = false,
                        onHideCollapsedShelfThumbnailsChange = {},
                        onShowReadingChange = {},
                        onCoverModeChange = {},
                        onCreateShelf = {},
                        onDeleteShelf = {},
                        onRenameShelf = { _, _ -> },
                        onMoveShelf = { _, _ -> },
                        onDismiss = {},
                    )
                }
            }
        }

        composeRule.onAllNodesWithText("Shelf 40").assertCountEquals(0)

        composeRule.onNodeWithTag(ShelfManagementShelfListTag)
            .performScrollToNode(hasText("Shelf 40"))

        composeRule.onNodeWithText("Shelf 40").assertIsDisplayed()
    }

    @Test
    fun shelfRenameActionIsShownInMoreMenu() {
        setShelfManagementDialogContent(
            shelves = listOf(BookShelf(name = "Shelf 1", bookIds = emptyList())),
        )

        composeRule.onNodeWithContentDescription(text(R.string.action_more)).performClick()

        composeRule.onNodeWithText(text(R.string.bookshelf_rename_shelf)).assertIsDisplayed()
    }

    @Test
    fun moveShelfActionKeepsMoreMenuOpenForRepeatedMoves() {
        val moveRequests = mutableListOf<Pair<Int, Int>>()
        setShelfManagementDialogContent(
            shelves = listOf(
                BookShelf(name = "Shelf 1", bookIds = emptyList()),
                BookShelf(name = "Shelf 2", bookIds = emptyList()),
            ),
            onMoveShelf = { from, to -> moveRequests += from to to },
        )

        composeRule.onAllNodesWithContentDescription(text(R.string.action_more))[1].performClick()
        composeRule.onNodeWithText(text(R.string.bookshelf_move_shelf_up)).performClick()

        composeRule.onNodeWithText(text(R.string.bookshelf_move_shelf_up)).assertIsDisplayed()
        assertEquals(listOf(1 to 0), moveRequests)
    }

    private fun setShelfManagementDialogContent(
        shelves: List<BookShelf>,
        onMoveShelf: (Int, Int) -> Unit = { _, _ -> },
        onCreateShelf: (String) -> Unit = {},
        onRenameShelf: (String, String) -> Unit = { _, _ -> },
    ) {
        composeRule.setContent {
            MaterialTheme {
                Box(
                    modifier = Modifier
                        .requiredSize(width = 360.dp, height = 640.dp),
                ) {
                    ShelfManagementDialog(
                        shelves = shelves,
                        showReading = true,
                        coverMode = BookshelfCoverMode.Show,
                        hideCollapsedShelfThumbnails = false,
                        onHideCollapsedShelfThumbnailsChange = {},
                        onShowReadingChange = {},
                        onCoverModeChange = {},
                        onCreateShelf = onCreateShelf,
                        onDeleteShelf = {},
                        onRenameShelf = onRenameShelf,
                        onMoveShelf = onMoveShelf,
                        onDismiss = {},
                    )
                }
            }
        }
    }
}
