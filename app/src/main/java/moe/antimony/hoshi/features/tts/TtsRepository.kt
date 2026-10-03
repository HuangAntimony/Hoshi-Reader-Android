package moe.antimony.hoshi.features.tts

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import moe.antimony.hoshi.di.IoDispatcher
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import moe.antimony.hoshi.epub.EpubBook

@Serializable
data class TtsSettings(
    val engine: String = "", val voice: String = "", val rate: Float = 1f,
    val customSources: List<TtsCustomSource> = emptyList(),
    val online: Map<String, TtsOnlineConfig> = emptyMap(), val pitch: Float = 0f,
    val parameters: Map<String, TtsSynthesisOptions> = emptyMap(), val sentencePauseMs: Long = 0,
    val prefetchSentences: Int = 2, val playbackRate: Float = 1f, val useRubyReadings: Boolean = true,
    val routingBySource: Map<String, TtsVoiceRouting> = emptyMap(),
)
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
            val settings = stored["settings"] as? JsonObject
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

    suspend fun settings(settings: TtsSettings) {
        val custom = settings.customSource()
        val rate = custom?.normalizeRate(settings.rate) ?: TtsOnlineSource.from(settings.engine)?.normalizeRate(settings.rate)
            ?: settings.rate.takeIf { it.isFinite() }?.coerceIn(.5f, 3f) ?: 1f
        val pitchRange = if (settings.isCustomSource()) null else ttsPitchRange(TtsOnlineSource.from(settings.engine), settings.voice.ifBlank { "automatic" })
        val pitch = if (pitchRange != null && settings.pitch.isFinite()) settings.pitch.coerceIn(pitchRange) else 0f
        val sources = settings.customSources.map { if (it.id == settings.engine) it.copy(voice = settings.voice, rate = rate) else it }
        val normalized = settings.copy(customSources = sources, rate = rate, pitch = pitch, sentencePauseMs = settings.sentencePauseMs.coerceIn(0, 5000),
            routingBySource = settings.routingBySource.mapValues { (id, routing) -> if (id == settings.engine) routing.copy(narration = settings.voice) else routing },
            prefetchSentences = settings.prefetchSentences.coerceIn(ttsPrefetchRange),
            playbackRate = normalizeTtsPlaybackRate(settings.playbackRate),
            parameters = settings.parameters.mapValues { (source, options) -> options.normalized(TtsOnlineSource.from(source)) })
        store.edit { it[key] = json.encodeToString(decode(it[key]).copy(settings = normalized)) }
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
    suspend fun saveSource(source: TtsCustomSource) {
        val value = source.validate()
        store.edit {
            val current = decode(it[key])
            val settings = current.settings
            val created = settings.customSources.none { profile -> profile.id == value.id }
            val sources = settings.customSources.filterNot { profile -> profile.id == value.id } + value
            val next = settings.copy(customSources = sources).let { if (created || it.engine == value.id) it.selectSource(value.id) else it }
            it[key] = json.encodeToString(current.copy(settings = next))
        }
    }
    suspend fun deleteSource(id: String) {
        store.edit {
            val current = decode(it[key])
            val settings = current.settings.copy(customSources = current.settings.customSources.filterNot { source -> source.id == id },
                routingBySource = current.settings.routingBySource - id)
            val next = if (settings.engine == id) settings.selectSource("").copy(routingBySource = settings.routingBySource - id) else settings
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

class TtsBookRepository @Inject constructor(@param:IoDispatcher private val dispatcher: CoroutineDispatcher) {
    suspend fun sentences(book: EpubBook): List<TtsSentence> = withContext(dispatcher) {
        book.chapters.flatMapIndexed { index, chapter ->
            if (chapter.linear && !chapter.isGuideToc && chapter.properties?.split(" ")?.contains("nav") != true)
                chapter(book, index) else emptyList()
        }
    }
    suspend fun chapter(book: EpubBook, index: Int): List<TtsSentence> = withContext(dispatcher) {
        val chapter = book.chapters[index]
        val html = chapter.html.ifBlank { book.readResource(chapter.href)?.toString(Charsets.UTF_8).orEmpty() }
        TtsText.chapter(index, html)
    }

    suspend fun selection(book: EpubBook, index: Int, text: String, normalizedOffset: Int?, sentenceOffset: Int?): TtsSentence =
        withContext(dispatcher) { TtsText.selection(chapter(book, index), text, normalizedOffset, sentenceOffset) }

}
