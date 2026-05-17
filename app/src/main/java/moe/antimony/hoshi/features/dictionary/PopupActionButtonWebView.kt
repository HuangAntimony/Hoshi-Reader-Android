package moe.antimony.hoshi.features.dictionary

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Rect
import android.os.Looper
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import android.widget.ImageButton
import android.widget.ImageView
import moe.antimony.hoshi.R

internal class PopupActionButtonWebView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : WebView(context, attrs) {
    private val buttons = mutableMapOf<String, ImageButton>()
    private var actionButtonTint = ColorStateList.valueOf(DefaultActionButtonTint)

    fun setActionButtonTint(color: Int) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            post { setActionButtonTint(color) }
            return
        }
        actionButtonTint = ColorStateList.valueOf(color)
        buttons.values.forEach { it.imageTintList = actionButtonTint }
    }

    fun updateActionButtonFrames(frames: List<PopupButtonFrame>) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            post { updateActionButtonFrames(frames) }
            return
        }

        val activeKeys = frames.mapTo(mutableSetOf()) { it.key }
        frames.forEach(::updateActionButton)

        buttons.keys
            .filterNot(activeKeys::contains)
            .forEach { key ->
                removeView(buttons.remove(key))
            }
    }

    fun clearActionButtons() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            post(::clearActionButtons)
            return
        }
        buttons.values.forEach(::removeView)
        buttons.clear()
    }

    override fun scrollTo(x: Int, y: Int) {
        super.scrollTo(0, y)
        refreshActionButtonClipping()
    }

    override fun scrollBy(x: Int, y: Int) {
        super.scrollBy(0, y)
        refreshActionButtonClipping()
    }

    override fun onScrollChanged(left: Int, top: Int, oldLeft: Int, oldTop: Int) {
        super.onScrollChanged(left, top, oldLeft, oldTop)
        if (scrollX != 0) {
            super.scrollTo(0, scrollY)
        }
        refreshActionButtonClipping()
    }

    override fun onSizeChanged(width: Int, height: Int, oldWidth: Int, oldHeight: Int) {
        super.onSizeChanged(width, height, oldWidth, oldHeight)
        refreshActionButtonClipping()
    }

    private fun updateActionButton(frame: PopupButtonFrame) {
        val button = buttons.getOrPut(frame.key) {
            createActionButton(frame).also { addView(it) }
        }

        button.tag = frame.key
        button.contentDescription = frame.contentDescription
        button.isEnabled = frame.enabled
        button.alpha = if (frame.enabled) EnabledAlpha else DisabledAlpha
        button.imageTintList = actionButtonTint
        button.setImageResource(frame.iconResId)
        button.setOnClickListener {
            evaluateJavascript(frame.kind.actionScript(frame.entryIndex), null)
        }

        val width = frame.width.cssPxToAndroidPx()
        val height = frame.height.cssPxToAndroidPx()
        val currentParams = button.layoutParams
        if (currentParams == null) {
            button.layoutParams = ViewGroup.LayoutParams(width, height)
        } else if (currentParams.width != width || currentParams.height != height) {
            currentParams.width = width
            currentParams.height = height
            button.layoutParams = currentParams
        }
        button.x = frame.x.cssPxToAndroidPx().toFloat()
        button.y = frame.y.cssPxToAndroidPx().toFloat()
        refreshActionButtonClipping()
        button.bringToFront()
    }

    private fun createActionButton(frame: PopupButtonFrame): ImageButton =
        ImageButton(context).apply {
            tag = frame.key
            setBackgroundColor(Color.TRANSPARENT)
            scaleType = ImageView.ScaleType.CENTER
            isFocusable = false
            setPadding(0, 0, 0, 0)
            setOnTouchListener { view, event ->
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> view.parent?.requestDisallowInterceptTouchEvent(true)
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> view.parent?.requestDisallowInterceptTouchEvent(false)
                }
                false
            }
        }

    private fun Double.cssPxToAndroidPx(): Int =
        (this * resources.displayMetrics.density).toInt().coerceAtLeast(1)

    private fun refreshActionButtonClipping() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            post(::refreshActionButtonClipping)
            return
        }
        val viewportLeft = scrollX
        val viewportTop = scrollY
        val viewportRight = scrollX + width
        val viewportBottom = scrollY + height
        buttons.values.forEach { button ->
            val buttonWidth = button.contentWidth()
            val buttonHeight = button.contentHeight()
            if (buttonWidth <= 0 || buttonHeight <= 0) {
                button.visibility = View.INVISIBLE
                button.clipBounds = null
                return@forEach
            }

            val buttonLeft = button.x.toInt()
            val buttonTop = button.y.toInt()
            val clipLeft = (viewportLeft - buttonLeft).coerceIn(0, buttonWidth)
            val clipTop = (viewportTop - buttonTop).coerceIn(0, buttonHeight)
            val clipRight = (viewportRight - buttonLeft).coerceIn(0, buttonWidth)
            val clipBottom = (viewportBottom - buttonTop).coerceIn(0, buttonHeight)

            if (clipLeft >= clipRight || clipTop >= clipBottom) {
                button.visibility = View.INVISIBLE
                button.clipBounds = null
                return@forEach
            }

            button.visibility = View.VISIBLE
            button.clipBounds =
                if (clipLeft == 0 && clipTop == 0 && clipRight == buttonWidth && clipBottom == buttonHeight) {
                    null
                } else {
                    Rect(clipLeft, clipTop, clipRight, clipBottom)
                }
        }
    }

    private fun View.contentWidth(): Int =
        width.takeIf { it > 0 } ?: layoutParams?.width?.takeIf { it > 0 } ?: 0

    private fun View.contentHeight(): Int =
        height.takeIf { it > 0 } ?: layoutParams?.height?.takeIf { it > 0 } ?: 0

    private val PopupButtonFrame.iconResId: Int
        get() = when (kind) {
            PopupButtonKind.Audio -> if (state == PopupButtonState.Error) {
                R.drawable.ic_material_rounded_volume_off
            } else {
                R.drawable.ic_material_rounded_volume_up
            }
            PopupButtonKind.Mine -> if (state == PopupButtonState.Duplicate) {
                R.drawable.ic_material_rounded_check_box
            } else {
                R.drawable.ic_material_rounded_add_box
            }
        }

    private val PopupButtonFrame.contentDescription: String
        get() = when (kind) {
            PopupButtonKind.Audio -> "Play Audio"
            PopupButtonKind.Mine -> "Add to Anki"
        }

    private companion object {
        val DefaultActionButtonTint: Int = Color.argb(220, 60, 60, 67)
        const val EnabledAlpha = 0.85f
        const val DisabledAlpha = 0.55f
    }
}
