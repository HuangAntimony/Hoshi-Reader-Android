package moe.antimony.hoshi.features.tts

import android.speech.tts.TextToSpeech
import moe.antimony.hoshi.R
import moe.antimony.hoshi.ui.UiText
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class AndroidTtsEngineTest {
    @Test fun terminalAutomaticEngineSelectionKeepsAllOriginalInitializationFailures() = runTest {
        val error = runCatching {
            resolveAutomaticTtsEngine(listOf("first.engine", "second.engine"), { id ->
                throw ttsPlatformError("onInit", if (id == "first.engine") -1 else -7, TtsFailure.EngineUnavailable)
            }, { true })
        }.exceptionOrNull()!!.toTtsError(TtsFailure.EngineUnavailable)

        assertEquals(TtsFailure.MissingJapanese, error.failure)
        assertEquals(UiText.Joined(listOf(UiText.Resource(R.string.tts_missing_japanese),
            UiText.Literal("first.engine: TextToSpeech.onInit: -1\nsecond.engine: TextToSpeech.onInit: -7"))), error.message)
    }

    @Test fun successfulAutomaticFallbackRemainsUsableAfterAnEarlierEngineFailure() = runTest {
        val selected = resolveAutomaticTtsEngine(listOf("unavailable", "working"), { id ->
            if (id == "unavailable") throw ttsPlatformError("onInit", -1, TtsFailure.EngineUnavailable)
            id
        }, { true })

        assertEquals("working" to "working", selected)
    }

    @Test fun platformCallbackAndOperationCodesSurviveTheVisibleErrorMapping() {
        val callback = ttsPlatformError("onError", TextToSpeech.ERROR_NETWORK, TtsFailure.Synthesis)
            .toTtsError(TtsFailure.Synthesis)
        assertEquals(TtsFailure.Synthesis, callback.failure)
        assertEquals(UiText.Joined(listOf(UiText.Resource(R.string.tts_failed), UiText.Literal("TextToSpeech.onError: -6"))), callback.message)

        val initialization = ttsPlatformError("onInit", TextToSpeech.ERROR, TtsFailure.EngineUnavailable)
            .toTtsError(TtsFailure.Synthesis)
        assertEquals(TtsFailure.EngineUnavailable, initialization.failure)
        assertEquals(UiText.Joined(listOf(UiText.Resource(R.string.tts_engine_unavailable), UiText.Literal("TextToSpeech.onInit: -1"))), initialization.message)

        val queued = ttsPlatformError("synthesizeToFile", -10123, TtsFailure.Synthesis)
            .toTtsError(TtsFailure.Synthesis)
        assertEquals(UiText.Joined(listOf(UiText.Resource(R.string.tts_failed), UiText.Literal("TextToSpeech.synthesizeToFile: -10123"))), queued.message)
    }

    @Test fun classificationWithoutAnOriginalDiagnosticDoesNotInventAnErrorCode() {
        val error = TtsException(TtsFailure.Synthesis).toTtsError(TtsFailure.Network)

        assertEquals(TtsFailure.Synthesis, error.failure)
        assertEquals(UiText.Resource(R.string.tts_failed), error.message)
    }

    @get:Rule val folder = TemporaryFolder()
    private val settings = TtsSettings(engine = "device.one")

    @Test fun cachedDialogueAudioReturnsWhileLaterSystemSynthesisIsPending() = runTest {
        val backend = DeviceEngine()
        val engine = AndroidTtsEngine(backend)
        val dispatcher = StandardTestDispatcher(testScheduler)
        val transport = OnlineTtsTransport()
        val router = TtsEngineRouter(engine,
            OnlineTtsEngine(transport, { _, _ -> error("Unexpected cloud source") }, dispatcher),
            CustomTtsEngine(transport, { _, _ -> error("Unexpected custom source") }, dispatcher))
        val audio = TtsAudioRepository(router, folder.root, dispatcher, TtsExportFallback { _, _ -> error("Unexpected transcoding") })
        val preferences = TtsPreferences(settings.withRouting(TtsVoiceRouting(true, "narration", "dialogue")))
        val sentence = TtsText.chapter(0, "<p>彼は「はい」と言った。</p>").single()
        val later = TtsSentence(0, 8, 3, "後です。")
        val files = audio.audio(sentence, "book", preferences)
        val released = CompletableDeferred<Unit>()
        backend.synthesisGate = released
        val prefetch = async { audio.audio(later, "book", preferences) }
        try {
            runCurrent()
            assertTrue(backend.synthesisStarted.isCompleted)
            val ready = async { audio.audio(sentence, "book", preferences) }
            runCurrent()
            assertTrue("Cached audio must not wait for another sentence's system synthesis", ready.isCompleted)
            assertEquals(files, ready.await())
            assertTrue(files.all { it.isFile })
            assertFalse(prefetch.isCompleted)
            assertEquals(1, backend.discoveries)
        } finally {
            released.complete(Unit)
            prefetch.cancelAndJoin()
            audio.clearPlaybackCache()
        }
    }

    @Test fun catalogsReuseEngineSelectionAcrossVoiceAndRateChanges() = runTest {
        val backend = DeviceEngine()
        val engine = AndroidTtsEngine(backend)
        engine.catalog(settings)
        engine.catalog(settings.withVoice("dialogue").withRate(2f))
        assertEquals(1, backend.discoveries)
        assertEquals("device.two", engine.catalog(settings.copy(engine = "device.two")).selectedEngine)
        assertEquals("system.default", engine.catalog(TtsSettings()).selectedEngine)
        assertEquals("device.one", engine.catalog(settings).selectedEngine)
        assertEquals(3, backend.discoveries)
    }

    @Test fun concurrentColdCatalogRequestsShareDiscovery() = runTest {
        val backend = DeviceEngine()
        val engine = AndroidTtsEngine(backend)
        val gate = CompletableDeferred<Unit>()
        backend.discoveryGate = gate
        val first = async { engine.catalog(settings) }
        val second = async { engine.catalog(settings.withVoice("dialogue")) }
        runCurrent()
        gate.complete(Unit)
        assertEquals("narration", first.await().voices.first().id)
        assertEquals("narration", second.await().voices.first().id)
        assertEquals(1, backend.discoveries)
    }

    @Test fun refreshKeepsCachedChoicesReadableThenReplacesThem() = runTest {
        val backend = DeviceEngine()
        val engine = AndroidTtsEngine(backend)
        engine.catalog(settings)
        backend.voices = listOf(TtsVoiceInfo("new.voice", false))
        val gate = CompletableDeferred<Unit>()
        backend.discoveryGate = gate
        val refresh = async { engine.catalog(settings, force = true) }
        try {
            runCurrent()
            assertEquals("narration", engine.cachedCatalog(settings)?.voices?.first()?.id)
            val ready = async { engine.catalog(settings) }
            runCurrent()
            assertTrue(ready.isCompleted)
            assertEquals("narration", ready.await().voices.first().id)
            gate.complete(Unit)
            assertEquals("new.voice", refresh.await().voices.single().id)
            assertEquals("new.voice", engine.cachedCatalog(settings)?.voices?.single()?.id)
            assertEquals(2, backend.discoveries)
        } finally { gate.complete(Unit); refresh.cancelAndJoin() }
    }

    @Test fun failedOrCancelledRefreshPreservesTheLastCatalogAndRetryUpdatesIt() = runTest {
        val backend = DeviceEngine()
        val engine = AndroidTtsEngine(backend)
        engine.catalog(settings)
        backend.failure = TtsFailure.EngineUnavailable
        try { engine.catalog(settings, force = true); fail("Expected discovery failure") }
        catch (error: TtsException) { assertEquals(TtsFailure.EngineUnavailable, error.failure) }
        assertEquals("narration", engine.cachedCatalog(settings)?.voices?.first()?.id)
        backend.failure = null
        backend.discoveryGate = CompletableDeferred()
        val refresh = async { engine.catalog(settings, force = true) }
        runCurrent()
        refresh.cancelAndJoin()
        assertEquals("narration", engine.cachedCatalog(settings)?.voices?.first()?.id)
        backend.discoveryGate = null
        backend.voices = listOf(TtsVoiceInfo("installed.voice", false))
        assertEquals("installed.voice", engine.catalog(settings, force = true).voices.single().id)
    }

    private class DeviceEngine : TtsDeviceEngine {
        private val serial = Mutex()
        var voices = listOf(TtsVoiceInfo("narration", false), TtsVoiceInfo("dialogue", false))
        var discoveries = 0
        var discoveryGate: CompletableDeferred<Unit>? = null
        var synthesisGate: CompletableDeferred<Unit>? = null
        val synthesisStarted = CompletableDeferred<Unit>()
        var failure: TtsFailure? = null

        override suspend fun installedEngines() = listOf(TtsEngineInfo("device.one", "Device one"), TtsEngineInfo("device.two", "Device two"))
        override suspend fun catalog(settings: TtsSettings): TtsCatalog = serial.withLock {
            discoveries++
            discoveryGate?.await()
            failure?.let { throw TtsException(it) }
            TtsCatalog(settings.engine.ifBlank { "system.default" }, voices)
        }
        override suspend fun synthesize(text: String, settings: TtsSettings, directory: File): List<File> = serial.withLock {
            synthesisGate?.let { synthesisStarted.complete(Unit); it.await() }
            directory.mkdirs()
            val wav = ByteBuffer.allocate(48).order(ByteOrder.LITTLE_ENDIAN).apply {
                put("RIFF".toByteArray()); putInt(40); put("WAVEfmt ".toByteArray()); putInt(16)
                putShort(1); putShort(1); putInt(24000); putInt(48000); putShort(2); putShort(16)
                put("data".toByteArray()); putInt(4); putInt(0)
            }.array()
            listOf(File.createTempFile("device-", ".wav", directory).apply { writeBytes(wav) })
        }
    }
}
