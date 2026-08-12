package moe.antimony.hoshi.features.anki

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AnkiFormatDeleteRequestTest {
    @Test
    fun deleteTapCreatesAConfirmationRequestWithoutRemovingTheFormat() {
        val formats = listOf(
            AnkiCardFormat(id = "word", name = "Word"),
            AnkiCardFormat(id = "sentence", name = "Sentence"),
        )

        val request = ankiFormatDeleteRequest(formats, "sentence")

        assertEquals(AnkiFormatDeleteRequest("sentence", "Sentence"), request)
        assertEquals(listOf("word", "sentence"), formats.map(AnkiCardFormat::id))
    }

    @Test
    fun lastFormatCannotCreateADeleteRequest() {
        val formats = listOf(AnkiCardFormat(id = "default", name = "Default"))

        assertNull(ankiFormatDeleteRequest(formats, "default"))
    }
}
