package moe.antimony.hoshi.features.tts

import java.util.Locale
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TtsLocaleTest {
    @Test fun recognizesJapaneseWithTwoOrThreeLetterLanguageCodes() {
        listOf(Locale.JAPANESE, Locale.JAPAN, Locale("jpn", "JPN", "f00"), Locale("jpn", "JPN", "m00"))
            .forEach { assertTrue(it.toString(), isJapaneseTtsLocale(it)) }
    }

    @Test fun rejectsOtherLanguagesEvenWithJapaneseCountryOrVariant() {
        listOf(Locale.ENGLISH, Locale.CHINESE, Locale("eng", "JPN"), Locale("zho", "CHN", "ja"), Locale.ROOT)
            .forEach { assertFalse(it.toString(), isJapaneseTtsLocale(it)) }
    }
}
