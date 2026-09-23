package moe.antimony.hoshi.features.sasayaki

import java.util.Locale
import moe.antimony.hoshi.epub.EpubBook
import moe.antimony.hoshi.epub.EpubChapter
import moe.antimony.hoshi.epub.SasayakiMatch
import moe.antimony.hoshi.epub.SasayakiMatchData
import moe.antimony.hoshi.epub.SasayakiMatchSource
import org.junit.Assert.*
import org.junit.Test

class SasayakiSrtTest {
    private val book = EpubBook(title = "Test", chapters = listOf(EpubChapter(
        id = "chapter", href = "chapter.xhtml", mediaType = "application/xhtml+xml",
        html = "<body>雨の降る静かな朝だった。私は駅へ向かって歩いた。</body>",
    )))

    @Test fun importedSubtitlesExportAndRematchWithoutOriginalFile() {
        val input = "\uFEFF1\r\n00:00:01,125 --> 00:00:04,500\r\n雨の降る\r\n静かな朝だった。\r\n\r\n" +
            "2\r\n00:00:05,000 --> 00:00:08,000\r\n私は駅へ向かって歩いた。\r\n\r\n" +
            "3\r\n00:00:09,000 --> 00:00:10,000\r\nこの文章は本に含まれていません。\r\n"
        val match = SasayakiMatcher.match(book, SasayakiParser.parseCues(input.toByteArray()))
        assertEquals(2, match.matches.size)
        assertEquals(1, match.unmatched)
        val exported = SasayakiSrt.encode(match)
        val cues = SasayakiParser.parseCues(exported.toByteArray(Charsets.UTF_8))
        assertEquals("雨の降る\n静かな朝だった。", cues.first().text)
        assertEquals(1.125, cues.first().startTime, 0.0)
        assertEquals(match.matches, SasayakiMatcher.match(book, cues).matches)
    }

    @Test fun transcriptionExportCanBeImportedWithoutTranscriptionData() {
        val match = SasayakiTranscriptAligner.align(book, listOf(
            SasayakiToken("雨の降る静かな朝だった", 1.0, 4.0),
            SasayakiToken("私は駅へ向かって歩いた", 5.0, 8.0),
        ))
        assertEquals(SasayakiMatchSource.Transcription, match.source)
        val restored = SasayakiMatcher.match(book, SasayakiParser.parseCues(SasayakiSrt.encode(match).toByteArray()))
        assertEquals(match.matches.map { it.copy(id = "") }, restored.matches.map { it.copy(id = "") })
    }

    @Test fun timestampsRoundCarryAndStayAsciiInOtherLocales() {
        val previous = Locale.getDefault()
        try {
            Locale.setDefault(Locale.forLanguageTag("ar"))
            val text = SasayakiSrt.encode(data(cue(3599.9996, 3601.2344), cue(360000.0, 360000.0001)))
            assertTrue(text.contains("01:00:00,000 --> 01:00:01,234"))
            assertTrue(text.contains("100:00:00,000 --> 100:00:00,001"))
        } finally { Locale.setDefault(previous) }
    }

    @Test fun exportSortsAndSkipsInvalidCuesWithoutCreatingBlankSubtitleBlocks() {
        val text = SasayakiSrt.encode(data(
            cue(5.0, 6.0, "後の字幕"), cue(1.0, 2.0, "最初\r\n \r\n　の字幕"),
            cue(-1.0, 1.0), cue(Double.NaN, 2.0), cue(1.0, Double.POSITIVE_INFINITY),
            cue(3.0, 2.0), cue(2.0, 2.0), cue(0.0, 1.0, " \n "),
        ))
        val restored = SasayakiParser.parseCues(text.toByteArray())
        assertEquals(listOf("最初\n　の字幕", "後の字幕"), restored.map { it.text })
        assertEquals(listOf(1.0, 5.0), restored.map { it.startTime })
        assertTrue(text.startsWith("1\n"))
        assertTrue(text.contains("\n\n2\n"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun emptyMatchDoesNotProduceAnUnusableExport() { SasayakiSrt.encode(data()) }

    private fun cue(from: Double, to: Double, text: String = "本文") = SasayakiMatch("cue", from, to, text, 0, 0, text.length)
    private fun data(vararg cues: SasayakiMatch) = SasayakiMatchData(cues.toList(), 0)
}
