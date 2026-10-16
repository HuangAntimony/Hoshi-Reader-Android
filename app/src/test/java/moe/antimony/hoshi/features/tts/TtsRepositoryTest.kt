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
    @Test fun legacyFlatVoiceAndAutomaticCustomVoiceKeepTheirSelectionsAfterReload() = runTest {
        val key = stringPreferencesKey("preferences")
        for ((json, expected) in listOf(
            """{"settings":{"engine":"system.example","voice":"ja-JP","rate":1.5}}""" to "ja-JP",
            """{"settings":{"engine":"custom:one","customSources":[{"id":"custom:one","voice":"alice"}]}}""" to "alice",
            """{"settings":{"engine":"custom:one","voice":"","customSources":[{"id":"custom:one","voice":"alice"}]}}""" to "",
        )) {
            val store = MemoryStore(preferencesOf(key to json))
            val repository = TtsRepository(store)
            assertEquals(expected, repository.preferences.first().settings.voice)
            repository.settings { it.copy(playbackRate = 2f) }
            assertEquals(expected, TtsRepository(store).preferences.first().settings.voice)
        }
    }

    @Test fun requestLocalVoiceOverrideKeepsSourceConfigurationAndSavedRouting() {
        val profile = TtsCustomSource("custom:test", "Local", rate = 1.5f)
        val original = TtsSettings(engine = profile.id, customSources = listOf(profile))
            .withRouting(TtsVoiceRouting(true, "narration", "dialogue"))
        val preview = original.forVoice(original.voice)
        assertEquals("narration", preview.voice)
        assertFalse(preview.routing().enabled)
        val dialogue = original.forVoice("dialogue")
        assertEquals("dialogue", dialogue.voice)
        assertEquals(1.5f, dialogue.rate)
        assertEquals(profile, dialogue.customSource())
        assertEquals(TtsVoiceRouting(true, "narration", "dialogue"), original.routing())
    }

    @Test fun changingCustomProtocolDropsIncompatibleVoicesButRenamingDoesNot() = runTest {
        val repository = TtsRepository(MemoryStore(preferencesOf()))
        val source = TtsCustomSource("custom:test", "Local", baseUrl = "https://example.com/v1/", model = "tts")
        repository.saveSource(source)
        repository.settings { it.withRouting(TtsVoiceRouting(true, "narration", "dialogue")) }
        repository.saveSource(source.copy(name = "Renamed"))
        assertEquals("dialogue", repository.preferences.first().settings.routing().dialogue)
        repository.saveSource(source.copy(protocol = TtsCustomProtocol.Vits))
        assertEquals("", repository.preferences.first().settings.voice)
        assertFalse(repository.preferences.first().settings.routing().enabled)
    }

    @Test fun delayedSettingEditsPreserveOtherSettingsAndNewlyConfiguredSources() = runTest {
        val store = MemoryStore(preferencesOf())
        val repository = TtsRepository(store)
        val changeRate: TtsSettingsUpdate = { it.withRate(1.5f) }
        val changePause: TtsSettingsUpdate = { it.copy(sentencePauseMs = 300) }
        repository.configure(TtsOnlineSource.Azure, TtsOnlineConfig("eastasia", "encrypted"))
        repository.useRubyReadings(false)
        repository.settings(changeRate)
        repository.settings(changePause)
        val saved = TtsRepository(store).preferences.first().settings
        assertEquals(1.5f, saved.rate)
        assertEquals(300L, saved.sentencePauseMs)
        assertFalse(saved.useRubyReadings)
        assertEquals("encrypted", saved.online.getValue(TtsOnlineSource.Azure.id).encryptedKey)
    }

    @Test fun legacyActiveAndInactiveSourceVoicesRatesAndCredentialsSurviveEditingAndReload() = runTest {
        val key = stringPreferencesKey("preferences")
        val store = MemoryStore(preferencesOf(key to """{"settings":{
          "engine":"custom:one","voice":"alice","rate":1.5,"playbackRate":2.5,
          "online":{"online:azure":{"region":"eastasia","encryptedKey":"cloud-cipher"}},
          "customSources":[
            {"id":"custom:one","name":"One","baseUrl":"https://one.example/v1/","model":"tts","voice":"alice","rate":1.5,"encryptedKey":"local-cipher","format":"Mp3"},
            {"id":"custom:two","name":"Two","baseUrl":"https://two.example/v1/","model":"other","voice":"bob","rate":2.0}],
          "routingBySource":{"custom:one":{"enabled":true,"narration":"alice","dialogue":"carol"},
            "online:edge":{"narration":"ja-JP-NanamiNeural"}}
        },"words":[{"id":"word","surface":"々","reading":"","regex":false}]}"""))
        val repository = TtsRepository(store)
        val old = repository.preferences.first().settings
        assertEquals("alice", old.voice)
        assertEquals(1.5f, old.rate)
        assertEquals("carol", old.routing().dialogue)
        repository.saveSource(old.customSource()!!.copy(name = "Renamed"))
        val restored = TtsRepository(store).preferences.first()
        assertEquals("alice", restored.settings.voice)
        assertEquals("carol", restored.settings.routing().dialogue)
        assertEquals("bob", restored.settings.selectSource("custom:two").voice)
        assertEquals(2f, restored.settings.selectSource("custom:two").rate)
        assertEquals(TtsAudioFormat.Wav, restored.settings.selectSource("custom:two").customSource()!!.format)
        assertEquals("ja-JP-NanamiNeural", restored.settings.selectSource("online:edge").voice)
        assertEquals("cloud-cipher", restored.settings.online.getValue("online:azure").encryptedKey)
        assertEquals("local-cipher", restored.settings.customSource()!!.encryptedKey)
        assertEquals(2.5f, restored.settings.playbackRate)
        assertEquals("", restored.words.single().replacement)
    }

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
        val first = TtsSettings(engine = TtsOnlineSource.Edge.id).withVoice("narrator")
            .withRouting(TtsVoiceRouting(true, "narrator", "dialogue"))
        repository.settings { first }
        repository.settings { it.selectSource(TtsOnlineSource.Azure.id).withVoice("azure")
            .withRouting(TtsVoiceRouting(true, "azure", "other")) }
        val restored = TtsRepository(store).preferences.first().settings.selectSource(TtsOnlineSource.Edge.id)
        assertEquals("narrator", restored.voice)
        assertEquals(TtsVoiceRouting(true, "narrator", "dialogue"), restored.routing())
        repository.settings { restored.withRouting(restored.routing().copy(enabled = false)) }
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
        repository.settings { TtsSettings(engine = TtsOnlineSource.Edge.id, playbackRate = 2f).withVoice("old-voice") }
        val first = TtsCustomSource("custom:first", "First", baseUrl = "http://127.0.0.1/v1", model = "tts", rate = 1.5f)
        repository.saveSource(first)
        repository.settings { it.withVoice("first-voice") }
        val selected = TtsRepository(store).preferences.first().settings
        assertEquals(first.id, selected.engine)
        assertEquals("first-voice", selected.voice)
        assertEquals(1.5f, selected.rate)
        assertEquals(2f, selected.playbackRate)
        val second = first.copy(id = "custom:second", name = "Second", rate = .75f)
        repository.saveSource(second)
        repository.settings { it.withVoice("second-voice") }
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
            repository.settings { TtsSettings(engine = TtsOnlineSource.Azure.id, customSources = listOf(custom.copy(format = format)))
                .withOptions(TtsSynthesisOptions(format = format)) }
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
        repository.settings { settings.copy(playbackRate = 2f) }
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
        repository.settings { restored.settings.copy(engine = TtsOnlineSource.Edge.id) }
        assertFalse(TtsRepository(store).preferences.first().settings.useRubyReadings)
    }

    @Test fun legacyPlaybackDefaultsToNormalAndPersistsIndependentlyOfEngineRate() = runTest {
        val key = stringPreferencesKey("preferences")
        val store = MemoryStore(preferencesOf(key to """{"settings":{"engine":"online:google-translate","rate":0.5}}"""))
        val repository = TtsRepository(store)
        val original = repository.preferences.first().settings
        assertEquals(1f, original.playbackRate)
        repository.settings { original.copy(playbackRate = 2.5f) }
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
            repository.settings { TtsSettings(playbackRate = rate) }
            assertEquals(expected, repository.preferences.first().settings.playbackRate)
        }
    }

    @Test fun legacySettingsDefaultToFivePrefetchedSentencesAndConfiguredCountSurvivesReload() = runTest {
        val key = stringPreferencesKey("preferences")
        val store = MemoryStore(preferencesOf(key to """{"settings":{"rate":1.5}}"""))
        val repository = TtsRepository(store)
        val legacy = repository.preferences.first().settings
        assertEquals(5, legacy.prefetchSentences)
        repository.settings { legacy.copy(prefetchSentences = 6) }
        val restored = TtsRepository(store).preferences.first().settings
        assertEquals(6, restored.prefetchSentences)
        assertEquals(1.5f, restored.rate)
    }

    @Test fun savingPrefetchCountClampsToSupportedBounds() = runTest {
        val repository = TtsRepository(MemoryStore(preferencesOf()))
        for ((count, expected) in listOf(-5 to 1, 1 to 1, 6 to 6, 20 to 20, 1000 to 20)) {
            repository.settings { TtsSettings(prefetchSentences = count) }
            assertEquals(expected, repository.preferences.first().settings.prefetchSentences)
        }
    }

    @Test fun emptyReplacementRulesSurviveReload() = runTest {
        val store = MemoryStore(preferencesOf())
        val repository = TtsRepository(store)
        val rules = listOf(TtsWord("literal", "注", ""), TtsWord("regex", "[0-9]+", "", "book", regex = true))
        rules.forEach { assertNull(repository.save(it)) }
        assertEquals(rules, TtsRepository(store).preferences.first().words)
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
        repository.settings { TtsSettings().withRate(1.5f) }

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
