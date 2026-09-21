package moe.antimony.hoshi.navigation

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.layout
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.rememberLifecycleOwner

/** Keep the visited tab's native view hierarchy attached without placing or activating it. */
@Composable
internal fun RetainedTabContent(active: Boolean, content: @Composable () -> Unit) {
    var visited by remember { mutableStateOf(false) }
    if (!active && !visited) return
    SideEffect { visited = true }
    val lifecycleOwner = rememberLifecycleOwner(
        maxLifecycle = if (active) Lifecycle.State.RESUMED else Lifecycle.State.CREATED,
    )
    CompositionLocalProvider(LocalLifecycleOwner provides lifecycleOwner) {
        Box(
            Modifier.layout { measurable, constraints ->
                val placeable = measurable.measure(constraints)
                layout(placeable.width, placeable.height) {
                    if (active) placeable.placeRelative(0, 0)
                }
            },
        ) {
            content()
        }
    }
}
