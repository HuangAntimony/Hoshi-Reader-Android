package moe.antimony.hoshi.features.sasayaki.transcription

import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.ProtocolException
import java.nio.file.Files
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.launch
import kotlinx.coroutines.cancelAndJoin
import moe.antimony.hoshi.R
import moe.antimony.hoshi.features.sasayaki.SasayakiFailureKind
import moe.antimony.hoshi.features.sasayaki.asSasayakiFailure
import moe.antimony.hoshi.features.sasayaki.toSasayakiFailureText
import moe.antimony.hoshi.ui.ErrorDiagnosticEvents
import moe.antimony.hoshi.ui.UiText
import org.junit.Assert.*
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class SasayakiModelStoreTest {
    private val spec = SasayakiModelFile("model.onnx", "https://example.org/model.onnx", 3,
        "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad")

    @Test fun retriesInterruptedTransferWithoutRepeatingConsent() = runTest {
        withDirectory { directory ->
            var requests = 0
            var confirmations = 0
            val store = SasayakiModelStore(directory, SasayakiModelTransport { _, _ ->
                requests++
                SasayakiDownloadResponse(
                    if (requests == 1) interrupted("a") else "abc".byteInputStream(), 3,
                )
            }, Dispatchers.Unconfined, listOf(spec))

            store.ensure({ confirmations++ }) {}

            assertEquals(2, requests)
            assertEquals(1, confirmations)
            assertEquals("abc", File(directory, spec.name).readText())
        }
    }

    @Test fun retriesResumeAtTheSavedOffsetIncludingReadOnlyNativeLibraries() = runTest {
        for (native in listOf(false, true)) withDirectory { directory ->
            val offsets = mutableListOf<Long>()
            var closed = 0
            val store = SasayakiModelStore(directory, SasayakiModelTransport { _, offset ->
                offsets += offset
                if (offset == 0L) SasayakiDownloadResponse(interrupted("a"), 3) { closed++ }
                else SasayakiDownloadResponse("bc".byteInputStream(), 2, offset, 3) { closed++ }
            }, Dispatchers.Unconfined, listOf(spec), readOnly = native)

            store.ensure({}) { fraction ->
                if (native && fraction > 0 && fraction < 1) assertFalse(directory.listFiles()!!.single().canWrite())
            }

            assertEquals(listOf(0L, 1L), offsets)
            assertEquals(2, closed)
            assertEquals("abc", File(directory, spec.name).readText())
            if (native) assertFalse(File(directory, spec.name).canWrite())
        }
    }

    @Test fun aFullyReceivedCheckpointIsVerifiedWithoutRequestingAnUnsatisfiableRange() = runTest {
        withDirectory { directory ->
            var requests = 0
            val store = SasayakiModelStore(directory, SasayakiModelTransport { _, _ ->
                requests++
                SasayakiDownloadResponse(interrupted("abc"), 3)
            }, Dispatchers.Unconfined, listOf(spec))

            store.ensure({}) {}

            assertEquals(1, requests)
            assertEquals("abc", File(directory, spec.name).readText())
        }
    }

    @Test fun aCompleteCheckpointSurvivesCancellationAndIsPublishedOfflineOnReopen() = runTest {
        withDirectory { directory ->
            val store = SasayakiModelStore(directory, SasayakiModelTransport { _, _ ->
                SasayakiDownloadResponse("abc".byteInputStream(), 3)
            }, Dispatchers.Unconfined, listOf(spec))
            try {
                store.ensure({}) { if (it == 1.0) throw CancellationException() }
                fail("Expected cancellation")
            } catch (_: CancellationException) { }
            val reopened = SasayakiModelStore(directory, SasayakiModelTransport { _, _ -> error("Must stay offline") },
                Dispatchers.Unconfined, listOf(spec))
            assertEquals(0L, reopened.missingBytes())
            reopened.ensure({ fail("No consent for a complete verified checkpoint") }) { fail("No download") }
            assertEquals("abc", File(directory, spec.name).readText())
        }
    }

    @Test fun cancellingRetryBackoffDoesNotOpenAnotherConnectionOrRecordAnError() = runTest {
        withDirectory { directory ->
            ErrorDiagnosticEvents.buffer.drain()
            var requests = 0
            val store = SasayakiModelStore(directory, SasayakiModelTransport { _, _ ->
                requests++
                throw IOException("connection reset")
            }, Dispatchers.Unconfined, listOf(spec))
            val job = launch { store.ensure({}) {} }
            runCurrent()
            job.cancelAndJoin()
            assertEquals(1, requests)
            assertTrue(ErrorDiagnosticEvents.buffer.drain().isEmpty())
        }
    }

    @Test fun permanentHttpAndIntegrityFailuresDoNotRetryOrReplaceAnInstalledFile() = runTest {
        for (status in listOf(404, 403)) withDirectory { directory ->
            File(directory, spec.name).writeText("old")
            var requests = 0
            val store = SasayakiModelStore(directory, SasayakiModelTransport { _, _ ->
                requests++
                throw SasayakiHttpException(status, "Not available")
            }, Dispatchers.Unconfined, listOf(spec))
            val failure = try {
                store.ensure({}) {}
                throw AssertionError("Expected HTTP failure")
            } catch (error: SasayakiDownloadFailure) { error }
            assertEquals(1, requests)
            assertEquals(0L, failure.receivedBytes)
            assertEquals(status, (failure.cause as SasayakiHttpException).status)
            assertEquals("old", File(directory, spec.name).readText())
        }
    }

    @Test fun retryableHttpStatusRecoversWithoutLosingItsCheckpoint() = runTest {
        withDirectory { directory ->
            var requests = 0
            val offsets = mutableListOf<Long>()
            val store = SasayakiModelStore(directory, SasayakiModelTransport { _, offset ->
                offsets += offset
                when (++requests) {
                    1 -> SasayakiDownloadResponse(interrupted("a"), 3)
                    2 -> throw SasayakiHttpException(503, "Service Unavailable")
                    else -> SasayakiDownloadResponse("bc".byteInputStream(), 2, offset, 3)
                }
            }, Dispatchers.Unconfined, listOf(spec))
            store.ensure({}) {}
            assertEquals(listOf(0L, 1L, 1L), offsets)
            assertEquals("abc", File(directory, spec.name).readText())
        }
    }

    @Test fun exhaustedRetriesKeepPartialForANewStore() = runTest {
        withDirectory { directory ->
            var requests = 0
            val store = SasayakiModelStore(directory, SasayakiModelTransport { _, _ ->
                requests++
                SasayakiDownloadResponse(interrupted("a"), 3)
            }, Dispatchers.Unconfined, listOf(spec))
            try { store.ensure({}) {}; fail("Expected download failure") } catch (_: IOException) { }

            assertEquals(3, requests)
            assertEquals("a", directory.listFiles()!!.single().readText())
            val reopened = SasayakiModelStore(directory, SasayakiModelTransport { _, offset ->
                assertEquals(1L, offset)
                SasayakiDownloadResponse("bc".byteInputStream(), 2, offset, 3)
            }, Dispatchers.Unconfined, listOf(spec))
            val consent = mutableListOf<Long>()
            reopened.ensure({ consent += it }) {}
            assertEquals(listOf(3L), consent)
            assertEquals("abc", File(directory, spec.name).readText())
        }
    }

    @Test fun failedDownloadContextAndOriginalCauseReachVisibleErrorAndDiagnosticHistory() = runTest {
        ErrorDiagnosticEvents.buffer.drain()
        withDirectory { directory ->
            val cause = IOException("peer closed connection")
            val original = ProtocolException("unexpected end of stream").apply { initCause(cause) }
            val store = SasayakiModelStore(directory, SasayakiModelTransport { _, _ ->
                SasayakiDownloadResponse(interrupted("a", original), 3)
            }, Dispatchers.Unconfined, listOf(spec))
            val failure = try {
                store.ensure({}) {}
                throw AssertionError("Expected download failure")
            } catch (error: IOException) { error }

            val visible = failure.asSasayakiFailure(SasayakiFailureKind.ModelResources)
                .toSasayakiFailureText(SasayakiFailureKind.Unknown) as UiText.Joined
            val details = (visible.parts.last() as UiText.Literal).value
            val operation = UiText.Joined(listOf(UiText.Resource(R.string.sasayaki_transcription_error_resources),
                UiText.Resource(R.string.sasayaki_transcription_download_details, spec.name, spec.url, 1L, 3L, 3)))
            assertEquals(operation, visible.parts.first())
            assertTrue(details, details.contains("unexpected end of stream"))
            assertTrue(details, details.contains("peer closed connection"))
            assertSame(original, failure.cause)
            val recorded = ErrorDiagnosticEvents.buffer.drain().single()
            assertEquals(operation, recorded.operation)
            assertSame(failure, recorded.error)
            assertTrue(recorded.error!!.stackTraceToString().contains("peer closed connection"))
        }
    }

    @Test fun verifiedModelIsPublishedAtomicallyAndReusedWithoutNetwork() = runBlocking {
        withDirectory { directory ->
            var requests = 0
            val downloadRequests = mutableListOf<Long>()
            val store = SasayakiModelStore(directory, SasayakiModelTransport { _, _ ->
                assertEquals(listOf(3L), downloadRequests)
                requests++
                SasayakiDownloadResponse(ByteArrayInputStream("abc".toByteArray()), 3)
            }, Dispatchers.Unconfined, listOf(spec))
            store.ensure({ downloadRequests += it }) { progress ->
                if (progress < 1.0) assertFalse(File(directory, spec.name).exists())
            }
            val cachedProgress = mutableListOf<Double>()
            store.ensure({ fail("Cached models must not ask to download") }) { cachedProgress += it }
            assertEquals(1, requests)
            assertTrue("Cached models must not report a download", cachedProgress.isEmpty())
            assertEquals("abc", File(directory, spec.name).readText())
            assertEquals(listOf(spec.name), directory.listFiles()!!.map { it.name })
        }
    }

    @Test fun decliningDownloadDoesNotOpenNetworkOrCreateModelFiles() = runBlocking {
        withDirectory { directory ->
            val store = SasayakiModelStore(directory, SasayakiModelTransport { _, _ ->
                fail("Network must wait for confirmation")
                error("Unexpected download")
            }, Dispatchers.Unconfined, listOf(spec))
            try {
                store.ensure({ throw CancellationException() }) { fail("No download progress before confirmation") }
                fail("Expected cancellation")
            } catch (_: CancellationException) { }
            assertTrue(directory.listFiles()!!.isEmpty())
        }
    }

    @Test fun downloadRequestCountsOnlyMissingOrInvalidFiles() = runBlocking {
        withDirectory { directory ->
            File(directory, spec.name).writeText("abc")
            val missing = spec.copy(name = "missing.onnx")
            val corrupt = spec.copy(name = "corrupt.onnx")
            File(directory, corrupt.name).writeText("bad")
            val requests = mutableListOf<Long>()
            val store = SasayakiModelStore(directory, SasayakiModelTransport { _, _ ->
                SasayakiDownloadResponse(ByteArrayInputStream("abc".toByteArray()), 3)
            }, Dispatchers.Unconfined, listOf(spec, missing, corrupt))
            store.ensure({ requests += it }) {}
            assertEquals(listOf(6L), requests)
        }
    }

    @Test fun wrongHashDoesNotReplacePreviouslyInstalledFile() = runBlocking {
        withDirectory { directory ->
            val installed = File(directory, spec.name).apply { writeText("old") }
            val store = SasayakiModelStore(directory, SasayakiModelTransport { _, _ ->
                SasayakiDownloadResponse(ByteArrayInputStream("bad".toByteArray()), 3)
            }, Dispatchers.Unconfined, listOf(spec))
            try { store.ensure({}) {}; fail("Expected integrity failure") } catch (_: IOException) { }
            assertEquals("old", installed.readText())
            assertEquals(1, directory.listFiles()!!.size)
        }
    }

    @Test fun cancelledDownloadClosesStreamAndKeepsCheckpointWithoutRecordingAnError() = runBlocking {
        withDirectory { directory ->
            ErrorDiagnosticEvents.buffer.drain()
            var closed = false
            val store = SasayakiModelStore(directory, SasayakiModelTransport { _, _ ->
                SasayakiDownloadResponse(interrupted("a"), 3) { closed = true }
            }, Dispatchers.Unconfined, listOf(spec))
            try {
                store.ensure({}) { if (it > 0) throw CancellationException() }
                fail("Expected cancellation")
            } catch (_: CancellationException) { }
            assertTrue(closed)
            assertEquals("a", directory.listFiles()!!.single().readText())
            assertTrue(ErrorDiagnosticEvents.buffer.drain().isEmpty())
        }
    }

    @Test fun interruptedProcessPartialIsRemovedBeforeRetry() = runBlocking {
        withDirectory { directory ->
            File(directory, "model.onnx123.part").writeText("interrupted")
            val store = SasayakiModelStore(directory, SasayakiModelTransport { _, _ ->
                SasayakiDownloadResponse(ByteArrayInputStream("abc".toByteArray()), 3)
            }, Dispatchers.Unconfined, listOf(spec))
            store.ensure({}) {}
            assertEquals(listOf("model.onnx"), directory.listFiles()!!.map { it.name })
        }
    }

    @Test fun nativeFilesAreReadOnlyBeforeWritingAndReusedOffline() = runBlocking {
        withDirectory { directory ->
            val native = spec.copy(name = "runtime.so")
            val store = SasayakiModelStore(directory, SasayakiModelTransport { _, _ ->
                SasayakiDownloadResponse(ByteArrayInputStream("abc".toByteArray()), 3)
            }, Dispatchers.Unconfined, listOf(native), readOnly = true)
            assertEquals(3L, store.missingBytes())
            store.ensure({ assertEquals(3L, it) }) { progress ->
                if (progress > 0 && progress < 1) {
                    assertFalse(directory.listFiles()!!.single().canWrite())
                }
            }
            assertFalse(File(directory, native.name).canWrite())
            assertEquals(0L, store.missingBytes())
            store.ensure({ fail("Native cache should be reused") }) { fail("No download") }
        }
    }

    @Test fun progressCountsOnlyTheBytesBeingDownloaded() = runBlocking {
        withDirectory { directory ->
            File(directory, spec.name).writeText("abc")
            val store = SasayakiModelStore(directory, SasayakiModelTransport { _, _ ->
                SasayakiDownloadResponse(ByteArrayInputStream("abc".toByteArray()), 3)
            }, Dispatchers.Unconfined, listOf(spec, spec.copy(name = "missing.onnx")))
            val progress = mutableListOf<Double>()
            assertEquals(3L, store.missingBytes())
            store.ensure({}) { progress += it }
            assertEquals(0.0, progress.first(), 0.0)
            assertEquals(1.0, progress.last(), 0.0)
        }
    }

    private suspend fun withDirectory(block: suspend (File) -> Unit) {
        val directory = Files.createTempDirectory("sasayaki-model-test").toFile()
        try { block(directory) } finally { directory.deleteRecursively() }
    }

    private fun interrupted(prefix: String, error: IOException = ProtocolException("unexpected end of stream")) =
        object : InputStream() {
            private val input = prefix.byteInputStream()
            override fun read(): Int = input.read().also { if (it < 0) throw error }
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
                input.read(buffer, offset, length).also { if (it < 0) throw error }
        }
}
