package moe.antimony.hoshi.features.sasayaki

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SasayakiAudiobookChaptersTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun parsesNeroChplChaptersFromM4b() {
        val file = temporaryFolder.newFile("book.m4b")
        file.writeBytes(
            minimalMp4WithChpl(
                durationSeconds = 90.0,
                chapters = listOf(
                    SasayakiChapterFixture(startSeconds = 0.0, title = "Prologue"),
                    SasayakiChapterFixture(startSeconds = 12.5, title = "Chapter 1"),
                    SasayakiChapterFixture(startSeconds = 70.0, title = "Chapter 2"),
                ),
            ),
        )

        val chapters = SasayakiAudiobookChapters.parse(file)

        assertEquals(3, chapters.size)
        assertEquals(SasayakiAudiobookChapter(index = 0, title = "Prologue", startSeconds = 0.0, endSeconds = 12.5), chapters[0])
        assertEquals(SasayakiAudiobookChapter(index = 1, title = "Chapter 1", startSeconds = 12.5, endSeconds = 70.0), chapters[1])
        assertEquals(SasayakiAudiobookChapter(index = 2, title = "Chapter 2", startSeconds = 70.0, endSeconds = 90.0), chapters[2])
    }

    @Test
    fun findsCurrentChapterForPlaybackPosition() {
        val chapters = listOf(
            SasayakiAudiobookChapter(index = 0, title = "Prologue", startSeconds = 0.0, endSeconds = 12.5),
            SasayakiAudiobookChapter(index = 1, title = "Chapter 1", startSeconds = 12.5, endSeconds = 70.0),
            SasayakiAudiobookChapter(index = 2, title = "Chapter 2", startSeconds = 70.0, endSeconds = 90.0),
        )

        assertEquals("Prologue", SasayakiAudiobookChapters.currentChapterAt(chapters, 12.499)?.title)
        assertEquals("Chapter 1", SasayakiAudiobookChapters.currentChapterAt(chapters, 12.5)?.title)
        assertEquals("Chapter 2", SasayakiAudiobookChapters.currentChapterAt(chapters, 89.999)?.title)
        assertEquals("Chapter 2", SasayakiAudiobookChapters.currentChapterAt(chapters, 90.0)?.title)
        assertNull(SasayakiAudiobookChapters.currentChapterAt(chapters, 90.001))
        assertNull(SasayakiAudiobookChapters.currentChapterAt(chapters, -1.0))
    }

    @Test
    fun invalidFileReturnsEmptyChapterList() {
        val file = temporaryFolder.newFile("invalid.m4b")
        file.writeText("not an mp4")

        assertEquals(emptyList<SasayakiAudiobookChapter>(), SasayakiAudiobookChapters.parse(file))
    }

}
