package moe.antimony.hoshi.ui

import java.lang.ref.ReferenceQueue
import java.lang.ref.WeakReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel

internal data class ErrorDiagnosticEvent(
    val operation: UiText,
    val error: Throwable?,
    val timestampMillis: Long,
    val serviceDetails: String? = null,
)

/** UI error adapters enqueue here; the injected application logger owns persistence. */
internal class ErrorDiagnosticBuffer(private val capacity: Int = 100) {
    private val lock = Any()
    private val pending = ArrayDeque<ErrorDiagnosticEvent>()
    private val collectedErrors = ReferenceQueue<Throwable>()
    private val seen = HashSet<ThrowableIdentity>()
    internal val notifications = Channel<Unit>(Channel.CONFLATED)

    init { require(capacity > 0) }

    fun record(operation: UiText, error: Throwable, timestampMillis: Long = System.currentTimeMillis()) {
        if (error is CancellationException) return
        synchronized(lock) {
            while (true) {
                val collected = collectedErrors.poll() ?: break
                seen.remove(collected)
            }
            if (!seen.add(ThrowableIdentity(error, collectedErrors))) return
            if (pending.size == capacity) pending.removeFirst()
            pending.addLast(ErrorDiagnosticEvent(operation, error, timestampMillis))
        }
        notifications.trySend(Unit)
    }

    fun recordServiceFailure(operation: UiText, details: String, timestampMillis: Long = System.currentTimeMillis()) {
        if (details.isBlank()) return
        synchronized(lock) {
            if (pending.size == capacity) pending.removeFirst()
            pending.addLast(ErrorDiagnosticEvent(operation, null, timestampMillis, details))
        }
        notifications.trySend(Unit)
    }

    fun drain(): List<ErrorDiagnosticEvent> = synchronized(lock) {
        pending.toList().also { pending.clear() }
    }

    private class ThrowableIdentity(error: Throwable, queue: ReferenceQueue<Throwable>) : WeakReference<Throwable>(error, queue) {
        private val identityHash = System.identityHashCode(error)
        override fun hashCode(): Int = identityHash
        override fun equals(other: Any?): Boolean =
            this === other || (other is ThrowableIdentity && get()?.let { it === other.get() } == true)
    }
}

internal object ErrorDiagnosticEvents {
    val buffer = ErrorDiagnosticBuffer()
}
