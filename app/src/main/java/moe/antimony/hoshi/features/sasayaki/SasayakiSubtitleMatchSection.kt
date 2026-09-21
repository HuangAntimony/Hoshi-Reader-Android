package moe.antimony.hoshi.features.sasayaki

import android.app.Activity
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import moe.antimony.hoshi.R
import moe.antimony.hoshi.epub.SasayakiMatchData
import moe.antimony.hoshi.epub.SasayakiPlaybackData
import moe.antimony.hoshi.features.update.openDownloadedUpdate
import moe.antimony.hoshi.importing.FileImportContent
import moe.antimony.hoshi.importing.ImportFileType
import moe.antimony.hoshi.importing.importDisplayName
import moe.antimony.hoshi.importing.localizedImportMessage
import moe.antimony.hoshi.importing.validateImportFile

@Composable
internal fun SasayakiSubtitleMatchSection(
    dependencies: SasayakiMatchDependencies?,
    playback: SasayakiPlaybackData,
    currentMatchData: SasayakiMatchData?,
    onMatchUpdated: (SasayakiMatchData) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val scope = rememberCoroutineScope()
    var matchUiState by remember { mutableStateOf(SasayakiSubtitleMatchUiState()) }
    var displayedMatch by remember { mutableStateOf(currentMatchData) }
    var showSubReadInstall by remember { mutableStateOf(false) }
    var subReadDownloading by remember { mutableStateOf(false) }
    val subReadDownloadFailedMessage = stringResource(R.string.sasayaki_subread_download_failed)
    val selectSrtMessage = stringResource(R.string.sasayaki_select_srt_file)
    val selectedSrtFallback = stringResource(R.string.sasayaki_selected_srt)
    val matchFailedMessage = stringResource(R.string.sasayaki_match_failed)
    val subReadFailedMessage = stringResource(R.string.sasayaki_subread_failed)
    val subReadNeedsAudioMessage = stringResource(R.string.sasayaki_subread_needs_audio)
    val subReadNeedsBookMessage = stringResource(R.string.sasayaki_subread_needs_book)

    LaunchedEffect(currentMatchData) {
        displayedMatch = currentMatchData
    }

    fun startMatching(readSubtitles: suspend () -> ByteArray) {
        val activeDependencies = dependencies
        if (activeDependencies == null) {
            matchUiState = matchUiState.finishMatching(matchFailedMessage)
            return
        }
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    val srtBytes = readSubtitles()
                    val book = activeDependencies.epubBookParser.parse(activeDependencies.bookEntry.root)
                    val nextMatch = SasayakiMatcher.match(
                        book = book,
                        cues = SasayakiParser.parseCues(srtBytes),
                    )
                    activeDependencies.bookRepository.saveSasayakiMatch(activeDependencies.bookEntry.root, nextMatch)
                    nextMatch
                }
            }.onSuccess { nextMatch ->
                displayedMatch = nextMatch
                onMatchUpdated(nextMatch)
                matchUiState = matchUiState.finishMatching(errorMessage = null)
            }.onFailure { error ->
                matchUiState = matchUiState.finishMatching(
                    errorMessage = error.localizedMessage ?: matchFailedMessage,
                )
            }
        }
    }

    fun readSubtitleBytes(uri: Uri): ByteArray =
        context.contentResolver.openInputStream(uri).use { input ->
            requireNotNull(input) { resources.getString(R.string.sasayaki_open_srt_failed) }.readBytes()
        }

    val importer = rememberLauncherForActivityResult(FileImportContent()) { uri ->
        if (uri == null || matchUiState.isMatching) return@rememberLauncherForActivityResult
        runCatching {
            context.contentResolver.validateImportFile(uri, ImportFileType.SasayakiSubtitle)
        }.onFailure { error ->
            matchUiState = matchUiState.finishMatching(
                errorMessage = error.localizedImportMessage(context, selectSrtMessage),
            )
            return@rememberLauncherForActivityResult
        }
        val transition = matchUiState.acceptFile(
            context.contentResolver.importDisplayName(uri).ifBlank { selectedSrtFallback },
        )
        matchUiState = transition.state
        if (transition.shouldStartMatching) startMatching { readSubtitleBytes(uri) }
    }

    val subReadLauncher = rememberLauncherForActivityResult(StartActivityForResult()) { result ->
        if (matchUiState.isMatching) return@rememberLauncherForActivityResult
        val answer = result.data
        val subtitleUri = answer?.data
        when (
            val outcome = sasayakiSubReadResult(
                isOk = result.resultCode == Activity.RESULT_OK,
                hasSubtitles = subtitleUri != null,
                error = answer?.getStringExtra(SubRead.EXTRA_ERROR),
                matchRate = answer?.sasayakiSubReadMatchRate(),
            )
        ) {
            is SasayakiSubReadResult.Subtitles -> {
                val srtUri = requireNotNull(subtitleUri)
                val transition = matchUiState.acceptFile(
                    fileName = context.contentResolver.importDisplayName(srtUri).ifBlank { selectedSrtFallback },
                    subReadMatchRate = outcome.matchRate,
                )
                matchUiState = transition.state
                // SubRead grants the read permission only to this activity, so the file is copied
                // into memory first, before the match parses it.
                if (transition.shouldStartMatching) startMatching { readSubtitleBytes(srtUri) }
            }
            is SasayakiSubReadResult.Failed ->
                matchUiState = matchUiState.finishMatching(outcome.error ?: subReadFailedMessage)
            SasayakiSubReadResult.Canceled -> Unit
        }
    }

    fun startSubRead() {
        val activeDependencies = dependencies ?: return
        scope.launch {
            val request = withContext(Dispatchers.IO) {
                sasayakiSubReadRequest(
                    audio = activeDependencies.audioRepository.playbackSource(playback),
                    book = activeDependencies.packedEpubFile,
                    bookLanguage = activeDependencies.bookEntry.metadata.bookLanguage,
                )
            }
            when (request) {
                SasayakiSubReadRequest.MissingAudio ->
                    matchUiState = matchUiState.finishMatching(subReadNeedsAudioMessage)
                SasayakiSubReadRequest.MissingBook ->
                    matchUiState = matchUiState.finishMatching(subReadNeedsBookMessage)
                is SasayakiSubReadRequest.Ready -> {
                    val ask = runCatching { sasayakiSubReadAlignIntent(context, request) }.getOrElse {
                        matchUiState = matchUiState.finishMatching(subReadFailedMessage)
                        return@launch
                    }
                    if (ask.resolveActivity(context.packageManager) == null) {
                        showSubReadInstall = true
                        return@launch
                    }
                    matchUiState = matchUiState.clearedMessages()
                    runCatching { subReadLauncher.launch(ask) }.onFailure {
                        showSubReadInstall = true
                    }
                }
            }
        }
    }

    SasayakiResourceCard {
        Column(
            modifier = modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = stringResource(R.string.sasayaki_subtitle_match),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = sasayakiSubtitleMatchSummary(displayedMatch)
                        ?: stringResource(R.string.sasayaki_no_subtitle_match),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            HorizontalDivider()
            SasayakiInlineActionRow(
                label = stringResource(R.string.sasayaki_file),
                value = matchUiState.selectedFileName ?: stringResource(R.string.sasayaki_no_file_selected),
                action = if (matchUiState.isMatching) {
                    stringResource(R.string.sasayaki_matching)
                } else {
                    stringResource(R.string.action_open)
                },
                actionEnabled = dependencies != null && !matchUiState.isMatching,
                onAction = { importer.launch(ImportFileType.SasayakiSubtitle.mimeTypes) },
            )
            SasayakiInlineActionRow(
                label = stringResource(R.string.sasayaki_subread),
                value = stringResource(R.string.sasayaki_subread_summary),
                action = stringResource(R.string.sasayaki_subread_action),
                actionEnabled = dependencies != null && !matchUiState.isMatching,
                onAction = ::startSubRead,
            )
        }
        matchUiState.errorMessage?.let { message ->
            Text(
                text = message,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 12.dp),
            )
        }
        matchUiState.subReadMatchRate
            ?.takeIf { sasayakiSubReadMatchRateIsLow(it) }
            ?.let { matchRate ->
                Text(
                    text = stringResource(
                        R.string.sasayaki_subread_low_match_rate_format,
                        (matchRate * PercentScale).toInt(),
                    ),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(top = 12.dp),
                )
            }
    }

    if (showSubReadInstall) {
        fun openSubReadPage() {
            runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, SubRead.RELEASES_URL.toUri())) }
        }
        SasayakiSubReadInstallDialog(
            downloading = subReadDownloading,
            onInstall = {
                subReadDownloading = true
                scope.launch {
                    val apk = downloadSasayakiSubReadApk(context.applicationContext)
                    subReadDownloading = false
                    showSubReadInstall = false
                    if (apk == null) {
                        matchUiState = matchUiState.finishMatching(subReadDownloadFailedMessage)
                        openSubReadPage()
                    } else {
                        // The installer of the app update. It asks for the permission to install
                        // when the user did not give it yet.
                        openDownloadedUpdate(context, apk)?.let { message ->
                            matchUiState = matchUiState.finishMatching(message)
                        }
                    }
                }
            },
            onOpenPage = {
                showSubReadInstall = false
                openSubReadPage()
            },
            onDismiss = { if (!subReadDownloading) showSubReadInstall = false },
        )
    }
}

@Composable
private fun SasayakiSubReadInstallDialog(
    downloading: Boolean,
    onInstall: () -> Unit,
    onOpenPage: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.sasayaki_subread)) },
        text = {
            Text(
                stringResource(
                    if (downloading) R.string.sasayaki_subread_downloading else R.string.sasayaki_subread_not_installed,
                ),
            )
        },
        confirmButton = {
            TextButton(enabled = !downloading, onClick = onInstall) {
                Text(stringResource(R.string.sasayaki_subread_get))
            }
        },
        dismissButton = {
            TextButton(enabled = !downloading, onClick = onOpenPage) {
                Text(stringResource(R.string.sasayaki_subread_open_page))
            }
        },
    )
}

private const val PercentScale = 100
