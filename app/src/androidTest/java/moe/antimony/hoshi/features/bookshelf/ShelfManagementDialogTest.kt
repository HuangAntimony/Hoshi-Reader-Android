package moe.antimony.hoshi.features.bookshelf

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import moe.antimony.hoshi.epub.BookShelf
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ShelfManagementDialogTest {
    @get:Rule
    val composeRule = createComposeRule()

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
                        onShowReadingChange = {},
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

        composeRule.onNodeWithContentDescription("More").performClick()

        composeRule.onNodeWithText("Rename shelf").assertIsDisplayed()
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

        composeRule.onAllNodesWithContentDescription("More")[1].performClick()
        composeRule.onNodeWithText("Move shelf up").performClick()

        composeRule.onNodeWithText("Move shelf up").assertIsDisplayed()
        assertEquals(listOf(1 to 0), moveRequests)
    }

    private fun setShelfManagementDialogContent(
        shelves: List<BookShelf>,
        onMoveShelf: (Int, Int) -> Unit = { _, _ -> },
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
                        onShowReadingChange = {},
                        onCreateShelf = {},
                        onDeleteShelf = {},
                        onRenameShelf = { _, _ -> },
                        onMoveShelf = onMoveShelf,
                        onDismiss = {},
                    )
                }
            }
        }
    }
}
