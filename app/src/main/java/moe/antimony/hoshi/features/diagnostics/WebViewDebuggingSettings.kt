package moe.antimony.hoshi.features.diagnostics

import android.content.Context
import android.webkit.WebView
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import moe.antimony.hoshi.BuildConfig
import moe.antimony.hoshi.R
import moe.antimony.hoshi.di.MainDispatcher
import moe.antimony.hoshi.features.settings.SettingsLoadState
import moe.antimony.hoshi.ui.UiText
import moe.antimony.hoshi.ui.withErrorDetails

private val Context.diagnosticsSettingsDataStore by preferencesDataStore(name = "diagnostics-settings")

/** App-local diagnostic preference and its process-wide WebView effect. */
@Singleton
internal class WebViewDebuggingSettings internal constructor(
    private val dataStore: DataStore<Preferences>,
    private val mainDispatcher: CoroutineDispatcher,
    private val debugBuild: Boolean,
    private val applyDebugging: (Boolean) -> Unit,
) {
    @Inject constructor(
        @ApplicationContext context: Context,
        @MainDispatcher mainDispatcher: CoroutineDispatcher,
    ) : this(context.diagnosticsSettingsDataStore, mainDispatcher, BuildConfig.DEBUG, WebView::setWebContentsDebuggingEnabled)

    private val mutex = Mutex()
    private val _state = MutableStateFlow<SettingsLoadState<Boolean>>(SettingsLoadState.Loading)
    val state = _state.asStateFlow()
    private var applied: Boolean? = null

    suspend fun initialize() = mutex.withLock {
        if (_state.value is SettingsLoadState.Loaded) return@withLock
        _state.value = SettingsLoadState.Loading
        try {
            val enabled = dataStore.data.first()[EnabledKey] ?: debugBuild
            apply(enabled)
            _state.value = SettingsLoadState.Loaded(enabled)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            _state.value = SettingsLoadState.Failed(UiText.Resource(R.string.settings_load_failed).withErrorDetails(error))
        }
    }

    suspend fun setEnabled(enabled: Boolean): Unit = mutex.withLock {
        val previous = (_state.value as? SettingsLoadState.Loaded)?.value
            ?: error("WebView debugging settings are not loaded")
        if (previous == enabled) return@withLock
        // DataStore can commit independently of its caller's acknowledgement.
        // Once accepted, finish the runtime/write/state transaction even if the
        // Diagnostics screen closes, rather than rolling back a committed choice.
        withContext(NonCancellable) {
            apply(enabled)
            try {
                dataStore.edit { it[EnabledKey] = enabled }
            } catch (error: Exception) {
                apply(previous)
                throw error
            }
            _state.value = SettingsLoadState.Loaded(enabled)
        }
    }

    private suspend fun apply(enabled: Boolean) = withContext(mainDispatcher) {
        // Avoid starting Chromium on ordinary release startup. Debug builds may
        // already have automatic debugging enabled, so explicitly apply their choice.
        if (enabled || debugBuild || applied != null) applyDebugging(enabled)
        applied = enabled
    }

    private companion object {
        val EnabledKey = booleanPreferencesKey("webViewDebuggingEnabled")
    }
}
