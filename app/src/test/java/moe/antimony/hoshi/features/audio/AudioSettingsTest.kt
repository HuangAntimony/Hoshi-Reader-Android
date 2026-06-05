package moe.antimony.hoshi.features.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioSettingsTest {
    @Test
    fun defaultSettingsMatchIosAudioDefaults() {
        val settings = AudioSettings()

        assertEquals(listOf(AudioSettings.DefaultAudioSource), settings.audioSources)
        assertFalse(settings.enableLocalAudio)
        assertFalse(settings.enableAutoplay)
        assertEquals(AudioPlaybackMode.Interrupt, settings.playbackMode)
        assertEquals(listOf(AudioSettings.DefaultAudioSource.url), settings.enabledAudioSourceUrls)
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
                audioSources = listOf(
                    AudioSettings.LocalAudioSource.copy(isEnabled = false),
                    AudioSettings.DefaultAudioSource,
                ),
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
                url = AudioSettings.DefaultAudioSource.url,
            ),
        )

        assertEquals(listOf(AudioSettings.DefaultAudioSource), settings.audioSources)
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
        val settings = AudioSettings(audioSources = listOf(AudioSettings.LocalAudioSource, AudioSettings.DefaultAudioSource, external))
            .copy(enableLocalAudio = true)
            .withLocalAudioEnabled(false)

        assertFalse(settings.enableLocalAudio)
        assertEquals(listOf(AudioSettings.DefaultAudioSource, external), settings.audioSources)
    }
}
