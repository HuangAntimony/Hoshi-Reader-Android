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
import kotlinx.coroutines.launch
import moe.antimony.hoshi.ui.theme.LocalHoshiEInkMode

internal const val ReaderSheetHeightFraction = 0.7f
private val ReaderBottomPanelSettleThreshold = 96.dp

@Immutable
internal data class ReaderSheetDensityMetrics(
    val appearanceSectionSpacingDp: Int,
    val appearanceSectionCornerRadiusDp: Int,
    val appearanceRowVerticalPaddingDp: Int,
    val appearanceWideRowVerticalPaddingDp: Int,
    val appearanceSliderVerticalPaddingDp: Int,
    val appearanceFontRowVerticalPaddingDp: Int,
    val appearanceSegmentedControlHeightDp: Int,
    val appearanceSwitchMinimumInteractiveSizeDp: Int,
    val stepperButtonSizeDp: Int,
    val stepperIconSizeDp: Int,
    val chapterRowVerticalPaddingDp: Int,
    val chapterRowCornerRadiusDp: Int,
    val chapterHeaderCoverWidthDp: Int,
    val chapterHeaderCoverHeightDp: Int,
    val chapterCloseButtonSizeDp: Int,
    val chapterCloseIconSizeDp: Int,
    val statisticsSectionBottomPaddingDp: Int,
    val statisticsRowVerticalPaddingDp: Int,
    val sasayakiRowVerticalPaddingDp: Int,
    val sasayakiSliderVerticalPaddingDp: Int,
)

internal fun readerSheetDensityMetrics(): ReaderSheetDensityMetrics =
    ReaderSheetDensityMetrics(
        appearanceSectionSpacingDp = 10,
        appearanceSectionCornerRadiusDp = 18,
        appearanceRowVerticalPaddingDp = 6,
        appearanceWideRowVerticalPaddingDp = 8,
        appearanceSliderVerticalPaddingDp = 6,
        appearanceFontRowVerticalPaddingDp = 6,
        appearanceSegmentedControlHeightDp = 34,
        appearanceSwitchMinimumInteractiveSizeDp = 0,
        stepperButtonSizeDp = 36,
        stepperIconSizeDp = 20,
        chapterRowVerticalPaddingDp = 8,
        chapterRowCornerRadiusDp = 8,
        chapterHeaderCoverWidthDp = 44,
        chapterHeaderCoverHeightDp = 66,
        chapterCloseButtonSizeDp = 40,
        chapterCloseIconSizeDp = 24,
        statisticsSectionBottomPaddingDp = 12,
        statisticsRowVerticalPaddingDp = 6,
        sasayakiRowVerticalPaddingDp = 6,
        sasayakiSliderVerticalPaddingDp = 6,
    )

@Immutable
internal data class ReaderChapterSheetChrome(
    val showNavigationHeader: Boolean,
    val showBookHeader: Boolean,
    val cacheCoverOutsideLazyList: Boolean,
    val disableListOverscrollEffect: Boolean,
    val useEagerScrollColumn: Boolean,
)

internal fun readerChapterSheetChrome(): ReaderChapterSheetChrome =
    ReaderChapterSheetChrome(
        showNavigationHeader = false,
        showBookHeader = true,
        cacheCoverOutsideLazyList = true,
        disableListOverscrollEffect = true,
        useEagerScrollColumn = true,
    )

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
        val targetHeightPx = readerPanelHeight(maxHeightPx)
        val settleThresholdPx = with(density) { ReaderBottomPanelSettleThreshold.toPx() }
        val panelHeightPx = remember(maxHeightPx) { Animatable(targetHeightPx) }
        LaunchedEffect(maxHeightPx) {
            panelHeightPx.snapTo(targetHeightPx)
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
                        .pointerInput(maxHeightPx, targetHeightPx, onDismiss) {
                            var totalDrag = 0f
                            detectVerticalDragGestures(
                                onDragStart = {
                                    totalDrag = 0f
                                },
                                onDragCancel = {
                                    scope.launch {
                                        panelHeightPx.animateTo(
                                            targetValue = targetHeightPx,
                                            animationSpec = tween(durationMillis = 180),
                                        )
                                    }
                                },
                                onDragEnd = {
                                    val target = readerPanelSettleTarget(
                                        currentHeight = panelHeightPx.value,
                                        totalDrag = totalDrag,
                                        targetHeight = targetHeightPx,
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
                                                .coerceIn(0f, targetHeightPx),
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
    totalDrag: Float,
    targetHeight: Float,
    threshold: Float,
): Float? {
    if (currentHeight < targetHeight - threshold && totalDrag > threshold) return null
    return targetHeight
}

internal fun readerPanelHeight(containerHeight: Float): Float =
    containerHeight * ReaderSheetHeightFraction

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
