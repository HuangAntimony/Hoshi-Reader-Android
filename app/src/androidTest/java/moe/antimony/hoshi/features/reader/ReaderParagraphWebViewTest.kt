package moe.antimony.hoshi.features.reader

import android.graphics.Bitmap
import android.graphics.Color
import android.view.ViewGroup
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.viewinterop.AndroidView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import moe.antimony.hoshi.epub.SasayakiMatch
import moe.antimony.hoshi.epub.SasayakiMatchData
import moe.antimony.hoshi.features.tts.TtsText

/** Isolated generated documents; never opens a library or writes user settings. */
@RunWith(AndroidJUnit4::class)
class ReaderParagraphWebViewTest {
    @get:Rule val composeRule = createComposeRule()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private lateinit var webView: WebView

    private fun createView() {
        composeRule.setContent {
            AndroidView(modifier = Modifier.fillMaxSize(), factory = { context ->
                WebView(context).also { webView = it; it.settings.javaScriptEnabled = true }
            })
        }
        composeRule.waitForIdle()
    }

    private fun load(settings: ReaderSettings, body: String, progress: Double = 0.0, cues: String? = null, highlights: String? = null) {
        val assets = ReaderWebAssets.load(instrumentation.targetContext)
        val loaded = CountDownLatch(1)
        instrumentation.runOnMainSync {
            val density = webView.resources.displayMetrics.density
            val viewport = readerViewportCssLayout(settings,
                (webView.width / density).toInt(), (webView.height / density).toInt()).cssVariables()
            val styles = ReaderContentStyles.styleTag(settings, readerCssTemplate = assets.readerCss)
            val script = ReaderPaginationScripts.shellScript(settings = settings, initialProgress = progress,
                sasayakiCuesJson = cues, highlightsJson = highlights, assets = assets)
            webView.webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView, url: String) { loaded.countDown() }
            }
            webView.loadDataWithBaseURL(null, """
                <!doctype html><html><head><meta name="viewport" content="width=device-width,initial-scale=1">
                <script>${assets.languageAssets.languageJapaneseJs}
                ${assets.languageAssets.selectionJapaneseJs}
                ${assets.selectionJs}
                window.HoshiReaderRestore={postMessage:()=>window.testRestored=true};</script>
                $styles<style>$viewport rt { color:rgb(220,0,0) !important; }</style>$script
                </head><body>$body</body></html>
            """.trimIndent(), "text/html", "utf-8", null)
        }
        assertTrue(loaded.await(15, TimeUnit.SECONDS))
        composeRule.waitUntil(15_000) { evaluate("Boolean(window.testRestored)") == "true" }
    }

    @Test fun eInkReloadPreservesPositionCuesAndSavedHighlights() {
        createView()
        try {
            for (vertical in listOf(false, true)) for (eInk in listOf(true, false, true)) {
                load(ReaderSettings(paragraphMode = true, eInkMode = eInk, verticalWriting = vertical),
                    "<p>前頁</p><p>前半後半末尾</p>", progress = 0.25,
                    cues = """[{"id":"first","start":2,"length":2},{"id":"second","start":4,"length":2},{"id":"last","start":6,"length":2}]""",
                    highlights = """[{"id":"saved","color":"blue","offset":3,"text":"半後"}]""")
                assertEquals("vertical=$vertical eink=$eInk", "\"ok\"", evaluate("""(() => {
                    hoshiReader.highlightSasayakiCue('second', false);
                    if (hoshiHighlights.highlights.get('saved').wrappers[0].textContent !== '半後') return 'saved highlight lost';
                    if (hoshiParagraph.pageSasayakiCues().length !== 3) return 'page cues lost';
                    if (Math.abs(hoshiReader.calculateProgress() - 0.25) > 0.001) return 'position changed';
                    if (hoshiReader.isEInkMode()) {
                        if (hoshiReader.cueWrappers.size !== 0) return 'unexpected inline cues';
                        const text = hoshiReader.cueGeometryRanges.get('second').map(range => range.toString()).join('');
                        if (text !== '後半') return text;
                    } else {
                        const texts = ['first', 'second', 'last'].map(id =>
                            hoshiReader.cueWrappers.get(id).map(node => node.textContent).join(''));
                        if (JSON.stringify(texts) !== JSON.stringify(['前半','後半','末尾'])) return texts;
                        if (!hoshiReader.cueWrappers.get('second').every(node => node.classList.contains('hoshi-sasayaki-active'))) return 'inactive';
                    }
                    return 'ok';
                })()""".trimIndent()))
                capture("eink-reload-$eInk-$vertical")
            }
        } finally { destroy() }
    }

    @Test fun nativePageTurnReadsProgressBeforeRevealFrames() {
        createView()
        try {
            for (vertical in listOf(false, true)) {
                load(ReaderSettings(paragraphMode = true, verticalWriting = vertical),
                    "<p>第一段落。</p><p>第二段落は少し長く続きます。</p><p>最後。</p>")
                evaluate("""
                    hoshiParagraph.configureAnimation(true, 25);
                    window.progressReads = 0;
                    const originalProgress = hoshiReader.calculateProgress;
                    hoshiReader.calculateProgress = function() { progressReads++; return originalProgress.call(this); };
                    const originalRaf = requestAnimationFrame;
                    window.readsAtFirstFrame = null;
                    window.requestAnimationFrame = callback => originalRaf.call(window, time => {
                        if (readsAtFirstFrame === null) readsAtFirstFrame = progressReads;
                        callback(time);
                    });
                """.trimIndent())
                val completed = CountDownLatch(1)
                var displayed = -1.0
                var saved = -1.0
                var accepted = 0
                instrumentation.runOnMainSync {
                    webView.navigatePage(ReaderNavigationDirection.Forward,
                        onLimit = { fail("fixture has a next page"); false },
                        onDisplayedProgress = { displayed = it },
                        onSaveProgress = { saved = it; completed.countDown() },
                        onTurnAccepted = { accepted++ })
                }
                assertTrue(completed.await(10, TimeUnit.SECONDS))
                composeRule.waitUntil(5_000) { evaluate("readsAtFirstFrame !== null") == "true" }
                assertEquals("Progress must not start in a later reveal frame", "1", evaluate("readsAtFirstFrame"))
                assertEquals("One scan per page turn", "1", evaluate("progressReads"))
                assertEquals(1, accepted)
                assertTrue(saved > 0 && saved < 1)
                assertEquals(saved, displayed, 0.0)
                assertEquals(saved, evaluate("hoshiReader.calculateProgress()").toDouble(), 0.0)
            }
        } finally { destroy() }
    }

    @Test fun narrationPageTurnsReuseLayoutMeasurements() {
        createView()
        try {
            val body = "<p>甲<ruby>乙<rt>おつ</rt></ruby>丙。</p>".repeat(80)
            val sentences = TtsText.chapter(0, body)
            val cues = requireNotNull(ReaderSasayakiCues.chapterCuesJson(SasayakiMatchData(
                matches = sentences.mapIndexed { index, sentence ->
                    SasayakiMatch("cue-$index", index.toDouble(), index + 1.0,
                        sentence.text, 0, sentence.start, sentence.length)
                }, unmatched = 0), 0))
            for (vertical in listOf(false, true)) {
                load(ReaderSettings(paragraphMode = true, textAnimation = true, verticalWriting = vertical),
                    body, cues = cues)
                assertEquals("vertical=$vertical", "\"reused\"", evaluate("""(() => {
                    const reader = hoshiReader;
                    reader.buildPaginationMetrics();
                    const rects = Range.prototype.getClientRects;
                    const walker = document.createTreeWalker;
                    let reads = 0, walks = 0;
                    Range.prototype.getClientRects = function() { reads++; return rects.call(this); };
                    document.createTreeWalker = function(...args) { walks++; return walker.apply(this, args); };
                    try {
                        for (const [direction, page] of [['forward',1],['forward',2],['backward',1]]) {
                            const progress = reader.paginateWithProgress(direction);
                            if (Math.abs(progress - page / 80) > 0.000001) return 'progress: '+progress;
                            if (hoshiParagraph.pageSasayakiCues().join(',') !== 'cue-'+page) return 'cue page';
                            if ((hoshiParagraph.animationFrame !== null) !== (direction === 'forward')) return 'reveal';
                        }
                        return reads === 0 && walks === 0 ? 'reused' : JSON.stringify({reads,walks});
                    } finally {
                        Range.prototype.getClientRects = rects;
                        document.createTreeWalker = walker;
                    }
                })()"""))
            }
        } finally { destroy() }
    }

    @Test fun aRapidBoundaryRequestStillAcceptsTheSuccessfulTurnBeforeIt() {
        createView()
        try {
            load(ReaderSettings(paragraphMode = true), "<p>第一段落。</p><p>最後。</p>")
            var accepted = 0
            var limits = 0
            val boundary = CountDownLatch(1)
            instrumentation.runOnMainSync {
                repeat(2) {
                    webView.navigatePage(ReaderNavigationDirection.Forward,
                        onLimit = { limits++; boundary.countDown(); false },
                        onDisplayedProgress = {}, onSaveProgress = {},
                        onTurnAccepted = { accepted++ })
                }
            }
            assertTrue(boundary.await(10, TimeUnit.SECONDS))
            assertEquals(1, limits)
            assertEquals("The successful turn must still pause and clear the old audio range", 1, accepted)
        } finally { destroy() }
    }

    @Test fun blankTapAdvanceKeepsRevealWhenDomClickArrivesAfterNativeNavigation() {
        createView()
        try {
            for (vertical in listOf(false, true)) for (domFirst in listOf(false, true)) {
                load(ReaderSettings(paragraphMode = true, textAnimation = true, verticalWriting = vertical),
                    "<p>第一段落。</p><p>次の段落は<ruby>東京<rt>とうきょう</rt></ruby>から始まります。</p>")
                evaluate("""
                    hoshiParagraph.finishTextAnimation();
                    window.requestAnimationFrame = () => 987654;
                    document.dispatchEvent(new PointerEvent('pointerdown'));
                """.trimIndent())
                if (domFirst) evaluate("document.dispatchEvent(new MouseEvent('click', {cancelable:true}))")
                assertEquals("Blank tap can advance", "null", evaluate(ReaderSelectionCommand.SelectText(2f, 2f, 16).source))
                val completed = CountDownLatch(1)
                instrumentation.runOnMainSync {
                    webView.navigatePage(ReaderNavigationDirection.Forward,
                        onLimit = { fail("fixture has a next page"); false },
                        onDisplayedProgress = {}, onSaveProgress = { completed.countDown() })
                }
                assertTrue(completed.await(10, TimeUnit.SECONDS))
                if (!domFirst) evaluate("document.dispatchEvent(new MouseEvent('click', {cancelable:true}))")
                assertEquals("The advancing tap must leave the next page's reveal running", "true",
                    evaluate("hoshiParagraph.animationFrame !== null && CSS.highlights.has('hoshi-animation')"))

                val position = evaluate("hoshiReader.getPagePosition(hoshiReader.getScrollContext())")
                evaluate("document.dispatchEvent(new PointerEvent('pointerdown'))")
                assertEquals("A separate tap finishes reveal", "\"animation\"",
                    evaluate(ReaderSelectionCommand.SelectText(2f, 2f, 16).source))
                assertEquals("false", evaluate("CSS.highlights.has('hoshi-animation')"))
                assertEquals(position, evaluate("hoshiReader.getPagePosition(hoshiReader.getScrollContext())"))
            }
        } finally { destroy() }
    }

    @Test fun gaijiImagesAndFallbackFollowTextWithoutMovingContent() {
        createView()
        try {
            val image = "data:image/svg+xml;base64," + android.util.Base64.encodeToString(
                """<svg xmlns="http://www.w3.org/2000/svg" width="24" height="24"><rect width="24" height="24" fill="red"/></svg>""".toByteArray(),
                android.util.Base64.NO_WRAP,
            )
            fun gaiji(id: String) = "<img id='$id' class='gaiji' src='$image' style='width:24px;height:24px;filter:none!important'>"
            for (vertical in listOf(false, true)) {
                load(ReaderSettings(paragraphMode = true, verticalWriting = vertical, fontSize = 24),
                    "<p id='page'>${gaiji("leading")}甲乙${gaiji("middle")}丙丁" +
                        "<span id='fallback' class='hoshi-gaiji-fallback' data-hoshi-gaiji-alt='TM' style='color:red!important'></span>" +
                        "戊己${gaiji("trailing")}</p><p>${gaiji("outside")}次。</p>")
                evaluate("""
                    window.requestAnimationFrame = callback => { window.revealTick=callback; return 987654; };
                    window.gaijiNodes = [...document.querySelectorAll('#page, img, .hoshi-gaiji-fallback')];
                    window.gaijiBounds = () => JSON.stringify(gaijiNodes.map(e => e.getBoundingClientRect().toJSON()));
                    window.beforeGaijiBounds = gaijiBounds();
                    window.beforeGaijiText = document.body.textContent;
                    window.gaijiVisibility = () => ['leading','middle','fallback','trailing','outside']
                        .map(id => getComputedStyle(document.getElementById(id)).visibility);
                    hoshiParagraph.animateText(25);
                """.trimIndent())
                assertEquals("visible leading gaiji; later gaiji wait; other pages stay untouched",
                    "[\"visible\",\"hidden\",\"hidden\",\"hidden\",\"visible\"]", evaluate("gaijiVisibility()"))
                val leadingArea = evaluate("""(() => {
                    const rect = document.getElementById('leading').getBoundingClientRect();
                    return rect.width * rect.height * devicePixelRatio ** 2;
                })()""").toDouble()
                var beforeRed = 0
                // The visual-state callback can precede the compositor's hidden
                // frame. Do not use the initially fully visible page as baseline.
                composeRule.waitUntil(5_000) {
                    val before = capture("gaiji-$vertical-before")
                    beforeRed = redPixels(before)
                    before.recycle()
                    beforeRed > leadingArea * 0.8 && beforeRed < leadingArea * 1.2
                }

                evaluate("revealTick(0); revealTick(40)") // Two preceding characters revealed.
                assertEquals("[\"visible\",\"visible\",\"hidden\",\"hidden\",\"visible\"]", evaluate("gaijiVisibility()"))
                composeRule.waitUntil(5_000) {
                    val painted = capture("gaiji-$vertical-middle")
                    val ready = redPixels(painted) > beforeRed * 1.5
                    painted.recycle()
                    ready
                }
                evaluate("revealTick(120)")
                assertEquals("[\"visible\",\"visible\",\"visible\",\"hidden\",\"visible\"]", evaluate("gaijiVisibility()"))
                evaluate("revealTick(200)")
                assertEquals("[\"visible\",\"visible\",\"visible\",\"visible\",\"visible\"]", evaluate("gaijiVisibility()"))
                assertEquals("No text, nodes or geometry may change during reveal", "true", evaluate("""
                    document.body.textContent === beforeGaijiText && gaijiBounds() === beforeGaijiBounds &&
                    gaijiNodes.every((node,i) => node === document.querySelectorAll('#page, img, .hoshi-gaiji-fallback')[i])
                """.trimIndent()))
                evaluate("hoshiParagraph.animateText(25); hoshiParagraph.configureAnimation(false,25)")
                assertEquals("Disabling reveal restores every gaiji", "false", evaluate("gaijiVisibility().includes('hidden')"))
                evaluate("hoshiParagraph.animateText(25); hoshiReader.paginate('forward')")
                assertEquals("Turning the page restores old gaiji", "false", evaluate("gaijiVisibility().includes('hidden')"))
                capture("gaiji-$vertical-finished").recycle()
            }
        } finally { destroy() }
    }

    @Test fun narrationFollowsWithoutMutatingOrCancellingParagraphReveal() {
        createView()
        try {
            val body = "<p>肉の塊が伸びる。宙を<ruby>駆<rt>か</rt></ruby>ける私を追いかける。</p>" +
                "<p>次の段落にも<ruby>漢字<rt>かんじ</rt></ruby>があります。</p><p>最後の段落です。</p>"
            val sentences = TtsText.chapter(0, body)
            for (tts in listOf(false, true)) for (vertical in listOf(false, true)) {
                val cues = if (tts) ReaderSasayakiCues.ttsChapterCuesJson(sentences) else
                    requireNotNull(ReaderSasayakiCues.chapterCuesJson(SasayakiMatchData(
                        matches = sentences.mapIndexed { index, sentence ->
                            SasayakiMatch("cue-$index", index.toDouble(), index + 1.0,
                                sentence.text, 0, sentence.start, sentence.length)
                        }, unmatched = 0), 0))
                val settings = ReaderSettings(paragraphMode = true, textAnimation = true, textSpeed = 25,
                    verticalWriting = vertical, fontSize = 30, horizontalPadding = 8, verticalPadding = 8)
                load(settings, body, cues = cues)
                assertEquals("Restore animates with chapter cues already installed", "true",
                    evaluate("Boolean(hoshiParagraph.animationFrame) && hoshiReader.cueWrappers.size === ${sentences.size}"))
                evaluate("""
                    hoshiParagraph.finishTextAnimation();
                    window.realRaf = requestAnimationFrame;
                    window.testFrames = new Map(); window.testFrameId = 900000;
                    window.requestAnimationFrame = callback => { testFrames.set(++testFrameId, callback); return testFrameId; };
                    window.nodes = []; const walker = hoshiReader.createWalker(); let node;
                    while (node = walker.nextNode()) nodes.push(node);
                    window.texts = nodes.map(node => node.textContent);
                    window.cues = $cues;
                    hoshiParagraph.animateText(25);
                    window.hidden = [...CSS.highlights.get('hoshi-animation')][0];
                    window.frame = hoshiParagraph.animationFrame;
                    window.mutations = new MutationObserver(() => {});
                    mutations.observe(document.body, {subtree:true, childList:true, characterData:true});
                """.trimIndent())
                assertEquals("Same-page highlighting leaves the current reveal running", "true", evaluate("""
                    (() => {
                        hoshiReader.highlightSasayakiCue(cues[0], false);
                        hoshiReader.highlightSasayakiCue(cues[1], true);
                        return hoshiParagraph.animationFrame === frame &&
                            [...CSS.highlights.get('hoshi-animation')][0] === hidden;
                    })()
                """.trimIndent()))
                // Drive the same animation across cue changes; stale text-node offsets used to throw here.
                evaluate("testFrames.get(hoshiParagraph.animationFrame)(0); testFrames.get(hoshiParagraph.animationFrame)(120)")
                assertEquals("Following to another page does not finish or restart the old animation", "true", evaluate("""
                    (() => {
                        const frame = hoshiParagraph.animationFrame;
                        const result = hoshiReader.highlightSasayakiCue(cues[cues.length - 1], true);
                        return result !== null && hoshiParagraph.animationFrame === frame &&
                            [...CSS.highlights.get('hoshi-animation')][0] === hidden &&
                            !hoshiParagraph.isOnPage(hoshiReader.getRect(hidden)) &&
                            mutations.takeRecords().length === 0 &&
                            nodes.every((node, i) => node.isConnected && node.textContent === texts[i]);
                    })()
                """.trimIndent()))
                evaluate("testFrames.get(hoshiParagraph.animationFrame)(60000); mutations.disconnect(); window.requestAnimationFrame=realRaf")
                assertEquals("Animation still completes normally after following", "false", evaluate("CSS.highlights.has('hoshi-animation')"))
                evaluate("hoshiReader.highlightSasayakiCue(cues[1], true)")
                composeRule.waitUntil(5_000) {
                    val bitmap = capture("narration-$tts-$vertical-highlight")
                    val painted = bluePixels(bitmap) > 20
                    bitmap.recycle()
                    painted
                }
                evaluate("hoshiReader.clearSasayakiCue()")
                composeRule.waitUntil(5_000) {
                    val bitmap = capture("narration-$tts-$vertical-plain")
                    val painted = bluePixels(bitmap) == 0
                    bitmap.recycle()
                    painted
                }
                // A new document restore is allowed to animate during narration, including a backward restore.
                load(settings, body, progress = 1.0, cues = cues)
                assertEquals("true", evaluate("Boolean(hoshiParagraph.animationFrame)"))
            }
        } finally { destroy() }
    }

    @Test fun rubyAnimationKeepsLiveDomAndGeometryStableInBothWritingDirections() {
        createView()
        try {
            for (vertical in listOf(false, true)) {
                val settings = ReaderSettings(paragraphMode = true, textAnimation = true, textSpeed = 25,
                    verticalWriting = vertical, fontSize = 30, horizontalPadding = 8, verticalPadding = 8)
                load(settings, "<p id='first'>始まりの文字。<ruby>東京<rt>とうきょう</rt></ruby>と<ruby><b>読書</b><rt><em>どくしょ</em></rt></ruby>の時間。</p><p>次の段落。</p>")
                assertEquals("true", evaluate("Boolean(window.hoshiParagraph.animationFrame)"))
                // Freeze only the reveal clock after normal restore has completed.
                evaluate("""
                    hoshiParagraph.finishTextAnimation();
                    window.realRaf = requestAnimationFrame;
                    window.requestAnimationFrame = callback => { window.revealTick = callback; return 987654; };
                    window.beforeHtml = document.body.innerHTML;
                    window.rects = () => JSON.stringify([...document.querySelectorAll('#first, ruby, rt')].map(el => {
                        const r = el.getBoundingClientRect(); return [r.x,r.y,r.width,r.height]; }));
                    window.beforeRects = rects();
                    hoshiParagraph.animateText(25);
                """.trimIndent())
                val hidden = capture("paragraph-$vertical-hidden")
                val hiddenRed = redPixels(hidden)
                hidden.recycle()
                assertEquals("true", evaluate("document.body.innerHTML === beforeHtml && rects() === beforeRects"))
                assertEquals("\"animation\"", evaluate(ReaderSelectionCommand.SelectText(2f, 2f, 16).source))
                assertEquals("false", evaluate("hoshiParagraph.finishTextAnimation()"))
                composeRule.waitUntil(10_000) {
                    val painted = capture("paragraph-$vertical-visible")
                    val ready = redPixels(painted) > 20
                    painted.recycle()
                    ready
                }
                val visible = capture("paragraph-$vertical-visible")
                val visibleRed = redPixels(visible)
                visible.recycle()
                assertTrue("ruby must be painted after reveal ($vertical): hidden=$hiddenRed visible=$visibleRed", visibleRed > 20)
                assertTrue("ruby must stay hidden before its base ($vertical): hidden=$hiddenRed visible=$visibleRed", hiddenRed < visibleRed / 10)
                assertEquals("true", evaluate("document.body.innerHTML === beforeHtml && rects() === beforeRects"))
                assertEquals("true", evaluate("""(() => {
                    document.dispatchEvent(new PointerEvent('pointerdown'));
                    const link = document.createElement('a'); link.href = '#first';
                    document.body.appendChild(link); let clicked = false;
                    link.addEventListener('click', () => clicked = true);
                    hoshiParagraph.animateText(25);
                    link.click();
                    const consumed = hoshiParagraph.consumeAnimationTap();
                    document.dispatchEvent(new PointerEvent('pointerdown'));
                    link.click(); link.remove();
                    return consumed && clicked && !hoshiParagraph.consumeAnimationTap();
                })()"""))
                evaluate("window.requestAnimationFrame=realRaf")
                assertEquals("\"scrolled\"", evaluate("hoshiReader.paginate('forward')"))
                assertEquals("true", evaluate("Boolean(hoshiParagraph.animationFrame)"))
                assertEquals("\"scrolled\"", evaluate("hoshiReader.paginate('backward')"))
                assertEquals("false", evaluate("Boolean(hoshiParagraph.animationFrame)"))
                // A previous-chapter restore (progress=1) starts animation too.
                load(settings, "<p>第一段落。</p><p>最後の段落です。</p>", progress = 1.0)
                assertEquals("true", evaluate("Boolean(hoshiParagraph.animationFrame)"))
            }
        } finally { destroy() }
    }

    @Test fun sentencesStayInsideParagraphsAndLongParagraphsKeepRubyAndOffsets() {
        createView()
        try {
            for (vertical in listOf(false, true)) for (eInk in listOf(false, true)) {
                val settings = ReaderSettings(paragraphMode = true, verticalWriting = vertical, maxSentencesPerPage = 1, eInkMode = eInk)
                load(settings, "<p>甲。乙！？丙。</p><p>「会話。続き。」後。</p><div id='other'>分割。しない。</div>")
                assertEquals("[\"甲。\",\"乙！？\",\"丙。\",\"「会話。続き。」後。\"]",
                    evaluate("[...document.querySelectorAll('p')].map(p=>p.textContent)"))
                assertEquals("\"分割。しない。\"", evaluate("document.getElementById('other').textContent"))
                load(settings.copy(splitDialogue = true), "<p>「会話。続き。最後。」後。</p>")
                assertEquals("3", evaluate("document.querySelectorAll('p').length"))
                val longText = "長い本文と𠮷。".repeat(300)
                load(settings.copy(maxSentencesPerPage = 0),
                    "<p id='long'>$longText<ruby>終端<rt>しゅうたん</rt></ruby></p><p>最後。</p>",
                    cues = """[{"id":"begin","start":0,"length":3},{"id":"last","start":${longText.codePointCount(0, longText.length) - 300},"length":2}]""")
                assertTrue(evaluate("document.getElementById('long').getClientRects().length").toInt() > 1)
                assertEquals("1", evaluate("document.querySelectorAll('ruby').length"))
                assertEquals("\"終端しゅうたん\"", evaluate("document.querySelector('ruby').textContent"))
                assertEquals("[\"begin\"]", evaluate("hoshiParagraph.pageSasayakiCues()"))
                assertEquals("true", evaluate("""(() => {
                    let node, raw=0, normalized=0; const walker=hoshiReader.createWalker();
                    while(node=walker.nextNode()) {
                        if(hoshiReader.nodeStartRawOffsets.get(node)!==raw || hoshiReader.nodeStartOffsets.get(node)!==normalized) return false;
                        raw+=hoshiReader.countRawChars(node.textContent); normalized+=hoshiReader.countChars(node.textContent);
                    } return true; })()"""))
                evaluate("window.beforeLong=document.getElementById('long').textContent; hoshiParagraph.configureAnimation(true,35); hoshiReader.paginate('forward')")
                assertEquals("true", evaluate("document.getElementById('long').textContent===beforeLong"))
            }
        } finally { destroy() }
    }

    @Test fun lateChapterPagesRevealFromTheBeginningAtZeroPagePadding() {
        createView()
        try {
            for (vertical in listOf(false, true)) {
                val body = (0 until 562).joinToString("") { index ->
                    val text = if (index % 2 == 0) "　最初から最後まで表示する文章。"
                        else "　文章の途中にある<ruby><b>漢字</b><rt>かんじ</rt></ruby>と続きの文章。"
                    "<p id='page-$index'>$text</p>"
                }
                load(ReaderSettings(paragraphMode = true, textAnimation = true, textSpeed = 25,
                    verticalWriting = vertical, horizontalPadding = 0, verticalPadding = 0), body)
                evaluate("""
                    hoshiParagraph.finishTextAnimation();
                    window.realRaf = requestAnimationFrame;
                    window.requestAnimationFrame = callback => 987654;
                    const context = hoshiReader.getScrollContext();
                    hoshiReader.setPagePosition(context, 549 * context.pageSize);
                """.trimIndent())
                for (page in 550..560) {
                    assertEquals("\"scrolled\"", evaluate("hoshiReader.paginate('forward')"))
                    val result = evaluate("""(() => {
                        const paragraph = document.getElementById('page-$page');
                        const walker = hoshiReader.createWalker(paragraph);
                        const first = walker.nextNode(); let last = first, node;
                        while(node = walker.nextNode()) last = node;
                        const hidden = [...(CSS.highlights.get('hoshi-animation') || [])][0];
                        if (!hidden) return 'missing animation';
                        if (hidden.startContainer !== first || hidden.startOffset !== 1) return 'skipped page beginning';
                        if (hidden.endContainer !== last || hidden.endOffset !== last.length) return 'wrong page end';
                        hoshiReader.cueWrappers = new Map(); hoshiReader.cueGeometryRanges = new Map();
                        for (const index of [$page-1, $page, $page+1]) {
                            const range = document.createRange();
                            const start = document.getElementById('page-'+index).firstChild;
                            range.setStart(start, 0); range.setEnd(start, 1);
                            hoshiReader.cueGeometryRanges.set('cue-'+index, [range]);
                        }
                        return hoshiParagraph.pageSasayakiCues();
                    })()""")
                    assertEquals("page=$page, vertical=$vertical", "[\"cue-$page\"]", result)
                }
                evaluate("hoshiParagraph.finishTextAnimation(); window.requestAnimationFrame=realRaf")
            }
        } finally { destroy() }
    }

    @Test fun longParagraphPagesAndShortTailsAreCenteredInBothDirections() {
        createView()
        try {
            for (vertical in listOf(false, true)) {
                val settings = ReaderSettings(paragraphMode = true, verticalWriting = vertical,
                    fontSize = 43, layoutAdvanced = true, lineHeight = 1.75,
                    horizontalPadding = 3, verticalPadding = 0)
                load(settings, """
                    <p id='empty-start'><br></p><p><br></p>
                    <p id='long'>${"あいうえお".repeat(45)}<ruby>漢字<rt>かんじ</rt></ruby>終わる。</p>
                    <p id='empty-middle'><br></p><p><br></p>
                    <p id='tail'>る。</p><p>次の段落。</p>
                    <p><br></p>
                """.trimIndent())
                val result = evaluate("""(() => {
                    const reader = hoshiReader, context = reader.getScrollContext();
                    const scroll = reader.getPagePosition(context), pages = new Map();
                    const range = document.createRange();
                    for (const id of ['long', 'tail']) {
                        const walker = reader.createWalker(document.getElementById(id)); let node;
                        while (node = walker.nextNode()) {
                            range.selectNodeContents(node);
                            for (const r of range.getClientRects()) {
                                if (!r.width || !r.height) continue;
                                const page = Math.floor((Math.round(context.vertical ? r.top : r.left) + scroll) / context.pageSize);
                                const offsetX = context.vertical ? 0 : scroll - page * context.pageSize;
                                const offsetY = context.vertical ? scroll - page * context.pageSize : 0;
                                const bounds = pages.get(page) || {left:Infinity,right:-Infinity,top:Infinity,bottom:-Infinity};
                                bounds.left = Math.min(bounds.left, r.left + offsetX);
                                bounds.right = Math.max(bounds.right, r.right + offsetX);
                                bounds.top = Math.min(bounds.top, r.top + offsetY);
                                bounds.bottom = Math.max(bounds.bottom, r.bottom + offsetY);
                                pages.set(page, bounds);
                            }
                        }
                    }
                    if (pages.size < 3) return 'fixture must include a continuation and a short paragraph';
                    if (Math.min(...pages.keys()) !== 0 || Math.max(...pages.keys()) !== pages.size-1)
                        return 'empty paragraphs introduced blank pages: '+[...pages.keys()];
                    for (const [page, b] of pages) {
                        const blockMiddle = context.vertical ? (b.left+b.right)/2 : (b.top+b.bottom)/2;
                        const blockCenter = (context.vertical ? reader.pageWidth : reader.pageHeight)/2;
                        // Glyph ink can be asymmetric inside centered line boxes.
                        if (Math.abs(blockMiddle-blockCenter) > 43/4)
                            return 'page '+page+' off block center: '+JSON.stringify(b);
                        const inlineSize = context.vertical ? b.bottom-b.top : b.right-b.left;
                        const inlineAvailable = context.vertical ? reader.pageHeight : reader.pageWidth;
                        if (inlineAvailable-inlineSize > 43 && Math.abs((context.vertical ? b.top+b.bottom : b.left+b.right)/2-inlineAvailable/2) > 1)
                            return 'page '+page+' off inline center: '+JSON.stringify(b);
                    }
                    const expected = new Map(), walker = reader.createWalker(document.getElementById('long'));
                    let node;
                    while (node = walker.nextNode()) {
                        for (let offset=0; offset<node.length;) {
                            const end = offset + String.fromCodePoint(node.textContent.codePointAt(offset)).length;
                            range.setStart(node, offset); range.setEnd(node, end);
                            const rect = range.getClientRects()[0];
                            const page = Math.floor((Math.round(context.vertical ? rect.top : rect.left)+scroll)/context.pageSize);
                            if (!expected.has(page)) expected.set(page, []);
                            expected.get(page).push({node, offset, end}); offset=end;
                        }
                    }
                    const originalRaf = requestAnimationFrame;
                    window.requestAnimationFrame = () => 987654;
                    try {
                        for (const [page, chars] of expected) {
                            reader.setPagePosition(context, page*context.pageSize);
                            hoshiParagraph.animateText(25);
                            const hidden = [...(CSS.highlights.get('hoshi-animation') || [])][0];
                            if (chars.length > 1 && (!hidden || hidden.startContainer !== chars[1].node ||
                                hidden.startOffset !== chars[1].offset || hidden.endContainer !== chars.at(-1).node ||
                                hidden.endOffset !== chars.at(-1).end)) return 'page '+page+' reveals the wrong text';
                        }
                    } finally {
                        hoshiParagraph.finishTextAnimation(); window.requestAnimationFrame=originalRaf;
                        reader.setPagePosition(context, scroll);
                    }
                    return 'centered';
                })()""")
                assertEquals("vertical=$vertical", "\"centered\"", result)
                for ((anchor, content) in listOf("empty-start" to "long", "empty-middle" to "tail")) {
                    evaluate("window.testRestored=false; hoshiReader.jumpToFragment('$anchor'); true")
                    composeRule.waitUntil(5_000) {
                        evaluate("testRestored && hoshiParagraph.isOnPage(hoshiReader.getRect(document.getElementById('$content')))") == "true"
                    }
                }
                assertEquals("\"scrolled\"", evaluate("hoshiReader.paginate('forward')"))
                assertEquals("No trailing blank page", "\"limit\"", evaluate("hoshiReader.paginate('forward')"))
            }
        } finally { destroy() }
    }

    @Test fun publisherOrnamentsMediaAndBordersRemainVisibleWithoutExtraPages() {
        createView()
        try {
            for (vertical in listOf(false, true)) {
                load(ReaderSettings(paragraphMode = true, verticalWriting = vertical), """
                    <style>
                        #ornament::before { content:'＊'; }
                        #background { background:red; inline-size:30px; block-size:30px; }
                        #border { border:1px solid; }
                    </style>
                    <p id='border'>囲み。</p><p id='ornament'></p>
                    <p id='background'></p><p id='media'><canvas width='30' height='30'></canvas></p>
                    <p id='end'>最後。</p>
                """.trimIndent())
                assertEquals("vertical=$vertical", "\"visible\"", evaluate("""(() => {
                    const reader = hoshiReader, context = reader.getScrollContext();
                    const scroll = reader.getPagePosition(context);
                    for (const [id, page] of [['border',0],['ornament',1],['background',1],['media',1],['end',2]]) {
                        const rects = document.getElementById(id).getClientRects();
                        const rect = rects[0];
                        if (rects.length !== 1 || !rect.width || !rect.height)
                            return id+' collapsed or overflowed: '+JSON.stringify([...rects]);
                        const actual = Math.floor((Math.round(context.vertical ? rect.top : rect.left)+scroll)/context.pageSize);
                        if (actual !== page) return id+' is on page '+actual+', expected '+page;
                    }
                    return 'visible';
                })()"""))
                for (decoration in listOf(
                    "<style>#last::before { content:'＊'; }</style><p id='last'></p>",
                    "<p id='last' style='background:red;inline-size:30px;block-size:30px'></p>",
                    "<p id='last' style='border-inline-start:1px solid;inline-size:30px;block-size:30px'></p>",
                )) {
                    load(ReaderSettings(paragraphMode = true, verticalWriting = vertical), "<p>本文。</p>$decoration")
                    assertEquals("Final decoration must be reachable", "\"scrolled\"", evaluate("hoshiReader.paginate('forward')"))
                    assertEquals("true", evaluate("hoshiParagraph.isOnPage(hoshiReader.getRect(document.getElementById('last')))"))
                    assertEquals("\"limit\"", evaluate("hoshiReader.paginate('forward')"))
                }
            }
        } finally { destroy() }
    }

    @Test fun verticalPageContentIsCenteredAsAWholeIncludingLineBreaksAndFragments() {
        createView()
        try {
            for (padding in listOf(0, 8)) {
                val settings = ReaderSettings(paragraphMode = true, verticalWriting = true,
                    horizontalPadding = padding, verticalPadding = padding, fontSize = 25)
                val longText = "長い本文が次のページまで続きます。".repeat(80)
                load(settings, """
                    <p id='short'>短い文章。</p>
                    <p id='lines'><span id='line-one'>短い。</span><br><span id='line-two'>少し長い<ruby>文章<rt>ぶんしょう</rt></ruby>です。</span></p>
                    <p id='long'>$longText</p>
                    <p id='broken-long'>${List(80) { "短い行。" }.joinToString("<br>")}</p>
                    <p id='image'><svg xmlns='http://www.w3.org/2000/svg' width='120' height='180'><rect width='120' height='180' fill='blue'/></svg></p>
                    <p id='last'>最後の文章。</p>
                """.trimIndent())
                val result = evaluate("""(() => {
                    const reader = hoshiReader, context = reader.getScrollContext();
                    const scroll = reader.getPagePosition(context);
                    window.contentBounds = target => {
                        const range = document.createRange(); range.selectNodeContents(target);
                        const rects = [...range.getClientRects()].filter(r => r.width > 0 && r.height > 0);
                        return { top: Math.min(...rects.map(r => r.top)), bottom: Math.max(...rects.map(r => r.bottom)) };
                    };
                    const fragments = [...document.querySelectorAll('#long > .hoshi-fragment, #broken-long > .hoshi-fragment')];
                    if (fragments.length < 2) return 'fixture must span pages';
                    for (const target of [document.getElementById('short'), document.getElementById('lines'),
                        ...fragments, document.getElementById('image'), document.getElementById('last')]) {
                        const bounds = contentBounds(target);
                        // Naturally wrapped full pages can leave less than one glyph
                        // of trailing space; that is not a short passage to center.
                        const available = reader.pageHeight - parseFloat(getComputedStyle(document.body).paddingTop)
                            - parseFloat(getComputedStyle(document.body).paddingBottom);
                        if (available - (bounds.bottom - bounds.top) < 25) continue;
                        const page = context.vertical ? Math.floor((bounds.top + scroll + 0.5) / reader.pageHeight) : 0;
                        const middle = (bounds.top + bounds.bottom) / 2 + (context.vertical ? scroll : 0);
                        if (Math.abs(middle - (page + 0.5) * reader.pageHeight) > 1)
                            return (target.id || 'fragment') + ' not centered: ' + JSON.stringify(bounds);
                    }
                    // Unequal lines move together; their original common starting edge stays aligned.
                    if (context.vertical && Math.abs(contentBounds(document.getElementById('line-one')).top -
                        contentBounds(document.getElementById('line-two')).top) > 1) return 'lines centered separately';
                    return 'centered';
                })()""")
                assertEquals("padding=$padding", "\"centered\"", result)
                evaluate("hoshiReader.scrollToRange(document.getElementById('lines'))")
                capture("paragraph-centered-$padding").recycle()
                assertEquals("true", evaluate("""(() => {
                    const range = document.createRange(); range.selectNodeContents(document.getElementById('last'));
                    hoshiReader.scrollToRange(range);
                    return hoshiReader.paginate('forward') === 'limit';
                })()"""))
            }
        } finally { destroy() }
    }

    private fun bluePixels(bitmap: Bitmap): Int {
        var count = 0
        for (y in 0 until bitmap.height) for (x in 0 until bitmap.width) {
            val c = bitmap.getPixel(x, y)
            if (Color.blue(c) > Color.red(c) + 15 && Color.green(c) > Color.red(c) + 5) count++
        }
        return count
    }

    private fun redPixels(bitmap: Bitmap): Int {
        var count = 0
        for (y in 0 until bitmap.height) for (x in 0 until bitmap.width) {
            val c = bitmap.getPixel(x, y)
            if (Color.red(c) > 100 && Color.red(c) > Color.green(c) * 2 && Color.red(c) > Color.blue(c) * 2) count++
        }
        return count
    }

    private fun capture(name: String): Bitmap {
        val ready = CountDownLatch(1)
        instrumentation.runOnMainSync {
            webView.postVisualStateCallback(0, object : WebView.VisualStateCallback() {
                override fun onComplete(requestId: Long) { webView.postOnAnimation { ready.countDown() } }
            })
        }
        assertTrue(ready.await(10, TimeUnit.SECONDS))
        val location = IntArray(2)
        instrumentation.runOnMainSync { webView.getLocationOnScreen(location) }
        val screen = instrumentation.uiAutomation.takeScreenshot()
        val bitmap = Bitmap.createBitmap(screen, location[0], location[1], webView.width, webView.height)
        screen.recycle()
        val directory = File(instrumentation.targetContext.cacheDir, "paragraph-evidence").apply { mkdirs() }
        directory.resolve("$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        return bitmap
    }

    private fun evaluate(script: String): String {
        val done = CountDownLatch(1)
        var result = "null"
        instrumentation.runOnMainSync { webView.evaluateJavascript(script) { result = it; done.countDown() } }
        assertTrue(done.await(10, TimeUnit.SECONDS))
        return result
    }
    private fun destroy() = instrumentation.runOnMainSync {
        (webView.parent as? ViewGroup)?.removeView(webView)
        webView.destroy()
    }
}
