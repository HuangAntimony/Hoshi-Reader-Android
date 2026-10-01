package moe.antimony.hoshi.features.tts

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.preferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import java.io.File
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import moe.antimony.hoshi.epub.EpubBook
import moe.antimony.hoshi.epub.EpubChapter
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class TtsPlaybackControllerTest {
    private class MemoryStore(initial: Preferences = emptyPreferences()) : DataStore<Preferences> {
        override val data = MutableStateFlow(initial)
        override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences = transform(data.value).also { data.value = it }
    }
    private class Output : TtsPlaybackOutput {
        override var onEnded: () -> Unit = {}
        override var onPaused: () -> Unit = {}
        override var onError: () -> Unit = {}
        var playCount = 0
        var prepared = emptyList<File>()
        override suspend fun connect() = Unit
        override fun prepare(files: List<File>, title: String, sentence: String) { prepared = files }
        override fun play() { playCount++ }
        override fun pause() = Unit
        override fun clear() { prepared = emptyList() }
        override fun disconnect() = Unit
    }
    private class Audio : TtsAudioSource {
        val requests = mutableListOf<String>()
        var synthesize: suspend (TtsSentence) -> Unit = {}
        override suspend fun audio(sentence: TtsSentence, bookId: String?, preferences: TtsPreferences): List<File> {
            requests += TtsText.spoken(sentence, bookId, preferences.words)
            synthesize(sentence)
            return listOf(File("${sentence.start}.wav"))
        }
        override suspend fun clearPlaybackCache() = Unit
    }
    private val book = EpubBook("Fixture", chapters = listOf(EpubChapter("one", "one.xhtml", "application/xhtml+xml", "<p>東京です。次です。三つです。四つです。</p>")))
    private suspend fun TestScope.fixture(store: MemoryStore = MemoryStore(), fixtureBook: EpubBook = book, body: suspend (TtsPlaybackController, Output, Audio, TtsRepository) -> Unit) {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        val fixtureScope = CoroutineScope(backgroundScope.coroutineContext + SupervisorJob(backgroundScope.coroutineContext[Job]))
        try {
            val repository = TtsRepository(store)
            val output = Output()
            val audio = Audio()
            val controller = TtsPlaybackController(TtsBookRepository(UnconfinedTestDispatcher(testScheduler)), repository, audio, output, fixtureScope)
            controller.load("book", fixtureBook, false)
            withTimeout(5_000) { controller.state.first { it.sentences.isNotEmpty() } }
            body(controller, output, audio, repository)
            controller.stop()
        } finally { fixtureScope.cancel(); runCurrent(); Dispatchers.resetMain() }
    }
    private val preferencesKey = stringPreferencesKey("preferences")
    private fun prefetchPreferences(count: Int) = preferencesOf(preferencesKey to """{"settings":{"prefetchSentences":$count}}""")
    private val shortSentencesBook = EpubBook("Short sentences", chapters = listOf(EpubChapter("one", "one.xhtml",
        "application/xhtml+xml", "<p>一。二。三。四。五。六。七。八。九。十。</p>")))

    @Test fun configuredPrefetchPreparesSixFollowingSentences() = runTest {
        fixture(store = MemoryStore(prefetchPreferences(6)), fixtureBook = shortSentencesBook) { controller, output, audio, _ ->
            controller.play(); runCurrent()
            assertEquals(1, output.playCount)
            assertEquals(listOf("一。", "二。", "三。", "四。", "五。", "六。", "七。"), audio.requests)
        }
    }

    @Test fun increasingPrefetchRefillsWithoutInterruptingCurrentPlayback() = runTest {
        val store = MemoryStore(prefetchPreferences(1))
        fixture(store = store, fixtureBook = shortSentencesBook) { controller, output, audio, _ ->
            controller.play(); runCurrent()
            assertEquals(listOf("一。", "二。"), audio.requests)
            store.updateData { prefetchPreferences(6) }; runCurrent()
            assertTrue(controller.state.value.playing)
            assertEquals(1, output.playCount)
            assertEquals(listOf("二。", "三。", "四。", "五。", "六。", "七。"), audio.requests.drop(2))
        }
    }

    @Test fun reducingPrefetchCancelsExcessPendingWorkWithoutPausingPlayback() = runTest {
        val store = MemoryStore(prefetchPreferences(6))
        fixture(store = store, fixtureBook = shortSentencesBook) { controller, output, audio, _ ->
            val pending = CompletableDeferred<Unit>()
            var cancelled = false
            audio.synthesize = { sentence ->
                if (sentence.text == "三。") {
                    pending.complete(Unit)
                    try { awaitCancellation() }
                    finally { cancelled = true }
                }
            }
            controller.play(); runCurrent(); pending.await()
            store.updateData { prefetchPreferences(1) }; runCurrent()
            assertTrue(cancelled)
            assertTrue(controller.state.value.playing)
            assertEquals(1, output.playCount)
            assertEquals(listOf("一。", "二。", "三。", "二。"), audio.requests)
        }
    }

    @Test fun prefetchClampsRestoredCountsAndStopsAtBookEnd() = runTest {
        val longBook = EpubBook("Long book", chapters = listOf(EpubChapter("one", "one.xhtml", "application/xhtml+xml",
            "<p>" + (1..25).joinToString("") { "第${it}文。" } + "</p>")))
        for ((count, requests) in listOf(-5 to 2, 1 to 2, 1000 to 21)) {
            fixture(store = MemoryStore(prefetchPreferences(count)), fixtureBook = longBook) { controller, _, audio, _ ->
                controller.play(); runCurrent()
                assertEquals(requests, audio.requests.size)
            }
        }
        fixture(store = MemoryStore(prefetchPreferences(20))) { controller, output, audio, _ ->
            controller.play(); runCurrent()
            assertEquals(4, audio.requests.size)
            assertEquals(1, output.playCount)
        }
    }
    @Test fun prefetchIsBoundedAndNavigationUsesSentences() = runTest {
        fixture { controller, output, audio, _ ->
            controller.play(); runCurrent()
            assertEquals(1, output.playCount)
            assertEquals(3, audio.requests.size)
            output.onEnded(); runCurrent()
            assertEquals(1, controller.state.value.index)
            assertEquals(2, output.playCount)
            controller.previous(); runCurrent()
            assertEquals(0, controller.state.value.index)
        }
    }
    @Test fun pausedSentenceNavigationChangesPositionWithoutResumingOrSynthesizing() = runTest {
        fixture { controller, output, audio, _ ->
            controller.play(); runCurrent()
            controller.pause()
            val requests = audio.requests.size
            val presented = mutableListOf<TtsSentence>()
            controller.presentSentence = { sentence, images -> assertFalse(images); presented += sentence }
            controller.next(); runCurrent()
            assertEquals(1, controller.state.value.index)
            assertFalse(controller.state.value.playing)
            assertFalse(controller.state.value.preparing)
            assertEquals(1, output.playCount)
            assertEquals(requests, audio.requests.size)
            controller.previous(); runCurrent()
            assertEquals(0, controller.state.value.index)
            assertFalse(controller.state.value.playing)
            assertEquals(1, output.playCount)
            assertEquals(listOf(1, 0), presented.map { controller.state.value.sentences.indexOf(it) })
            controller.play(); runCurrent()
            assertTrue(controller.state.value.playing)
            assertEquals(2, output.playCount)
        }
    }

    @Test fun navigationBeforeFirstPlayDoesNotStartTheBookSession() = runTest {
        fixture { controller, output, _, _ ->
            controller.next(); runCurrent()
            assertFalse(controller.state.value.started)
            assertFalse(controller.state.value.playing)
            assertEquals(0, output.playCount)
            controller.play(); runCurrent()
            controller.pause()
            assertTrue(controller.state.value.started)
            controller.stop()
            controller.load("book", book, false); runCurrent()
            assertFalse(controller.state.value.started)
            controller.play(); runCurrent()
            controller.load("other-book", book, false); runCurrent()
            assertFalse(controller.state.value.started)
        }
    }

    @Test fun pausedJumpCancelsPendingSynthesisAndInvalidatesLookupResume() = runTest {
        fixture { controller, output, audio, _ ->
            val release = CompletableDeferred<Unit>()
            audio.synthesize = { withContext(NonCancellable) { release.await() } }
            controller.play(); runCurrent()
            val resume = controller.pauseForLookup()
            controller.next(); runCurrent()
            release.complete(Unit); runCurrent()
            resume(); runCurrent()
            assertEquals(1, controller.state.value.index)
            assertFalse(controller.state.value.playing)
            assertEquals(0, output.playCount)
        }
    }

    @Test fun onlyNaturalSentenceAdvancesRequestImageHolds() = runTest {
        fixture { controller, output, _, _ ->
            val holds = mutableListOf<Boolean>()
            controller.presentSentence = { _, hold -> holds += hold }
            controller.play(); runCurrent()
            output.onEnded(); runCurrent()
            controller.repeat(); runCurrent()
            controller.next(); runCurrent()
            assertEquals(listOf(false, true, false, false), holds)
        }
    }
    @Test fun pausingDuringImagePresentationCancelsAudioUntilTheHoldIsResumed() = runTest {
        fixture { controller, output, _, _ ->
            controller.presentSentence = { _, hold -> if (hold) delay(1000) }
            controller.play(); runCurrent()
            output.onEnded(); runCurrent()
            assertTrue(controller.state.value.preparing)
            assertEquals(1, output.playCount)
            controller.pause()
            advanceTimeBy(2000); runCurrent()
            assertEquals(1, output.playCount)
            assertFalse(controller.state.value.playing)
            controller.play(); runCurrent()
            advanceTimeBy(999); runCurrent()
            assertEquals(1, output.playCount)
            advanceTimeBy(1); runCurrent()
            assertEquals(2, output.playCount)
            assertFalse(controller.state.value.preparing)
        }
    }
    @Test fun advancingToCachedSentenceKeepsPendingPrefetchAndMovesItsHorizon() = runTest {
        fixture { controller, output, audio, _ ->
            val pending = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            var cancelled = false
            audio.synthesize = { sentence ->
                if (sentence.text == "三つです。") {
                    pending.complete(Unit)
                    try { release.await() }
                    catch (error: CancellationException) { cancelled = true; throw error }
                }
            }
            controller.play(); runCurrent()
            pending.await()
            output.onEnded(); runCurrent()
            assertEquals(1, controller.state.value.index)
            assertEquals(2, output.playCount)
            assertFalse("Advancing must not discard pending sentence audio", cancelled)
            release.complete(Unit); runCurrent()
            assertTrue(audio.requests.contains("四つです。"))
            output.onEnded(); runCurrent()
            assertEquals(2, controller.state.value.index)
            assertEquals(3, output.playCount)
        }
    }
    @Test fun sentencePauseWaitsBetweenAutomaticSentencesAndStopCancelsIt() = runTest {
        fixture { controller, output, _, repository ->
            repository.settings(TtsSettings(sentencePauseMs = 1000)); runCurrent()
            controller.play(); runCurrent()
            output.onEnded(); runCurrent()
            assertEquals(1, output.playCount)
            assertTrue(controller.state.value.preparing)
            advanceTimeBy(999); runCurrent()
            assertEquals(1, output.playCount)
            advanceTimeBy(1); runCurrent()
            assertEquals(2, output.playCount)
            output.onEnded(); runCurrent()
            controller.stop()
            advanceTimeBy(2000); runCurrent()
            assertEquals(2, output.playCount)
        }
    }
    @Test fun resumingDuringSentencePauseStartsNextSentenceAndManualNavigationDoesNotWait() = runTest {
        fixture { controller, output, _, repository ->
            repository.settings(TtsSettings(sentencePauseMs = 1000)); runCurrent()
            controller.play(); runCurrent()
            output.onEnded(); runCurrent()
            controller.pause(); controller.play(); runCurrent()
            assertEquals(1, controller.state.value.index)
            assertEquals(2, output.playCount)
            controller.next(); runCurrent()
            assertEquals(2, controller.state.value.index)
            assertEquals(3, output.playCount)
            advanceTimeBy(2000); runCurrent()
            assertEquals(3, output.playCount)
        }
    }
    @Test fun loadingAudiobookDiscardsLateSynthesisAndCannotResume() = runTest {
        fixture { controller, output, audio, _ ->
            val done = CompletableDeferred<Unit>()
            audio.synthesize = { withContext(NonCancellable) { done.await() } }
            controller.play(); runCurrent()
            assertTrue(controller.state.value.preparing)
            controller.load("book", book, true)
            done.complete(Unit); runCurrent()
            controller.play(); runCurrent()
            assertEquals(0, output.playCount)
            assertFalse(controller.state.value.playing)
            assertNull(controller.state.value.bookId)
        }
    }
    @Test fun engineFailureIsVisibleAndRetryCanPlay() = runTest {
        fixture { controller, output, audio, _ ->
            audio.synthesize = { throw TtsException(TtsFailure.MissingJapanese) }
            controller.play(); runCurrent()
            assertEquals(TtsFailure.MissingJapanese, controller.state.value.error)
            assertFalse(controller.state.value.playing)
            audio.synthesize = {}
            controller.play(); runCurrent()
            assertEquals(1, output.playCount)
            assertNull(controller.state.value.error)
        }
    }
    @Test fun changingVoicePausesAndNextPlayUsesNewSettings() = runTest {
        fixture { controller, output, _, repository ->
            controller.play(); runCurrent()
            repository.settings(TtsSettings(voice = "new")); runCurrent()
            assertFalse(controller.state.value.playing)
            assertTrue(output.prepared.isEmpty())
            controller.play(); runCurrent()
            assertEquals(2, output.playCount)
        }
    }
    @Test fun synthesisOptionsPauseAndInvalidatePreparedAudio() = runTest {
        fixture { controller, output, _, repository ->
            controller.play(); runCurrent()
            repository.settings(TtsSettings(pitch = 3f, parameters = mapOf("" to TtsSynthesisOptions(volume = -10f)))); runCurrent()
            assertFalse(controller.state.value.playing)
            assertTrue(output.prepared.isEmpty())
            controller.play(); runCurrent()
            assertEquals(2, output.playCount)
        }
    }
    @Test fun dictionaryEditDoesNotInterruptCurrentSentenceButReplayUsesNewReading() = runTest {
        fixture { controller, output, audio, repository ->
            controller.play(); runCurrent()
            repository.save(TtsWord("a", "東京", "とうけい", "book")); runCurrent()
            assertTrue(controller.state.value.playing)
            assertEquals(1, output.playCount)
            controller.repeat(); runCurrent()
            assertTrue(audio.requests.contains("とうけいです。"))
        }
    }
    @Test fun dictionaryEditDuringSynthesisCannotPlayTheOldReading() = runTest {
        fixture { controller, output, audio, repository ->
            val done = CompletableDeferred<Unit>()
            var first = true
            audio.synthesize = { if (first) { first = false; withContext(NonCancellable) { done.await() } } }
            controller.play(); runCurrent()
            repository.save(TtsWord("a", "東京", "とうけい", "book")); runCurrent()
            done.complete(Unit); runCurrent()
            assertEquals(1, output.playCount)
            assertTrue(audio.requests.contains("とうけいです。"))
        }
    }
    @Test fun dictionaryEditWhilePausedInvalidatesPreparedAudio() = runTest {
        fixture { controller, output, audio, repository ->
            controller.play(); runCurrent()
            controller.pause()
            repository.save(TtsWord("a", "東京", "とうけい", "book")); runCurrent()
            assertFalse(controller.state.value.playing)
            controller.play(); runCurrent()
            assertEquals(2, output.playCount)
            assertTrue(audio.requests.contains("とうけいです。"))
        }
    }
    @Test fun explicitPauseAfterLookupPreventsAutomaticResume() = runTest {
        fixture { controller, output, _, _ ->
            controller.play(); runCurrent()
            val resume = controller.pauseForLookup()
            controller.pause()
            resume(); runCurrent()
            assertFalse(controller.state.value.playing)
            assertEquals(1, output.playCount)
        }
    }
    @Test fun stopDuringPreparationNeverResurrectsPlayback() = runTest {
        fixture { controller, output, audio, _ ->
            val done = CompletableDeferred<Unit>()
            audio.synthesize = { withContext(NonCancellable) { done.await() } }
            controller.play(); runCurrent()
            controller.stop()
            done.complete(Unit); runCurrent()
            assertEquals(0, output.playCount)
            assertFalse(controller.state.value.playing)
        }
    }
    @Test fun popupReplayStopsAfterOneSentenceAndForwardRestoresContinuousPlayback() = runTest {
        fixture { controller, output, _, _ ->
            controller.startAt(0, 0, singleSentence = true); runCurrent()
            output.onEnded(); runCurrent()
            assertEquals(0, controller.state.value.index)
            assertFalse(controller.state.value.playing)
            controller.startAt(0, 0); runCurrent()
            output.onEnded(); runCurrent()
            assertEquals(1, controller.state.value.index)
            assertTrue(controller.state.value.playing)
        }
    }

}
