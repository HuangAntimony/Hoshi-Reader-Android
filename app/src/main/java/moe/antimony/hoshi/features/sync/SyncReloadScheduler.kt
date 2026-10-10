package moe.antimony.hoshi.features.sync

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

internal class SyncReloadScheduler(
    private val scope: CoroutineScope,
    private val isReaderOpen: () -> Boolean,
    private val reload: () -> Unit,
) {
    private var task: Job? = null

    fun schedule() {
        if (task != null) return
        task = scope.launch {
            delay(2_000)
            task = null
            if (!isReaderOpen()) reload()
        }
    }
}
