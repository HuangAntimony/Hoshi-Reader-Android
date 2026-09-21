package moe.antimony.hoshi.features.anki

import android.content.ContentUris
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.ichi2.anki.FlashCardsContract
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import moe.antimony.hoshi.features.audio.BuiltInAudioSource
import moe.antimony.hoshi.features.audio.LocalAudioRepository
import moe.antimony.hoshi.features.audio.RemoteWordAudioRepository
import moe.antimony.hoshi.features.audio.UrlConnectionAudioHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Opt-in network/provider smoke test; does not change saved Hoshi settings. */
@RunWith(AndroidJUnit4::class)
class AnkiRemoteAudioDeviceTest {
    @Test
    fun builtInRecordingsExportToAnkiDroidAsMp3() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("ankiRemoteAudioSmoke") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val api = AndroidAnkiContentApi(context)
        assertTrue("AnkiDroid must be initialized and grant Hoshi database access", api.isAvailable())
        val createdIds = mutableListOf<Long>()
        val trackingApi = object : AnkiContentApi by api {
            override fun addNote(modelId: Long, deckId: Long, fields: Array<String>, tags: Set<String>): Long? =
                api.addNote(modelId, deckId, fields, tags).also { id -> id?.let(createdIds::add) }
        }
        val backend = AnkiDroidBackendAdapter(trackingApi)
        val decks = backend.fetchDecks()
        val noteTypes = backend.fetchNoteTypes()
        val deck = decks.firstOrNull { it.name == "Default" } ?: decks.first()
        val noteType = noteTypes.first { it.fields.size >= 2 }
        val format = AnkiCardFormat(
            id = "remote-audio-test",
            name = "Remote audio test",
            selectedDeckId = deck.id,
            selectedNoteTypeId = noteType.id,
            fieldMappings = mapOf(noteType.fields[0] to "{expression}", noteType.fields[1] to "{audio}"),
            tags = "hoshi_remote_audio_test",
        )
        val settings = object : AnkiSettingsRepository {
            override val settings = MutableStateFlow(AnkiSettings(cardFormats = listOf(format)))
            override suspend fun update(transform: (AnkiSettings) -> AnkiSettings) {
                settings.value = transform(settings.value)
            }
        }
        val repository = AnkiRepository(context, backend, settings, LocalAudioRepository(context.filesDir))
        val remote = RemoteWordAudioRepository(UrlConnectionAudioHttpClient(), Dispatchers.IO)
        try {
            for (source in BuiltInAudioSource.entries) {
                val candidates = remote.resolve(source, "食べる", "たべる")
                assertTrue("${source.id} must return a live recording", candidates.isNotEmpty())
                val expression = "hoshi_audio_test_${source.id}_${UUID.randomUUID()}"
                val payload = buildJsonObject {
                    put("expression", expression)
                    put("audio", candidates.first().url)
                }.toString()
                assertTrue(repository.mineEntry(payload, AnkiMiningContext(sentence = ""), decks, noteTypes, format.id))
                val uri = ContentUris.withAppendedId(FlashCardsContract.Note.CONTENT_URI, createdIds.last())
                context.contentResolver.query(uri, arrayOf(FlashCardsContract.Note.FLDS), null, null, null)!!.use { cursor ->
                    assertTrue(cursor.moveToFirst())
                    val fields = cursor.getString(0).split('\u001f')
                    assertEquals(expression, fields[0])
                    assertTrue("${source.id}: ${fields[1]}", fields[1].startsWith("[sound:hoshi_audio_") && fields[1].endsWith(".mp3]"))
                }
            }
            assertEquals(3, createdIds.size)
        } finally {
            createdIds.forEach { id ->
                val uri = ContentUris.withAppendedId(FlashCardsContract.Note.CONTENT_URI, id)
                assertTrue("Remove only this test's notes", context.contentResolver.delete(uri, null, null) > 0)
            }
        }
    }
}
