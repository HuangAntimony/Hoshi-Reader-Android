package moe.antimony.hoshi.features.dictionary

import android.annotation.SuppressLint
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.absoluteOffset
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import de.manhhao.hoshi.LookupResult
import moe.antimony.hoshi.features.reader.ReaderSelectionData
import org.json.JSONObject

data class LookupPopupState(
    val selection: ReaderSelectionData,
    val results: List<LookupResult>,
)

@Composable
fun LookupPopupView(
    state: LookupPopupState,
    onSwipeDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (state.results.isEmpty()) return
    val context = LocalContext.current
    val assets = remember(context) { LookupPopupAssets.load(context) }
    val html = remember(state.results, assets) { LookupPopupHtml.render(state.results, assets) }

    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val frame = LookupPopupLayout(
            selectionRect = state.selection.rect,
            screenWidth = maxWidth.value.toDouble(),
            screenHeight = maxHeight.value.toDouble(),
            maxWidth = 320.0,
            maxHeight = 250.0,
            isVertical = true,
        ).calculate()
        Surface(
            modifier = Modifier
                .absoluteOffset(
                    x = (frame.centerX - frame.width / 2).dp,
                    y = (frame.centerY - frame.height / 2).dp,
                )
                .width(frame.width.dp)
                .height(frame.height.dp)
                .popupSwipeDismiss(onSwipeDismiss),
            shape = RoundedCornerShape(8.dp),
            tonalElevation = 8.dp,
            shadowElevation = 8.dp,
        ) {
            LookupPopupWebView(
                html = html,
                onDismiss = onSwipeDismiss,
            )
        }
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun LookupPopupWebView(
    html: String,
    onDismiss: () -> Unit,
) {
    AndroidView(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Transparent),
        factory = { context ->
            WebView(context).apply {
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = false
                settings.allowFileAccess = false
                settings.allowContentAccess = false
                isVerticalScrollBarEnabled = false
                isHorizontalScrollBarEnabled = false
                setBackgroundColor(android.graphics.Color.TRANSPARENT)
                addJavascriptInterface(PopupWebViewBridge(onDismiss), "HoshiPopup")
                webViewClient = PopupWebViewClient(onDismiss)
                setOnTouchListener(PopupWebViewSwipeListener(onDismiss))
            }
        },
        update = { webView ->
            webView.loadDataWithBaseURL(
                "https://hoshi.local/popup/",
                html,
                "text/html",
                "UTF-8",
                null,
            )
        },
    )
}

private class PopupWebViewClient(
    private val onDismiss: () -> Unit,
) : WebViewClient() {
    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
        handlePopupUrl(request.url.scheme)

    @Suppress("OVERRIDE_DEPRECATION")
    override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean =
        handlePopupUrl(android.net.Uri.parse(url).scheme)

    private fun handlePopupUrl(scheme: String?): Boolean {
        if (scheme != "hoshi-popup") return false
        onDismiss()
        return true
    }
}

private class PopupWebViewBridge(
    private val onDismiss: () -> Unit,
) {
    private val mainHandler = Handler(Looper.getMainLooper())

    @JavascriptInterface
    fun postMessage(message: String) {
        val name = runCatching { JSONObject(message).optString("name") }.getOrNull()
        if (name == "tapOutside" || name == "swipeDismiss") {
            mainHandler.post(onDismiss)
        }
    }
}

private class PopupWebViewSwipeListener(
    private val onSwipeDismiss: () -> Unit,
) : android.view.View.OnTouchListener {
    private var startX = 0f
    private var startY = 0f

    override fun onTouch(view: android.view.View, event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                startX = event.x
                startY = event.y
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                val dx = event.x - startX
                val dy = event.y - startY
                if (kotlin.math.abs(dx) > POPUP_SWIPE_DISMISS_THRESHOLD_PX &&
                    kotlin.math.abs(dy) < POPUP_SWIPE_VERTICAL_SLOP_PX
                ) {
                    onSwipeDismiss()
                }
            }
        }
        return false
    }
}

private fun Modifier.popupSwipeDismiss(onSwipeDismiss: () -> Unit): Modifier =
    pointerInput(onSwipeDismiss) {
        var totalX = 0f
        var totalY = 0f
        detectHorizontalDragGestures(
            onDragStart = {
                totalX = 0f
                totalY = 0f
            },
            onHorizontalDrag = { change, dragAmount ->
                totalX += dragAmount
                totalY += change.positionChange().y
            },
            onDragEnd = {
                if (kotlin.math.abs(totalX) > POPUP_SWIPE_DISMISS_THRESHOLD_PX &&
                    kotlin.math.abs(totalY) < POPUP_SWIPE_VERTICAL_SLOP_PX
                ) {
                    onSwipeDismiss()
                }
            },
        )
    }

private const val POPUP_SWIPE_DISMISS_THRESHOLD_PX = 80f
private const val POPUP_SWIPE_VERTICAL_SLOP_PX = 40f
