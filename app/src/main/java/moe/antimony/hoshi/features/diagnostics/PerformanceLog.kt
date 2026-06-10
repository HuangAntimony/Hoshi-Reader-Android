package moe.antimony.hoshi.features.diagnostics

internal object PerformanceLog {
    const val ReaderTag = "HoshiReaderPerf"

    fun start(): Long = System.nanoTime()

    fun elapsedMillis(startNanos: Long): Long =
        (System.nanoTime() - startNanos) / 1_000_000L

    fun d(tag: String, message: String) {
        runCatching {
            val logClass = Class.forName("android.util.Log")
            val method = logClass.getMethod("d", String::class.java, String::class.java)
            method.invoke(null, tag, message)
        }.getOrElse {
            println("$tag: $message")
        }
    }

    fun dElapsed(tag: String, label: String, startNanos: Long, details: String = "") {
        val suffix = details.takeIf { it.isNotBlank() }?.let { " $it" }.orEmpty()
        d(tag, "$label took ${elapsedMillis(startNanos)}ms$suffix")
    }
}
