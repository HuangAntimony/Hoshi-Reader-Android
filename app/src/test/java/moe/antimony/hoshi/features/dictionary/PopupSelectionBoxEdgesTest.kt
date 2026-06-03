package moe.antimony.hoshi.features.dictionary

import moe.antimony.hoshi.features.reader.ReaderSelectionRect
import org.junit.Assert.assertEquals
import org.junit.Test

class PopupSelectionBoxEdgesTest {
    @Test
    fun horizontalPageSplitOpensOppositeBoxEdges() {
        val edges = popupSelectionBoxEdges(
            rects = listOf(
                ReaderSelectionRect(x = 90.0, y = 24.0, width = 10.0, height = 16.0),
                ReaderSelectionRect(x = 0.0, y = 24.0, width = 12.0, height = 16.0),
            ),
            verticalWriting = false,
            viewportWidth = 100.0,
            viewportHeight = 100.0,
        )

        assertEquals(
            listOf(
                PopupSelectionBoxEdges(right = false),
                PopupSelectionBoxEdges(left = false),
            ),
            edges,
        )
    }

    @Test
    fun horizontalLineSplitOpensOppositeBoxEdges() {
        val edges = popupSelectionBoxEdges(
            rects = listOf(
                ReaderSelectionRect(x = 90.0, y = 24.0, width = 10.0, height = 16.0),
                ReaderSelectionRect(x = 0.0, y = 50.0, width = 12.0, height = 16.0),
            ),
            verticalWriting = false,
            viewportWidth = 100.0,
            viewportHeight = 100.0,
        )

        assertEquals(
            listOf(
                PopupSelectionBoxEdges(right = false),
                PopupSelectionBoxEdges(left = false),
            ),
            edges,
        )
    }

    @Test
    fun verticalPageSplitOpensOppositeBoxEdges() {
        val edges = popupSelectionBoxEdges(
            rects = listOf(
                ReaderSelectionRect(x = 40.0, y = 90.0, width = 12.0, height = 10.0),
                ReaderSelectionRect(x = 40.0, y = 0.0, width = 12.0, height = 8.0),
            ),
            verticalWriting = true,
            viewportWidth = 100.0,
            viewportHeight = 100.0,
        )

        assertEquals(
            listOf(
                PopupSelectionBoxEdges(bottom = false),
                PopupSelectionBoxEdges(top = false),
            ),
            edges,
        )
    }

    @Test
    fun verticalAdjacentRubySegmentsMergeIntoOneBoxRect() {
        val rects = popupSelectionBoxRects(
            rects = listOf(
                ReaderSelectionRect(x = 40.0, y = 50.0, width = 12.0, height = 30.0),
                ReaderSelectionRect(x = 40.0, y = 80.0, width = 12.0, height = 30.0),
            ),
            verticalWriting = true,
        )

        assertEquals(
            listOf(
                ReaderSelectionRect(x = 40.0, y = 50.0, width = 12.0, height = 60.0),
            ),
            rects,
        )
    }

    @Test
    fun horizontalAdjacentRubySegmentsMergeIntoOneBoxRect() {
        val rects = popupSelectionBoxRects(
            rects = listOf(
                ReaderSelectionRect(x = 20.0, y = 24.0, width = 18.0, height = 16.0),
                ReaderSelectionRect(x = 38.0, y = 24.0, width = 12.0, height = 16.0),
            ),
            verticalWriting = false,
        )

        assertEquals(
            listOf(
                ReaderSelectionRect(x = 20.0, y = 24.0, width = 30.0, height = 16.0),
            ),
            rects,
        )
    }

    @Test
    fun horizontalRubyAwareLineHeightExpandsAdjacentSegments() {
        val rects = popupSelectionBoxRects(
            rects = listOf(
                ReaderSelectionRect(x = 20.0, y = 20.0, width = 18.0, height = 16.0),
                ReaderSelectionRect(x = 38.0, y = 20.0, width = 12.0, height = 24.0),
                ReaderSelectionRect(x = 50.0, y = 20.0, width = 12.0, height = 16.0),
            ),
            verticalWriting = false,
        )

        assertEquals(
            listOf(
                ReaderSelectionRect(x = 20.0, y = 20.0, width = 42.0, height = 24.0),
            ),
            rects,
        )
    }

    @Test
    fun verticalRubyAwareColumnWidthExpandsAdjacentSegments() {
        val rects = popupSelectionBoxRects(
            rects = listOf(
                ReaderSelectionRect(x = 40.0, y = 60.0, width = 12.0, height = 10.0),
                ReaderSelectionRect(x = 34.0, y = 70.0, width = 18.0, height = 10.0),
                ReaderSelectionRect(x = 40.0, y = 80.0, width = 12.0, height = 10.0),
            ),
            verticalWriting = true,
        )

        assertEquals(
            listOf(
                ReaderSelectionRect(x = 34.0, y = 60.0, width = 18.0, height = 30.0),
            ),
            rects,
        )
    }

    @Test
    fun ordinaryTwoRectSelectionKeepsCompleteBoxes() {
        val edges = popupSelectionBoxEdges(
            rects = listOf(
                ReaderSelectionRect(x = 20.0, y = 24.0, width = 10.0, height = 16.0),
                ReaderSelectionRect(x = 34.0, y = 24.0, width = 12.0, height = 16.0),
            ),
            verticalWriting = false,
            viewportWidth = 100.0,
            viewportHeight = 100.0,
        )

        assertEquals(
            listOf(
                PopupSelectionBoxEdges(),
                PopupSelectionBoxEdges(),
            ),
            edges,
        )
    }
}
