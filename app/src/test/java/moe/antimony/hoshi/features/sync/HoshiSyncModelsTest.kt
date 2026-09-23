package moe.antimony.hoshi.features.sync

import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class HoshiSyncModelsTest {
    private fun book(): SyncBook = SyncFormat.decode(javaClass.getResource("/sync/book.json")!!.readText())

    @Test fun iosBookRoundTripsWithoutChangingMeaning() {
        val original = Json.parseToJsonElement(javaClass.getResource("/sync/book.json")!!.readText())
        assertEquals(original, Json.parseToJsonElement(SyncFormat.encode(book())))
    }

    @Test fun equalStampsKeepFirstAndDoNotRequireUpload() {
        val first = book()
        val second = first.copy(metadata = first.metadata.replacing(SyncMetadata("different")))
        assertEquals(first, SyncBook.merge(first, second))
        assertFalse(second.needsUpload(first))
    }

    @Test fun newerPositionCanMoveBackwardAndMembershipsMergePerKey() {
        val first = book()
        val second = first.copy(
            bookmark = Timestamped(first.bookmark!!.modified + 1, SyncBookmark(1)),
            shelves = mapOf("別" to Timestamped(1, true)),
            characterCount = 1,
        )
        val merged = SyncBook.merge(first, second)
        assertEquals(1, merged.bookmark!!.value.characterCount)
        assertEquals(first.characterCount, merged.characterCount)
        assertEquals(setOf("お気に入り", "別"), merged.shelves.keys)
        assertEquals(merged, SyncBook.merge(second, first))
        assertEquals(merged, SyncBook.merge(merged, second))
    }

    @Test fun deletionWinsAndKeepsOnlyCoverMetadataAndSessions() {
        val first = book()
        val merged = SyncBook.merge(first, first.delete())
        assertTrue(merged.deleted)
        assertEquals(setOf(SyncFileType.cover), merged.files.keys)
        assertNull(merged.bookmark)
        assertNull(merged.audiobook)
        assertTrue(merged.highlights.isEmpty())
        assertTrue(merged.shelves.isEmpty())
        assertEquals(first.sessions, merged.sessions)
    }

    @Test fun sessionsSurviveGenerationReplacementAndDeletionBeatsLaterEdits() {
        val first = book()
        val id = first.sessions.keys.single()
        val second = first.copy(generation = 2, highlights = emptyMap(), sessions = mapOf(id to Timestamped(1, null)))
        val merged = SyncBook.merge(first, second)
        assertEquals(2, merged.generation)
        assertEquals(emptyMap<String, Timestamped<SyncHighlight?>>(), merged.highlights)
        assertNull(merged.sessions.getValue(id).value)
        assertEquals(merged, SyncBook.merge(second, first))
    }

    @Test fun mergeRecordsUnionsIdsAndKeepsNewestOfTwoDeletions() {
        val first = mapOf("deleted" to Timestamped<String?>(10, null), "live" to Timestamped<String?>(10, "a"))
        val second = mapOf("deleted" to Timestamped<String?>(20, null), "live" to Timestamped<String?>(20, "b"), "new" to Timestamped<String?>(1, "c"))
        assertEquals(second, SyncBook.mergeRecords(first, second))
        assertEquals(first + ("new" to second.getValue("new")), SyncBook.mergeRecords(first, second.mapValues { (_, v) -> v.copy(modified = 1) }))
    }

    @Test fun shelvesCanBeRecreatedAndUnknownOrdersArePreserved() {
        val remote: SyncShelves = SyncFormat.decode(javaClass.getResource("/sync/shelves.json")!!.readText())
        val local = SyncShelves(mapOf("Old" to Timestamped(1789430500001, 2)))
        val merged = SyncShelves.merge(remote, local)
        assertEquals(2, merged.shelves.getValue("Old").value)
        assertEquals(remote.orders, merged.orders)
        assertEquals(merged, SyncFormat.decode<SyncShelves>(SyncFormat.encode(merged)))
    }

    @Test fun missingAndUnsupportedVersionsAreRejected() {
        for (value in listOf("{}", "{\"formatVersion\":2}", "{\"formatVersion\":\"1\"}")) {
            assertThrows(SyncFormatError::class.java) { SyncFormat.decode<SyncBook>(value) }
        }
    }

    @Test fun highlightNativeDateUsesAppleEpochAndRemoteDateUsesMilliseconds() {
        val remote = book().highlights.values.single().value!!
        assertEquals(remote, SyncHighlight(remote.highlight("80CC1266-83A1-4A41-89E5-76EF980BDB66")))
        assertEquals(978307200000L, 0.0.appleDateMilliseconds())
        assertEquals("で", "て\u3099".syncKey())
    }
}
