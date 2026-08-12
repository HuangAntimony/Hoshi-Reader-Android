package moe.antimony.hoshi.features.sasayaki

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SasayakiAudiobookOpusMetadataTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun parsesMetadataAndChaptersFromOpusTagsSplitAcrossOggPages() {
        val file = temporaryFolder.newFile("book.opus")
        file.writeBytes(
            minimalOggOpusWithComments(
                comments = listOf(
                    "TITLE=Recorded Book",
                    "ARTIST=Book Author",
                    "ALBUMARTIST=Series Artist",
                    "AUTHOR=Written Author",
                    "COMPOSER=Narrator That Must Not Be Displayed",
                    "CHAPTER001=00:00:12.500",
                    "CHAPTER001NAME=Chapter 1",
                    "CHAPTER000=00:00:00.000",
                    "CHAPTER000NAME=Prologue",
                    "CHAPTER002=01:02:03.045",
                    "CHAPTER002NAME=Chapter 2",
                ),
                splitTagsAcrossPages = true,
            ),
        )

        val info = SasayakiAudiobookOpusMetadata.parse(file)

        assertNotNull(info)
        requireNotNull(info)
        assertEquals("Recorded Book", info.metadata.title)
        assertEquals("Book Author", info.metadata.artist)
        assertEquals("Series Artist", info.metadata.albumArtist)
        assertEquals("Written Author", info.metadata.author)
        assertEquals(
            listOf(
                SasayakiAudiobookChapter(index = 0, title = "Prologue", startSeconds = 0.0, endSeconds = 12.5),
                SasayakiAudiobookChapter(index = 1, title = "Chapter 1", startSeconds = 12.5, endSeconds = 3723.045),
                SasayakiAudiobookChapter(index = 2, title = "Chapter 2", startSeconds = 3723.045, endSeconds = null),
            ),
            info.chapters,
        )
    }

    @Test
    fun composerDoesNotBecomeDisplayedArtist() {
        val file = temporaryFolder.newFile("composer-only.opus")
        file.writeBytes(minimalOggOpusWithComments(listOf("COMPOSER=Narrator")))

        val metadata = requireNotNull(SasayakiAudiobookOpusMetadata.parse(file)).metadata.normalized()

        assertNull(metadata.artist)
    }

    @Test
    fun extractsFrontCoverFromFlacPictureComment() {
        val fallback = byteArrayOf(1, 2)
        val frontCover = byteArrayOf(0xff.toByte(), 0xd8.toByte(), 3, 4)
        val file = temporaryFolder.newFile("cover.opus")
        file.writeBytes(
            minimalOggOpusWithComments(
                listOf(
                    flacPictureComment(fallback, pictureType = 0),
                    flacPictureComment(frontCover, pictureType = 3),
                ),
            ),
        )

        val info = requireNotNull(SasayakiAudiobookOpusMetadata.parse(file))

        assertArrayEquals(frontCover, info.metadata.artworkData)
    }

    @Test
    fun supportsLegacyCoverArtAndAuthorFallback() {
        val cover = byteArrayOf(5, 6, 7)
        val encodedCover = java.util.Base64.getEncoder().encodeToString(cover)
        val file = temporaryFolder.newFile("legacy-cover.opus")
        file.writeBytes(
            minimalOggOpusWithComments(
                listOf(
                    "AUTHOR=Fallback Author",
                    "COVERART=$encodedCover",
                ),
            ),
        )

        val metadata = requireNotNull(SasayakiAudiobookOpusMetadata.parse(file)).metadata.normalized()

        assertEquals("Fallback Author", metadata.artist)
        assertArrayEquals(cover, metadata.artworkData)
    }

    @Test
    fun parsesTwentyTwoGeneratedChapters() {
        val comments = buildList {
            repeat(22) { index ->
                val id = index.toString().padStart(3, '0')
                add("CHAPTER$id=00:${index.toString().padStart(2, '0')}:00.000")
                add("CHAPTER${id}NAME=Chapter $index")
            }
        }
        val file = temporaryFolder.newFile("twenty-two-chapters.opus")
        file.writeBytes(minimalOggOpusWithComments(comments))

        val chapters = requireNotNull(SasayakiAudiobookOpusMetadata.parse(file)).chapters

        assertEquals(22, chapters.size)
        assertEquals("Chapter 21", chapters.last().title)
    }

    @Test
    fun acceptsMultichannelOpusHeadWithChannelMappingTable() {
        val file = temporaryFolder.newFile("mapped.opus")
        file.writeBytes(
            minimalOggOpusWithComments(
                comments = listOf("TITLE=Mapped Opus"),
                channelMappingFamily = 1,
            ),
        )

        val info = requireNotNull(SasayakiAudiobookOpusMetadata.parse(file))

        assertEquals("Mapped Opus", info.metadata.title)
    }

    @Test
    fun nonOggOpusInputIsNotRecognized() {
        val file = temporaryFolder.newFile("not-opus.bin")
        file.writeText("not an Ogg Opus stream")

        assertNull(SasayakiAudiobookOpusMetadata.parse(file))
    }

    @Test
    fun corruptOggPageFailsSafelyAfterRecognizingOpusStream() {
        val file = temporaryFolder.newFile("corrupt.opus")
        val bytes = minimalOggOpusWithComments(listOf("TITLE=Hidden Corrupt Title"))
        bytes[bytes.lastIndex] = (bytes.last().toInt() xor 1).toByte()
        file.writeBytes(bytes)

        val info = SasayakiAudiobookOpusMetadata.parse(file)

        assertNotNull(info)
        assertEquals(SasayakiAudiobookMetadata.Empty, info?.metadata)
        assertTrue(info?.chapters.orEmpty().isEmpty())
    }

    @Test
    fun invalidContinuationDoesNotSpliceOpusTagsPackets() {
        val file = temporaryFolder.newFile("invalid-continuation.opus")
        file.writeBytes(
            minimalOggOpusWithComments(
                comments = listOf("TITLE=Must Not Be Parsed"),
                splitTagsAcrossPages = true,
                omitContinuationFlag = true,
            ),
        )

        val info = requireNotNull(SasayakiAudiobookOpusMetadata.parse(file))

        assertEquals(SasayakiAudiobookMetadata.Empty, info.metadata)
    }

    @Test
    fun calculatesDurationFromFinalGranulePositionAfterPreSkip() {
        val file = temporaryFolder.newFile("duration.opus")
        file.writeBytes(
            minimalOggOpusWithComments(
                comments = listOf("TITLE=Duration Test"),
                preSkip = 312,
                finalGranulePosition = 480_312,
            ),
        )

        val info = requireNotNull(SasayakiAudiobookOpusMetadata.parse(file))

        assertEquals(10.0, info.durationSeconds ?: error("Missing Opus duration"), 0.000_001)
    }

    @Test
    fun tailScanResynchronizesAfterFalseOggCapturePattern() {
        val file = temporaryFolder.newFile("false-tail-capture.opus")
        file.writeBytes(
            minimalOggOpusWithComments(
                comments = listOf("TITLE=Tail Resync"),
                finalGranulePosition = 480_000,
                durationTailPrefix = oggTailPrefixWithFalseCapturePattern(),
                finalPagePayloadSize = 255 * 255,
            ),
        )

        val info = requireNotNull(SasayakiAudiobookOpusMetadata.parse(file))

        assertEquals(10.0, info.durationSeconds ?: error("Missing resynchronized duration"), 0.000_001)
    }
}
