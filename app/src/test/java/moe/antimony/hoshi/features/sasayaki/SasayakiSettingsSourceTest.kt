package moe.antimony.hoshi.features.sasayaki

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class SasayakiSettingsSourceTest {
    @Test
    fun settingsExposePrivateAudiobookCopyToggle() {
        val model = File("src/main/java/moe/antimony/hoshi/features/sasayaki/SasayakiSettings.kt").readText()
        val view = File("src/main/java/moe/antimony/hoshi/features/sasayaki/SasayakiSettingsView.kt").readText()

        assertTrue(model.contains("copyAudiobookToPrivateStorage: Boolean = false"))
        assertTrue(model.contains("KEY_COPY_AUDIOBOOK_TO_PRIVATE_STORAGE"))
        assertTrue(model.contains("settings.copyAudiobookToPrivateStorage"))
        assertTrue(view.contains("Copy Audiobook to App Storage"))
        assertTrue(view.contains("settings.copy(copyAudiobookToPrivateStorage = it)"))
    }
}
