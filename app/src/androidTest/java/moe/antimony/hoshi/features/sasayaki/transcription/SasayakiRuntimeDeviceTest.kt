package moe.antimony.hoshi.features.sasayaki.transcription

import android.os.Build
import android.os.Process
import androidx.test.platform.app.InstrumentationRegistry
import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig
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
        val transport = SasayakiModelTransport { url ->
            assertTrue("Network/stream access requires consent", confirmed)
            requests++
            if (payloads == null) HttpSasayakiModelTransport().open(url) else {
                val file = File(payloads, url.substringAfterLast('/'))
                SasayakiDownloadResponse(file.inputStream(), file.length())
            }
        }
        try {
            val store = SasayakiModelStore(directory, transport, Dispatchers.IO, specs, readOnly = true)
            store.ensure({ assertEquals(specs.sumOf { it.bytes }, it); confirmed = true }) {}
            assertEquals(3, requests)
            assertTrue(specs.all { !File(directory, it.name).canWrite() })
            val offline = SasayakiModelStore(directory, SasayakiModelTransport { error("Offline cache opened network") },
                Dispatchers.IO, specs, readOnly = true)
            offline.ensure({ fail("Cached runtime asks to download") }) { fail("Cached runtime shows downloading") }
            SasayakiRuntimeRepository(context, Dispatchers.IO).load(directory)
            val model = File(context.noBackupFilesDir, "SasayakiModels/reazonspeech-k2-v2-int8-v1/silero_vad.onnx")
            assertTrue("Seed verified VAD model for native execution", model.isFile)
            val vad = Vad(config = VadModelConfig(sileroVadModelConfig = SileroVadModelConfig(model = model.absolutePath)))
            try { vad.acceptWaveform(FloatArray(512)); assertTrue(vad.empty()) } finally { vad.release() }
        } finally { root.deleteRecursively() }
    }
}
