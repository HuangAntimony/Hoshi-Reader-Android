package moe.antimony.hoshi.features.anki

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class AnkiSettingsMigrationTest {
    @Test
    fun cardFormatCrudCapsAtThreeAndProtectsLastFormat() {
        val initial = AnkiSettings(
            cardFormats = listOf(AnkiCardFormat(id = "one", name = "Default")),
        )

        val three = initial
            .addCardFormat(AnkiCardFormat(id = "two", name = "Format 2"))
            .addCardFormat(AnkiCardFormat(id = "three", name = "Format 3"))
            .addCardFormat(AnkiCardFormat(id = "four", name = "Format 4"))
        assertEquals(listOf("one", "two", "three"), three.cardFormats.map(AnkiCardFormat::id))

        val renamed = three.updateCardFormat("two") { it.copy(name = "Listening") }
        assertEquals("Listening", renamed.cardFormats[1].name)
        assertEquals(three, three.updateCardFormat("missing") { it.copy(name = "Wrong") })

        val one = renamed.removeCardFormat("two").removeCardFormat("three")
        assertEquals(listOf("one"), one.cardFormats.map(AnkiCardFormat::id))
        assertEquals(one, one.removeCardFormat("one"))
    }

    @Test
    fun legacySingleFormatSettingsMigrateIntoOneStableDefaultFormat() {
        val migrated = decodeAnkiSettings(
            raw = """
                {
                  "selectedDeckId": 3,
                  "selectedDeckName": "Mining",
                  "selectedNoteTypeId": 7,
                  "selectedNoteTypeName": "Lapis",
                  "fieldMappings": {"Expression":"{expression}"},
                  "tags": "hoshi reader",
                  "allowDupes": true
                }
            """.trimIndent(),
            newFormatId = { "legacy-format-id" },
        )

        assertTrue(migrated.didMigrate)
        assertEquals(AnkiSettingsSchemaVersion, migrated.settings.schemaVersion)
        assertEquals(1, migrated.settings.cardFormats.size)
        assertEquals(
            AnkiCardFormat(
                id = "legacy-format-id",
                name = "Default",
                icon = AnkiFormatIcon.Square,
                selectedDeckId = 3L,
                selectedDeckName = "Mining",
                selectedNoteTypeId = 7L,
                selectedNoteTypeName = "Lapis",
                fieldMappings = mapOf("Expression" to "{expression}"),
                tags = "hoshi reader",
            ),
            migrated.settings.cardFormats.single(),
        )
        assertTrue(migrated.settings.allowDupes)
    }

    @Test
    fun versionTwoSettingsKeepFormatIdsWithoutMigration() {
        val decoded = decodeAnkiSettings(
            raw = """
                {
                  "schemaVersion": 2,
                  "cardFormats": [{
                    "id": "saved-format-id",
                    "name": "Reading",
                    "icon": "plus.circle.small",
                    "selectedDeckId": 3,
                    "selectedDeckName": "Mining",
                    "selectedNoteTypeId": 7,
                    "selectedNoteTypeName": "Lapis",
                    "fieldMappings": {"Expression":"{expression}"},
                    "tags": ""
                  }]
                }
            """.trimIndent(),
            newFormatId = { error("v2 settings must not allocate a new id") },
        )

        assertFalse(decoded.didMigrate)
        assertEquals("saved-format-id", decoded.settings.cardFormats.single().id)
        assertEquals(AnkiFormatIcon.CircleSmall, decoded.settings.cardFormats.single().icon)
        assertTrue(Json.encodeToString(decoded.settings).contains("plus.circle.small"))
    }

    @Test
    fun emptyVersionTwoFormatListIsRepaired() {
        val decoded = decodeAnkiSettings(
            raw = """{"schemaVersion":2,"cardFormats":[]}""",
            newFormatId = { "replacement-format-id" },
        )

        assertTrue(decoded.didMigrate)
        assertEquals("replacement-format-id", decoded.settings.cardFormats.single().id)
        assertEquals("Default", decoded.settings.cardFormats.single().name)
    }

    @Test
    fun unknownFormatIconFallsBackWithoutDiscardingTheFormat() {
        val decoded = decodeAnkiSettings(
            raw = """{"schemaVersion":2,"cardFormats":[{"id":"kept","name":"Custom","icon":"FutureIcon"}]}""",
            newFormatId = { error("valid v2 format must be kept") },
        )

        assertFalse(decoded.didMigrate)
        assertEquals("kept", decoded.settings.cardFormats.single().id)
        assertEquals(AnkiFormatIcon.Square, decoded.settings.cardFormats.single().icon)
    }

    @Test
    fun damagedSettingsFallBackToOneUsableFormat() {
        val decoded = decodeAnkiSettings("not-json", newFormatId = { "recovered" })

        assertTrue(decoded.didMigrate)
        assertEquals(listOf("recovered"), decoded.settings.cardFormats.map(AnkiCardFormat::id))
    }

    @Test
    fun versionTwoFormatListIsCappedAndInvalidIdsAreRepaired() {
        var next = 0
        val decoded = decodeAnkiSettings(
            raw = """{"schemaVersion":2,"cardFormats":[{"id":"same","name":"One"},{"id":"same","name":"Two"},{"id":"","name":"Three"},{"id":"four","name":"Four"}]}""",
            newFormatId = { "replacement-${++next}" },
        )

        assertTrue(decoded.didMigrate)
        assertEquals(listOf("same", "replacement-1", "replacement-2"), decoded.settings.cardFormats.map(AnkiCardFormat::id))
    }
}
