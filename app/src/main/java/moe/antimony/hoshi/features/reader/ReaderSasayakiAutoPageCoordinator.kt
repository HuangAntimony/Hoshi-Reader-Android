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
            return holdPlaybackWhile(driver) {
                showStops(driver = driver, stops = stops)
                driver.revealCue(cue, reveal = true)
            }
        }

        if (cue.chapterIndex < driver.currentChapterIndex) {
            return holdPlaybackWhile(driver) {
                driver.loadChapter(cue.chapterIndex)
                showStops(driver, driver.mediaStopsBeforeCue(cue))
                driver.revealCue(cue, reveal = true)
            }
        }

        return holdPlaybackWhile(driver) {
            val startChapter = driver.currentChapterIndex
            showStops(driver, driver.mediaStopsToChapterEnd())
            for (chapterIndex in (startChapter + 1) until cue.chapterIndex) {
                driver.loadChapter(chapterIndex)
                showStops(driver, driver.mediaStopsToChapterEnd())
            }
            driver.loadChapter(cue.chapterIndex)
            showStops(driver, driver.mediaStopsBeforeCue(cue))
            driver.revealCue(cue, reveal = true)
        }
    }

    private suspend fun holdPlaybackWhile(
        driver: ReaderSasayakiAutoPageDriver,
        block: suspend () -> Double?,
    ): Double? {
        val shouldResume = driver.pauseForAutoPageHold()
        return try {
            block()
        } finally {
            if (shouldResume && currentCoroutineContext().isActive) {
                driver.resumeAfterAutoPageHold()
            }
        }
    }

    private suspend fun showStops(
        driver: ReaderSasayakiAutoPageDriver,
        stops: List<ReaderSasayakiMediaStop>,
    ): Boolean {
        for (stop in stops) {
            driver.showMediaStop(stop)
            delay(holdMillis)
        }
        return stops.isNotEmpty()
    }

    private companion object {
        const val DefaultMediaHoldMillis = 1_000L
    }
}
