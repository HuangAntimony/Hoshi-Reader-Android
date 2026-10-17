package moe.antimony.hoshi.features.sasayaki

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import moe.antimony.hoshi.epub.EpubBook
import moe.antimony.hoshi.epub.EpubChapter
import moe.antimony.hoshi.epub.SasayakiMatchData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NakuruTranscriptAlignerTest {
    @Test fun cancelledMatchingDoesNotPublishAResult() = runTest {
        val book = EpubBook(title = "Cancellation", chapters = listOf(
            EpubChapter("one", "one.xhtml", "application/xhtml+xml", "<p>雨の降る静かな朝だった。</p>"),
        ))
        val aligner = NakuruTranscriptAligner(book)
        var cancelled = false
        val task = launch {
            currentCoroutineContext().cancel()
            try {
                aligner.align(listOf(SasayakiToken("雨の降る静かな朝だった", 1.0, 4.0)))
            } catch (_: CancellationException) {
                cancelled = true
            }
        }
        task.join()
        assertTrue("Matching must observe cancellation before returning cues", cancelled)
    }

    @Test fun successivePrefixesMatchFreshAlignmentAcrossFixtureBoundaries() = runTest {
        val json = Json { ignoreUnknownKeys = true }
        val cases = javaClass.getResourceAsStream("/nakuru/aligner.json")!!.bufferedReader().use {
            json.decodeFromString<List<Case>>(it.readText())
        }
        for (case in cases) {
            val book = EpubBook(title = case.name, chapters = case.chapters.mapIndexed { index, html ->
                EpubChapter("chapter-$index", "chapter-$index.xhtml", "application/xhtml+xml", html)
            })
            val session = NakuruTranscriptAligner(book)
            for (count in (listOf(0, 1, 2, 3, case.tokens.size / 2, case.tokens.size - 1, case.tokens.size)
                .filter { it in 0..case.tokens.size }.distinct().sorted())) {
                val snapshot = case.tokens.take(count)
                assertEquals("${case.name}: prefix=$count", NakuruTranscriptAligner(book).align(snapshot), session.align(snapshot))
            }
            assertEquals(case.expected.matches.size, session.align(case.tokens).matches.size)
        }
    }

    @Serializable private data class Case(
        val name: String,
        val chapters: List<String>,
        val tokens: List<SasayakiToken>,
        val expected: SasayakiMatchData,
    )

    @Test fun matchesExpectedAlignments() = runTest {
        val json = Json { ignoreUnknownKeys = true }
        val cases = javaClass.getResourceAsStream("/nakuru/aligner.json")!!.bufferedReader().use {
            json.decodeFromString<List<Case>>(it.readText())
        }
        cases.forEach { case ->
            val book = EpubBook(title = case.name, chapters = case.chapters.mapIndexed { index, html ->
                EpubChapter("chapter-$index", "chapter-$index.xhtml", "application/xhtml+xml", html)
            })
            val result = NakuruTranscriptAligner(book).align(case.tokens)
            assertEquals(case.name, case.expected.unmatched, result.unmatched)
            assertEquals(case.name, case.expected.matches.size, result.matches.size)
            case.expected.matches.zip(result.matches).forEach { (expected, actual) ->
                assertEquals(case.name, expected.startTime, actual.startTime, 1e-9)
                assertEquals(case.name, expected.endTime, actual.endTime, 1e-9)
                assertEquals(case.name, expected.copy(startTime = actual.startTime, endTime = actual.endTime), actual)
            }
        }
    }
}
