package moe.antimony.hoshi.navigation

import androidx.navigation3.runtime.NavKey
import kotlinx.serialization.Serializable

@Serializable
sealed interface AppRoute : NavKey {
    @Serializable
    data object MainRoute : AppRoute

    @Serializable
    data object BooksRoute : AppRoute

    @Serializable
    data object DictionaryRoute : AppRoute

    @Serializable
    data object StatisticsRoute : AppRoute

    @Serializable
    data object SettingsRoute : AppRoute

    @Serializable
    data class SettingsDetailRoute(
        val section: SettingsDetailSection,
    ) : AppRoute

    @Serializable
    data class ReaderRoute(
        val bookId: String,
    ) : AppRoute
}

@Serializable
enum class SettingsDetailSection {
    Dictionaries,
    Anki,
    Profiles,
    Appearance,
    Behavior,
    Advanced,
    Diagnostics,
    About,
}
