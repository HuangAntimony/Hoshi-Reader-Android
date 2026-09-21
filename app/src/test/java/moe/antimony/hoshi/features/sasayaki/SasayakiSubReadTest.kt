package moe.antimony.hoshi.features.sasayaki

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class SasayakiSubReadTest {
    @Test
    fun requestNeedsTheAudiobookBeforeTheBook() {
        assertEquals(
            SasayakiSubReadRequest.MissingAudio,
            sasayakiSubReadRequest(audio = null, book = File("book.epub"), bookLanguage = "ja"),
        )
        assertEquals(
            SasayakiSubReadRequest.MissingAudio,
            sasayakiSubReadRequest(audio = null, book = null, bookLanguage = "ja"),
        )
    }

    @Test
    fun requestNeedsThePackedEpubFile() {
        assertEquals(
            SasayakiSubReadRequest.MissingBook,
            sasayakiSubReadRequest(audio = privateAudio, book = null, bookLanguage = "ja"),
        )
    }

    @Test
    fun readyRequestKeepsTheAudioSourceTheBookAndThePrimaryLanguageSubtag() {
        val book = File("book.epub")

        val request = sasayakiSubReadRequest(audio = privateAudio, book = book, bookLanguage = "ja-JP")

        assertEquals(SasayakiSubReadRequest.Ready(audio = privateAudio, book = book, language = "ja"), request)
    }

    @Test
    fun languageKeepsOnlyAWhisperLanguageCode() {
        assertEquals("ja", sasayakiSubReadLanguage("ja"))
        assertEquals("ja", sasayakiSubReadLanguage("JA-JP"))
        assertEquals("zh", sasayakiSubReadLanguage(" zh_CN "))
        assertNull(sasayakiSubReadLanguage(null))
        assertNull(sasayakiSubReadLanguage("  "))
        assertNull(sasayakiSubReadLanguage("-JP"))
    }

    @Test
    fun subtitlesResultKeepsTheMatchRate() {
        val result = sasayakiSubReadResult(
            isOk = true,
            hasSubtitles = true,
            error = null,
            matchRate = 0.93,
        )

        assertEquals(SasayakiSubReadResult.Subtitles(matchRate = 0.93), result)
    }

    @Test
    fun successWithoutSubtitlesFails() {
        val result = sasayakiSubReadResult(
            isOk = true,
            hasSubtitles = false,
            error = null,
            matchRate = null,
        )

        assertEquals(SasayakiSubReadResult.Failed(error = null), result)
    }

    @Test
    fun reportedErrorFailsEvenWhenTheUserWentBack() {
        val result = sasayakiSubReadResult(
            isOk = false,
            hasSubtitles = false,
            error = "  The audio file is too short.  ",
            matchRate = null,
        )

        assertEquals(SasayakiSubReadResult.Failed(error = "The audio file is too short."), result)
    }

    @Test
    fun cancelWithoutAnErrorIsNotAFailure() {
        val result = sasayakiSubReadResult(
            isOk = false,
            hasSubtitles = false,
            error = "   ",
            matchRate = null,
        )

        assertEquals(SasayakiSubReadResult.Canceled, result)
    }

    @Test
    fun onlyAKnownMatchRateBelowTheLimitIsLow() {
        assertTrue(sasayakiSubReadMatchRateIsLow(0.79))
        assertFalse(sasayakiSubReadMatchRateIsLow(SubRead.LOW_MATCH_RATE))
        assertFalse(sasayakiSubReadMatchRateIsLow(1.0))
        assertFalse(sasayakiSubReadMatchRateIsLow(null))
        assertFalse(sasayakiSubReadMatchRateIsLow(Double.NaN))
    }

    private val privateAudio = SasayakiPlaybackSource.PrivateFile(File("Sasayaki/sasayaki_audio.m4b"))
}
