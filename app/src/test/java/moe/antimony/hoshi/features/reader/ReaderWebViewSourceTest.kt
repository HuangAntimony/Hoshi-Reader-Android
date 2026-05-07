package moe.antimony.hoshi.features.reader

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ReaderWebViewSourceTest {
    @Test
    fun sasayakiPlayerReceivesCurrentBookCoverFile() {
        val source = File("src/main/java/moe/antimony/hoshi/features/reader/ReaderWebView.kt").readText()
        val playerCreation = source.substringAfter("SasayakiPlayer(")
            .substringBefore("matchData = sasayakiMatchData")

        assertTrue(source.contains("val sasayakiCoverFile = remember(bookRoot, book.coverHref)"))
        assertTrue(source.contains("private fun resolveBookCoverFile(bookRoot: File?, coverHref: String?): File?"))
        assertTrue(playerCreation.contains("bookCoverFile = sasayakiCoverFile"))
    }

    @Test
    fun sasayakiPlayerDisposalKeepsReaderLifecycleScopedInstance() {
        val source = File("src/main/java/moe/antimony/hoshi/features/reader/ReaderWebView.kt").readText()
        val playerSection = source.substringAfter("var sasayakiPlayer by remember")
            .substringBefore("LaunchedEffect(showReaderMenu, showSasayaki)")

        assertFalse(source.contains("rememberUpdatedState(sasayakiPlayer)"))
        assertFalse(playerSection.contains("currentSasayakiPlayer"))
        assertTrue(playerSection.contains("onDispose { sasayakiPlayer?.release() }"))
    }

    @Test
    fun pagedNavigationDefersProgressSaveUntilAfterWebViewScrollPaint() {
        val source = File("src/main/java/moe/antimony/hoshi/features/reader/ReaderWebView.kt").readText()
        val navigatePage = source.substringAfter("private fun WebView.navigatePage(")
            .substringBefore("private fun WebView.navigatePageForDirection(")

        assertTrue(source.contains("PAGE_TURN_PROGRESS_SAVE_DELAY_MS"))
        assertTrue(source.contains("PAGE_TURN_PROGRESS_SAVE_DELAY_MS = 1_000L"))
        assertTrue(navigatePage.contains("postDelayed(progressCallback, PAGE_TURN_PROGRESS_SAVE_DELAY_MS)"))
        assertTrue(navigatePage.contains("ReaderPaginationScripts.progressInvocation()"))
        assertTrue(navigatePage.contains("PAGE_TURN_PROGRESS_SAVE_DELAY_MS"))
    }

    @Test
    fun pagedNavigationUpdatesDisplayedProgressBeforeDebouncedBookmarkSave() {
        val source = File("src/main/java/moe/antimony/hoshi/features/reader/ReaderWebView.kt").readText()
        val navigatePage = source.substringAfter("private fun WebView.navigatePage(")
            .substringBefore("private fun WebView.navigatePageForDirection(")
        val readerNavigation = source.substringAfter("fun navigateReaderPage(direction: ReaderNavigationDirection): Boolean")
            .substringBefore("fun pauseSasayakiForLookupIfNeeded()")

        assertTrue(source.contains("fun displayPagedTurnProgress(progress: Double)"))
        assertTrue(navigatePage.contains("onDisplayedProgress: (progress: Double) -> Unit"))
        assertTrue(navigatePage.contains("onSaveProgress: (progress: Double) -> Unit"))
        assertTrue(navigatePage.indexOf("onDisplayedProgress(progress)") < navigatePage.indexOf("postDelayed"))
        assertTrue(navigatePage.contains("onSaveProgress(progress)"))
        assertTrue(readerNavigation.contains("::displayPagedTurnProgress"))
        assertTrue(readerNavigation.contains("::saveDisplayedProgress"))
    }

    @Test
    fun pendingPagedTurnProgressIsFlushedBeforeReaderCloseOrStop() {
        val source = File("src/main/java/moe/antimony/hoshi/features/reader/ReaderWebView.kt").readText()
        val closeReader = source.substringAfter("fun closeReader()")
            .substringBefore("fun clearReaderSelection()")
        val lifecycleEffect = source.substringAfter("DisposableEffect(view)")
            .substringBefore("BackHandler")

        assertTrue(source.contains("private fun WebView.flushPendingPageTurnProgress()"))
        assertTrue(closeReader.contains("webView?.flushPendingPageTurnProgress()"))
        assertTrue(closeReader.contains("onClose()"))
        assertTrue(source.contains("BackHandler(onBack = ::closeReader)"))
        assertTrue(source.contains("onClose = ::closeReader"))
        assertTrue(lifecycleEffect.contains("Lifecycle.Event.ON_STOP"))
        assertTrue(lifecycleEffect.contains("webView?.flushPendingPageTurnProgress()"))
        assertTrue(lifecycleEffect.contains("onDispose"))
    }

    @Test
    fun pageTurnsDoNotClearSelectionBridgeWhenNoLookupPopupIsOpen() {
        val source = File("src/main/java/moe/antimony/hoshi/features/reader/ReaderWebView.kt").readText()
        val closeLookup = source.substringAfter("fun closeLookupPopupsAndSelection()")
            .substringBefore("fun updateSasayakiSettings")

        assertTrue(closeLookup.contains("if (lookupPopups.isNotEmpty())"))
        assertTrue(closeLookup.contains("clearReaderSelection()"))
        assertTrue(closeLookup.contains("setLookupPopups(emptyList())"))
    }
}
