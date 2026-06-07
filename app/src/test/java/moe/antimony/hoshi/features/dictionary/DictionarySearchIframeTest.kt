package moe.antimony.hoshi.features.dictionary

import de.manhhao.hoshi.FrequencyEntry
import de.manhhao.hoshi.GlossaryEntry
import de.manhhao.hoshi.LookupResult
import de.manhhao.hoshi.PitchEntry
import de.manhhao.hoshi.TermResult
import moe.antimony.hoshi.features.reader.ReaderLookupPopupViewport
import moe.antimony.hoshi.features.reader.ReaderPopupHistoryCounts
import moe.antimony.hoshi.features.reader.ReaderSelectionData
import moe.antimony.hoshi.features.reader.ReaderSelectionRect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Test

class DictionarySearchIframeTest {
    @Test
    fun rootPayloadFillsAreaBelowMeasuredSearchBarWithoutActionBar() {
        val payload = dictionarySearchRootFramePayload(
            results = listOf(lookupResult("猫"), lookupResult("犬")),
            viewport = ReaderLookupPopupViewport(width = 390.0, height = 700.0),
            searchBarBottomDp = 86.0,
            darkMode = true,
            eInkMode = false,
            iframeUrl = "https://appassets.androidplatform.net/popup/iframe.html?v=5",
        )

        assertEquals(DictionarySearchRootPopupId, payload.id)
        assertEquals(0.0, payload.frame.left, 0.0)
        assertEquals(86.0, payload.frame.top, 0.0)
        assertEquals(390.0, payload.frame.width, 0.0)
        assertEquals(614.0, payload.frame.height, 0.0)
        assertEquals(2, payload.entriesCount)
        assertEquals(LookupPopupHtml.entryJsonString(lookupResult("猫")), payload.initialEntryJson)
        assertFalse(payload.popupActionBar)
        assertFalse(payload.actionBarVisible)
        assertEquals(86.0, payload.selectionOffsetY, 0.0)
        assertEquals("https://appassets.androidplatform.net/popup/iframe.html?v=5", payload.iframeUrl)
    }

    @Test
    fun payloadsAppendChildPopupsAfterRootSearchResults() {
        val child = LookupPopupItem(
            id = "child",
            state = LookupPopupState(
                selection = ReaderSelectionData(
                    text = "犬",
                    sentence = "犬",
                    rect = ReaderSelectionRect(x = 120.0, y = 160.0, width = 16.0, height = 18.0),
                    normalizedOffset = null,
                ),
                results = listOf(lookupResult("犬")),
                width = 300,
                height = 220,
                topInset = 86.0,
            ),
        )

        val payloads = dictionarySearchIframePayloads(
            rootResults = listOf(lookupResult("猫")),
            childPopups = listOf(child),
            childHistories = mapOf("child" to ReaderPopupHistoryCounts(backCount = 1, forwardCount = 2)),
            viewport = ReaderLookupPopupViewport(width = 390.0, height = 700.0),
            searchBarBottomDp = 86.0,
            darkMode = false,
            eInkMode = false,
            iframeUrl = "https://appassets.androidplatform.net/popup/iframe.html",
        )

        assertEquals(listOf(DictionarySearchRootPopupId, "child"), payloads.map { it.id })
        assertEquals(1, payloads[1].backCount)
        assertEquals(2, payloads[1].forwardCount)
    }

    @Test
    fun payloadsPassRootHistoryCountsToRootSearchResults() {
        val payloads = dictionarySearchIframePayloads(
            rootResults = listOf(lookupResult("猫")),
            childPopups = emptyList(),
            childHistories = emptyMap(),
            rootHistory = ReaderPopupHistoryCounts(backCount = 2, forwardCount = 1),
            viewport = ReaderLookupPopupViewport(width = 390.0, height = 700.0),
            searchBarBottomDp = 86.0,
            darkMode = false,
            eInkMode = false,
            iframeUrl = "https://appassets.androidplatform.net/popup/iframe.html",
        )

        assertEquals(2, payloads.single().backCount)
        assertEquals(1, payloads.single().forwardCount)
    }

    @Test
    fun rootPayloadContentKeyCoversEntriesBeyondTheFirstResult() {
        val firstResults = listOf(lookupResult("猫"), lookupResult("犬"))
        val secondResults = listOf(lookupResult("猫"), lookupResult("鳥"))

        val firstPayload = dictionarySearchRootFramePayload(
            results = firstResults,
            viewport = ReaderLookupPopupViewport(width = 390.0, height = 700.0),
            searchBarBottomDp = 86.0,
            darkMode = false,
            eInkMode = false,
            iframeUrl = "https://appassets.androidplatform.net/popup/iframe.html",
        )
        val secondPayload = dictionarySearchRootFramePayload(
            results = secondResults,
            viewport = ReaderLookupPopupViewport(width = 390.0, height = 700.0),
            searchBarBottomDp = 86.0,
            darkMode = false,
            eInkMode = false,
            iframeUrl = "https://appassets.androidplatform.net/popup/iframe.html",
        )

        assertNotEquals(firstPayload.contentKey, secondPayload.contentKey)
    }

    private fun lookupResult(matched: String): LookupResult = LookupResult(
        matched = matched,
        deinflected = matched,
        process = emptyArray(),
        term = TermResult(
            expression = matched,
            reading = matched,
            rules = "",
            glossaries = arrayOf(
                GlossaryEntry(
                    dictName = "JMdict",
                    glossary = "glossary",
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
