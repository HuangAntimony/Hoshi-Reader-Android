package moe.antimony.hoshi.features.tts

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.preferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.*
import org.junit.Test

class TtsRepositoryTest {
    private class MemoryStore(initial: Preferences) : DataStore<Preferences> {
        override val data = MutableStateFlow(initial)
        override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences =
            transform(data.value).also { data.value = it }
    }

    @Test fun rubyDefaultsOnForLegacyPreferencesAndIsPersistedGloballyAcrossSourceChanges() = runTest {
        val key = stringPreferencesKey("preferences")
        val store = MemoryStore(preferencesOf(key to """{"settings":{"rate":1.5}}"""))
        val repository = TtsRepository(store)
        assertTrue(repository.preferences.first().settings.useRubyReadings)
        repository.useRubyReadings(false)
        assertNull(repository.save(TtsWord("fixture", "今日", "きょう", "book")))
        val restored = TtsRepository(store).preferences.first()
        assertFalse(restored.settings.useRubyReadings)
        assertEquals(1.5f, restored.settings.rate)
        repository.settings(restored.settings.copy(engine = TtsOnlineSource.Edge.id))
        assertFalse(TtsRepository(store).preferences.first().settings.useRubyReadings)
    }

    @Test fun legacyPlaybackDefaultsToNormalAndPersistsIndependentlyOfEngineRate() = runTest {
        val key = stringPreferencesKey("preferences")
        val store = MemoryStore(preferencesOf(key to """{"settings":{"engine":"online:google-translate","rate":0.5}}"""))
        val repository = TtsRepository(store)
        val original = repository.preferences.first().settings
        assertEquals(1f, original.playbackRate)
        repository.settings(original.copy(playbackRate = 2.5f))
        val restored = TtsRepository(store).preferences.first().settings
        assertEquals(2.5f, restored.playbackRate)
        assertEquals(.5f, restored.rate)
    }

    @Test fun invalidRestoredPlaybackRateIsClampedBeforeReachingPlayersOrUi() = runTest {
        val key = stringPreferencesKey("preferences")
        for ((value, expected) in listOf(-10 to .5f, 10 to 3f)) {
            val repository = TtsRepository(MemoryStore(preferencesOf(key to """{"settings":{"playbackRate":$value}}""")))
            assertEquals(expected, repository.preferences.first().settings.playbackRate)
        }
    }

    @Test fun playbackRateIsBoundedAndNonFiniteValuesResetToNormal() = runTest {
        val repository = TtsRepository(MemoryStore(preferencesOf()))
        for ((rate, expected) in listOf(-1f to .5f, .5f to .5f, 1.75f to 1.75f, 3f to 3f, 10f to 3f,
            Float.NaN to 1f, Float.POSITIVE_INFINITY to 1f)) {
            repository.settings(TtsSettings(playbackRate = rate))
            assertEquals(expected, repository.preferences.first().settings.playbackRate)
        }
    }

    @Test fun legacySettingsDefaultToTwoPrefetchedSentencesAndConfiguredCountSurvivesReload() = runTest {
        val key = stringPreferencesKey("preferences")
        val store = MemoryStore(preferencesOf(key to """{"settings":{"rate":1.5}}"""))
        val repository = TtsRepository(store)
        val legacy = repository.preferences.first().settings
        assertEquals(2, legacy.prefetchSentences)
        repository.settings(legacy.copy(prefetchSentences = 6))
        val restored = TtsRepository(store).preferences.first().settings
        assertEquals(6, restored.prefetchSentences)
        assertEquals(1.5f, restored.rate)
    }

    @Test fun savingPrefetchCountClampsToSupportedBounds() = runTest {
        val repository = TtsRepository(MemoryStore(preferencesOf()))
        for ((count, expected) in listOf(-5 to 1, 1 to 1, 6 to 6, 20 to 20, 1000 to 20)) {
            repository.settings(TtsSettings(prefetchSentences = count))
            assertEquals(expected, repository.preferences.first().settings.prefetchSentences)
        }
    }

    @Test fun legacyReadingsAndArbitraryReplacementsSurviveSettingsChangesAndReload() = runTest {
        val key = stringPreferencesKey("preferences")
        val store = MemoryStore(preferencesOf(key to """{"words":[{"id":"legacy","surface":"東京","reading":"とうきょう","bookId":"book"}]}"""))
        val repository = TtsRepository(store)
        val legacy = repository.preferences.first().words.single()
        assertEquals("とうきょう", legacy.replacement)
        val global = TtsWord("global", "――", "。")
        val local = TtsWord("local", "――", " \n、 ", "book")
        assertNull(repository.save(global))
        assertNull(repository.save(local))
        assertEquals(TtsWordError.Duplicate, repository.save(local.copy(id = "duplicate")))
        assertEquals(TtsWordError.Replacement, repository.save(local.copy(replacement = "\n ")))
        repository.settings(TtsSettings(rate = 1.5f))

        val reloaded = TtsRepository(store).preferences.first()
        assertEquals(listOf(legacy, global, local), reloaded.words)
        assertEquals(1.5f, reloaded.settings.rate)
        val sentence = TtsText.chapter(0, "<p>東京――続く。</p>").single()
        assertEquals("とうきょう \n、 続く。", TtsText.spoken(sentence, "book", reloaded.words))
        assertEquals("東京。続く。", TtsText.spoken(sentence, "other", reloaded.words))
        val saved = Json.parseToJsonElement(store.data.value[key]!!).jsonObject.getValue("words").jsonArray
        assertEquals(" \n、 ", saved.last().jsonObject.getValue("reading").jsonPrimitive.content)
        repository.delete(local.id)
        assertEquals("とうきょう。続く。", TtsText.spoken(sentence, "book", repository.preferences.first().words))
    }
}
