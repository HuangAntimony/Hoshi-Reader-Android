package moe.antimony.hoshi.features.reader

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReaderWebAssetsTest {
    @Test
    fun readerWebAssetsExistInNeutralAssetTree() {
        val assets = listOf(
            "hoshi-web/shared/language-ja.js",
            "hoshi-web/shared/selection-ja.js",
            "hoshi-web/shared/selection-en.js",
            "hoshi-web/shared/selection.js",
            "hoshi-web/reader/reader-paginated.js",
            "hoshi-web/reader/reader-continuous.js",
            "hoshi-web/reader/reader-visual-novel.js",
            "hoshi-web/reader/reader-content-stream.js",
            "hoshi-web/reader/reader-range-map.js",
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
}
