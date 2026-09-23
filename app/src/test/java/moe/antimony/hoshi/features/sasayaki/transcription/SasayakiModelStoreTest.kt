package moe.antimony.hoshi.features.sasayaki.transcription

import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.nio.file.Files
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class SasayakiModelStoreTest {
    private val spec = SasayakiModelFile("model.onnx", "https://example.org/model.onnx", 3,
        "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad")

    @Test fun verifiedModelIsPublishedAtomicallyAndReusedWithoutNetwork() = runBlocking {
        withDirectory { directory ->
            var requests = 0
            val downloadRequests = mutableListOf<Long>()
            val store = SasayakiModelStore(directory, SasayakiModelTransport {
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
            val store = SasayakiModelStore(directory, SasayakiModelTransport {
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
            val store = SasayakiModelStore(directory, SasayakiModelTransport {
                SasayakiDownloadResponse(ByteArrayInputStream("abc".toByteArray()), 3)
            }, Dispatchers.Unconfined, listOf(spec, missing, corrupt))
            store.ensure({ requests += it }) {}
            assertEquals(listOf(6L), requests)
        }
    }

    @Test fun wrongHashDoesNotReplacePreviouslyInstalledFile() = runBlocking {
        withDirectory { directory ->
            val installed = File(directory, spec.name).apply { writeText("old") }
            val store = SasayakiModelStore(directory, SasayakiModelTransport {
                SasayakiDownloadResponse(ByteArrayInputStream("bad".toByteArray()), 3)
            }, Dispatchers.Unconfined, listOf(spec))
            try { store.ensure({}) {}; fail("Expected integrity failure") } catch (_: IOException) { }
            assertEquals("old", installed.readText())
            assertEquals(1, directory.listFiles()!!.size)
        }
    }

    @Test fun cancelledDownloadClosesStreamAndRemovesTemporaryFile() = runBlocking {
        withDirectory { directory ->
            var closed = false
            val store = SasayakiModelStore(directory, SasayakiModelTransport {
                SasayakiDownloadResponse(ByteArrayInputStream("abc".toByteArray()), 3) { closed = true }
            }, Dispatchers.Unconfined, listOf(spec))
            try {
                store.ensure({}) { if (it > 0) throw CancellationException() }
                fail("Expected cancellation")
            } catch (_: CancellationException) { }
            assertTrue(closed)
            assertTrue(directory.listFiles()!!.isEmpty())
        }
    }

    @Test fun interruptedProcessPartialIsRemovedBeforeRetry() = runBlocking {
        withDirectory { directory ->
            File(directory, "model.onnx123.part").writeText("interrupted")
            val store = SasayakiModelStore(directory, SasayakiModelTransport {
                SasayakiDownloadResponse(ByteArrayInputStream("abc".toByteArray()), 3)
            }, Dispatchers.Unconfined, listOf(spec))
            store.ensure({}) {}
            assertEquals(listOf("model.onnx"), directory.listFiles()!!.map { it.name })
        }
    }

    @Test fun nativeFilesAreReadOnlyBeforeWritingAndReusedOffline() = runBlocking {
        withDirectory { directory ->
            val native = spec.copy(name = "runtime.so")
            val store = SasayakiModelStore(directory, SasayakiModelTransport {
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
            val store = SasayakiModelStore(directory, SasayakiModelTransport {
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
}
