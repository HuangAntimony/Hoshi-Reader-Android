package moe.antimony.hoshi.features.sasayaki

import android.content.Context
import android.content.ContextWrapper
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import moe.antimony.hoshi.epub.SasayakiMatch
import moe.antimony.hoshi.epub.SasayakiMatchData
import moe.antimony.hoshi.epub.SasayakiPlaybackData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SasayakiPlaybackControllerDeferredCommandTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun restartStartsPausedCueAndIgnoresFixedSkipSetting() {
        val cue = SasayakiMatch("current", 3.0, 5.0, "current", 0, 0, 7)
        val displayed = mutableListOf<Pair<SasayakiMatch, Boolean>>()
        val harness = controllerHarness(
            matchData = SasayakiMatchData(listOf(cue), 0),
            onCue = { match, reveal, _ -> displayed += match to reveal },
        )
        harness.controller.readerSkipButtonAction = SasayakiReaderSkipButtonAction.Seconds30

        harness.controller.restartCurrentCue()

        assertEquals(listOf("seek:3000", "start:1.0"), harness.engine.events)
        assertTrue(harness.controller.isPlaying)
        assertEquals(3.0, harness.controller.playback.lastPosition, 0.0)
        assertTrue(displayed.contains(cue to true))

        harness.engine.events.clear()
        harness.controller.restartCurrentCue()
        assertEquals(listOf("pause", "seek:3000", "start:1.0"), harness.engine.events)
        assertTrue(harness.controller.isPlaying)
    }

    @Test
    fun restartWithoutAnyCueDoesNotPrepareOrStartAudio() {
        val harness = controllerHarness()
        harness.controller.restartCurrentCue()
        assertFalse(harness.preparer.prepared)
        assertTrue(harness.engine.events.isEmpty())
        assertFalse(harness.controller.isPlaying)
    }

    @Test
    fun restartBeforeFirstCueDoesNotPrepareOrStartAudio() {
        val harness = controllerHarness(matchData = SasayakiMatchData(
            listOf(SasayakiMatch("first", 10.0, 12.0, "first", 0, 0, 5)), 0,
        ))
        harness.controller.restartCurrentCue()
        assertFalse(harness.preparer.prepared)
        assertTrue(harness.engine.events.isEmpty())
    }

    @Test
    fun skipForwardPreparesAudioBeforeSeekingWhenEngineIsNotLoadedYet() {
        val harness = controllerHarness()

        assertTrue(harness.controller.hasAudio)
        assertFalse(harness.preparer.prepared)

        harness.controller.skipForward(10)

        assertTrue(harness.preparer.prepared)
        assertEquals(listOf("seek:13500"), harness.engine.events)
    }

    @Test
    fun nextCuePreparesAudioBeforeSeekingWhenEngineIsNotLoadedYet() {
        val harness = controllerHarness(
            matchData = SasayakiMatchData(
                matches = listOf(
                    SasayakiMatch("next", 10.0, 12.0, "next", 0, 0, 4),
                ),
                unmatched = 0,
            ),
        )

        harness.controller.nextCue()

        assertTrue(harness.preparer.prepared)
        assertEquals(listOf("seek:10000"), harness.engine.events)
    }

    @Test
    fun nextCueFallsBackToFixedSkipWhenMatchDataIsMissing() {
        val harness = controllerHarness()

        harness.controller.nextCue()

        assertTrue(harness.preparer.prepared)
        assertEquals(listOf("seek:18500"), harness.engine.events)
    }

    @Test
    fun updatingMatchDataChangesCueNavigationWithoutRestoringInitialPlaybackPosition() {
        val harness = controllerHarness(initialPosition = 3.5)

        harness.controller.seekTo(21.0)
        assertTrue(harness.preparer.prepared)
        harness.engine.events.clear()

        harness.controller.updateMatchData(
            SasayakiMatchData(
                matches = listOf(
                    SasayakiMatch("new", 24.0, 26.0, "new", 0, 0, 3),
                ),
                unmatched = 0,
            ),
        )
        harness.controller.nextCue()

        assertTrue(harness.preparer.prepared)
        assertEquals(listOf("seek:24000"), harness.engine.events)
    }

    @Test
    fun appendingMatchesDoesNotRedisplayCurrentCue() {
        val displayedCues = mutableListOf<Pair<SasayakiMatch, Boolean>>()
        val cue = SasayakiMatch("current", 3.0, 5.0, "current", 0, 0, 7)
        val harness = controllerHarness(
            matchData = SasayakiMatchData(listOf(cue), 0),
            onCue = { match, reveal, _ -> displayedCues += match to reveal },
        )
        harness.controller.togglePlayback()
        displayedCues.clear()
        harness.engine.events.clear()

        harness.controller.updateMatchData(
            SasayakiMatchData(listOf(cue.copy(), SasayakiMatch("next", 6.0, 8.0, "next", 0, 7, 4)), 0),
        )

        assertTrue(displayedCues.isEmpty())
        assertTrue(harness.engine.events.isEmpty())
        assertEquals(3.5, harness.controller.currentTime, 0.0)
    }

    @Test
    fun correctedCurrentCueRangeRefreshesHighlightWithoutRevealingIt() {
        val displayedCues = mutableListOf<Pair<SasayakiMatch, Boolean>>()
        val cue = SasayakiMatch("current", 3.0, 5.0, "cur", 0, 0, 3)
        val harness = controllerHarness(
            matchData = SasayakiMatchData(listOf(cue), 0),
            onCue = { match, reveal, _ -> displayedCues += match to reveal },
        )
        harness.controller.togglePlayback()
        displayedCues.clear()
        val corrected = cue.copy(text = "current", length = 7, endTime = 6.0)

        harness.controller.updateMatchData(SasayakiMatchData(listOf(corrected), 0))

        assertEquals(listOf(corrected to false), displayedCues)
        assertTrue(harness.controller.isPlaying)
    }

    @Test
    fun newlyMatchedOtherChapterClearsOldHighlightWithoutNavigating() {
        val displayedCues = mutableListOf<Pair<SasayakiMatch, Boolean>>()
        var clearCount = 0
        val cue = SasayakiMatch("current", 3.0, 5.0, "current", 0, 0, 7)
        val harness = controllerHarness(
            matchData = SasayakiMatchData(listOf(cue), 0),
            onCue = { match, reveal, _ -> displayedCues += match to reveal },
            onClearCue = { clearCount++ },
        )
        harness.controller.togglePlayback()
        displayedCues.clear()

        harness.controller.updateMatchData(SasayakiMatchData(listOf(cue.copy(chapterIndex = 1)), 0))

        assertEquals(1, clearCount)
        assertTrue(displayedCues.isEmpty())
        assertEquals(3.5, harness.controller.currentTime, 0.0)
    }

    @Test
    fun clearingMatchesRemovesStaleHighlight() {
        var clearCount = 0
        val harness = controllerHarness(
            matchData = SasayakiMatchData(listOf(SasayakiMatch("current", 3.0, 5.0, "current", 0, 0, 7)), 0),
            onClearCue = { clearCount++ },
        )
        harness.controller.togglePlayback()

        harness.controller.updateMatchData(null)
        harness.controller.updateMatchData(null)

        assertEquals(1, clearCount)
        assertFalse(harness.controller.hasMatch)
    }

    @Test
    fun matchRefreshDuringAutoPageHoldPreservesAutomaticResume() {
        val cue = SasayakiMatch("current", 3.0, 5.0, "current", 0, 0, 7)
        val harness = controllerHarness(matchData = SasayakiMatchData(listOf(cue), 0))
        harness.controller.togglePlayback()
        harness.engine.events.clear()
        assertTrue(harness.controller.pauseForAutoPageHold())

        harness.controller.updateMatchData(SasayakiMatchData(listOf(cue), 0))
        harness.controller.resumeAfterAutoPageHold()

        assertEquals(listOf("pause", "start:1.0"), harness.engine.events)
        assertTrue(harness.controller.isPlaying)
    }

    @Test
    fun sameBookReaderReattachmentExplicitlyRestoresUnchangedCueWithoutSeeking() {
        val attachment = SasayakiReaderAttachment()
        attachment.attach(getCurrentChapterIndex = { 0 }, onCue = { _, _, _ -> }, onClearCue = {})
        val cue = SasayakiMatch("current", 3.0, 5.0, "current", 0, 0, 7)
        val data = SasayakiMatchData(listOf(cue), 0)
        val harness = controllerHarness(matchData = data, onCue = attachment::cue)
        harness.controller.togglePlayback()
        harness.engine.events.clear()
        attachment.detach()
        val displayedCues = mutableListOf<Pair<SasayakiMatch, Boolean>>()
        attachment.attach(
            getCurrentChapterIndex = { 0 },
            onCue = { match, reveal, _ -> displayedCues += match to reveal },
            onClearCue = {},
        )

        harness.controller.updateMatchData(data)
        assertTrue(displayedCues.isEmpty())
        harness.controller.restoreCueDisplay()

        assertEquals(listOf(cue to true), displayedCues)
        assertTrue(harness.engine.events.isEmpty())
        assertEquals(3.5, harness.controller.currentTime, 0.0)
    }

    @Test
    fun previousCueFallsBackToFixedSkipWhenMatchDataIsMissing() {
        val harness = controllerHarness(
            initialPosition = 20.0,
        )

        harness.controller.previousCue()

        assertTrue(harness.preparer.prepared)
        assertEquals(listOf("seek:5000"), harness.engine.events)
    }

    @Test
    fun resumeAfterAutoPageHoldDoesNotRedispatchCurrentCue() {
        val displayedCues = mutableListOf<String>()
        val cue = SasayakiMatch("current", 3.0, 5.0, "current", 0, 0, 7)
        val harness = controllerHarness(
            matchData = SasayakiMatchData(matches = listOf(cue), unmatched = 0),
            initialPosition = 3.5,
            onCue = { match, reveal, _ -> displayedCues += "${match.id}:$reveal" },
        )
        harness.controller.togglePlayback()
        displayedCues.clear()
        harness.engine.events.clear()

        assertTrue(harness.controller.pauseForAutoPageHold())
        harness.controller.resumeAfterAutoPageHold()

        assertEquals(emptyList<String>(), displayedCues)
        assertEquals(listOf("pause", "start:1.0"), harness.engine.events)
    }

    @Test
    fun explicitPauseDuringAutoPageHoldPreventsAutomaticResume() {
        val harness = controllerHarness()
        harness.controller.togglePlayback()
        harness.engine.events.clear()

        assertTrue(harness.controller.pauseForAutoPageHold())
        harness.controller.pausePlayback(restoreTemporaryPosition = true)
        harness.controller.resumeAfterAutoPageHold()

        assertEquals(listOf("pause", "pause"), harness.engine.events)
        assertFalse(harness.controller.isPlaying)
    }

    @Test
    fun nextCueDuringAutoPageHoldContinuesPlaybackAfterSeek() {
        val harness = controllerHarness(
            matchData = SasayakiMatchData(
                matches = listOf(
                    SasayakiMatch("next", 10.0, 12.0, "next", 0, 0, 4),
                ),
                unmatched = 0,
            ),
        )
        harness.controller.togglePlayback()
        harness.engine.events.clear()

        assertTrue(harness.controller.pauseForAutoPageHold())
        harness.controller.nextCue()

        assertEquals(listOf("pause", "seek:10000", "start:1.0"), harness.engine.events)
        assertTrue(harness.controller.isPlaying)
    }

    @Test
    fun previousCueDuringAutoPageHoldContinuesPlaybackAfterSeek() {
        val harness = controllerHarness(
            matchData = SasayakiMatchData(
                matches = listOf(
                    SasayakiMatch("previous", 10.0, 12.0, "previous", 0, 0, 8),
                    SasayakiMatch("current", 20.0, 22.0, "current", 1, 0, 7),
                ),
                unmatched = 0,
            ),
            initialPosition = 20.5,
        )
        harness.controller.togglePlayback()
        harness.engine.events.clear()

        assertTrue(harness.controller.pauseForAutoPageHold())
        harness.controller.previousCue()

        assertEquals(listOf("pause", "seek:10000", "start:1.0"), harness.engine.events)
        assertTrue(harness.controller.isPlaying)
    }

    @Test
    fun progressSeekDuringAutoPageHoldContinuesPlaybackAfterSeek() {
        val harness = controllerHarness()
        harness.controller.togglePlayback()
        harness.engine.events.clear()

        assertTrue(harness.controller.pauseForAutoPageHold())
        harness.controller.seekTo(21.0)

        assertEquals(listOf("pause", "seek:21000", "start:1.0"), harness.engine.events)
        assertTrue(harness.controller.isPlaying)
    }

    @Test
    fun pageTurnPlaysForwardPausesAtBoundaryBeforeDispatchAndBackwardOnlySeeks() {
        val cues = listOf(SasayakiMatch("page", 4.0, 12.0, "page", 0, 0, 4),
            SasayakiMatch("next", 8.0, 10.0, "next", 0, 4, 4))
        val displayed = mutableListOf<String>()
        val harness = controllerHarness(matchData = SasayakiMatchData(cues, 0),
            onCue = { cue, _, _ -> displayed += cue.id })
        harness.controller.handlePageChanged(0, listOf("page"), true)
        assertTrue(harness.controller.isPlaying)
        assertTrue(harness.engine.events.contains("seek:4000"))
        displayed.clear()
        harness.engine.callbacks!!.onPositionChanged(8100, 90_000)
        assertFalse(harness.controller.isPlaying)
        assertTrue(displayed.isEmpty())
        harness.engine.events.clear()
        harness.controller.handlePageChanged(0, listOf("page"), false)
        assertFalse(harness.controller.isPlaying)
        assertTrue(harness.engine.events.contains("seek:4000"))
        assertFalse(harness.engine.events.any { it.startsWith("start") })
    }

    @Test
    fun passivePageRefreshOutsidePlaybackDoesNotBlockFollowingOrChangeTransport() {
        var chapter = 0
        val displayed = mutableListOf<String>()
        val harness = controllerHarness(
            matchData = SasayakiMatchData(listOf(
                SasayakiMatch("past", 0.0, 2.0, "past", 0, 0, 4),
                SasayakiMatch("playing", 4.0, 6.0, "playing", 0, 4, 4),
                SasayakiMatch("future", 20.0, 22.0, "future", 0, 8, 4),
            ), 0),
            initialPosition = 4.5,
            getCurrentChapterIndex = { chapter },
            onCue = { cue, _, _ -> displayed += cue.id },
        )
        harness.controller.togglePlayback()
        for ((pageChapter, ids) in listOf(1 to emptyList(), 0 to listOf("future"), 0 to listOf("past"))) {
            chapter = pageChapter
            displayed.clear()
            harness.engine.events.clear()
            harness.controller.handlePageChanged(chapter, ids, null)
            assertTrue(harness.engine.events.isEmpty())
            assertEquals(4.5, harness.controller.currentTime, 0.0)
            harness.engine.callbacks!!.onPositionChanged(4500, 90_000)
            assertEquals("Follow must survive passive refresh: $ids", listOf("playing"), displayed)
            assertTrue(harness.controller.isPlaying)
        }

        // An explicit turn to a page without cues still pauses without seeking.
        displayed.clear()
        harness.controller.handlePageChanged(chapter, emptyList(), false)
        assertFalse(harness.controller.isPlaying)
        assertEquals(4.5, harness.controller.currentTime, 0.0)
        assertTrue(displayed.isEmpty())
    }

    @Test
    fun playbackCommandsAfterCueLessPageCanFollowAudioAgain() {
        val cue = SasayakiMatch("audio", 4.0, 6.0, "audio", 0, 0, 5)
        for (command in listOf("play", "next", "seek")) {
            var chapter = 1
            val displayed = mutableListOf<String>()
            val harness = controllerHarness(
                matchData = SasayakiMatchData(listOf(cue), 0),
                initialPosition = if (command == "play") 4.5 else 3.5,
                getCurrentChapterIndex = { chapter },
                onCue = { match, _, _ -> displayed += match.id },
            )
            harness.controller.handlePageChanged(chapter, emptyList(), true)
            assertFalse(harness.controller.isPlaying)
            assertTrue(displayed.isEmpty())

            when (command) {
                "play" -> harness.controller.togglePlayback()
                "next" -> harness.controller.nextCue()
                "seek" -> harness.controller.seekTo(4.0)
            }
            assertTrue("$command must release the empty page's cue restriction", displayed.contains("audio"))
            chapter = 0
            harness.controller.handlePageChanged(chapter, listOf("audio"), null)
            if (!harness.controller.isPlaying) harness.controller.togglePlayback()
            harness.engine.callbacks!!.onPositionChanged(6100, 90_000)
            assertFalse("$command must still stop at the followed page end", harness.controller.isPlaying)
        }
    }

    @Test
    fun playAfterPageEndCanFollowAndStopAtTheNextPage() {
        val displayed = mutableListOf<String>()
        val harness = controllerHarness(
            matchData = SasayakiMatchData(listOf(
                SasayakiMatch("first", 4.0, 6.0, "first", 0, 0, 5),
                SasayakiMatch("second", 6.0, 8.0, "second", 0, 5, 6),
            ), 0),
            onCue = { cue, _, _ -> displayed += cue.id },
        )
        harness.controller.handlePageChanged(0, listOf("first"), true)
        harness.engine.callbacks!!.onPositionChanged(6100, 90_000)
        assertFalse(harness.controller.isPlaying)
        displayed.clear()

        harness.controller.togglePlayback()
        assertTrue(harness.controller.isPlaying)
        assertTrue(displayed.contains("second"))
        harness.controller.handlePageChanged(0, listOf("second"), null)
        harness.engine.callbacks!!.onPositionChanged(8100, 90_000)
        assertFalse(harness.controller.isPlaying)
    }

    @Test
    fun passivePageRefreshBindsNewPageEvenWhenAudioHasNotReachedIt() {
        val cue = SasayakiMatch("destination", 4.0, 6.0, "destination", 1, 0, 11)
        val harness = controllerHarness(
            matchData = SasayakiMatchData(listOf(cue), 0), initialPosition = 3.5,
        )
        harness.controller.togglePlayback()
        harness.engine.events.clear()
        harness.controller.handlePageChanged(1, listOf("destination"), null)
        assertTrue(harness.engine.events.isEmpty())
        assertEquals(3.5, harness.controller.currentTime, 0.0)
        harness.engine.callbacks!!.onPositionChanged(6100, 90_000)
        assertFalse(harness.controller.isPlaying)
    }

    @Test
    fun passivePageRefreshAtMillisecondSeekTargetStillStopsAtPageEnd() {
        val cue = SasayakiMatch("page", 4.1000000000000005, 6.0, "page", 0, 0, 4)
        val harness = controllerHarness(
            matchData = SasayakiMatchData(listOf(cue), 0), initialPosition = 4.1,
        )
        harness.controller.handlePageChanged(0, listOf("page"), null)
        harness.controller.togglePlayback()
        harness.engine.callbacks!!.onPositionChanged(6100, 90_000)
        assertFalse(harness.controller.isPlaying)
    }

    @Test
    fun nextCuePageRefreshPreservesTransportAndStopsAtTheNewPageEnd() {
        val harness = controllerHarness(matchData = SasayakiMatchData(listOf(
            SasayakiMatch("first", 4.0, 6.0, "first", 0, 0, 4),
            SasayakiMatch("second", 8.0, 10.0, "second", 0, 4, 4),
            SasayakiMatch("third", 12.0, 14.0, "third", 0, 8, 4),
        ), 0))
        for (playing in listOf(false, true)) {
            harness.controller.handlePageChanged(0, listOf("first"), playing)
            harness.controller.nextCue()
            assertEquals(8.0, harness.controller.currentTime, 0.0)
            assertEquals(playing, harness.controller.isPlaying)
            harness.engine.events.clear()

            // Reader reports the followed page without issuing another transport command.
            harness.controller.handlePageChanged(0, listOf("second"), null)
            assertTrue(harness.engine.events.isEmpty())
            assertEquals(8.0, harness.controller.currentTime, 0.0)
            assertEquals(playing, harness.controller.isPlaying)

            if (!playing) harness.controller.togglePlayback()
            harness.engine.callbacks!!.onPositionChanged(10_100, 90_000)
            assertFalse(harness.controller.isPlaying)
        }
    }

    @Test
    fun emptyPageDoesNotSeekRefreshDoesNotStartAndDisablingRemovesBoundary() {
        val cue = SasayakiMatch("page", 4.0, 6.0, "page", 0, 0, 4)
        val harness = controllerHarness(matchData = SasayakiMatchData(listOf(cue), 0))
        harness.controller.handlePageChanged(0, listOf("page"), null)
        assertFalse(harness.preparer.prepared)
        harness.controller.handlePageChanged(0, listOf("page"), true)
        harness.engine.events.clear()
        harness.controller.handlePageChanged(0, emptyList(), true)
        assertFalse(harness.controller.isPlaying)
        assertFalse(harness.engine.events.any { it.startsWith("seek") })
        harness.controller.handlePageChanged(0, listOf("page"), true)
        harness.controller.clearPageRange()
        harness.engine.callbacks!!.onPositionChanged(7000, 90_000)
        assertTrue(harness.controller.isPlaying)
    }

    @Test
    fun releasedControllerRejectsLatePageCallback() {
        val cue = SasayakiMatch("page", 4.0, 6.0, "page", 0, 0, 4)
        val harness = controllerHarness(matchData = SasayakiMatchData(listOf(cue), 0))
        harness.controller.release()
        harness.controller.handlePageChanged(0, listOf("page"), true)
        assertFalse(harness.preparer.prepared)
    }

    @Test
    fun pageSeekFromLaterPositionAppliesTargetBeforeCheckingPageEnd() {
        val cue = SasayakiMatch("page", 4.0, 6.0, "page", 0, 0, 4)
        val harness = controllerHarness(matchData = SasayakiMatchData(listOf(cue), 0), initialPosition = 20.0)
        harness.controller.handlePageChanged(0, listOf("page"), true)
        assertEquals(4.0, harness.controller.currentTime, 0.0)
        assertTrue(harness.controller.isPlaying)
    }

    @Test
    fun pageDisableOrNewEmptyPageCancelsPendingPageSeekPlayback() {
        val cue = SasayakiMatch("page", 4.0, 6.0, "page", 0, 0, 4)
        val harness = controllerHarness(matchData = SasayakiMatchData(listOf(cue), 0))
        harness.engine.completeSeeksImmediately = false
        harness.controller.handlePageChanged(0, listOf("page"), true)
        harness.controller.clearPageRange()
        harness.engine.callbacks!!.onSeekComplete()
        assertFalse(harness.controller.isPlaying)
        harness.controller.handlePageChanged(0, listOf("page"), true)
        harness.controller.handlePageChanged(0, emptyList(), false)
        harness.engine.callbacks!!.onSeekComplete()
        assertFalse(harness.controller.isPlaying)
    }

    @Test
    fun matchesInOtherChaptersDoNotRemoveCurrentPageBoundary() {
        val cue = SasayakiMatch("page", 4.0, 6.0, "page", 0, 0, 4)
        val harness = controllerHarness(matchData = SasayakiMatchData(listOf(cue), 0))
        harness.controller.handlePageChanged(0, listOf("page"), true)
        harness.controller.updateMatchData(SasayakiMatchData(listOf(cue,
            SasayakiMatch("other", 9.0, 10.0, "other", 1, 0, 5)), 0))
        harness.engine.callbacks!!.onPositionChanged(7000, 90_000)
        assertFalse(harness.controller.isPlaying)
    }

    @Test
    fun explicitPauseCancelsPageSeekAndLaterManualSeekOwnsItsCompletion() {
        val cue = SasayakiMatch("page", 4.0, 6.0, "page", 0, 0, 4)
        val harness = controllerHarness(matchData = SasayakiMatchData(listOf(cue), 0))
        harness.engine.completeSeeksImmediately = false
        harness.controller.handlePageChanged(0, listOf("page"), true)
        harness.controller.pausePlayback(restoreTemporaryPosition = false)
        harness.engine.callbacks!!.onSeekComplete()
        assertFalse(harness.controller.isPlaying)
        harness.controller.handlePageChanged(0, listOf("page"), true)
        harness.controller.seekTo(5.0)
        harness.controller.clearPageRange()
        harness.engine.callbacks!!.onSeekComplete()
        assertEquals(5.0, harness.controller.currentTime, 0.0)
    }

    private fun controllerHarness(
        matchData: SasayakiMatchData? = null,
        initialPosition: Double = 3.5,
        getCurrentChapterIndex: () -> Int = { 0 },
        onCue: (SasayakiMatch, Boolean, SasayakiCueRevealSource) -> Unit = { _, _, _ -> },
        onClearCue: () -> Unit = {},
    ): ControllerHarness {
        val bookRoot = temporaryFolder.newFolder()
        val audioFile = bookRoot.resolve("Sasayaki/sasayaki_audio.m4b").also { file ->
            file.parentFile?.mkdirs()
            file.writeText("audio")
        }
        val engine = FakePlaybackEngine()
        val preparer = FakePlaybackPreparer(engine)
        val controller = SasayakiPlaybackController(
            context = FakeContext(temporaryFolder.newFolder()),
            bookRoot = bookRoot,
            playbackRepository = NoOpPlaybackRepository,
            bookTitle = "Book",
            bookCoverFile = null,
            matchData = matchData,
            initialPlayback = SasayakiPlaybackData(
                lastPosition = initialPosition,
                audioFileName = audioFile.name,
            ),
            persistenceScope = CoroutineScope(Dispatchers.Unconfined),
            persistenceDispatcher = Dispatchers.Unconfined,
            getCurrentChapterIndex = getCurrentChapterIndex,
            onCue = onCue,
            onClearCue = onClearCue,
            playbackPreparer = preparer,
            onPlaybackStartRequested = { onReady -> onReady() },
            restoreAudioOnCreate = false,
            tickScheduler = NoOpTickScheduler,
        )
        return ControllerHarness(controller = controller, preparer = preparer, engine = engine)
    }

    private data class ControllerHarness(
        val controller: SasayakiPlaybackController,
        val preparer: FakePlaybackPreparer,
        val engine: FakePlaybackEngine,
    )

    private class FakeContext(
        private val cacheDirectory: File,
    ) : ContextWrapper(null) {
        override fun getApplicationContext(): Context = this

        override fun getCacheDir(): File = cacheDirectory
    }

    private object NoOpPlaybackRepository : SasayakiPlaybackRepository {
        override suspend fun load(): SasayakiPlaybackData? = null

        override suspend fun save(playback: SasayakiPlaybackData) = Unit
    }

    private object NoOpTickScheduler : SasayakiTickScheduler {
        override fun postTick() = Unit

        override fun stopTicking() = Unit
    }

    private class FakePlaybackPreparer(
        private val engine: FakePlaybackEngine,
    ) : SasayakiPlaybackPreparer {
        var prepared = false
            private set

        override fun prepare(
            source: SasayakiPlaybackSource,
            startPositionMs: Int,
            title: String,
            artworkFile: File?,
            callbacks: SasayakiAudioRestoreCallbacks,
        ): SasayakiPreparedPlayback {
            prepared = true
            engine.currentPositionMs = startPositionMs
            engine.callbacks = callbacks
            callbacks.onPrepared(engine.durationMs)
            return SasayakiPreparedPlayback(engine = engine, durationMs = engine.durationMs)
        }
    }

    private class FakePlaybackEngine : SasayakiPlaybackEngine {
        val events = mutableListOf<String>()
        override var durationMs: Int = 30_000
        override var currentPositionMs: Int = 0
        var callbacks: SasayakiAudioRestoreCallbacks? = null
        var completeSeeksImmediately = true

        override fun start(rate: Float) {
            events += "start:$rate"
        }

        override fun pause() {
            events += "pause"
        }

        override fun setRate(rate: Float) {
            events += "rate:$rate"
        }

        override fun seekTo(positionMs: Int) {
            currentPositionMs = positionMs
            events += "seek:$positionMs"
            if (completeSeeksImmediately) callbacks?.onSeekComplete()
        }

        override fun release() {
            events += "release"
        }
    }
}
