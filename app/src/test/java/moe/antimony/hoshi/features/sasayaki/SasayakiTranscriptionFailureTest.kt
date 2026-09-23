package moe.antimony.hoshi.features.sasayaki

import moe.antimony.hoshi.R
import moe.antimony.hoshi.ui.UiText
import org.junit.Assert.assertEquals
import org.junit.Test

class SasayakiTranscriptionFailureTest {
    @Test
    fun eachFailureKindHasItsOwnLocalizedMessage() {
        val expected = mapOf(
            SasayakiFailureKind.AudioSource to R.string.sasayaki_transcription_error_audio,
            SasayakiFailureKind.ModelResources to R.string.sasayaki_transcription_error_resources,
            SasayakiFailureKind.Recognition to R.string.sasayaki_transcription_error_recognition,
            SasayakiFailureKind.BookMatch to R.string.sasayaki_transcription_error_match,
            SasayakiFailureKind.Storage to R.string.sasayaki_transcription_error_storage,
            SasayakiFailureKind.Unknown to R.string.sasayaki_transcription_failed,
        )

        assertEquals(
            expected.mapValues { UiText.Resource(it.value) },
            expected.mapValues { sasayakiFailureText(it.key) },
        )
        assertEquals(expected.size, expected.values.distinct().size)
    }
}
