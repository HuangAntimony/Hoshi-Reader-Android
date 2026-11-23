package moe.antimony.hoshi.features.reader

import android.content.Intent
import android.media.MediaMetadataRetriever
import android.view.ViewGroup
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.WebChromeClient
import android.webkit.ConsoleMessage
import android.util.Log
import androidx.annotation.OptIn
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.IntSize
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlin.coroutines.resume
import kotlin.io.path.createTempDirectory
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import moe.antimony.hoshi.MainActivity
import moe.antimony.hoshi.epub.*
import moe.antimony.hoshi.features.sasayaki.*
import moe.antimony.hoshi.features.tts.*
import moe.antimony.hoshi.content.ContentLanguageProfile
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Real device synthesis and Media3 playback, with a generated book, scratch audio and isolated preferences. */
@OptIn(UnstableApi::class)
class ReaderWholeBookPlaybackDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var web: WebView
    private val sentences = listOf("朝の空を見上げます。", "午後には明るい道を歩きます。")
    private val book = EpubBook("Playback fixture", chapters = sentences.mapIndexed { index, text ->
        EpubChapter("c$index", "c$index.xhtml", "application/xhtml+xml", "<p style='min-block-size:150vh'>$text</p>")
    })
    private var documentLoads = 0

    @Test fun deviceSpeechCrossesChaptersWithAndWithoutFollowing() = exercise(audiobook = false)
    @Test fun audiobookCrossesChaptersWithAndWithoutFollowing() = exercise(audiobook = true)

    private fun exercise(audiobook: Boolean) = runBlocking {
        val directory = createTempDirectory(context.cacheDir.toPath(), "whole-book-playback-").toFile()
        try {
            val sasayakiRuntime = if (audiobook) compose.activity.uiDependencies.sasayakiPlaybackServiceRuntime else null
            openReader()
            val books = TtsBookRepository(Dispatchers.IO)
            val units = book.chapters.indices.map { books.chapter(book, it).single() }
            val engine = AndroidTtsEngine(context)
            val speech = units.map { engine.synthesize(it.text, TtsSettings(), directory).single() }
            withContext(Dispatchers.Main) {
                for (follow in listOf(false, true)) {
                    evaluate("hoshiReader.scrollToChapterStart(hoshiReader.chapterRoot(0)); hoshiReader.clearSasayakiCue()")
                    assertEquals("0", evaluate("hoshiReader.visibleChapterIndex()"))
                    if (sasayakiRuntime != null) playAudiobook(directory, units, speech, follow, sasayakiRuntime)
                    else playSpeech(books, speech, follow)
                }
                assertEquals("All playback and scrolling stayed in the first document", 1, documentLoads)
            }
        } finally {
            withContext(Dispatchers.Main) {
                if (::web.isInitialized) {
                    (web.parent as? ViewGroup)?.removeView(web)
                    web.destroy()
                }
            }
            directory.deleteRecursively()
        }
    }

    private suspend fun openReader() {
        val setup = CompletableDeferred<Unit>()
        val bridge = ReaderWebResourceBridge(book) { null }
        val assets = ReaderWebAssets.load(context)
        val activity = compose.activity
        withContext(Dispatchers.Main) {
            val view = WebView(activity)
            web = view
            view.settings.javaScriptEnabled = true
            view.webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest) =
                    (bridge.resourceForUrl(request.url.toString()) ?: ReaderWebResource.notFound()).toWebResourceResponse()

                override fun onPageFinished(view: WebView, url: String) {
                    if (url != READER_BOOK_URL) return
                    documentLoads++
                    val density = view.resources.displayMetrics.density
                    val script = readerSetupScript(
                        0.0, null, ReaderSettings(viewMode = ReaderViewMode.Continuous, verticalWriting = false),
                        null, null, false, false, ContentLanguageProfile.Default,
                        IntSize((view.width / density).toInt(), (view.height / density).toInt()),
                        0xFF000000, 0xFF0000FF, null, null, "fixture", assets,
                    )
                    view.evaluateJavascript(script) { setup.complete(Unit) }
                }
            }
            view.webChromeClient = object : WebChromeClient() {
                override fun onConsoleMessage(message: ConsoleMessage): Boolean {
                    Log.d("WholeBookPlaybackTest", message.message())
                    return true
                }
            }
            activity.setContentView(view)
            view.post { view.loadUrl(READER_BOOK_URL) }
        }
        assertNotNull("The generated EPUB document must load", withTimeoutOrNull(15_000) { setup.await() })
        withContext(Dispatchers.Main) {
            val restored = withTimeoutOrNull(8_000) {
                while (evaluate("Boolean(window.hoshiReader?.progressRestored)") != "true") delay(20)
                true
            }
            assertEquals(evaluate("[location.href, document.readyState, typeof hoshiReader, document.body.innerText]"), true, restored)
        }
    }

    private suspend fun playSpeech(books: TtsBookRepository, speech: List<File>, follow: Boolean) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val player = ExoPlayer.Builder(context).build()
        val output = AndroidTtsPlaybackOutput(context).apply { attach(player) }
        val direct = object : TtsPlaybackOutput by output { override suspend fun connect() = Unit }
        val store = object : DataStore<Preferences> {
            override val data = MutableStateFlow(emptyPreferences())
            override suspend fun updateData(transform: suspend (Preferences) -> Preferences) =
                transform(data.value).also { data.value = it }
        }
        val prefs = TtsRepository(store)
        prefs.settings { TtsSettings(enabled = true) }
        val audio = object : TtsAudioSource {
            override suspend fun audio(sentence: TtsSentence, bookId: String?, preferences: TtsPreferences) =
                listOf(speech[sentence.chapter])
            override suspend fun clearPlaybackCache() = Unit
        }
        val playback = TtsPlaybackController(books, prefs, audio, direct, scope)
        val presented = mutableSetOf<Int>()
        try {
            playback.presentSentence = { sentence, _ ->
                val cue = ReaderSasayakiCues.ttsCue(sentence)
                val mode = readerTtsPresentation(playback.state.value, sentence, sentence.chapter,
                    autoScroll = follow, restoring = false)
                val json = ReaderSasayakiCues.ttsChapterCuesJson(books.chapter(book, sentence.chapter))
                if (mode != ReaderTtsPresentation.None) {
                    present(cue, json, mode == ReaderTtsPresentation.Follow)
                    presented.add(sentence.chapter)
                }
            }
            playback.load("fixture", book, false)
            withTimeout(3_000) {
                while (!playback.state.value.started) {
                    playback.startAt(0, 0)
                    delay(20)
                }
            }
            withTimeout(20_000) {
                while (!(player.isPlaying && player.currentMediaItem?.mediaMetadata?.artist?.toString() == sentences[1] && player.currentPosition > 50 && 1 in presented)) {
                    assertNull(playback.state.value.error)
                    delay(20)
                }
            }
            assertEquals(setOf(0, 1), presented)
            assertEquals(1, playback.state.value.sentence?.chapter)
            assertLanding(follow)
        } finally {
            playback.stop()
            scope.coroutineContext[Job]?.cancelAndJoin()
            output.detach()
            player.release()
        }
    }

    private suspend fun playAudiobook(directory: File, units: List<TtsSentence>, speech: List<File>, follow: Boolean, runtime: SasayakiPlaybackServiceRuntime) {
        val root = File(directory, "audio-book").apply { mkdirs() }
        val file = File(root, "Sasayaki/fixture.wav").apply { parentFile!!.mkdirs() }
        val matches = withContext(Dispatchers.IO) {
            TtsWav.concatenate(speech, file)
            var time = 0.0
            units.mapIndexed { i, sentence ->
                val start = time
                val meta = MediaMetadataRetriever()
                try {
                    meta.setDataSource(speech[i].absolutePath)
                    time += requireNotNull(meta.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)).toDouble() / 1000.0
                } finally { meta.release() }
                SasayakiMatch("a$i", start, time, sentence.text, i, sentence.start, sentence.length)
            }
        }
        val data = SasayakiMatchData(matches, 0)
        assertNull(runtime.currentSession())
        assertNull(runtime.activePlaybackBookId())
        val changes = mutableSetOf<Int>()
        val playback = SasayakiPlayer(
            bookId = root.name,
            bookRoot = root,
            playbackRepository = BookSasayakiPlaybackRepository(root, BookRepository(directory, Dispatchers.IO)),
            bookTitle = book.title,
            bookCoverFile = null,
            matchData = data,
            initialPlayback = SasayakiPlaybackData(0.0, audioFileName = file.name),
            getCurrentChapterIndex = { null },
            onCue = { cue, reveal, _ ->
                present(SasayakiCueRange(cue.id, cue.start, cue.length, cue.chapterIndex),
                    requireNotNull(ReaderSasayakiCues.chapterCuesJson(data, cue.chapterIndex)), reveal)
                changes.add(cue.chapterIndex)
            },
            onClearCue = {},
            playbackServiceRuntime = runtime,
        )
        try {
            playback.autoScroll = follow
            playback.togglePlayback()
            withTimeout(20_000) {
                while (!(runtime.currentSession()?.player?.isPlaying == true && playback.currentTime > matches[1].startTime && 1 in changes)) {
                    assertNull(playback.errorMessage)
                    delay(20)
                }
            }
            assertEquals(setOf(0, 1), changes)
            assertLanding(follow)
        } finally {
            playback.stopPlayback()
            context.stopService(Intent(context, SasayakiPlaybackService::class.java))
            withTimeout(10_000) {
                while (runtime.currentSession() != null) delay(25)
            }
        }
    }

    private fun present(cue: SasayakiCueRange, cues: String, reveal: Boolean) {
        web.evaluateJavascript(ReaderPaginationScripts.applySasayakiCuesInvocation(cues), null)
        web.evaluateJavascript(ReaderPaginationScripts.highlightSasayakiCueInvocation(cue, reveal), null)
    }

    private suspend fun assertLanding(follow: Boolean) {
        val actual = evaluate("""
            (()=>{const active=document.querySelector('.hoshi-sasayaki-active');
            return [active?.closest('section')?.dataset.hoshiChapter,active?.textContent,
                hoshiReader.visibleChapterIndex(),document.querySelectorAll('.hoshi-sasayaki-cue').length];})()
        """.trimIndent())
        val result = org.json.JSONArray(actual)
        assertEquals("1", result.getString(0))
        assertEquals(sentences[1], result.getString(1))
        assertEquals(if (follow) 1 else 0, result.getInt(2))
        assertEquals("Only the playback chapter has cue wrappers", 1, result.getInt(3))
    }

    private suspend fun evaluate(source: String): String = suspendCancellableCoroutine { continuation ->
        web.evaluateJavascript(source) { if (continuation.isActive) continuation.resume(it) }
    }
}
