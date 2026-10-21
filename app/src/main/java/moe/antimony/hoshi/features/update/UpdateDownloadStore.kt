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
    val releaseUrl: String = "",
    val assetName: String,
    val fileName: String,
    val downloadId: Long?,
    val sha256: String?,
    val downloadUrl: String? = null,
    val fallbackDownloadUrls: List<String> = emptyList(),
    val status: UpdateDownloadRecordStatus,
    val failureDetails: String? = null,
    val previewCommitSha: String? = null,
    val previewTag: String? = null,
    val previewPublishedAt: String? = null,
) {
    fun matches(update: AvailableUpdate): Boolean =
        previewCommitSha == update.previewCommitSha &&
            versionName == update.versionName &&
            assetName == update.assetName &&
            sha256 == update.sha256

    fun toAvailableUpdate(): AvailableUpdate? {
        val url = downloadUrl ?: return null
        return AvailableUpdate(
            versionName = versionName,
            releaseUrl = releaseUrl,
            assetName = assetName,
            downloadUrl = url,
            fallbackDownloadUrls = fallbackDownloadUrls,
            sha256 = sha256,
            previewCommitSha = previewCommitSha,
            previewTag = previewTag,
            previewPublishedAt = previewPublishedAt,
        )
    }
}

internal enum class UpdateDownloadRecordStatus {
    Available,
    Skipped,
    Downloading,
    Downloaded,
    Failed,
}

private val Context.previewDownloadDataStore by preferencesDataStore(name = "preview-downloads")

internal fun Context.previewDownloadStore(): UpdateDownloadStore = UpdateDownloadStore(previewDownloadDataStore)

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
            update.previewCommitSha?.let { preferences[KEY_PREVIEW_SHA] = it } ?: preferences.remove(KEY_PREVIEW_SHA)
            update.previewTag?.let { preferences[KEY_PREVIEW_TAG] = it } ?: preferences.remove(KEY_PREVIEW_TAG)
            update.previewPublishedAt?.let { preferences[KEY_PREVIEW_PUBLISHED] = it } ?: preferences.remove(KEY_PREVIEW_PUBLISHED)
            preferences[KEY_VERSION_NAME] = update.versionName
            preferences[KEY_RELEASE_URL] = update.releaseUrl
            preferences[KEY_ASSET_NAME] = update.assetName
            preferences[KEY_FILE_NAME] = fileName
            preferences[KEY_DOWNLOAD_ID] = downloadId
            preferences[KEY_DOWNLOAD_URL] = downloadUrl
            preferences[KEY_FALLBACK_DOWNLOAD_URLS] = update.fallbackDownloadUrls.joinToString(separator = "\n")
            preferences[KEY_STATUS] = UpdateDownloadRecordStatus.Downloading.name
            preferences.remove(KEY_FAILURE_DETAILS)
            update.sha256?.let { preferences[KEY_SHA256] = it } ?: preferences.remove(KEY_SHA256)
        }
    }

    suspend fun saveAvailable(update: AvailableUpdate) {
        dataStore.edit { preferences ->
            update.previewCommitSha?.let { preferences[KEY_PREVIEW_SHA] = it } ?: preferences.remove(KEY_PREVIEW_SHA)
            update.previewTag?.let { preferences[KEY_PREVIEW_TAG] = it } ?: preferences.remove(KEY_PREVIEW_TAG)
            update.previewPublishedAt?.let { preferences[KEY_PREVIEW_PUBLISHED] = it } ?: preferences.remove(KEY_PREVIEW_PUBLISHED)
            preferences[KEY_VERSION_NAME] = update.versionName
            preferences[KEY_RELEASE_URL] = update.releaseUrl
            preferences[KEY_ASSET_NAME] = update.assetName
            preferences[KEY_FILE_NAME] = AndroidUpdateDownloadManager.UpdateFileName
            preferences.remove(KEY_DOWNLOAD_ID)
            preferences[KEY_DOWNLOAD_URL] = update.downloadUrl
            preferences[KEY_FALLBACK_DOWNLOAD_URLS] = update.fallbackDownloadUrls.joinToString(separator = "\n")
            preferences[KEY_STATUS] = UpdateDownloadRecordStatus.Available.name
            preferences.remove(KEY_FAILURE_DETAILS)
            update.sha256?.let { preferences[KEY_SHA256] = it } ?: preferences.remove(KEY_SHA256)
        }
    }

    suspend fun skip(update: AvailableUpdate) {
        dataStore.edit { preferences ->
            update.previewCommitSha?.let { preferences[KEY_PREVIEW_SHA] = it } ?: preferences.remove(KEY_PREVIEW_SHA)
            update.previewTag?.let { preferences[KEY_PREVIEW_TAG] = it } ?: preferences.remove(KEY_PREVIEW_TAG)
            update.previewPublishedAt?.let { preferences[KEY_PREVIEW_PUBLISHED] = it } ?: preferences.remove(KEY_PREVIEW_PUBLISHED)
            preferences[KEY_VERSION_NAME] = update.versionName
            preferences[KEY_RELEASE_URL] = update.releaseUrl
            preferences[KEY_ASSET_NAME] = update.assetName
            preferences[KEY_FILE_NAME] = AndroidUpdateDownloadManager.UpdateFileName
            preferences.remove(KEY_DOWNLOAD_ID)
            preferences[KEY_DOWNLOAD_URL] = update.downloadUrl
            preferences[KEY_FALLBACK_DOWNLOAD_URLS] = update.fallbackDownloadUrls.joinToString(separator = "\n")
            preferences[KEY_STATUS] = UpdateDownloadRecordStatus.Skipped.name
            preferences.remove(KEY_FAILURE_DETAILS)
            update.sha256?.let { preferences[KEY_SHA256] = it } ?: preferences.remove(KEY_SHA256)
        }
    }

    suspend fun markDownloaded(downloadId: Long) {
        dataStore.edit { preferences ->
            if (preferences[KEY_DOWNLOAD_ID] == downloadId) {
                preferences[KEY_STATUS] = UpdateDownloadRecordStatus.Downloaded.name
                preferences.remove(KEY_FAILURE_DETAILS)
            }
        }
    }

    suspend fun markFailed(downloadId: Long, details: String? = null) {
        dataStore.edit { preferences ->
            if (preferences[KEY_DOWNLOAD_ID] == downloadId) {
                preferences[KEY_STATUS] = UpdateDownloadRecordStatus.Failed.name
                details?.takeIf(String::isNotBlank)?.let { preferences[KEY_FAILURE_DETAILS] = it }
            }
        }
    }

    suspend fun clear() {
        dataStore.edit { preferences ->
            preferences.remove(KEY_PREVIEW_SHA)
            preferences.remove(KEY_PREVIEW_TAG)
            preferences.remove(KEY_PREVIEW_PUBLISHED)
            preferences.remove(KEY_VERSION_NAME)
            preferences.remove(KEY_RELEASE_URL)
            preferences.remove(KEY_ASSET_NAME)
            preferences.remove(KEY_FILE_NAME)
            preferences.remove(KEY_DOWNLOAD_ID)
            preferences.remove(KEY_DOWNLOAD_URL)
            preferences.remove(KEY_FALLBACK_DOWNLOAD_URLS)
            preferences.remove(KEY_SHA256)
            preferences.remove(KEY_STATUS)
            preferences.remove(KEY_FAILURE_DETAILS)
        }
    }

    private fun Preferences.toRecord(): UpdateDownloadRecord? {
        val versionName = this[KEY_VERSION_NAME] ?: return null
        val fileName = this[KEY_FILE_NAME] ?: return null
        val assetName = this[KEY_ASSET_NAME] ?: fileName
        val status = this[KEY_STATUS]
            ?.let { raw -> UpdateDownloadRecordStatus.entries.firstOrNull { it.name == raw } }
            ?: return null
        return UpdateDownloadRecord(
            versionName = versionName,
            releaseUrl = this[KEY_RELEASE_URL].orEmpty(),
            assetName = assetName,
            fileName = fileName,
            downloadId = this[KEY_DOWNLOAD_ID],
            sha256 = this[KEY_SHA256],
            downloadUrl = this[KEY_DOWNLOAD_URL],
            fallbackDownloadUrls = this[KEY_FALLBACK_DOWNLOAD_URLS]
                ?.lineSequence()
                ?.filter(String::isNotBlank)
                ?.toList()
                .orEmpty(),
            status = status,
            failureDetails = this[KEY_FAILURE_DETAILS],
            previewCommitSha = this[KEY_PREVIEW_SHA],
            previewTag = this[KEY_PREVIEW_TAG],
            previewPublishedAt = this[KEY_PREVIEW_PUBLISHED],
        )
    }

    companion object {
        private val KEY_PREVIEW_SHA = stringPreferencesKey("previewCommitSha")
        private val KEY_PREVIEW_TAG = stringPreferencesKey("previewTag")
        private val KEY_PREVIEW_PUBLISHED = stringPreferencesKey("previewPublishedAt")
        private val KEY_VERSION_NAME = stringPreferencesKey("versionName")
        private val KEY_RELEASE_URL = stringPreferencesKey("releaseUrl")
        private val KEY_ASSET_NAME = stringPreferencesKey("assetName")
        private val KEY_FILE_NAME = stringPreferencesKey("fileName")
        private val KEY_DOWNLOAD_ID = longPreferencesKey("downloadId")
        private val KEY_DOWNLOAD_URL = stringPreferencesKey("downloadUrl")
        private val KEY_FALLBACK_DOWNLOAD_URLS = stringPreferencesKey("fallbackDownloadUrls")
        private val KEY_SHA256 = stringPreferencesKey("sha256")
        private val KEY_FAILURE_DETAILS = stringPreferencesKey("failureDetails")
        private val KEY_STATUS = stringPreferencesKey("status")
    }
}
