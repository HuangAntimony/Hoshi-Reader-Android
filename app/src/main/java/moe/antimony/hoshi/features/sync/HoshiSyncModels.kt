package moe.antimony.hoshi.features.sync

import java.text.Normalizer
import kotlinx.serialization.Required
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import moe.antimony.hoshi.epub.HighlightColor
import moe.antimony.hoshi.epub.ReaderHighlight
import moe.antimony.hoshi.epub.ReadingSession
import kotlin.math.roundToLong

class SyncFormatError : SerializationException("Unsupported sync format.")

object SyncFormat {
    val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }

    inline fun <reified T> decode(data: String): T {
        val document = json.parseToJsonElement(data).jsonObject
        val version = document["formatVersion"]
        if (version == null || version == JsonNull) throw SyncFormatError()
        if (version.jsonPrimitive.isString) throw SerializationException("Invalid formatVersion type.")
        if (version.jsonPrimitive.content.toBigDecimal().longValueExact() != 1L) throw SyncFormatError()
        return json.decodeFromJsonElement(document)
    }

    inline fun <reified T> encode(value: T): String = json.encodeToString(
        JsonObject(json.encodeToJsonElement(value).jsonObject + ("formatVersion" to JsonPrimitive(1))),
    )
}

@Serializable
data class Timestamped<T>(val modified: Long, val value: T) {
    fun <R> replacing(value: R): Timestamped<R> = Timestamped(modified, value)

    companion object {
        fun <T> newest(first: Timestamped<T>?, second: Timestamped<T>?): Timestamped<T>? = when {
            first == null -> second
            second == null -> first
            first.modified >= second.modified -> first
            else -> second
        }

        fun <K, T> merge(first: Map<K, Timestamped<T>>, second: Map<K, Timestamped<T>>): Map<K, Timestamped<T>> =
            first.toMutableMap().apply { second.forEach { (key, value) -> this[key] = newest(this[key], value)!! } }
    }
}

enum class SyncProvider(val rawValue: String) {
    Gdrive("gdrive"),
    Ttu("ttu"),
}

@Serializable
data class SyncMetadata(val title: String, val author: String? = null)

@Serializable
enum class SyncFileType { epub, cover, sasayaki }

typealias SyncFiles = Map<SyncFileType, Timestamped<String?>>

@Serializable
data class SyncBookmark(val characterCount: Int)

@Serializable
data class SyncPlayback(val lastPosition: Double, val delay: Double, val rate: Double)

@Serializable
data class SyncHighlight(
    val character: Int,
    val offset: Int,
    val text: String,
    val textFurigana: String? = null,
    val color: String,
    val createdAt: Long,
) {
    constructor(highlight: ReaderHighlight) : this(
        highlight.character, highlight.offset, highlight.text, highlight.textFurigana,
        highlight.color.rawValue, highlight.createdAt.appleDateMilliseconds(),
    )

    fun highlight(id: String): ReaderHighlight = ReaderHighlight(
        id, character, offset, text, HighlightColor.entries.first { it.rawValue == color },
        createdAt.appleDateSeconds(), textFurigana,
    )
}

@Serializable
data class SyncBook(
    val generation: Int,
    val deleted: Boolean,
    val metadata: Timestamped<SyncMetadata>,
    @Required val characterCount: Int = 0,
    @Required val files: SyncFiles = emptyMap(),
    val bookmark: Timestamped<SyncBookmark>? = null,
    val audiobook: Timestamped<SyncPlayback>? = null,
    @Required val highlights: Map<String, Timestamped<SyncHighlight?>> = emptyMap(),
    @Required val sessions: Map<String, Timestamped<ReadingSession?>> = emptyMap(),
    @Required val shelves: Map<String, Timestamped<Boolean>> = emptyMap(),
) {
    fun delete(): SyncBook = copy(
        deleted = true,
        files = files - SyncFileType.epub - SyncFileType.sasayaki,
        bookmark = null, audiobook = null, highlights = emptyMap(), shelves = emptyMap(),
    )

    fun needsUpload(remote: SyncBook?): Boolean = remote == null || merge(remote, this) != remote

    companion object {
        fun merge(first: SyncBook, second: SyncBook): SyncBook {
            val result = if (first.generation != second.generation) {
                if (first.generation > second.generation) first else second
            } else {
                first.copy(
                    metadata = Timestamped.newest(first.metadata, second.metadata)!!,
                    characterCount = maxOf(first.characterCount, second.characterCount),
                    files = Timestamped.merge(first.files, second.files),
                    bookmark = Timestamped.newest(first.bookmark, second.bookmark),
                    audiobook = Timestamped.newest(first.audiobook, second.audiobook),
                    highlights = mergeRecords(first.highlights, second.highlights),
                    shelves = Timestamped.merge(first.shelves, second.shelves),
                ).let { if (first.deleted || second.deleted) it.delete() else it }
            }
            return result.copy(sessions = mergeRecords(first.sessions, second.sessions))
        }

        fun <T> mergeRecords(first: Map<String, Timestamped<T?>>, second: Map<String, Timestamped<T?>>): Map<String, Timestamped<T?>> =
            first.toMutableMap().apply {
                second.forEach { (id, incoming) ->
                    val held = this[id]
                    this[id] = when {
                        held == null -> incoming
                        held.value == null && incoming.value != null -> held
                        incoming.value == null && held.value != null -> incoming
                        else -> Timestamped.newest(held, incoming)!!
                    }
                }
            }
    }
}

@Serializable
data class SyncShelves(
    val shelves: Map<String, Timestamped<Int?>>,
    @Required val orders: Map<String, Timestamped<List<String>?>> = emptyMap(),
) {
    companion object {
        fun merge(first: SyncShelves, second: SyncShelves): SyncShelves = SyncShelves(
            Timestamped.merge(first.shelves, second.shelves), Timestamped.merge(first.orders, second.orders),
        )
    }
}

@Serializable
data class SyncRecord(
    val generation: Int,
    val deleted: Boolean,
    @Required val files: SyncFiles = emptyMap(),
    @Required val sources: Map<SyncFileType, Long> = emptyMap(),
    @Required val attached: Boolean = false,
    @Required val pending: Boolean = true,
    @Required val cleanup: Set<Int> = emptySet(),
)

@Serializable
data class SyncState(@Required val books: Map<String, SyncRecord> = emptyMap(), @Required val shelvesPending: Boolean = false)

fun String.syncKey(): String = Normalizer.normalize(this, Normalizer.Form.NFC)

fun Double.appleDateMilliseconds(): Long = ((this + 978_307_200.0) * 1000).roundToLong()

fun Long.appleDateSeconds(): Double = toDouble() / 1000 - 978_307_200.0
