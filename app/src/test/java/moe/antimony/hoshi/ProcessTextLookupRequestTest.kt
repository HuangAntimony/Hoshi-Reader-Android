package moe.antimony.hoshi

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ProcessTextLookupRequestTest {
    @Test
    fun createsRequestForNonBlankProcessTextSelection() {
        val request = ProcessTextLookupRequest.from(
            action = "android.intent.action.PROCESS_TEXT",
            selectedText = " 食べる ",
        )

        assertEquals("食べる", request?.query)
    }

    @Test
    fun ignoresBlankSelectionsAndOtherActions() {
        assertNull(
            ProcessTextLookupRequest.from(
                action = "android.intent.action.PROCESS_TEXT",
                selectedText = "   ",
            ),
        )
        assertNull(
            ProcessTextLookupRequest.from(
                action = "android.intent.action.VIEW",
                selectedText = "食べる",
            ),
        )
    }
}
