package moe.antimony.hoshi.navigation

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

internal class ReaderAutoSyncExportController(
    private val scope: CoroutineScope,
    private val debounceMillis: Long = ReaderAutoSyncDebounceMillis,
) {
    private var latestSaveJob: Job? = null
    private var debounceJob: Job? = null
    private var exportJob: Job? = null
    private var pendingExport: (suspend () -> Unit)? = null

    fun launchSave(save: suspend () -> Unit): Job {
        val previousSaveJob = latestSaveJob
        val job = scope.launch {
            previousSaveJob?.join()
            save()
        }
        latestSaveJob = job
        return job
    }

    fun scheduleExport(enabled: Boolean, export: suspend () -> Unit) {
        if (!enabled) return
        pendingExport = export
        if (debounceJob?.isActive == true) return
        debounceJob = scope.launch {
            delay(debounceMillis)
            debounceJob = null
            runPendingExport()
        }
    }

    fun flushExport(enabled: Boolean): Job? {
        if (!enabled || pendingExport == null) return null
        debounceJob?.cancel()
        debounceJob = null
        return scope.launch {
            runPendingExport()
        }
    }

    private suspend fun runPendingExport() {
        exportJob?.join()
        val export = pendingExport ?: return
        pendingExport = null
        val saveJob = latestSaveJob
        val job = scope.launch {
            saveJob?.join()
            export()
        }
        exportJob = job
        try {
            job.join()
        } finally {
            if (exportJob == job) {
                exportJob = null
            }
        }
    }
}

internal const val ReaderAutoSyncDebounceMillis = 30_000L
