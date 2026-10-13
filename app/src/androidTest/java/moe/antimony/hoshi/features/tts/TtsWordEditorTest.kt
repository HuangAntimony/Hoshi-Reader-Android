package moe.antimony.hoshi.features.tts

import android.content.Context
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.CoroutineDispatcher
import kotlin.coroutines.CoroutineContext
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import moe.antimony.hoshi.R
import moe.antimony.hoshi.ui.theme.HoshiReaderTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** In-memory drafts only: no saved reader data or online synthesis. */
@RunWith(AndroidJUnit4::class)
class TtsWordEditorTest {
    @get:Rule val compose = createComposeRule()
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private fun text(id: Int) = context.getString(id)

    private class QueuedPreviewDispatcher : CoroutineDispatcher() {
        private val tasks = LinkedBlockingQueue<Runnable>()
        override fun dispatch(context: CoroutineContext, block: Runnable) { tasks.add(block) }
        fun completeNext() { requireNotNull(tasks.poll(5, TimeUnit.SECONDS)).run() }
    }

    @Test fun openingWaitsForInitialValidationInsteadOfResizingAnEmptyPreview() {
        val dispatcher = QueuedPreviewDispatcher()
        compose.setContent {
            HoshiReaderTheme(darkTheme = false, dynamicColor = false) {
                TtsWordEditorDialog(TtsWord("draft", "々", ""), "book", TtsSentence(0, 0, 1, "瑞々しい。"),
                    TtsPreferences(), false, null, {}, { null }, {}, {}, previewDispatcher = dispatcher)
            }
        }
        compose.onNode(isDialog()).assertDoesNotExist()
        dispatcher.completeNext()
        compose.onNode(isDialog()).assertExists()
        compose.onNodeWithText("瑞しい。").assertExists()
        compose.onNodeWithText(text(R.string.tts_replacement_delete_hint)).assertIsDisplayed()
        val bounds = compose.onNode(isDialog()).fetchSemanticsNode().boundsInRoot
        compose.mainClock.advanceTimeBy(100)
        assertEquals(bounds, compose.onNode(isDialog()).fetchSemanticsNode().boundsInRoot)
    }

    @Test fun editingKeepsPreviewLayoutButCannotPlayTheObsoleteDraft() {
        val dispatcher = QueuedPreviewDispatcher()
        var played: TtsWordPreview? = null
        compose.setContent {
            HoshiReaderTheme(darkTheme = false, dynamicColor = false) {
                TtsWordEditorDialog(TtsWord("draft", "東京", "とうきょう"), "book", TtsSentence(0, 0, 2, "東京へ。"),
                    TtsPreferences(), false, null, { played = it }, { null }, {}, {}, previewDispatcher = dispatcher)
            }
        }
        dispatcher.completeNext()
        compose.onNodeWithText("とうきょうへ。").assertExists()
        val bounds = compose.onNode(isDialog()).fetchSemanticsNode().boundsInRoot
        compose.onNodeWithText(text(R.string.tts_replacement)).performScrollTo().performTextReplacement("トーキョー")
        compose.onNode(isDialog()).assertExists()
        compose.onNodeWithText("とうきょうへ。").assertExists()
        assertEquals(bounds, compose.onNode(isDialog()).fetchSemanticsNode().boundsInRoot)
        compose.onNodeWithText(text(R.string.tts_preview)).performScrollTo().assertIsEnabled().performClick()
        assertNull(played)
        dispatcher.completeNext()
        compose.onNodeWithText("トーキョーへ。").assertExists()
        compose.onNodeWithText("とうきょうへ。").assertDoesNotExist()
        compose.runOnIdle { assertEquals("トーキョーへ。", played?.text) }
    }

    @Test fun scopeChangeKeepsActionsAvailableAndUsesOnlyTheCurrentDraft() {
        val dispatcher = QueuedPreviewDispatcher()
        var played: TtsWordPreview? = null
        var saved: TtsWord? = null
        compose.setContent {
            HoshiReaderTheme(darkTheme = false, dynamicColor = false) {
                TtsWordEditorDialog(TtsWord("draft", "東京", "とうきょう"), "book", TtsSentence(0, 0, 2, "東京へ。"),
                    TtsPreferences(), false, null, { played = it }, { saved = it; null }, {}, {}, previewDispatcher = dispatcher)
            }
        }
        dispatcher.completeNext()
        compose.onNodeWithText(text(R.string.tts_book)).performScrollTo().performClick()
        compose.onNodeWithText(text(R.string.tts_preview)).performScrollTo().assertIsEnabled().performClick()
        compose.onNodeWithText(text(R.string.action_save)).assertIsEnabled().performClick()
        compose.runOnIdle { assertNull(played); assertEquals("book", saved?.bookId) }
        dispatcher.completeNext()
        compose.runOnIdle { assertEquals("book", played?.word?.bookId) }
    }

    @Test fun editingAgainCancelsAQueuedPreview() {
        val dispatcher = QueuedPreviewDispatcher()
        var plays = 0
        compose.setContent {
            HoshiReaderTheme(darkTheme = false, dynamicColor = false) {
                TtsWordEditorDialog(TtsWord("draft", "東京", "とうきょう"), "book", TtsSentence(0, 0, 2, "東京へ。"),
                    TtsPreferences(), false, null, { plays++ }, { null }, {}, {}, previewDispatcher = dispatcher)
            }
        }
        dispatcher.completeNext()
        compose.onNodeWithText(text(R.string.tts_book)).performScrollTo().performClick()
        compose.onNodeWithText(text(R.string.tts_preview)).performScrollTo().assertIsEnabled().performClick()
        compose.onNodeWithText(text(R.string.tts_replacement)).performScrollTo().performTextReplacement("トーキョー")
        dispatcher.completeNext()
        compose.waitForIdle()
        dispatcher.completeNext()
        compose.waitUntil(5000) { compose.onAllNodesWithText("トーキョーへ。").fetchSemanticsNodes().isNotEmpty() }
        compose.runOnIdle { assertEquals(0, plays) }
    }

    @Test fun deletingWholeSentenceCanBeSavedButCannotBePreviewed() {
        var saved: TtsWord? = null
        val draft = TtsWord("existing", "東京。", "とうきょう。")
        compose.setContent {
            HoshiReaderTheme(darkTheme = false, dynamicColor = false) {
                TtsWordEditorDialog(draft, "book", TtsSentence(0, 0, 2, "東京。"),
                    TtsPreferences(words = listOf(draft)), false, null, { fail("Empty preview must not play") },
                    { saved = it; validateTtsWord(it, listOf(draft)) }, {}, {})
            }
        }
        compose.onNodeWithText(text(R.string.tts_replacement)).performScrollTo().performTextClearance()
        compose.waitUntil(5000) { compose.onAllNodesWithText(text(R.string.tts_replacement_empty_preview)).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText(text(R.string.tts_preview)).performScrollTo().assertIsNotEnabled()
        val delete = compose.onNodeWithText(text(R.string.action_delete)).fetchSemanticsNode().boundsInRoot
        val save = compose.onNodeWithText(text(R.string.action_save)).fetchSemanticsNode().boundsInRoot
        assertEquals(delete.center.y, save.center.y, 1f)
        compose.onNodeWithText(text(R.string.action_save)).assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals("", saved?.replacement) }
    }

    private fun verify(eink: Boolean) {
        var played: TtsWordPreview? = null
        var saved: TtsWord? = null
        val draft = TtsWord("draft", "(時)々", "\$1\$1")
        compose.setContent {
            HoshiReaderTheme(darkTheme = false, eInkMode = eink, dynamicColor = false) {
                TtsWordEditorDialog(draft, "book", TtsSentence(0, 0, 4, "時々東京"),
                    TtsPreferences(words = listOf(TtsWord("tokyo", "東京", "とうきょう"))), false, null,
                    { played = it }, { saved = it; null }, {}, {})
            }
        }
        compose.onNodeWithText(text(R.string.tts_replacement_before)).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("時々東京").assertExists()
        compose.waitUntil(5000) { compose.onAllNodesWithText("時々とうきょう").fetchSemanticsNodes().isNotEmpty() }
        compose.onAllNodes(isToggleable()).onFirst().performScrollTo().assertIsOff().performClick()
        compose.waitUntil(5000) { compose.onAllNodesWithText("時時とうきょう").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText(text(R.string.tts_preview)).performScrollTo().performClick()
        compose.runOnIdle { assertEquals("時時とうきょう", played?.text); assertTrue(played!!.word.regex) }
        compose.onNodeWithText(text(R.string.action_save)).performClick()
        compose.runOnIdle { assertTrue(saved!!.regex); assertNull(saved!!.bookId) }
        compose.onNodeWithText(text(R.string.tts_regex_pattern)).performScrollTo().performTextReplacement("[")
        compose.waitUntil(5000) { compose.onAllNodesWithText(text(R.string.tts_invalid_regex)).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText(text(R.string.tts_preview)).performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText("時時とうきょう").assertDoesNotExist()
    }
    @Test fun ordinaryAndRegexPreviewShowActualTextAndSaveTheMode() = verify(false)
    @Test fun regexEditorSupportsEinkWithoutChangingScopeLabels() = verify(true)
    @Test fun editingRegexWithoutBookContextUsesEditableSampleAndCanReturnToLiteral() {
        var played: TtsWordPreview? = null
        var saved: TtsWord? = null
        val draft = TtsWord("existing", "(時)々", "\$1\$1", regex = true)
        compose.setContent {
            HoshiReaderTheme(darkTheme = false, dynamicColor = false) {
                TtsWordEditorDialog(draft, null, null, TtsPreferences(words = listOf(draft)), false, null,
                    { played = it }, { saved = it; null }, {}, {})
            }
        }
        compose.onNodeWithText(text(R.string.action_delete)).assertExists()
        compose.onAllNodes(isToggleable()).onFirst().assertIsOn()
        compose.onNodeWithText(text(R.string.tts_replacement_sample)).performScrollTo().performTextReplacement("時々")
        compose.waitUntil(5000) { compose.onAllNodesWithText("時時").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText(text(R.string.tts_preview)).performScrollTo().performClick()
        compose.runOnIdle { assertEquals("時々", played?.sentence?.text); assertEquals("時時", played?.text) }
        compose.onAllNodes(isToggleable()).onFirst().performScrollTo().performClick()
        compose.onNodeWithText(text(R.string.action_save)).performClick()
        compose.runOnIdle { assertFalse(saved!!.regex); assertEquals("\$1\$1", saved!!.replacement) }
    }

}
