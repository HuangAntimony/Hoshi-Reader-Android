package moe.antimony.hoshi.navigation

import androidx.navigation3.runtime.NavKey
import moe.antimony.hoshi.features.dictionary.DictionarySettings
import moe.antimony.hoshi.features.reader.ReaderSettings

internal class AppLaunchRouteStateHolder {
    private var defaultRouteApplied = false

    suspend fun defaultRouteAfterSettingsLoad(
        readerSettings: ReaderSettings,
        dictionarySettings: DictionarySettings,
        hasPendingImport: Boolean,
        hasPendingDictionaryLookup: Boolean = false,
        isBooksTabSelected: Boolean,
        backStack: List<NavKey>,
        recentBookIdProvider: suspend () -> String?,
    ): AppRoute? {
        if (defaultRouteApplied) {
            return null
        }
        defaultRouteApplied = true
        if (
            hasPendingImport ||
            hasPendingDictionaryLookup ||
            !isBooksTabSelected ||
            backStack.size != 1 ||
            backStack.lastOrNull() != AppRoute.BooksRoute
        ) {
            return null
        }
        if (readerSettings.openLastReadBookOnLaunch) {
            recentBookIdProvider()?.takeIf { it.isNotBlank() }?.let { bookId ->
                return AppRoute.ReaderRoute(bookId)
            }
        }
        if (dictionarySettings.dictionaryTabDefault) {
            return AppRoute.DictionaryRoute
        }
        return null
    }
}
