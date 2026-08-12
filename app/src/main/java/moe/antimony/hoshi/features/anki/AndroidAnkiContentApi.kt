package moe.antimony.hoshi.features.anki

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import com.ichi2.anki.FlashCardsContract
import com.ichi2.anki.api.AddContentApi
import java.lang.SecurityException
import java.math.BigInteger
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.net.URLEncoder
import java.util.Locale
import javax.inject.Inject

class AndroidAnkiContentApi @Inject constructor(
    @ApplicationContext
    context: Context,
) : AnkiContentApi {
    private val appContext = context.applicationContext
    private val api = AddContentApi(appContext)

    override fun deckList(): Map<Long, String> =
        readAnkiApi(AnkiFetchFailure.DeckListUnavailable) {
            api.deckList ?: throw AnkiFetchException(AnkiFetchFailure.DeckListUnavailable)
        }

    override fun modelList(): Map<Long, String> =
        readAnkiApi(AnkiFetchFailure.ModelListUnavailable) {
            api.modelList ?: throw AnkiFetchException(AnkiFetchFailure.ModelListUnavailable)
        }

    override fun fieldList(modelId: Long): List<String> =
        readAnkiApi(AnkiFetchFailure.ModelFieldsUnavailable) {
            api.getFieldList(modelId)?.toList()
                ?: throw AnkiFetchException(AnkiFetchFailure.ModelFieldsUnavailable)
        }

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

    override fun sync(): Boolean =
        runCatching {
            appContext.startActivity(ankiDroidSyncIntent())
            true
        }.recoverCatching { error ->
            when (error) {
                is ActivityNotFoundException -> logAnkiApiFailure("AnkiDroid sync activity is unavailable.", error)
                else -> logAnkiApiFailure("Unable to start AnkiDroid sync.", error)
            }
            false
        }.getOrDefault(false)

    override fun openNotes(query: String): Boolean =
        runCatching {
            val spec = ankiDroidBrowserIntentSpec(
                query = query,
                packageName = AddContentApi.getAnkiDroidPackageName(appContext) ?: "com.ichi2.anki",
            )
            appContext.startActivity(
                Intent(spec.action, Uri.parse(spec.uri)).apply {
                    setPackage(spec.packageName)
                    if (spec.newTask) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                },
            )
            true
        }.recoverCatching { error ->
            logAnkiApiFailure("Unable to open the AnkiDroid card browser.", error)
            false
        }.getOrDefault(false)

    override fun isAvailable(): Boolean =
        AddContentApi.getAnkiDroidPackageName(appContext) != null

    private inline fun <T> readAnkiApi(fallbackFailure: AnkiFetchFailure, block: () -> T): T {
        if (!isAvailable()) throw AnkiFetchException(AnkiFetchFailure.ApiUnavailable)
        return try {
            block()
        } catch (error: AnkiFetchException) {
            throw error
        } catch (error: SecurityException) {
            logAnkiApiFailure("AnkiDroid database permission denied.", error)
            throw AnkiFetchException(AnkiFetchFailure.PermissionDenied, cause = error)
        } catch (error: Throwable) {
            logAnkiApiFailure("AnkiDroid API request failed.", error)
            throw AnkiFetchException(fallbackFailure, cause = error)
        }
    }

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

private fun logAnkiApiFailure(message: String, error: Throwable) {
    runCatching { Log.w("AndroidAnkiContentApi", message, error) }
}

internal fun ankiDroidSyncIntent(): Intent =
    ankiDroidSyncIntentSpec().let { spec ->
        Intent(spec.action).apply {
            setPackage(spec.packageName)
            spec.categories.forEach(::addCategory)
            flags = spec.flags
        }
    }

internal data class AnkiDroidSyncIntentSpec(
    val action: String,
    val packageName: String,
    val categories: Set<String>,
    val flags: Int,
)

internal fun ankiDroidSyncIntentSpec(): AnkiDroidSyncIntentSpec =
    AnkiDroidSyncIntentSpec(
        action = "com.ichi2.anki.DO_SYNC",
        packageName = "com.ichi2.anki",
        categories = setOf(Intent.CATEGORY_DEFAULT),
        flags = Intent.FLAG_ACTIVITY_NEW_TASK or
            Intent.FLAG_ACTIVITY_NO_HISTORY or
            Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS or
            Intent.FLAG_ACTIVITY_NO_ANIMATION,
    )

internal data class AnkiDroidBrowserIntentSpec(
    val action: String,
    val uri: String,
    val packageName: String,
    val newTask: Boolean,
)

internal fun ankiDroidBrowserIntentSpec(
    query: String,
    packageName: String = "com.ichi2.anki",
): AnkiDroidBrowserIntentSpec =
    AnkiDroidBrowserIntentSpec(
        action = Intent.ACTION_VIEW,
        uri = "anki://x-callback-url/browser?search=" +
            URLEncoder.encode(query, StandardCharsets.UTF_8.name()),
        packageName = packageName,
        newTask = true,
    )

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
            val rootName = selectedDeck.name.substringBefore("::")
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
