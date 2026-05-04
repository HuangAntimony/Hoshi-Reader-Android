package moe.antimony.hoshi

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class HoshiAppContainerSourceTest {
    @Test
    fun appContainerOwnsProductionDependencyConstruction() {
        val container = source("HoshiAppContainer.kt")
        val mainActivity = source("MainActivity.kt")
        val appShell = source("navigation/AppShell.kt")
        val bookshelf = source("features/bookshelf/BookshelfView.kt")
        val dictionaryView = source("features/dictionary/DictionaryView.kt")
        val dictionarySearch = source("features/dictionary/DictionarySearchView.kt")
        val readerWebView = source("features/reader/ReaderWebView.kt")

        container.mustContainAll(
            "class HoshiAppContainer",
            "private val appContext = context.applicationContext",
            "val bookRepository: BookRepository = BookRepository(appContext.filesDir)",
            "val dictionaryRepository: DictionaryRepository = DictionaryRepository(appContext.filesDir, appContext.cacheDir)",
            "val readerSettingsRepository: ReaderSettingsRepository = appContext.readerSettingsRepository()",
            "val dictionarySettingsRepository: DictionarySettingsRepository = appContext.dictionarySettingsRepository()",
            "val audioSettingsRepository: AudioSettingsRepository = appContext.audioSettingsRepository()",
            "val sasayakiSettingsRepository: SasayakiSettingsRepository = appContext.sasayakiSettingsRepository()",
            "val readerFontManager: ReaderFontManager = ReaderFontManager(appContext.filesDir)",
            "fun readerRouteStateHolder(): ReaderRouteStateHolder",
        )
        assertTrue(mainActivity.contains("HoshiAppContainer(applicationContext)"))
        assertTrue(mainActivity.contains("LocalHoshiAppContainer provides appContainer"))
        listOf(appShell, bookshelf, dictionaryView, dictionarySearch, readerWebView).forEach { productionSource ->
            assertTrue(productionSource.contains("LocalHoshiAppContainer.current"))
            assertFalse(productionSource.contains("BookRepository(context.filesDir)"))
            assertFalse(productionSource.contains("DictionaryRepository(context.filesDir, context.cacheDir)"))
        }
    }

    private fun source(path: String): String =
        File("src/main/java/moe/antimony/hoshi/$path").readText()

    private fun String.mustContainAll(vararg snippets: String) {
        snippets.forEach { snippet ->
            assertTrue("Missing snippet: $snippet", contains(snippet))
        }
    }
}
