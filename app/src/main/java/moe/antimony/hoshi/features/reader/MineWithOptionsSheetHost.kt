package moe.antimony.hoshi.features.reader

import androidx.compose.runtime.Composable
import moe.antimony.hoshi.features.anki.AnkiMiningContext
import moe.antimony.hoshi.features.anki.AnkiMiningPayload
import moe.antimony.hoshi.features.anki.ExampleSentence

internal data class MineWithOptionsRequest(
    val popupId: String,
    val messageId: String,
    val payloadJson: String,
    val baseContext: AnkiMiningContext,
    val term: String,
)

/** Which leading options the sheet shows (the term is always present). */
internal enum class MineSentenceMode {
    /** Reader / process-text: the term plus the in-book sentence (selected by default). */
    InBookSentence,

    /** Dictionary: just the term (no in-book sentence). */
    Term,
}

/**
 * The context committed by the options flow. A picked sentence replaces the
 * sentence (clearing the stale in-book offset); a null pick keeps the base context
 * unchanged, so "mine with options" with the in-book sentence equals instant mine.
 */
internal fun augmentedMiningContext(base: AnkiMiningContext, picked: String?): AnkiMiningContext =
    if (picked != null) {
        base.copy(sentence = picked, sentenceOffset = null)
    } else {
        base
    }

/**
 * Mounts [MineWithOptionsSheet] for [request] and routes its result. Reused by the
 * reader, dictionary tab, and process-text surfaces — each supplies how to mine
 * ([mine]) and how to reply to the popup bridge ([reply]); [onClose] clears the
 * request state. The iframe promise is always answered (mined boolean on Add,
 * false on Cancel).
 */
@Composable
internal fun MineWithOptionsSheetHost(
    request: MineWithOptionsRequest?,
    mine: (payloadJson: String, context: AnkiMiningContext, onResult: (Boolean) -> Unit) -> Unit,
    reply: (popupId: String, messageId: String, body: String) -> Unit,
    onClose: () -> Unit,
    sentenceMode: MineSentenceMode,
) {
    val req = request ?: return
    // Reader / process-text carry a real surrounding sentence; the dictionary does not.
    val inBookSentence = req.baseContext.sentence
        .takeIf { sentenceMode == MineSentenceMode.InBookSentence && it.isNotBlank() }
        ?.let { sentence -> ExampleSentence(sentence, inBookHighlights(req, sentence)) }

    MineWithOptionsSheet(
        term = req.term,
        currentSentence = inBookSentence,
        onConfirm = { picked ->
            mine(req.payloadJson, augmentedMiningContext(req.baseContext, picked)) { mined ->
                reply(req.popupId, req.messageId, mined.toString())
            }
            onClose()
        },
        onDismiss = {
            reply(req.popupId, req.messageId, false.toString())
            onClose()
        },
    )
}

/**
 * Bold range for the looked-up word in the in-book sentence. Mirrors
 * `AnkiHandlebarRenderer.sentenceValue` (offset + matched surface form, with an
 * indexOf fallback) so the highlight matches the word bolded on the mined card.
 */
private fun inBookHighlights(req: MineWithOptionsRequest, sentence: String): List<IntRange> {
    val matched = runCatching { AnkiMiningPayload.fromJson(req.payloadJson).matched }
        .getOrNull().orEmpty()
    if (matched.isBlank()) return emptyList()
    val offset = req.baseContext.sentenceOffset
    if (
        offset != null &&
        offset >= 0 &&
        offset + matched.length <= sentence.length &&
        sentence.regionMatches(offset, matched, 0, matched.length)
    ) {
        return listOf(offset until offset + matched.length)
    }
    val index = sentence.indexOf(matched)
    return if (index >= 0) listOf(index until index + matched.length) else emptyList()
}
