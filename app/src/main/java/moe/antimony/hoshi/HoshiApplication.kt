package moe.antimony.hoshi

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import dagger.Lazy
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.runBlocking
import moe.antimony.hoshi.di.IoDispatcher
import moe.antimony.hoshi.features.diagnostics.installCrashDiagnostics
import moe.antimony.hoshi.features.dictionary.DictionaryAutoUpdateScheduler
import moe.antimony.hoshi.features.update.UpdateApkCleanup
import moe.antimony.hoshi.features.update.UpdateScheduler
import moe.antimony.hoshi.features.update.UpdateStartupSnapshot
import moe.antimony.hoshi.features.update.UpdateDownloadStore

@HiltAndroidApp
class HoshiApplication : Application(), Configuration.Provider {
    @Inject internal lateinit var updateApkCleanup: UpdateApkCleanup
    @Inject internal lateinit var dictionaryAutoUpdateScheduler: Lazy<DictionaryAutoUpdateScheduler>
    @Inject internal lateinit var updateDownloadStore: UpdateDownloadStore
    @Inject internal lateinit var updateScheduler: Lazy<UpdateScheduler>
    @Inject internal lateinit var workerFactory: HiltWorkerFactory
    @Inject @IoDispatcher internal lateinit var ioDispatcher: CoroutineDispatcher

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()

    override fun onCreate() {
        super.onCreate()
        installCrashDiagnostics(this)
        prepareUpdateStartupState()
        updateScheduler.get().sync()
        dictionaryAutoUpdateScheduler.get().registerProcessForegroundChecks()
    }

    private fun prepareUpdateStartupState() {
        UpdateStartupSnapshot.initialRecord = runBlocking(ioDispatcher) {
            updateApkCleanup.deleteCurrentVersionApks()
            updateDownloadStore.load()
        }
    }
}
