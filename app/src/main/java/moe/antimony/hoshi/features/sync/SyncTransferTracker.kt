package moe.antimony.hoshi.features.sync

/** Immutable UI snapshot. Bytes are current-file progress; speed is useful progress per elapsed transfer time. */
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
    private data class Active(
        var state: SyncFileTransfer,
        var started: Long? = null,
        var baseline: Long = 0,
        var lastActivity: Long = 0,
    )
    private val active = linkedMapOf<String, Active>()
    private var nextId = 0L

    @Synchronized fun begin(key: String, direction: SyncTransferDirection, type: SyncFileType): Long {
        val id = ++nextId
        active[key] = Active(SyncFileTransfer(id, direction, type))
        return id
    }

    @Synchronized fun update(key: String, id: Long, update: DriveTransferUpdate) {
        val entry = active[key]?.takeIf { it.state.id == id } ?: return
        val now = clock()
        // A rejected/expired session loses its old remote offset, but not elapsed time.
        if (update.phase == SyncTransferPhase.Preparing && update.bytes == 0L) entry.baseline = 0
        if (entry.started == null && (update.phase == SyncTransferPhase.Transferring || update.deltaBytes > 0)) {
            entry.started = now
            // The initial server offset belongs to a previous process, not this transfer.
            entry.baseline = (update.bytes - update.deltaBytes).coerceAtLeast(0)
            entry.lastActivity = now
        }
        if (update.deltaBytes > 0 || update.confirmedBytes > entry.state.confirmedBytes) entry.lastActivity = now
        entry.state = entry.state.copy(phase = update.phase, bytes = update.bytes, totalBytes = update.totalBytes, confirmedBytes = update.confirmedBytes)
    }

    @Synchronized fun finish(key: String, id: Long) {
        if (active[key]?.state?.id == id) active.remove(key)
    }

    @Synchronized fun snapshot(): Map<String, SyncFileTransfer> {
        val now = clock()
        return active.mapValues { (_, entry) ->
            val started = entry.started
            // Include response/retry waits; current in-flight bytes are provisional and
            // roll back on recovery. Repeated writes never inflate useful throughput.
            val speed = if (started == null || now - entry.lastActivity >= 60_000) 0.0 else {
                (entry.state.bytes - entry.baseline).coerceAtLeast(0).toDouble() * 1000 / (now - started).coerceAtLeast(1000)
            }
            entry.state.copy(bytesPerSecond = speed)
        }
    }
}
