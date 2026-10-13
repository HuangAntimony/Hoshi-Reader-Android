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
    @Test fun firstEnableInheritsTheVoiceChosenWhileDisabledButReenableKeepsDialogue() {
        var settings = TtsSettings(engine = TtsOnlineSource.Edge.id)
        settings = settings.withRouting(settings.routing().copy(narration = "chosen"))
        settings = settings.withRouting(settings.routing().copy(enabled = true))
        assertEquals("chosen", settings.routing().dialogue)
        settings = settings.withRouting(settings.routing().copy(dialogue = "other"))
        settings = settings.withRouting(settings.routing().copy(enabled = false))
        settings = settings.withRouting(settings.routing().copy(narration = "new-narration"))
        settings = settings.withRouting(settings.routing().copy(enabled = true))
        assertEquals("other", settings.routing().dialogue)
    }

    @Test fun dialogueRoutingDefaultsOffAndRestoresEachSourceIndependently() = runTest {
        val store = MemoryStore(preferencesOf())
        val repository = TtsRepository(store)
        assertFalse(repository.preferences.first().settings.routing().enabled)
        val first = TtsSettings(engine = TtsOnlineSource.Edge.id, voice = "narrator")
            .withRouting(TtsVoiceRouting(true, "narrator", "dialogue"))
        repository.settings(first)
        repository.settings(repository.preferences.first().settings.selectSource(TtsOnlineSource.Azure.id).copy(voice = "azure")
            .withRouting(TtsVoiceRouting(true, "azure", "other")))
        val restored = TtsRepository(store).preferences.first().settings.selectSource(TtsOnlineSource.Edge.id)
        assertEquals("narrator", restored.voice)
        assertEquals(TtsVoiceRouting(true, "narrator", "dialogue"), restored.routing())
        repository.settings(restored.withRouting(restored.routing().copy(enabled = false)))
        val disabled = repository.preferences.first().settings
        assertEquals("dialogue", disabled.routing().dialogue)
        assertEquals(TtsVoiceRouting(true, "azure", "other"), disabled.selectSource(TtsOnlineSource.Azure.id).routing())
        assertEquals(first.synthesisSettings(), first.copy(routingBySource = first.routingBySource + ("unselected" to TtsVoiceRouting(true, "a", "b"))).synthesisSettings())
    }

    private class MemoryStore(initial: Preferences) : DataStore<Preferences> {
        override val data = MutableStateFlow(initial)
        override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences =
            transform(data.value).also { data.value = it }
    }

    @Test fun creatingSourceSelectsItAtomicallyAndEditingAnotherKeepsSelection() = runTest {
        val store = MemoryStore(preferencesOf())
        val repository = TtsRepository(store)
        repository.settings(TtsSettings(engine = TtsOnlineSource.Edge.id, voice = "old-voice", playbackRate = 2f))
        val first = TtsCustomSource("custom:first", "First", baseUrl = "http://127.0.0.1/v1", model = "tts", voice = "first-voice", rate = 1.5f)
        repository.saveSource(first)
        val selected = TtsRepository(store).preferences.first().settings
        assertEquals(first.id, selected.engine)
        assertEquals("first-voice", selected.voice)
        assertEquals(1.5f, selected.rate)
        assertEquals(2f, selected.playbackRate)
        val second = first.copy(id = "custom:second", name = "Second", voice = "second-voice", rate = .75f)
        repository.saveSource(second)
        repository.saveSource(first.copy(name = "Renamed", model = "different"))
        val restored = TtsRepository(store).preferences.first().settings
        assertEquals(second.id, restored.engine)
        assertEquals("second-voice", restored.voice)
        assertEquals(.75f, restored.rate)
        assertEquals("Renamed", restored.customSources.first { it.id == first.id }.name)
    }

    @Test fun newSourcesDefaultToMp3AndPersistEitherFormatAcrossReload() = runTest {
        val store = MemoryStore(preferencesOf())
        val repository = TtsRepository(store)
        for (source in listOf(TtsOnlineSource.Azure, TtsOnlineSource.GoogleCloud, TtsOnlineSource.MicrosoftTranslator)) {
            assertEquals(TtsAudioFormat.Mp3, TtsSettings(engine = source.id).options().format)
        }
        val custom = TtsCustomSource("custom:format", "Local", baseUrl = "http://127.0.0.1/v1", model = "tts")
        assertEquals(TtsAudioFormat.Mp3, custom.format)
        repository.saveSource(custom)
        for (format in listOf(TtsAudioFormat.Wav, TtsAudioFormat.Mp3)) {
            repository.settings(TtsSettings(engine = TtsOnlineSource.Azure.id, customSources = listOf(custom.copy(format = format)))
                .withOptions(TtsSynthesisOptions(format = format)))
            val restored = TtsRepository(store).preferences.first().settings
            assertEquals(format, restored.options().format)
            assertEquals(format, restored.customSources.single().format)
        }
    }

    @Test fun legacyOmittedFormatsStayWavWhileUnconfiguredSourcesUseMp3() = runTest {
        val store = MemoryStore(preferencesOf(stringPreferencesKey("preferences") to """{"settings":{
          "engine":"online:azure","parameters":{"online:azure":{},"online:google-cloud":{"format":"Mp3"}},
          "customSources":[{"id":"custom:legacy","name":"Legacy","baseUrl":"http://127.0.0.1/v1","model":"tts"}]}}
        """))
        val repository = TtsRepository(store)
        val settings = repository.preferences.first().settings
        assertEquals(TtsAudioFormat.Wav, settings.options().format)
        assertEquals(TtsAudioFormat.Wav, settings.customSources.single().format)
        assertEquals(TtsAudioFormat.Mp3, settings.copy(engine = TtsOnlineSource.GoogleCloud.id).options().format)
        assertEquals(TtsAudioFormat.Mp3, settings.copy(engine = TtsOnlineSource.MicrosoftTranslator.id).options().format)
        repository.settings(settings.copy(playbackRate = 2f))
        val restored = TtsRepository(store).preferences.first().settings
        assertEquals(TtsAudioFormat.Wav, restored.options().format)
        assertEquals(TtsAudioFormat.Wav, restored.customSources.single().format)
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
    @Test fun regexEntriesRoundTripAndLegacyEntriesRemainLiteral() = runTest {
        val store = MemoryStore(preferencesOf(stringPreferencesKey("preferences") to
            """{"words":[{"id":"old","surface":"(時)々","reading":"$1$1"}]}"""))
        val repository = TtsRepository(store)
        assertFalse(repository.preferences.first().words.single().regex)
        val regex = TtsWord("new", "(時)々", "\$1\$1", regex = true)
        assertNull(repository.save(regex))
        val restored = TtsRepository(store).preferences.first()
        assertEquals(2, restored.words.size)
        assertEquals("時時", TtsText.spoken(TtsSentence(0, 0, 2, "時々"), null, restored.words))
        assertEquals(TtsWordError.Duplicate, repository.save(regex.copy(id = "duplicate")))
        assertNull(repository.save(regex.copy(id = "book", bookId = "book")))
        assertEquals(TtsWordError.RegexPattern, repository.save(regex.copy(id = "invalid", surface = "[")))
        assertEquals(3, repository.preferences.first().words.size)
    }

}
