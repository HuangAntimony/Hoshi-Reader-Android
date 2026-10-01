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
import kotlinx.serialization.json.Json
import moe.antimony.hoshi.epub.EpubBook

@Serializable
data class TtsSettings(
    val engine: String = "", val voice: String = "", val rate: Float = 1f,
    val online: Map<String, TtsOnlineConfig> = emptyMap(), val pitch: Float = 0f,
    val parameters: Map<String, TtsSynthesisOptions> = emptyMap(), val sentencePauseMs: Long = 0,
    val prefetchSentences: Int = 2, val playbackRate: Float = 1f, val useRubyReadings: Boolean = true,
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
        val preferences = value?.let { json.decodeFromString<TtsPreferences>(it) } ?: TtsPreferences()
        return preferences.copy(settings = preferences.settings.copy(playbackRate = normalizeTtsPlaybackRate(preferences.settings.playbackRate)))
    }

    suspend fun settings(settings: TtsSettings) {
        val rate = TtsOnlineSource.from(settings.engine)?.normalizeRate(settings.rate)
            ?: settings.rate.takeIf { it.isFinite() }?.coerceIn(.5f, 3f) ?: 1f
        val pitchRange = ttsPitchRange(TtsOnlineSource.from(settings.engine), settings.voice.ifBlank { "automatic" })
        val pitch = if (pitchRange != null && settings.pitch.isFinite()) settings.pitch.coerceIn(pitchRange) else 0f
        val normalized = settings.copy(rate = rate, pitch = pitch, sentencePauseMs = settings.sentencePauseMs.coerceIn(0, 5000),
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

}
