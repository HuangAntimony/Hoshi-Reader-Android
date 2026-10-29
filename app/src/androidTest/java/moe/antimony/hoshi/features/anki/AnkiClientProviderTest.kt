package moe.antimony.hoshi.features.anki

import android.content.ContentProvider
import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import com.ichi2.anki.FlashCardsContract
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Entirely in-memory providers: never reads or changes an installed Anki collection. */
@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = 29)
class AnkiClientProviderTest {
    @Test fun bothClientsRouteEveryOperationToTheSelectedProvider() {
        for (client in AnkiDroidClient.entries) {
            val fixture = Fixture(client)
            val api = fixture.api
            assertEquals(mapOf(7L to "Mining"), api.deckList())
            assertEquals(mapOf(8L to "Basic"), api.modelList())
            assertEquals(listOf("Front", "Back"), api.fieldList(8))
            assertTrue(api.findDuplicateNotes(AnkiDeck(7, "Mining"), 8, "猫", AnkiDuplicateScope.Deck, false))
            assertEquals(42L, api.addNote(8, 7, arrayOf("猫", ""), setOf("hoshi", "日本語")))
            assertEquals("猫\u001f", fixture.provider.noteValues!!.getAsString("flds"))
            assertEquals("hoshi 日本語", fixture.provider.noteValues!!.getAsString("tags"))
            assertEquals(listOf("/notes/42/cards/0", "/notes/42/cards/1"), fixture.provider.updates.map { it.first.path })
            assertTrue(fixture.provider.updates.all { it.second.getAsLong("deck_id") == 7L })
            val mediaUri = "content://hoshi.fixture/sound.mp3"
            assertEquals("[sound:my_audio.mp3]", api.addMediaFromUri(mediaUri, "my audio.mp3", "audio/mpeg"))
            assertEquals("<img src=\"my_audio.mp3\" />", api.addMediaFromUri(mediaUri, "my audio.png", "image/png"))
            assertEquals("my_audio", fixture.provider.mediaValues!!.getAsString("preferred_name"))
            assertEquals(listOf(client.packageName, client.packageName), fixture.grants)
            assertTrue(api.openNotes("猫"))
            assertTrue(api.sync())
            assertTrue(fixture.intents.all { it.`package` == client.packageName })
            assertEquals("com.ichi2.anki.DO_SYNC", fixture.intents.last().action)
            assertTrue(fixture.provider.requests.isNotEmpty())
            assertTrue(fixture.provider.requests.all { it.authority == client.authority })
        }
    }

    @Test fun unavailableClientDoesNotUseAnotherInstalledProvider() {
        val fixture = Fixture(AnkiDroidClient.Kuma3, providerPackage = AnkiDroidClient.AnkiDroid.packageName)
        assertFalse(fixture.api.isAvailable())
        val error = runCatching { fixture.api.deckList() }.exceptionOrNull() as AnkiFetchException
        assertEquals(AnkiFetchFailure.ApiUnavailable, error.failure)
        assertTrue(fixture.provider.requests.isEmpty())
    }

    @Test fun permissionAndProviderFailuresRetainOriginalCause() {
        val fixture = Fixture(AnkiDroidClient.Kuma3)
        for ((original, failure) in listOf(
            SecurityException("fixture permission denied") to AnkiFetchFailure.PermissionDenied,
            IllegalStateException("fixture collection locked") to AnkiFetchFailure.DeckListUnavailable,
        )) {
            fixture.provider.failure = original
            val error = runCatching { fixture.api.deckList() }.exceptionOrNull() as AnkiFetchException
            assertEquals(failure, error.failure)
            assertSame(original, error.cause)
        }
    }

    private class Fixture(val client: AnkiDroidClient, providerPackage: String? = client.packageName) {
        val provider = RecordingProvider()
        val grants = mutableListOf<String>()
        val intents = mutableListOf<Intent>()
        val context = object : ContextWrapper(ApplicationProvider.getApplicationContext<Context>()) {
            override fun getApplicationContext(): Context = this
            override fun getContentResolver(): ContentResolver = ContentResolver.wrap(provider)
            override fun grantUriPermission(toPackage: String, uri: Uri, modeFlags: Int) {
                assertEquals(Intent.FLAG_GRANT_READ_URI_PERMISSION, modeFlags)
                grants += toPackage
            }
            override fun startActivity(intent: Intent) { intents += intent }
        }
        val api = AndroidAnkiContentApi(context, client) { authority ->
            assertEquals(client.authority, authority)
            providerPackage
        }
    }

    private class RecordingProvider : ContentProvider() {
        val requests = mutableListOf<Uri>()
        val updates = mutableListOf<Pair<Uri, ContentValues>>()
        var noteValues: ContentValues? = null
        var mediaValues: ContentValues? = null
        var failure: RuntimeException? = null
        override fun onCreate() = true
        override fun getType(uri: Uri) = "application/octet-stream"
        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = error("Unexpected delete")
        override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor {
            requests += uri
            failure?.let { throw it }
            return when (uri.path) {
                "/decks" -> MatrixCursor(arrayOf("deck_id", "deck_name")).apply { addRow(arrayOf<Any>(7L, "Mining")) }
                "/models", "/models/8" -> MatrixCursor(arrayOf("_id", "name", "field_names")).apply { addRow(arrayOf<Any>(8L, "Basic", "Front\u001fBack")) }
                "/notes_v2" -> MatrixCursor(arrayOf("_id", "csum")).apply { addRow(arrayOf<Any>(42L, ankiFirstFieldChecksum("猫"))) }
                "/notes/42/cards" -> MatrixCursor(arrayOf(FlashCardsContract.Card.CARD_ORD, "deck_id")).apply {
                    addRow(arrayOf<Any>(0, 7L)); addRow(arrayOf<Any>(1, 7L))
                }
                else -> error("Unexpected query $uri")
            }
        }
        override fun insert(uri: Uri, values: ContentValues?): Uri {
            requests += uri
            return when (uri.path) {
                "/notes" -> {
                    noteValues = ContentValues(values)
                    // kuma3 v31 returns the official authority even when called through its own.
                    Uri.parse("content://com.ichi2.anki.flashcards/notes/42")
                }
                "/media" -> {
                    mediaValues = ContentValues(values)
                    Uri.parse("file:///my_audio.mp3")
                }
                else -> error("Unexpected insert $uri")
            }
        }
        override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int {
            requests += uri
            updates += uri to ContentValues(values)
            return 1
        }
    }
}
