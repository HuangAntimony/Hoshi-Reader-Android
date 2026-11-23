package moe.antimony.hoshi.features.reader

import java.io.File
import moe.antimony.hoshi.epub.EpubBook
import moe.antimony.hoshi.epub.EpubChapter
import moe.antimony.hoshi.epub.EpubResource
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.nodes.TextNode
import org.jsoup.parser.Parser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ReaderBookDocumentTest {
    @get:Rule val temp = TemporaryFolder()

    @Test fun servesEveryChapterOnceWithOriginalFragmentsAndScopedPublisherStyles() {
        val source = """
            <html class="outer" lang="ja"><head><link rel="stylesheet" href="../css/book.css"></head>
            <body class="inner"><p id="first">Chapter text</p><a href="part 2.xhtml#first">next</a>
            <svg><image xlink:href="../img/cover.jpg" width="100" height="50"/><defs><pattern><image href="../img/tile.png" width="10" height="10"/></pattern><filter id="ink"></filter></defs><path filter="url(#ink)"></path><use href="#ink"/></svg>
            <img src="../img/cover.jpg" srcset="../img/small.jpg 1x, ../img/large.jpg 2x">
            </body></html>
        """.trimIndent()
        val stylesheet = """
            @import "nested/intro.css" print;
            @font-face { font-family: 'Book Face'; src:url('../font/book.woff2') }
            @media (min-width:1px) { :root.outer > body, #first:is(.foo,.bar), [id="first"] { font-family:'Book Face'; filter:url(#ink) } }
            @keyframes glow { from { opacity: 0 } to { opacity: 1 } }
            .anim { animation: glow 2s }
        """.trimIndent()
        val book = EpubBook("Title", chapters = listOf(
            EpubChapter("a", "Text/start.xhtml", "application/xhtml+xml", source),
            EpubChapter("b", "Text/part 2.xhtml", "application/xhtml+xml", "", linear = false),
            EpubChapter("c", "nav.xhtml", "text/html", "<style>body { color: blue }</style><p>Contents</p>", isGuideToc = true),
        ), resources = mapOf(
            "Text/part 2.xhtml" to EpubResource("application/xhtml+xml", source.encodeToByteArray()),
            "css/book.css" to EpubResource("text/css", stylesheet.encodeToByteArray()),
            "css/nested/intro.css" to EpubResource("text/css", "p {background:url('../../img/bg.png')}".encodeToByteArray()),
        ))
        val bridge = ReaderWebResourceBridge(book) { null }
        val html = bridge.resourceForUrl(READER_BOOK_URL)!!
        assertEquals("no-store", html.headers["Cache-Control"])
        val policy = html.headers.getValue("Content-Security-Policy").split("; ").toSet()
        assertTrue("script-src https://appassets.androidplatform.net/popup/reader-popup-host.js" in policy)
        assertTrue("frame-src https://appassets.androidplatform.net/popup/iframe.html" in policy)
        val doc = Jsoup.parse(html.data.decodeToString())
        val sections = doc.select("body > [data-hoshi-chapter]")
        assertEquals(listOf("0", "1", "2"), sections.eachAttr("data-hoshi-chapter"))
        assertEquals(book.chapters.map { it.href }, sections.eachAttr("data-hoshi-href"))
        assertEquals(2, doc.select("p[data-hoshi-id=first]").size)
        assertEquals(2, doc.select("[data-hoshi-root].outer > hoshi-body.inner").size)
        assertEquals(2, doc.select("p[data-hoshi-id=first]").eachAttr("id").distinct().size)
        assertEquals("https://appassets.androidplatform.net/epub/Text/part%202.xhtml#first", doc.selectFirst("a")!!.attr("href"))
        assertEquals(2, doc.select("img[loading=lazy]").size)
        assertEquals(2, doc.select("svg image[data-hoshi-image-href]").size)
        assertTrue(doc.select("svg > image[href], svg > image[xlink:href]").isEmpty())
        assertEquals("https://appassets.androidplatform.net/epub/img/tile.png", doc.selectFirst("pattern image")!!.attr("href"))
        assertEquals("https://appassets.androidplatform.net/epub/img/cover.jpg", doc.selectFirst("svg image")!!.attr("data-hoshi-image-href"))
        assertTrue(doc.selectFirst("img")!!.attr("srcset").contains("/epub/img/large.jpg 2x"))
        sections.take(2).forEach { section ->
            val id = section.selectFirst("filter")!!.id()
            assertEquals("#$id", section.selectFirst("use")!!.attr("href"))
            assertEquals("url(\"#$id\")", section.selectFirst("path")!!.attr("filter"))
            assertTrue(section.attr("style").contains("#$id"))
        }
        val css = bridge.resourceForUrl(READER_BOOK_URL.replace("index.html", "publisher.css"))!!.data.decodeToString()
        assertEquals(listOf("s0", "s0", "s1"), sections.eachAttr("data-hoshi-styles"))
        assertTrue(css.contains("[data-hoshi-styles=\"s0\"] [data-hoshi-root].outer > hoshi-body"))
        assertTrue(css.contains("[data-hoshi-styles=\"s0\"] :is(#hoshi-unused-id,[data-hoshi-id=\"first\"]):is(.foo,.bar)"))
        assertTrue(css.contains("[data-hoshi-styles=\"s1\"] hoshi-body"))
        assertTrue(css.contains("@media print{"))
        assertTrue(css.contains("/epub/img/bg.png"))
        assertTrue(css.contains("/epub/font/book.woff2"))
        assertTrue(css.contains("animation: hoshi-animation-s0-0"))
        assertFalse(css.contains("Book Face"))
        assertFalse(css.contains("@import"))
    }

    @Test fun koboXhtmlRetainsChapterBodyFollowingSelfClosingScript() {
        // Structural markup from item/xhtml/p-010.xhtml in the Kobo EPUB fixture.
        val xhtml = """
            <?xml version="1.0" encoding="UTF-8"?><!DOCTYPE html>
            <html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops" xml:lang="ja" class="vrtl">
            <head><meta charset="UTF-8"/>
            <link rel="stylesheet" type="text/css" href="../style/book-style.css"/>
            <script xmlns="http://www.w3.org/1999/xhtml" type="text/javascript" src="../../js/kobo.js"/>
            <style xmlns="http://www.w3.org/1999/xhtml" type="text/css" id="koboSpanStyle">.koboSpan { -webkit-text-combine: inherit; }</style>
            </head><body class="p-text"><div class="main">
            <p><a id="target"/><span class="koboSpan" id="kobo.1.1">章</span><br/><ruby>驚<rt>きょう</rt></ruby></p>
            </div></body></html>
        """.trimIndent()
        val fragment = "<script src='kobo.js'/><p>fragment</p>"
        val html = "<title>HTML</title><p>first<p>second"
        val book = EpubBook("Kobo", chapters = listOf(
            EpubChapter("1", "item/xhtml/p-010.xhtml", "application/xhtml+xml; charset=utf-8", xhtml),
            EpubChapter("2", "fragment.xhtml", "application/xhtml+xml", fragment),
            EpubChapter("3", "web.html", "text/html", html),
        ), resources = mapOf("item/style/book-style.css" to EpubResource("text/css", ".main { color: blue }".encodeToByteArray())))
        val merged = book.buildReaderBookDocument()
        val doc = Jsoup.parse(merged.html.decodeToString())
        val sections = doc.select("body > section")
        val p = sections[0].selectFirst("hoshi-html.vrtl[lang=ja] > hoshi-body.p-text > .main > p")!!
        assertEquals(listOf("a", "span", "br", "ruby"), p.children().map { it.normalName() })
        assertEquals("きょう", p.selectFirst("ruby > rt")!!.text())
        assertEquals("kobo.1.1", p.selectFirst("span")!!.attr("data-hoshi-id"))
        assertTrue(doc.select("script").isEmpty())
        assertEquals("fragment", sections[1].selectFirst("p")!!.text())
        assertEquals(listOf("first", "second"), sections[2].select("p").eachText())
        assertTrue(merged.css.decodeToString().contains(".koboSpan"))
        assertTrue(merged.css.decodeToString().contains(".main"))
    }

    @Test fun mergedChapterPreservesExactBodyTextNodesForRawHighlightOffsets() {
        val source = """
            <html xmlns="http://www.w3.org/1999/xhtml">

              <head>
                <title>Title</title>
              </head>

              <body>
                <p>  First <em>word</em>. </p>
                <p>次の<ruby>字<rt>じ</rt></ruby></p>
              </body>

            </html>
        """.trimIndent()
        val book = EpubBook("Offsets", chapters = listOf(EpubChapter("1", "ch.xhtml", "application/xhtml+xml", source)))
        fun textNodes(root: Element): List<String> =
            root.nodeStream().filter { it is TextNode }.map { (it as TextNode).wholeText }.toList()
        val original = Jsoup.parse(source, "", Parser.xmlParser()).selectFirst("body")!!
        val section = Jsoup.parse(book.buildReaderBookDocument().html.decodeToString()).selectFirst("[data-hoshi-chapter]")!!
        assertEquals(textNodes(original), textNodes(section))
    }

    @Test fun readerTypographyReachesChapterWrappersAndRespectsPublisherFontChoice() {
        fun wrapperStyle(font: String): String {
            val css = ReaderContentStyles.css(ReaderSettings(viewMode = ReaderViewMode.Continuous, selectedFont = font))
            return css.substringAfter("[data-hoshi-chapter] > hoshi-html > hoshi-body {").substringBefore('}')
        }
        val custom = wrapperStyle("Custom Font")
        assertTrue(custom.contains("font-family: 'Custom Font'"))
        assertTrue(custom.contains("font-size: inherit !important"))
        assertFalse(wrapperStyle(ReaderFontManager.publisherFont).contains("font-family:"))
    }

    @Test fun publisherWebLinksSurviveMergingWithoutLoadingExternalResources() {
        val publisher = "https://publisher.example/books"
        val store = "http://store.example/title#details"
        val source = """<a href="$publisher">Publisher</a><a href="$store">Buy this book</a>
            <a href="notes.xhtml#note">Notes</a><img src="$publisher/cover.jpg">
            <a href="https://appassets.androidplatform.net/popup/index.html">Outside the EPUB</a>"""
        val book = EpubBook("Book", chapters = listOf(EpubChapter("end", "text/end.html", "text/html", source)))
        val doc = Jsoup.parse(book.buildReaderBookDocument().html.decodeToString())
        assertEquals(listOf(publisher, store, "https://appassets.androidplatform.net/epub/text/notes.xhtml#note"),
            doc.select("a[href]").eachAttr("href"))
        assertTrue(doc.select("img[src]").isEmpty())
    }

    @Test fun reservesIntrinsicImageSpaceOncePerResourceAndKeepsPublisherSizes() {
        val root = temp.newFolder()
        val book = EpubBook("Book", rootDirectory = root, chapters = listOf(EpubChapter(
            "a", "ch.xhtml", "application/xhtml+xml",
            "<img src='art.png'><img src='art.png' width='90'><img src='art.png' width='30' height='60'>" +
                "<p>文<img src='art.png' class='publisher-Gaiji'/>字</p>",
        )))
        val calls = mutableListOf<File>()
        val generated = book.buildReaderBookDocument { calls += it; 300 to 600 }
        assertEquals(listOf(root.resolve("art.png")), calls)
        val images = Jsoup.parse(generated.html.decodeToString()).select("img")
        assertEquals(listOf("300", "90", "30", "300"), images.eachAttr("width"))
        assertEquals(listOf("600", "180", "60", "600"), images.eachAttr("height"))
        assertEquals(3, images.select("img.block-img[data-hoshi-sized-art]").size)
        assertTrue(images.first()!!.attr("style").contains("--hoshi-art-ratio:0.5"))
        assertFalse(images.last()!!.hasClass("block-img"))
    }

    @Test fun stripsActivePublisherMarkupAndContainsResourceRequestsWithinBookRoot() {
        val root = temp.newFolder()
        val book = EpubBook("Book", rootDirectory = root, chapters = listOf(EpubChapter(
            "a", "ch.xhtml", "text/html", """
                <script>window.bad = true</script><iframe srcdoc="bad"></iframe><p onclick="bad()" data-hoshi-chapter="99">safe</p>
                <a href="javascript:bad()">bad</a><img src="../fonts/private.ttf"><img src="%2e%2e/secret.png">
            """.trimIndent(),
        )))
        val bridge = ReaderWebResourceBridge(book) { null }
        val doc = Jsoup.parse(bridge.resourceForUrl(READER_BOOK_URL)!!.data.decodeToString())
        assertTrue(doc.select("script,iframe,[onclick],a[href],img[src]").isEmpty())
        assertEquals(1, doc.select("[data-hoshi-chapter]").size)
        val media = "https://appassets.androidplatform.net/epub/"
        temp.root.resolve("secret.png").writeBytes(byteArrayOf(1, 2, 3))
        for (path in listOf("%2e%2e/secret.png", "%2Fsecret.png", "..%5csecret.png")) {
            assertEquals(404, bridge.resourceForUrl(media + path)!!.statusCode)
            assertNull(bridge.imageResourceForUrl(media + path))
        }
    }
}
