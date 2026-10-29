package moe.antimony.hoshi.features.anki

import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.SystemClock
import androidx.core.content.ContextCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import moe.antimony.hoshi.features.audio.LocalAudioRepository
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Opt-in only: creates a uniquely tagged note in kuma3, then removes only that note. */
@RunWith(AndroidJUnit4::class)
class AnkiKuma3SmokeTest {
    @Test fun fetchCreateFindAndOpenNoteInInstalledKuma3() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("kuma3Smoke") == "true")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val client = AnkiDroidClient.Kuma3
        val api = AndroidAnkiContentApi(context, client)
        assertTrue("Install and initialize kuma3 before running this test", api.isAvailable())
        assertEquals(PackageManager.PERMISSION_GRANTED, ContextCompat.checkSelfPermission(context, client.readWritePermission))
        val tag = "hoshi_kuma3_smoke_${UUID.randomUUID().toString().replace("-", "")}"
        val marker = "Hoshi kuma3 smoke ${UUID.randomUUID()}"
        val settings = object : AnkiSettingsRepository {
            val state = MutableStateFlow(AnkiSettings(ankiDroidClient = client))
            override val settings = state
            override suspend fun update(transform: (AnkiSettings) -> AnkiSettings) { state.value = transform(state.value) }
        }
        val scratch = File(context.cacheDir, tag).apply { mkdirs() }
        val repository = AnkiRepository(
            context = context,
            backend = AnkiDroidBackendAdapter(api, client.displayName),
            settingsRepository = settings,
            localAudioRepository = LocalAudioRepository(scratch),
        )
        val notesUri = Uri.parse("content://${client.authority}/notes")
        try {
            val fetched = repository.fetchConfiguration()
            assertTrue("Fetching failed: $fetched", fetched is AnkiFetchResult.Success)
            val result = fetched as AnkiFetchResult.Success
            val deck = result.decks.first()
            val type = result.noteTypes.first { it.fields.size == 2 }
            val format = settings.state.value.cardFormats.single().copy(
                selectedDeckId = deck.id, selectedDeckName = deck.name,
                selectedNoteTypeId = type.id, selectedNoteTypeName = type.name,
                fieldMappings = mapOf(type.fields[0] to "{expression}", type.fields[1] to "Hoshi integration test"),
                tags = tag,
            )
            settings.update { it.copy(cardFormats = listOf(format)) }
            assertEquals(false, repository.duplicateStates(mapOf("{expression}" to marker), result.decks, result.noteTypes)[format.id])
            assertTrue(repository.mineEntry(
                """{"expression":"$marker"}""", AnkiMiningContext(sentence = ""), result.decks, result.noteTypes, format.id,
            ))
            assertEquals(true, repository.duplicateStates(mapOf("{expression}" to marker), result.decks, result.noteTypes)[format.id])
            context.contentResolver.query(notesUri, arrayOf("_id", "flds"), "tag:$tag", null, null)!!.use {
                assertEquals(1, it.count)
                assertTrue(it.moveToFirst())
                assertEquals("$marker\u001fHoshi integration test", it.getString(1))
                val cardsUri = Uri.parse("content://${client.authority}/notes/${it.getLong(0)}/cards")
                context.contentResolver.query(cardsUri, arrayOf("deck_id"), null, null, null)!!.use { cards ->
                    assertTrue(cards.moveToFirst())
                    assertEquals(deck.id, cards.getLong(0))
                }
            }
            assertTrue(repository.showNotes(format.id, mapOf("{expression}" to marker), result.decks, result.noteTypes))
            val automation = instrumentation.uiAutomation
            val deadline = SystemClock.uptimeMillis() + 10_000
            while (automation.rootInActiveWindow?.packageName?.toString() != client.packageName && SystemClock.uptimeMillis() < deadline) {
                SystemClock.sleep(100)
            }
            assertEquals(client.packageName, automation.rootInActiveWindow?.packageName?.toString())
            automation.waitForIdle(500, 5_000)
            File(context.cacheDir, "kuma3-smoke.png").outputStream().use { output ->
                assertTrue(automation.takeScreenshot().compress(Bitmap.CompressFormat.PNG, 100, output))
            }
        } finally {
            // Search by the unique fixture tag even if insertion failed after creating its note.
            val ownedNoteIds = context.contentResolver.query(notesUri, arrayOf("_id"), "tag:$tag", null, null)!!.use {
                buildList { while (it.moveToNext()) add(it.getLong(0)) }
            }
            ownedNoteIds.forEach { id ->
                assertTrue(context.contentResolver.delete(Uri.withAppendedPath(notesUri, id.toString()), null, null) > 0)
            }
            scratch.deleteRecursively()
        }
    }
}
