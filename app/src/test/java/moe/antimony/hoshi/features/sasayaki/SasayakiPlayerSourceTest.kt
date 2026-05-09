package moe.antimony.hoshi.features.sasayaki

import moe.antimony.hoshi.epub.SasayakiPlaybackData
import moe.antimony.hoshi.epub.SasayakiMatch

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class SasayakiPlayerSourceTest {
    @Test
    fun playerIsOnlyTheComposeFacingControllerAdapter() {
        val source = playerSource()

        assertTrue(source.contains("class SasayakiPlayer internal constructor("))
        assertTrue(source.contains("private val controller: SasayakiPlaybackControllerContract"))
        assertTrue(source.contains("SasayakiPlaybackController("))
        assertTrue(source.contains("val playback: SasayakiPlaybackData get() = controller.playback"))
        assertTrue(source.contains("var autoScroll: Boolean"))
        assertTrue(source.contains("get() = controller.autoScroll"))
        assertTrue(source.contains("controller.autoScroll = value"))
        assertTrue(source.contains("fun importAudio(audioUri: Uri, copiedAudioFileName: String? = null)"))
        assertTrue(source.contains("controller.importAudio(audioUri = audioUri, copiedAudioFileName = copiedAudioFileName)"))
        assertTrue(source.contains("controller.pausePlayback(restoreTemporaryPosition = restoreTemporaryPosition)"))
        assertTrue(source.contains("controller.findCue(chapterIndex = chapterIndex, offset = offset)"))
        assertTrue(source.contains("controller.playCue(cue = cue, stop = stop)"))
        assertTrue(source.contains("controller.exportCueAudio(cue = cue, sentence = sentence)"))

        assertFalse(source.contains("SasayakiAudioCommandCoordinator("))
        assertFalse(source.contains("SasayakiPlaybackCommandCoordinator("))
        assertFalse(source.contains("SasayakiPlaybackLifecycleController("))
        assertFalse(source.contains("SasayakiMediaSessionHandleCoordinator("))
        assertFalse(source.contains("private fun restoreAudio()"))
        assertFalse(source.contains("private fun tick()"))
        assertFalse(source.contains("private fun updateCue("))
    }

    @Test
    fun controllerOwnsPlaybackAudioAndMediaSessionCoordinatorGraph() {
        val source = controllerSource()
        val restoreAudio = source.substringAfter("private fun restoreAudio()")
            .substringBefore("private fun tick()")
        val teardown = source.substringAfter("private fun teardownPlayer(clearCue: Boolean)")

        assertTrue(source.contains("internal interface SasayakiPlaybackControllerContract"))
        assertTrue(source.contains("internal class SasayakiPlaybackController("))
        assertTrue(source.contains("private val audioSourceRepository = SasayakiAudioRepository(bookRoot)"))
        assertTrue(source.contains("private val playbackPersistence = SasayakiPlaybackPersistenceState("))
        assertTrue(source.contains("private val playbackLifecycle = SasayakiPlaybackLifecycleController("))
        assertTrue(source.contains("private val playbackCommands = SasayakiPlaybackCommandCoordinator("))
        assertTrue(source.contains("private val audioCommands = SasayakiAudioCommandCoordinator("))
        assertTrue(source.contains("private val audioRestore = SasayakiAudioRestoreController("))
        assertTrue(source.contains("private val audioRestoreCallbacks = SasayakiAudioRestoreCallbacksCoordinator("))
        assertTrue(source.contains("private val audioRestoreResult = SasayakiAudioRestoreResultCoordinator("))
        assertTrue(source.contains("private val audioRestoreWorkflow = SasayakiAudioRestoreWorkflowCoordinator("))
        assertTrue(source.contains("private val mediaSessionHandle = SasayakiMediaSessionHandleCoordinator()"))
        assertTrue(source.contains("private val mediaSessionPublishing = SasayakiMediaSessionPublishingCoordinator("))
        assertTrue(source.contains("private val playbackTeardown = SasayakiPlaybackTeardownCoordinator("))
        assertTrue(source.contains("val playback: SasayakiPlaybackData"))
        assertTrue(source.contains("get() = playbackPersistence.playback"))
        assertTrue(source.contains("val audioStorageSummary: String"))
        assertTrue(source.contains("get() = playbackPersistence.audioStorageSummary"))
        assertTrue(source.contains("private val cueAudioExporter = SasayakiCueAudioExporter("))
        assertTrue(source.contains("override fun exportCueAudio(cue: SasayakiMatch, sentence: String): File?"))
        assertFalse(source.contains("File(appContext.cacheDir, \"anki-media/sasayaki\")"))

        assertTrue(restoreAudio.contains("audioRestoreWorkflow.restore("))
        assertTrue(restoreAudio.contains("releaseExistingMediaSession = mediaSessionHandle::releaseExisting"))
        assertTrue(restoreAudio.contains("handleSeekComplete = ::handleSeekComplete"))
        assertTrue(restoreAudio.contains("pausePlayback = { pausePlayback(restoreTemporaryPosition = true) }"))
        assertTrue(teardown.contains("playbackTeardown.teardown("))
        assertFalse(source.contains("import android.media.MediaPlayer"))
        assertFalse(source.contains("private var playbackEngine: SasayakiPlaybackEngine? = null"))
        assertFalse(source.contains("private var mediaSession: SasayakiMediaSessionHandle? = null"))
    }

    @Test
    fun controllerPreservesCuePlaybackSeekAndDisplaySemantics() {
        val source = controllerSource()
        val eventSource = File("src/main/java/moe/antimony/hoshi/features/sasayaki/SasayakiPlaybackEventCoordinator.kt").readText()
        val playCue = source.substringAfter("override fun playCue(cue: SasayakiMatch, stop: Boolean)")
            .substringBefore("override fun release()")
        val complete = source.substringAfter("private fun handleSeekComplete()")
            .substringBefore("private fun restoreAudio()")
        val tick = source.substringAfter("private fun tick()")
            .substringBefore("private fun updateCue(")
        val updateCue = source.substringAfter("private fun updateCue(")
            .substringBefore("private fun applyCueDisplayAction(")

        assertTrue(source.contains("private val cueNavigation = SasayakiCueNavigationController(matchData)"))
        assertTrue(source.contains("private val cueDisplay = SasayakiCueDisplayCoordinator()"))
        assertTrue(source.contains("private val cueDisplayActionDispatcher = SasayakiCueDisplayActionDispatcher("))
        assertTrue(source.contains("private val cuePresentation = SasayakiCuePresentationState()"))
        assertTrue(source.contains("private val playbackStart = SasayakiPlaybackStartCoordinator("))
        assertTrue(source.contains("private val playbackTick = SasayakiPlaybackTickCoordinator("))
        assertTrue(source.contains("private val cueUpdate = SasayakiCueUpdateCoordinator("))
        assertTrue(source.contains("private val seekComplete = SasayakiSeekCompleteCoordinator("))
        assertTrue(source.contains("private val temporaryPlaybackRestore = SasayakiTemporaryPlaybackRestoreCoordinator("))
        assertTrue(source.contains("override fun findCue(chapterIndex: Int, offset: Int): SasayakiMatch?"))
        assertTrue(source.contains("cueNavigation.findCue(chapterIndex = chapterIndex, offset = offset)"))
        assertTrue(playCue.contains("playbackCommands.playCue("))
        assertTrue(playCue.contains("pauseWithoutRestore = { pausePlayback(restoreTemporaryPosition = false) }"))
        assertTrue(complete.contains("seekComplete.handle("))
        assertTrue(complete.contains("applyCueDisplayAction = ::applyCueDisplayAction"))
        assertTrue(tick.contains("playbackTick.tick("))
        assertTrue(updateCue.contains("cueUpdate.update("))
        assertTrue(source.contains("cueDisplayActionDispatcher.apply(action)"))

        assertTrue(eventSource.contains("seek.displayCue?.let { cue ->"))
        assertTrue(eventSource.contains("cueDisplay.displaySelectedCue("))
        assertTrue(eventSource.contains("tick.shouldStopPlayback"))
        assertTrue(eventSource.contains("cueNavigation.cueAtPlaybackTime(time = time, delay = delay)"))
        assertFalse(source.contains("private var currentCue: SasayakiMatch? = null"))
        assertFalse(source.contains("private var stopPlaybackTime: Double? = null"))
        assertFalse(source.contains("private var temporaryPlaybackReturnPosition: Double? = null"))
    }

    @Test
    fun controllerPreservesPersistenceSettingsAndPublishingBoundaries() {
        val source = controllerSource()
        val eventSource = File("src/main/java/moe/antimony/hoshi/features/sasayaki/SasayakiPlaybackEventCoordinator.kt").readText()
        val setDelay = source.substringAfter("override fun setDelay(value: Double)")
            .substringBefore("override fun setRate(value: Float)")
        val setRate = source.substringAfter("override fun setRate(value: Float)")
            .substringBefore("override fun importAudio(")
        val updateMediaSession = source.substringAfter("private fun updateMediaSession()")
            .substringBefore("private fun restoreTemporaryPlaybackPositionIfNeeded()")

        assertTrue(source.contains("private val playbackSettings = SasayakiPlaybackSettingsCoordinator("))
        assertTrue(setDelay.contains("playbackSettings.setDelay("))
        assertTrue(setDelay.contains("currentTime = currentTime"))
        assertTrue(setDelay.contains("updateCue = ::updateCue"))
        assertTrue(setRate.contains("playbackSettings.setRate("))
        assertTrue(setRate.contains("updateMediaSession = ::updateMediaSession"))
        assertTrue(updateMediaSession.contains("mediaSessionPublishing.update("))
        assertTrue(updateMediaSession.contains("isPlaying = isPlaying"))
        assertTrue(updateMediaSession.contains("currentTime = currentTime"))
        assertTrue(updateMediaSession.contains("duration = duration"))
        assertTrue(updateMediaSession.contains("rate = rate"))
        assertTrue(eventSource.contains("playbackPersistence.savePosition(seek.seconds)"))
        assertTrue(eventSource.contains("playbackPersistence.savePosition(playbackState.currentTime)"))
        assertFalse(setDelay.contains("playbackPersistence.setDelay(value)"))
        assertFalse(setRate.contains("playbackPersistence.setRate(value)"))
        assertFalse(updateMediaSession.contains("mediaSessionHandle.update("))
    }

    @Test
    fun cueAudioExporterUsesMedia3TransformerInsteadOfPlatformExtractor() {
        val source = File("src/main/java/moe/antimony/hoshi/features/sasayaki/SasayakiCueAudioExporter.kt").readText()

        assertTrue(source.contains("class SasayakiCueAudioExporter("))
        assertTrue(source.contains("Transformer.Builder(appContext)"))
        assertTrue(source.contains("setAudioMimeType(MimeTypes.AUDIO_AAC)"))
        assertTrue(source.contains("setMuxerFactory(FrameworkMuxer.Factory())"))
        assertTrue(source.contains("MediaItem.ClippingConfiguration.Builder()"))
        assertTrue(source.contains("CountDownLatch"))
        assertTrue(source.contains("ExportResult"))
        assertTrue(source.contains("ExportException"))
        assertTrue(source.contains("AacAdtsCueAudioRewriter.rewrite("))
        assertTrue(source.contains(""".tmp.m4a"""))
        assertTrue(source.contains(""".aac""""))
        assertTrue(source.contains("if (output.exists()) output.delete()"))
        assertFalse(source.contains("android.media.MediaExtractor"))
        assertFalse(source.contains("android.media.MediaMuxer"))
        assertFalse(source.contains("android.media.MediaCodec"))
        assertFalse(source.contains("MediaExtractor("))
        assertFalse(source.contains("MediaMuxer("))
        assertFalse(source.contains("BufferInfo"))

        val rewriter = File("src/main/java/moe/antimony/hoshi/features/sasayaki/AacAdtsCueAudioRewriter.kt").readText()
        assertFalse(rewriter.contains("android.media.MediaExtractor"))
        assertFalse(rewriter.contains("android.media.MediaMuxer"))
        assertFalse(rewriter.contains("android.media.MediaCodec"))
    }

    @Test
    fun media3TransformerDependencyIsDeclaredForSasayakiExport() {
        val versions = File("../gradle/libs.versions.toml").readText()
        val build = File("build.gradle.kts").readText()

        assertTrue(versions.contains("androidx-media3-transformer"))
        assertTrue(build.contains("implementation(libs.androidx.media3.transformer)"))
    }

    private fun playerSource(): String =
        File("src/main/java/moe/antimony/hoshi/features/sasayaki/SasayakiPlayer.kt").readText()

    private fun controllerSource(): String =
        File("src/main/java/moe/antimony/hoshi/features/sasayaki/SasayakiPlaybackController.kt").readText()
}
