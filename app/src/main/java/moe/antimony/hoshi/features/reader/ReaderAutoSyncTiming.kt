package moe.antimony.hoshi.features.reader

import moe.antimony.hoshi.features.sync.SyncProvider

internal enum class ReaderLifecycleAutoSyncEvent {
    Resume,
    Pause,
    Dispose,
}

internal data class ReaderLifecycleAutoSyncPlan(
    val flushPendingProgressSave: Boolean = false,
    val saveCurrentDisplayedPosition: Boolean = false,
    val flushAutoSyncExport: Boolean = false,
    val importOnForeground: Boolean = false,
)

internal fun readerLifecycleAutoSyncPlan(
    event: ReaderLifecycleAutoSyncEvent,
    inactiveElapsedMillis: Long? = null,
    provider: SyncProvider = SyncProvider.Ttu,
): ReaderLifecycleAutoSyncPlan = when (event) {
    ReaderLifecycleAutoSyncEvent.Resume -> ReaderLifecycleAutoSyncPlan(
        importOnForeground = inactiveElapsedMillis != null &&
            (provider == SyncProvider.Gdrive || inactiveElapsedMillis >= AutoSyncForegroundThresholdMillis),
    )
    ReaderLifecycleAutoSyncEvent.Pause,
    ReaderLifecycleAutoSyncEvent.Dispose -> ReaderLifecycleAutoSyncPlan(
        flushPendingProgressSave = true,
        flushAutoSyncExport = true,
    )
}

internal const val AutoSyncForegroundThresholdMillis = 10L * 60L * 1_000L
