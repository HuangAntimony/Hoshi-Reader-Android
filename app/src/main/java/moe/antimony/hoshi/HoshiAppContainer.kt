package moe.antimony.hoshi

import android.content.ContentResolver
import android.content.Context
import androidx.compose.runtime.staticCompositionLocalOf
import moe.antimony.hoshi.dictionary.DictionaryRepository
import moe.antimony.hoshi.epub.BookRepository
import moe.antimony.hoshi.features.audio.AudioSettingsRepository
import moe.antimony.hoshi.features.audio.LocalAudioRepository
import moe.antimony.hoshi.features.audio.audioSettingsRepository
import moe.antimony.hoshi.features.anki.AndroidAnkiContentApi
import moe.antimony.hoshi.features.anki.AnkiDroidBackendAdapter
import moe.antimony.hoshi.features.anki.AnkiRepository
import moe.antimony.hoshi.features.anki.AnkiSettingsRepository
import moe.antimony.hoshi.features.anki.ankiSettingsRepository
import moe.antimony.hoshi.features.backup.HoshiBackupRepository
import moe.antimony.hoshi.features.bookshelf.AndroidBookshelfRepository
import moe.antimony.hoshi.features.bookshelf.BookshelfRepository
import moe.antimony.hoshi.features.bookshelf.BookshelfSettingsRepository
import moe.antimony.hoshi.features.bookshelf.bookshelfSettingsRepository
import moe.antimony.hoshi.features.dictionary.AndroidDictionaryViewModelRepository
import moe.antimony.hoshi.features.dictionary.AndroidDictionarySearchRepository
import moe.antimony.hoshi.features.dictionary.DictionarySettingsRepository
import moe.antimony.hoshi.features.dictionary.DictionarySearchRepository
import moe.antimony.hoshi.features.dictionary.DictionaryViewModelRepository
import moe.antimony.hoshi.features.dictionary.dictionarySettingsRepository
import moe.antimony.hoshi.features.reader.ReaderFontManager
import moe.antimony.hoshi.features.reader.ReaderSettingsRepository
import moe.antimony.hoshi.features.reader.readerSettingsRepository
import moe.antimony.hoshi.features.sasayaki.SasayakiSettingsRepository
import moe.antimony.hoshi.features.sasayaki.sasayakiSettingsRepository
import moe.antimony.hoshi.features.update.AndroidUpdateDownloadManager
import moe.antimony.hoshi.features.update.GitHubReleaseUpdateRepository
import moe.antimony.hoshi.features.update.UpdateCheckService
import moe.antimony.hoshi.features.update.UpdateDownloadStore
import moe.antimony.hoshi.features.update.UpdateSettingsRepository
import moe.antimony.hoshi.features.update.updateDownloadStore
import moe.antimony.hoshi.features.update.updateSettingsRepository
import moe.antimony.hoshi.navigation.ReaderRouteStateHolder

internal class HoshiAppContainer(context: Context) {
    private val appContext = context.applicationContext

    val bookRepository: BookRepository = BookRepository(appContext.filesDir)
    val dictionaryRepository: DictionaryRepository = DictionaryRepository(appContext.filesDir, appContext.cacheDir)
    val readerSettingsRepository: ReaderSettingsRepository = appContext.readerSettingsRepository()
    val dictionarySettingsRepository: DictionarySettingsRepository = appContext.dictionarySettingsRepository()
    val audioSettingsRepository: AudioSettingsRepository = appContext.audioSettingsRepository()
    val ankiSettingsRepository: AnkiSettingsRepository = appContext.ankiSettingsRepository()
    val sasayakiSettingsRepository: SasayakiSettingsRepository = appContext.sasayakiSettingsRepository()
    val bookshelfSettingsRepository: BookshelfSettingsRepository = appContext.bookshelfSettingsRepository()
    val updateSettingsRepository: UpdateSettingsRepository = appContext.updateSettingsRepository()
    val updateDownloadStore: UpdateDownloadStore = appContext.updateDownloadStore()
    val readerFontManager: ReaderFontManager = ReaderFontManager(appContext.filesDir)
    val localAudioRepository: LocalAudioRepository = LocalAudioRepository(appContext.filesDir)
    val backupRepository: HoshiBackupRepository = HoshiBackupRepository(appContext.filesDir)
    val ankiRepository: AnkiRepository = AnkiRepository(
        context = appContext,
        backend = AnkiDroidBackendAdapter(AndroidAnkiContentApi(appContext)),
        settingsRepository = ankiSettingsRepository,
    )
    val updateDownloadManager: AndroidUpdateDownloadManager = AndroidUpdateDownloadManager(
        context = appContext,
        store = updateDownloadStore,
    )
    val updateCheckService: UpdateCheckService = UpdateCheckService(
        currentVersionName = BuildConfig.VERSION_NAME,
        releaseRepository = GitHubReleaseUpdateRepository(),
        downloadController = updateDownloadManager,
    )

    fun readerRouteStateHolder(): ReaderRouteStateHolder =
        ReaderRouteStateHolder(bookRepository)

    fun bookshelfRepository(contentResolver: ContentResolver): BookshelfRepository =
        AndroidBookshelfRepository(
            contentResolver = contentResolver,
            bookRepository = bookRepository,
            dictionaryRepository = dictionaryRepository,
            settingsRepository = bookshelfSettingsRepository,
        )

    fun dictionaryViewModelRepository(contentResolver: ContentResolver): DictionaryViewModelRepository =
        AndroidDictionaryViewModelRepository(
            contentResolver = contentResolver,
            dictionaryRepository = dictionaryRepository,
            settingsRepository = dictionarySettingsRepository,
        )

    fun dictionarySearchRepository(): DictionarySearchRepository =
        AndroidDictionarySearchRepository(
            dictionaryRepository = dictionaryRepository,
            dictionarySettingsRepository = dictionarySettingsRepository,
            audioSettingsRepository = audioSettingsRepository,
        )
}

internal val LocalHoshiAppContainer = staticCompositionLocalOf<HoshiAppContainer> {
    error("HoshiAppContainer is not provided.")
}
