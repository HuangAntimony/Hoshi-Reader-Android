package moe.antimony.hoshi.features.tts

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class TtsCredentialDraftTest {
    @Test fun emptyDraftPreservesSavedCiphertextAndRemovalWinsOverNewInput() = runTest {
        val encrypt: suspend (String) -> String = { error("Must not re-encrypt saved credentials") }
        assertEquals("saved", resolveTtsCredential("  ", "saved", false, encrypt))
        assertEquals("", resolveTtsCredential("new-key", "saved", true, encrypt))
    }
    @Test fun newKeysAreTrimmedAndEncryptedOnce() = runTest {
        val inputs = mutableListOf<String>()
        assertEquals("encrypted", resolveTtsCredential("  secret-token\n", "saved", false) {
            inputs += it; "encrypted"
        })
        assertEquals(listOf("secret-token"), inputs)
    }
    @Test fun invalidHeaderValuesAreRejectedBeforeEncryption() = runTest {
        for (value in listOf("key\nheader", "two words", "密钥", "key\u007f")) {
            try {
                resolveTtsCredential(value, "saved", false) { error("Must reject first") }
                fail("Expected configuration failure")
            } catch (error: TtsException) { assertEquals(TtsFailure.Configuration, error.failure) }
        }
    }
    @Test fun cancellationIsNotConvertedIntoAConfigurationError() = runTest {
        try {
            resolveTtsCredential("key", "saved", false) { throw CancellationException() }
            fail("Expected cancellation")
        } catch (_: CancellationException) { }
    }
}
