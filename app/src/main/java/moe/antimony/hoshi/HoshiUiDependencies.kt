package moe.antimony.hoshi

import androidx.compose.runtime.staticCompositionLocalOf
import dagger.Lazy
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import moe.antimony.hoshi.di.ApplicationScope
import moe.antimony.hoshi.dictionary.DictionaryRepository
import moe.antimony.hoshi.epub.BookRepository
import moe.antimony.hoshi.epub.EpubBookParser
import moe.antimony.hoshi.features.audio.AudioSettingsRepository
import moe.antimony.hoshi.features.audio.LocalAudioRepository
import moe.antimony.hoshi.features.backup.HoshiBackupRepository
import moe.antimony.hoshi.features.dictionary.DictionarySettingsRepository
import moe.antimony.hoshi.features.reader.ReaderFontManager
import moe.antimony.hoshi.features.reader.ReaderSettingsRepository
import moe.antimony.hoshi.features.sasayaki.SasayakiSettingsRepository
import moe.antimony.hoshi.features.storage.StorageCleanupRepository
import moe.antimony.hoshi.features.sync.DeviceCodeDriveAuthorizer
import moe.antimony.hoshi.features.sync.SyncManager
import moe.antimony.hoshi.features.sync.SyncSettingsRepository
import moe.antimony.hoshi.features.update.AndroidUpdateDownloadManager
import moe.antimony.hoshi.features.update.UpdateCheckService
import moe.antimony.hoshi.features.update.UpdateDownloadStore
import moe.antimony.hoshi.features.update.UpdatePromptEvents
import moe.antimony.hoshi.features.update.UpdateScheduler
import moe.antimony.hoshi.features.update.UpdateSettingsRepository

internal class HoshiUiDependencies @Inject constructor(
    @param:ApplicationScope private val appScopeProvider: Lazy<CoroutineScope>,
    private val bookRepositoryProvider: Lazy<BookRepository>,
    private val dictionaryRepositoryProvider: Lazy<DictionaryRepository>,
    private val readerSettingsRepositoryProvider: Lazy<ReaderSettingsRepository>,
    private val dictionarySettingsRepositoryProvider: Lazy<DictionarySettingsRepository>,
    private val audioSettingsRepositoryProvider: Lazy<AudioSettingsRepository>,
    private val sasayakiSettingsRepositoryProvider: Lazy<SasayakiSettingsRepository>,
    private val syncSettingsRepositoryProvider: Lazy<SyncSettingsRepository>,
    private val updateSettingsRepositoryProvider: Lazy<UpdateSettingsRepository>,
    private val updateDownloadStoreProvider: Lazy<UpdateDownloadStore>,
    private val epubBookParserProvider: Lazy<EpubBookParser>,
    private val readerFontManagerProvider: Lazy<ReaderFontManager>,
    private val localAudioRepositoryProvider: Lazy<LocalAudioRepository>,
    private val backupRepositoryProvider: Lazy<HoshiBackupRepository>,
    private val storageCleanupRepositoryProvider: Lazy<StorageCleanupRepository>,
    private val deviceCodeDriveAuthorizerProvider: Lazy<DeviceCodeDriveAuthorizer>,
    private val syncManagerProvider: Lazy<SyncManager>,
    private val updateDownloadManagerProvider: Lazy<AndroidUpdateDownloadManager>,
    private val updateCheckServiceProvider: Lazy<UpdateCheckService>,
    private val updatePromptEventsProvider: Lazy<UpdatePromptEvents>,
    private val updateSchedulerProvider: Lazy<UpdateScheduler>,
) {
    val appScope: CoroutineScope get() = appScopeProvider.get()
    val bookRepository: BookRepository get() = bookRepositoryProvider.get()
    val dictionaryRepository: DictionaryRepository get() = dictionaryRepositoryProvider.get()
    val readerSettingsRepository: ReaderSettingsRepository get() = readerSettingsRepositoryProvider.get()
    val dictionarySettingsRepository: DictionarySettingsRepository get() = dictionarySettingsRepositoryProvider.get()
    val audioSettingsRepository: AudioSettingsRepository get() = audioSettingsRepositoryProvider.get()
    val sasayakiSettingsRepository: SasayakiSettingsRepository get() = sasayakiSettingsRepositoryProvider.get()
    val syncSettingsRepository: SyncSettingsRepository get() = syncSettingsRepositoryProvider.get()
    val updateSettingsRepository: UpdateSettingsRepository get() = updateSettingsRepositoryProvider.get()
    val updateDownloadStore: UpdateDownloadStore get() = updateDownloadStoreProvider.get()
    val epubBookParser: EpubBookParser get() = epubBookParserProvider.get()
    val readerFontManager: ReaderFontManager get() = readerFontManagerProvider.get()
    val localAudioRepository: LocalAudioRepository get() = localAudioRepositoryProvider.get()
    val backupRepository: HoshiBackupRepository get() = backupRepositoryProvider.get()
    val storageCleanupRepository: StorageCleanupRepository get() = storageCleanupRepositoryProvider.get()
    val deviceCodeDriveAuthorizer: DeviceCodeDriveAuthorizer get() = deviceCodeDriveAuthorizerProvider.get()
    val syncManager: SyncManager get() = syncManagerProvider.get()
    val updateDownloadManager: AndroidUpdateDownloadManager get() = updateDownloadManagerProvider.get()
    val updateCheckService: UpdateCheckService get() = updateCheckServiceProvider.get()
    val updatePromptEvents: UpdatePromptEvents get() = updatePromptEventsProvider.get()
    val updateScheduler: UpdateScheduler get() = updateSchedulerProvider.get()
}

internal val LocalHoshiUiDependencies = staticCompositionLocalOf<HoshiUiDependencies> {
    error("HoshiUiDependencies is not provided.")
}
