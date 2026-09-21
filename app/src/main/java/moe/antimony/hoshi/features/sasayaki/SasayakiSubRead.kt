package moe.antimony.hoshi.features.sasayaki

import android.app.DownloadManager
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import androidx.core.content.FileProvider
import androidx.core.net.toUri
import kotlinx.coroutines.delay
import java.io.File
import java.util.Locale

/**
 * SubRead makes the Sasayaki `.srt` from the audiobook and the book on the device.
 * The contract is the SubRead 0.9.0 intent API.
 */
internal object SubRead {
    const val PACKAGE = "space.subread.app"
    const val ACTION_ALIGN = "space.subread.app.action.ALIGN"
    const val EXTRA_AUDIO = "space.subread.extra.AUDIO"
    const val EXTRA_BOOK = "space.subread.extra.BOOK"
    const val EXTRA_LANGUAGE = "space.subread.extra.LANGUAGE"
    const val EXTRA_MATCH_RATE = "space.subread.extra.MATCH_RATE"
    const val EXTRA_ERROR = "space.subread.extra.ERROR"
    const val RELEASES_URL = "https://github.com/equwal/subread-android/releases/latest"

    /** Each SubRead release has the APK under this name also, so the link needs no version. */
    const val APK_URL = "https://github.com/equwal/subread-android/releases/latest/download/SubRead.apk"
    const val APK_FILE_NAME = "SubRead.apk"

    /** A match rate below this value usually means a different edition or language. */
    const val LOW_MATCH_RATE = 0.8
}

/** What the app can send to SubRead for the current book. */
internal sealed interface SasayakiSubReadRequest {
    data class Ready(
        val audio: SasayakiPlaybackSource,
        val book: File,
        val language: String?,
    ) : SasayakiSubReadRequest

    /** The book has no audiobook yet. */
    data object MissingAudio : SasayakiSubReadRequest

    /** The book has no packed EPUB file to send. */
    data object MissingBook : SasayakiSubReadRequest
}

/** What SubRead sent back. */
internal sealed interface SasayakiSubReadResult {
    data class Subtitles(val matchRate: Double?) : SasayakiSubReadResult

    data class Failed(val error: String?) : SasayakiSubReadResult

    data object Canceled : SasayakiSubReadResult
}

internal fun sasayakiSubReadRequest(
    audio: SasayakiPlaybackSource?,
    book: File?,
    bookLanguage: String?,
): SasayakiSubReadRequest =
    when {
        audio == null -> SasayakiSubReadRequest.MissingAudio
        book == null -> SasayakiSubReadRequest.MissingBook
        else -> SasayakiSubReadRequest.Ready(
            audio = audio,
            book = book,
            language = sasayakiSubReadLanguage(bookLanguage),
        )
    }

/** Keeps the primary subtag of an EPUB language, because SubRead takes a Whisper language code. */
internal fun sasayakiSubReadLanguage(bookLanguage: String?): String? =
    bookLanguage
        ?.trim()
        ?.substringBefore('-')
        ?.substringBefore('_')
        ?.lowercase(Locale.ROOT)
        ?.takeIf { it.isNotEmpty() }

internal fun sasayakiSubReadResult(
    isOk: Boolean,
    hasSubtitles: Boolean,
    error: String?,
    matchRate: Double?,
): SasayakiSubReadResult {
    val reportedError = error?.trim()?.takeIf { it.isNotEmpty() }
    return when {
        isOk && hasSubtitles -> SasayakiSubReadResult.Subtitles(matchRate = matchRate)
        reportedError != null -> SasayakiSubReadResult.Failed(reportedError)
        isOk -> SasayakiSubReadResult.Failed(error = null)
        else -> SasayakiSubReadResult.Canceled
    }
}

internal fun sasayakiSubReadMatchRateIsLow(matchRate: Double?): Boolean =
    matchRate != null && matchRate.isFinite() && matchRate < SubRead.LOW_MATCH_RATE

internal fun sasayakiSubReadAlignIntent(
    context: Context,
    request: SasayakiSubReadRequest.Ready,
): Intent {
    val audioUri = sasayakiSubReadAudioUri(context, request.audio)
    val bookUri = sasayakiSubReadFileUri(context, request.book)
    return Intent(SubRead.ACTION_ALIGN).apply {
        setPackage(SubRead.PACKAGE)
        putExtra(SubRead.EXTRA_AUDIO, audioUri)
        putExtra(SubRead.EXTRA_BOOK, bookUri)
        request.language?.let { putExtra(SubRead.EXTRA_LANGUAGE, it) }
        clipData = ClipData.newRawUri("audio", audioUri).apply {
            addItem(ClipData.Item(bookUri))
        }
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
}

internal fun Intent.sasayakiSubReadMatchRate(): Double? =
    takeIf { it.hasExtra(SubRead.EXTRA_MATCH_RATE) }?.getDoubleExtra(SubRead.EXTRA_MATCH_RATE, 0.0)

private fun sasayakiSubReadAudioUri(context: Context, audio: SasayakiPlaybackSource): Uri =
    when (audio) {
        is SasayakiPlaybackSource.ExternalUri -> audio.uri
        is SasayakiPlaybackSource.PrivateFile -> sasayakiSubReadFileUri(context, audio.file)
    }

private fun sasayakiSubReadFileUri(context: Context, file: File): Uri =
    FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)

/** Where the download of the SubRead APK is. */
internal enum class SasayakiSubReadDownload { Running, Done, Failed }

internal fun sasayakiSubReadDownloadState(downloadManagerStatus: Int?): SasayakiSubReadDownload =
    when (downloadManagerStatus) {
        DownloadManager.STATUS_SUCCESSFUL -> SasayakiSubReadDownload.Done
        DownloadManager.STATUS_PENDING,
        DownloadManager.STATUS_RUNNING,
        DownloadManager.STATUS_PAUSED,
        -> SasayakiSubReadDownload.Running
        // A download that the user removed from the notification has no row any more.
        else -> SasayakiSubReadDownload.Failed
    }

/**
 * Downloads the SubRead APK with the download manager of the system.
 * Returns the file, or null when the download failed.
 */
internal suspend fun downloadSasayakiSubReadApk(context: Context): File? {
    val downloads = context.getSystemService(DownloadManager::class.java) ?: return null
    val directory = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: return null
    val target = File(directory, SubRead.APK_FILE_NAME)
    target.delete()
    val request = DownloadManager.Request(SubRead.APK_URL.toUri())
        .setTitle("SubRead")
        .setMimeType("application/vnd.android.package-archive")
        .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
        .setDestinationUri(Uri.fromFile(target))
    val id = runCatching { downloads.enqueue(request) }.getOrNull() ?: return null
    while (true) {
        val status = downloads.query(DownloadManager.Query().setFilterById(id))?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS)) else null
        }
        when (sasayakiSubReadDownloadState(status)) {
            SasayakiSubReadDownload.Done -> return target.takeIf { it.isFile && it.length() > 0 }
            SasayakiSubReadDownload.Failed -> {
                downloads.remove(id)
                return null
            }
            SasayakiSubReadDownload.Running -> delay(DownloadPollMillis)
        }
    }
}

private const val DownloadPollMillis = 500L
