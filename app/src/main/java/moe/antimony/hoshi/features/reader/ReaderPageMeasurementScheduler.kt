package moe.antimony.hoshi.features.reader

import java.io.IOException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** UI-owned activity gate; its immutable snapshot is also read by the JS bridge thread. */
internal class ReaderPageMeasurementScheduler(
    private val quietPeriodMillis: Long = 1_000,
    private val nowMillis: () -> Long = { System.nanoTime() / 1_000_000 },
) {
    // UI-thread hook: stop an attached document before the visible WebView loads.
    var onRestoreStarted: (() -> Unit)? = null
    private data class Activity(val ready: Boolean = false, val at: Long? = null, val revision: Long = 0)
    private val activity = MutableStateFlow(Activity())
    private fun remainingQuietMillis(current: Activity) = current.at
        ?.let { (quietPeriodMillis - (nowMillis() - it)).coerceAtLeast(0) } ?: 0
    val isIdle: Boolean get() = activity.value.let { it.ready && remainingQuietMillis(it) == 0L }

    fun setReaderReady(ready: Boolean) {
        if (activity.value.ready != ready) {
            activity.value = activity.value.copy(ready = ready, revision = activity.value.revision + 1)
            if (!ready) onRestoreStarted?.invoke()
        }
    }

    fun onForegroundActivity() {
        activity.value = activity.value.copy(at = nowMillis(), revision = activity.value.revision + 1)
    }

    suspend fun awaitIdle() {
        while (true) {
            val current = activity.first { it.ready }
            delay(remainingQuietMillis(current))
            if (activity.value == current && isIdle) return
        }
    }

    /** A deliberately paused renderer must not be mistaken for a hung document. */
    suspend fun <T> withIdleTimeout(timeoutMillis: Long, block: suspend () -> T): T = coroutineScope {
        val watchdog = launch {
            activity.collectLatest { current ->
                if (current.ready) {
                    delay(remainingQuietMillis(current) + timeoutMillis)
                    throw IOException("Timed out measuring pages while Reader was idle")
                }
            }
        }
        try { block() } finally { watchdog.cancel() }
    }
}
