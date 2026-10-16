package moe.antimony.hoshi.features.sasayaki

import java.io.IOException
import moe.antimony.hoshi.R
import moe.antimony.hoshi.ui.UiText
import org.junit.Assert.assertEquals
import org.junit.Test

class SasayakiTranscriptionFailureTest {
    @Test
    fun classifiedFailureKeepsItsCategoryAndUnderlyingDiagnostic() {
        val error = IOException("model download failed", IOException("HTTP 503 Service Unavailable"))
            .asSasayakiFailure(SasayakiFailureKind.ModelResources)

        assertEquals(
            UiText.Joined(listOf(UiText.Resource(R.string.sasayaki_transcription_error_resources), UiText.Literal("model download failed\nHTTP 503 Service Unavailable"))),
            error.toSasayakiFailureText(SasayakiFailureKind.Unknown),
        )
    }

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
            UiText.Joined(listOf(UiText.Resource(R.string.sasayaki_transcription_error_unknown), UiText.Literal("recognizer returned no stream\nunsupported codec"))),
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
            UiText.Joined(listOf(UiText.Resource(R.string.sasayaki_transcription_error_unknown), UiText.Literal("recognizer returned no stream\nunsupported codec"))),
            error.toSasayakiFailureText(SasayakiFailureKind.Unknown),
        )
    }

    @Test
    fun unknownFailureWithoutMessageStillShowsExceptionType() {
        assertEquals(
            UiText.Joined(listOf(UiText.Resource(R.string.sasayaki_transcription_error_unknown), UiText.Literal("IllegalStateException"))),
            IllegalStateException().toSasayakiFailureText(SasayakiFailureKind.Unknown),
        )
    }
}
