package moe.antimony.hoshi

import android.app.Application
import android.content.Context
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.bitmapFactoryMaxParallelism
import coil3.memory.MemoryCache
import dagger.Lazy
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.runBlocking
import moe.antimony.hoshi.di.IoDispatcher
import moe.antimony.hoshi.features.bookshelf.BookCoverRecoveryDecoderFactory
import moe.antimony.hoshi.features.bookshelf.BookCoverFetcher
import moe.antimony.hoshi.features.bookshelf.BookCoverKeyer
import moe.antimony.hoshi.features.bookshelf.BookCoverThumbnailStore
import moe.antimony.hoshi.features.diagnostics.installCrashDiagnostics
import moe.antimony.hoshi.features.dictionary.DictionaryAutoUpdateScheduler
import moe.antimony.hoshi.features.update.UpdateApkCleanup
import moe.antimony.hoshi.features.update.UpdateScheduler
import moe.antimony.hoshi.features.update.UpdateStartupSnapshot
import moe.antimony.hoshi.features.update.UpdateDownloadStore

@HiltAndroidApp
class HoshiApplication : Application(), Configuration.Provider, SingletonImageLoader.Factory {
    @Inject internal lateinit var updateApkCleanup: UpdateApkCleanup
    @Inject internal lateinit var dictionaryAutoUpdateScheduler: Lazy<DictionaryAutoUpdateScheduler>
    @Inject internal lateinit var updateDownloadStore: UpdateDownloadStore
    @Inject internal lateinit var updateScheduler: Lazy<UpdateScheduler>
    @Inject internal lateinit var workerFactory: HiltWorkerFactory
    @Inject @IoDispatcher internal lateinit var ioDispatcher: CoroutineDispatcher
    @Inject internal lateinit var bookCoverThumbnailStore: Lazy<BookCoverThumbnailStore>

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

    override fun newImageLoader(context: Context): ImageLoader =
        ImageLoader.Builder(context)
            .bitmapFactoryMaxParallelism(2)
            .memoryCache {
                MemoryCache.Builder()
                    .maxSizePercent(context, percent = 0.20)
                    .build()
            }
            .components {
                add(BookCoverKeyer)
                add(BookCoverFetcher.Factory(bookCoverThumbnailStore.get()))
                add(BookCoverRecoveryDecoderFactory(bookCoverThumbnailStore.get()))
            }
            .build()

    private fun prepareUpdateStartupState() {
        UpdateStartupSnapshot.initialRecord = runBlocking(ioDispatcher) {
            updateApkCleanup.deleteCurrentVersionApks()
            updateDownloadStore.load()
        }
    }
}
