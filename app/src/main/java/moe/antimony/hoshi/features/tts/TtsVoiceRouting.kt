package moe.antimony.hoshi.features.tts

import kotlinx.serialization.Serializable

@Serializable
data class TtsVoiceRouting(val enabled: Boolean = false, val narration: String = "", val dialogue: String = "", val initialized: Boolean = enabled)

internal fun TtsSettings.routing() = routingBySource[engine] ?: TtsVoiceRouting(narration = voice, dialogue = voice)
internal fun TtsSettings.withRouting(value: TtsVoiceRouting): TtsSettings {
    val routing = if (value.enabled && !value.initialized) value.copy(dialogue = value.narration, initialized = true) else value
    return copy(voice = routing.narration, routingBySource = routingBySource + (engine to routing),
        customSources = customSources.map { if (it.id == engine) it.copy(voice = routing.narration) else it })
}

internal data class TtsSynthesisPart(val text: String, val settings: TtsSettings)

/** Effective voice overrides are request-local, including profiles read directly by custom engines. */
internal fun TtsSettings.forVoice(id: String): TtsSettings = copy(voice = id,
    customSources = customSources.map { if (it.id == engine) it.copy(voice = id) else it }, routingBySource = emptyMap())

internal fun TtsSettings.sharedVoice(voices: List<TtsVoiceInfo>): TtsVoiceInfo? {
    fun find(id: String) = if (id.isBlank()) voices.firstOrNull() else voices.firstOrNull { it.id == id }
    val narration = find(voice) ?: return null
    if (!routing().enabled) return narration
    val dialogue = find(routing().dialogue) ?: return null
    return narration.copy(styles = narration.styles.intersect(dialogue.styles.toSet()).toList(),
        roles = narration.roles.intersect(dialogue.roles.toSet()).toList())
}

internal fun TtsSettings.sharedPitchRange(voices: List<TtsVoiceInfo>): ClosedFloatingPointRange<Float>? {
    if (isCustomSource()) return null
    fun id(value: String) = value.ifBlank { voices.firstOrNull()?.id.orEmpty() }
    val source = TtsOnlineSource.from(engine)
    val first = ttsPitchRange(source, id(voice)) ?: return null
    if (!routing().enabled) return first
    val second = ttsPitchRange(source, id(routing().dialogue)) ?: return null
    return maxOf(first.start, second.start)..minOf(first.endInclusive, second.endInclusive)
}

internal fun TtsSettings.supportsSharedRate(voices: List<TtsVoiceInfo>): Boolean {
    val source = customSource() ?: return true
    fun supported(id: String) = source.copy(voice = id.ifBlank { voices.firstOrNull()?.id.orEmpty() }).supportsRate
    return supported(voice) && (!routing().enabled || supported(routing().dialogue))
}

internal fun TtsSettings.withCompatibleVoiceOptions(voices: List<TtsVoiceInfo>): TtsSettings {
    val available = sharedVoice(voices)
    val options = options()
    val style = options.style.takeIf { it in available?.styles.orEmpty() }.orEmpty()
    return withOptions(options.copy(style = style, role = options.role.takeIf { style.isNotBlank() && it in available?.roles.orEmpty() }.orEmpty()))
        .copy(pitch = sharedPitchRange(voices)?.let { pitch.coerceIn(it) } ?: 0f)
}

internal fun ttsSynthesisPlan(
    sentence: TtsSentence, bookId: String?, preferences: TtsPreferences, catalog: TtsCatalog?,
    spoken: List<TtsSpokenPart> = TtsText.spokenParts(sentence, bookId, preferences.words, preferences.settings.useRubyReadings),
): List<TtsSynthesisPart> {
    val settings = preferences.settings
    val routing = settings.routing()
    if (spoken.all { it.text.isBlank() }) return emptyList()
    if (!routing.enabled) return listOf(TtsSynthesisPart(spoken.joinToString("") { it.text }, settings))
    fun effective(id: String): String {
        // An OpenAI server's omitted voice means its own default, not necessarily the first listed voice.
        if (settings.customSource()?.protocol == TtsCustomProtocol.OpenAi) return id
        if (id.isNotBlank() && catalog?.voices?.none { it.id == id } == true) throw TtsException(TtsFailure.MissingJapanese)
        return id.ifBlank { catalog?.voices?.firstOrNull()?.id.orEmpty() }
    }
    val narration = effective(settings.voice)
    val dialogue = effective(routing.dialogue)
    val parts = mutableListOf<TtsSynthesisPart>()
    var pendingPunctuation = ""
    spoken.forEach { part ->
        val readable = part.text.codePoints().anyMatch { point ->
            !Character.isWhitespace(point) && Character.getType(point) !in listOf(
                Character.CONNECTOR_PUNCTUATION.toInt(), Character.DASH_PUNCTUATION.toInt(),
                Character.START_PUNCTUATION.toInt(), Character.END_PUNCTUATION.toInt(),
                Character.INITIAL_QUOTE_PUNCTUATION.toInt(), Character.FINAL_QUOTE_PUNCTUATION.toInt(), Character.OTHER_PUNCTUATION.toInt())
        }
        if (!readable) {
            if (parts.isEmpty()) pendingPunctuation += part.text
            else parts[parts.lastIndex] = parts.last().copy(text = parts.last().text + part.text)
        } else {
            val config = settings.forVoice(if (part.role == TtsTextRole.Dialogue) dialogue else narration)
            val text = pendingPunctuation + part.text
            pendingPunctuation = ""
            if (parts.lastOrNull()?.settings == config) parts[parts.lastIndex] = parts.last().copy(text = parts.last().text + text)
            else parts += TtsSynthesisPart(text, config)
        }
    }
    return parts
}
