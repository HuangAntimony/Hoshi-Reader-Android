package moe.antimony.hoshi.features.sasayaki

import moe.antimony.hoshi.epub.SasayakiMatchData

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import moe.antimony.hoshi.R
import moe.antimony.hoshi.epub.BookEntry
import moe.antimony.hoshi.epub.EpubBookParser
import moe.antimony.hoshi.epub.SasayakiSidecarRepository
import moe.antimony.hoshi.importing.FileImportContent
import moe.antimony.hoshi.importing.ImportFileType
import moe.antimony.hoshi.importing.importDisplayName
import moe.antimony.hoshi.importing.localizedImportMessage
import moe.antimony.hoshi.importing.validateImportFile
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SasayakiMatchView(
    bookEntry: BookEntry,
    bookRepository: SasayakiSidecarRepository,
    epubBookParser: EpubBookParser,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val scope = rememberCoroutineScope()
    var selectedSrtUri by remember { mutableStateOf<Uri?>(null) }
    var selectedSrtName by remember { mutableStateOf<String?>(null) }
    var searchWindow by remember { mutableFloatStateOf(200f) }
    var isMatching by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var currentMatch by remember(bookEntry.root) { mutableStateOf<SasayakiMatchData?>(null) }
    val selectSrtMessage = stringResource(R.string.sasayaki_select_srt_file)
    val selectedSrtFallback = stringResource(R.string.sasayaki_selected_srt)
    val matchFailedMessage = stringResource(R.string.sasayaki_match_failed)
    LaunchedEffect(bookEntry.root, bookRepository) {
        currentMatch = bookRepository.loadSasayakiMatch(bookEntry.root)
    }
    val importer = rememberLauncherForActivityResult(FileImportContent()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        runCatching {
            context.contentResolver.validateImportFile(uri, ImportFileType.SasayakiSubtitle)
        }.onFailure { error ->
            errorMessage = error.localizedImportMessage(context, selectSrtMessage)
            return@rememberLauncherForActivityResult
        }
        selectedSrtUri = uri
        selectedSrtName = context.contentResolver.importDisplayName(uri).ifBlank { selectedSrtFallback }
        errorMessage = null
    }

    fun matchSelectedFile() {
        val uri = selectedSrtUri ?: return
        if (isMatching) return
        isMatching = true
        errorMessage = null
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    val srtBytes = context.contentResolver.openInputStream(uri).use { input ->
                        requireNotNull(input) { resources.getString(R.string.sasayaki_open_srt_failed) }.readBytes()
                    }
                    val book = epubBookParser.parse(bookEntry.root)
                    val nextMatch = SasayakiMatcher.match(
                        book = book,
                        cues = SasayakiParser.parseCues(srtBytes),
                        searchWindow = searchWindow.roundToInt(),
                    )
                    bookRepository.saveSasayakiMatch(bookEntry.root, nextMatch)
                    nextMatch
                }
            }.onSuccess { nextMatch ->
                currentMatch = nextMatch
            }.onFailure { error ->
                errorMessage = error.localizedMessage ?: matchFailedMessage
            }
            isMatching = false
        }
    }

    BackHandler(onBack = onClose)
    val colorScheme = MaterialTheme.colorScheme
    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = colorScheme.background,
        topBar = {
            CenterAlignedTopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = colorScheme.background,
                    scrolledContainerColor = colorScheme.background,
                ),
                title = { Text(stringResource(R.string.sasayaki_match_title), fontWeight = FontWeight.SemiBold) },
                actions = {
                    TextButton(onClick = onClose) {
                        Text(stringResource(R.string.action_done))
                    }
                },
            )
        },
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            item {
                MatchSectionHeader(stringResource(R.string.sasayaki_file))
                MatchCard {
                    ListItem(
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                        headlineContent = {
                            Text(
                                text = selectedSrtName ?: stringResource(R.string.sasayaki_no_file_selected),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        },
                        trailingContent = {
                            TextButton(
                                enabled = !isMatching,
                                onClick = {
                                    importer.launch(ImportFileType.SasayakiSubtitle.mimeTypes)
                                },
                            ) {
                                Text(stringResource(R.string.action_open))
                            }
                        },
                    )
                }
            }

            item {
                MatchCard {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = stringResource(R.string.sasayaki_search_window),
                                style = MaterialTheme.typography.titleMedium,
                                modifier = Modifier.weight(1f),
                            )
                            Text(
                                text = "${searchWindow.roundToInt()}",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                            )
                        }
                        Slider(
                            value = searchWindow,
                            onValueChange = { searchWindow = it },
                            valueRange = 50f..1000f,
                            steps = 18,
                            enabled = !isMatching,
                        )
                    }
                    HorizontalDivider(
                        modifier = Modifier.padding(horizontal = 16.dp),
                        color = colorScheme.outlineVariant,
                    )
                    ListItem(
                        modifier = Modifier.clickable(
                            enabled = selectedSrtUri != null && !isMatching,
                            onClick = ::matchSelectedFile,
                        ),
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                        headlineContent = {
                            if (isMatching) {
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(18.dp),
                                        strokeWidth = 2.dp,
                                    )
                                    Text(stringResource(R.string.sasayaki_matching))
                                }
                            } else {
                                Text(
                                    text = stringResource(R.string.sasayaki_match_title),
                                    color = if (selectedSrtUri == null) {
                                        colorScheme.onSurfaceVariant.copy(alpha = 0.45f)
                                    } else {
                                        colorScheme.primary
                                    },
                                )
                            }
                        },
                    )
                }
                errorMessage?.let { message ->
                    Text(
                        text = message,
                        color = colorScheme.error,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(start = 16.dp, top = 8.dp),
                    )
                }
            }

            currentMatch?.let { match ->
                item {
                    MatchSectionHeader(stringResource(R.string.sasayaki_current_match))
                    MatchCard {
                        ListItem(
                            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                            headlineContent = { Text(stringResource(R.string.sasayaki_match_rate)) },
                            trailingContent = {
                                Text(
                                    text = match.matchRateText(),
                                    color = colorScheme.onSurfaceVariant,
                                )
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun MatchSectionHeader(text: String) {
    Text(
        text = text,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(start = 16.dp, bottom = 8.dp),
    )
}

@Composable
private fun MatchCard(content: @Composable () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        tonalElevation = 0.dp,
    ) {
        Column(content = { content() })
    }
}
