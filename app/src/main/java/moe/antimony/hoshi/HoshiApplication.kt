package moe.antimony.hoshi

import android.app.Application
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import moe.antimony.hoshi.features.diagnostics.installCrashDiagnostics
import moe.antimony.hoshi.features.update.AndroidUpdateDownloadManager
import moe.antimony.hoshi.features.update.UpdateApkCleanup
import moe.antimony.hoshi.features.update.UpdateScheduler
import moe.antimony.hoshi.features.update.UpdateStartupSnapshot
import moe.antimony.hoshi.features.update.updateDownloadStore

class HoshiApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        installCrashDiagnostics(this)
        prepareUpdateStartupState()
        UpdateScheduler.sync(this)
    }

    private fun prepareUpdateStartupState() {
        val store = updateDownloadStore()
        val downloadManager = AndroidUpdateDownloadManager(this, store)
        UpdateStartupSnapshot.initialRecord = runBlocking(Dispatchers.IO) {
            UpdateApkCleanup(
                context = this@HoshiApplication,
                downloadManager = downloadManager,
                store = store,
            ).deleteCurrentVersionApks()
            store.load()
        }
    }
}
