package moe.antimony.hoshi.features.dictionary

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PendingDictionaryLookupRequestTest {
    @Test
    fun internalLookupActionCreatesRequestWithProvidedIdentity() {
        val request = PendingDictionaryLookupRequest.from(
            action = "moe.antimony.hoshi.action.OPEN_DICTIONARY_LOOKUP",
            query = " 猫 ",
            requestId = 7L,
        )

        assertEquals(" 猫 ", request?.query)
        assertEquals(7L, request?.requestId)
    }

    @Test
    fun repeatedQueryUsesNewRequestIdentity() {
        val first = PendingDictionaryLookupRequest.from(
            action = "moe.antimony.hoshi.action.OPEN_DICTIONARY_LOOKUP",
            query = "猫",
            requestId = 7L,
        )
        val second = PendingDictionaryLookupRequest.from(
            action = "moe.antimony.hoshi.action.OPEN_DICTIONARY_LOOKUP",
            query = "猫",
            requestId = 8L,
        )

        assertNotEquals(first, second)
    }

    @Test
    fun wrongActionDoesNotCreatePendingLookup() {
        assertNull(
            PendingDictionaryLookupRequest.from(
                action = "android.intent.action.VIEW",
                query = "猫",
                requestId = 1L,
            ),
        )
    }
}
