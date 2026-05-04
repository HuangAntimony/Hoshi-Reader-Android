package moe.antimony.hoshi

import android.content.ContentResolver
import android.content.Context
import androidx.compose.runtime.staticCompositionLocalOf
import moe.antimony.hoshi.dictionary.DictionaryRepository
import moe.antimony.hoshi.epub.BookRepository
import moe.antimony.hoshi.features.audio.AudioSettingsRepository
import moe.antimony.hoshi.features.audio.LocalAudioRepository
import moe.antimony.hoshi.features.audio.audioSettingsRepository
import moe.antimony.hoshi.features.bookshelf.AndroidBookshelfRepository
import moe.antimony.hoshi.features.bookshelf.BookshelfRepository
import moe.antimony.hoshi.features.dictionary.AndroidDictionaryViewModelRepository
import moe.antimony.hoshi.features.dictionary.DictionarySettingsRepository
import moe.antimony.hoshi.features.dictionary.DictionaryViewModelRepository
import moe.antimony.hoshi.features.dictionary.dictionarySettingsRepository
import moe.antimony.hoshi.features.reader.ReaderFontManager
import moe.antimony.hoshi.features.reader.ReaderSettingsRepository
import moe.antimony.hoshi.features.reader.readerSettingsRepository
import moe.antimony.hoshi.features.sasayaki.SasayakiSettingsRepository
import moe.antimony.hoshi.features.sasayaki.sasayakiSettingsRepository
import moe.antimony.hoshi.navigation.ReaderRouteStateHolder

internal class HoshiAppContainer(context: Context) {
    private val appContext = context.applicationContext

    val bookRepository: BookRepository = BookRepository(appContext.filesDir)
    val dictionaryRepository: DictionaryRepository = DictionaryRepository(appContext.filesDir, appContext.cacheDir)
    val readerSettingsRepository: ReaderSettingsRepository = appContext.readerSettingsRepository()
    val dictionarySettingsRepository: DictionarySettingsRepository = appContext.dictionarySettingsRepository()
    val audioSettingsRepository: AudioSettingsRepository = appContext.audioSettingsRepository()
    val sasayakiSettingsRepository: SasayakiSettingsRepository = appContext.sasayakiSettingsRepository()
    val readerFontManager: ReaderFontManager = ReaderFontManager(appContext.filesDir)
    val localAudioRepository: LocalAudioRepository = LocalAudioRepository(appContext.filesDir)

    fun readerRouteStateHolder(): ReaderRouteStateHolder =
        ReaderRouteStateHolder(bookRepository)

    fun bookshelfRepository(contentResolver: ContentResolver): BookshelfRepository =
        AndroidBookshelfRepository(
            contentResolver = contentResolver,
            bookRepository = bookRepository,
            dictionaryRepository = dictionaryRepository,
        )

    fun dictionaryViewModelRepository(contentResolver: ContentResolver): DictionaryViewModelRepository =
        AndroidDictionaryViewModelRepository(
            contentResolver = contentResolver,
            dictionaryRepository = dictionaryRepository,
            settingsRepository = dictionarySettingsRepository,
        )
}

internal val LocalHoshiAppContainer = staticCompositionLocalOf<HoshiAppContainer> {
    error("HoshiAppContainer is not provided.")
}
