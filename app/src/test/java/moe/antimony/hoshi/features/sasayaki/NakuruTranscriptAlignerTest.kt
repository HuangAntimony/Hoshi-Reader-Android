package moe.antimony.hoshi.features.sasayaki

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import moe.antimony.hoshi.epub.EpubBook
import moe.antimony.hoshi.epub.EpubChapter
import moe.antimony.hoshi.epub.SasayakiMatchData
import org.junit.Assert.assertEquals
import org.junit.Test

class NakuruTranscriptAlignerTest {
    @Serializable private data class Case(
        val name: String,
        val chapters: List<String>,
        val tokens: List<SasayakiToken>,
        val expected: SasayakiMatchData,
    )

    @Test fun matchesDesktopRustReference() {
        val json = Json { ignoreUnknownKeys = true }
        val cases = javaClass.getResourceAsStream("/nakuru/desktop-aligner.json")!!.bufferedReader().use {
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
