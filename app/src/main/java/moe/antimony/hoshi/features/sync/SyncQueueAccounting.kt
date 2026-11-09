package moe.antimony.hoshi.features.sync

import moe.antimony.hoshi.ui.UiText

data class SyncTransferSummary(
    val doneBytes: Long,
    val totalBytes: Long?,
    val bytesPerSecond: Double,
    val remainingSeconds: Double?,
    val isComplete: Boolean = false,
) {
    val fraction: Float? get() = totalBytes?.takeIf { it > 0 }?.let {
        (doneBytes.toDouble() / it).coerceIn(0.0, if (isComplete) 1.0 else 0.999).toFloat()
    }
}

/** One payload version within the manager's current connection. */
internal data class SyncPayload(
    val book: String,
    val type: SyncFileType,
    val generation: Int,
    val archived: Boolean,
    val modified: Long,
    val direction: SyncTransferDirection,
    val name: String,
) {
    fun isPending(record: SyncRecord?): Boolean =
        record != null && record.generation == generation && record.deleted == archived &&
            if (direction == SyncTransferDirection.Upload) record.uploadSource(type) == modified
            else record.downloadReference(type) == Timestamped(modified, name)
}

internal fun SyncRecord.uploadSource(type: SyncFileType): Long? =
    sources[type]?.takeIf { attached && (!deleted || type == SyncFileType.cover) && it > (files[type]?.modified ?: Long.MIN_VALUE) }

internal fun SyncRecord.downloadReference(type: SyncFileType): Timestamped<String?>? =
    files[type]?.takeIf { (!deleted || type == SyncFileType.cover) && it.modified > (sources[type] ?: Long.MIN_VALUE) }

/** Main-dispatcher accounting. The tracker owns byte callbacks and attempt speed. */
internal class SyncQueueAccounting {
    private data class Attempt(val payload: SyncPayload, val item: SyncQueueItem)
    private data class Interruption(val item: SyncQueueItem, val transfer: SyncFileTransfer)

    internal data class Snapshot(
        val summaries: Map<SyncTransferDirection, SyncTransferSummary>,
        val pendingSizes: Map<String, Long?>,
        val activeItems: List<SyncQueueItem>,
        val interruptedItems: Map<String, SyncQueueItem>,
    )

    private var inPass = false
    private val planned = mutableMapOf<SyncPayload, Long?>()
    private val completed = mutableMapOf<SyncPayload, Long>()
    private val attempts = linkedMapOf<String, Attempt>()
    private val interrupted = linkedMapOf<SyncPayload, Interruption>()

    fun beginPass() {
        planned.clear()
        completed.clear()
        inPass = true
    }

    fun endPass() {
        inPass = false
        completed.clear()
    }

    fun clear() {
        endPass()
        planned.clear()
        attempts.clear()
        interrupted.clear()
    }

    fun plan(payload: SyncPayload, size: Long?, listedFile: GoogleDriveFile?) {
        if (payload.direction == SyncTransferDirection.Upload && listedFile != null) {
            skip(payload)
        } else if (payload !in completed) {
            planned[payload] = size ?: listedFile?.payloadSize ?: planned[payload]
        }
    }

    fun applyListing(fileFor: (SyncPayload) -> GoogleDriveFile?) {
        for (payload in planned.keys.toList()) {
            plan(payload, planned[payload], fileFor(payload))
        }
    }

    fun begin(payload: SyncPayload, item: SyncQueueItem) {
        interrupted.remove(payload)
        attempts[payload.book] = Attempt(payload, item)
    }

    fun skip(payload: SyncPayload) {
        planned.remove(payload)
        interrupted.remove(payload)
        if (inPass) completed.putIfAbsent(payload, 0L)
    }

    fun finish(key: String, transfer: SyncFileTransfer, size: Long?, record: SyncRecord?, error: UiText?) {
        val (payload, item) = attempts.remove(key) ?: return
        if (!payload.isPending(record)) return
        if (size != null) {
            planned.remove(payload)
            if (inPass) completed[payload] = size
        } else {
            if (payload in planned && transfer.totalBytes != null) planned[payload] = transfer.totalBytes
            val bytes = if (transfer.direction == SyncTransferDirection.Upload) transfer.confirmedBytes else transfer.bytes
            interrupted[payload] = Interruption(item.copy(error = error), transfer.copy(bytes = bytes, bytesPerSecond = 0.0))
        }
    }

    fun snapshot(
        records: Map<String, SyncRecord>,
        active: Map<String, SyncFileTransfer>,
        failedBooks: Set<String>,
    ): Snapshot {
        planned.keys.removeAll { !it.isPending(records[it.book]) }
        interrupted.keys.removeAll { !it.isPending(records[it.book]) }
        val moving = attempts.values.mapNotNull { (payload, _) ->
            active[payload.book]?.takeIf { payload.isPending(records[payload.book]) }?.let { payload to it }
        }.toMap()
        val totals = mutableMapOf<SyncTransferDirection, DirectionTotals>()
        val pendingSizes = mutableMapOf<String, Long?>()
        for ((payload, plannedSize) in planned) {
            val size = moving[payload]?.totalBytes ?: plannedSize
            pendingSizes[payload.book] = if (payload.book in pendingSizes) {
                pendingSizes[payload.book]?.let { sum -> size?.let { sum + it } }
            } else size
        }
        val payloads = if (inPass) planned.keys + completed.keys + moving.keys else moving.keys
        for (payload in payloads) {
            val finished = completed[payload]
            val transfer = moving[payload]
            val size = finished ?: transfer?.totalBytes ?: planned[payload]
            if (size == 0L) continue
            val total = totals.getOrPut(payload.direction) { DirectionTotals() }
            val bytes = finished ?: transfer?.bytes ?: 0L
            total.done += size?.let { bytes.coerceIn(0L, it) } ?: bytes.coerceAtLeast(0L)
            total.bytes = total.bytes?.let { sum -> size?.let { sum + it } }
            total.speed += transfer?.bytesPerSecond ?: 0.0
            total.complete = total.complete && finished != null
            total.blocked = total.blocked || (finished == null && transfer == null &&
                (payload in interrupted || payload.book in failedBooks))
        }
        val interruptedItems = linkedMapOf<String, SyncQueueItem>()
        for ((item, transfer) in interrupted.values) {
            val error = item.error ?: interruptedItems[item.key]?.error
            interruptedItems[item.key] = item.copy(error = error, interruptedTransfer = transfer)
        }
        return Snapshot(
            totals.mapValues { (_, total) -> total.summary() },
            pendingSizes,
            attempts.values.map { it.item },
            interruptedItems,
        )
    }

    private class DirectionTotals {
        var done = 0L
        var bytes: Long? = 0L
        var speed = 0.0
        var complete = true
        var blocked = false

        fun summary(): SyncTransferSummary {
            val remaining = bytes?.minus(done)?.takeIf { it > 0 && !blocked && speed > 0 }
            return SyncTransferSummary(done, bytes, speed, remaining?.div(speed)?.takeIf { it.isFinite() }, complete)
        }
    }
}

internal val GoogleDriveFile.payloadSize: Long? get() = size?.toLongOrNull()?.takeIf { it >= 0 }
