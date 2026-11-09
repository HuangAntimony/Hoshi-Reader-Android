package moe.antimony.hoshi.features.reader

import java.io.File
import androidx.compose.ui.unit.IntSize
import moe.antimony.hoshi.content.ContentLanguageProfile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import moe.antimony.hoshi.epub.BookInfo
import moe.antimony.hoshi.epub.EpubBook
import moe.antimony.hoshi.epub.EpubChapter
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ReaderPageCountRepositoryTest {
    @get:Rule val temp = TemporaryFolder()
    private val book = EpubBook("Book", chapters = listOf(EpubChapter("a", "a", "text/html", "甲乙")),
        bookInfo = BookInfo(2, mapOf("a" to BookInfo.ChapterInfo(0, 0, 2))))

    @Test fun oldParagraphBoundaryAlgorithmDoesNotReuseItsPageCache() = runBlocking {
        val font = ReaderFontRenderSpec("family", "regular", "Family", "Family", 400, false)
        val geometry = ReaderSettings().readerContentReloadKey()
        val size = IntSize(384, 801)
        val language = ContentLanguageProfile.Japanese
        val legacyKey = pageCountDigest(listOf("reader-pages-v1", "content", geometry, size, 3f,
            font.copy(revision = 0), "font-bytes", language.id, "webview", "system").joinToString("\n"))
        val repository = ReaderPageCountRepository(temp.newFolder(), Dispatchers.IO)
        repository.save("book", legacyKey, requireNotNull(ReaderPageIndex.create(book, listOf(listOf(0, 1)))))
        val currentKey = readerPageCountKey("content", geometry, size, 3f, font,
            "font-bytes", language, "webview", "system")
        assertNull(repository.load("book", currentKey, book))
    }

    @Test fun cacheIdentitySurvivesNewLanguageObjectsAndFontLibrarySessions() = runBlocking {
        val language = ContentLanguageProfile.Japanese
        // Recreate the values as in a new app process, without sharing the singleton's identity.
        val reopenedLanguage = ContentLanguageProfile::class.java.declaredConstructors.single().apply {
            isAccessible = true
        }.newInstance(language.id, language.displayNameRes, language.dictionaryLanguageId, language.htmlLang,
            language.composeLocaleTag, language.inputLocaleTag, language.webViewFontFamilyCss,
            language.readerSerifFontFamilyCss, language.readerSansSerifFontFamilyCss) as ContentLanguageProfile
        val font = ReaderFontRenderSpec("family", "regular", "Family", "Family", 400, false, revision = 1)
        fun key(profile: ContentLanguageProfile, revision: Long) = readerPageCountKey("content",
            ReaderSettings().readerContentReloadKey(), IntSize(384, 801), 3f, font.copy(revision = revision),
            "font-bytes", profile, "webview-version", "system-version")
        val cache = temp.newFolder()
        ReaderPageCountRepository(cache, Dispatchers.IO).save("book", key(language, 1),
            requireNotNull(ReaderPageIndex.create(book, listOf(listOf(0, 1)))))
        val reopened = ReaderPageCountRepository(cache, Dispatchers.IO)
        assertEquals(2, reopened.load("book", key(reopenedLanguage, 99), book)?.totalPages)
        assertNull(reopened.load("book", key(ContentLanguageProfile.Chinese, 99), book))
    }

    @Test fun cacheSurvivesNewRepositoryAndRejectsDifferentLayoutAndCorruption() = runBlocking {
        val cache = temp.newFolder()
        val repository = ReaderPageCountRepository(cache, Dispatchers.IO)
        val index = requireNotNull(ReaderPageIndex.create(book, listOf(listOf(0, 1))))
        repository.save("book", "layout-a", index)
        assertEquals(2, ReaderPageCountRepository(cache, Dispatchers.IO).load("book", "layout-a", book)?.totalPages)
        assertNull(repository.load("book", "layout-b", book))
        repository.save("book", "layout-b", index)
        assertNull(repository.load("book", "layout-a", book))
        cache.walkTopDown().first { it.extension == "json" }.writeText("broken")
        assertNull(repository.load("book", "layout-b", book))
    }

    @Test fun contentAndFontChangesInvalidateEvenWhenFileLengthIsUnchanged() = runBlocking {
        val repository = ReaderPageCountRepository(temp.newFolder(), Dispatchers.IO)
        val font = temp.newFile().apply { writeText("font-a") }
        val original = repository.contentFingerprint(book)
        assertNotEquals(original, repository.contentFingerprint(book.copy(chapters = listOf(book.chapters[0].copy(html = "甲丙")))))
        val fontIdentity = repository.fontFingerprint(listOf(font))
        font.writeText("font-b")
        assertNotEquals(fontIdentity, repository.fontFingerprint(listOf(font)))
    }
}
