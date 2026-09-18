package moe.antimony.hoshi.features.reader

import android.animation.ValueAnimator
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.view.View
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ReaderPageTurnRenderingTest {
    @Test
    fun pendingTurnsKeepOldPixelsAndIgnoreStaleCallbacks() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            assumeTrue(ValueAnimator.areAnimatorsEnabled())
            val view = View(InstrumentationRegistry.getInstrumentation().targetContext)
            view.layout(0, 0, 100, 80)
            var pageColor = Color.RED
            val transition = ReaderPageTurnAnimation(view) { it.drawColor(pageColor) }
            val output = Bitmap.createBitmap(100, 80, Bitmap.Config.ARGB_8888)
            fun pixel(): Int {
                transition.draw(Canvas(output))
                return output.getPixel(50, 40)
            }
            try {
                transition.enabled = true
                val first = transition.begin(ReaderNavigationDirection.Forward)
                pageColor = Color.BLUE
                val latest = transition.begin(ReaderNavigationDirection.Forward)
                assertTrue(transition.isWaiting)
                assertEquals(Color.RED, pixel())
                transition.ready(first)
                transition.cancel(first)
                assertTrue(transition.isWaiting)
                assertEquals(Color.RED, pixel())
                transition.ready(latest)
                assertFalse(transition.isWaiting)
                transition.cancel()
                assertEquals(Color.BLUE, pixel())
            } finally {
                transition.cancel()
            }
        }
    }

    @Test
    fun cancelDuringChapterRestoreHidesUnrestoredContent() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            assumeTrue(ValueAnimator.areAnimatorsEnabled())
            val view = View(InstrumentationRegistry.getInstrumentation().targetContext)
            view.layout(0, 0, 100, 80)
            val transition = ReaderPageTurnAnimation(view) { it.drawColor(Color.RED) }
            try {
                transition.enabled = true
                val ticket = transition.begin(ReaderNavigationDirection.Forward)
                assertTrue(transition.holdForRestore())
                transition.enabled = false
                transition.ready(ticket)
                assertFalse(transition.isWaiting)
                assertEquals(0f, view.alpha, 0f)
            } finally {
                transition.cancel()
            }
        }
    }
}
