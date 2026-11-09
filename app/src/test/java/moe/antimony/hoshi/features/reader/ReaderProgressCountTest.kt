package moe.antimony.hoshi.features.reader

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.*
import org.junit.Test

class ReaderProgressCountTest {
    @Test fun legacyVisibilityAndExplicitNewChoiceSurviveMigration() {
        assertEquals(ReaderProgressCount.Off, readerProgressCount(null, false))
        assertEquals(ReaderProgressCount.Characters, readerProgressCount(null, true))
        assertEquals(ReaderProgressCount.Characters, readerProgressCount(null, null))
        assertEquals(ReaderProgressCount.Pages, readerProgressCount("Pages", false))
        assertEquals(ReaderProgressCount.Off, readerProgressCount("off", true))
        val migrated = migrateReaderProgressCountJson(Json.parseToJsonElement("""{"showCharacters":false,"fontSize":27}""").jsonObject)
        assertEquals("Off", migrated["progressCount"]?.jsonPrimitive?.content)
        assertFalse(migrated.containsKey("showCharacters"))
        assertEquals("27", migrated["fontSize"]?.jsonPrimitive?.content)
        val explicit = migrateReaderProgressCountJson(Json.parseToJsonElement("""{"progressCount":"Pages","showCharacters":false}""").jsonObject)
        assertEquals("Pages", explicit["progressCount"]?.jsonPrimitive?.content)
        assertEquals(explicit, migrateReaderProgressCountJson(explicit))
    }

    @Test fun pagesWaitWhilePercentAndContinuousWordCountsRemainAvailable() {
        val state = ReaderChromeState("Book", 50, 100)
        val settings = ReaderSettings(progressCount = ReaderProgressCount.Pages)
        assertEquals("… 50.00%", state.progressText(settings))
        assertEquals("10 / 20 50.00%", state.progressText(settings.copy(viewMode = ReaderViewMode.Continuous), ReaderProgressDisplay.word()))
        assertEquals("50.00%", state.progressText(settings.copy(progressCount = ReaderProgressCount.Off)))
        assertEquals("50 / 100 50.00%", state.copy(pageCountsFailed = true).progressText(settings))
        assertEquals("2 / 5 50.00%", state.copy(pageProgress = ReaderPageProgress(2, 5, 1, 3)).progressText(settings))
    }
}
