package moe.antimony.hoshi.features.reader

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.intl.LocaleList
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import moe.antimony.hoshi.R
import moe.antimony.hoshi.features.anki.ExampleSentence

/**
 * The list always leads with the bare term (bold), then — for the reader and process-text — the
 * in-book/selection sentence (selected by default), then the Massif examples.
 *
 * @param currentSentence the surrounding sentence; shown after the term and selected by default
 *   when present (null for the dictionary, whose only non-example option is the term).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MineWithOptionsSheet(
    term: String,
    onConfirm: (selectedSentence: String?) -> Unit,
    onDismiss: () -> Unit,
    currentSentence: ExampleSentence? = null,
    viewModel: MineWithOptionsViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    LaunchedEffect(term) { viewModel.start(term) }

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

            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                horizontalArrangement = Arrangement.End,
            ) {
                TextButton(
                    onClick = onDismiss,
                    modifier = Modifier.testTag("mine-with-options-cancel"),
                ) {
                    Text(stringResource(R.string.action_cancel))
                }
                Button(
                    onClick = { onConfirm(options.getOrNull(selectedOption)?.picked()) },
                    modifier = Modifier.testTag("mine-with-options-add"),
                ) {
                    Text(stringResource(R.string.mine_with_options_add))
                }
            }
        }
    }
}

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
