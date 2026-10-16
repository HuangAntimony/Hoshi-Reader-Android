package moe.antimony.hoshi.features.tts

import kotlinx.serialization.json.*

/** Read legacy duplicated voice fields once; subsequent saves use routingBySource alone. */
internal fun restoreTtsVoiceSelections(settings: JsonObject): JsonObject {
    val selections = (settings["routingBySource"] as? JsonObject).orEmpty().toMutableMap()
    fun restore(id: String, voice: JsonElement, replace: Boolean) {
        if (replace || id !in selections) {
            val old = (selections[id] as? JsonObject).orEmpty()
            selections[id] = JsonObject(old + ("narration" to voice))
        }
    }
    (settings["customSources"] as? JsonArray).orEmpty().forEach { entry ->
        val profile = entry.jsonObject
        val id = profile["id"]?.jsonPrimitive?.content ?: return@forEach
        profile["voice"]?.let { restore(id, it, replace = false) }
    }
    settings["voice"]?.let { restore(settings["engine"]?.jsonPrimitive?.content.orEmpty(), it, replace = true) }
    return JsonObject(settings + ("routingBySource" to JsonObject(selections)))
}
