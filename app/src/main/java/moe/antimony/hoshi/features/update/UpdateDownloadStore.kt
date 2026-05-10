package moe.antimony.hoshi.features.update

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

internal data class UpdateDownloadRecord(
    val versionName: String,
    val assetName: String,
    val fileName: String,
    val downloadId: Long,
    val sha256: String?,
    val downloadUrl: String? = null,
    val status: UpdateDownloadRecordStatus,
) {
    fun matches(update: AvailableUpdate): Boolean =
        versionName == update.versionName &&
            assetName == update.assetName &&
            sha256 == update.sha256
}

internal enum class UpdateDownloadRecordStatus {
    Downloading,
    Downloaded,
    Failed,
}

private val Context.updateDownloadDataStore by preferencesDataStore(name = "update-downloads")

internal fun Context.updateDownloadStore(): UpdateDownloadStore =
    UpdateDownloadStore(updateDownloadDataStore)

internal class UpdateDownloadStore(
    private val dataStore: DataStore<Preferences>,
) {
    val record: Flow<UpdateDownloadRecord?> = dataStore.data.map { it.toRecord() }

    suspend fun load(): UpdateDownloadRecord? = dataStore.data.map { it.toRecord() }.first()

    suspend fun saveDownloading(
        update: AvailableUpdate,
        fileName: String,
        downloadId: Long,
        downloadUrl: String,
    ) {
        dataStore.edit { preferences ->
            preferences[KEY_VERSION_NAME] = update.versionName
            preferences[KEY_ASSET_NAME] = update.assetName
            preferences[KEY_FILE_NAME] = fileName
            preferences[KEY_DOWNLOAD_ID] = downloadId
            preferences[KEY_DOWNLOAD_URL] = downloadUrl
            preferences[KEY_STATUS] = UpdateDownloadRecordStatus.Downloading.name
            update.sha256?.let { preferences[KEY_SHA256] = it } ?: preferences.remove(KEY_SHA256)
        }
    }

    suspend fun markDownloaded(downloadId: Long) {
        dataStore.edit { preferences ->
            if (preferences[KEY_DOWNLOAD_ID] == downloadId) {
                preferences[KEY_STATUS] = UpdateDownloadRecordStatus.Downloaded.name
            }
        }
    }

    suspend fun markFailed(downloadId: Long) {
        dataStore.edit { preferences ->
            if (preferences[KEY_DOWNLOAD_ID] == downloadId) {
                preferences[KEY_STATUS] = UpdateDownloadRecordStatus.Failed.name
            }
        }
    }

    private fun Preferences.toRecord(): UpdateDownloadRecord? {
        val versionName = this[KEY_VERSION_NAME] ?: return null
        val fileName = this[KEY_FILE_NAME] ?: return null
        val assetName = this[KEY_ASSET_NAME] ?: fileName
        val downloadId = this[KEY_DOWNLOAD_ID] ?: return null
        val status = this[KEY_STATUS]
            ?.let { raw -> UpdateDownloadRecordStatus.entries.firstOrNull { it.name == raw } }
            ?: return null
        return UpdateDownloadRecord(
            versionName = versionName,
            assetName = assetName,
            fileName = fileName,
            downloadId = downloadId,
            sha256 = this[KEY_SHA256],
            downloadUrl = this[KEY_DOWNLOAD_URL],
            status = status,
        )
    }

    companion object {
        private val KEY_VERSION_NAME = stringPreferencesKey("versionName")
        private val KEY_ASSET_NAME = stringPreferencesKey("assetName")
        private val KEY_FILE_NAME = stringPreferencesKey("fileName")
        private val KEY_DOWNLOAD_ID = longPreferencesKey("downloadId")
        private val KEY_DOWNLOAD_URL = stringPreferencesKey("downloadUrl")
        private val KEY_SHA256 = stringPreferencesKey("sha256")
        private val KEY_STATUS = stringPreferencesKey("status")
    }
}
