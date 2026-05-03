package moe.antimony.hoshi.features.reader

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SheetState
import androidx.compose.material3.contentColorFor
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import moe.antimony.hoshi.ui.theme.LocalHoshiEInkMode

internal const val ReaderMediumSheetHeightFraction = 0.5f
private val ReaderMediumSheetHandleReserve = 64.dp
private val ReaderMediumSheetMinimumContentHeight = 240.dp
private val ReaderSheetDismissDragThreshold = 48.dp

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
internal fun Modifier.readerMediumSheetContentHeight(): Modifier {
    val screenHeight = LocalConfiguration.current.screenHeightDp.dp
    val contentHeight = screenHeight * ReaderMediumSheetHeightFraction - ReaderMediumSheetHandleReserve
    return height(
        if (contentHeight < ReaderMediumSheetMinimumContentHeight) {
            ReaderMediumSheetMinimumContentHeight
        } else {
            contentHeight
        },
    )
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
internal fun ReaderSheetDismissDragHandle(
    sheetStyle: ReaderSheetStyle,
    sheetState: SheetState,
    onDismiss: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val thresholdPx = with(LocalDensity.current) { ReaderSheetDismissDragThreshold.toPx() }
    ReaderSheetDragHandle(
        sheetStyle = sheetStyle,
        modifier = Modifier
            .fillMaxWidth()
            .background(sheetStyle.containerColor)
            .pointerInput(sheetState, onDismiss) {
                var downwardDrag = 0f
                detectVerticalDragGestures(
                    onDragStart = { downwardDrag = 0f },
                    onDragCancel = { downwardDrag = 0f },
                    onDragEnd = {
                        if (downwardDrag >= thresholdPx) {
                            scope.launch {
                                sheetState.hide()
                                onDismiss()
                            }
                        }
                        downwardDrag = 0f
                    },
                    onVerticalDrag = { change, dragAmount ->
                        if (dragAmount > 0f) {
                            downwardDrag += dragAmount
                            change.consume()
                        } else {
                            downwardDrag = 0f
                        }
                    },
                )
            },
    )
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
internal fun ReaderSheetDragHandle(
    sheetStyle: ReaderSheetStyle,
    modifier: Modifier = Modifier,
) {
    if (sheetStyle.eInkMode) {
        Column(
            modifier = modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            ReaderSheetTopOutline()
            BottomSheetDefaults.DragHandle(color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    } else {
        Column(
            modifier = modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            BottomSheetDefaults.DragHandle()
        }
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
