package moe.antimony.hoshi.features.reader

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReaderWebAssetsTest {
    @Test
    fun copyingAssetsDoesNotShareCompiledTemplates() {
        val original = ReaderWebAssets(selectionJs = "", readerPaginatedJs = "first __HOSHI_RESTORE_TOKEN_LITERAL__",
            readerContinuousJs = "", readerParagraphJs = "", readerViewportJs = "", readerDocumentPreparationJs = "",
            readerTextSemanticsJs = "", readerDomTextJs = "", readerMediaSemanticsJs = "", readerSasayakiJs = "",
            highlightsJs = "", readerCss = "")
        val parameters = mapOf("__HOSHI_RESTORE_TOKEN_LITERAL__" to "token")
        assertTrue(original.paginationSource.paginatedTemplate.render(parameters).startsWith("first"))
        val copy = original.copy(readerPaginatedJs = "second __HOSHI_RESTORE_TOKEN_LITERAL__")
        assertTrue(copy.paginationSource.paginatedTemplate.render(parameters).startsWith("second"))
        assertTrue(original.paginationSource.paginatedTemplate.render(parameters).startsWith("first"))
    }

    @Test
    fun readerWebAssetsExistInNeutralAssetTree() {
        val assets = listOf(
            "hoshi-web/shared/language-ja.js",
            "hoshi-web/shared/selection-ja.js",
            "hoshi-web/shared/selection-en.js",
            "hoshi-web/shared/selection.js",
            "hoshi-web/reader/reader-paginated.js",
            "hoshi-web/reader/reader-continuous.js",
            "hoshi-web/reader/reader-paragraph.js",
            "hoshi-web/reader/reader-viewport.js",
            "hoshi-web/reader/reader-text-semantics.js",
            "hoshi-web/reader/reader-dom-text.js",
            "hoshi-web/reader/reader-media-semantics.js",
            "hoshi-web/reader/highlights.js",
            "hoshi-web/reader/reader.css",
            "hoshi-web/popup/popup.js",
            "hoshi-web/popup/popup.css",
            "hoshi-web/popup/iframe.html",
            "hoshi-web/popup/reader-popup-host.js",
            "hoshi-web/popup/icons/close.svg",
        )

        assets.forEach { path ->
            val file = listOf(
                File("app/src/main/assets/$path"),
                File("src/main/assets/$path"),
            ).firstOrNull(File::isFile)
                ?: File("app/src/main/assets/$path")
            assertTrue("$path should exist", file.isFile)
            assertTrue("$path should not be empty", file.length() > 0)
        }
    }

    @Test
    fun generatedReaderCssDoesNotExposeTemplatePlaceholders() {
        val css = ReaderContentStyles.css()

        assertFalse(css.contains("__HOSHI_"))
    }

    @Test
    fun onlyExplicitReaderFontFacesArePreloaded() {
        val settings = ReaderSettings(selectedFont = "Custom Font")
        val custom = ReaderContentStyles.css(settings, fontFaceUrl = "https://appassets.androidplatform.net/fonts/custom.ttf")
        assertTrue(custom.contains("--hoshi-preload-face: 'Custom Font';"))
        val system = ReaderContentStyles.css(settings)
        assertTrue(system.contains("--hoshi-preload-face: ;"))
        val publisher = ReaderContentStyles.css(settings.copy(selectedFont = ReaderFontManager.publisherFont),
            fontFaceUrl = "https://appassets.androidplatform.net/fonts/custom.ttf")
        assertTrue(publisher.contains("--hoshi-preload-face: ;"))
    }
}
