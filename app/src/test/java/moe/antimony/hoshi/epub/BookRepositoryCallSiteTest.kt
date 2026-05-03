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
        val readerWebView = File("src/main/java/moe/antimony/hoshi/features/reader/ReaderWebView.kt").readText()
        val sasayakiMatchView = File("src/main/java/moe/antimony/hoshi/features/sasayaki/SasayakiMatchView.kt").readText()
        val sasayakiPlayer = File("src/main/java/moe/antimony/hoshi/features/sasayaki/SasayakiPlayer.kt").readText()

        assertTrue(appShell.contains("val bookRepository = remember { BookRepository(context.filesDir) }"))
        assertTrue(appShell.contains("bookRepository.loadBookEntry(bookId)"))
        assertTrue(appShell.contains("bookRepository.saveBookmark(state.bookRoot, savedBookmark)"))
        assertTrue(readerWebView.contains("val bookRepository = remember { BookRepository(context.filesDir) }"))
        assertTrue(sasayakiMatchView.contains("bookRepository: SasayakiSidecarRepository"))
        assertTrue(sasayakiPlayer.contains("bookRepository: SasayakiSidecarRepository"))

        val productionSources = listOf(bookshelfView, appShell, readerWebView, sasayakiMatchView, sasayakiPlayer)
            .joinToString("\n")
        assertFalse(productionSources.contains("BookStorage("))
        assertFalse(productionSources.contains("import moe.antimony.hoshi.epub.BookStorage"))
    }
}
