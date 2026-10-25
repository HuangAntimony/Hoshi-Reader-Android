package moe.antimony.hoshi.features.tts

import java.io.File
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import moe.antimony.hoshi.epub.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

@OptIn(ExperimentalCoroutinesApi::class)
class TtsBookRepositoryTest {
    @get:Rule val folder = TemporaryFolder()
    @Test fun repeatedImagesDoNotCreatePlaybackBarriersButOrdinaryIllustrationsDo() = runTest {
        val chapters = listOf(
            EpubChapter("a", "Text/a.xhtml", "application/xhtml+xml", "<p>一。</p><img src='../images/divider.jpg'/><p>二。</p><img src='../images/art.jpg'/><p>三。</p>"),
            EpubChapter("b", "Text/b.xhtml", "application/xhtml+xml", "<img src='../images/divider.jpg'/><p>四。</p><img src='../images/divider.jpg'/>"),
        )
        val book = EpubBook("Book", chapters = chapters)
        val repository = TtsBookRepository(UnconfinedTestDispatcher(testScheduler))
        val sentences = repository.chapter(book, 0)
        assertEquals(listOf(false, false, true), sentences.map { it.imageBefore })
        assertEquals(false, repository.chapter(book, 1).single().imageBefore)
        val from = repository.at(book, 0, 0)!!
        val to = repository.adjacent(book, from, 1)!!
        assertEquals(false, repository.hasImagesBetween(book, from, to))
        assertEquals(true, repository.hasImagesBetween(book, to, repository.adjacent(book, to, 1)!!))
    }

    @Test fun playbackAndLookupShareParsedChapterButReopenedBooksReadFreshContent() = runTest {
        val root = folder.newFolder()
        val file = File(root, "one.xhtml")
        file.writeText("<p>「<ruby>東京<rt>とうきょう</rt></ruby>です。次です。」</p>")
        val book = EpubBook("Book", chapters = listOf(EpubChapter("one", "one.xhtml", "application/xhtml+xml", "")), rootDirectory = root)
        val repository = TtsBookRepository(UnconfinedTestDispatcher(testScheduler))
        repository.chapter(book, 0)
        file.writeText("<p>新しい本文です。</p>")
        val selection = repository.selection(book, 0, "東京です。", 0, 0)
        assertEquals("とうきょうです。", TtsText.spoken(selection, null, emptyList()))
        assertTrue(selection.roles.any { it.role == TtsTextRole.Dialogue })
        assertEquals("新しい本文です。", repository.chapter(book.copy(), 0).single().text)
    }

    @Test fun navigationSkipsEmptyAndNonReadingChaptersAndPreservesChapterOffsets() = runTest {
        val chapters = listOf(
            EpubChapter("one", "one.xhtml", "application/xhtml+xml", "<p>一。二。</p>"),
            EpubChapter("aside", "aside.xhtml", "application/xhtml+xml", "<p>欄外。</p>", linear = false),
            EpubChapter("nav", "nav.xhtml", "application/xhtml+xml", "<p>目次。</p>", properties = "nav"),
            EpubChapter("empty", "empty.xhtml", "application/xhtml+xml", "<img src='a.jpg'/>"),
            EpubChapter("two", "two.xhtml", "application/xhtml+xml", "<img src='a.jpg'/><p>三。</p>"),
        )
        val book = EpubBook("Book", chapters = chapters)
        val repository = TtsBookRepository(UnconfinedTestDispatcher(testScheduler))
        val first = requireNotNull(repository.at(book, 0, 0))
        assertNull(first.previousChapter)
        assertEquals(4, first.nextChapter)
        val second = requireNotNull(repository.adjacent(book, first, 1))
        assertEquals("二。", second.sentence.text)
        val next = requireNotNull(repository.adjacent(book, second, 1))
        assertEquals(4, next.sentence.chapter)
        assertEquals(0, next.sentence.start)
        assertTrue(next.sentence.imageBefore)
        assertNull(next.nextChapter)
        assertEquals(second, repository.adjacent(book, next, -1))
        assertEquals(next, repository.at(book, 3, 0))
        assertEquals(next, repository.at(book, 4, 999))
    }

    @Test fun chapterCacheEvictsOldestUnusedSourceAndSeparatesBooks() = runTest {
        val root = folder.newFolder()
        val chapters = (0..4).map { index ->
            File(root, "$index.xhtml").writeText("<p>第${index}章。</p>")
            EpubChapter("$index", "$index.xhtml", "application/xhtml+xml", "")
        }
        val book = EpubBook("Book", chapters = chapters, rootDirectory = root)
        val repository = TtsBookRepository(UnconfinedTestDispatcher(testScheduler))
        for (index in 0..3) repository.chapter(book, index)
        File(root, "0.xhtml").writeText("<p>再読込。</p>")
        repository.chapter(book, 1) // Recently read chapters remain available for lookup.
        repository.chapter(book, 4)
        assertEquals("再読込。", repository.chapter(book, 0).single().text)
        val other = book.copy(chapters = listOf(chapters[0].copy(html = "<p>別の本。</p>")))
        assertEquals("別の本。", repository.chapter(other, 0).single().text)
    }

    @Test fun xhtmlSelfClosingScriptsPreserveBodyRubyAndSentencePositions() = runTest {
        val html = """<?xml version="1.0" encoding="UTF-8"?>
            <html xmlns="http://www.w3.org/1999/xhtml"><head><title>書名</title>
            <script src="reader.js"/><style/></head><body>
            <p>「<ruby>東京<rt>とうきょう</rt></ruby>です。」</p>
            <script src="other.js"/><img src="picture.jpg"/>
            <p>次&#x3067;す。</p></body></html>""".trimIndent()
        val root = folder.newFolder()
        File(root, "chapter.xhtml").writeText(html)
        // Both a newly parsed chapter and a file-backed cached chapter use its declared MIME type.
        for (source in listOf(html, "")) {
            val book = EpubBook("Book", chapters = listOf(EpubChapter("one", "chapter.xhtml", "application/xhtml+xml", source)), rootDirectory = root)
            val sentences = TtsBookRepository(UnconfinedTestDispatcher(testScheduler)).chapter(book, 0)
            assertEquals(listOf("「東京です。」", "次です。"), sentences.map { it.text.trim() })
            assertEquals(listOf(0, 4), sentences.map { it.start })
            assertEquals("「とうきょうです。」", TtsText.spoken(sentences.first(), null, emptyList()).trim())
            assertTrue(sentences.last().imageBefore)
            assertTrue(sentences.first().roles.any { it.role == TtsTextRole.Dialogue })
        }
    }

    @Test fun htmlChaptersRetainHtmlRecoveryAndIgnoreScriptContents() = runTest {
        val html = """<html><head><script>const text = '<p>偽の本文</p>';</script></head>
            <body><p>東京です。<p>次です。""".trimIndent()
        val book = EpubBook("Book", chapters = listOf(EpubChapter("one", "one.html", "text/html", html)))
        val sentences = TtsBookRepository(UnconfinedTestDispatcher(testScheduler)).chapter(book, 0)
        assertEquals(listOf("東京です。", "次です。"), sentences.map { it.text })
        assertEquals(listOf(0, 4), sentences.map { it.start })
    }

    @Test fun cachedChapterShellsLoadTheirStoredSourceWithoutAWebView() = runTest {
        val root = folder.newFolder()
        File(root, "one.xhtml").writeText("<p><ruby>東京<rt>とうきょう</rt></ruby>です。</p>")
        val book = EpubBook("Book", chapters = listOf(EpubChapter("one", "one.xhtml", "application/xhtml+xml", "")), rootDirectory = root)
        val sentences = TtsBookRepository(UnconfinedTestDispatcher(testScheduler)).chapter(book, 0)
        assertEquals("とうきょうです。", TtsText.spoken(sentences.single(), "book", emptyList()))
    }
}
