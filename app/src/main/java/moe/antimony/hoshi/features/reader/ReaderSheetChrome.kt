package moe.antimony.hoshi.features.reader

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.contentColorFor
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlinx.coroutines.launch
import moe.antimony.hoshi.ui.theme.LocalHoshiEInkMode

internal const val ReaderMediumSheetHeightFraction = 0.5f
internal const val ReaderExpandedSheetHeightFraction = 0.75f
private val ReaderBottomPanelSettleThreshold = 96.dp

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
internal fun ReaderBottomPanel(
    sheetStyle: ReaderSheetStyle,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit,
) {
    BackHandler(onBack = onDismiss)
    val scope = rememberCoroutineScope()
    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val density = LocalDensity.current
        val maxHeightPx = with(density) { maxHeight.toPx() }
        val expandedHeightPx = readerPanelExpandedHeight(maxHeightPx)
        val partialHeightPx = maxHeightPx * ReaderMediumSheetHeightFraction
        val settleThresholdPx = with(density) { ReaderBottomPanelSettleThreshold.toPx() }
        val panelHeightPx = remember(maxHeightPx) { Animatable(partialHeightPx) }
        LaunchedEffect(maxHeightPx) {
            panelHeightPx.snapTo(partialHeightPx)
        }
        val panelHeight = with(density) { panelHeightPx.value.toDp() }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(sheetStyle.scrimColor)
                .clickable(onClick = onDismiss),
        )
        Surface(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .height(panelHeight),
            shape = BottomSheetDefaults.ExpandedShape,
            color = sheetStyle.containerColor,
            contentColor = sheetStyle.contentColor,
            tonalElevation = 0.dp,
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                ReaderSheetDragHandle(
                    sheetStyle = sheetStyle,
                    modifier = Modifier
                        .fillMaxWidth()
                        .pointerInput(maxHeightPx, partialHeightPx, onDismiss) {
                            var startHeight = 0f
                            var totalDrag = 0f
                            detectVerticalDragGestures(
                                onDragStart = {
                                    startHeight = panelHeightPx.value
                                    totalDrag = 0f
                                },
                                onDragCancel = {
                                    scope.launch {
                                        panelHeightPx.animateTo(
                                            targetValue = nearestReaderPanelHeight(
                                                currentHeight = panelHeightPx.value,
                                                partialHeight = partialHeightPx,
                                                maxHeight = expandedHeightPx,
                                            ),
                                            animationSpec = tween(durationMillis = 180),
                                        )
                                    }
                                },
                                onDragEnd = {
                                    val target = readerPanelSettleTarget(
                                        currentHeight = panelHeightPx.value,
                                        startHeight = startHeight,
                                        totalDrag = totalDrag,
                                        partialHeight = partialHeightPx,
                                        maxHeight = expandedHeightPx,
                                        threshold = settleThresholdPx,
                                    )
                                    if (target == null) {
                                        onDismiss()
                                    } else {
                                        scope.launch {
                                            panelHeightPx.animateTo(
                                                targetValue = target,
                                                animationSpec = tween(durationMillis = 180),
                                            )
                                        }
                                    }
                                },
                                onVerticalDrag = { change, dragAmount ->
                                    change.consume()
                                    totalDrag += dragAmount
                                    scope.launch {
                                        panelHeightPx.snapTo(
                                            (panelHeightPx.value - dragAmount)
                                                .coerceIn(0f, expandedHeightPx),
                                        )
                                    }
                                },
                            )
                        },
                )
                content()
            }
        }
    }
}

internal fun readerPanelSettleTarget(
    currentHeight: Float,
    @Suppress("UNUSED_PARAMETER") startHeight: Float,
    totalDrag: Float,
    partialHeight: Float,
    maxHeight: Float,
    threshold: Float,
): Float? {
    if (currentHeight < partialHeight - threshold && totalDrag > threshold) return null
    if (totalDrag <= -threshold) return maxHeight
    if (totalDrag >= threshold) return partialHeight
    return nearestReaderPanelHeight(currentHeight, partialHeight, maxHeight)
}

internal fun readerPanelExpandedHeight(containerHeight: Float): Float =
    containerHeight * ReaderExpandedSheetHeightFraction

private fun nearestReaderPanelHeight(
    currentHeight: Float,
    partialHeight: Float,
    maxHeight: Float,
): Float =
    if (abs(currentHeight - maxHeight) < abs(currentHeight - partialHeight)) {
        maxHeight
    } else {
        partialHeight
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
