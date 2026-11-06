package moe.antimony.hoshi.content

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ContentLanguageProfileTest {
    @Test
    fun chineseAndKoreanResolveTheirOwnTypographyAndInputLocale() {
        listOf(Triple("zh", "zh-CN", "SC"), Triple("ko", "ko-KR", "KR")).forEach { (id, locale, region) ->
            val profile = ContentLanguageProfile.fromDictionaryLanguageId(id)
            org.junit.Assert.assertNotNull("Supported profile $id", profile)
            profile!!
            assertEquals(id, profile.htmlLang)
            assertEquals(locale, profile.composeLocaleTag)
            assertEquals(locale, profile.inputLocaleTag)
            assertTrue(profile.webViewFontFamilyCss.contains("Noto Sans CJK $region"))
            assertTrue(profile.readerSerifFontFamilyCss.contains("Noto Serif CJK $region"))
            assertTrue(profile.readerSansSerifFontFamilyCss.contains("Noto Sans CJK $region"))
        }
    }

    @Test
    fun defaultProfileIsJapaneseAndUsesAndroidFonts() {
        val japanese = ContentLanguageProfile.Default

        assertEquals("ja", japanese.id)
        assertEquals("ja", japanese.dictionaryLanguageId)
        assertEquals("ja", japanese.htmlLang)
        assertEquals("ja-JP", japanese.composeLocaleTag)
        assertEquals("ja-JP", japanese.inputLocaleTag)
        assertTrue(japanese.webViewFontFamilyCss.contains("Noto Sans CJK JP"))
        assertTrue(japanese.readerSerifFontFamilyCss.contains("Noto Serif CJK JP"))
        assertFalse(japanese.webViewFontFamilyCss.contains("Hira" + "gino"))
    }

    @Test
    fun englishProfileUsesEnglishLanguageAndLatinFallbacks() {
        val english = ContentLanguageProfile.English

        assertEquals("en", english.id)
        assertEquals("en", english.dictionaryLanguageId)
        assertEquals("en", english.htmlLang)
        assertEquals("en-US", english.composeLocaleTag)
        assertEquals("en-US", english.inputLocaleTag)
        assertTrue(english.webViewFontFamilyCss.contains("Roboto"))
        assertFalse(english.webViewFontFamilyCss.contains("Noto Sans CJK JP"))
    }

    @Test
    fun supportedLanguagesRejectUnsupportedDictionaryIds() {
        assertEquals(ContentLanguageProfile.Japanese, ContentLanguageProfile.fromDictionaryLanguageId("ja"))
        assertEquals(ContentLanguageProfile.English, ContentLanguageProfile.fromDictionaryLanguageId("en"))
        assertEquals(null, ContentLanguageProfile.fromDictionaryLanguageId("fr"))
    }
}
