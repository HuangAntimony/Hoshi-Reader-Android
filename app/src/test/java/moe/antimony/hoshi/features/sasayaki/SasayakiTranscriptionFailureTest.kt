package moe.antimony.hoshi.features.sasayaki

import java.io.IOException
import moe.antimony.hoshi.R
import moe.antimony.hoshi.ui.UiText
import org.junit.Assert.assertEquals
import org.junit.Test

class SasayakiTranscriptionFailureTest {
    @Test
    fun eachKnownFailureKindHasItsOwnLocalizedMessage() {
        val expected = mapOf(
            SasayakiFailureKind.AudioSource to R.string.sasayaki_transcription_error_audio,
            SasayakiFailureKind.ModelResources to R.string.sasayaki_transcription_error_resources,
            SasayakiFailureKind.Recognition to R.string.sasayaki_transcription_error_recognition,
            SasayakiFailureKind.BookMatch to R.string.sasayaki_transcription_error_match,
            SasayakiFailureKind.Storage to R.string.sasayaki_transcription_error_storage,
        )

        assertEquals(
            expected.mapValues { UiText.Resource(it.value) },
            expected.mapValues { sasayakiFailureText(it.key) },
        )
        assertEquals(expected.size, expected.values.distinct().size)
    }

    @Test
    fun unknownFailureShowsOriginalExceptionAndCause() {
        val error = IllegalStateException("recognizer returned no stream", IOException("unsupported codec"))

        assertEquals(
            UiText.Literal("java.lang.IllegalStateException: recognizer returned no stream\nCaused by: java.io.IOException: unsupported codec"),
            error.toSasayakiFailureText(SasayakiFailureKind.Unknown),
        )
    }

    @Test
    fun unknownOperationFailureShowsOriginalCauseInsteadOfWrapper() {
        val error = SasayakiOperationFailure(
            SasayakiFailureKind.Unknown,
            IllegalStateException("recognizer returned no stream", IOException("unsupported codec")),
        )

        assertEquals(
            UiText.Literal("java.lang.IllegalStateException: recognizer returned no stream\nCaused by: java.io.IOException: unsupported codec"),
            error.toSasayakiFailureText(SasayakiFailureKind.Unknown),
        )
    }

    @Test
    fun unknownFailureWithoutMessageStillShowsExceptionType() {
        assertEquals(
            UiText.Literal("java.lang.IllegalStateException"),
            IllegalStateException().toSasayakiFailureText(SasayakiFailureKind.Unknown),
        )
    }
}
