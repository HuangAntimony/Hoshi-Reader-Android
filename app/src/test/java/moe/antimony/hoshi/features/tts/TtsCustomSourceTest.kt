package moe.antimony.hoshi.features.tts

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class TtsCustomSourceTest {
    private class Store : DataStore<Preferences> {
        override val data = MutableStateFlow(emptyPreferences())
        override suspend fun updateData(transform: suspend (Preferences) -> Preferences) = transform(data.value).also { data.value = it }
    }
    private val first = TtsCustomSource("custom:one", "Irodori", baseUrl = "http://192.168.50.147:8088/v1", model = "irodori-tts", rate = 3.5f, format = TtsAudioFormat.Mp3)
    private val second = TtsCustomSource("custom:two", "VITS", TtsCustomProtocol.Vits, "http://192.168.50.147:23456", rate = 2f, format = TtsAudioFormat.Wav)

    @Test fun profilesRestoreIndependentVoiceRateAndFormatWithoutChangingPlayerSpeed() = runTest {
        val store = Store()
        val repository = TtsRepository(store)
        repository.saveSource(first); repository.settings { it.withVoice("alice") }
        repository.saveSource(second); repository.settings { it.withVoice("VITS:0") }
        var settings = repository.preferences.first().settings
        repository.settings { settings.selectSource(first.id).copy(playbackRate = 2.5f) }
        settings = repository.preferences.first().settings
        assertEquals(3.5f, settings.rate)
        assertEquals("alice", settings.voice)
        assertEquals("mp3", settings.audioExtension())
        repository.settings { settings.selectSource(second.id).withVoice("VITS:5").withRate(1.5f) }
        settings = TtsRepository(store).preferences.first().settings
        assertEquals("VITS:5", settings.voice)
        assertEquals(1.5f, settings.rate)
        assertEquals("wav", settings.audioExtension())
        repository.settings { settings.selectSource(first.id) }
        settings = repository.preferences.first().settings
        assertEquals("alice", settings.voice); assertEquals(3.5f, settings.rate); assertEquals(2.5f, settings.playbackRate)
        repository.settings { settings.selectSource(second.id) }
        assertEquals("VITS:5", repository.preferences.first().settings.voice)
    }

    @Test fun renamingAndEditingOtherProfilesDoNotChangeSynthesisIdentity() = runTest {
        val repository = TtsRepository(Store())
        repository.saveSource(first); repository.settings { it.withVoice("alice") }
        repository.saveSource(second); repository.settings { it.withVoice("VITS:0") }
        repository.settings { it.selectSource(first.id) }
        val identity = repository.preferences.first().settings.synthesisSettings()
        repository.saveSource(second.copy(baseUrl = "http://192.168.50.148:23456"))
        repository.saveSource(first.copy(name = "Renamed"))
        assertEquals(identity, repository.preferences.first().settings.synthesisSettings())
        repository.settings { it.withVoice("bob") }
        assertNotEquals(identity, repository.preferences.first().settings.synthesisSettings())
    }

    @Test fun deletingUnusedSourceKeepsSelectionAndDeletingActiveSourceReturnsToSystemDefault() = runTest {
        val repository = TtsRepository(Store())
        repository.saveSource(first); repository.settings { it.withVoice("alice") }
        repository.saveSource(second); repository.settings { it.withVoice("VITS:0") }
        repository.settings { it.selectSource(first.id) }
        repository.deleteSource(second.id)
        assertEquals(first.id, repository.preferences.first().settings.engine)
        repository.deleteSource(first.id)
        assertEquals("", repository.preferences.first().settings.engine)
        assertEquals(3f, repository.preferences.first().settings.rate)
        assertTrue(repository.preferences.first().settings.customSources.isEmpty())
    }

    @Test fun urlValidationSharesPrivateHttpRulesAndKeepsProxyPrefixWithoutCredentials() {
        assertEquals("http://192.168.50.147:23456/proxy/", first.copy(baseUrl = "http://192.168.50.147:23456/proxy").validate().baseUrl)
        assertEquals("https://tts.example.com/proxy/v1/", first.copy(baseUrl = "https://tts.example.com/proxy/v1").validate().baseUrl)
        for (url in listOf("http://public.example.com/v1", "http://user:password@192.168.1.1/v1", "https://tts.example.com/v1?key=secret", "file:///tts", "https://tts.example.com/v1#fragment")) {
            try { first.copy(baseUrl = url).validate(); fail("Expected rejected URL") } catch (_: TtsException) { }
        }
    }

    @Test fun configuredVoiceIdsPersistWithoutChangingTheChosenVoiceOrSynthesisIdentity() = runTest {
        val store = Store()
        val repository = TtsRepository(store)
        repository.saveSource(first)
        repository.settings { it.withVoice("alice") }
        repository.settings { it.selectSource(first.id) }
        val before = repository.preferences.first().settings.synthesisSettings()
        repository.saveSource(first.copy(voiceIds = listOf(" alice ", "bob", "bob", "")))
        val restored = TtsRepository(store).preferences.first().settings
        assertEquals(listOf("alice", "bob"), restored.customSource()!!.voiceIds)
        assertEquals("alice", restored.voice)
        assertEquals(before, restored.synthesisSettings())
    }
}
