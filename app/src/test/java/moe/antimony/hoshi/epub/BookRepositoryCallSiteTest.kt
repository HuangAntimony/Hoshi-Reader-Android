package moe.antimony.hoshi.epub


import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class BookRepositoryCallSiteTest {
    @Test
    fun readerAndSasayakiProductionPathsUseRepositoryFacingApis() {
        val bookshelfView = File("src/main/java/moe/antimony/hoshi/features/bookshelf/BookshelfView.kt").readText()
        val appShell = File("src/main/java/moe/antimony/hoshi/navigation/AppShell.kt").readText()
        val readerDestination = File("src/main/java/moe/antimony/hoshi/navigation/ReaderRouteDestination.kt").readText()
        val readerWebView = File("src/main/java/moe/antimony/hoshi/features/reader/ReaderWebView.kt").readText()
        val sasayakiMatchView = File("src/main/java/moe/antimony/hoshi/features/sasayaki/SasayakiMatchView.kt").readText()
        val sasayakiPlayer = File("src/main/java/moe/antimony/hoshi/features/sasayaki/SasayakiPlayer.kt").readText()
        val sasayakiPlaybackController = File(
            "src/main/java/moe/antimony/hoshi/features/sasayaki/SasayakiPlaybackController.kt",
        ).readText()
        val sasayakiPlaybackPersistence = File(
            "src/main/java/moe/antimony/hoshi/features/sasayaki/SasayakiPlaybackPersistenceState.kt",
        ).readText()

        assertTrue(appShell.contains("val bookRepository = appContainer.bookRepository"))
        assertTrue(appShell.contains("val readerRouteStateHolder = remember(appContainer) { appContainer.readerRouteStateHolder() }"))
        assertTrue(readerDestination.contains("stateHolder.load(bookId)"))
        assertTrue(readerDestination.contains("stateHolder.saveBookmark("))
        assertTrue(readerWebView.contains("val bookRepository = appContainer.bookRepository"))
        assertTrue(readerWebView.contains("BookSasayakiPlaybackRepository(bookRoot, bookRepository)"))
        assertTrue(sasayakiMatchView.contains("bookRepository: SasayakiSidecarRepository"))
        assertTrue(sasayakiPlayer.contains("playbackRepository: SasayakiPlaybackRepository"))
        assertTrue(sasayakiPlaybackController.contains("playbackRepository: SasayakiPlaybackRepository"))
        assertTrue(sasayakiPlaybackController.contains("SasayakiPlaybackPersistenceState("))
        assertTrue(sasayakiPlaybackPersistence.contains("initialPlayback ?: SasayakiPlaybackData(lastPosition = 0.0)"))
        assertTrue(sasayakiPlaybackPersistence.contains("playbackRepository.save(snapshot)"))
        assertFalse(sasayakiPlayer.contains("bookRepository: SasayakiSidecarRepository"))
        assertFalse(sasayakiPlaybackController.contains("bookRepository: SasayakiSidecarRepository"))

        val productionSources = listOf(
            bookshelfView,
            appShell,
            readerDestination,
            readerWebView,
            sasayakiMatchView,
            sasayakiPlayer,
            sasayakiPlaybackController,
            sasayakiPlaybackPersistence,
        )
            .joinToString("\n")
        assertFalse(productionSources.contains("BookStorage("))
        assertFalse(productionSources.contains("import moe.antimony.hoshi.epub.BookStorage"))
    }
}
