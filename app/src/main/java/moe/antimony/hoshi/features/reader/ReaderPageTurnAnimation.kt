package moe.antimony.hoshi.features.reader

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.RectF
import android.view.View
import android.view.animation.DecelerateInterpolator
import kotlin.math.sqrt

internal fun ReaderSettings.shouldAnimatePageTurns(): Boolean =
    pageTurnAnimation && !eInkMode && viewMode == ReaderViewMode.Paginated

internal fun readerPageTurnSign(verticalWriting: Boolean, direction: ReaderNavigationDirection): Int =
    if (verticalWriting == (direction == ReaderNavigationDirection.Forward)) 1 else -1

/** Keeps the old page visible until WebView confirms that the destination can be drawn.
 * Only pixels move: DOM pagination, selection coordinates and progress stay on whole pages.
 */
internal class ReaderPageTurnAnimation(
    private val view: View,
    private val drawPage: (Canvas) -> Unit,
) {
    var enabled = false
        set(value) {
            if (!value) cancel()
            field = value
        }
    var verticalWriting = true
    var backgroundColor = android.graphics.Color.WHITE
    private var snapshot: Bitmap? = null
    private var animator: ValueAnimator? = null
    private var fraction = 0f
    private var sign = 1
    private var generation = 0L
    private var restoringChapter = false
    private val timeout = Runnable { cancel() }

    val isWaiting: Boolean get() = snapshot != null && animator == null

    fun holdForRestore(): Boolean {
        restoringChapter = isWaiting
        return restoringChapter
    }

    fun begin(direction: ReaderNavigationDirection): Long {
        if (!enabled || !ValueAnimator.areAnimatorsEnabled() || view.alpha < 1f ||
            view.width <= 0 || view.height <= 0
        ) {
            cancel()
            return generation
        }
        // Coalesce quick requests while the renderer is catching up; don't capture a stale frame.
        if (!isWaiting) {
            cancel()
            snapshot = try {
                val scale = sqrt(4_000_000.0 / (view.width.toDouble() * view.height)).coerceAtMost(1.0).toFloat()
                Bitmap.createBitmap(
                    (view.width * scale).toInt().coerceAtLeast(1),
                    (view.height * scale).toInt().coerceAtLeast(1),
                    Bitmap.Config.ARGB_8888,
                ).also {
                    val canvas = Canvas(it)
                    canvas.drawColor(backgroundColor)
                    canvas.scale(scale, scale)
                    drawPage(canvas)
                }
            } catch (_: OutOfMemoryError) {
                null // Animation is optional, including on memory-constrained readers.
            } catch (_: RuntimeException) {
                null // Some WebView content cannot be captured on a software canvas.
            }
        }
        generation += 1
        fraction = 0f
        sign = readerPageTurnSign(verticalWriting, direction)
        view.removeCallbacks(timeout)
        view.postDelayed(timeout, 2500L)
        view.invalidate()
        return generation
    }

    fun ready(ticket: Long = generation) {
        if (ticket != generation || !isWaiting) return
        restoringChapter = false
        view.removeCallbacks(timeout)
        if (!ValueAnimator.areAnimatorsEnabled()) {
            cancel()
            return
        }
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 180L
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                fraction = it.animatedValue as Float
                view.invalidate()
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    if (ticket == generation) cancel()
                }
            })
            start()
        }
    }

    fun cancel(ticket: Long = generation) {
        if (ticket != generation) return
        if (restoringChapter) view.alpha = 0f
        restoringChapter = false
        generation += 1
        animator?.removeAllListeners()
        animator?.cancel()
        animator = null
        // Do not recycle a bitmap that may still be referenced by the render thread.
        snapshot = null
        view.removeCallbacks(timeout)
        view.invalidate()
    }

    fun draw(canvas: Canvas) {
        val old = snapshot
        if (old == null) {
            drawPage(canvas)
            return
        }
        val save = canvas.save()
        canvas.clipRect(0, 0, view.width, view.height)
        canvas.drawColor(backgroundColor)
        if (animator != null) {
            val incoming = canvas.save()
            canvas.translate(-sign * view.width * (1f - fraction), 0f)
            drawPage(canvas)
            canvas.restoreToCount(incoming)
        }
        val offset = sign * view.width * fraction
        canvas.drawBitmap(old, null, RectF(offset, 0f, offset + view.width, view.height.toFloat()), null)
        canvas.restoreToCount(save)
    }
}
