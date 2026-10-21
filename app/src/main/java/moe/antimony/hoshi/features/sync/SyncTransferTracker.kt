package moe.antimony.hoshi.features.sync

/** Immutable UI snapshot. Bytes are current-file progress; speed includes retransmission. */
data class SyncFileTransfer(
    val id: Long,
    val direction: SyncTransferDirection,
    val type: SyncFileType,
    val phase: SyncTransferPhase = SyncTransferPhase.Preparing,
    val bytes: Long = 0,
    val totalBytes: Long? = null,
    val confirmedBytes: Long = 0,
    val bytesPerSecond: Double = 0.0,
) {
    val fraction: Float? get() = totalBytes?.takeIf { it > 0 }?.let {
        // A full local socket write is not a remotely completed upload.
        (bytes.toDouble() / it).coerceIn(0.0, if (direction == SyncTransferDirection.Upload) 0.999 else 1.0).toFloat()
    }
}

/** Thread-safe callbacks from OkHttp; publishing is separately throttled by the manager. */
internal class SyncTransferTracker(private val clock: () -> Long = { System.nanoTime() / 1_000_000 }) {
    private data class Sample(val at: Long, val bytes: Long)
    private data class Active(var state: SyncFileTransfer, val started: Long, val samples: ArrayDeque<Sample> = ArrayDeque())
    private val active = linkedMapOf<String, Active>()
    private var nextId = 0L

    @Synchronized fun begin(key: String, direction: SyncTransferDirection, type: SyncFileType): Long {
        val id = ++nextId
        active[key] = Active(SyncFileTransfer(id, direction, type), clock())
        return id
    }

    @Synchronized fun update(key: String, id: Long, update: DriveTransferUpdate) {
        val entry = active[key]?.takeIf { it.state.id == id } ?: return
        val now = clock()
        if (update.deltaBytes > 0) entry.samples += Sample(now, update.deltaBytes)
        trim(entry, now)
        entry.state = entry.state.copy(phase = update.phase, bytes = update.bytes, totalBytes = update.totalBytes, confirmedBytes = update.confirmedBytes)
    }

    @Synchronized fun finish(key: String, id: Long) {
        if (active[key]?.state?.id == id) active.remove(key)
    }

    @Synchronized fun snapshot(): Map<String, SyncFileTransfer> {
        val now = clock()
        return active.mapValues { (_, entry) ->
            trim(entry, now)
            val elapsed = (now - entry.started).coerceIn(1, 3000)
            entry.state.copy(bytesPerSecond = entry.samples.sumOf { it.bytes }.toDouble() * 1000 / elapsed)
        }
    }

    private fun trim(entry: Active, now: Long) {
        while (entry.samples.firstOrNull()?.let { it.at <= now - 3000 } == true) entry.samples.removeFirst()
    }
}
