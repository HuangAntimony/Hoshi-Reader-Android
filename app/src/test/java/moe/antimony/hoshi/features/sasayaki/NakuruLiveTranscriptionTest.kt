package moe.antimony.hoshi.features.sasayaki

import java.io.File
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import moe.antimony.hoshi.epub.BookRepository
import moe.antimony.hoshi.epub.BookWorkRegistry
import moe.antimony.hoshi.epub.EpubBookParser
import moe.antimony.hoshi.epub.writeMinimalExtractedEpub
import moe.antimony.hoshi.features.sasayaki.transcription.SasayakiTranscriptionBackend
import moe.antimony.hoshi.features.sasayaki.transcription.SasayakiTranscriptionBatch
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class NakuruLiveTranscriptionTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun actualNakuruMatchesAreSavedAndPublishedWhileBatchesContinue() = runTest {
        val root = temporary.newFolder()
        writeMinimalExtractedEpub(root,
            firstChapterHtml = "<body><p>今日は、いい天気です。明日も晴れるでしょう。</p></body>",
            secondChapterHtml = "<body><p>新しい章が始まりました。</p></body>",
        )
        val dispatcher = StandardTestDispatcher(testScheduler)
        val books = BookRepository(temporary.newFolder(), dispatcher)
        val store = SasayakiTranscriptStore(dispatcher)
        val parser = EpubBookParser(temporary.newFolder())
        val repository = AndroidSasayakiTranscriptionRepository(store, books, parser, dispatcher, dispatcher)
        val batches = Channel<SasayakiTranscriptionBatch>(Channel.UNLIMITED)
        val backend = object : SasayakiTranscriptionBackend {
            override suspend fun duration(source: String) = 20.0
            override suspend fun transcribe(source: String, from: Double,
                onDownloadRequired: suspend (Long) -> Unit, onDownload: suspend (Double) -> Unit,
                onBatch: suspend (SasayakiTranscriptionBatch) -> Unit, parallelism: Int,
            ) { for (batch in batches) onBatch(batch) }
        }
        val coordinator = SasayakiTranscriptionCoordinator(backend, repository, BookWorkRegistry(), backgroundScope,
            dispatcher, SasayakiTranscriptionClock { testScheduler.currentTime * 1_000_000 })
        coordinator.start(root, "audio")
        val first = listOf(SasayakiToken("今日は", 1.0, 2.0), SasayakiToken("いい天気です", 2.0, 4.0))
        batches.send(SasayakiTranscriptionBatch(first, 4.0))
        runCurrent()
        assertEquals(SasayakiTranscriptionStage.Transcribing, coordinator.state.value.stage)
        assertEquals(listOf("今日は", "いい天気です"), coordinator.state.value.match!!.matches.map { it.text })
        assertEquals(coordinator.state.value.match, books.loadSasayakiMatch(root))
        val revision = coordinator.state.value.revision
        val next = listOf(SasayakiToken("明日も晴れるでしょう", 5.0, 8.0))
        batches.send(SasayakiTranscriptionBatch(next, 8.0))
        batches.send(SasayakiTranscriptionBatch(emptyList(), 9.0))
        runCurrent()
        assertEquals(9.0, coordinator.state.value.through, 0.0)
        advanceTimeBy(14_999); runCurrent()
        assertEquals(revision, coordinator.state.value.revision)
        advanceTimeBy(1); runCurrent()
        assertEquals(listOf("今日は", "いい天気です", "明日も晴れるでしょう"), coordinator.state.value.match!!.matches.map { it.text })
        assertEquals(coordinator.state.value.match, books.loadSasayakiMatch(root))
        val last = listOf(SasayakiToken("新しい章が始まりました", 10.0, 14.0))
        batches.send(SasayakiTranscriptionBatch(last, 14.0)); runCurrent()
        coordinator.pause(root); runCurrent()
        assertEquals(listOf(0, 0, 0, 1), coordinator.state.value.match!!.matches.map { it.chapterIndex })
        assertEquals(14.0, store.load(root)!!.through, 0.0)
        assertEquals(first + next + last, store.load(root)!!.tokens)
        assertEquals(coordinator.state.value.match, books.loadSasayakiMatch(root))
        assertFalse(coordinator.state.value.running)
        batches.close()
        coordinator.start(root, "audio"); runCurrent()
        val expected = NakuruTranscriptAligner(parser.parse(root)).align(first + next + last)
        assertEquals(expected, coordinator.state.value.match)
        assertTrue(store.load(root)!!.isComplete)
        assertFalse(coordinator.state.value.running)
    }
}
