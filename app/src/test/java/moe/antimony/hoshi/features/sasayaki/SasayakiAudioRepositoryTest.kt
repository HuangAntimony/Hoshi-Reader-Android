package moe.antimony.hoshi.features.sasayaki

import moe.antimony.hoshi.epub.SasayakiPlaybackData

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files

class SasayakiAudioRepositoryTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun privateCopiedAudioSourceResolvesOnlyExistingFilesUnderBookAudioDirectory() {
        val bookRoot = temporaryFolder.newFolder("book")
        val repository = SasayakiAudioRepository(bookRoot)
        val audioFile = bookRoot.resolve("Sasayaki/sasayaki_audio.m4b")
        audioFile.parentFile!!.mkdirs()
        audioFile.writeText("audio")

        val source = repository.playbackSource(playback(audioFileName = "sasayaki_audio.m4b"))

        assertEquals(SasayakiPlaybackSource.PrivateFile(audioFile.canonicalFile), source)
        assertNull(repository.playbackSource(playback(audioFileName = "../outside.m4b")))
        assertNull(repository.playbackSource(playback(audioFileName = "missing.m4b")))
    }

    @Test
    fun deleteAudioRemovesOnlyResolvedPrivateAudioFile() {
        val bookRoot = temporaryFolder.newFolder("delete-book")
        val repository = SasayakiAudioRepository(bookRoot)
        val audioFile = bookRoot.resolve("Sasayaki/sasayaki_audio.mp3")
        audioFile.parentFile!!.mkdirs()
        audioFile.writeText("audio")

        assertTrue(repository.deleteAudio(playback(audioFileName = "sasayaki_audio.mp3")))

        assertFalse(audioFile.exists())
        assertFalse(repository.deleteAudio(playback(audioFileName = "../outside.mp3")))
    }

    @Test
    fun storageSummaryDescribesPrivateCopyExternalLinkAndMissingAudio() {
        val repository = SasayakiAudioRepository(temporaryFolder.newFolder("summary-book"))

        assertEquals(
            "Copied to app storage. The original audiobook file can be deleted.",
            repository.storageSummary(playback(audioFileName = "sasayaki_audio.m4b")),
        )
        assertEquals(
            "Linked to the external audiobook file. Keep the original file available.",
            repository.storageSummary(playback(audioUri = "content://audio/book.m4b")),
        )
        assertEquals(
            "Select a .mp3, .m4b, or .opus audiobook",
            repository.storageSummary(playback()),
        )
    }

    @Test
    fun inspectAudiobookReadsOpusInfoWithoutCallingPlatformReader() {
        val bookRoot = temporaryFolder.newFolder("inspect-opus-book")
        val repository = SasayakiAudioRepository(bookRoot)
        val audioFile = bookRoot.resolve("Sasayaki/sasayaki_audio.mp3")
        audioFile.parentFile!!.mkdirs()
        audioFile.writeBytes(
            minimalOggOpusWithComments(
                comments = listOf(
                    "TITLE=Fast Opus",
                    "ALBUMARTIST=Opus Artist",
                    "CHAPTER000=00:00:00.000",
                    "CHAPTER000NAME=Opening",
                ),
                preSkip = 312,
                finalGranulePosition = 480_312,
            ),
        )
        var platformReadCount = 0

        val info = repository.inspectAudiobook(
            playback = playback(audioFileName = "sasayaki_audio.mp3"),
            readPlatformInfo = {
                platformReadCount += 1
                SasayakiAudiobookPlatformInfo.Empty
            },
        )

        assertEquals(SasayakiAudiobookFormat.Opus, info.format)
        assertEquals("Fast Opus", info.metadata.title)
        assertEquals("Opus Artist", info.metadata.artist)
        assertEquals(listOf("Opening"), info.chapters.map { it.title })
        assertEquals(10.0, info.durationSeconds ?: error("Missing Opus duration"), 0.000_001)
        assertEquals(0, platformReadCount)
    }

    @Test
    fun inspectAudiobookReadsM4bMetadataChaptersAndDurationInOneContainerPass() {
        val bookRoot = temporaryFolder.newFolder("inspect-m4b-book")
        val repository = SasayakiAudioRepository(bookRoot)
        val audioFile = bookRoot.resolve("Sasayaki/sasayaki_audio.m4b")
        audioFile.parentFile!!.mkdirs()
        audioFile.writeBytes(
            minimalMp4WithInfo(
                durationSeconds = 90.0,
                chapters = listOf(
                    SasayakiChapterFixture(startSeconds = 0.0, title = "Opening"),
                    SasayakiChapterFixture(startSeconds = 45.0, title = "Ending"),
                ),
                title = "Native M4B",
                artist = "M4B Artist",
            ),
        )
        var platformReadCount = 0

        val info = repository.inspectAudiobook(
            playback = playback(audioFileName = "sasayaki_audio.m4b"),
            readPlatformInfo = {
                platformReadCount += 1
                SasayakiAudiobookPlatformInfo.Empty
            },
        )

        assertEquals(SasayakiAudiobookFormat.M4b, info.format)
        assertEquals("Native M4B", info.metadata.title)
        assertEquals("M4B Artist", info.metadata.artist)
        assertEquals(listOf("Opening", "Ending"), info.chapters.map { it.title })
        assertEquals(90.0, info.durationSeconds ?: error("Missing M4B duration"), 0.000_001)
        assertEquals(0, platformReadCount)
    }

    @Test
    fun inspectAudiobookPrefersMp4AndMp3SignaturesOverMisleadingExtensions() {
        val bookRoot = temporaryFolder.newFolder("inspect-signatures-book")
        val repository = SasayakiAudioRepository(bookRoot)
        val audioFile = bookRoot.resolve("Sasayaki/sasayaki_audio.opus")
        audioFile.parentFile!!.mkdirs()
        audioFile.writeBytes(
            minimalMp4WithInfo(
                durationSeconds = 12.0,
                chapters = emptyList(),
                title = "Disguised M4B",
            ),
        )

        val m4bInfo = repository.inspectAudiobook(playback(audioFileName = "sasayaki_audio.opus"))

        assertEquals(SasayakiAudiobookFormat.M4b, m4bInfo.format)
        assertEquals("Disguised M4B", m4bInfo.metadata.title)

        audioFile.writeBytes("ID3fixture".toByteArray())
        val mp3Info = repository.inspectAudiobook(
            playback = playback(audioFileName = "sasayaki_audio.opus"),
            readPlatformInfo = {
                SasayakiAudiobookPlatformInfo(
                    metadata = SasayakiAudiobookMetadata(title = "Disguised MP3"),
                    durationSeconds = 8.0,
                )
            },
        )

        assertEquals(SasayakiAudiobookFormat.Mp3, mp3Info.format)
        assertEquals("Disguised MP3", mp3Info.metadata.title)
    }

    @Test
    fun inspectAudiobookReadsExternalM4bThroughChannelProvider() {
        val repository = SasayakiAudioRepository(temporaryFolder.newFolder("external-info-book"))
        val externalFile = temporaryFolder.newFile("external.m4b").also { file ->
            file.writeBytes(
                minimalMp4WithInfo(
                    durationSeconds = 20.0,
                    chapters = listOf(SasayakiChapterFixture(0.0, "External Opening")),
                    title = "External M4B",
                ),
            )
        }
        var openedUri: String? = null

        val info = repository.inspectAudiobook(
            playback = playback(audioUri = "content://audio/external.m4b"),
            openExternalAudio = { uriString ->
                openedUri = uriString
                Files.newByteChannel(externalFile.toPath())
            },
        )

        assertEquals("content://audio/external.m4b", openedUri)
        assertEquals("External M4B", info.metadata.title)
        assertEquals(listOf("External Opening"), info.chapters.map { it.title })
        assertEquals(20.0, info.durationSeconds ?: error("Missing external duration"), 0.000_001)
    }

    @Test
    fun inspectAudiobookFallsBackToPlatformWhenM4bContainerHasNoMetadata() {
        val bookRoot = temporaryFolder.newFolder("m4b-platform-fallback-book")
        val repository = SasayakiAudioRepository(bookRoot)
        val audioFile = bookRoot.resolve("Sasayaki/sasayaki_audio.m4b")
        audioFile.parentFile!!.mkdirs()
        audioFile.writeBytes(
            minimalMp4WithChpl(
                durationSeconds = 40.0,
                chapters = listOf(SasayakiChapterFixture(0.0, "Opening")),
            ),
        )

        val info = repository.inspectAudiobook(
            playback = playback(audioFileName = "sasayaki_audio.m4b"),
            readPlatformInfo = {
                SasayakiAudiobookPlatformInfo(
                    metadata = SasayakiAudiobookMetadata(
                        title = " Platform M4B ",
                        author = " Platform Author ",
                    ),
                    durationSeconds = 999.0,
                )
            },
        )

        assertEquals("Platform M4B", info.metadata.title)
        assertEquals("Platform Author", info.metadata.artist)
        assertEquals(40.0, info.durationSeconds ?: error("Missing native duration"), 0.000_001)
    }

    @Test
    fun inspectAudiobookUsesPlatformInfoForMp3AndKeepsChaptersEmpty() {
        val bookRoot = temporaryFolder.newFolder("inspect-mp3-book")
        val repository = SasayakiAudioRepository(bookRoot)
        val audioFile = bookRoot.resolve("Sasayaki/sasayaki_audio.mp3")
        audioFile.parentFile!!.mkdirs()
        audioFile.writeBytes("fixture without an early MP3 signature".toByteArray())

        val info = repository.inspectAudiobook(
            playback = playback(audioFileName = "sasayaki_audio.mp3"),
            readPlatformInfo = {
                SasayakiAudiobookPlatformInfo(
                    metadata = SasayakiAudiobookMetadata(
                        title = " Platform MP3 ",
                        albumArtist = " MP3 Artist ",
                    ),
                    durationSeconds = 123.456,
                )
            },
        )

        assertEquals(SasayakiAudiobookFormat.Mp3, info.format)
        assertEquals("Platform MP3", info.metadata.title)
        assertEquals("MP3 Artist", info.metadata.artist)
        assertTrue(info.chapters.isEmpty())
        assertEquals(123.456, info.durationSeconds ?: error("Missing MP3 duration"), 0.000_001)
    }

    @Test
    fun inspectAudiobookFailsSafelyForMissingAndUnreadableSources() {
        val repository = SasayakiAudioRepository(temporaryFolder.newFolder("missing-info-book"))

        assertEquals(SasayakiAudiobookInfo.Empty, repository.inspectAudiobook(playback()))
        assertEquals(
            SasayakiAudiobookInfo(format = SasayakiAudiobookFormat.M4b),
            repository.inspectAudiobook(
                playback = playback(audioUri = "content://audio/book.m4b?token=temporary#document"),
                openExternalAudio = { error("Provider unavailable") },
                readPlatformInfo = { error("Platform reader unavailable") },
            ),
        )
    }

    private fun playback(
        audioUri: String? = null,
        audioFileName: String? = null,
    ): SasayakiPlaybackData =
        SasayakiPlaybackData(
            lastPosition = 0.0,
            audioUri = audioUri,
            audioFileName = audioFileName,
        )
}
