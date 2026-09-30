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
    @Test fun cachedChapterShellsLoadTheirStoredSourceWithoutAWebView() = runTest {
        val root = folder.newFolder()
        File(root, "one.xhtml").writeText("<p><ruby>東京<rt>とうきょう</rt></ruby>です。</p>")
        val book = EpubBook("Book", chapters = listOf(EpubChapter("one", "one.xhtml", "application/xhtml+xml", "")), rootDirectory = root)
        val sentences = TtsBookRepository(UnconfinedTestDispatcher(testScheduler)).sentences(book)
        assertEquals("とうきょうです。", TtsText.spoken(sentences.single(), "book", emptyList()))
    }
}
