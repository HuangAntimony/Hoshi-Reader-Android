package moe.antimony.hoshi.features.sync

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first

/** One permit per book, shared by upload, queued download and reader download. */
internal class BookTransferScheduler(limit: Int = 2) {
    private class Waiter(val key: String, val priority: Boolean)
    private val lock = Any()
    private var limit = limit.coerceIn(1, 8)
    private val active = mutableSetOf<String>()
    private val waiting = mutableListOf<Waiter>()
    private val changed = MutableStateFlow(0L)

    fun setLimit(value: Int) = synchronized(lock) {
        limit = value.coerceIn(1, 8)
        changed.value++
    }

    suspend fun <T> withBook(key: String, priority: Boolean = false, action: suspend () -> T): T {
        val waiter = Waiter(key, priority)
        synchronized(lock) { waiting += waiter; changed.value++ }
        var acquired = false
        try {
            while (!acquired) {
                val version = changed.value
                acquired = synchronized(lock) {
                    val eligible = waiting.filter { it.key !in active }
                    val next = eligible.firstOrNull { it.priority } ?: eligible.firstOrNull()
                    if (active.size < limit && next === waiter) {
                        waiting.remove(waiter)
                        active += key
                        changed.value++
                        true
                    } else false
                }
                if (!acquired) changed.first { it != version }
            }
            return action()
        } finally {
            synchronized(lock) {
                waiting.remove(waiter)
                if (acquired) active -= key
                changed.value++
            }
        }
    }
}
