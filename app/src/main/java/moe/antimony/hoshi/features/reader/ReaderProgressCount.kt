package moe.antimony.hoshi.features.reader

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

@Serializable
enum class ReaderProgressCount { Off, Characters, Pages }

internal fun readerProgressCount(value: String?, legacyVisible: Boolean?): ReaderProgressCount =
    ReaderProgressCount.entries.firstOrNull { it.name.equals(value, ignoreCase = true) }
        ?: if (legacyVisible == false) ReaderProgressCount.Off else ReaderProgressCount.Characters

internal fun migrateReaderProgressCountJson(source: JsonObject): JsonObject = JsonObject(source.toMutableMap().apply {
    val choice = readerProgressCount(source["progressCount"]?.jsonPrimitive?.contentOrNull,
        source["showCharacters"]?.jsonPrimitive?.booleanOrNull)
    this["progressCount"] = JsonPrimitive(choice.name)
    remove("showCharacters")
})

internal val ReaderSettings.usesPageCounts: Boolean
    get() = progressCount == ReaderProgressCount.Pages && viewMode == ReaderViewMode.Paginated
