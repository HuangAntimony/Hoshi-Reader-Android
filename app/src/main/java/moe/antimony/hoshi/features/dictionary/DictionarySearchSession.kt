package moe.antimony.hoshi.features.dictionary

import android.content.Context
import android.view.ViewGroup
import android.webkit.WebView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import moe.antimony.hoshi.features.reader.ReaderLookupPopupBridgeCallbackHolder
import moe.antimony.hoshi.features.reader.ReaderLookupPopupBridgeCallbacks
import moe.antimony.hoshi.features.reader.ReaderPopupHistoryCounts

/** UI-owned session: survives tab removal, but never outlives the shell's Activity composition. */
internal class DictionarySearchSession {
    var webView: WebView? = null
    val bridge = ReaderLookupPopupBridgeCallbackHolder()
    val rootAtTop = mutableStateOf(true)
    val childHistories = mutableStateOf<Map<String, ReaderPopupHistoryCounts>>(emptyMap())
    var profileId: String? = null
    var resetSearch: (() -> Unit)? = null
    internal val resetVersion = mutableIntStateOf(0)

    fun clearForReader() {
        resetSearch?.invoke()
        rootAtTop.value = true
        childHistories.value = emptyMap()
        resetVersion.intValue += 1
        webView?.evaluateJavascript(
            """
                (function() {
                    var empty = { popups: [] };
                    if (window.hoshiReaderPopupHost) {
                        window.hoshiReaderPopupHost.renderStack(empty);
                    } else {
                        window.__hoshiPendingReaderPopupStack = empty;
                    }
                })();
            """.trimIndent(),
            null,
        )
    }

    fun dispose() {
        resetSearch = null
        bridge.callbacks = ReaderLookupPopupBridgeCallbacks()
        webView?.let { view ->
            (view.parent as? ViewGroup)?.removeView(view)
            view.destroy()
        }
        webView = null
    }
}

@Composable
internal fun rememberDictionarySearchState(
    session: DictionarySearchSession,
    viewModel: DictionarySearchViewModel,
): State<DictionarySearchUiState> {
    DisposableEffect(session, viewModel) {
        session.resetSearch = viewModel::resetSearch
        onDispose { session.resetSearch = null }
    }
    // A hidden tab has stopped collecting. Restart from the reset StateFlow value
    // immediately so later profile updates cannot resubmit its old popup payload.
    return key(session.resetVersion.intValue) {
        viewModel.uiState.collectAsStateWithLifecycle()
    }
}

@Composable
internal fun rememberDictionarySearchSession(): DictionarySearchSession {
    val session = remember { DictionarySearchSession() }
    DisposableEffect(session) {
        onDispose { session.dispose() }
    }
    return session
}

@Composable
internal fun DictionarySearchSessionWebView(
    session: DictionarySearchSession,
    factory: (Context) -> WebView,
    update: (WebView) -> Unit,
    onAttach: (WebView) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    AndroidView(
        modifier = modifier,
        factory = { context ->
            (session.webView ?: factory(context).also { session.webView = it }).also {
                it.onResume()
                onAttach(it)
            }
        },
        update = update,
        onRelease = { view ->
            if (session.webView === view) {
                view.setOnTouchListener(null)
                view.onPause()
            }
        },
    )
}
