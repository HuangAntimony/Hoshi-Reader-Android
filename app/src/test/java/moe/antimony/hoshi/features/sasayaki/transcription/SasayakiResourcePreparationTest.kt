package moe.antimony.hoshi.features.sasayaki.transcription

import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class SasayakiResourcePreparationTest {
    @Test fun confirmsOnceBeforeEitherDownloadAndReportsCombinedProgress() = runBlocking<Unit> {
        val root = Files.createTempDirectory("sasayaki-resources").toFile()
        try {
            val requests = mutableListOf<Long>()
            val progress = mutableListOf<Double>()
            val stores = listOf("runtime", "model").map { name ->
                SasayakiModelStore(File(root, name), SasayakiModelTransport {
                    assertEquals(listOf(6L), requests)
                    SasayakiDownloadResponse("abc".byteInputStream(), 3)
                }, Dispatchers.Unconfined, listOf(SasayakiModelFile(name, "https://example.org/$name", 3,
                    "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad")))
            }
            prepareSasayakiResources(stores, { requests += it }, { progress += it })
            assertEquals(0.0, progress.first(), 0.0)
            assertTrue(progress.contains(0.5))
            assertEquals(1.0, progress.last(), 0.0)
            assertTrue(progress.zipWithNext().all { (a, b) -> a <= b })
            prepareSasayakiResources(stores, { fail("Cached resources must not ask") }, { fail("No downloading") })
        } finally { root.deleteRecursively() }
    }

    @Test fun decliningCombinedDownloadNeverOpensNetwork() = runBlocking<Unit> {
        val root = Files.createTempDirectory("sasayaki-resources").toFile()
        try {
            val store = SasayakiModelStore(root, SasayakiModelTransport { error("Network before consent") },
                Dispatchers.Unconfined, listOf(SasayakiModelFile("runtime", "https://example.org", 3, "unused")))
            try {
                prepareSasayakiResources(listOf(store), { throw CancellationException() }, {})
                fail("Should cancel")
            } catch (_: CancellationException) { }
            assertTrue(root.listFiles()!!.isEmpty())
        } finally { root.deleteRecursively() }
    }
}
