package moe.antimony.hoshi.features.wallpaper

import moe.antimony.hoshi.R
import org.junit.Assert.assertEquals
import org.junit.Test

class BookCoverScaleModeOptionsTest {
    @Test
    fun scaleModeOptionsUseFitFillStretchOrderAndLocalizedResources() {
        assertEquals(
            listOf(
                BookCoverScaleModeOption(
                    BookCoverScaleMode.Fit,
                    R.string.book_cover_scale_fit,
                    R.string.book_cover_scale_fit_summary,
                ),
                BookCoverScaleModeOption(
                    BookCoverScaleMode.Fill,
                    R.string.book_cover_scale_fill,
                    R.string.book_cover_scale_fill_summary,
                ),
                BookCoverScaleModeOption(
                    BookCoverScaleMode.Stretch,
                    R.string.book_cover_scale_stretch,
                    R.string.book_cover_scale_stretch_summary,
                ),
            ),
            bookCoverScaleModeOptions(),
        )
    }
}
