package moe.antimony.hoshi.features.sasayaki

import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import moe.antimony.hoshi.epub.SasayakiMatchData
import moe.antimony.hoshi.epub.SasayakiMatchSource
import moe.antimony.hoshi.epub.SasayakiPlaybackData

/** Reader owns this binding even when the audiobook sheet is closed. */
@Composable
internal fun rememberSasayakiTranscriptionState(
    root: File?,
    audioRepository: SasayakiAudioRepository?,
    playback: SasayakiPlaybackData?,
    viewModel: SasayakiTranscriptionViewModel,
    onMatchUpdated: (SasayakiMatchData) -> Unit,
    matchSource: SasayakiMatchSource? = null,
): SasayakiTranscriptionUiState {
    val currentOnMatchUpdated by rememberUpdatedState(onMatchUpdated)
    LaunchedEffect(root, audioRepository, playback?.audioUri, playback?.audioFileName, viewModel, matchSource) {
        if (root != null) {
            viewModel.bind(root, null, matchSource) { currentOnMatchUpdated(it) }
            val source = withContext(Dispatchers.IO) {
                when (val source = playback?.let { audioRepository?.playbackSource(it) }) {
                    is SasayakiPlaybackSource.ExternalUri -> source.uri.toString()
                    is SasayakiPlaybackSource.PrivateFile -> Uri.fromFile(source.file).toString()
                    null -> null
                }
            }
            // Bind at completion, without relying on a later sheet recomposition.
            viewModel.bind(root, source, matchSource) { currentOnMatchUpdated(it) }
        }
    }
    // The route ViewModel pauses on removal. Activity ON_STOP only hides the
    // Reader (Home, app switch, screen off), so it must not cancel transcription.
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    return state
}
