package moe.antimony.hoshi.features.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException

class ProcessExitTraceTest {
    @Test
    fun nativeProtobufShowsAbortSignalAndTheCrashingWorkerStackAfterLargeUnknownFields() {
        val text = nativeTrace(tombstone(unknownBytes = 90_000))

        assertNotNull(text)
        requireNotNull(text)
        assertTrue(text.contains("pid: 123, tid: 456, name: crash-worker"))
        assertTrue(text.contains("moe.antimony.hoshi.debug"))
        assertTrue(text.contains("signal 6 (SIGABRT), code -1 (SI_QUEUE)"))
        assertTrue(text.contains("Abort message: 'Exceeded the maximum indexable cmap coverage.'"))
        assertTrue(text.contains("Cause: font fallback overflow"))
        assertTrue(text.contains("#00 pc 0000000000015154"))
        assertTrue(text.contains("/system/lib64/libminikin.so (FontCollection::init+1348)"))
        assertTrue(text.contains("BuildId: a1b2c3"))
        assertFalse(text.contains("WrongThread"))
        assertFalse(text.contains("unknown-payload"))
    }

    @Test
    fun faultAddressesAndFrameOffsetsRemainUnsignedAndCorrectFor32BitTombstones() {
        val payload = proto(
            number(5, 123), number(6, 456),
            message(10, proto(number(1, 11), string(2, "SIGSEGV"), number(8, 1), number(9, -16))),
            threadEntry(456, proto(
                number(1, 456), string(2, "worker"),
                message(4, proto(number(1, 0x1234), string(6, "base.apk!libexample.so"), number(7, 0xabc))),
            )),
        )

        val text = nativeTrace(payload)

        assertNotNull(text)
        requireNotNull(text)
        assertTrue(text.contains("fault addr 0xfffffffffffffff0"))
        assertTrue(text.contains("#00 pc 00001234"))
        assertTrue(text.contains("base.apk!libexample.so (offset 0xabc)"))
    }

    @Test
    fun missingCrashingThreadPreservesSignalWithoutSubstitutingAnotherThreadsStack() {
        val text = nativeTrace(tombstone(includeCrashingThread = false))

        assertNotNull(text)
        requireNotNull(text)
        assertTrue(text.contains("SIGABRT"))
        assertTrue(text.contains("Exceeded the maximum indexable cmap coverage."))
        assertFalse(text.contains("WrongThread"))
    }

    @Test
    fun malformedOrTruncatedNativePayloadsAreNotExposedAsBinaryText() {
        listOf(
            byteArrayOf(), byteArrayOf(0, 1, 2), ByteArray(12) { 0x80.toByte() },
            tombstone().dropLast(4).toByteArray(),
            proto(number(5, 123), number(6, 456)),
        ).forEach { assertNull(nativeTrace(it)) }
    }

    @Test
    fun nativeCrashPreservesAttachedAnrTextAndTheExistingTextReadLimit() {
        val header = "----- pid 123 at 2026-11-05 20:00:00 -----\nCmd line: moe.antimony.hoshi\n"
        for (text in listOf(header + "\"main\" tid=1 Waiting", header + "x".repeat(8 * 1024 * 1024 + 10))) {
            val input = ClosingInput(text.toByteArray())

            assertEquals(text.take(64_000), readProcessExitTrace(ProcessExitReason.NativeCrash) { input })
            assertTrue(input.closed)
        }
    }

    @Test
    fun textAnrTracesStillRemainReadableAndStreamsAreClosed() {
        val original = "ANR in moe.antimony.hoshi\n\"main\" tid=1 Waiting\n    at android.os.Looper.loop"
        val input = ClosingInput(original.toByteArray())

        assertEquals(original, readProcessExitTrace(ProcessExitReason.Anr) { input })
        assertTrue(input.closed)
        assertNull(readProcessExitTrace(ProcessExitReason.Anr) { null })
        assertNull(readProcessExitTrace(ProcessExitReason.Anr) { ByteArrayInputStream(byteArrayOf(0, 1, 2)) })
    }

    @Test
    fun absentOrUnreadableSystemTracesDoNotPreventLoadingExitHistory() {
        assertNull(readProcessExitTrace(ProcessExitReason.NativeCrash) { null })
        assertNull(readProcessExitTrace(ProcessExitReason.NativeCrash) { throw IOException("tombstone removed") })
        assertNull(readProcessExitTrace(ProcessExitReason.NativeCrash) { throw SecurityException("trace unavailable") })
        val input = object : ByteArrayInputStream(byteArrayOf(1)) {
            var closed = false
            override fun read(b: ByteArray, off: Int, len: Int): Int = throw IOException("read failed")
            override fun close() { closed = true }
        }
        assertNull(readProcessExitTrace(ProcessExitReason.NativeCrash) { input })
        assertTrue(input.closed)
    }

    @Test
    fun oversizedNativeTracesAreBoundedAndClosed() {
        val input = ClosingInput(ByteArray(8 * 1024 * 1024 + 10))

        assertNull(readProcessExitTrace(ProcessExitReason.NativeCrash) { input })
        assertTrue(input.closed)
        assertTrue(input.available() > 0)
    }

    private fun nativeTrace(bytes: ByteArray) =
        readProcessExitTrace(ProcessExitReason.NativeCrash) { ByteArrayInputStream(bytes) }

    // Wire fixtures deliberately use Android's field numbers without the production
    // serializer, so schema/tag mistakes cannot be mirrored in the test encoder.
    private fun tombstone(unknownBytes: Int = 0, includeCrashingThread: Boolean = true): ByteArray = proto(
        number(1, 1), number(5, 123), number(6, 456), string(9, "moe.antimony.hoshi.debug"),
        message(10, proto(number(1, 6), string(2, "SIGABRT"), number(3, -1), string(4, "SI_QUEUE"))),
        string(14, "Exceeded the maximum indexable cmap coverage."),
        message(15, proto(string(1, "font fallback overflow"))),
        message(1001, "unknown-payload".toByteArray() + ByteArray(unknownBytes)),
        threadEntry(123, proto(number(1, 123), string(2, "main"), message(4, proto(string(4, "WrongThread"))))),
        if (includeCrashingThread) threadEntry(456, proto(
            number(1, 456), string(2, "crash-worker"),
            message(4, proto(
                number(1, 0x15154), string(4, "FontCollection::init"), number(5, 1348),
                string(6, "/system/lib64/libminikin.so"), string(8, "a1b2c3"),
            )),
        )) else byteArrayOf(),
    )

    private fun threadEntry(id: Long, thread: ByteArray) = message(16, proto(number(1, id), message(2, thread)))
    private fun proto(vararg fields: ByteArray): ByteArray = fields.fold(byteArrayOf()) { bytes, field -> bytes + field }
    private fun string(tag: Long, value: String) = message(tag, value.toByteArray())
    private fun message(tag: Long, value: ByteArray) = varint(tag * 8 + 2) + varint(value.size.toLong()) + value
    private fun number(tag: Long, value: Long) = varint(tag * 8) + varint(value)
    private fun varint(value: Long): ByteArray {
        val output = ByteArrayOutputStream()
        var remaining = value
        while (remaining and -128L != 0L) {
            output.write((remaining.toInt() and 127) or 128)
            remaining = remaining ushr 7
        }
        output.write(remaining.toInt())
        return output.toByteArray()
    }

    private class ClosingInput(bytes: ByteArray) : ByteArrayInputStream(bytes) {
        var closed = false
        override fun close() { closed = true }
    }
}
