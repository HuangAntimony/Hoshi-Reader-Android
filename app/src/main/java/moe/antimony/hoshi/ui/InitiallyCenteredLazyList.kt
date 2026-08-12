package moe.antimony.hoshi.ui

import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first

@Composable
internal fun rememberInitiallyCenteredLazyListState(
    targetIndex: Int?,
    itemCount: Int,
): InitiallyCenteredLazyListState {
    val listState = rememberLazyListState()
    var targetCapture by remember { mutableStateOf(InitialLazyListTargetCapture()) }
    var hasFinishedPositioning by remember { mutableStateOf(false) }

    LaunchedEffect(targetIndex, itemCount) {
        targetCapture = captureInitialLazyListTarget(
            currentCapture = targetCapture,
            requestedTargetIndex = targetIndex,
            itemCount = itemCount,
        )
    }
    LaunchedEffect(listState, targetCapture) {
        val index = targetCapture.targetIndex ?: return@LaunchedEffect
        finishInitialLazyListPositioning(
            position = {
                listState.scrollToItem(index)
                val item = snapshotFlow {
                    listState.layoutInfo.visibleItemsInfo.firstOrNull { it.index == index }
                }.filterNotNull().first()
                val layoutInfo = listState.layoutInfo
                listState.scrollBy(
                    lazyListCenterScrollDelta(
                        viewportStartOffset = layoutInfo.viewportStartOffset,
                        viewportEndOffset = layoutInfo.viewportEndOffset,
                        itemOffset = item.offset,
                        itemSize = item.size,
                    ),
                )
            },
            onFinished = { hasFinishedPositioning = true },
        )
    }
    return InitiallyCenteredLazyListState(
        listState = listState,
        contentVisible = initiallyCenteredLazyListContentVisible(
            targetCapture = targetCapture,
            hasFinishedPositioning = hasFinishedPositioning,
        ),
    )
}

internal data class InitiallyCenteredLazyListState(
    val listState: LazyListState,
    val contentVisible: Boolean,
)

internal data class InitialLazyListTargetCapture(
    val isCaptured: Boolean = false,
    val targetIndex: Int? = null,
)

internal fun captureInitialLazyListTarget(
    currentCapture: InitialLazyListTargetCapture,
    requestedTargetIndex: Int?,
    itemCount: Int,
): InitialLazyListTargetCapture {
    if (currentCapture.isCaptured || itemCount <= 0) return currentCapture
    return InitialLazyListTargetCapture(
        isCaptured = true,
        targetIndex = requestedTargetIndex?.takeIf { it in 0 until itemCount },
    )
}

internal fun initiallyCenteredLazyListContentVisible(
    targetCapture: InitialLazyListTargetCapture,
    hasFinishedPositioning: Boolean,
): Boolean = targetCapture.isCaptured &&
    (targetCapture.targetIndex == null || hasFinishedPositioning)

internal suspend fun finishInitialLazyListPositioning(
    position: suspend () -> Unit,
    onFinished: () -> Unit,
) {
    try {
        position()
    } finally {
        onFinished()
    }
}

internal fun lazyListCenterScrollDelta(
    viewportStartOffset: Int,
    viewportEndOffset: Int,
    itemOffset: Int,
    itemSize: Int,
): Float {
    val viewportCenter = (viewportStartOffset + viewportEndOffset) / 2f
    val itemCenter = itemOffset + itemSize / 2f
    return itemCenter - viewportCenter
}
