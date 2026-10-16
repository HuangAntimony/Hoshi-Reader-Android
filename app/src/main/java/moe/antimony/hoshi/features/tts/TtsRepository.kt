package moe.antimony.hoshi.features.tts

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName
import kotlinx.serialization.json.*

@Serializable
data class TtsSettings(
    val engine: String = "", @SerialName("rate") val sharedRate: Float = 1f,
    val customSources: List<TtsCustomSource> = emptyList(),
    val online: Map<String, TtsOnlineConfig> = emptyMap(), val pitch: Float = 0f,
    val parameters: Map<String, TtsSynthesisOptions> = emptyMap(), val sentencePauseMs: Long = 0,
    val prefetchSentences: Int = 5, val playbackRate: Float = 1f, val useRubyReadings: Boolean = true,
    val routingBySource: Map<String, TtsVoiceRouting> = emptyMap(),
) {
    val voice: String get() = routingBySource[engine]?.narration.orEmpty()
    val rate: Float get() = customSource()?.rate ?: sharedRate
}
typealias TtsSettingsUpdate = (TtsSettings) -> TtsSettings

internal val ttsPlaybackRateRange = .5f..3f
internal fun normalizeTtsPlaybackRate(rate: Float): Float = if (rate.isFinite()) rate.coerceIn(ttsPlaybackRateRange) else 1f
internal val ttsPrefetchRange = 1..20
@Serializable
data class TtsPreferences(val settings: TtsSettings = TtsSettings(), val words: List<TtsWord> = emptyList())
private val Context.ttsStore by preferencesDataStore("japanese_tts")

@Singleton
class TtsRepository internal constructor(private val store: androidx.datastore.core.DataStore<androidx.datastore.preferences.core.Preferences>) {
    @Inject constructor(@ApplicationContext context: Context) : this(context.ttsStore)
    private val key = stringPreferencesKey("preferences")
    private val json = Json { ignoreUnknownKeys = true }
    val preferences = store.data.map { decode(it[key]) }
    private fun decode(value: String?): TtsPreferences {
        val preferences = value?.let {
            val stored = json.parseToJsonElement(it).jsonObject
            val settings = (stored["settings"] as? JsonObject)?.let(::restoreTtsVoiceSelections)
            // Older saved options/profiles omitted the WAV default. New saves always encode format.
            val restored = settings?.let { fields -> JsonObject(fields.mapValues { (field, entry) -> when (field) {
                "parameters" -> JsonObject(entry.jsonObject.mapValues { (_, options) -> storedFormat(options) })
                "customSources" -> JsonArray(entry.jsonArray.map(::storedFormat))
                else -> entry
            } }) }
            json.decodeFromJsonElement<TtsPreferences>(if (restored == null) stored else JsonObject(stored + ("settings" to restored)))
        } ?: TtsPreferences()
        return preferences.copy(settings = preferences.settings.copy(playbackRate = normalizeTtsPlaybackRate(preferences.settings.playbackRate)))
    }

    private fun storedFormat(value: JsonElement): JsonObject = value.jsonObject.let {
        if ("format" in it) it else JsonObject(it + ("format" to JsonPrimitive(TtsAudioFormat.Wav.name)))
    }

    suspend fun settings(update: TtsSettingsUpdate): Pair<TtsSettings, TtsSettings> {
        var result: Pair<TtsSettings, TtsSettings>? = null
        store.edit {
            val current = decode(it[key])
            val next = normalizeSettings(update(current.settings))
            it[key] = json.encodeToString(current.copy(settings = next))
            result = current.settings to next
        }
        return checkNotNull(result)
    }

    private fun normalizeSettings(settings: TtsSettings): TtsSettings {
        val custom = settings.customSource()
        val rate = custom?.normalizeRate(settings.rate, settings.voice) ?: TtsOnlineSource.from(settings.engine)?.normalizeRate(settings.rate)
            ?: settings.rate.takeIf { it.isFinite() }?.coerceIn(.5f, 3f) ?: 1f
        val pitchRange = if (settings.isCustomSource()) null else ttsPitchRange(TtsOnlineSource.from(settings.engine), settings.voice.ifBlank { "automatic" })
        val pitch = if (pitchRange != null && settings.pitch.isFinite()) settings.pitch.coerceIn(pitchRange) else 0f
        return settings.withRate(rate).copy(pitch = pitch, sentencePauseMs = settings.sentencePauseMs.coerceIn(0, 5000),
            prefetchSentences = settings.prefetchSentences.coerceIn(ttsPrefetchRange),
            playbackRate = normalizeTtsPlaybackRate(settings.playbackRate),
            parameters = settings.parameters.mapValues { (source, options) -> options.normalized(TtsOnlineSource.from(source)) })
    }
    suspend fun useRubyReadings(enabled: Boolean) {
        store.edit {
            val current = decode(it[key])
            it[key] = json.encodeToString(current.copy(settings = current.settings.copy(useRubyReadings = enabled)))
        }
    }
    suspend fun configure(source: TtsOnlineSource, config: TtsOnlineConfig) {
        store.edit {
            val old = decode(it[key])
            it[key] = json.encodeToString(old.copy(settings = old.settings.copy(online = old.settings.online + (source.id to config))))
        }
    }
    suspend fun removeOnlineKey(source: TtsOnlineSource) {
        store.edit {
            val current = decode(it[key])
            val config = current.settings.online[source.id] ?: return@edit
            it[key] = json.encodeToString(current.copy(settings = current.settings.copy(
                online = current.settings.online + (source.id to config.copy(encryptedKey = "")))))
        }
    }
    suspend fun saveSource(source: TtsCustomSource) {
        val value = source.validate()
        store.edit {
            val current = decode(it[key])
            val settings = current.settings
            val created = settings.customSources.none { profile -> profile.id == value.id }
            val protocolChanged = settings.customSources.any { it.id == value.id && it.protocol != value.protocol }
            val sources = settings.customSources.filterNot { profile -> profile.id == value.id } + value
            val updated = settings.copy(customSources = sources,
                routingBySource = if (protocolChanged) settings.routingBySource - value.id else settings.routingBySource)
            val next = if (created || updated.engine == value.id) updated.selectSource(value.id) else updated
            it[key] = json.encodeToString(current.copy(settings = next))
        }
    }
    suspend fun deleteSource(id: String) {
        store.edit {
            val current = decode(it[key])
            val selected = if (current.settings.engine == id) current.settings.selectSource("") else current.settings
            val next = selected.copy(customSources = selected.customSources.filterNot { source -> source.id == id },
                routingBySource = selected.routingBySource - id)
            it[key] = json.encodeToString(current.copy(settings = next))
        }
    }
    suspend fun save(word: TtsWord): TtsWordError? {
        var error: TtsWordError? = null
        store.edit {
            val old = decode(it[key])
            error = validateTtsWord(word, old.words)
            if (error == null) it[key] = json.encodeToString(old.copy(words = old.words.filterNot { w -> w.id == word.id } + word))
        }
        return error
    }
    suspend fun delete(id: String) {
        store.edit { it[key] = json.encodeToString(decode(it[key]).let { old -> old.copy(words = old.words.filterNot { w -> w.id == id }) }) }
    }
}
