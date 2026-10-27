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
import moe.antimony.hoshi.R
import moe.antimony.hoshi.ui.UiText
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class TtsPlaybackControllerTest {
    @Test fun restartWhilePausedStartsCurrentSentenceAndContinuesNormally() = runTest {
        fixture { controller, output, _, _ ->
            controller.startAt(0, 0, singleSentence = true); runCurrent()
            controller.pause()
            val preparations = output.prepareCount
            controller.restartCurrentSentence(); runCurrent()
            assertTrue(controller.state.value.playing)
            assertEquals("東京です。", controller.state.value.sentence?.text)
            assertEquals(preparations + 1, output.prepareCount)
            output.enterNext(); runCurrent()
            assertEquals("次です。", controller.state.value.sentence?.text)
            assertTrue(controller.state.value.playing)
        }
    }

    @Test fun restartUsesActualQueuedSentenceAcrossChapterAndRejectsOldCallbacks() = runTest {
        val chapters = book.copy(chapters = listOf(
            book.chapters.single().copy(html = "<p>一。</p>"),
            EpubChapter("two", "two.xhtml", "application/xhtml+xml", "<p>二。三。</p>"),
        ))
        fixture(fixtureBook = chapters) { controller, output, _, _ ->
            controller.play(); runCurrent()
            val oldTokens = output.queued.keys.toList()
            output.enterNext(notify = false)
            controller.restartCurrentSentence(); runCurrent()
            oldTokens.forEach(output.onTransition)
            assertEquals("二。", controller.state.value.sentence?.text)
            assertEquals(1, controller.state.value.sentence?.chapter)
            assertTrue(controller.state.value.playing)
            controller.restartCurrentSentence(); runCurrent()
            assertEquals("二。", controller.state.value.sentence?.text)
            output.enterNext(); runCurrent()
            assertEquals("三。", controller.state.value.sentence?.text)
            assertTrue(controller.state.value.playing)
        }
    }

    @Test fun restartAfterLastSentenceStartsItAgain() = runTest {
        fixture(fixtureBook = book.copy(chapters = listOf(book.chapters.single().copy(html = "<p>末尾。</p>")))) { controller, output, _, _ ->
            controller.play(); runCurrent()
            output.onEnded(); runCurrent()
            assertFalse(controller.state.value.playing)
            controller.restartCurrentSentence(); runCurrent()
            assertTrue(controller.state.value.playing)
            assertEquals("末尾。", controller.state.value.sentence?.text)
            assertEquals(2, output.prepareCount)
        }
    }

    @Test fun restartWhilePausedReconcilesUndeliveredTransitionWithinChapter() = runTest {
        fixture { controller, output, _, _ ->
            controller.play(); runCurrent()
            output.enterNext(notify = false)
            val resumeLookup = controller.pauseForLookup()
            controller.restartCurrentSentence(); runCurrent()
            assertEquals("次です。", controller.state.value.sentence?.text)
            assertTrue(controller.state.value.playing)
            controller.pause()
            resumeLookup(); runCurrent()
            assertFalse(controller.state.value.playing)
        }
    }

    @Test fun restartAfterStopDoesNotStartOrSynthesize() = runTest {
        fixture { controller, output, audio, _ ->
            controller.stop()
            controller.restartCurrentSentence(); runCurrent()
            assertFalse(controller.state.value.playing)
            assertEquals(0, output.playCount)
            assertTrue(audio.requests.isEmpty())
        }
    }

    @Test fun prefetchStartsSeveralRequestsButQueuesOnlyInSourceOrder() = runTest {
        fixture(store = MemoryStore(prefetchPreferences(20)), fixtureBook = shortSentencesBook) { controller, output, audio, _ ->
            val gates = (1..ttsSynthesisConcurrency).map { CompletableDeferred<Unit>() }
            val pending = mutableListOf<String>()
            audio.synthesize = { sentence ->
                val index = listOf("二。", "三。", "四。").take(ttsSynthesisConcurrency).indexOf(sentence.text)
                if (index >= 0) { pending += sentence.text; gates[index].await() }
            }
            controller.play(); runCurrent()
            assertEquals(listOf("二。", "三。", "四。").take(ttsSynthesisConcurrency), pending)
            gates.drop(1).forEach { it.complete(Unit) }; runCurrent()
            assertTrue(output.queued.isEmpty())
            assertTrue("A slow first request must not stop a free worker preparing later units", audio.requests.contains("十。"))
            gates[0].complete(Unit); runCurrent()
            assertEquals(shortSentencesBook.let { TtsText.chapter(0, it.chapters.single().html) }.drop(1).map { it.text }, output.queued.values.toList())
        }
    }

    @Test fun readyPrefetchIsPublishedWhileAFartherChapterIsStillLoading() = runTest {
        val delegate = StandardTestDispatcher(testScheduler)
        var blockNext = false
        var blocked: (() -> Unit)? = null
        val dispatcher = object : CoroutineDispatcher() {
            override fun dispatch(context: kotlin.coroutines.CoroutineContext, block: Runnable) {
                if (blockNext) {
                    blockNext = false
                    blocked = { delegate.dispatch(context, block) }
                } else delegate.dispatch(context, block)
            }
        }
        val chapters = book.copy(chapters = listOf(
            book.chapters.single().copy(html = "<p>一。二。</p>"),
            EpubChapter("two", "two.xhtml", "application/xhtml+xml", "<p>三。</p>"),
        ))
        fixture(store = MemoryStore(prefetchPreferences(20)), fixtureBook = chapters, bookDispatcher = dispatcher) { controller, output, audio, _ ->
            val ready = CompletableDeferred<Unit>()
            audio.synthesize = { sentence ->
                if (sentence.text == "二。") { blockNext = true; ready.await() }
            }
            controller.play(); runCurrent()
            assertNotNull(blocked)
            ready.complete(Unit); runCurrent()
            assertEquals(listOf("二。"), output.queued.values.toList())
            requireNotNull(blocked).invoke(); runCurrent()
            assertEquals(listOf("二。", "三。"), output.queued.values.toList())
        }
    }

    @Test fun failedPrefetchDoesNotAbandonLaterSentences() = runTest {
        fixture { controller, output, audio, _ ->
            audio.synthesize = { sentence -> if (sentence.text == "次です。") throw TtsException(TtsFailure.Network) }
            controller.play(); runCurrent()
            assertTrue(audio.requests.contains("三つです。"))
            assertTrue(audio.requests.contains("四つです。"))
            assertTrue(output.queued.isEmpty()) // A failed unit cannot be skipped in the playlist.
            assertTrue(controller.state.value.playing)
        }
    }

    @Test fun fartherChapterReadFailureDoesNotEscapePrefetchOrStopCurrentPlayback() = runTest {
        val failure = java.io.IOException("fixture chapter unavailable")
        val unreadable = object : File("fixture-unreadable.xhtml") {
            override fun isFile(): Boolean = throw failure
        }
        val chapters = book.copy(chapters = listOf(
            book.chapters.single().copy(html = "<p>一。二。</p>"),
            EpubChapter("two", "two.xhtml", "application/xhtml+xml", "<p>三。</p>"),
            EpubChapter("three", "three.xhtml", "application/xhtml+xml", ""),
        ), resources = mapOf("three.xhtml" to moe.antimony.hoshi.epub.EpubResource("application/xhtml+xml", file = unreadable)))
        moe.antimony.hoshi.ui.ErrorDiagnosticEvents.buffer.drain()
        fixture(store = MemoryStore(prefetchPreferences(20)), fixtureBook = chapters) { controller, output, _, _ ->
            controller.play(); runCurrent()
            assertTrue(controller.state.value.playing)
            assertNull(controller.state.value.error)
            assertEquals(listOf("二。"), output.queued.values.toList())
            assertTrue(moe.antimony.hoshi.ui.ErrorDiagnosticEvents.buffer.drain().any { event ->
                generateSequence(event.error) { it.cause }.any { it === failure }
            })
        }
    }

    @Test fun prefetchedOrdinaryAndCrossChapterUnitsTransitionWithoutRestartingAudio() = runTest {
        val chapters = book.copy(chapters = listOf(
            book.chapters.single().copy(html = "<p>一。二。</p>"),
            EpubChapter("two", "two.xhtml", "application/xhtml+xml", "<p>三。四。</p>"),
        ))
        fixture(fixtureBook = chapters) { controller, output, _, _ ->
            controller.presentSentence = { sentence, _ -> if (sentence.text != "一。") awaitCancellation() }
            controller.play(); runCurrent()
            val clears = output.clearCount
            assertEquals(listOf("二。", "三。", "四。"), output.queued.values.toList())
            assertEquals("一。", controller.state.value.sentence?.text)
            listOf("二。", "三。", "四。").forEach { expected ->
                output.enterNext(); runCurrent()
                assertEquals(expected, controller.state.value.sentence?.text)
                assertFalse(controller.state.value.preparing)
                assertTrue(controller.state.value.playing)
            }
            assertEquals(1, controller.state.value.sentence?.chapter)
            assertEquals(clears, output.clearCount)
            assertEquals(1, output.prepareCount)
            assertEquals(1, output.playCount)
            output.onEnded(); runCurrent()
            assertFalse(controller.state.value.playing)
        }
    }

    @Test fun manualReplayAndPreferenceChangesRejectOldQueueTransitions() = runTest {
        fixture { controller, output, _, repository ->
            controller.play(); runCurrent()
            val oldTokens = output.queued.keys.toList()
            controller.repeat(); runCurrent()
            oldTokens.forEach(output.onTransition)
            assertEquals(0, controller.state.value.index)
            val beforePauseChange = output.queued.keys.toList()
            repository.settings { it.copy(sentencePauseMs = 700) }; runCurrent()
            assertTrue(output.queued.isEmpty())
            beforePauseChange.forEach(output.onTransition)
            assertEquals(0, controller.state.value.index)
            val plays = output.playCount
            output.onEnded(); runCurrent(); advanceTimeBy(699); runCurrent()
            assertEquals(plays, output.playCount)
            advanceTimeBy(1); runCurrent()
            assertEquals(1, controller.state.value.index)
        }
    }

    @Test fun preferenceChangeConsumesTheAlreadyCurrentUndeliveredTransition() = runTest {
        fixture { controller, output, _, repository ->
            controller.play(); runCurrent()
            output.enterNext(notify = false)
            val current = requireNotNull(output.currentToken)
            repository.settings { it.copy(sentencePauseMs = 100) }; runCurrent()
            assertEquals(1, controller.state.value.index)
            output.onTransition(current)
            assertEquals(1, controller.state.value.index)
            output.onEnded(); runCurrent(); advanceTimeBy(100); runCurrent()
            assertEquals(2, controller.state.value.index)
            assertEquals(1, output.prepareCount)
        }
    }

    @Test fun undeliveredTransitionCannotOverwriteManualReplayOrTheNextLoadedBook() = runTest {
        val chapters = book.copy(chapters = listOf(
            book.chapters.single().copy(html = "<p>一。二。</p>"),
            EpubChapter("two", "two.xhtml", "application/xhtml+xml", "<p>三。</p>"),
        ))
        fixture(fixtureBook = chapters) { controller, output, _, _ ->
            controller.startAt(0, controller.state.value.sentences[1].start); runCurrent()
            output.enterNext(notify = false)
            controller.repeat(); runCurrent()
            assertEquals("二。", controller.state.value.sentence?.text)
            output.enterNext(notify = false)
            controller.load("replacement-book", book, false)
            controller.play(); runCurrent()
            assertEquals("東京です。", controller.state.value.sentence?.text)
            assertTrue(controller.state.value.playing)
            assertNull(controller.state.value.error)
        }
    }

    @Test fun prefetchRechecksTheCurrentPositionAfterSuspendedImageInspection() = runTest {
        val delegate = StandardTestDispatcher(testScheduler)
        var blockNext = false
        var blocked: (() -> Unit)? = null
        val dispatcher = object : CoroutineDispatcher() {
            override fun dispatch(context: kotlin.coroutines.CoroutineContext, block: Runnable) {
                if (blockNext) {
                    blockNext = false
                    blocked = { delegate.dispatch(context, block) }
                } else delegate.dispatch(context, block)
            }
        }
        val chapters = book.copy(chapters = listOf(
            book.chapters.single().copy(html = "<p>一。</p>"),
            EpubChapter("two", "two.xhtml", "application/xhtml+xml", "<p>二。三。</p>"),
        ))
        fixture(fixtureBook = chapters, bookDispatcher = dispatcher) { controller, output, audio, _ ->
            var firstPrefetch = true
            audio.synthesize = { sentence ->
                if (sentence.text == "二。" && firstPrefetch) {
                    firstPrefetch = false
                    blockNext = true // The outgoing chapter image check must cross dispatchers.
                }
            }
            controller.play(); runCurrent()
            assertNotNull(blocked)
            output.onEnded(); runCurrent()
            assertEquals("二。", controller.state.value.sentence?.text)
            requireNotNull(blocked).invoke(); runCurrent()
            assertEquals(1, output.queued.values.count { it == "二。" }) // Foreground append only.
            assertEquals(2, output.playCount)
        }
    }

    @Test fun pausedLateQueueTransitionKeepsLookupResumeValidWithoutPlaying() = runTest {
        fixture { controller, output, _, _ ->
            controller.play(); runCurrent()
            val resume = controller.pauseForLookup()
            output.enterNext(); runCurrent()
            assertEquals(1, controller.state.value.index)
            assertFalse(controller.state.value.playing)
            assertEquals(1, output.playCount)
            resume(); runCurrent()
            assertTrue(controller.state.value.playing)
            assertEquals(1, output.prepareCount)
            assertEquals(2, output.playCount)
        }
    }

    @Test fun sourceImageBarriersPreventQueueingButDisablingHoldsAllowsIt() = runTest {
        val cases = listOf(
            listOf("<p>一。</p><img src='x'/><p>二。</p>"),
            listOf("<p>一。</p><img src='x'/>", "<p>二。</p>"),
            listOf("<p>一。</p>", "<img src='x'/>", "<p>二。</p>"),
            listOf("<p>一。</p>", "<img src='x'/><p>二。</p>"),
        )
        for (sources in cases) {
            val imageChapters = EpubBook("Images", chapters = sources.mapIndexed { i, html ->
                EpubChapter("$i", "$i.xhtml", "application/xhtml+xml", html)
            })
            fixture(fixtureBook = imageChapters) { controller, output, _, _ ->
                controller.play(); runCurrent()
                assertTrue(sources.toString(), output.queued.isEmpty())
                controller.imageHoldMillis = 0; runCurrent()
                assertEquals(listOf("二。"), output.queued.values.toList())
            }
        }
    }

    @Test fun singleSentencePlaybackNeverQueuesFollowingAudio() = runTest {
        fixture { controller, output, _, _ ->
            controller.startAt(0, 0, singleSentence = true); runCurrent()
            assertTrue(output.queued.isEmpty())
            output.onEnded(); runCurrent()
            assertFalse(controller.state.value.playing)
            assertEquals(0, controller.state.value.index)
        }
    }

    @Test fun naturalContinuationNeverClearsOrPreparesThePlayerAgain() = runTest {
        fixture { controller, output, _, _ ->
            val clears = output.clearCount
            controller.play(); runCurrent()
            repeat(3) { output.onEnded(); runCurrent() }
            assertEquals(3, controller.state.value.index)
            assertEquals(clears, output.clearCount)
            assertEquals(1, output.prepareCount)
        }
    }

    @Test fun ordinarySentencePresentationDoesNotGateContinuousAudio() = runTest {
        fixture { controller, output, _, _ ->
            controller.presentSentence = { sentence, _ -> if (sentence.start > 0) awaitCancellation() }
            controller.play(); runCurrent()
            output.onEnded(); runCurrent()
            assertEquals(1, controller.state.value.index)
            assertFalse(controller.state.value.preparing)
            assertEquals(2, output.playCount)
        }
    }

    @Test fun synthesisFailureKeepsCategoryAndNestedDiagnosticInPlaybackState() = runTest {
        fixture { controller, _, audio, _ ->
            audio.synthesize = { throw TtsException(TtsFailure.Network, cause = java.io.IOException("connection reset", java.io.IOException("proxy refused connection"))) }

            controller.play(); runCurrent()

            assertEquals(TtsFailure.Network, controller.state.value.error?.failure)
            assertEquals(UiText.Joined(listOf(UiText.Resource(R.string.tts_network_error), UiText.Literal("connection reset\nproxy refused connection"))), controller.state.value.error?.message)
            assertFalse(controller.state.value.playing)
        }
    }

    @Test fun playbackFailureKeepsTheOriginalDecoderDiagnostic() = runTest {
        fixture { controller, output, _, _ ->
            controller.play(); runCurrent()

            output.onError(java.io.IOException("decoder initialization failed", IllegalArgumentException("unsupported sample rate 11025")))

            assertEquals(TtsFailure.AudioUnavailable, controller.state.value.error?.failure)
            assertEquals(UiText.Joined(listOf(UiText.Resource(R.string.tts_failed), UiText.Literal("decoder initialization failed\nunsupported sample rate 11025"))), controller.state.value.error?.message)
            assertFalse(controller.state.value.playing)
        }
    }

    @Test fun silentSentencesAdvanceWithoutPlayingEmptyAudioOrAddingPauses() = runTest {
        fixture { controller, output, audio, repository ->
            repository.settings { TtsSettings(enabled = true, sentencePauseMs = 1000) }; runCurrent()
            val sentences = controller.state.value.sentences
            audio.silent = { it != sentences.last() }
            val presented = mutableListOf<Pair<TtsSentence, Boolean>>()
            controller.presentSentence = { sentence, hold -> presented += sentence to hold }
            controller.play(); runCurrent()
            assertEquals(3, controller.state.value.index)
            assertTrue(controller.state.value.playing)
            assertEquals(1, output.playCount)
            assertEquals(sentences, presented.map { it.first })
            assertEquals(listOf(false, false, false, false), presented.map { it.second })
            assertEquals(0L, testScheduler.currentTime)
        }
    }

    @Test fun allSilentSentencesAndSingleSentenceReplayStopWithoutErrors() = runTest {
        fixture { controller, output, audio, _ ->
            audio.silent = { true }
            controller.startAt(0, 0, singleSentence = true); runCurrent()
            assertEquals(0, controller.state.value.index)
            assertFalse(controller.state.value.playing)
            controller.startAt(0, 0); runCurrent()
            assertEquals(3, controller.state.value.index)
            assertFalse(controller.state.value.playing)
            assertFalse(controller.state.value.preparing)
            assertNull(controller.state.value.error)
            assertEquals(0, output.playCount)
            assertTrue(output.prepared.isEmpty())
        }
    }

    @Test fun lookupPauseDuringSilentSentenceImageHoldCannotAdvanceUntilResumed() = runTest {
        fixture(fixtureBook = imageBook) { controller, output, audio, _ ->
            audio.silent = { it == controller.state.value.sentences[1] }
            controller.presentSentence = { sentence, hold -> if (hold && sentence == controller.state.value.sentences[1]) delay(1000) }
            controller.play(); runCurrent()
            output.onEnded(); runCurrent()
            val resume = controller.pauseForLookup()
            advanceTimeBy(2000); runCurrent()
            assertEquals(1, controller.state.value.index)
            assertEquals(1, output.playCount)
            resume(); runCurrent(); advanceTimeBy(1000); runCurrent()
            assertEquals(2, controller.state.value.index)
            assertEquals(2, output.playCount)
            assertTrue(controller.state.value.playing)
        }
    }

    @Test fun lookupDuringSentenceDelayResumesNextSentence() = runTest {
        fixture { controller, output, _, repository ->
            repository.settings { TtsSettings(enabled = true, sentencePauseMs = 1000) }; runCurrent()
            controller.play(); runCurrent()
            output.onEnded(); runCurrent()
            advanceTimeBy(100); runCurrent()
            val resume = controller.pauseForLookup()
            advanceTimeBy(2000); runCurrent()
            assertFalse(controller.state.value.playing)
            resume(); runCurrent()
            assertTrue(controller.state.value.playing)
            assertEquals(1, controller.state.value.index)
            assertEquals(2, output.playCount)
        }
    }

    @Test fun lookupDuringNextSentencePreparationResumes() = runTest {
        fixture { controller, output, audio, _ ->
            val ready = CompletableDeferred<Unit>()
            audio.synthesize = { if (it.start > 0) ready.await() }
            controller.play(); runCurrent()
            output.onEnded(); runCurrent()
            assertTrue(controller.state.value.preparing)
            val resume = controller.pauseForLookup()
            ready.complete(Unit); runCurrent()
            assertFalse(controller.state.value.playing)
            resume(); runCurrent()
            assertTrue(controller.state.value.playing)
            assertFalse(controller.state.value.preparing)
            assertEquals(1, controller.state.value.index)
            assertEquals(2, output.playCount)
        }
    }

    @Test fun sentenceEndArrivingAfterLookupPauseStillResumesForward() = runTest {
        fixture { controller, output, _, _ ->
            controller.play(); runCurrent()
            val resume = controller.pauseForLookup()
            // The audio has finished, but its completion event arrives after lookup paused playback.
            output.onEnded(); runCurrent()
            resume(); runCurrent()
            assertTrue(controller.state.value.playing)
            assertEquals("Must advance beyond the audio that already ended", 1, controller.state.value.index)
        }
    }

    @Test fun lookupAtFinalSentenceEndDoesNotReplayFinishedAudio() = runTest {
        fixture { controller, output, _, _ ->
            controller.startAt(0, 999); runCurrent()
            val resume = controller.pauseForLookup()
            output.onEnded(); runCurrent()
            resume(); runCurrent()
            assertFalse(controller.state.value.playing)
            assertEquals(1, output.playCount)
        }
    }
    @Test fun lookupAtSingleSentenceEndDoesNotContinueOrReplay() = runTest {
        fixture { controller, output, _, _ ->
            controller.startAt(0, 0, singleSentence = true); runCurrent()
            val resume = controller.pauseForLookup()
            output.onEnded(); runCurrent()
            resume(); runCurrent()
            assertFalse(controller.state.value.playing)
            assertEquals(0, controller.state.value.index)
            assertEquals(1, output.playCount)
        }
    }
    @Test fun lateEndAfterPausedNavigationCannotSkipTheNewSentence() = runTest {
        fixture { controller, output, _, _ ->
            controller.play(); runCurrent()
            val resume = controller.pauseForLookup()
            controller.next(); runCurrent()
            output.onEnded(); resume(); runCurrent()
            assertFalse(controller.state.value.playing)
            controller.play(); runCurrent()
            assertEquals(1, controller.state.value.index)
            assertEquals(2, output.playCount)
        }
    }

    @Test fun changingUnselectedRoutingKeepsPlaybackButActiveRoutingCancelsLateAudio() = runTest {
        fixture { controller, output, audio, repository ->
            controller.play(); runCurrent()
            val original = repository.preferences.first().settings
            repository.settings { original.copy(routingBySource = mapOf("unselected" to TtsVoiceRouting(true, "a", "b"))) }; runCurrent()
            assertTrue(controller.state.value.playing)
            controller.pause()
            val release = CompletableDeferred<Unit>()
            audio.synthesize = { withContext(NonCancellable) { release.await() } }
            controller.next()
            controller.play(); runCurrent()
            val count = output.playCount
            val current = repository.preferences.first().settings
            repository.settings { current.withRouting(TtsVoiceRouting(true, current.voice, "different")) }; runCurrent()
            release.complete(Unit); runCurrent()
            assertFalse(controller.state.value.playing)
            assertEquals(count, output.playCount)
            assertTrue(output.prepared.isEmpty())
        }
    }

    private val chapterBook = EpubBook("Chapters", chapters = (0..5).map { chapter ->
        EpubChapter("$chapter", "$chapter.xhtml", "application/xhtml+xml", "<img src='image${chapter}.jpg'/><p>第${chapter}章。</p>")
    })

    @Test fun prefetchCrossesChaptersWithoutChangingItsSentenceBound() = runTest {
        fixture(store = MemoryStore(prefetchPreferences(2)), fixtureBook = chapterBook) { controller, output, audio, _ ->
            controller.imageHoldMillis = 0
            controller.play(); runCurrent()
            assertEquals(listOf("第0章。", "第1章。", "第2章。"), audio.requests)
            assertFalse(controller.state.value.hasPrevious)
            assertTrue(controller.state.value.hasNext)
            output.onEnded(); runCurrent()
            assertEquals(1, controller.state.value.sentence?.chapter)
            assertTrue(controller.state.value.hasPrevious)
            assertEquals(2, output.playCount)
            assertEquals("第3章。", audio.requests.last())
        }
    }

    @Test fun pausedCrossChapterNavigationDoesNotPlayOrSynthesize() = runTest {
        fixture(fixtureBook = chapterBook) { controller, output, audio, _ ->
            controller.play(); runCurrent(); controller.pause()
            val requests = audio.requests.size
            controller.next(); runCurrent()
            assertEquals(1, controller.state.value.sentence?.chapter)
            assertFalse(controller.state.value.playing)
            controller.previous(); runCurrent()
            assertEquals(0, controller.state.value.sentence?.chapter)
            assertFalse(controller.state.value.playing)
            assertEquals(1, output.playCount)
            assertEquals(requests, audio.requests.size)
            controller.startAt(4, 0); runCurrent()
            assertEquals(4, controller.state.value.sentence?.chapter)
            assertEquals(2, output.playCount)
        }
    }

    @Test fun repeatedChapterImagesDoNotPauseNaturalPlayback() = runTest {
        val repeated = chapterBook.copy(chapters = chapterBook.chapters.map { it.copy(html = "<img src='divider.jpg'/><p>本文。</p>") })
        fixture(fixtureBook = repeated) { controller, output, _, _ ->
            val holds = mutableListOf<Int>()
            controller.presentSentence = { sentence, hold -> if (hold) holds += sentence.chapter }
            controller.play(); runCurrent()
            output.onEnded(); runCurrent()
            assertTrue(holds.isEmpty())
            assertEquals(1, controller.state.value.sentence?.chapter)
            assertEquals(2, output.playCount)
        }
    }

    @Test fun chapterBoundaryImageHoldAndLookupResumeRetainNaturalAdvance() = runTest {
        fixture(fixtureBook = chapterBook) { controller, output, _, _ ->
            val holds = mutableListOf<Int>()
            controller.presentSentence = { sentence, hold ->
                if (hold) { holds += sentence.chapter; delay(1000) }
            }
            controller.play(); runCurrent()
            output.onEnded(); runCurrent()
            assertEquals(listOf(1), holds)
            val resume = controller.pauseForLookup()
            advanceTimeBy(2000); runCurrent()
            assertEquals(1, output.playCount)
            resume(); runCurrent(); advanceTimeBy(1000); runCurrent()
            assertEquals(2, output.playCount)
            assertEquals(1, controller.state.value.sentence?.chapter)
        }
    }

    @Test fun deletedSentencesCanTraverseChaptersWithoutPlayingEmptyAudio() = runTest {
        fixture(fixtureBook = chapterBook) { controller, output, audio, _ ->
            controller.imageHoldMillis = 0
            audio.silent = { it.chapter < 5 }
            controller.play(); runCurrent()
            assertEquals(5, controller.state.value.sentence?.chapter)
            assertFalse(controller.state.value.hasNext)
            assertEquals(1, output.playCount)
            output.onEnded(); runCurrent()
            assertFalse(controller.state.value.playing)
        }
    }

    @Test fun switchingBooksBeforeChapterReadFinishesCannotRestoreOldPosition() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        try {
            val output = Output()
            val controller = TtsPlaybackController(TtsBookRepository(StandardTestDispatcher(testScheduler)),
                TtsRepository(MemoryStore()), Audio(), output, backgroundScope)
            controller.load("first", chapterBook, false)
            controller.startAt(4, 0)
            assertTrue(controller.state.value.preparing)
            controller.load("second", book, false)
            runCurrent()
            assertEquals("second", controller.state.value.bookId)
            assertTrue(controller.state.value.sentences.isEmpty())
            assertFalse(controller.state.value.started)
            assertEquals(0, output.playCount)
            controller.startAt(0, 0); runCurrent()
            assertEquals("東京です。", controller.state.value.sentence?.text)
            assertEquals(1, output.playCount)
            controller.stop()
        } finally { backgroundScope.cancel(); runCurrent(); Dispatchers.resetMain() }
    }

    @Test fun disablingStopsPlaybackAndInvalidatesLookupResumeWithoutAutoRestart() = runTest {
        fixture { controller, output, audio, repository ->
            controller.play(); runCurrent()
            val resume = controller.pauseForLookup()
            repository.enabled(false); runCurrent()
            val count = audio.requests.size
            controller.play(); controller.startAt(0, 0); resume(); runCurrent()
            assertFalse(controller.state.value.started)
            assertFalse(controller.state.value.playing)
            assertTrue(output.prepared.isEmpty())
            assertEquals(count, audio.requests.size)
            repository.enabled(true); runCurrent()
            resume(); runCurrent()
            assertFalse(controller.state.value.started)
            controller.load("book", book, false)
            controller.startAt(0, 0); runCurrent()
            assertTrue(controller.state.value.playing)
        }
    }

    @Test fun disablingDuringPreparationRejectsLateAudioAndCancelsPrefetch() = runTest {
        fixture { controller, output, audio, repository ->
            val release = CompletableDeferred<Unit>()
            audio.synthesize = { withContext(NonCancellable) { release.await() } }
            controller.play(); runCurrent()
            assertTrue(controller.state.value.preparing)
            repository.enabled(false); runCurrent()
            release.complete(Unit); runCurrent()
            output.onEnded(); controller.repeat(); runCurrent()
            assertFalse(controller.state.value.started)
            assertFalse(controller.state.value.preparing)
            assertEquals(0, output.playCount)
            assertTrue(output.prepared.isEmpty())
        }
    }

    @Test fun noPlaybackBeforeFirstPreferencesAndFailedReadCanBeRetried() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        try {
            val gate = CompletableDeferred<Unit>()
            var failure: Throwable? = java.io.IOException("preferences unavailable")
            val store = object : DataStore<Preferences> {
                override val data = kotlinx.coroutines.flow.flow {
                    gate.await()
                    failure?.let { throw it }
                    emit(preferencesOf(stringPreferencesKey("preferences") to """{"settings":{"enabled":true}}"""))
                    awaitCancellation()
                }
                override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences = error("Read only fixture")
            }
            val output = Output()
            val audio = Audio()
            val controller = TtsPlaybackController(TtsBookRepository(UnconfinedTestDispatcher(testScheduler)),
                TtsRepository(store), audio, output, backgroundScope)
            controller.load("book", book, false)
            controller.startAt(0, 0); runCurrent()
            assertEquals(0, audio.requests.size)
            assertFalse(controller.state.value.started)
            gate.complete(Unit); runCurrent()
            assertNotNull(controller.state.value.error)
            failure = null
            controller.retryPreferences(); runCurrent()
            controller.load("book", book, false)
            controller.startAt(0, 0); runCurrent()
            assertTrue(controller.state.value.playing)
            assertEquals(1, output.playCount)
            controller.stop()
        } finally { backgroundScope.cancel(); runCurrent(); Dispatchers.resetMain() }
    }

    @Test fun disablingWhilePlayingCancelsPendingPrefetch() = runTest {
        fixture { controller, output, audio, repository ->
            var cancelled = false
            audio.synthesize = { sentence ->
                if (sentence.start > 0) try { awaitCancellation() } finally { cancelled = true }
            }
            controller.play(); runCurrent()
            assertEquals(1, output.playCount)
            repository.enabled(false); runCurrent()
            assertTrue(cancelled)
            assertFalse(controller.state.value.started)
            assertTrue(output.prepared.isEmpty())
        }
    }

    private class MemoryStore(initial: Preferences = preferencesOf(stringPreferencesKey("preferences") to """{"settings":{"enabled":true}}""")) : DataStore<Preferences> {
        override val data = MutableStateFlow(initial)
        override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences = transform(data.value).also { data.value = it }
    }
    @Test fun openingBookLeavesTextUnpreparedUntilTtsIsRequested() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        try {
            val controller = TtsPlaybackController(TtsBookRepository(UnconfinedTestDispatcher(testScheduler)),
                TtsRepository(MemoryStore()), Audio(), Output(), backgroundScope)
            controller.load("book", book, false)
            runCurrent()
            assertTrue(controller.state.value.sentences.isEmpty())
            assertFalse(controller.state.value.preparing)
            assertFalse(controller.state.value.started)
            controller.startAt(0, 0)
            runCurrent()
            assertEquals("東京です。", controller.state.value.sentence?.text)
            assertTrue(controller.state.value.playing)
            controller.stop()
        } finally { backgroundScope.cancel(); runCurrent(); Dispatchers.resetMain() }
    }

    private class Output : TtsPlaybackOutput {
        override var onEnded: () -> Unit = {}
        override var onPaused: () -> Unit = {}
        override var onError: (Throwable) -> Unit = {}
        var appliedRate = 1f
        var playCount = 0
        var clearCount = 0
        var prepareCount = 0
        var prepared = emptyList<File>()
        override var onTransition: (Long) -> Unit = {}
        val queued = linkedMapOf<Long, String>()
        override fun append(files: List<File>, title: String, sentence: String, token: Long) { queued[token] = sentence }
        var currentToken: Long? = null
        override fun discardUpcoming(): Long? { queued.clear(); return currentToken }
        fun enterNext(notify: Boolean = true) {
            val token = queued.keys.first(); queued.remove(token); currentToken = token
            if (notify) onTransition(token)
        }
        override suspend fun connect() = Unit
        override fun prepare(files: List<File>, title: String, sentence: String) { prepareCount++; prepared = files; currentToken = null }
        override fun setPlaybackRate(rate: Float) { appliedRate = rate }
        override fun play() { playCount++ }
        override fun pause() = Unit
        override fun clear() { clearCount++; prepared = emptyList(); currentToken = null }
        override fun disconnect() = Unit
    }
    private class Audio : TtsAudioSource {
        val requests = mutableListOf<String>()
        var synthesize: suspend (TtsSentence) -> Unit = {}
        var silent: (TtsSentence) -> Boolean = { false }
        override suspend fun audio(sentence: TtsSentence, bookId: String?, preferences: TtsPreferences): List<File> {
            requests += TtsText.spoken(sentence, bookId, preferences.words, preferences.settings.useRubyReadings)
            synthesize(sentence)
            return if (silent(sentence)) emptyList() else listOf(File("${sentence.start}.wav"))
        }
        override suspend fun clearPlaybackCache() = Unit
    }
    private val book = EpubBook("Fixture", chapters = listOf(EpubChapter("one", "one.xhtml", "application/xhtml+xml", "<p>東京です。次です。三つです。四つです。</p>")))
    private val imageBook = book.copy(chapters = listOf(book.chapters.single().copy(html = "<p>東京です。</p><img src='image.png'/><p>次です。三つです。四つです。</p>")))
    private suspend fun TestScope.fixture(store: MemoryStore = MemoryStore(), fixtureBook: EpubBook = book, bookDispatcher: CoroutineDispatcher? = null, body: suspend (TtsPlaybackController, Output, Audio, TtsRepository) -> Unit) {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        val fixtureScope = CoroutineScope(backgroundScope.coroutineContext + SupervisorJob(backgroundScope.coroutineContext[Job]))
        try {
            val repository = TtsRepository(store)
            val output = Output()
            val audio = Audio()
            val controller = TtsPlaybackController(TtsBookRepository(bookDispatcher ?: UnconfinedTestDispatcher(testScheduler)), repository, audio, output, fixtureScope)
            controller.load("book", fixtureBook, false)
            controller.prepareAt(0, 0)
            withTimeout(5_000) { controller.state.first { it.sentences.isNotEmpty() } }
            body(controller, output, audio, repository)
            controller.stop()
        } finally { fixtureScope.cancel(); runCurrent(); Dispatchers.resetMain() }
    }
    private val preferencesKey = stringPreferencesKey("preferences")
    private fun prefetchPreferences(count: Int) = preferencesOf(preferencesKey to """{"settings":{"enabled":true,"prefetchSentences":$count}}""")
    private val shortSentencesBook = EpubBook("Short sentences", chapters = listOf(EpubChapter("one", "one.xhtml",
        "application/xhtml+xml", "<p>一。二。三。四。五。六。七。八。九。十。</p>")))

    @Test fun editingUnusedSourceDoesNotInterruptPlayback() = runTest {
        fixture { controller, output, _, repository ->
            controller.play(); runCurrent()
            val prepared = output.prepared
            repository.configure(TtsOnlineSource.Azure, TtsOnlineConfig("eastasia", "fixture")); runCurrent()
            assertTrue(controller.state.value.playing)
            assertEquals(prepared, output.prepared)
            assertEquals(1, output.playCount)
        }
    }

    @Test fun editingOrDeletingUnusedCustomServerPreservesCurrentPlaybackAndDeletingActiveStopsIt() = runTest {
        fixture { controller, output, _, repository ->
            val first = TtsCustomSource("custom:one", "One", baseUrl = "http://192.168.1.1:8088/v1", model = "irodori-tts")
            val second = first.copy(id = "custom:two", name = "Two")
            repository.saveSource(first); repository.saveSource(second)
            repository.settings { it.selectSource(first.id) }; runCurrent()
            controller.play(); runCurrent()
            val prepared = output.prepared
            repository.saveSource(second.copy(baseUrl = "http://192.168.1.2/v1")); runCurrent()
            repository.saveSource(first.copy(name = "Renamed")); runCurrent()
            repository.saveSource(first.copy(voiceIds = listOf("bob", "carol"))); runCurrent()
            repository.deleteSource(second.id); runCurrent()
            assertTrue(controller.state.value.playing); assertEquals(prepared, output.prepared)
            repository.deleteSource(first.id); runCurrent()
            assertFalse(controller.state.value.playing); assertTrue(output.prepared.isEmpty())
        }
    }

    @Test fun customServerChangeCancelsPendingSynthesisAndLateAudioCannotResumePlayback() = runTest {
        fixture { controller, output, audio, repository ->
            val first = TtsCustomSource("custom:one", "One", baseUrl = "http://192.168.1.1:8088/v1", model = "irodori-tts")
            repository.saveSource(first); repository.settings { it.selectSource(first.id) }; runCurrent()
            val release = CompletableDeferred<Unit>()
            audio.synthesize = { if (it.text == "東京です。") withContext(NonCancellable) { release.await() } }
            controller.play(); runCurrent()
            repository.saveSource(first.copy(baseUrl = "http://192.168.1.2:8088/v1")); runCurrent()
            release.complete(Unit); runCurrent()
            assertFalse(controller.state.value.playing); assertEquals(0, output.playCount)
        }
    }

    @Test fun changingRubyPreferenceInvalidatesPendingAudioWithoutResumingOldSynthesis() = runTest {
        val rubyBook = EpubBook("Ruby fixture", chapters = listOf(EpubChapter("one", "one.xhtml", "application/xhtml+xml",
            "<p><ruby>今日<rt>キヨウ</rt></ruby>です。次です。</p>")))
        fixture(fixtureBook = rubyBook) { controller, output, audio, repository ->
            val release = CompletableDeferred<Unit>()
            audio.synthesize = { if (audio.requests.size == 1) release.await() }
            controller.play(); runCurrent()
            assertEquals(listOf("キヨウです。"), audio.requests)
            repository.settings { it.copy(useRubyReadings = false) }; runCurrent()
            assertFalse(controller.state.value.playing)
            assertFalse(controller.state.value.preparing)
            release.complete(Unit); runCurrent()
            assertEquals(0, output.playCount)
            controller.play(); runCurrent()
            assertEquals(listOf("キヨウです。", "今日です。", "次です。"), audio.requests)
            assertEquals(1, output.playCount)
        }
    }

    @Test fun playbackRateChangesDoNotInterruptCurrentAudioOrPrefetchAndPreservePause() = runTest {
        fixture { controller, output, audio, repository ->
            val release = CompletableDeferred<Unit>()
            var cancelled = false
            audio.synthesize = { sentence ->
                if (sentence.text == "次です。") {
                    try { release.await() } catch (error: CancellationException) { cancelled = true; throw error }
                }
            }
            controller.play(); runCurrent()
            val prepared = output.prepared
            repository.settings { it.copy(playbackRate = 3f) }; runCurrent()
            assertEquals(3f, output.appliedRate)
            assertTrue(controller.state.value.playing)
            assertFalse(controller.state.value.preparing)
            assertEquals(prepared, output.prepared)
            assertEquals(1, output.playCount)
            assertFalse(cancelled)
            release.complete(Unit); runCurrent()
            assertEquals(listOf("東京です。", "次です。", "三つです。", "四つです。"), audio.requests)
            controller.pause()
            repository.settings { it.copy(playbackRate = .5f) }; runCurrent()
            assertEquals(.5f, output.appliedRate)
            assertFalse(controller.state.value.playing)
            assertEquals(prepared, output.prepared)
            controller.play(); runCurrent()
            assertEquals(2, output.playCount)
            assertEquals(.5f, output.appliedRate)
        }
    }

    @Test fun playbackRateDuringSynthesisKeepsThePendingSentenceAndUsesLatestRate() = runTest {
        fixture { controller, output, audio, repository ->
            val release = CompletableDeferred<Unit>()
            audio.synthesize = { if (it.text == "東京です。") release.await() }
            controller.play(); runCurrent()
            repository.settings { it.copy(playbackRate = 2f) }; runCurrent()
            assertTrue(controller.state.value.playing)
            assertTrue(controller.state.value.preparing)
            assertEquals(0, output.playCount)
            release.complete(Unit); runCurrent()
            assertEquals(1, output.playCount)
            assertEquals(2f, output.appliedRate)
            assertEquals(1, audio.requests.count { it == "東京です。" })
        }
    }

    @Test fun restoredPlaybackRateAppliesBeforePlaybackStarts() = runTest {
        fixture(store = MemoryStore(preferencesOf(preferencesKey to """{"settings":{"enabled":true,"playbackRate":2.5}}"""))) { controller, output, _, _ ->
            assertEquals(2.5f, output.appliedRate)
            controller.play(); runCurrent()
            assertEquals(2.5f, output.appliedRate)
        }
    }

    @Test fun configuredPrefetchPreparesSixFollowingSentences() = runTest {
        fixture(store = MemoryStore(prefetchPreferences(6)), fixtureBook = shortSentencesBook) { controller, output, audio, _ ->
            controller.play(); runCurrent()
            assertEquals(1, output.playCount)
            assertEquals(listOf("一。", "二。", "三。", "四。", "五。", "六。", "七。"), audio.requests)
        }
    }

    @Test fun changingSentencePauseKeepsPlaybackAndAppliesAtNextBoundary() = runTest {
        fixture { controller, output, _, repository ->
            controller.play(); runCurrent()
            repository.settings { it.copy(sentencePauseMs = 700) }; runCurrent()
            assertTrue(controller.state.value.playing)
            assertEquals(1, output.playCount)
            output.onEnded(); runCurrent()
            advanceTimeBy(699); runCurrent()
            assertEquals(0, controller.state.value.index)
            advanceTimeBy(1); runCurrent()
            assertEquals(1, controller.state.value.index)
            assertEquals(2, output.playCount)
        }
    }

    @Test fun quoteUnitsSupportPrefetchReplayNavigationAndSingleSentencePlayback() = runTest {
        val quotedBook = EpubBook("Quotes", chapters = listOf(
            EpubChapter("one", "one.xhtml", "application/xhtml+xml", "<p>彼は「はい」と言った。</p>"),
            EpubChapter("two", "two.xhtml", "application/xhtml+xml", "<p>次。</p>"),
        ))
        fixture(store = MemoryStore(prefetchPreferences(3)), fixtureBook = quotedBook) { controller, output, audio, repository ->
            repository.settings { it.copy(sentencePauseMs = 700) }; runCurrent()
            controller.play(); runCurrent()
            assertEquals(listOf("彼は", "「はい」", "と言った。", "次。"), audio.requests)
            output.onEnded(); runCurrent()
            advanceTimeBy(699); runCurrent()
            assertEquals("彼は", controller.state.value.sentence?.text)
            advanceTimeBy(1); runCurrent()
            assertEquals("「はい」", controller.state.value.sentence?.text)
            controller.repeat(); runCurrent()
            assertEquals("「はい」", controller.state.value.sentence?.text)
            assertEquals(3, output.playCount)
            controller.next(); runCurrent()
            assertEquals("と言った。", controller.state.value.sentence?.text)
            controller.next(); runCurrent()
            assertEquals(1, controller.state.value.sentence?.chapter)
            controller.previous(); runCurrent()
            assertEquals("と言った。", controller.state.value.sentence?.text)
            controller.startAt(0, 2, singleSentence = true); runCurrent()
            assertEquals("「はい」", controller.state.value.sentence?.text)
            output.onEnded(); runCurrent()
            advanceTimeBy(700); runCurrent()
            assertFalse(controller.state.value.playing)
            assertEquals("「はい」", controller.state.value.sentence?.text)
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
            assertEquals(listOf("一。", "二。", "三。", "四。", "五。", "六。", "七。", "二。"), audio.requests)
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
    @Test fun defaultPrefetchPreparesFiveFollowingSentencesAndNavigationUsesSentences() = runTest {
        fixture(fixtureBook = shortSentencesBook) { controller, output, audio, _ ->
            controller.play(); runCurrent()
            assertEquals(1, output.playCount)
            assertEquals(listOf("一。", "二。", "三。", "四。", "五。", "六。"), audio.requests)
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
        fixture(fixtureBook = imageBook) { controller, output, _, _ ->
            val holds = mutableListOf<Boolean>()
            controller.presentSentence = { _, hold -> holds += hold }
            controller.play(); runCurrent()
            output.onEnded(); runCurrent()
            controller.repeat(); runCurrent()
            controller.next(); runCurrent()
            assertEquals(listOf(false, true, false, false), holds)
        }
    }
    @Test fun cancelledReaderRestoreCannotLeaveSpeechPreparingForever() = runTest {
        fixture(fixtureBook = imageBook) { controller, output, _, _ ->
            val restore = CompletableDeferred<Unit>()
            controller.presentSentence = { _, hold -> if (hold) restore.await() }
            controller.play(); runCurrent()
            output.onEnded(); runCurrent()
            assertTrue(controller.state.value.preparing)

            restore.cancel()
            runCurrent()

            assertEquals(1, controller.state.value.index)
            assertEquals(2, output.playCount)
            assertTrue(controller.state.value.playing)
            assertFalse(controller.state.value.preparing)
            assertNull(controller.state.value.error)
        }
    }
    @Test fun manualPageTurnCancelsImagePresentationAndPlaysTheWaitingSentenceOnce() = runTest {
        fixture(fixtureBook = imageBook) { controller, output, _, _ ->
            val revealed = mutableListOf<String>()
            var presentationContext: kotlin.coroutines.CoroutineContext? = null
            controller.presentSentence = { sentence, hold ->
                if (hold) {
                    presentationContext = currentCoroutineContext()
                    delay(1000)
                }
                revealed += sentence.text
            }
            controller.play(); runCurrent()
            output.onEnded(); runCurrent()
            assertEquals(1, output.playCount)
            assertTrue(controller.state.value.preparing)

            controller.cancelReaderPresentation()
            runCurrent()

            assertEquals(1, controller.state.value.index)
            assertEquals(2, output.playCount)
            assertTrue(controller.state.value.playing)
            assertFalse(controller.state.value.preparing)
            assertFalse(requireNotNull(presentationContext).isActive)
            advanceTimeBy(2000); runCurrent()
            assertEquals(listOf("東京です。"), revealed)
            assertEquals(2, output.playCount)

            output.onEnded(); runCurrent()
            advanceTimeBy(1000); runCurrent()
            assertEquals(2, controller.state.value.index)
            assertEquals(3, output.playCount)
            assertEquals(listOf("東京です。", "三つです。"), revealed)
        }
    }
    @Test fun lookupImmediatelyAfterSkippingImageHoldStillControlsResume() = runTest {
        fixture(fixtureBook = imageBook) { controller, output, _, _ ->
            controller.presentSentence = { _, hold -> if (hold) delay(1000) }
            controller.play(); runCurrent()
            output.onEnded(); runCurrent()

            controller.cancelReaderPresentation()
            val resume = controller.pauseForLookup()
            runCurrent()
            val playCountAtLookup = output.playCount
            advanceTimeBy(2000); runCurrent()
            assertEquals(playCountAtLookup, output.playCount)
            assertFalse(controller.state.value.playing)

            resume(); runCurrent()
            assertEquals(1, controller.state.value.index)
            assertEquals(playCountAtLookup + 1, output.playCount)
            assertFalse(controller.state.value.preparing)
        }
    }
    @Test fun skippingSilentSentenceImageHoldContinuesToTheNextAudibleSentence() = runTest {
        fixture { controller, output, audio, _ ->
            audio.silent = { it == controller.state.value.sentences[1] }
            controller.presentSentence = { sentence, hold ->
                if (hold && sentence == controller.state.value.sentences[1]) delay(1000)
            }
            controller.play(); runCurrent()
            output.onEnded(); runCurrent()
            controller.cancelReaderPresentation()
            runCurrent()

            assertEquals(2, controller.state.value.index)
            assertEquals(2, output.playCount)
            assertTrue(controller.state.value.playing)
            assertFalse(controller.state.value.preparing)
        }
    }
    @Test fun pausingDuringImagePresentationCancelsAudioUntilTheHoldIsResumed() = runTest {
        fixture(fixtureBook = imageBook) { controller, output, _, _ ->
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
            repository.settings { TtsSettings(enabled = true, sentencePauseMs = 1000) }; runCurrent()
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
            repository.settings { TtsSettings(enabled = true, sentencePauseMs = 1000) }; runCurrent()
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
            assertEquals(TtsFailure.MissingJapanese, controller.state.value.error?.failure)
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
            repository.settings { TtsSettings(enabled = true, ).withVoice("new") }; runCurrent()
            assertFalse(controller.state.value.playing)
            assertTrue(output.prepared.isEmpty())
            controller.play(); runCurrent()
            assertEquals(2, output.playCount)
        }
    }
    @Test fun synthesisOptionsPauseAndInvalidatePreparedAudio() = runTest {
        fixture { controller, output, _, repository ->
            controller.play(); runCurrent()
            repository.settings { TtsSettings(enabled = true, pitch = 3f, parameters = mapOf("" to TtsSynthesisOptions(volume = -10f))) }; runCurrent()
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
