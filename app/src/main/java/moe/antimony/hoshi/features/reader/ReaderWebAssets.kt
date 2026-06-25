package moe.antimony.hoshi.features.reader

import android.content.Context
import moe.antimony.hoshi.content.ContentLanguageProfile

internal data class ReaderWebAssets(
    val languageJapaneseJs: String,
    val selectionJapaneseJs: String,
    val selectionEnglishJs: String,
    val selectionJs: String,
    val readerPaginatedJs: String,
    val readerContinuousJs: String,
    val readerVisualNovelJs: String,
    val readerTextSemanticsJs: String,
    val readerDomTextJs: String,
    val readerVnContentStreamJs: String,
    val readerVnRangeMapJs: String,
    val readerSasayakiJs: String,
    val highlightsJs: String,
    val readerCss: String,
) {
    companion object {
        @Volatile
        private var cached: ReaderWebAssets? = null

        fun load(context: Context): ReaderWebAssets =
            cached ?: synchronized(this) {
                cached ?: read(context.applicationContext).also { cached = it }
            }

        private fun read(context: Context): ReaderWebAssets =
            ReaderWebAssets(
                languageJapaneseJs = context.readAsset("hoshi-web/shared/language-ja.js"),
                selectionJapaneseJs = context.readAsset("hoshi-web/shared/selection-ja.js"),
                selectionEnglishJs = context.readAsset("hoshi-web/shared/selection-en.js"),
                selectionJs = context.readAsset("hoshi-web/shared/selection.js"),
                readerPaginatedJs = context.readAsset("hoshi-web/reader/reader-paginated.js"),
                readerContinuousJs = context.readAsset("hoshi-web/reader/reader-continuous.js"),
                readerVisualNovelJs = context.readAsset("hoshi-web/reader/reader-visual-novel.js"),
                readerTextSemanticsJs = context.readAsset("hoshi-web/reader/reader-text-semantics.js"),
                readerDomTextJs = context.readAsset("hoshi-web/reader/reader-dom-text.js"),
                readerVnContentStreamJs = context.readAsset("hoshi-web/reader/reader-vn-content-stream.js"),
                readerVnRangeMapJs = context.readAsset("hoshi-web/reader/reader-vn-range-map.js"),
                readerSasayakiJs = context.readAsset("hoshi-web/reader/reader-sasayaki.js"),
                highlightsJs = context.readAsset("hoshi-web/reader/highlights.js"),
                readerCss = context.readAsset("hoshi-web/reader/reader.css"),
            )

        private fun Context.readAsset(path: String): String =
            assets.open(path)
                .bufferedReader()
                .use { it.readText() }
    }

    fun selectionSupportJs(contentLanguageProfile: ContentLanguageProfile): String =
        when (contentLanguageProfile.dictionaryLanguageId) {
            ContentLanguageProfile.EnglishLanguageId -> "$languageJapaneseJs\n$selectionEnglishJs"
            else -> "$languageJapaneseJs\n$selectionJapaneseJs"
        }
}
