package moe.antimony.hoshi.features.settings

import androidx.annotation.MainThread
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat

enum class AppLanguageMode(val languageTags: String) {
    System(languageTags = ""),
    English(languageTags = "en-US"),
    SimplifiedChinese(languageTags = "zh-CN"),
    ;

    companion object {
        fun fromLanguageTags(languageTags: String): AppLanguageMode {
            val firstTag = languageTags.split(',').firstOrNull()?.trim().orEmpty()
            return entries.firstOrNull { it.languageTags == firstTag } ?: System
        }
    }
}

fun currentAppLanguageMode(): AppLanguageMode =
    AppLanguageMode.fromLanguageTags(AppCompatDelegate.getApplicationLocales().toLanguageTags())

@MainThread
fun setAppLanguageMode(mode: AppLanguageMode) {
    val locales = LocaleListCompat.forLanguageTags(mode.languageTags)
    if (locales != AppCompatDelegate.getApplicationLocales()) {
        AppCompatDelegate.setApplicationLocales(locales)
    }
}
