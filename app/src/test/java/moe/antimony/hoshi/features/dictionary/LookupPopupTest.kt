package moe.antimony.hoshi.features.dictionary

import de.manhhao.hoshi.GlossaryEntry
import de.manhhao.hoshi.LookupResult
import de.manhhao.hoshi.TermResult
import moe.antimony.hoshi.features.reader.ReaderSelectionData
import moe.antimony.hoshi.features.reader.ReaderSelectionRect
import moe.antimony.hoshi.features.reader.ReaderLookupPopupFramePayload
import moe.antimony.hoshi.features.reader.ReaderLookupPopupRootHighlightPayload
import moe.antimony.hoshi.features.reader.ReaderLookupPopupStackPayload
import moe.antimony.hoshi.features.reader.ReaderLookupPopupViewport
import moe.antimony.hoshi.features.reader.readerLookupPopupIframeUrl
import moe.antimony.hoshi.features.reader.readerLookupPopupTouchBlocksReaderGesture
import moe.antimony.hoshi.features.audio.AudioSettings
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class LookupPopupTest {
    @Test
    fun verticalLayoutChoosesLargerSideLikeIosPopupLayout() {
        val layout = LookupPopupLayout(
            selectionRect = ReaderSelectionRect(x = 100.0, y = 200.0, width = 20.0, height = 30.0),
            screenWidth = 400.0,
            screenHeight = 800.0,
            maxWidth = 320.0,
            maxHeight = 250.0,
            isVertical = true,
        )

        val result = layout.calculate()

        assertEquals(270.0, result.width, 0.0)
        assertEquals(250.0, result.height, 0.0)
        assertEquals(259.0, result.centerX, 0.0)
        assertEquals(325.0, result.centerY, 0.0)
    }

    @Test
    fun verticalLayoutPrefersRightSideWhenItCanFitPopupLikeIosPopupLayout() {
        val layout = LookupPopupLayout(
            selectionRect = ReaderSelectionRect(x = 450.0, y = 200.0, width = 20.0, height = 30.0),
            screenWidth = 800.0,
            screenHeight = 800.0,
            maxWidth = 320.0,
            maxHeight = 250.0,
            isVertical = true,
        )

        val result = layout.calculate()

        assertEquals(320.0, result.width, 0.0)
        assertEquals(634.0, result.centerX, 0.0)
    }

    @Test
    fun horizontalLayoutAppearsBelowSelectionWhenThereIsRoom() {
        val layout = LookupPopupLayout(
            selectionRect = ReaderSelectionRect(x = 100.0, y = 100.0, width = 20.0, height = 30.0),
            screenWidth = 400.0,
            screenHeight = 800.0,
            maxWidth = 320.0,
            maxHeight = 250.0,
            isVertical = false,
        )

        val result = layout.calculate()

        assertEquals(320.0, result.width, 0.0)
        assertEquals(250.0, result.height, 0.0)
        assertEquals(234.0, result.centerX, 0.0)
        assertEquals(259.0, result.centerY, 0.0)
    }

    @Test
    fun fullWidthLayoutMatchesIosPopupLayout() {
        val layout = LookupPopupLayout(
            selectionRect = ReaderSelectionRect(x = 100.0, y = 100.0, width = 20.0, height = 30.0),
            screenWidth = 400.0,
            screenHeight = 800.0,
            maxWidth = 320.0,
            maxHeight = 250.0,
            isVertical = true,
            isFullWidth = true,
        )

        val result = layout.calculate()

        assertEquals(388.0, result.width, 0.0)
        assertEquals(250.0, result.height, 0.0)
        assertEquals(200.0, result.centerX, 0.0)
        assertEquals(669.0, result.centerY, 0.0)
    }

    @Test
    fun verticalLayoutUsesIosClampWhenPopupIsTallerThanAvailableHeight() {
        val layout = LookupPopupLayout(
            selectionRect = ReaderSelectionRect(x = 100.0, y = 0.0, width = 20.0, height = 30.0),
            screenWidth = 400.0,
            screenHeight = 244.62222290039062,
            maxWidth = 320.0,
            maxHeight = 250.0,
            isVertical = true,
        )

        val result = layout.calculate()

        assertEquals(131.0, result.centerY, 0.0)
    }

    @Test
    fun rootSelectionOffsetMovesOnlyRootPopupAnchor() {
        val popups = listOf("root", "child").mapIndexed { index, id ->
            LookupPopupItem(
                id = id,
                state = LookupPopupState(
                    selection = ReaderSelectionData(
                        text = id,
                        sentence = id,
                        rect = ReaderSelectionRect(
                            x = 10.0 + index,
                            y = 20.0 + index,
                            width = 30.0,
                            height = 40.0,
                        ),
                        normalizedOffset = null,
                    ),
                    results = emptyList(),
                ),
            )
        }

        val shifted = popups.withRootSelectionOffset(offsetX = 5.0, offsetY = 7.0)

        assertEquals(15.0, shifted[0].state.selection.rect.x, 0.0)
        assertEquals(27.0, shifted[0].state.selection.rect.y, 0.0)
        assertEquals(11.0, shifted[1].state.selection.rect.x, 0.0)
        assertEquals(21.0, shifted[1].state.selection.rect.y, 0.0)
    }

    @Test
    fun readerIframeFramePayloadUsesWebViewViewportCoordinatesWithoutRootPaddingOffset() {
        val popup = LookupPopupItem(
            id = "root",
            state = LookupPopupState(
                selection = ReaderSelectionData(
                    text = "root",
                    sentence = "root",
                    rect = ReaderSelectionRect(x = 100.0, y = 100.0, width = 20.0, height = 30.0),
                    normalizedOffset = null,
                ),
                results = emptyList(),
                isVertical = false,
                width = 320,
                height = 250,
                popupActionBar = true,
            ),
        )

        val payload = ReaderLookupPopupFramePayload.fromPopup(
            popup = popup,
            popupIndex = 0,
            viewport = ReaderLookupPopupViewport(
                width = 500.0,
                height = 800.0,
            ),
            entriesCount = 3,
            backCount = 1,
            forwardCount = 2,
        )

        assertEquals("root", payload.id)
        assertEquals(100.0, payload.frame.left, 0.0)
        assertEquals(134.0, payload.frame.top, 0.0)
        assertEquals(320.0, payload.frame.width, 0.0)
        assertEquals(250.0, payload.frame.height, 0.0)
        assertEquals(171.0, payload.selectionOffsetY, 0.0)
        assertTrue(payload.popupActionBar)
        assertEquals(3, payload.entriesCount)
        assertEquals("https://appassets.androidplatform.net/popup/iframe.html", payload.iframeUrl)
        assertEquals("https://appassets.androidplatform.net/popup/iframe.html?v=123", readerLookupPopupIframeUrl(123))
    }

    @Test
    fun readerIframeFramePayloadSeedsFirstEntryForInitialPaint() {
        val popup = LookupPopupItem(
            id = "root",
            state = LookupPopupState(
                selection = ReaderSelectionData(
                    text = "root",
                    sentence = "root",
                    rect = ReaderSelectionRect(x = 100.0, y = 100.0, width = 20.0, height = 30.0),
                    normalizedOffset = null,
                ),
                results = listOf(
                    lookupResult(expression = "食べる", reading = "たべる", glossary = "to eat"),
                    lookupResult(expression = "読む", reading = "よむ", glossary = "to read"),
                ),
                isVertical = false,
                width = 320,
                height = 250,
            ),
        )

        val payload = ReaderLookupPopupFramePayload.fromPopup(
            popup = popup,
            popupIndex = 0,
            viewport = ReaderLookupPopupViewport(width = 500.0, height = 800.0),
        )

        assertTrue(payload.initialEntryJson?.contains(""""expression":"食べる"""") == true)
        assertFalse(payload.initialEntryJson?.contains(""""expression":"読む"""") == true)
    }

    @Test
    fun readerIframeFramePayloadCanOmitInitialEntryForFrameOnlyUpdates() {
        val popup = LookupPopupItem(
            id = "root",
            state = LookupPopupState(
                selection = ReaderSelectionData(
                    text = "root",
                    sentence = "root",
                    rect = ReaderSelectionRect(x = 100.0, y = 100.0, width = 20.0, height = 30.0),
                    normalizedOffset = null,
                ),
                results = listOf(
                    lookupResult(expression = "食べる", reading = "たべる", glossary = "to eat"),
                ),
                isVertical = false,
                width = 320,
                height = 250,
            ),
        )

        val payload = ReaderLookupPopupFramePayload.fromPopup(
            popup = popup,
            popupIndex = 0,
            viewport = ReaderLookupPopupViewport(width = 500.0, height = 800.0),
            includeInitialEntryJson = false,
        )

        assertEquals(1, payload.entriesCount)
        assertEquals(null, payload.initialEntryJson)
    }

    @Test
    fun readerIframeStackPayloadCarriesPendingRootHighlightGate() {
        val payload = ReaderLookupPopupStackPayload(
            popups = emptyList(),
            rootHighlight = ReaderLookupPopupRootHighlightPayload.fromReaderRects(
                popupId = "root",
                rects = null,
                darkMode = false,
                eInkMode = true,
                verticalWriting = true,
            ),
        )

        val rootHighlight = Json.parseToJsonElement(payload.toJson())
            .jsonObject
            .getValue("rootHighlight")
            .jsonObject

        assertEquals("root", rootHighlight.getValue("popupId").jsonPrimitive.content)
        assertTrue(rootHighlight.getValue("pending").jsonPrimitive.boolean)
        assertTrue(rootHighlight.getValue("eInkMode").jsonPrimitive.boolean)
        assertTrue(rootHighlight.getValue("verticalWriting").jsonPrimitive.boolean)
        assertEquals(0, rootHighlight.getValue("rects").jsonArray.size)
    }

    @Test
    fun readerIframeStackPayloadCarriesReadyRootHighlightRects() {
        val payload = ReaderLookupPopupStackPayload(
            popups = emptyList(),
            rootHighlight = ReaderLookupPopupRootHighlightPayload.fromReaderRects(
                popupId = "root",
                rects = listOf(
                    ReaderSelectionRect(x = 12.0, y = 24.0, width = 30.0, height = 16.0),
                ),
                darkMode = true,
                eInkMode = false,
                verticalWriting = false,
            ),
        )

        val rootHighlight = Json.parseToJsonElement(payload.toJson())
            .jsonObject
            .getValue("rootHighlight")
            .jsonObject
        val rect = rootHighlight.getValue("rects").jsonArray.first().jsonObject

        assertFalse(rootHighlight.getValue("pending").jsonPrimitive.boolean)
        assertTrue(rootHighlight.getValue("darkMode").jsonPrimitive.boolean)
        assertEquals(12.0, rect.getValue("x").jsonPrimitive.double, 0.0)
        assertEquals(24.0, rect.getValue("y").jsonPrimitive.double, 0.0)
        assertEquals(30.0, rect.getValue("width").jsonPrimitive.double, 0.0)
        assertEquals(16.0, rect.getValue("height").jsonPrimitive.double, 0.0)
    }

    @Test
    fun readerIframePopupFramesBlockReaderGesturesOnlyInsidePopupBounds() {
        val popup = LookupPopupItem(
            id = "root",
            state = LookupPopupState(
                selection = ReaderSelectionData(
                    text = "root",
                    sentence = "root",
                    rect = ReaderSelectionRect(x = 100.0, y = 100.0, width = 20.0, height = 30.0),
                    normalizedOffset = null,
                ),
                results = emptyList(),
                isVertical = false,
                width = 320,
                height = 250,
            ),
        )
        val payload = ReaderLookupPopupFramePayload.fromPopup(
            popup = popup,
            popupIndex = 0,
            viewport = ReaderLookupPopupViewport(width = 500.0, height = 800.0),
        )

        assertTrue(readerLookupPopupTouchBlocksReaderGesture(listOf(payload), x = 130.0, y = 150.0))
        assertFalse(readerLookupPopupTouchBlocksReaderGesture(listOf(payload), x = 40.0, y = 150.0))
        assertFalse(readerLookupPopupTouchBlocksReaderGesture(emptyList(), x = 130.0, y = 150.0))
    }

    @Test
    fun dismissPopupAtClosesTheSelectedPopupAndItsChildren() {
        val popups = listOf("root", "child", "grandchild").map { id ->
            LookupPopupItem(
                id = id,
                state = LookupPopupState(
                    selection = ReaderSelectionData(
                        text = id,
                        sentence = id,
                        rect = ReaderSelectionRect(x = 0.0, y = 0.0, width = 1.0, height = 1.0),
                        normalizedOffset = null,
                    ),
                    results = emptyList(),
                ),
            )
        }

        assertEquals(listOf("root"), dismissPopupAt(popups, 1).map { it.id })
        assertEquals(emptyList<String>(), dismissPopupAt(popups, 0).map { it.id })
    }

    @Test
    fun dismissingChildPopupSignalsParentSelectionClearLikeIos() {
        val popups = listOf("root", "child", "grandchild").map { id ->
            LookupPopupItem(
                id = id,
                state = LookupPopupState(
                    selection = ReaderSelectionData(
                        text = id,
                        sentence = id,
                        rect = ReaderSelectionRect(x = 0.0, y = 0.0, width = 1.0, height = 1.0),
                        normalizedOffset = null,
                    ),
                    results = emptyList(),
                ),
            )
        }

        val afterDismissingChild = dismissPopupAt(popups, 1)
        val afterDismissingGrandchild = dismissPopupAt(popups, 2)

        assertEquals(1, afterDismissingChild.single { it.id == "root" }.clearSelectionSignal)
        assertEquals(1, afterDismissingGrandchild.single { it.id == "child" }.clearSelectionSignal)
    }

    @Test
    fun tappingOutsidePopupContentClosesChildrenAndClearsCurrentSelection() {
        val popups = listOf("root", "child", "grandchild").map { id ->
            LookupPopupItem(
                id = id,
                state = LookupPopupState(
                    selection = ReaderSelectionData(
                        text = id,
                        sentence = id,
                        rect = ReaderSelectionRect(x = 0.0, y = 0.0, width = 1.0, height = 1.0),
                        normalizedOffset = null,
                    ),
                    results = emptyList(),
                ),
            )
        }

        val afterRootTapOutside = closeChildPopupsAndClearSelection(popups, 0)
        val afterChildTapOutside = closeChildPopupsAndClearSelection(popups, 1)

        assertEquals(listOf("root"), afterRootTapOutside.map { it.id })
        assertEquals(1, afterRootTapOutside.single().clearSelectionSignal)
        assertEquals(listOf("root", "child"), afterChildTapOutside.map { it.id })
        assertEquals(0, afterChildTapOutside.single { it.id == "root" }.clearSelectionSignal)
        assertEquals(1, afterChildTapOutside.single { it.id == "child" }.clearSelectionSignal)
    }

    @Test
    fun scrollingRootOnlyPopupDoesNotRewritePopupState() {
        val popups = listOf("root").map { id ->
            LookupPopupItem(
                id = id,
                state = LookupPopupState(
                    selection = ReaderSelectionData(
                        text = id,
                        sentence = id,
                        rect = ReaderSelectionRect(x = 0.0, y = 0.0, width = 1.0, height = 1.0),
                        normalizedOffset = null,
                    ),
                    results = emptyList(),
                ),
            )
        }

        assertTrue(closeChildPopupsForScrolledParent(popups, 0) === popups)
    }

    @Test
    fun scrollingParentPopupClosesChildrenAndClearsSelection() {
        val popups = listOf("root", "child").map { id ->
            LookupPopupItem(
                id = id,
                state = LookupPopupState(
                    selection = ReaderSelectionData(
                        text = id,
                        sentence = id,
                        rect = ReaderSelectionRect(x = 0.0, y = 0.0, width = 1.0, height = 1.0),
                        normalizedOffset = null,
                    ),
                    results = emptyList(),
                ),
            )
        }

        val scrolled = closeChildPopupsForScrolledParent(popups, 0)

        assertEquals(listOf("root"), scrolled.map { it.id })
        assertEquals(1, scrolled.single().clearSelectionSignal)
    }

    @Test
    fun existingPopupsRetainSelectionAndHistorySignalsWhenThemeChanges() {
        val popups = listOf(
            LookupPopupItem(
                id = "root",
                clearSelectionSignal = 3,
                state = LookupPopupState(
                    selection = ReaderSelectionData(
                        text = "猫",
                        sentence = "猫です",
                        rect = ReaderSelectionRect(x = 0.0, y = 0.0, width = 1.0, height = 1.0),
                        normalizedOffset = 4,
                    ),
                    results = emptyList(),
                    darkMode = false,
                    eInkMode = false,
                    audioSettings = AudioSettings(enableAutoplay = false),
                ),
            ),
        )

        val themed = popups.withLookupPopupVisualOptions(
            darkMode = true,
            eInkMode = true,
            audioSettings = AudioSettings(enableAutoplay = true),
        )

        assertEquals("root", themed.single().id)
        assertEquals(3, themed.single().clearSelectionSignal)
        assertEquals("猫", themed.single().state.selection.text)
        assertTrue(themed.single().state.darkMode)
        assertTrue(themed.single().state.eInkMode)
        assertTrue(themed.single().state.audioSettings.enableAutoplay)
    }

    @Test
    fun popupSelectionOffsetTracksHistoryControls() {
        assertEquals(
            50.0,
            popupSelectionOffsetY(
                frameTopDp = 50.0,
                popupActionBar = false,
                backCount = 0,
                forwardCount = 0,
                hasSasayakiCue = false,
            ),
            0.0,
        )
        assertEquals(
            87.0,
            popupSelectionOffsetY(
                frameTopDp = 50.0,
                popupActionBar = false,
                backCount = 1,
                forwardCount = 0,
                hasSasayakiCue = false,
            ),
            0.0,
        )
    }

    private fun lookupResult(
        expression: String,
        reading: String,
        glossary: String,
    ): LookupResult = LookupResult(
        expression,
        expression,
        emptyArray(),
        TermResult(
            expression = expression,
            reading = reading,
            rules = "",
            glossaries = arrayOf(
                GlossaryEntry(
                    dictName = "JMdict",
                    glossary = glossary,
                    definitionTags = "",
                    termTags = "",
                ),
            ),
            frequencies = emptyArray(),
            pitches = emptyArray(),
        ),
        0,
    )
}
