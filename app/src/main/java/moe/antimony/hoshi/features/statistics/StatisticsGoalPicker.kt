package moe.antimony.hoshi.features.statistics

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import kotlin.math.abs
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
import moe.antimony.hoshi.R
import moe.antimony.hoshi.ui.asString
import moe.antimony.hoshi.ui.theme.LocalHoshiEInkMode

/** Anchored to the goal link; choosing a value never changes the dashboard's layout. */
@Composable
internal fun StatisticsGoalPicker(
    state: StatisticsTargetSettingsUi,
    onEvent: (StatisticsEvent) -> Unit,
) {
    val margin = with(LocalDensity.current) { 8.dp.roundToPx() }
    val position = remember(margin) { GoalPickerPositionProvider(margin) }
    val settings = state.values
    val type = settings.dailyTargetType
    val eInk = LocalHoshiEInkMode.current
    Popup(
        popupPositionProvider = position,
        onDismissRequest = { onEvent(StatisticsEvent.DismissTargetSettings) },
        properties = PopupProperties(focusable = true),
    ) {
        Surface(
            modifier = Modifier.width(260.dp),
            shape = RoundedCornerShape(28.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            shadowElevation = if (eInk) 0.dp else 8.dp,
            border = if (eInk) BorderStroke(1.dp, MaterialTheme.colorScheme.outline) else null,
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(stringResource(R.string.statistics_goal_picker_title), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(
                    stringResource(if (type == DailyTargetType.Characters) R.string.statistics_goal_characters_per_day else R.string.statistics_goal_minutes_per_day),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                StatisticsSegmentedControl(
                    options = listOf(
                        StatisticsSegmentedOption(DailyTargetType.Duration, stringResource(R.string.statistics_target_type_duration)),
                        StatisticsSegmentedOption(DailyTargetType.Characters, stringResource(R.string.statistics_target_type_characters)),
                    ),
                    selected = type,
                    onSelect = { onEvent(StatisticsEvent.SelectDailyTargetType(it)) },
                )
                key(type) {
                    val values = remember(type) {
                        when (type) {
                            DailyTargetType.Characters -> (StatisticsTargetDefaults.MinDailyCharacterTarget..StatisticsTargetDefaults.MaxDailyCharacterTarget step StatisticsTargetDefaults.DailyCharacterTargetStep).toList()
                            DailyTargetType.Duration -> (StatisticsTargetDefaults.MinDailyDurationTargetMinutes..StatisticsTargetDefaults.MaxDailyDurationTargetMinutes step StatisticsTargetDefaults.DailyDurationTargetStepMinutes).toList()
                        }
                    }
                    GoalValueWheel(
                        values = values,
                        value = if (type == DailyTargetType.Characters) settings.dailyCharacterTarget else settings.dailyDurationTargetMinutes,
                        onSelect = { value ->
                            onEvent(when (type) {
                                DailyTargetType.Characters -> StatisticsEvent.UpdateDailyCharacterTarget(value)
                                DailyTargetType.Duration -> StatisticsEvent.UpdateDailyDurationTargetMinutes(value)
                            })
                        },
                    )
                }
                state.error?.let { Text(it.asString(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
            }
        }
    }
}

@Composable
private fun GoalValueWheel(values: List<Int>, value: Int, onSelect: (Int) -> Unit) {
    val list = rememberLazyListState(initialFirstVisibleItemIndex = values.indexOf(value).coerceAtLeast(0))
    val currentValue by rememberUpdatedState(value)
    val currentOnSelect by rememberUpdatedState(onSelect)
    val scope = rememberCoroutineScope()
    val eInk = LocalHoshiEInkMode.current
    LaunchedEffect(list) {
        snapshotFlow {
            if (list.isScrollInProgress) null else {
                val layout = list.layoutInfo
                val center = (layout.viewportStartOffset + layout.viewportEndOffset) / 2
                layout.visibleItemsInfo.minByOrNull { abs(it.offset + it.size / 2 - center) }?.index
            }
        }.filterNotNull().distinctUntilChanged().collect { index ->
            if (values[index] != currentValue) currentOnSelect(values[index])
        }
    }
    Box(Modifier.fillMaxWidth().height(180.dp).clipToBounds(), contentAlignment = Alignment.Center) {
        Surface(
            modifier = Modifier.fillMaxWidth().height(36.dp),
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHighest,
            border = if (eInk) BorderStroke(1.dp, MaterialTheme.colorScheme.outline) else null,
        ) {}
        LazyColumn(
            state = list,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(vertical = 72.dp),
            flingBehavior = rememberSnapFlingBehavior(list),
        ) {
            items(values.size, key = { values[it] }) { index ->
                Box(
                    modifier = Modifier.fillMaxWidth().height(36.dp)
                        .clickable {
                            // A tap commits immediately, even if the popup closes during centering.
                            if (values[index] != currentValue) currentOnSelect(values[index])
                            scope.launch {
                                if (eInk) list.scrollToItem(index) else list.animateScrollToItem(index)
                            }
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = formatStatisticsGroupedCount(values[index]),
                        style = MaterialTheme.typography.headlineSmall.copy(fontFeatureSettings = "tnum", lineHeight = 32.sp),
                        maxLines = 1,
                        modifier = Modifier.graphicsLayer {
                            val layout = list.layoutInfo
                            val item = layout.visibleItemsInfo.firstOrNull { it.index == index }
                            val center = (layout.viewportStartOffset + layout.viewportEndOffset) / 2f
                            val distance = item?.let { (it.offset + it.size / 2f - center) / it.size } ?: 0f
                            val fraction = (abs(distance) / 2.5f).coerceIn(0f, 1f)
                            alpha = if (eInk) 1f else 1f - fraction * 0.85f
                            scaleX = 1f - fraction * 0.12f
                            scaleY = 1f - fraction * 0.35f
                        },
                    )
                }
            }
        }
    }
}

internal class GoalPickerPositionProvider(private val margin: Int) : PopupPositionProvider {
    override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize, layoutDirection: LayoutDirection, popupContentSize: IntSize): IntOffset {
        val maxX = (windowSize.width - popupContentSize.width - margin).coerceAtLeast(margin)
        val maxY = (windowSize.height - popupContentSize.height - margin).coerceAtLeast(margin)
        val below = anchorBounds.bottom + margin
        val y = if (below <= maxY) below else anchorBounds.top - popupContentSize.height - margin
        return IntOffset(
            x = (anchorBounds.center.x - popupContentSize.width / 2).coerceIn(margin, maxX),
            y = y.coerceIn(margin, maxY),
        )
    }
}
