package moe.antimony.hoshi.features.statistics

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.LocalOverscrollFactory
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun StatisticsOverscrollDisabled(
    content: @Composable () -> Unit,
) {
    CompositionLocalProvider(LocalOverscrollFactory provides null) {
        content()
    }
}
