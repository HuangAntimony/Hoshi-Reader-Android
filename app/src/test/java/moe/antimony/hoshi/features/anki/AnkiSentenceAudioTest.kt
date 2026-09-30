package moe.antimony.hoshi.features.anki

import org.junit.Assert.assertEquals
import org.junit.Test

class AnkiSentenceAudioTest {
    @Test fun ttsSentenceAudioNeverLeaksIntoSasayakiHandlebar() {
        val context = AnkiMiningContext(sentence = "東京です。", sentenceAudioPath = "[sound:tts.wav]")
        assertEquals("|[sound:tts.wav]|東京です。", AnkiHandlebarRenderer.render(
            "{sasayaki-audio}|{sentence-audio}|{sentence}", AnkiMiningPayload(expression = "東京"), context))
    }
    @Test fun audiobookCanPopulateBothWithoutChangingOldTemplate() {
        val context = AnkiMiningContext(sentence = "", sasayakiAudioPath = "[sound:book.mp3]", sentenceAudioPath = "[sound:book.mp3]")
        assertEquals("[sound:book.mp3]|[sound:book.mp3]", AnkiHandlebarRenderer.render(
            "{sasayaki-audio}|{sentence-audio}", AnkiMiningPayload(expression = "東京"), context))
    }
}
