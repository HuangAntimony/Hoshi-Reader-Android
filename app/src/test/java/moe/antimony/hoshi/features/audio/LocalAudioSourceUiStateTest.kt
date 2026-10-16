package moe.antimony.hoshi.features.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import moe.antimony.hoshi.R
import moe.antimony.hoshi.ui.UiText

class LocalAudioSourceUiStateTest {
    @Test
    fun failedSaveKeepsPreviousConfigAndShowsError() {
        val config = LocalAudioSourceConfig(
            sourceOrder = listOf("nhk16", "forvo"),
            disabledSources = setOf("forvo"),
        )
        val state = LocalAudioSourceUiState(config = config)

        val updated = state.withSaveResult(Result.failure(IllegalStateException("disk full")))

        assertEquals(config, updated.config)
        assertTrue(updated.saveFailed)
        assertEquals(UiText.Joined(listOf(UiText.Resource(R.string.audio_local_source_save_failed), UiText.Literal("disk full"))), updated.errorMessage)
    }

    @Test
    fun successfulSaveReplacesConfigAndClearsError() {
        val oldConfig = LocalAudioSourceConfig(sourceOrder = listOf("nhk16", "forvo"))
        val newConfig = oldConfig.copy(disabledSources = setOf("forvo"))
        val state = LocalAudioSourceUiState(config = oldConfig, saveFailed = true)

        val updated = state.withSaveResult(Result.success(newConfig))

        assertEquals(newConfig, updated.config)
        assertFalse(updated.saveFailed)
        assertEquals(null, updated.errorMessage)
    }
}
