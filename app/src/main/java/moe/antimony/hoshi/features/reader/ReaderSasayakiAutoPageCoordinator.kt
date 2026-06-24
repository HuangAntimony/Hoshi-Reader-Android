package moe.antimony.hoshi.features.reader

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay as coroutineDelay
import kotlinx.coroutines.isActive
import kotlinx.serialization.Serializable
import moe.antimony.hoshi.epub.SasayakiMatch

@Serializable
internal data class ReaderSasayakiMediaStop(
    val scroll: Double? = null,
    val screenIndex: Int? = null,
)

internal interface ReaderSasayakiAutoPageDriver {
    val currentChapterIndex: Int

    suspend fun mediaStopsToChapterEnd(): List<ReaderSasayakiMediaStop>
    suspend fun mediaStopsBeforeCue(cue: SasayakiMatch): List<ReaderSasayakiMediaStop>
    suspend fun showMediaStop(stop: ReaderSasayakiMediaStop): Double?
    suspend fun revealCue(cue: SasayakiMatch, reveal: Boolean): Double?
    suspend fun loadChapter(chapterIndex: Int)
    fun pauseForAutoPageHold(): Boolean
    fun resumeAfterAutoPageHold()
}

internal class ReaderSasayakiAutoPageCoordinator(
    private val holdMillis: Long = DefaultMediaHoldMillis,
    private val delay: suspend (Long) -> Unit = { millis -> coroutineDelay(millis) },
) {
    suspend fun revealCueWithMediaStops(
        cue: SasayakiMatch,
        reveal: Boolean,
        driver: ReaderSasayakiAutoPageDriver,
    ): Double? {
        if (!reveal) {
            return driver.revealCue(cue, reveal = false)
        }

        if (cue.chapterIndex == driver.currentChapterIndex) {
            val stops = driver.mediaStopsBeforeCue(cue)
            if (stops.isEmpty()) {
                return driver.revealCue(cue, reveal = true)
            }
            return withPlaybackHold(driver) { hold ->
                hold.showStops(stops)
                driver.revealCue(cue, reveal = true)
            }
        }

        if (cue.chapterIndex < driver.currentChapterIndex) {
            return withPlaybackHold(driver) { hold ->
                driver.loadChapter(cue.chapterIndex)
                hold.showStops(driver.mediaStopsBeforeCue(cue))
                driver.revealCue(cue, reveal = true)
            }
        }

        return withPlaybackHold(driver) { hold ->
            val startChapter = driver.currentChapterIndex
            hold.showStops(driver.mediaStopsToChapterEnd())
            for (chapterIndex in (startChapter + 1) until cue.chapterIndex) {
                driver.loadChapter(chapterIndex)
                hold.showStops(driver.mediaStopsToChapterEnd())
            }
            driver.loadChapter(cue.chapterIndex)
            hold.showStops(driver.mediaStopsBeforeCue(cue))
            driver.revealCue(cue, reveal = true)
        }
    }

    private suspend fun withPlaybackHold(
        driver: ReaderSasayakiAutoPageDriver,
        block: suspend (PlaybackHold) -> Double?,
    ): Double? {
        val hold = PlaybackHold(driver)
        return try {
            block(hold)
        } finally {
            hold.resumeIfNeeded()
        }
    }

    private inner class PlaybackHold(
        private val driver: ReaderSasayakiAutoPageDriver,
    ) {
        private var started = false
        private var shouldResume = false

        suspend fun showStops(stops: List<ReaderSasayakiMediaStop>) {
            if (stops.isEmpty()) return
            pauseIfNeeded()
            for (stop in stops) {
                driver.showMediaStop(stop)
                delay(holdMillis)
            }
        }

        suspend fun resumeIfNeeded() {
            if (shouldResume && currentCoroutineContext().isActive) {
                driver.resumeAfterAutoPageHold()
            }
        }

        private fun pauseIfNeeded() {
            if (started) return
            started = true
            shouldResume = driver.pauseForAutoPageHold()
        }
    }

    private companion object {
        const val DefaultMediaHoldMillis = 1_000L
    }
}
