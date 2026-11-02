package moe.antimony.hoshi.features.reader

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test

class ReaderParagraphSettingsMigrationTest {
    private fun migrate(source: String) = migrateParagraphSettingsJson(Json.parseToJsonElement(source).jsonObject)

    @Test fun explicitLegacyVnModeWinsOverStaleContinuousFlag() {
        assertEquals(ReaderViewMode.Paginated, migrateReaderViewMode("visualNovel", legacyContinuousMode = true))
        assertEquals(ReaderViewMode.Continuous, migrateReaderViewMode(null, legacyContinuousMode = true))
    }

    @Test fun sharedPreferencesSnapshotRetainsExplicitClickValuesAndNewPriority() {
        for (click in listOf(false, true)) {
            val source = mapOf("readerViewMode" to "visualNovel", "visualNovelClickAdvance" to click)
            val migrated = paragraphSettingsFromPreferences(source)
            assertTrue(migrated.paragraphMode)
            assertTrue(migrated.textAnimation)
            assertEquals(45, migrated.textSpeed)
            assertEquals(click, migrated.paragraphClickAdvance)
            assertEquals(!click, paragraphSettingsFromPreferences(source + ("paragraphClickAdvance" to !click)).paragraphClickAdvance)
        }
    }

    @Test fun legacyVnSettingsBecomeParagraphSettingsWithoutLosingOtherFields() {
        val result = migrate("""{"viewMode":"VisualNovel","theme":"Dark","visualNovelScreenMode":"Sentences","visualNovelSentencesPerScreen":12,"visualNovelPreserveDialogueBubbles":true,"visualNovelRevealSpeed":120,"visualNovelClickAdvance":true,"visualNovelMergeCrossScreenSasayakiCues":true}""")
        assertEquals("Paginated", result["viewMode"]?.jsonPrimitive?.content)
        assertEquals("true", result["paragraphMode"]?.jsonPrimitive?.content)
        assertEquals("10", result["maxSentencesPerPage"]?.jsonPrimitive?.content)
        assertEquals("false", result["splitDialogue"]?.jsonPrimitive?.content)
        assertEquals("true", result["textAnimation"]?.jsonPrimitive?.content)
        assertEquals("100", result["textSpeed"]?.jsonPrimitive?.content)
        assertEquals("true", result["paragraphClickAdvance"]?.jsonPrimitive?.content)
        assertEquals("Dark", result["theme"]?.jsonPrimitive?.content)
        assertFalse(result.keys.any { it.startsWith("visualNovel") })
        assertEquals(result, migrateParagraphSettingsJson(result))
    }

    @Test fun newExplicitFalseValuesWinAndInstantAnimationGetsDefaultSpeed() {
        val result = migrate("""{"viewMode":"visualNovel","paragraphMode":false,"visualNovelScreenMode":"block","visualNovelRevealSpeed":0,"visualNovelClickAdvance":true,"paragraphClickAdvance":false,"splitDialogue":false}""")
        assertEquals("false", result["paragraphMode"]?.jsonPrimitive?.content)
        assertEquals("false", result["paragraphClickAdvance"]?.jsonPrimitive?.content)
        assertEquals("false", result["splitDialogue"]?.jsonPrimitive?.content)
        assertEquals("false", result["textAnimation"]?.jsonPrimitive?.content)
        assertEquals("35", result["textSpeed"]?.jsonPrimitive?.content)
        assertEquals("0", result["maxSentencesPerPage"]?.jsonPrimitive?.content)
    }

    @Test fun ordinaryAndContinuousModesAndNewDefaultsRemainUnchanged() {
        assertEquals(Json.parseToJsonElement("{}").jsonObject, migrate("{}"))
        assertEquals("Continuous", migrate("""{"viewMode":"Continuous"}""")["viewMode"]?.jsonPrimitive?.content)
        val result = migrate("""{"viewMode":"Paginated","visualNovelPreserveDialogueBubbles":false,"visualNovelClickAdvance":false,"visualNovelRevealSpeed":12}""")
        assertEquals("false", result["paragraphMode"]?.jsonPrimitive?.content)
        assertEquals("true", result["splitDialogue"]?.jsonPrimitive?.content)
        assertEquals("false", result["paragraphClickAdvance"]?.jsonPrimitive?.content)
        assertEquals("25", result["textSpeed"]?.jsonPrimitive?.content)
    }
}
