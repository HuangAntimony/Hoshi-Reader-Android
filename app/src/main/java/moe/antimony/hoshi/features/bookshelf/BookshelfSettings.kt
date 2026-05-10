package moe.antimony.hoshi.features.bookshelf

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import moe.antimony.hoshi.epub.BookSortOption

data class BookshelfSettings(
    val sortOption: BookSortOption = BookSortOption.Recent,
    val showReading: Boolean = false,
)

class BookshelfSettingsRepository(
    private val dataStore: DataStore<Preferences>,
) {
    val settings: Flow<BookshelfSettings> =
        dataStore.data.map { preferences ->
            BookshelfSettings(
                sortOption = bookSortOptionFromRawValue(preferences[KEY_SORT_OPTION]),
                showReading = preferences[KEY_SHOW_READING] ?: false,
            )
        }

    suspend fun update(transform: (BookshelfSettings) -> BookshelfSettings) {
        dataStore.edit { preferences ->
            val next = transform(
                BookshelfSettings(
                    sortOption = bookSortOptionFromRawValue(preferences[KEY_SORT_OPTION]),
                    showReading = preferences[KEY_SHOW_READING] ?: false,
                ),
            )
            preferences[KEY_SORT_OPTION] = next.sortOption.name
            preferences[KEY_SHOW_READING] = next.showReading
        }
    }

    private companion object {
        val KEY_SORT_OPTION = stringPreferencesKey("bookshelfSortOption")
        val KEY_SHOW_READING = booleanPreferencesKey("bookshelfShowReading")
    }
}

private val Context.bookshelfDataStore by preferencesDataStore(name = "bookshelf-settings")

fun Context.bookshelfSettingsRepository(): BookshelfSettingsRepository =
    BookshelfSettingsRepository(bookshelfDataStore)

private fun bookSortOptionFromRawValue(rawValue: String?): BookSortOption =
    BookSortOption.entries.firstOrNull { it.name == rawValue } ?: BookSortOption.Recent
