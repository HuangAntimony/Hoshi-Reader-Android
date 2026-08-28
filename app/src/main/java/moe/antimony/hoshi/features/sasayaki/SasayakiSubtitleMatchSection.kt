package moe.antimony.hoshi.features.sasayaki

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import moe.antimony.hoshi.R
import moe.antimony.hoshi.epub.SasayakiMatchData
import moe.antimony.hoshi.importing.FileImportContent
import moe.antimony.hoshi.importing.ImportFileType
import moe.antimony.hoshi.importing.importDisplayName
import moe.antimony.hoshi.importing.localizedImportMessage
import moe.antimony.hoshi.importing.validateImportFile

@Composable
internal fun SasayakiSubtitleMatchSection(
    dependencies: SasayakiMatchDependencies?,
    currentMatchData: SasayakiMatchData?,
    onMatchUpdated: (SasayakiMatchData) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val scope = rememberCoroutineScope()
    var matchUiState by remember { mutableStateOf(SasayakiSubtitleMatchUiState()) }
    var displayedMatch by remember { mutableStateOf(currentMatchData) }
    val selectSrtMessage = stringResource(R.string.sasayaki_select_srt_file)
    val selectedSrtFallback = stringResource(R.string.sasayaki_selected_srt)
    val matchFailedMessage = stringResource(R.string.sasayaki_match_failed)

    LaunchedEffect(currentMatchData) {
        displayedMatch = currentMatchData
    }

    fun startMatching(uri: Uri) {
        val activeDependencies = dependencies
        if (activeDependencies == null) {
            matchUiState = matchUiState.finishMatching(matchFailedMessage)
            return
        }
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    val srtBytes = context.contentResolver.openInputStream(uri).use { input ->
                        requireNotNull(input) { resources.getString(R.string.sasayaki_open_srt_failed) }.readBytes()
                    }
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
        if (transition.shouldStartMatching) startMatching(uri)
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
        }
        matchUiState.errorMessage?.let { message ->
            Text(
                text = message,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 12.dp),
            )
        }
    }
}
