package moe.antimony.hoshi.features.sync

/** Sizes payload requests using their full send + acknowledgement duration. */
internal class DriveUploadChunkSizer {
    var bytes: Long = 4L * 1024 * 1024
        private set

    fun confirmed(bytes: Long, elapsedMillis: Long) {
        if (bytes <= 0) return
        val target = (bytes.toDouble() * 30_000 / elapsedMillis.coerceAtLeast(1))
            .coerceIn(MIN.toDouble(), MAX.toDouble()).toLong()
        this.bytes = target / MIN * MIN
    }

    fun failed() { bytes = (bytes / 2 / MIN * MIN).coerceAtLeast(MIN) }

    private companion object {
        const val MIN = 256L * 1024
        const val MAX = 8L * 1024 * 1024
    }
}
