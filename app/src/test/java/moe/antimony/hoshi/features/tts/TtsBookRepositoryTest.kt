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
            val sentences = TtsBookRepository(UnconfinedTestDispatcher(testScheduler)).sentences(book)
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
        val sentences = TtsBookRepository(UnconfinedTestDispatcher(testScheduler)).sentences(book)
        assertEquals(listOf("東京です。", "次です。"), sentences.map { it.text })
        assertEquals(listOf(0, 4), sentences.map { it.start })
    }

    @Test fun cachedChapterShellsLoadTheirStoredSourceWithoutAWebView() = runTest {
        val root = folder.newFolder()
        File(root, "one.xhtml").writeText("<p><ruby>東京<rt>とうきょう</rt></ruby>です。</p>")
        val book = EpubBook("Book", chapters = listOf(EpubChapter("one", "one.xhtml", "application/xhtml+xml", "")), rootDirectory = root)
        val sentences = TtsBookRepository(UnconfinedTestDispatcher(testScheduler)).sentences(book)
        assertEquals("とうきょうです。", TtsText.spoken(sentences.single(), "book", emptyList()))
    }
}
