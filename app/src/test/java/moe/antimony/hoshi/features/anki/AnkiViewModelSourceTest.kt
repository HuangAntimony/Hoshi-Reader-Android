package moe.antimony.hoshi.features.anki

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class AnkiViewModelSourceTest {
    @Test
    fun configuredSettingsWithoutCachedFieldsTriggerOneRestoreFetch() {
        val source = File("src/main/java/moe/antimony/hoshi/features/anki/AnkiViewModel.kt").readText()

        assertTrue(source.contains("private var attemptedRestoreFetch = false"))
        assertTrue(source.contains("settings.availableDecks.isEmpty() || settings.availableNoteTypes.isEmpty()"))
        assertTrue(source.contains("attemptedRestoreFetch = true"))
        assertTrue(source.contains("fetchConfiguration()"))
    }
}
