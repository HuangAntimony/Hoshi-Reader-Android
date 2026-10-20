package moe.antimony.hoshi.features.sasayaki.transcription

import android.os.Build
import android.os.Process
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

class SasayakiRuntimeDeviceTest {
    @Test fun installRuntimeFromPublishedUrls() = runBlocking<Unit> {
        assumeTrue("Opt in to downloading verified runtime files", InstrumentationRegistry.getArguments().getString("downloadRuntime") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val repository = SasayakiRuntimeRepository(context, Dispatchers.IO)
        val store = repository.store()
        val directory = store.ensure({ assertTrue(it > 0) }) {}
        repository.load(directory)
        assertEquals(0L, store.missingBytes())
        store.ensure({ fail("Cached components must not ask again") }) { fail("Cached components must not report download progress") }
    }

    @Test fun verifiedPrivateLibrariesLoadAndCachedFilesWorkOffline() = runBlocking<Unit> {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val payloads = InstrumentationRegistry.getArguments().getString("runtimePayloads")
        val network = InstrumentationRegistry.getArguments().getString("downloadRuntime") == "true"
        assumeTrue("Explicitly provide scratch payloads or allow runtime download", payloads != null || network)
        val catalog = transcriptionRuntimeCatalog(context)
        val abis = if (Process.is64Bit()) Build.SUPPORTED_64_BIT_ABIS else Build.SUPPORTED_32_BIT_ABIS
        val abi = abis.first { it in catalog }
        val specs = catalog.getValue(abi)
        val root = File(context.cacheDir, "runtime-device-test").apply { mkdirs() }
        val directory = File(root, abi)
        var confirmed = false
        var requests = 0
        val transport = SasayakiModelTransport { url, offset ->
            assertTrue("Network/stream access requires consent", confirmed)
            requests++
            if (payloads == null) HttpSasayakiModelTransport().open(url, offset) else {
                val file = File(payloads, url.substringAfterLast('/'))
                SasayakiDownloadResponse(file.inputStream(), file.length())
            }
        }
        try {
            val store = SasayakiModelStore(directory, transport, Dispatchers.IO, specs, readOnly = true)
            store.ensure({ assertEquals(specs.sumOf { it.bytes }, it); confirmed = true }) {}
            assertEquals(1, requests)
            assertTrue(specs.all { !File(directory, it.name).canWrite() })
            val offline = SasayakiModelStore(directory, SasayakiModelTransport { _, _ -> error("Offline cache opened network") },
                Dispatchers.IO, specs, readOnly = true)
            offline.ensure({ fail("Cached runtime asks to download") }) { fail("Cached runtime shows downloading") }
            SasayakiRuntimeRepository(context, Dispatchers.IO).load(directory)
        } finally { root.deleteRecursively() }
    }
}
