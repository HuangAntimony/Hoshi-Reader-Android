package moe.antimony.hoshi.features.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.Flow

@Composable
internal fun <T> Flow<T>.collectAsLoadedSettings(): T? {
    val value by collectAsStateWithLifecycle(initialValue = null)
    return value
}

internal fun settingsContentReady(vararg settings: Any?): Boolean =
    settings.all { it != null }
