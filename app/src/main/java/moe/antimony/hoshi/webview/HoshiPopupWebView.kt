package moe.antimony.hoshi.webview

import android.content.Context
import android.util.AttributeSet
import android.webkit.WebView

class HoshiPopupWebView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : WebView(context, attrs) {
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
}
