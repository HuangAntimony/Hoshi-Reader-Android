package moe.antimony.hoshi.ui

import java.util.concurrent.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class InitiallyCenteredLazyListTest {
    @Test
    fun positioningCancellationStillFinishesTheVisibilityGate() = runBlocking {
        var finished = false

        val failure: Throwable? = try {
            finishInitialLazyListPositioning(
                position = { throw CancellationException("cancelled") },
                onFinished = { finished = true },
            )
            null
        } catch (error: Throwable) {
            error
        }

        assertEquals(CancellationException::class.java, failure?.javaClass)
        assertEquals(true, finished)
    }

    @Test
    fun contentStaysHiddenUntilTheInitialTargetIsCentered() {
        val targetCapture = InitialLazyListTargetCapture(isCaptured = true, targetIndex = 3)

        assertEquals(
            false,
            initiallyCenteredLazyListContentVisible(targetCapture, hasFinishedPositioning = false),
        )
        assertEquals(
            true,
            initiallyCenteredLazyListContentVisible(targetCapture, hasFinishedPositioning = true),
        )
    }

    @Test
    fun contentIsVisibleOnceAnAbsentInitialTargetIsCaptured() {
        assertEquals(
            true,
            initiallyCenteredLazyListContentVisible(
                targetCapture = InitialLazyListTargetCapture(isCaptured = true, targetIndex = null),
                hasFinishedPositioning = false,
            ),
        )
    }

    @Test
    fun initialTargetWaitsForTheListThenKeepsTheFirstTarget() {
        val pendingCapture = InitialLazyListTargetCapture()
        assertEquals(
            pendingCapture,
            captureInitialLazyListTarget(
                currentCapture = pendingCapture,
                requestedTargetIndex = 3,
                itemCount = 0,
            ),
        )
        val completedCapture = InitialLazyListTargetCapture(isCaptured = true, targetIndex = 3)
        assertEquals(
            completedCapture,
            captureInitialLazyListTarget(
                currentCapture = pendingCapture,
                requestedTargetIndex = 3,
                itemCount = 10,
            ),
        )
        assertEquals(
            completedCapture,
            captureInitialLazyListTarget(
                currentCapture = completedCapture,
                requestedTargetIndex = 4,
                itemCount = 10,
            ),
        )
    }

    @Test
    fun missingInitialTargetDoesNotAllowALaterTargetToStealTheScrollPosition() {
        val initialCapture = captureInitialLazyListTarget(
            currentCapture = InitialLazyListTargetCapture(),
            requestedTargetIndex = null,
            itemCount = 10,
        )

        assertEquals(
            InitialLazyListTargetCapture(isCaptured = true, targetIndex = null),
            captureInitialLazyListTarget(
                currentCapture = initialCapture,
                requestedTargetIndex = 3,
                itemCount = 10,
            ),
        )
    }

    @Test
    fun centerScrollDeltaMovesItemsTowardTheViewportCenter() {
        assertEquals(
            -180f,
            lazyListCenterScrollDelta(
                viewportStartOffset = 20,
                viewportEndOffset = 620,
                itemOffset = 100,
                itemSize = 80,
            ),
            0f,
        )
        assertEquals(
            230f,
            lazyListCenterScrollDelta(
                viewportStartOffset = 20,
                viewportEndOffset = 620,
                itemOffset = 500,
                itemSize = 100,
            ),
            0f,
        )
        assertEquals(
            0f,
            lazyListCenterScrollDelta(
                viewportStartOffset = 20,
                viewportEndOffset = 620,
                itemOffset = 280,
                itemSize = 80,
            ),
            0f,
        )
    }
}
