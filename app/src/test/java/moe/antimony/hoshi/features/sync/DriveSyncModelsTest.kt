package moe.antimony.hoshi.features.sync

import moe.antimony.hoshi.epub.BookInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DriveSyncModelsTest {
    @Test
    fun sanitizeTtuFilenameMatchesIosEscapingRules() {
        assertEquals(
            "Book%2FName~ttu-star~%3F%3C%3E%5C%3A%7C%25%22",
            sanitizeTtuFilename("Book/Name*?<>\\:|%\""),
        )
        assertEquals("Trailing~ttu-spc~", sanitizeTtuFilename("Trailing "))
        assertEquals("Dot~ttu-dend~", sanitizeTtuFilename("Dot."))
    }

    @Test
    fun parseProgressTimestampMillisReadsTtuProgressFileName() {
        assertEquals(
            1_762_000_123_456L,
            parseProgressTimestampMillis(
                DriveFile(
                    id = "progress-id",
                    name = "progress_1_6_1762000123456_0.42.json",
                ),
            ),
        )
        assertNull(parseProgressTimestampMillis(DriveFile(id = "other", name = "cover_1_6.jpeg")))
        assertNull(parseProgressTimestampMillis(DriveFile(id = "bad", name = "progress_1_6_nope_0.42.json")))
    }

    @Test
    fun appleReferenceSecondsConvertToUnixMillisecondsAndBack() {
        assertEquals(1_762_000_123_456L, appleReferenceSecondsToUnixMillis(783_692_923.456))
        assertEquals(783_692_923.456, unixMillisToAppleReferenceSeconds(1_762_000_123_456L), 0.0001)
    }

    @Test
    fun bookInfoResolvesCharacterPositionToChapterProgress() {
        val bookInfo = BookInfo(
            characterCount = 300,
            chapterInfo = mapOf(
                "0" to BookInfo.ChapterInfo(spineIndex = 0, currentTotal = 0, chapterCount = 100),
                "1" to BookInfo.ChapterInfo(spineIndex = 1, currentTotal = 100, chapterCount = 200),
            ),
        )

        assertEquals(
            ResolvedBookPosition(chapterIndex = 1, progress = 0.25),
            bookInfo.resolveCharacterPosition(150),
        )
        assertEquals(
            ResolvedBookPosition(chapterIndex = 0, progress = 0.0),
            bookInfo.resolveCharacterPosition(-20),
        )
        assertEquals(
            ResolvedBookPosition(chapterIndex = 1, progress = 1.0),
            bookInfo.resolveCharacterPosition(999),
        )
    }
}
