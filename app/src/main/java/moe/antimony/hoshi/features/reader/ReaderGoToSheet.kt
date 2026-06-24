package moe.antimony.hoshi.features.reader

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import moe.antimony.hoshi.R
import moe.antimony.hoshi.epub.EpubBook
import moe.antimony.hoshi.epub.ReaderHighlight
import moe.antimony.hoshi.ui.hoshiSingleLineTextFieldLineLimits
import moe.antimony.hoshi.ui.hoshiTextFieldCursorBrush
import moe.antimony.hoshi.ui.rememberSyncedTextFieldState

internal enum class ReaderGoToTab {
    Search,
    Chapters,
    Highlights,
}

internal val ReaderGoToTabRole = Role.Tab

@Composable
internal fun ReaderGoToSheet(
    book: EpubBook,
    currentPosition: ReaderChapterPosition,
    progressDisplay: ReaderProgressDisplay,
    highlights: List<ReaderHighlight>,
    onChapterJump: (ReaderChapterPosition, String?) -> Unit,
    onCharacterJump: (Int) -> Unit,
    onSearchResultJump: (ReaderSearchResult) -> Unit,
    onHighlightJump: (ReaderHighlight) -> Unit,
    onHighlightDelete: (ReaderHighlight) -> Unit,
    onDismiss: () -> Unit,
) {
    var selectedTab by remember { mutableStateOf(ReaderGoToTab.Search) }
    var showJumpDialog by remember { mutableStateOf(false) }
    val coverBitmap = remember(book) { book.decodeCoverImageBitmap() }
    val searchState = remember(book) { ReaderSearchSheetState() }
    val searchEngine = remember(book) { ReaderSearchEngine(book) }

    LaunchedEffect(book, searchState.searchNonce) {
        if (searchState.searchNonce == searchState.handledSearchNonce) {
            return@LaunchedEffect
        }
        searchState.handledSearchNonce = searchState.searchNonce
        val captured = searchState.submittedQuery
        if (!readerSearchQueryHasMatchableText(captured)) {
            searchState.clearSubmittedSearch()
            return@LaunchedEffect
        }
        searchState.searching = true
        searchState.failed = false
        when (val searchResult = loadReaderSearchResults { withContext(Dispatchers.IO) { searchEngine.search(captured) } }) {
            is ReaderSearchLoadResult.Success -> {
                searchState.results = searchResult.value
                searchState.searching = false
            }
            ReaderSearchLoadResult.Failure -> {
                searchState.results = emptyList()
                searchState.searching = false
                searchState.failed = true
            }
        }
    }

    ReaderBottomPanel(
        sheetStyle = readerSheetStyle(),
        onDismiss = onDismiss,
    ) {
        ReaderGoToBookHeader(
            book = book,
            coverBitmap = coverBitmap,
            currentPosition = currentPosition,
            progressDisplay = progressDisplay,
            onJumpToCharacter = { showJumpDialog = true },
            modifier = Modifier.padding(start = 20.dp, end = 20.dp, bottom = 12.dp),
        )
        ReaderGoToTabs(
            selectedTab = selectedTab,
            onSelectedTabChange = { selectedTab = it },
            modifier = Modifier.padding(start = 20.dp, end = 20.dp, bottom = 10.dp),
        )
        when (selectedTab) {
            ReaderGoToTab.Search -> ReaderSearchTab(
                searchState = searchState,
                progressDisplay = progressDisplay,
                totalCharacters = book.bookInfo.characterCount,
                onJump = onSearchResultJump,
                modifier = Modifier.weight(1f),
            )
            ReaderGoToTab.Chapters -> ReaderGoToChaptersTab(
                book = book,
                currentPosition = currentPosition,
                progressDisplay = progressDisplay,
                onJump = onChapterJump,
                modifier = Modifier.weight(1f),
            )
            ReaderGoToTab.Highlights -> ReaderGoToHighlightsTab(
                book = book,
                highlights = highlights,
                onJump = onHighlightJump,
                onDelete = onHighlightDelete,
                modifier = Modifier.weight(1f),
            )
        }
    }

    if (showJumpDialog) {
        JumpToCharacterDialog(
            totalCharacters = book.bookInfo.characterCount,
            progressDisplay = progressDisplay,
            onDismiss = { showJumpDialog = false },
            onConfirm = { count ->
                showJumpDialog = false
                onCharacterJump(count)
            },
        )
    }
}

internal class ReaderSearchSheetState {
    var query by mutableStateOf("")
    var submittedQuery by mutableStateOf("")
    var results by mutableStateOf(emptyList<ReaderSearchResult>())
    var searching by mutableStateOf(false)
    var failed by mutableStateOf(false)
    var searchNonce by mutableStateOf(0)
    var handledSearchNonce by mutableStateOf(0)

    fun updateQuery(value: String) {
        query = value
        if (!readerSearchQueryHasMatchableText(value)) {
            clearSubmittedSearch()
        }
    }

    fun submitQuery() {
        if (!readerSearchQueryHasMatchableText(query)) {
            clearSubmittedSearch()
            return
        }
        submittedQuery = query
        searchNonce += 1
    }

    fun clearSubmittedSearch() {
        submittedQuery = ""
        results = emptyList()
        searching = false
        failed = false
    }
}

@Composable
private fun ReaderGoToTabs(
    selectedTab: ReaderGoToTab,
    onSelectedTabChange: (ReaderGoToTab) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .selectableGroup()
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f), RoundedCornerShape(12.dp))
            .padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        ReaderGoToTab.entries.forEach { tab ->
            val selected = tab == selectedTab
            val label = when (tab) {
                ReaderGoToTab.Search -> stringResource(R.string.reader_search)
                ReaderGoToTab.Chapters -> stringResource(R.string.reader_chapters)
                ReaderGoToTab.Highlights -> stringResource(R.string.reader_highlights)
            }
            Box(
                modifier = Modifier
                    .weight(1f)
                    .background(
                        color = if (selected) MaterialTheme.colorScheme.surface else Color.Transparent,
                        shape = RoundedCornerShape(10.dp),
                    )
                    .selectable(
                        selected = selected,
                        role = ReaderGoToTabRole,
                        onClick = { onSelectedTabChange(tab) },
                    )
                    .padding(vertical = 7.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (selected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun ReaderSearchTab(
    searchState: ReaderSearchSheetState,
    progressDisplay: ReaderProgressDisplay,
    totalCharacters: Int,
    onJump: (ReaderSearchResult) -> Unit,
    modifier: Modifier = Modifier,
) {
    val focusRequester = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current

    Column(modifier = modifier.fillMaxWidth()) {
        ReaderCompactSearchField(
            query = searchState.query,
            onQueryChange = searchState::updateQuery,
            onSearch = {
                readerSearchImeAction(
                    onSearch = searchState::submitQuery,
                    clearFocus = { focusManager.clearFocus() },
                    hideKeyboard = { keyboardController?.hide() },
                )
            },
            focusRequester = focusRequester,
            modifier = Modifier.padding(start = 20.dp, end = 20.dp, bottom = 8.dp),
        )
        ReaderSearchResultsContent(
            query = searchState.submittedQuery,
            searching = searchState.searching,
            failed = searchState.failed,
            results = searchState.results,
            progressDisplay = progressDisplay,
            totalCharacters = totalCharacters,
            onJump = onJump,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun ReaderCompactSearchField(
    query: String,
    onQueryChange: (String) -> Unit,
    onSearch: () -> Unit,
    focusRequester: FocusRequester,
    modifier: Modifier = Modifier,
) {
    val fieldScrollState = rememberScrollState()
    val fieldState = rememberSyncedTextFieldState(
        value = query,
        onValueChange = onQueryChange,
        scrollState = fieldScrollState,
    )

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 40.dp),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 0.dp,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(
                imageVector = Icons.Rounded.Search,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp),
            )
            ReaderCompactSearchInput(
                fieldState = fieldState,
                query = query,
                onSearch = onSearch,
                fieldScrollState = fieldScrollState,
                focusRequester = focusRequester,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun ReaderCompactSearchInput(
    fieldState: TextFieldState,
    query: String,
    onSearch: () -> Unit,
    fieldScrollState: ScrollState,
    focusRequester: FocusRequester,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier) {
        if (query.isBlank()) {
            Text(
                text = stringResource(R.string.reader_search_in_book),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        BasicTextField(
            state = fieldState,
            lineLimits = hoshiSingleLineTextFieldLineLimits(),
            scrollState = fieldScrollState,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search, showKeyboardOnFocus = true),
            onKeyboardAction = { onSearch() },
            textStyle = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurface),
            cursorBrush = hoshiTextFieldCursorBrush(MaterialTheme.colorScheme.onSurface),
            modifier = Modifier
                .fillMaxWidth()
                .focusRequester(focusRequester)
                .onPreviewKeyEvent { event ->
                    if (event.type == KeyEventType.KeyUp && event.key == Key.Enter) {
                        onSearch()
                        true
                    } else {
                        false
                    }
                },
        )
    }
}

@Composable
private fun ReaderSearchResultsContent(
    query: String,
    searching: Boolean,
    failed: Boolean,
    results: List<ReaderSearchResult>,
    progressDisplay: ReaderProgressDisplay,
    totalCharacters: Int,
    onJump: (ReaderSearchResult) -> Unit,
    modifier: Modifier = Modifier,
) {
    val resultsByChapter = remember(results) { results.groupBy { it.chapterIndex } }
    when {
        !readerSearchQueryHasMatchableText(query) -> ReaderGoToEmptyState(
            icon = Icons.Rounded.Search,
            text = stringResource(R.string.reader_search_empty_prompt),
            modifier = modifier,
        )
        searching -> ReaderGoToLoadingState(modifier = modifier)
        failed -> ReaderGoToEmptyState(
            icon = Icons.Rounded.Search,
            text = stringResource(R.string.reader_search_failed),
            modifier = modifier,
        )
        results.isEmpty() -> ReaderGoToEmptyState(
            icon = Icons.Rounded.Search,
            text = stringResource(R.string.reader_no_search_results),
            modifier = modifier,
        )
        else -> LazyColumn(
            modifier = modifier
                .fillMaxWidth()
                .padding(start = 20.dp, end = 20.dp, bottom = 24.dp),
        ) {
            resultsByChapter.forEach { (_, chapterResults) ->
                val label = chapterResults.first().chapterLabel
                item {
                    Text(
                        text = label.ifBlank { stringResource(R.string.reader_untitled_chapter) },
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(top = 12.dp, bottom = 2.dp),
                    )
                    Text(
                        text = pluralStringResource(
                            R.plurals.reader_search_result_count,
                            chapterResults.size,
                            chapterResults.size,
                        ),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(bottom = 4.dp),
                    )
                }
                items(chapterResults) { result ->
                    ReaderSearchResultRow(
                        result = result,
                        progressDisplay = progressDisplay,
                        totalCharacters = totalCharacters,
                        chapterLabel = label.ifBlank { stringResource(R.string.reader_untitled_chapter) },
                        onClick = { onJump(result) },
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.75f))
                }
            }
        }
    }
}

@Composable
private fun ReaderSearchResultRow(
    result: ReaderSearchResult,
    progressDisplay: ReaderProgressDisplay,
    totalCharacters: Int,
    chapterLabel: String,
    onClick: () -> Unit,
) {
    val positionText = progressDisplay.countWithPercentText(result.character, totalCharacters)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .semantics {
                contentDescription = "$chapterLabel $positionText ${result.snippet}"
            }
            .padding(vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Text(
            text = result.highlightedSnippet(),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 4,
        )
        Text(
            text = positionText,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ReaderSearchResult.highlightedSnippet() =
    buildAnnotatedString {
        val start = snippet.codePointIndex(snippetMatchStart)
        val end = snippet.codePointIndex(snippetMatchEnd)
        append(snippet.substring(0, start))
        withStyle(SpanStyle(background = MaterialTheme.colorScheme.primary.copy(alpha = 0.22f))) {
            append(snippet.substring(start, end))
        }
        append(snippet.substring(end))
    }

@Composable
private fun ReaderGoToChaptersTab(
    book: EpubBook,
    currentPosition: ReaderChapterPosition,
    progressDisplay: ReaderProgressDisplay,
    onJump: (ReaderChapterPosition, String?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val rows = remember(book, currentPosition.index) { book.chapterRows(currentPosition.index) }
    LazyColumn(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = 20.dp, end = 20.dp, bottom = 24.dp),
    ) {
        items(rows) { row ->
            ReaderChapterListRow(
                row = row,
                progressDisplay = progressDisplay,
                onClick = {
                    onJump(ReaderChapterPosition(index = row.spineIndex, progress = 0.0), row.fragment)
                },
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.75f))
        }
    }
}

@Composable
private fun ReaderGoToHighlightsTab(
    book: EpubBook,
    highlights: List<ReaderHighlight>,
    onJump: (ReaderHighlight) -> Unit,
    onDelete: (ReaderHighlight) -> Unit,
    modifier: Modifier = Modifier,
) {
    val sections = remember(book, highlights) { ReaderHighlightSections.sections(book, highlights) }
    val formatter = remember {
        DateTimeFormatter
            .ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT)
            .withLocale(Locale.getDefault())
            .withZone(ZoneId.systemDefault())
    }
    if (highlights.isEmpty()) {
        ReaderGoToEmptyState(
            icon = Icons.Rounded.Edit,
            text = stringResource(R.string.reader_no_highlights),
            modifier = modifier,
        )
    } else {
        LazyColumn(
            modifier = modifier
                .fillMaxWidth()
                .padding(start = 20.dp, end = 20.dp, bottom = 24.dp),
        ) {
            sections.forEach { section ->
                item {
                    Text(
                        text = section.label.ifBlank { stringResource(R.string.reader_untitled_chapter) },
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(top = 12.dp, bottom = 6.dp),
                    )
                }
                items(section.highlights) { highlight ->
                    ReaderHighlightRow(
                        highlight = highlight,
                        createdAtText = highlight.createdAtDisplayText(formatter),
                        onJump = { onJump(highlight) },
                        onDelete = { onDelete(highlight) },
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.75f))
                }
            }
        }
    }
}

@Composable
private fun ReaderGoToEmptyState(
    icon: ImageVector,
    text: String,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = text,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(top = 8.dp),
        )
    }
}

@Composable
private fun ReaderGoToLoadingState(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
            Text(
                text = stringResource(R.string.reader_searching),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

private fun String.codePointIndex(codePointOffset: Int): Int =
    offsetByCodePoints(0, codePointOffset.coerceIn(0, codePointCount(0, length)))

internal fun readerSearchImeAction(
    onSearch: () -> Unit,
    clearFocus: () -> Unit,
    hideKeyboard: () -> Unit,
) {
    onSearch()
    clearFocus()
    hideKeyboard()
}

internal fun readerJumpImeAction(
    input: String,
    totalCharacters: Int,
    progressDisplay: ReaderProgressDisplay,
    onConfirm: (Int) -> Unit,
    hideKeyboard: () -> Unit,
): Boolean {
    val target = readerJumpTargetFromInput(
        input = input,
        totalCharacters = totalCharacters,
        progressDisplay = progressDisplay,
    ) ?: return false
    onConfirm(target)
    hideKeyboard()
    return true
}

internal fun readerJumpTargetFromInput(
    input: String,
    totalCharacters: Int,
    progressDisplay: ReaderProgressDisplay,
): Int? {
    val parsed = input.filter(Char::isDigit).toIntOrNull() ?: return null
    return progressDisplay.rawTargetFromDisplayCount(
        displayCount = parsed,
        totalCharacters = totalCharacters,
    )
}
