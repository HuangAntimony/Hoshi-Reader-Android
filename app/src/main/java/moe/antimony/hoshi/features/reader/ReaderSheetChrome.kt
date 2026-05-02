package moe.antimony.hoshi.features.reader

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.contentColorFor
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import moe.antimony.hoshi.ui.theme.LocalHoshiEInkMode

@Immutable
internal data class ReaderSheetStyle(
    val containerColor: Color,
    val contentColor: Color,
    val scrimColor: Color,
    val eInkMode: Boolean,
)

@Composable
@OptIn(ExperimentalMaterial3Api::class)
internal fun readerSheetStyle(eInkMode: Boolean = LocalHoshiEInkMode.current): ReaderSheetStyle {
    val containerColor = if (eInkMode) MaterialTheme.colorScheme.surface else BottomSheetDefaults.ContainerColor
    return ReaderSheetStyle(
        containerColor = containerColor,
        contentColor = if (eInkMode) MaterialTheme.colorScheme.onSurface else contentColorFor(containerColor),
        scrimColor = if (eInkMode) Color.Transparent else BottomSheetDefaults.ScrimColor,
        eInkMode = eInkMode,
    )
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
internal fun ReaderSheetDragHandle(sheetStyle: ReaderSheetStyle) {
    if (sheetStyle.eInkMode) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            ReaderSheetTopOutline()
            BottomSheetDefaults.DragHandle(color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    } else {
        BottomSheetDefaults.DragHandle()
    }
}

@Composable
internal fun ReaderSheetTopOutline(modifier: Modifier = Modifier) {
    HorizontalDivider(
        modifier = modifier.fillMaxWidth(),
        thickness = 1.dp,
        color = MaterialTheme.colorScheme.outline,
    )
}
