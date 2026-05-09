package moe.antimony.hoshi.features.anki

import android.content.Context
import android.net.Uri
import com.ichi2.anki.FlashCardsContract
import com.ichi2.anki.api.AddContentApi
import java.math.BigInteger
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Locale

class AndroidAnkiContentApi(
    context: Context,
) : AnkiContentApi {
    private val appContext = context.applicationContext
    private val api = AddContentApi(appContext)

    override fun deckList(): Map<Long, String> =
        api.deckList.orEmpty()

    override fun modelList(): Map<Long, String> =
        api.modelList.orEmpty()

    override fun fieldList(modelId: Long): List<String> =
        api.getFieldList(modelId)?.toList().orEmpty()

    override fun findDuplicateNotes(
        deck: AnkiDeck,
        modelId: Long,
        key: String,
        duplicateScope: AnkiDuplicateScope,
        checkAllModels: Boolean,
    ): Boolean {
        if (key.isBlank()) return false
        val checksum = ankiFirstFieldChecksum(key)
        if (checksum == 0L) return false
        val scopeDeckIds = ankiDuplicateScopeDeckIds(
            decksById = deckList(),
            selectedDeck = deck,
            duplicateScope = duplicateScope,
        )
        val cursor = appContext.contentResolver.query(
            FlashCardsContract.Note.CONTENT_URI_V2,
            NoteProjection,
            ankiDuplicateNoteSelection(
                modelId = modelId,
                checksum = checksum,
                checkAllModels = checkAllModels,
            ),
            null,
            null,
        ) ?: return false

        cursor.use {
            while (it.moveToNext()) {
                if (duplicateScope == AnkiDuplicateScope.Collection) return true
                val noteId = it.getLong(it.getColumnIndexOrThrow(FlashCardsContract.Note._ID))
                if (noteHasCardInDeck(noteId, scopeDeckIds)) return true
            }
        }
        return false
    }

    override fun addNote(
        modelId: Long,
        deckId: Long,
        fields: Array<String>,
        tags: Set<String>,
    ): Long? = api.addNote(modelId, deckId, fields, tags)

    override fun addMediaFromUri(uriString: String, preferredName: String, mimeType: String): String? =
        api.addMediaFromUri(
            Uri.parse(uriString),
            ankiPreferredMediaName(preferredName),
            if (mimeType.startsWith("audio/")) "audio" else "image",
        )

    override fun isAvailable(): Boolean =
        AddContentApi.getAnkiDroidPackageName(appContext) != null

    private fun noteHasCardInDeck(noteId: Long, deckIds: Set<Long>): Boolean {
        if (deckIds.isEmpty()) return false
        val noteUri = Uri.withAppendedPath(FlashCardsContract.Note.CONTENT_URI, noteId.toString())
        val cardUri = Uri.withAppendedPath(noteUri, "cards")
        val cursor = appContext.contentResolver.query(
            cardUri,
            CardProjection,
            null,
            null,
            null,
        ) ?: return false

        cursor.use {
            while (it.moveToNext()) {
                val cardDeckId = it.getLong(it.getColumnIndexOrThrow(FlashCardsContract.Card.DECK_ID))
                if (cardDeckId in deckIds) return true
            }
        }
        return false
    }

    private companion object {
        val NoteProjection = arrayOf(FlashCardsContract.Note._ID, FlashCardsContract.Note.CSUM)
        val CardProjection = arrayOf(FlashCardsContract.Card.DECK_ID)
    }
}

internal fun ankiDuplicateNoteSelection(
    modelId: Long,
    checksum: Long,
    checkAllModels: Boolean,
): String {
    val checksumSelection = String.format(
        Locale.US,
        "%s in (%d)",
        FlashCardsContract.Note.CSUM,
        checksum,
    )
    return if (checkAllModels) {
        checksumSelection
    } else {
        String.format(
            Locale.US,
            "%s=%d and %s",
            FlashCardsContract.Note.MID,
            modelId,
            checksumSelection,
        )
    }
}

internal fun ankiPreferredMediaName(preferredName: String): String {
    val fileName = preferredName.substringAfterLast('/').substringAfterLast('\\')
    return fileName.substringBeforeLast('.', missingDelimiterValue = fileName)
        .ifBlank { "hoshi_media" }
}

internal fun ankiDuplicateScopeDeckIds(
    decksById: Map<Long, String>,
    selectedDeck: AnkiDeck,
    duplicateScope: AnkiDuplicateScope,
): Set<Long> =
    when (duplicateScope) {
        AnkiDuplicateScope.Collection -> emptySet()
        AnkiDuplicateScope.Deck -> setOf(selectedDeck.id)
        AnkiDuplicateScope.DeckRoot -> {
            val rootName = selectedDeck.name
            decksById.mapNotNull { (id, name) ->
                id.takeIf { name == rootName || name.startsWith("$rootName::") }
            }.toSet()
        }
    }

internal fun ankiFirstFieldChecksum(data: String): Long {
    val strippedData = data.stripHtmlMedia()
    val digest = MessageDigest.getInstance("SHA1")
        .digest(strippedData.toByteArray(StandardCharsets.UTF_8))
    val hex = BigInteger(1, digest).toString(16).padStart(40, '0')
    return hex.substring(0, 8).toLong(16)
}

private val StyleRegex = Regex("(?is)<style.*?>.*?</style>")
private val ScriptRegex = Regex("(?is)<script.*?>.*?</script>")
private val TagRegex = Regex("<.*?>")
private val ImgRegex = Regex("<img src=[\"']?([^\"'>]+)[\"']? ?/?>", RegexOption.IGNORE_CASE)
private val HtmlEntityRegex = Regex("&#?\\w+;")

private fun String.stripHtmlMedia(): String =
    ImgRegex.replace(this) { " ${it.groupValues[1]} " }
        .replace(StyleRegex, "")
        .replace(ScriptRegex, "")
        .replace(TagRegex, "")
        .decodeHtmlEntities()

private fun String.decodeHtmlEntities(): String =
    HtmlEntityRegex.replace(replace("&nbsp;", " ")) { match ->
        when (val entity = match.value) {
            "&amp;" -> "&"
            "&lt;" -> "<"
            "&gt;" -> ">"
            "&quot;" -> "\""
            "&#39;", "&apos;" -> "'"
            else -> entity.decodeNumericHtmlEntity() ?: entity
        }
    }

private fun String.decodeNumericHtmlEntity(): String? {
    val value = removePrefix("&#").removeSuffix(";")
    val codePoint = when {
        value.startsWith("x", ignoreCase = true) -> value.drop(1).toIntOrNull(16)
        value.all(Char::isDigit) -> value.toIntOrNull()
        else -> null
    } ?: return null
    return runCatching { String(Character.toChars(codePoint)) }.getOrNull()
}
