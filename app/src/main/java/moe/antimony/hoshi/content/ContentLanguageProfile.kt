package moe.antimony.hoshi.content

import androidx.annotation.StringRes
import moe.antimony.hoshi.R

class ContentLanguageProfile private constructor(
    val id: String,
    @param:StringRes val displayNameRes: Int,
    val dictionaryLanguageId: String,
    val htmlLang: String,
    val composeLocaleTag: String,
    val inputLocaleTag: String,
    val webViewFontFamilyCss: String,
    val readerSerifFontFamilyCss: String,
    val readerSansSerifFontFamilyCss: String,
) {
    companion object {
        const val JapaneseLanguageId = "ja"
        const val EnglishLanguageId = "en"
        const val ChineseLanguageId = "zh"
        const val KoreanLanguageId = "ko"

        val Japanese: ContentLanguageProfile = ContentLanguageProfile(
            id = JapaneseLanguageId,
            displayNameRes = R.string.profile_language_japanese,
            dictionaryLanguageId = JapaneseLanguageId,
            htmlLang = "ja",
            composeLocaleTag = "ja-JP",
            inputLocaleTag = "ja-JP",
            webViewFontFamilyCss = """"Noto Sans CJK JP", "NotoSansCJKJP-Regular", "SECCJKjp-Regular", sans-serif""",
            readerSerifFontFamilyCss = "'Noto Serif CJK JP', 'NotoSerifCJKjp-Regular', serif",
            readerSansSerifFontFamilyCss = "'Noto Sans CJK JP', 'NotoSansCJKJP-Regular', sans-serif",
        )

        val English: ContentLanguageProfile = ContentLanguageProfile(
            id = EnglishLanguageId,
            displayNameRes = R.string.profile_language_english,
            dictionaryLanguageId = EnglishLanguageId,
            htmlLang = "en",
            composeLocaleTag = "en-US",
            inputLocaleTag = "en-US",
            webViewFontFamilyCss = """"Roboto", "Noto Sans", Arial, sans-serif""",
            readerSerifFontFamilyCss = "'Noto Serif', Georgia, serif",
            readerSansSerifFontFamilyCss = "'Roboto', 'Noto Sans', Arial, sans-serif",
        )

        val Chinese: ContentLanguageProfile = ContentLanguageProfile(
            id = ChineseLanguageId,
            displayNameRes = R.string.profile_language_chinese,
            dictionaryLanguageId = ChineseLanguageId,
            htmlLang = "zh",
            composeLocaleTag = "zh-CN",
            inputLocaleTag = "zh-CN",
            webViewFontFamilyCss = "'Noto Sans CJK SC', sans-serif",
            readerSerifFontFamilyCss = "'Noto Serif CJK SC', serif",
            readerSansSerifFontFamilyCss = "'Noto Sans CJK SC', sans-serif",
        )

        val Korean: ContentLanguageProfile = ContentLanguageProfile(
            id = KoreanLanguageId,
            displayNameRes = R.string.profile_language_korean,
            dictionaryLanguageId = KoreanLanguageId,
            htmlLang = "ko",
            composeLocaleTag = "ko-KR",
            inputLocaleTag = "ko-KR",
            webViewFontFamilyCss = "'Noto Sans CJK KR', sans-serif",
            readerSerifFontFamilyCss = "'Noto Serif CJK KR', serif",
            readerSansSerifFontFamilyCss = "'Noto Sans CJK KR', sans-serif",
        )

        val Default: ContentLanguageProfile = Japanese
        val Supported: List<ContentLanguageProfile> = listOf(Japanese, English, Chinese, Korean)

        fun fromDictionaryLanguageId(languageId: String?): ContentLanguageProfile? =
            Supported.firstOrNull { it.dictionaryLanguageId == languageId }
    }
}
