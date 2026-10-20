package moe.antimony.hoshi.features.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import moe.antimony.hoshi.R
import moe.antimony.hoshi.ui.UiText
import moe.antimony.hoshi.ui.withErrorDetails

sealed interface SettingsLoadState<out T> {
    data object Loading : SettingsLoadState<Nothing>
    data class Loaded<T>(val value: T) : SettingsLoadState<T>
    data class Failed(val message: UiText) : SettingsLoadState<Nothing>
}

internal fun <T, R> SettingsLoadState<T>.map(transform: (T) -> R): SettingsLoadState<R> = when (this) {
    SettingsLoadState.Loading -> SettingsLoadState.Loading
    is SettingsLoadState.Failed -> this
    is SettingsLoadState.Loaded -> SettingsLoadState.Loaded(transform(value))
}

internal fun <A, B, R> combineSettingsLoadStates(
    first: SettingsLoadState<A>,
    second: SettingsLoadState<B>,
    transform: (A, B) -> R,
): SettingsLoadState<R> = when {
    first is SettingsLoadState.Failed -> first
    second is SettingsLoadState.Failed -> second
    first is SettingsLoadState.Loaded && second is SettingsLoadState.Loaded ->
        SettingsLoadState.Loaded(transform(first.value, second.value))
    else -> SettingsLoadState.Loading
}

internal fun <T> Flow<T>.settingsLoadStates(): Flow<SettingsLoadState<T>> =
    map<T, SettingsLoadState<T>> { SettingsLoadState.Loaded(it) }.catch { error ->
        if (error is CancellationException) throw error
        emit(SettingsLoadState.Failed(UiText.Resource(R.string.settings_load_failed).withErrorDetails(error)))
    }

internal data class SettingsLoader<T>(val state: SettingsLoadState<T>, val retry: () -> Unit)

@Composable
internal fun <T> Flow<T>.rememberSettingsLoader(): SettingsLoader<T> {
    var attempt by remember(this) { mutableIntStateOf(0) }
    val state = collectAsSettingsLoadState(attempt)
    return SettingsLoader(state) { attempt++ }
}

internal val SettingsLoadState<*>.isLoaded: Boolean
    get() = this is SettingsLoadState.Loaded<*>

@Composable
internal fun <T> Flow<T>.collectAsSettingsLoadState(retryKey: Int = 0): SettingsLoadState<T> {
    val loadStateFlow = remember(this, retryKey) {
        settingsLoadStates()
    }
    return key(this, retryKey) {
        val state by loadStateFlow.collectAsStateWithLifecycle(initialValue = SettingsLoadState.Loading)
        state
    }
}

internal fun settingsContentReady(vararg settings: Any?): Boolean =
    settings.all { it != null }
