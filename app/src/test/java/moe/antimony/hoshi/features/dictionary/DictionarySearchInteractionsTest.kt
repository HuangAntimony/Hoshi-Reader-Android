package moe.antimony.hoshi.features.dictionary

import de.manhhao.hoshi.FrequencyEntry
import de.manhhao.hoshi.GlossaryEntry
import de.manhhao.hoshi.LookupResult
import de.manhhao.hoshi.PitchEntry
import de.manhhao.hoshi.TermResult
import moe.antimony.hoshi.features.reader.ReaderLookupPopupFramePayload
import moe.antimony.hoshi.features.reader.ReaderLookupPopupFrameRect
import moe.antimony.hoshi.features.reader.ReaderSelectionData
import moe.antimony.hoshi.features.reader.ReaderSelectionRect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DictionarySearchInteractionsTest {
    @Test
    fun pullResetUsesLargerAndroidDragThreshold() {
        assertEquals(160, DictionaryPullResetTriggerDistanceDp)
        assertEquals(
            DictionaryPullResetAction.None,
            dictionaryPullResetAction(
                distancePx = 159f,
                thresholdPx = DictionaryPullResetTriggerDistanceDp.toFloat(),
                hasQuery = true,
            ),
        )
        assertEquals(
            DictionaryPullResetAction.ResetAndFocus,
            dictionaryPullResetAction(
                distancePx = 160f,
                thresholdPx = DictionaryPullResetTriggerDistanceDp.toFloat(),
                hasQuery = true,
            ),
        )
        assertEquals(
            DictionaryPullResetAction.FocusOnly,
            dictionaryPullResetAction(
                distancePx = 160f,
                thresholdPx = DictionaryPullResetTriggerDistanceDp.toFloat(),
                hasQuery = false,
            ),
        )
    }

    @Test
    fun dismissingFirstIframePopupClearsRootSelection() {
        val popups = listOf(popup("root-child"), popup("nested-child"))

        val result = dictionarySearchIframePopupsAfterSwipeDismiss(
            popups = popups,
            popupId = "root-child",
        )

        assertEquals(emptyList<LookupPopupItem>(), result.popups)
        assertTrue(result.clearRootSelection)
    }

    @Test
    fun dismissingNestedIframePopupClearsParentSelectionOnly() {
        val popups = listOf(popup("root-child"), popup("nested-child"))

        val result = dictionarySearchIframePopupsAfterSwipeDismiss(
            popups = popups,
            popupId = "nested-child",
        )

        assertEquals(listOf("root-child"), result.popups.map { it.id })
        assertEquals(1, result.popups.single().clearSelectionSignal)
        assertFalse(result.clearRootSelection)
    }

    @Test
    fun pullResetGestureCanStartOnlyFromUncoveredRootIframeArea() {
        val root = framePayload(
            id = DictionarySearchRootPopupId,
            left = 0.0,
            top = 100.0,
            width = 390.0,
            height = 600.0,
        )
        val child = framePayload(
            id = "child",
            left = 70.0,
            top = 160.0,
            width = 250.0,
            height = 240.0,
        )

        assertTrue(dictionarySearchPullGestureCanStart(listOf(root), x = 20.0, y = 140.0))
        assertFalse(dictionarySearchPullGestureCanStart(listOf(root), x = 20.0, y = 80.0))
        assertFalse(dictionarySearchPullGestureCanStart(listOf(root, child), x = 100.0, y = 180.0))
        assertTrue(dictionarySearchPullGestureCanStart(listOf(root, child), x = 20.0, y = 180.0))
        assertFalse(dictionarySearchPullGestureCanStart(emptyList(), x = 20.0, y = 140.0))
    }

    @Test
    fun historySwipeGestureCanStartOnlyFromUncoveredRootIframeArea() {
        val root = framePayload(
            id = DictionarySearchRootPopupId,
            left = 0.0,
            top = 100.0,
            width = 390.0,
            height = 600.0,
        )
        val child = framePayload(
            id = "child",
            left = 70.0,
            top = 160.0,
            width = 250.0,
            height = 240.0,
        )

        assertTrue(dictionarySearchHistorySwipeGestureCanStart(listOf(root), x = 20.0, y = 140.0))
        assertFalse(dictionarySearchHistorySwipeGestureCanStart(listOf(root), x = 20.0, y = 80.0))
        assertFalse(dictionarySearchHistorySwipeGestureCanStart(listOf(root, child), x = 100.0, y = 180.0))
        assertTrue(dictionarySearchHistorySwipeGestureCanStart(listOf(root, child), x = 20.0, y = 180.0))
        assertFalse(dictionarySearchHistorySwipeGestureCanStart(emptyList(), x = 20.0, y = 140.0))
    }

    private fun popup(id: String): LookupPopupItem = LookupPopupItem(
        id = id,
        state = LookupPopupState(
            selection = ReaderSelectionData(
                text = "食べる",
                sentence = "食べる",
                rect = ReaderSelectionRect(x = 0.0, y = 0.0, width = 10.0, height = 12.0),
                normalizedOffset = 0,
            ),
            results = listOf(lookupResult()),
        ),
    )

    private fun framePayload(
        id: String,
        left: Double,
        top: Double,
        width: Double,
        height: Double,
    ): ReaderLookupPopupFramePayload = ReaderLookupPopupFramePayload(
        id = id,
        frame = ReaderLookupPopupFrameRect(
            left = left,
            top = top,
            width = width,
            height = height,
        ),
        entriesCount = 1,
        initialEntryJson = null,
        popupActionBar = false,
        actionBarVisible = false,
        backCount = 0,
        forwardCount = 0,
        sasayakiVisible = false,
        sasayakiWasPaused = false,
        sasayakiIsPlaying = false,
        darkMode = false,
        eInkMode = false,
        clearSelectionSignal = 0,
        selectionOffsetY = top,
        iframeUrl = "https://appassets.androidplatform.net/popup/iframe.html",
    )

    private fun lookupResult(): LookupResult = LookupResult(
        matched = "食べる",
        deinflected = "食べる",
        process = emptyArray(),
        term = TermResult(
            expression = "食べる",
            reading = "たべる",
            rules = "",
            glossaries = arrayOf(
                GlossaryEntry(
                    dictName = "JMdict",
                    glossary = "eat",
                    definitionTags = "",
                    termTags = "",
                ),
            ),
            frequencies = emptyArray<FrequencyEntry>(),
            pitches = emptyArray<PitchEntry>(),
        ),
        preprocessorSteps = 0,
    )
}
