package moe.antimony.hoshi.features.tts

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.distinctUntilChanged
import moe.antimony.hoshi.profiles.ProfileRepository
import kotlinx.serialization.Transient
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName
import kotlinx.serialization.json.Json

@Serializable
data class TtsSettings(
    val enabled: Boolean = false,
    val engine: String = "", @SerialName("rate") val sharedRate: Float = 1f,
    val customSources: List<TtsCustomSource> = emptyList(),
    val online: Map<String, TtsOnlineConfig> = emptyMap(), val pitch: Float = 0f,
    val parameters: Map<String, TtsSynthesisOptions> = emptyMap(), val sentencePauseMs: Long = 0,
    val prefetchSentences: Int = 5, val playbackRate: Float = 1f, val useRubyReadings: Boolean = true,
    @Transient val languageId: String = "ja",
    val splitAtClausePunctuation: Boolean = false,
    val routingByLanguageAndSource: Map<String, Map<String, TtsVoiceRouting>> = emptyMap(),
    // Decode-only legacy routing; normalized before every write.
    val routingBySource: Map<String, TtsVoiceRouting> = emptyMap(),
) {
    val voice: String get() = routing().narration
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
class TtsRepository internal constructor(
    private val store: androidx.datastore.core.DataStore<androidx.datastore.preferences.core.Preferences>,
    private val language: Flow<String> = flowOf("ja"),
) {
    @Inject constructor(@ApplicationContext context: Context, profiles: ProfileRepository) : this(context.ttsStore,
        profiles.state.map { it.effectiveContentLanguageProfile.id }.distinctUntilChanged())
    private val key = stringPreferencesKey("preferences")
    private val json = Json { ignoreUnknownKeys = true }
    val preferences = combine(store.data, language) { data, languageId ->
        decode(data[key]).let { it.copy(settings = it.settings.copy(languageId = languageId)) }
    }.distinctUntilChanged()
    private val reloadRequests = MutableStateFlow(0)
    internal val preferencesReloads = reloadRequests.asStateFlow()

    /** A settings retry also recovers readers and other retained routes whose read flow failed. */
    fun retryPreferences() { reloadRequests.update { it + 1 } }

    suspend fun enabled(enabled: Boolean) {
        store.edit {
            val current = decode(it[key])
            it[key] = json.encodeToString(current.copy(settings = current.settings.copy(enabled = enabled)))
        }
        retryPreferences()
    }

    /** Disabled is a deliberate no-op; real storage/synthesis failures and caller cancellation propagate. */
    internal suspend fun <T> whileEnabled(action: suspend (TtsPreferences) -> T): T? = try {
        coroutineScope {
            val current = preferences.first()
            if (!current.settings.enabled) return@coroutineScope null
            val work = async { action(current) }
            val watcher = launch {
                preferences.first { !it.settings.enabled || it.settings.languageId != current.settings.languageId }
                work.cancel(TtsDisabledCancellation())
            }
            try { work.await() } finally { watcher.cancel() }
        }
    } catch (_: TtsDisabledCancellation) { null }
    private fun decode(value: String?): TtsPreferences {
        val preferences = value?.let { json.decodeFromString<TtsPreferences>(it) } ?: TtsPreferences()
        return preferences.copy(settings = preferences.settings.migrateRouting().copy(
            playbackRate = normalizeTtsPlaybackRate(preferences.settings.playbackRate),
            sentencePauseMs = preferences.settings.sentencePauseMs.coerceIn(0, ttsSentencePauseMaxMs)))
    }

    suspend fun settings(update: TtsSettingsUpdate): Pair<TtsSettings, TtsSettings> = settings(language.first(), update)

    /** The caller captures the displayed language before launching its write coroutine. */
    suspend fun settings(languageId: String, update: TtsSettingsUpdate): Pair<TtsSettings, TtsSettings> {
        var result: Pair<TtsSettings, TtsSettings>? = null
        store.edit {
            val current = decode(it[key])
            val previous = current.settings.copy(languageId = languageId)
            val next = normalizeSettings(update(previous)).migrateRouting().copy(languageId = languageId)
            it[key] = json.encodeToString(current.copy(settings = next))
            result = previous to next
        }
        return checkNotNull(result)
    }

    private fun normalizeSettings(settings: TtsSettings): TtsSettings {
        val custom = settings.customSource()
        val rate = custom?.normalizeRate(settings.rate, settings.voice) ?: TtsOnlineSource.from(settings.engine)?.normalizeRate(settings.rate)
            ?: settings.rate.takeIf { it.isFinite() }?.coerceIn(.5f, 3f) ?: 1f
        val pitchRange = if (settings.isCustomSource()) null else ttsPitchRange(TtsOnlineSource.from(settings.engine), settings.voice.ifBlank { "automatic" })
        val pitch = if (pitchRange != null && settings.pitch.isFinite()) settings.pitch.coerceIn(pitchRange) else 0f
        return settings.withRate(rate).copy(pitch = pitch, sentencePauseMs = settings.sentencePauseMs.coerceIn(0, ttsSentencePauseMaxMs),
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
            val updated = (if (protocolChanged) settings.withoutSourceRouting(value.id) else settings).copy(customSources = sources)
            val next = if (created || updated.engine == value.id) updated.selectSource(value.id) else updated
            it[key] = json.encodeToString(current.copy(settings = next))
        }
    }
    suspend fun deleteSource(id: String) {
        store.edit {
            val current = decode(it[key])
            val selected = if (current.settings.engine == id) current.settings.selectSource("") else current.settings
            val next = selected.withoutSourceRouting(id).copy(customSources = selected.customSources.filterNot { source -> source.id == id })
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

private class TtsDisabledCancellation : CancellationException("TTS disabled")
