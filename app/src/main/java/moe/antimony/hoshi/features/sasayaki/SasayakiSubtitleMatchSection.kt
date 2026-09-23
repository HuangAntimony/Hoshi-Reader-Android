package moe.antimony.hoshi.features.sasayaki

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
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
    onMatchUpdated: (SasayakiMatchData) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onMatchingChange: (Boolean) -> Unit = {},
) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val scope = rememberCoroutineScope()
    var matchUiState by remember { mutableStateOf(SasayakiSubtitleMatchUiState()) }
    val selectSrtMessage = stringResource(R.string.sasayaki_select_srt_file)
    val selectedSrtFallback = stringResource(R.string.sasayaki_selected_srt)
    val matchFailedMessage = stringResource(R.string.sasayaki_match_failed)

    SideEffect { onMatchingChange(matchUiState.isMatching) }
    val currentMatchingChange by rememberUpdatedState(onMatchingChange)
    DisposableEffect(Unit) {
        onDispose { currentMatchingChange(false) }
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
                    currentCoroutineContext().ensureActive()
                    activeDependencies.bookRepository.saveSasayakiMatch(activeDependencies.bookEntry.root, nextMatch)
                    nextMatch
                }
            }.onSuccess { nextMatch ->
                onMatchUpdated(nextMatch)
                matchUiState = matchUiState.finishMatching(errorMessage = null)
            }.onFailure { error ->
                if (error is CancellationException) throw error
                matchUiState = matchUiState.finishMatching(
                    errorMessage = matchFailedMessage,
                )
            }
        }
    }

    val importer = rememberLauncherForActivityResult(FileImportContent()) { uri ->
        if (uri == null || !enabled || matchUiState.isMatching) return@rememberLauncherForActivityResult
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
            SasayakiInlineActionRow(
                label = stringResource(R.string.sasayaki_file),
                value = matchUiState.selectedFileName ?: stringResource(R.string.sasayaki_no_file_selected),
                action = if (matchUiState.isMatching) {
                    stringResource(R.string.sasayaki_matching)
                } else {
                    stringResource(R.string.action_open)
                },
                actionEnabled = enabled && dependencies != null && !matchUiState.isMatching,
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
