package moe.antimony.hoshi.features.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

internal sealed interface SettingsLoadState<out T> {
    data object Loading : SettingsLoadState<Nothing>
    data class Loaded<T>(val value: T) : SettingsLoadState<T>
}

internal val SettingsLoadState<*>.isLoaded: Boolean
    get() = this is SettingsLoadState.Loaded<*>

@Composable
internal fun <T> Flow<T>.collectAsLoadedSettings(): T? {
    val value by collectAsStateWithLifecycle(initialValue = null)
    return value
}

@Composable
internal fun <T> Flow<T>.collectAsSettingsLoadState(): SettingsLoadState<T> {
    val loadStateFlow = remember(this) {
        map<T, SettingsLoadState<T>> { value -> SettingsLoadState.Loaded(value) }
    }
    val state by loadStateFlow
        .collectAsStateWithLifecycle(initialValue = SettingsLoadState.Loading)
    return state
}

internal fun settingsContentReady(vararg settings: Any?): Boolean =
    settings.all { it != null }
