package moe.antimony.hoshi.epub

import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/** Deletion joins book-owned work before removing its files, preventing late sidecar writes. */
@Singleton
class BookWorkRegistry @Inject constructor() {
    private val work = mutableMapOf<File, suspend () -> Unit>()
    private val deleting = mutableSetOf<File>()

    fun register(root: File, cancelAndJoin: suspend () -> Unit): AutoCloseable {
        synchronized(this) {
            check(root.isDirectory && root !in deleting) { "Book is unavailable" }
            check(root !in work) { "Book work is already running" }
            work[root] = cancelAndJoin
        }
        return AutoCloseable {
            synchronized(this) {
                if (work[root] === cancelAndJoin) work.remove(root)
            }
        }
    }

    suspend fun <T> delete(root: File, action: suspend () -> T): T {
        val cancel = synchronized(this) {
            check(deleting.add(root)) { "Book deletion is already running" }
            work[root]
        }
        try {
            cancel?.invoke()
            return action()
        } finally {
            synchronized(this) { deleting.remove(root) }
        }
    }
}
