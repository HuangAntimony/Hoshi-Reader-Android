package moe.antimony.hoshi.features.settings

import android.content.Context
import android.content.res.Configuration
import android.os.LocaleList
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import java.util.Locale

enum class AppLanguageMode(val languageTag: String?) {
    System(languageTag = null),
    English(languageTag = "en"),
    SimplifiedChinese(languageTag = "zh-CN"),
}

class AppLanguageRepository(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(PreferencesName, Context.MODE_PRIVATE)

    fun load(): AppLanguageMode =
        preferences.getString(LanguageModeKey, null)
            ?.let { stored -> AppLanguageMode.entries.firstOrNull { it.name == stored } }
            ?: AppLanguageMode.System

    fun save(mode: AppLanguageMode) {
        preferences.edit().putString(LanguageModeKey, mode.name).apply()
    }

    private companion object {
        const val PreferencesName = "app-language"
        const val LanguageModeKey = "languageMode"
    }
}

@Composable
fun AppLanguageResources(
    mode: AppLanguageMode,
    content: @Composable () -> Unit,
) {
    val baseContext = LocalContext.current
    val systemConfiguration = LocalConfiguration.current
    val localizedContext = remember(baseContext, systemConfiguration, mode) {
        baseContext.withAppLanguage(mode)
    }
    CompositionLocalProvider(
        LocalResources provides localizedContext.resources,
        content = content,
    )
}

internal fun Context.withAppLanguage(mode: AppLanguageMode): Context {
    val languageTag = mode.languageTag ?: return this
    val locale = Locale.forLanguageTag(languageTag)
    val configuration = Configuration(resources.configuration).apply {
        setLocales(LocaleList(locale))
    }
    return createConfigurationContext(configuration)
}
