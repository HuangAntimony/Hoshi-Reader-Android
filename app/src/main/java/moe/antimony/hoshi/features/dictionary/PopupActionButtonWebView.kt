package moe.antimony.hoshi.features.dictionary

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Looper
import android.util.AttributeSet
import android.view.MotionEvent
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
    }

    override fun scrollBy(x: Int, y: Int) {
        super.scrollBy(0, y)
    }

    override fun onScrollChanged(left: Int, top: Int, oldLeft: Int, oldTop: Int) {
        super.onScrollChanged(left, top, oldLeft, oldTop)
        if (scrollX != 0) {
            super.scrollTo(0, scrollY)
        }
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

    private val PopupButtonFrame.iconResId: Int
        get() = when (kind) {
            PopupButtonKind.Audio -> if (state == PopupButtonState.Error) {
                R.drawable.ic_popup_audio_error
            } else {
                R.drawable.ic_popup_audio
            }
            PopupButtonKind.Mine -> if (state == PopupButtonState.Duplicate) {
                R.drawable.ic_popup_mine_duplicate
            } else {
                R.drawable.ic_popup_mine
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
