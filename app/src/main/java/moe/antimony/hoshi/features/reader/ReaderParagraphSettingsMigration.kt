package moe.antimony.hoshi.features.reader

import android.content.SharedPreferences
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive

@Serializable
internal data class ParagraphModeSettings(
    val paragraphMode: Boolean = false,
    val maxSentencesPerPage: Int = 0,
    val splitDialogue: Boolean = false,
    val textAnimation: Boolean = false,
    val textSpeed: Int = 35,
    val paragraphClickAdvance: Boolean = false,
)

private val paragraphMigrationJson = Json { ignoreUnknownKeys = true }

/** Accept old backups before decoding ReaderViewMode, including display-only migration reads. */
internal fun migrateParagraphSettingsJson(source: JsonObject): JsonObject {
    val result = source.toMutableMap()
    val mode = source["viewMode"]?.jsonPrimitive?.contentOrNull
    val legacyVn = mode.equals("visualNovel", ignoreCase = true)
    val hasLegacy = legacyVn || source.keys.any { it.startsWith("visualNovel") }
    if (legacyVn) result["viewMode"] = JsonPrimitive("Paginated")
    if (mode.equals("paginated", ignoreCase = true)) result["viewMode"] = JsonPrimitive("Paginated")
    if (mode.equals("continuous", ignoreCase = true)) result["viewMode"] = JsonPrimitive("Continuous")
    if (!hasLegacy) return JsonObject(result)

    fun default(name: String, value: JsonPrimitive) {
        if (result[name] == null) result[name] = value
    }
    default("paragraphMode", JsonPrimitive(legacyVn))
    val sentences = source["visualNovelScreenMode"]?.jsonPrimitive?.contentOrNull.equals("sentences", ignoreCase = true)
    default("maxSentencesPerPage", JsonPrimitive(if (sentences) {
        (source["visualNovelSentencesPerScreen"]?.jsonPrimitive?.intOrNull ?: 1).coerceIn(1, 10)
    } else 0))
    default("splitDialogue", JsonPrimitive(!(source["visualNovelPreserveDialogueBubbles"]?.jsonPrimitive?.booleanOrNull ?: false)))
    val legacySpeed = source["visualNovelRevealSpeed"]?.jsonPrimitive?.intOrNull ?: 45
    default("textAnimation", JsonPrimitive(legacySpeed > 0))
    default("textSpeed", JsonPrimitive(if (legacySpeed > 0) legacySpeed.coerceParagraphTextSpeed() else 35))
    default("paragraphClickAdvance", JsonPrimitive(source["visualNovelClickAdvance"]?.jsonPrimitive?.booleanOrNull ?: false))
    result.keys.removeAll { it.startsWith("visualNovel") }
    return JsonObject(result)
}

internal fun paragraphSettingsFromPreferences(values: Map<String, Any?>): ParagraphModeSettings {
    val fields = values.filterKeys {
        it.startsWith("visualNovel") || it in setOf(
            "paragraphMode", "maxSentencesPerPage", "splitDialogue", "textAnimation", "textSpeed", "paragraphClickAdvance",
        )
    }.mapValues { (_, value) ->
        when (value) {
            is Boolean -> JsonPrimitive(value)
            is Number -> JsonPrimitive(value)
            else -> JsonPrimitive(value?.toString())
        }
    }.toMutableMap()
    values["readerViewMode"]?.let { fields["viewMode"] = JsonPrimitive(it.toString()) }
    val result = paragraphMigrationJson.decodeFromJsonElement<ParagraphModeSettings>(migrateParagraphSettingsJson(JsonObject(fields)))
    return result.copy(maxSentencesPerPage = result.maxSentencesPerPage.coerceIn(0, 10), textSpeed = result.textSpeed.coerceParagraphTextSpeed())
}

internal fun Int.coerceParagraphTextSpeed(): Int = ((coerceIn(25, 100) + 2) / 5) * 5

private val legacyParagraphKeys = listOf(
    "visualNovelRevealSpeed", "visualNovelScreenMode", "visualNovelSentencesPerScreen",
    "visualNovelPreserveDialogueBubbles", "visualNovelClickAdvance", "visualNovelMergeCrossScreenSasayakiCues",
)

internal fun SharedPreferences.Editor.removeLegacyParagraphSettings(): SharedPreferences.Editor = apply {
    legacyParagraphKeys.forEach(::remove)
}

internal fun MutablePreferences.migrateLegacyParagraphPreferences() {
    val values = asMap().mapKeys { it.key.name }
    val legacyVn = values["readerViewMode"]?.toString().equals("visualNovel", ignoreCase = true)
    if (!legacyVn && legacyParagraphKeys.none(values::containsKey)) return
    val migrated = paragraphSettingsFromPreferences(values)
    if (legacyVn) this[stringPreferencesKey("readerViewMode")] = "paginated"
    this[booleanPreferencesKey("paragraphMode")] = migrated.paragraphMode
    this[intPreferencesKey("maxSentencesPerPage")] = migrated.maxSentencesPerPage
    this[booleanPreferencesKey("splitDialogue")] = migrated.splitDialogue
    this[booleanPreferencesKey("textAnimation")] = migrated.textAnimation
    this[intPreferencesKey("textSpeed")] = migrated.textSpeed.coerceParagraphTextSpeed()
    this[booleanPreferencesKey("paragraphClickAdvance")] = migrated.paragraphClickAdvance
    asMap().keys.filter { it.name in legacyParagraphKeys }.forEach { remove(it) }
}

internal fun migrateReaderViewMode(value: String?, legacyContinuousMode: Boolean): ReaderViewMode =
    if (value.equals("visualNovel", ignoreCase = true)) ReaderViewMode.Paginated
    else ReaderViewMode.fromStorage(value, legacyContinuousMode)
