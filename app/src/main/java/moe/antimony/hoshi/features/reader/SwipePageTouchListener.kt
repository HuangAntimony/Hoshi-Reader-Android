package moe.antimony.hoshi.features.reader

import android.content.Context
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs

abstract class SwipePageTouchListener(context: Context) : View.OnTouchListener {
    private val detector = GestureDetector(context, GestureListener())

    override fun onTouch(view: View, event: MotionEvent): Boolean {
        detector.onTouchEvent(event)
        return false
    }

    open fun onLeftSwipe() = Unit
    open fun onRightSwipe() = Unit
    open fun onTap(x: Float, y: Float) = Unit

    private inner class GestureListener : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(event: MotionEvent): Boolean = true

        override fun onSingleTapUp(event: MotionEvent): Boolean {
            onTap(event.x, event.y)
            return true
        }

        override fun onFling(
            downEvent: MotionEvent?,
            moveEvent: MotionEvent,
            velocityX: Float,
            velocityY: Float,
        ): Boolean {
            val start = downEvent ?: return false
            val dx = moveEvent.x - start.x
            if (abs(dx) < MIN_DISTANCE || abs(dx) < abs(moveEvent.y - start.y)) {
                return false
            }
            if (dx < 0) onLeftSwipe() else onRightSwipe()
            return true
        }
    }

    private companion object {
        const val MIN_DISTANCE = 72f
    }
}
