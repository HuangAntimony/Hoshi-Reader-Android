package moe.antimony.hoshi.features.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioSettingsTest {
    @Test
    fun defaultSettingsUseYomitanSourcesAndIosPlaybackDefaults() {
        val settings = AudioSettings()

        assertEquals(AudioSettings.DefaultAudioSources, settings.audioSources)
        assertFalse(settings.enableLocalAudio)
        assertFalse(settings.enableAutoplay)
        assertEquals(AudioPlaybackMode.Interrupt, settings.playbackMode)
        assertEquals(AudioSettings.DefaultAudioSources.map { it.url }, settings.enabledAudioSourceUrls)
    }

    @Test
    fun enablingLocalAudioAddsLocalSourceAtFrontOnce() {
        val settings = AudioSettings().withLocalAudioEnabled(true)
            .withLocalAudioEnabled(true)

        assertTrue(settings.enableLocalAudio)
        assertEquals(AudioSettings.LocalAudioSource, settings.audioSources.first())
        assertEquals(1, settings.audioSources.count { it.url == AudioSettings.LocalAudioSource.url })
    }

    @Test
    fun disablingLocalAudioRemovesLocalSource() {
        val settings = AudioSettings().withLocalAudioEnabled(true)
            .withLocalAudioEnabled(false)

        assertFalse(settings.enableLocalAudio)
        assertFalse(settings.audioSources.any { it.url == AudioSettings.LocalAudioSource.url })
    }

    @Test
    fun disablingDisabledLocalAudioSourceDoesNotCreateDuplicateLocalSources() {
        val settings = AudioSettings()
            .withLocalAudioEnabled(true)
            .copy(
                audioSources = listOf(AudioSettings.LocalAudioSource.copy(isEnabled = false)) + AudioSettings.DefaultAudioSources,
            )
            .withLocalAudioEnabled(false)

        assertFalse(settings.enableLocalAudio)
        assertFalse(settings.audioSources.any { it.name == AudioSettings.LocalAudioSource.name })
        assertFalse(settings.audioSources.any { it.url == AudioSettings.LocalAudioSource.url })
    }

    @Test
    fun changingLocalSourceEnabledStateUpdatesLocalAudioSetting() {
        val settings = AudioSettings()
            .withLocalAudioEnabled(true)
            .withAudioSourceEnabled(AudioSettings.LocalAudioSource, false)

        assertFalse(settings.enableLocalAudio)
        assertFalse(settings.audioSources.any { it.url == AudioSettings.LocalAudioSource.url })
    }

    @Test
    fun addSourceIgnoresDuplicateUrlsLikeIos() {
        val settings = AudioSettings().addSource(
            AudioSource(
                name = "Default Copy",
                url = AudioSettings.DefaultAudioSources.first().url,
            ),
        )

        assertEquals(AudioSettings.DefaultAudioSources, settings.audioSources)
    }

    @Test
    fun addSourceWithAnkiconnectAndroidLocalAudioUrlKeepsExternalSource() {
        val settings = AudioSettings().addSource(
            AudioSource(
                name = "Ankiconnect Android",
                url = AudioSettings.LocalAudioUrl,
            ),
        )

        assertFalse(settings.enableLocalAudio)
        assertEquals(
            AudioSource(
                name = "Ankiconnect Android",
                url = AudioSettings.LocalAudioUrl,
            ),
            settings.audioSources.last(),
        )
    }

    @Test
    fun disablingBuiltInLocalAudioKeepsExternalAnkiconnectAndroidSource() {
        val external = AudioSource(
            name = "Ankiconnect Android",
            url = AudioSettings.LocalAudioUrl,
        )
        val settings = AudioSettings(audioSources = listOf(AudioSettings.LocalAudioSource) + AudioSettings.DefaultAudioSources + external)
            .copy(enableLocalAudio = true)
            .withLocalAudioEnabled(false)

        assertFalse(settings.enableLocalAudio)
        assertEquals(AudioSettings.DefaultAudioSources + external, settings.audioSources)
    }
}
