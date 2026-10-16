package moe.antimony.hoshi.ui

import java.io.IOException
import kotlinx.coroutines.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ErrorDetailsTest {
    @Test
    fun visibleErrorAdaptersPublishOriginalThrowableButPureDiagnosticDoesNot() {
        ErrorDiagnosticEvents.buffer.drain()
        val error = IOException("Provider unavailable", IllegalStateException("Access revoked"))
        error.diagnosticMessage()
        assertEquals(0, ErrorDiagnosticEvents.buffer.drain().size)

        UiText.Resource(17).withErrorDetails(error)
        val uiEvent = ErrorDiagnosticEvents.buffer.drain().single()
        org.junit.Assert.assertSame(error, uiEvent.error)
        assertEquals(UiText.Resource(17), uiEvent.operation)

        val retryError = IOException("Retry failed")
        "Save failed".withErrorDetails(retryError)
        val stringEvent = ErrorDiagnosticEvents.buffer.drain().single()
        org.junit.Assert.assertSame(retryError, stringEvent.error)
        assertEquals(UiText.Literal("Save failed"), stringEvent.operation)
    }

    @Test
    fun keepsOperationContextAndEveryDistinctCause() {
        val error = IOException("Unable to read book", IllegalArgumentException("Malformed XML"))

        val text = UiText.Resource(1).withErrorDetails(error)

        assertEquals(
            "Failed to import EPUB.\nUnable to read book\nMalformed XML",
            text.resolve(getString = { _, _ -> "Failed to import EPUB." }, getQuantityString = { _, _, _ -> error("Unexpected plural") }),
        )
    }

    @Test
    fun stringErrorsKeepContextAndUnderlyingCause() {
        assertEquals(
            "Save failed\nDisk full",
            "Save failed".withErrorDetails(IOException("Disk full")),
        )
        assertEquals("Disk full", "Disk full".withErrorDetails(IOException("Disk full")))
    }

    @Test
    fun blankMessagesStillIdentifyExceptionTypeAndCause() {
        val error = IOException("  ", IllegalArgumentException("Broken metadata"))

        assertEquals("IOException\nBroken metadata", error.diagnosticMessage())
        assertEquals("IOException", IOException().diagnosticMessage())
    }

    @Test
    fun duplicateMessagesAndCyclicCauseChainsTerminateWithoutDroppingDistinctReason() {
        val first = IOException("Read failed")
        val second = IOException("Read failed")
        val third = IOException("Invalid archive")
        first.initCause(second)
        second.initCause(third)
        third.initCause(first)

        assertEquals("Read failed\nInvalid archive", first.diagnosticMessage())
    }

    @Test
    fun cancellationIsNeverConvertedIntoAnErrorMessage() {
        assertThrows(CancellationException::class.java) {
            UiText.Resource(1).withErrorDetails(CancellationException("User left"))
        }
    }
}
