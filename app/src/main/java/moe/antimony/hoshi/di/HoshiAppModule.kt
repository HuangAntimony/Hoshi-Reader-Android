package moe.antimony.hoshi.di

import android.content.ContentResolver
import android.content.Context
import androidx.work.WorkManager
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.io.File
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import moe.antimony.hoshi.BuildConfig
import moe.antimony.hoshi.features.anki.AnkiSettingsRepository
import moe.antimony.hoshi.features.anki.ankiSettingsRepository
import moe.antimony.hoshi.features.audio.AudioSettingsRepository
import moe.antimony.hoshi.features.audio.audioSettingsRepository
import moe.antimony.hoshi.features.bookshelf.BookshelfSettingsRepository
import moe.antimony.hoshi.features.bookshelf.bookshelfSettingsRepository
import moe.antimony.hoshi.features.dictionary.DictionarySettingsRepository
import moe.antimony.hoshi.features.dictionary.dictionarySettingsRepository
import moe.antimony.hoshi.features.reader.ReaderSettingsRepository
import moe.antimony.hoshi.features.reader.readerSettingsRepository
import moe.antimony.hoshi.features.sasayaki.SasayakiSettingsRepository
import moe.antimony.hoshi.features.sasayaki.sasayakiSettingsRepository
import moe.antimony.hoshi.features.sync.DriveSyncDataSource
import moe.antimony.hoshi.features.sync.SyncSettingsRepository
import moe.antimony.hoshi.features.sync.syncSettingsRepository
import moe.antimony.hoshi.features.update.UpdateDownloadStore
import moe.antimony.hoshi.features.update.UpdateSettingsRepository
import moe.antimony.hoshi.features.update.updateDownloadStore
import moe.antimony.hoshi.features.update.updateSettingsRepository

@Module
@InstallIn(SingletonComponent::class)
internal object HoshiAppModule {
    @Provides
    @Singleton
    @ApplicationScope
    fun provideApplicationScope(
        @MainDispatcher mainDispatcher: CoroutineDispatcher,
    ): CoroutineScope = CoroutineScope(SupervisorJob() + mainDispatcher)

    @Provides
    @DefaultDispatcher
    fun provideDefaultDispatcher(): CoroutineDispatcher = Dispatchers.Default

    @Provides
    @IoDispatcher
    fun provideIoDispatcher(): CoroutineDispatcher = Dispatchers.IO

    @Provides
    @MainDispatcher
    fun provideMainDispatcher(): CoroutineDispatcher = Dispatchers.Main.immediate

    @Provides
    @Singleton
    fun provideContentResolver(@ApplicationContext context: Context): ContentResolver =
        context.contentResolver

    @Provides
    @Singleton
    @FilesDir
    fun provideFilesDir(@ApplicationContext context: Context): File = context.filesDir

    @Provides
    @Singleton
    @CacheDir
    fun provideCacheDir(@ApplicationContext context: Context): File = context.cacheDir

    @Provides
    @Singleton
    fun provideWorkManager(@ApplicationContext context: Context): WorkManager =
        WorkManager.getInstance(context)

    @Provides
    @Singleton
    fun provideReaderSettingsRepository(@ApplicationContext context: Context): ReaderSettingsRepository =
        context.readerSettingsRepository()

    @Provides
    @Singleton
    fun provideDictionarySettingsRepository(@ApplicationContext context: Context): DictionarySettingsRepository =
        context.dictionarySettingsRepository()

    @Provides
    @Singleton
    fun provideAudioSettingsRepository(@ApplicationContext context: Context): AudioSettingsRepository =
        context.audioSettingsRepository()

    @Provides
    @Singleton
    fun provideAnkiSettingsRepository(@ApplicationContext context: Context): AnkiSettingsRepository =
        context.ankiSettingsRepository()

    @Provides
    @Singleton
    fun provideSasayakiSettingsRepository(@ApplicationContext context: Context): SasayakiSettingsRepository =
        context.sasayakiSettingsRepository()

    @Provides
    @Singleton
    fun provideSyncSettingsRepository(
        @ApplicationContext context: Context,
        drive: DriveSyncDataSource,
    ): SyncSettingsRepository =
        context.syncSettingsRepository(drive)

    @Provides
    @Singleton
    fun provideBookshelfSettingsRepository(@ApplicationContext context: Context): BookshelfSettingsRepository =
        context.bookshelfSettingsRepository()

    @Provides
    @Singleton
    fun provideUpdateSettingsRepository(@ApplicationContext context: Context): UpdateSettingsRepository =
        context.updateSettingsRepository()

    @Provides
    @Singleton
    fun provideUpdateDownloadStore(@ApplicationContext context: Context): UpdateDownloadStore =
        context.updateDownloadStore()

    @Provides
    @CurrentVersionName
    fun provideCurrentVersionName(): String = BuildConfig.VERSION_NAME
}
