package moe.antimony.hoshi.features.wallpaper

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

enum class BookCoverScaleMode {
    Fit,
    Fill,
    Stretch,
}

data class BookCoverWallpaperSettings(
    val updateLockScreen: Boolean = false,
    val exportEnabled: Boolean = false,
    val exportTargetUri: String? = null,
    val scaleMode: BookCoverScaleMode = BookCoverScaleMode.Fit,
)

class BookCoverWallpaperSettingsRepository(
    private val dataStore: DataStore<Preferences>,
) {
    val settings: Flow<BookCoverWallpaperSettings> = dataStore.data.map { preferences ->
        BookCoverWallpaperSettings(
            updateLockScreen = preferences[KeyUpdateLockScreen] ?: false,
            exportEnabled = preferences[KeyExportEnabled] ?: false,
            exportTargetUri = preferences[KeyExportTargetUri],
            scaleMode = preferences[KeyScaleMode].toBookCoverScaleMode(),
        )
    }

    suspend fun update(transform: (BookCoverWallpaperSettings) -> BookCoverWallpaperSettings) {
        dataStore.edit { preferences ->
            val current = BookCoverWallpaperSettings(
                updateLockScreen = preferences[KeyUpdateLockScreen] ?: false,
                exportEnabled = preferences[KeyExportEnabled] ?: false,
                exportTargetUri = preferences[KeyExportTargetUri],
                scaleMode = preferences[KeyScaleMode].toBookCoverScaleMode(),
            )
            val updated = transform(current)
            preferences[KeyUpdateLockScreen] = updated.updateLockScreen
            preferences[KeyExportEnabled] = updated.exportEnabled
            preferences[KeyScaleMode] = updated.scaleMode.name
            if (updated.exportTargetUri == null) {
                preferences.remove(KeyExportTargetUri)
            } else {
                preferences[KeyExportTargetUri] = updated.exportTargetUri
            }
        }
    }

    companion object {
        const val DataStoreName = "book-cover-wallpaper-settings"

        private val KeyUpdateLockScreen = booleanPreferencesKey("updateLockScreen")
        private val KeyExportEnabled = booleanPreferencesKey("exportEnabled")
        private val KeyExportTargetUri = stringPreferencesKey("exportTargetUri")
        private val KeyScaleMode = stringPreferencesKey("scaleMode")
    }
}

private val Context.bookCoverWallpaperSettingsDataStore by preferencesDataStore(
    name = BookCoverWallpaperSettingsRepository.DataStoreName,
)

fun Context.bookCoverWallpaperSettingsRepository(): BookCoverWallpaperSettingsRepository =
    BookCoverWallpaperSettingsRepository(bookCoverWallpaperSettingsDataStore)

private fun String?.toBookCoverScaleMode(): BookCoverScaleMode =
    BookCoverScaleMode.entries.firstOrNull { it.name == this } ?: BookCoverScaleMode.Fit
