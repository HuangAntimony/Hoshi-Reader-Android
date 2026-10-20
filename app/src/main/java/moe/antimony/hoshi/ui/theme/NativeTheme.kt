package moe.antimony.hoshi.ui.theme

import android.content.ComponentCallbacks
import android.content.Context
import android.content.res.Configuration
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import moe.antimony.hoshi.features.display.AppDisplaySettings
import moe.antimony.hoshi.features.display.NativeThemePolicy
import moe.antimony.hoshi.features.display.nativeThemePolicy

/** Actual device appearance, never an Activity configuration overridden by AppCompat. */
val LocalSystemDarkTheme = staticCompositionLocalOf { false }

@Singleton
class SystemThemeObserver @Inject constructor(@ApplicationContext private val context: Context) :
    ComponentCallbacks, DefaultLifecycleObserver {
    private val mutableDark = MutableStateFlow(readSystemDark())
    val dark = mutableDark.asStateFlow()

    init {
        context.registerComponentCallbacks(this)
        ProcessLifecycleOwner.get().lifecycle.addObserver(this)
    }

    private fun readSystemDark() = context.resources.configuration.uiMode and
        Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES

    override fun onConfigurationChanged(newConfig: Configuration) { mutableDark.value = readSystemDark() }
    override fun onStart(owner: LifecycleOwner) { mutableDark.value = readSystemDark() }
    override fun onLowMemory() = Unit
}

/** Gate only the initial content. Subsequent theme changes retain the composed navigation/View tree. */
@Composable
internal fun nativeThemeReady(settings: AppDisplaySettings?, systemDark: Boolean): Boolean {
    var ready by remember { mutableStateOf(false) }
    val policy = settings?.let { nativeThemePolicy(it, systemDark) }
    DisposableEffect(policy) {
        if (policy != null) {
            AppCompatDelegate.setDefaultNightMode(when (policy) {
                NativeThemePolicy.FollowSystem -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
                NativeThemePolicy.Light -> AppCompatDelegate.MODE_NIGHT_NO
                NativeThemePolicy.Dark -> AppCompatDelegate.MODE_NIGHT_YES
            })
            ready = true
        }
        onDispose { }
    }
    return ready
}
