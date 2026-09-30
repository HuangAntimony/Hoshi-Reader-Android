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
data class TtsSettings(val engine: String = "", val voice: String = "", val rate: Float = 1f)
@Serializable
data class TtsPreferences(val settings: TtsSettings = TtsSettings(), val words: List<TtsWord> = emptyList())
private val Context.ttsStore by preferencesDataStore("japanese_tts")

@Singleton
class TtsRepository internal constructor(private val store: androidx.datastore.core.DataStore<androidx.datastore.preferences.core.Preferences>) {
    @Inject constructor(@ApplicationContext context: Context) : this(context.ttsStore)
    private val key = stringPreferencesKey("preferences")
    private val json = Json { ignoreUnknownKeys = true }
    val preferences = store.data.map { decode(it[key]) }
    private fun decode(value: String?) = value?.let { json.decodeFromString<TtsPreferences>(it) } ?: TtsPreferences()

    suspend fun settings(settings: TtsSettings) {
        store.edit { it[key] = json.encodeToString(decode(it[key]).copy(settings = settings.copy(rate = settings.rate.coerceIn(.5f, 3f)))) }
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
