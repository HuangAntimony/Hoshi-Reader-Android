package moe.antimony.hoshi.features.reader

import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.intl.LocaleList
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import moe.antimony.hoshi.R
import moe.antimony.hoshi.features.anki.ExampleSentence

private enum class MineStep { Sentence, Image }

/**
 * Two-step mining sheet: pick a sentence, then a Picture. The sentence list leads with the bare
 * term (bold), then — for the reader and process-text — the in-book/selection sentence (selected
 * by default), then the Massif examples. The image step has a "None" option plus a thumbnail grid
 * that leads with the book cover (when the surface has one — selected by default), then the Bing
 * results.
 *
 * @param currentSentence the surrounding sentence; shown after the term and selected by default
 *   when present (null for the dictionary, whose only non-example option is the term).
 * @param bookCoverPath the book cover image path for this surface (the reader); when non-null it
 *   leads the image grid and is selected by default, otherwise the default is "None".
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MineWithOptionsSheet(
    term: String,
    onConfirm: (selectedSentence: String?, image: MineImageChoice) -> Unit,
    onDismiss: () -> Unit,
    currentSentence: ExampleSentence? = null,
    bookCoverPath: String? = null,
    viewModel: MineWithOptionsViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    LaunchedEffect(term) { viewModel.start(term) }

    // Prefetch thumbnails so the image step is instant.
    LaunchedEffect(state.imageCandidates) {
        val gate = Semaphore(THUMB_PREFETCH_CONCURRENCY)
        coroutineScope {
            state.imageCandidates.forEach { candidate ->
                launch { gate.withPermit { WebThumbBitmapCache.load(candidate.thumbUrl) } }
            }
        }
    }

    val termOption = term
        .takeIf { it.isNotBlank() }
        ?.let { ExampleSentence(it, listOf(0 until it.length)) }

    // One list = [term, in-book sentence?, …Massif examples].
    val options: List<MineOption> = buildList {
        if (termOption != null) add(MineOption.Term(termOption, term))
        if (currentSentence != null) add(MineOption.InBook(currentSentence))
        state.candidates.forEach { add(MineOption.Example(it)) }
    }
    // Default to the in-book sentence when present, else the term.
    val defaultIndex = if (currentSentence != null && termOption != null) 1 else 0
    var selectedOption by remember(term, currentSentence) { mutableStateOf(defaultIndex) }

    var step by remember(term) { mutableStateOf(MineStep.Sentence) }
    // Default to the book cover when this surface has one, else "None".
    var imageChoice by remember(term, bookCoverPath) {
        mutableStateOf<MineImageChoice>(if (bookCoverPath != null) MineImageChoice.Cover else MineImageChoice.None)
    }

    val maxListHeight = (LocalConfiguration.current.screenHeightDp * 0.5f).dp

    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier
                .testTag("mine-with-options-sheet")
                .fillMaxWidth()
                .padding(start = 24.dp, end = 24.dp, top = 8.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = stringResource(R.string.mine_with_options_title),
                style = MaterialTheme.typography.titleLarge,
            )

            if (step == MineStep.Sentence) {
                Text(
                    text = stringResource(R.string.mine_with_options_sentence_section),
                    style = MaterialTheme.typography.labelLarge,
                )
                // Fixed height so the sheet doesn't grow/re-animate when Massif loads in.
                Column(
                    modifier = Modifier
                        .height(maxListHeight)
                        .verticalScroll(rememberScrollState())
                        .selectableGroup(),
                ) {
                    options.forEachIndexed { index, option ->
                        SentenceOption(
                            testTag = "mine-with-options-candidate-$index",
                            selected = selectedOption == index,
                            onSelect = { selectedOption = index },
                        ) {
                            Text(
                                text = option.display.annotated(),
                                style = MaterialTheme.typography.bodyMedium
                                    .copy(localeList = JapaneseLocale),
                            )
                        }
                    }
                    if (state.loading) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            CircularProgressIndicator()
                            Text(stringResource(R.string.mine_with_options_loading))
                        }
                    }
                }
            } else {
                Text(
                    text = stringResource(R.string.mine_with_options_image_section),
                    style = MaterialTheme.typography.labelLarge,
                )
                ImageStep(
                    state = state,
                    bookCoverPath = bookCoverPath,
                    selected = imageChoice,
                    onSelect = { imageChoice = it },
                    maxHeight = maxListHeight,
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                if (step == MineStep.Sentence) {
                    TextButton(
                        onClick = onDismiss,
                        modifier = Modifier.testTag("mine-with-options-cancel"),
                    ) {
                        Text(stringResource(R.string.action_cancel))
                    }
                    Button(
                        onClick = { step = MineStep.Image },
                        modifier = Modifier.testTag("mine-with-options-next"),
                    ) {
                        Text(stringResource(R.string.mine_with_options_next))
                    }
                } else {
                    TextButton(
                        onClick = { step = MineStep.Sentence },
                        modifier = Modifier.testTag("mine-with-options-back"),
                    ) {
                        Text(stringResource(R.string.mine_with_options_back))
                    }
                    Button(
                        onClick = { onConfirm(options.getOrNull(selectedOption)?.picked(), imageChoice) },
                        modifier = Modifier.testTag("mine-with-options-add"),
                    ) {
                        Text(stringResource(R.string.mine_with_options_add))
                    }
                }
            }
        }
    }
}

private const val IMAGE_GRID_COLUMNS = 3
private const val IMAGE_GRID_MAX_TILES = IMAGE_GRID_COLUMNS * 4
private const val THUMB_PREFETCH_CONCURRENCY = 4
private const val THUMB_CACHE_MAX_BYTES = 8 * 1024 * 1024

@Composable
private fun ImageStep(
    state: MineWithOptionsUiState,
    bookCoverPath: String?,
    selected: MineImageChoice,
    onSelect: (MineImageChoice) -> Unit,
    maxHeight: Dp,
) {
    // "None" is the only non-image option; the book cover and the Bing results are thumbnails.
    ImageOptionRow(
        testTag = "mine-with-options-image-none",
        label = stringResource(R.string.mine_with_options_image_none),
        selected = selected == MineImageChoice.None,
        onSelect = { onSelect(MineImageChoice.None) },
    )
    val selectedUrl = (selected as? MineImageChoice.Web)?.url
    val coverDescription = stringResource(R.string.mine_with_options_image_cover)
    val webImages = state.imageCandidates.take(IMAGE_GRID_MAX_TILES - if (bookCoverPath != null) 1 else 0)
    if (bookCoverPath != null || webImages.isNotEmpty()) {
        LazyVerticalGrid(
            columns = GridCells.Fixed(IMAGE_GRID_COLUMNS),
            modifier = Modifier.fillMaxWidth().height(maxHeight),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // The book cover leads the grid, selected by default in the reader.
            if (bookCoverPath != null) {
                item(key = "cover") {
                    Thumb(
                        cacheKey = bookCoverPath,
                        loadBitmap = { decodeFileBitmap(bookCoverPath) },
                        contentDescription = coverDescription,
                        selected = selected == MineImageChoice.Cover,
                        onSelect = { onSelect(MineImageChoice.Cover) },
                        modifier = Modifier.testTag("mine-with-options-image-cover"),
                    )
                }
            }
            items(webImages, key = { it.fullUrl }) { candidate ->
                Thumb(
                    cacheKey = candidate.thumbUrl,
                    cached = WebThumbBitmapCache.get(candidate.thumbUrl),
                    loadBitmap = { WebThumbBitmapCache.load(candidate.thumbUrl) },
                    contentDescription = candidate.title.ifBlank { candidate.fullUrl },
                    selected = selectedUrl == candidate.fullUrl,
                    onSelect = { onSelect(MineImageChoice.Web(candidate.fullUrl)) },
                )
            }
        }
    }
    if (state.imageLoading) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CircularProgressIndicator()
            Text(stringResource(R.string.mine_with_options_image_loading))
        }
    } else if (state.imageCandidates.isEmpty() && bookCoverPath == null) {
        Text(
            text = stringResource(R.string.mine_with_options_no_images),
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

@Composable
private fun ImageOptionRow(
    testTag: String,
    label: String,
    selected: Boolean,
    onSelect: () -> Unit,
) {
    Row(
        modifier = Modifier
            .testTag(testTag)
            .fillMaxWidth()
            .selectable(selected = selected, onClick = onSelect, role = Role.RadioButton)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        RadioButton(selected = selected, onClick = null)
        Text(label)
    }
}

private sealed interface ThumbState {
    data object Loading : ThumbState
    data class Loaded(val bitmap: ImageBitmap) : ThumbState
    data object Failed : ThumbState
}

/** A square selectable thumbnail. [loadBitmap] runs off the main thread; null → failed placeholder. */
@Composable
private fun Thumb(
    cacheKey: Any?,
    loadBitmap: suspend () -> ImageBitmap?,
    contentDescription: String,
    selected: Boolean,
    onSelect: () -> Unit,
    modifier: Modifier = Modifier,
    cached: ImageBitmap? = null,
) {
    val thumb by produceState<ThumbState>(
        cached?.let { ThumbState.Loaded(it) } ?: ThumbState.Loading,
        cacheKey,
    ) {
        if (cached == null) {
            value = withContext(Dispatchers.IO) {
                loadBitmap()?.let { ThumbState.Loaded(it) } ?: ThumbState.Failed
            }
        }
    }
    Box(
        modifier = modifier
            .aspectRatio(1f)
            .clip(RoundedCornerShape(8.dp))
            .then(
                if (selected) {
                    Modifier.border(3.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(8.dp))
                } else {
                    Modifier
                },
            )
            .clickable(onClick = onSelect),
        contentAlignment = Alignment.Center,
    ) {
        when (val s = thumb) {
            is ThumbState.Loaded -> Image(
                bitmap = s.bitmap,
                contentDescription = contentDescription,
                contentScale = ContentScale.Crop,
                modifier = Modifier.matchParentSize(),
            )
            ThumbState.Loading -> CircularProgressIndicator(modifier = Modifier.size(20.dp))
            // Dependency-free failed placeholder: a muted square (no extended icon set).
            ThumbState.Failed -> Box(
                modifier = Modifier
                    .matchParentSize()
                    .background(MaterialTheme.colorScheme.surfaceVariant),
            )
        }
    }
}

private object WebThumbBitmapCache {
    private val cache = object : LruCache<String, ImageBitmap>(THUMB_CACHE_MAX_BYTES) {
        override fun sizeOf(key: String, value: ImageBitmap): Int = value.asAndroidBitmap().byteCount
    }

    fun get(url: String): ImageBitmap? = synchronized(cache) { cache.get(url) }

    suspend fun load(url: String): ImageBitmap? = withContext(Dispatchers.IO) {
        get(url)?.let { return@withContext it }
        val bitmap = decodeUrlBitmap(url) ?: return@withContext null
        synchronized(cache) { cache.put(url, bitmap) }
        bitmap
    }
}

private fun decodeUrlBitmap(url: String): ImageBitmap? =
    runCatching {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 5_000
            readTimeout = 10_000
            instanceFollowRedirects = true
        }
        try {
            conn.inputStream.use { BitmapFactory.decodeStream(it) }
        } finally {
            conn.disconnect()
        }
    }.getOrNull()?.asImageBitmap()

private fun decodeFileBitmap(path: String): ImageBitmap? =
    runCatching { BitmapFactory.decodeFile(path) }.getOrNull()?.asImageBitmap()

@Composable
private fun SentenceOption(
    testTag: String,
    selected: Boolean,
    onSelect: () -> Unit,
    content: @Composable () -> Unit,
) {
    Row(
        modifier = Modifier
            .testTag(testTag)
            .fillMaxWidth()
            .selectable(selected = selected, onClick = onSelect, role = Role.RadioButton)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        RadioButton(selected = selected, onClick = null)
        content()
    }
}

private val JapaneseLocale = LocaleList("ja")

/** One row of the combined list; [display] is rendered, [picked] is handed to onConfirm. */
private sealed interface MineOption {
    val display: ExampleSentence

    /** null keeps the in-book sentence (mine as-is); a string overrides the sentence. */
    fun picked(): String?

    /** The bare term — mines the word itself as the sentence. */
    data class Term(override val display: ExampleSentence, val term: String) : MineOption {
        override fun picked() = term
    }

    /** The in-book sentence — keeps the base context (real offset intact). */
    data class InBook(override val display: ExampleSentence) : MineOption {
        override fun picked(): String? = null
    }

    /** A Massif example — overrides the sentence with the example text. */
    data class Example(override val display: ExampleSentence) : MineOption {
        override fun picked() = display.text
    }
}

/** Bolds the highlighted ranges (the matched word — conjugations included for Massif examples). */
private fun ExampleSentence.annotated(): AnnotatedString =
    buildAnnotatedString {
        append(text)
        val bold = SpanStyle(fontWeight = FontWeight.Bold)
        highlights.forEach { range ->
            addStyle(bold, range.first, range.last + 1)
        }
    }
