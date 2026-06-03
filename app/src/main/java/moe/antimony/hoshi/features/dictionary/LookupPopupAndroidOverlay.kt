package moe.antimony.hoshi.features.dictionary

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color as AndroidColor
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import de.manhhao.hoshi.LookupResult
import moe.antimony.hoshi.LocalHoshiAppContainer
import moe.antimony.hoshi.R
import moe.antimony.hoshi.dictionary.LookupEngine
import moe.antimony.hoshi.epub.SasayakiMatch
import moe.antimony.hoshi.features.anki.AnkiPopupSettings
import moe.antimony.hoshi.features.anki.AnkiViewModel
import moe.antimony.hoshi.features.audio.AudioRequestHandler
import moe.antimony.hoshi.features.audio.LocalAudioRepository
import moe.antimony.hoshi.features.audio.WordAudioPlayer
import moe.antimony.hoshi.features.reader.ReaderFontManager
import moe.antimony.hoshi.features.reader.ReaderSelectionData
import moe.antimony.hoshi.features.reader.ReaderSelectionRect
import moe.antimony.hoshi.webview.applyHoshiWebViewSecurityDefaults
import kotlin.math.roundToInt

@Composable
internal fun LookupPopupAndroidStack(
    popups: List<LookupPopupItem>,
    onPopupsChange: (List<LookupPopupItem>) -> Unit,
    lookupChildPopup: (ReaderSelectionData) -> Pair<LookupPopupItem, Int>?,
    modifier: Modifier = Modifier,
    onRootPopupDismissed: () -> Boolean = { false },
    rootHighlightRects: List<ReaderSelectionRect> = emptyList(),
    rootHighlightDarkMode: Boolean = false,
    rootHighlightEInkMode: Boolean = false,
    rootHighlightVerticalWriting: Boolean = false,
) {
    val context = LocalContext.current
    val appContainer = LocalHoshiAppContainer.current
    val ankiViewModel: AnkiViewModel = viewModel(
        factory = remember(appContainer) {
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    AnkiViewModel(appContainer.ankiRepository) as T
            }
        },
    )
    val ankiUiState by ankiViewModel.uiState.collectAsState()
    val assets = remember(context) { LookupPopupAssets.load(context) }
    val controller = remember(context) {
        LookupPopupOverlayController(
            context = context,
            assets = assets,
            fontManager = appContainer.readerFontManager,
        )
    }
    AndroidView(
        modifier = modifier,
        factory = { controller.view },
        update = {
            controller.update(
                popups = popups,
                rootHighlightRects = rootHighlightRects,
                rootHighlightDarkMode = rootHighlightDarkMode,
                rootHighlightEInkMode = rootHighlightEInkMode,
                rootHighlightVerticalWriting = rootHighlightVerticalWriting,
                ankiViewModel = ankiViewModel,
                ankiSettings = ankiUiState.popupSettings,
                onPopupsChange = onPopupsChange,
                lookupChildPopup = lookupChildPopup,
                onRootPopupDismissed = onRootPopupDismissed,
                sasayakiWasPaused = false,
                sasayakiIsPlaying = false,
                onSasayakiReplayCue = {},
                onSasayakiTogglePlayback = {},
                onSasayakiPauseStateCleared = {},
                onSasayakiPlayForward = {},
                onPrepareSasayakiAudio = { _, _ -> null },
            )
        },
    )
}

private class LookupPopupOverlayController(
    context: Context,
    private val assets: LookupPopupAssets,
    private val fontManager: ReaderFontManager,
) {
    val view = LookupPopupOverlayLayout(context)
    private val childHosts = linkedMapOf<String, LookupPopupHostView>()
    private val rootHighlightView = PopupSelectionHighlightView(context)
    private var lastUpdate: OverlayUpdate? = null

    init {
        view.onOverlaySizeChanged = { lastUpdate?.let(::applyUpdate) }
        view.onOutsideStylusTouch = ::dismissFromOverlay
        view.addView(
            rootHighlightView,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ),
        )
    }

    private fun dismissFromOverlay() {
        val update = lastUpdate ?: return
        if (update.popups.isEmpty()) return
        if (!update.onRootPopupDismissed()) {
            update.onPopupsChange(emptyList())
        }
    }

    fun update(
        popups: List<LookupPopupItem>,
        rootHighlightRects: List<ReaderSelectionRect>,
        rootHighlightDarkMode: Boolean,
        rootHighlightEInkMode: Boolean,
        rootHighlightVerticalWriting: Boolean,
        ankiViewModel: AnkiViewModel,
        ankiSettings: AnkiPopupSettings,
        onPopupsChange: (List<LookupPopupItem>) -> Unit,
        lookupChildPopup: (ReaderSelectionData) -> Pair<LookupPopupItem, Int>?,
        onRootPopupDismissed: () -> Boolean,
        sasayakiWasPaused: Boolean,
        sasayakiIsPlaying: Boolean,
        onSasayakiReplayCue: (SasayakiMatch) -> Unit,
        onSasayakiTogglePlayback: () -> Unit,
        onSasayakiPauseStateCleared: () -> Unit,
        onSasayakiPlayForward: (SasayakiMatch) -> Unit,
        onPrepareSasayakiAudio: (SasayakiMatch, String) -> String?,
    ) {
        lastUpdate = OverlayUpdate(
            popups = popups,
            rootHighlightRects = rootHighlightRects,
            rootHighlightDarkMode = rootHighlightDarkMode,
            rootHighlightEInkMode = rootHighlightEInkMode,
            rootHighlightVerticalWriting = rootHighlightVerticalWriting,
            ankiViewModel = ankiViewModel,
            ankiSettings = ankiSettings,
            onPopupsChange = onPopupsChange,
            lookupChildPopup = lookupChildPopup,
            onRootPopupDismissed = onRootPopupDismissed,
            sasayakiWasPaused = sasayakiWasPaused,
            sasayakiIsPlaying = sasayakiIsPlaying,
            onSasayakiReplayCue = onSasayakiReplayCue,
            onSasayakiTogglePlayback = onSasayakiTogglePlayback,
            onSasayakiPauseStateCleared = onSasayakiPauseStateCleared,
            onSasayakiPlayForward = onSasayakiPlayForward,
            onPrepareSasayakiAudio = onPrepareSasayakiAudio,
        )
        lastUpdate?.let(::applyUpdate)
    }

    private fun applyUpdate(update: OverlayUpdate) {
        // The overlay sits above the reader WebView. When no popup is visible it must leave the
        // input path entirely; some stylus implementations do not pass through a visible full-size
        // parent even when its touch dispatch returns false.
        view.visibility = lookupPopupOverlayVisibility(hasPopups = update.popups.isNotEmpty())

        rootHighlightView.update(
            rects = if (update.popups.isNotEmpty()) update.rootHighlightRects else emptyList(),
            darkMode = update.rootHighlightDarkMode,
            eInkMode = update.rootHighlightEInkMode,
            verticalWriting = update.rootHighlightVerticalWriting,
        )

        val childPopups = update.popups
        val childKeys = childPopups.mapTo(mutableSetOf()) { it.id }
        childHosts.keys.filterNot(childKeys::contains).forEach { key ->
            childHosts.remove(key)?.let { view.removeView(it) }
        }
        childPopups.forEachIndexed { childIndex, popup ->
            val index = childIndex
            val host = childHosts.getOrPut(popup.id) {
                LookupPopupHostView(view.context, assets, fontManager).also { view.addView(it) }
            }
            host.update(
                popup = popup,
                index = index,
                allPopups = update.popups,
                ankiViewModel = update.ankiViewModel,
                ankiSettings = update.ankiSettings,
                isContentVisible = true,
                isPopupActive = true,
                onPopupsChange = update.onPopupsChange,
                lookupChildPopup = update.lookupChildPopup,
                onRootPopupDismissed = update.onRootPopupDismissed,
                onContentReady = {},
                sasayakiWasPaused = update.sasayakiWasPaused,
                sasayakiIsPlaying = update.sasayakiIsPlaying,
                onSasayakiReplayCue = update.onSasayakiReplayCue,
                onSasayakiTogglePlayback = update.onSasayakiTogglePlayback,
                onSasayakiPauseStateCleared = update.onSasayakiPauseStateCleared,
                onSasayakiPlayForward = update.onSasayakiPlayForward,
                onPrepareSasayakiAudio = update.onPrepareSasayakiAudio,
            )
            host.bringToFront()
        }
    }
}

internal fun lookupPopupOverlayVisibility(hasPopups: Boolean): Int =
    if (hasPopups) View.VISIBLE else View.GONE

private data class OverlayUpdate(
    val popups: List<LookupPopupItem>,
    val rootHighlightRects: List<ReaderSelectionRect>,
    val rootHighlightDarkMode: Boolean,
    val rootHighlightEInkMode: Boolean,
    val rootHighlightVerticalWriting: Boolean,
    val ankiViewModel: AnkiViewModel,
    val ankiSettings: AnkiPopupSettings,
    val onPopupsChange: (List<LookupPopupItem>) -> Unit,
    val lookupChildPopup: (ReaderSelectionData) -> Pair<LookupPopupItem, Int>?,
    val onRootPopupDismissed: () -> Boolean,
    val sasayakiWasPaused: Boolean,
    val sasayakiIsPlaying: Boolean,
    val onSasayakiReplayCue: (SasayakiMatch) -> Unit,
    val onSasayakiTogglePlayback: () -> Unit,
    val onSasayakiPauseStateCleared: () -> Unit,
    val onSasayakiPlayForward: (SasayakiMatch) -> Unit,
    val onPrepareSasayakiAudio: (SasayakiMatch, String) -> String?,
)

private class LookupPopupOverlayLayout(context: Context) : FrameLayout(context) {
    var onOverlaySizeChanged: () -> Unit = {}
    var onOutsideStylusTouch: () -> Unit = {}
    private val touchStreamTracker = PopupTouchStreamTracker()

    override fun onSizeChanged(width: Int, height: Int, oldWidth: Int, oldHeight: Int) {
        super.onSizeChanged(width, height, oldWidth, oldHeight)
        onOverlaySizeChanged()
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        val hitPopup = hitPopup(event)
        if (shouldDismissForOutsideStylusTouch(
            actionMasked = event.actionMasked,
            toolType = event.getToolType(0),
            hitPopup = hitPopup,
        )) {
            onOutsideStylusTouch()
            return true
        }

        val shouldDispatch = touchStreamTracker.shouldDispatch(event.actionMasked, hitPopup)
        if (!shouldDispatch) return false
        val handled = super.dispatchTouchEvent(event)
        touchStreamTracker.onDispatchResult(event.actionMasked, handled)
        return handled
    }

    private fun hitPopup(event: MotionEvent): Boolean {
        for (index in childCount - 1 downTo 0) {
            val child = getChildAt(index)
            if (child !is LookupPopupHostView || child.visibility != VISIBLE || child.alpha == 0f) continue
            if (event.x >= child.left && event.x < child.right && event.y >= child.top && event.y < child.bottom) {
                return true
            }
        }
        return false
    }
}

internal fun shouldDismissForOutsideStylusTouch(
    actionMasked: Int,
    toolType: Int,
    hitPopup: Boolean,
): Boolean =
    actionMasked == MotionEvent.ACTION_DOWN && !hitPopup && isStylusTool(toolType)

private fun isStylusTool(toolType: Int): Boolean =
    toolType == MotionEvent.TOOL_TYPE_STYLUS || toolType == MotionEvent.TOOL_TYPE_ERASER

internal class PopupTouchStreamTracker {
    private var activePopupStream = false

    fun shouldDispatch(actionMasked: Int, hitPopup: Boolean): Boolean =
        when (actionMasked) {
            MotionEvent.ACTION_DOWN -> hitPopup
            MotionEvent.ACTION_UP,
            MotionEvent.ACTION_CANCEL,
            -> activePopupStream || hitPopup
            else -> activePopupStream || hitPopup
        }

    fun onDispatchResult(actionMasked: Int, handled: Boolean) {
        when (actionMasked) {
            MotionEvent.ACTION_DOWN -> activePopupStream = handled
            MotionEvent.ACTION_UP,
            MotionEvent.ACTION_CANCEL,
            -> activePopupStream = false
        }
    }
}

@SuppressLint("ViewConstructor")
private class LookupPopupHostView(
    context: Context,
    private val assets: LookupPopupAssets,
    private val fontManager: ReaderFontManager,
) : FrameLayout(context) {
    private val density = resources.displayMetrics.density
    private val callbacks = PopupWebViewCallbackHolder(PopupWebViewCallbacks())
    private val lookupResultsHolder = PopupLookupResultsHolder(emptyList())
    private val selectionOffsetHolder = PopupSelectionOffsetHolder()
    private val webView = createWebView(context)
    private val selectionHighlightView = PopupSelectionHighlightView(context)
    private val content = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
    }
    private val actionBar = PopupControlBar(context)
    private val sasayakiBar = PopupControlBar(context)
    private var loadedHtml: String? = null
    private var loadedPopupId: String? = null
    private var contentReady = false
    private var clearSelectionSignal = 0
    private var popupScale = 1.0
    private var backCount = 0
    private var forwardCount = 0
    private var currentFrame: PopupFrameDp? = null

    init {
        addView(
            content,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ),
        )
        content.addView(actionBar)
        content.addView(sasayakiBar)
        content.addView(webView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        addView(
            selectionHighlightView,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ),
        )
        actionBar.visibility = GONE
        sasayakiBar.visibility = GONE
        visibility = INVISIBLE
        alpha = 0f
    }

    fun update(
        popup: LookupPopupItem,
        index: Int,
        allPopups: List<LookupPopupItem>,
        ankiViewModel: AnkiViewModel,
        ankiSettings: AnkiPopupSettings,
        isContentVisible: Boolean,
        isPopupActive: Boolean,
        onPopupsChange: (List<LookupPopupItem>) -> Unit,
        lookupChildPopup: (ReaderSelectionData) -> Pair<LookupPopupItem, Int>?,
        onRootPopupDismissed: () -> Boolean,
        onContentReady: (String) -> Unit,
        sasayakiWasPaused: Boolean,
        sasayakiIsPlaying: Boolean,
        onSasayakiReplayCue: (SasayakiMatch) -> Unit,
        onSasayakiTogglePlayback: () -> Unit,
        onSasayakiPauseStateCleared: () -> Unit,
        onSasayakiPlayForward: (SasayakiMatch) -> Unit,
        onPrepareSasayakiAudio: (SasayakiMatch, String) -> String?,
    ) {
        val state = popup.state
        val html = renderHtml(state, state.results, ankiSettings)
        if (loadedHtml != html) {
            loadedHtml = html
            loadedPopupId = null
            contentReady = false
            backCount = 0
            forwardCount = 0
            webView.clearActionButtons()
            lookupResultsHolder.results = state.results
            webView.loadDataWithBaseURL("https://hoshi.local/popup/", html, "text/html", "UTF-8", null)
        }
        if (loadedPopupId != popup.id) {
            loadedPopupId = popup.id
            contentReady = false
            backCount = 0
            forwardCount = 0
            webView.clearActionButtons()
        }
        lookupResultsHolder.results = state.results
        if (popupScale != state.popupScale) {
            popupScale = state.popupScale
            webView.evaluateJavascript(
                "document.documentElement.style.zoom = '${state.popupScale.coerceIn(0.8, 1.5)}'; if (typeof syncButtonFrames === 'function') requestAnimationFrame(syncButtonFrames)",
                null,
            )
        }
        if (clearSelectionSignal != popup.clearSelectionSignal) {
            clearSelectionSignal = popup.clearSelectionSignal
            selectionHighlightView.update(emptyList(), state.darkMode, state.eInkMode)
            webView.evaluateJavascript("window.hoshiSelection.clearSelection()", null)
        }

        val overlayView = parent as? View
        val frame = state.popupFrame(overlayView?.width ?: 0, overlayView?.height ?: 0)
        currentFrame = frame
        syncSelectionOffset(frame, state, popup.sasayakiCue)
        callbacks.callbacks = callbacksFor(
            popup = popup,
            index = index,
            allPopups = allPopups,
            ankiViewModel = ankiViewModel,
            isPopupActive = isPopupActive,
            onPopupsChange = onPopupsChange,
            lookupChildPopup = lookupChildPopup,
            onRootPopupDismissed = onRootPopupDismissed,
            onContentReady = onContentReady,
            isContentVisible = isContentVisible,
            onPrepareSasayakiAudio = onPrepareSasayakiAudio,
        )
        updateBars(
            popup = popup,
            allPopups = allPopups,
            state = state,
            index = index,
            sasayakiWasPaused = sasayakiWasPaused,
            sasayakiIsPlaying = sasayakiIsPlaying,
            onPopupsChange = onPopupsChange,
            onRootPopupDismissed = onRootPopupDismissed,
            onSasayakiReplayCue = onSasayakiReplayCue,
            onSasayakiTogglePlayback = onSasayakiTogglePlayback,
            onSasayakiPauseStateCleared = onSasayakiPauseStateCleared,
            onSasayakiPlayForward = onSasayakiPlayForward,
        )
        background = state.popupBackground()
        foreground = state.popupOutline()
        webView.setActionButtonTint(state.controlContentColor())
        val interactive = isPopupActive && isContentVisible && contentReady
        webView.setPopupInputEnabled(interactive)
        webView.isEnabled = interactive
        webView.isClickable = interactive
        if (!interactive) webView.clearFocus()
        layoutAt(frame)
        visibility = if (isPopupActive) VISIBLE else INVISIBLE
        alpha = if (interactive) 1f else 0f
        if (!isPopupActive) selectionHighlightView.update(emptyList(), state.darkMode, state.eInkMode)
    }

    private fun callbacksFor(
        popup: LookupPopupItem,
        index: Int,
        allPopups: List<LookupPopupItem>,
        ankiViewModel: AnkiViewModel,
        isPopupActive: Boolean,
        onPopupsChange: (List<LookupPopupItem>) -> Unit,
        lookupChildPopup: (ReaderSelectionData) -> Pair<LookupPopupItem, Int>?,
        onRootPopupDismissed: () -> Boolean,
        onContentReady: (String) -> Unit,
        isContentVisible: Boolean,
        onPrepareSasayakiAudio: (SasayakiMatch, String) -> String?,
    ): PopupWebViewCallbacks {
        val state = popup.state
        return PopupWebViewCallbacks(
            onTapOutside = {
                selectionHighlightView.update(emptyList(), state.darkMode, state.eInkMode)
                if (isPopupActive) onPopupsChange(closeChildPopups(allPopups, index))
            },
            onSwipeDismiss = {
                if (isPopupActive) dismiss(index, allPopups, onPopupsChange, onRootPopupDismissed)
            },
            onOpenLink = context::openPopupExternalLink,
            onTextSelected = { selection ->
                if (!isPopupActive) {
                    null
                } else {
                    val nextPopups = closeChildPopups(allPopups, index)
                    val lookup = lookupChildPopup(selection)
                    if (lookup == null) {
                        selectionHighlightView.update(emptyList(), state.darkMode, state.eInkMode)
                        null
                    } else {
                        val (childPopup, highlightCount) = lookup
                        onPopupsChange(nextPopups + childPopup.withoutRootInsets())
                        highlightCount
                    }
                }
            },
            onSelectionRectsLoaded = { rects ->
                selectionHighlightView.update(rects, state.darkMode, state.eInkMode)
            },
            onLookupRedirect = { query ->
                LookupEngine.lookup(query, state.dictionarySettings.maxResults, state.dictionarySettings.scanLength)
            },
            onLookupRedirected = { count ->
                if (count > 0) {
                    backCount += 1
                    forwardCount = 0
                    updateControlBar(state)
                    syncSelectionOffsetForCurrentFrame(state, popup.sasayakiCue)
                }
            },
            onPlayWordAudio = { url, mode ->
                WordAudioPlayer.get(context).play(url, mode)
            },
            onMineEntry = { payload, reply ->
                val miningContext = runCatching {
                    popup.sasayakiCue?.let { cue ->
                        state.ankiContext.copy(
                            sasayakiAudioPath = onPrepareSasayakiAudio(cue, state.selection.sentence),
                        )
                    } ?: state.ankiContext
                }.getOrNull()
                if (miningContext == null) {
                    reply(false)
                } else {
                    ankiViewModel.mineEntryAsync(payload, miningContext, reply)
                }
            },
            onDuplicateCheck = { expression, reply ->
                ankiViewModel.duplicateCheckAsync(expression, reply)
            },
            onContentReady = {
                if (state.results.isNotEmpty()) {
                    contentReady = true
                    val interactive = isPopupActive && isContentVisible
                    webView.setPopupInputEnabled(interactive)
                    webView.isEnabled = interactive
                    webView.isClickable = interactive
                    alpha = if (interactive) 1f else 0f
                    onContentReady(popup.id)
                }
            },
            onScroll = {
                selectionHighlightView.update(emptyList(), state.darkMode, state.eInkMode)
                if (isPopupActive) {
                    val nextPopups = closeChildPopupsForScrolledParent(allPopups, index)
                    if (nextPopups != allPopups) onPopupsChange(nextPopups)
                }
            },
        )
    }

    private fun updateBars(
        popup: LookupPopupItem,
        allPopups: List<LookupPopupItem>,
        state: LookupPopupState,
        index: Int,
        sasayakiWasPaused: Boolean,
        sasayakiIsPlaying: Boolean,
        onPopupsChange: (List<LookupPopupItem>) -> Unit,
        onRootPopupDismissed: () -> Boolean,
        onSasayakiReplayCue: (SasayakiMatch) -> Unit,
        onSasayakiTogglePlayback: () -> Unit,
        onSasayakiPauseStateCleared: () -> Unit,
        onSasayakiPlayForward: (SasayakiMatch) -> Unit,
    ) {
        updateControlBar(state)
        actionBar.onFirst = {
            if (backCount > 0) {
                backCount -= 1
                forwardCount += 1
                webView.evaluateJavascript(
                    "window.navigateBack(); if (typeof syncButtonFrames === 'function') requestAnimationFrame(syncButtonFrames)",
                    null,
                )
                updateControlBar(state)
                syncSelectionOffsetForCurrentFrame(state, popup.sasayakiCue)
            }
        }
        actionBar.onSecond = {
            if (forwardCount > 0) {
                forwardCount -= 1
                backCount += 1
                webView.evaluateJavascript(
                    "window.navigateForward(); if (typeof syncButtonFrames === 'function') requestAnimationFrame(syncButtonFrames)",
                    null,
                )
                updateControlBar(state)
                syncSelectionOffsetForCurrentFrame(state, popup.sasayakiCue)
            }
        }
        actionBar.onThird = {
            dismiss(index, allPopups, onPopupsChange, onRootPopupDismissed)
        }

        sasayakiBar.visibility = if (popup.sasayakiCue == null) GONE else VISIBLE
        popup.sasayakiCue?.let { cue ->
            sasayakiBar.configure(
                tint = state.controlContentColor(),
                dividerColor = state.popupBorderColor(),
                firstIcon = R.drawable.ic_material_rounded_replay,
                secondIcon = if (sasayakiIsPlaying || sasayakiWasPaused) R.drawable.ic_material_rounded_pause else R.drawable.ic_material_rounded_play_arrow,
                thirdIcon = R.drawable.ic_material_rounded_start,
                firstDescription = context.getString(R.string.sasayaki_replay_cue),
                secondDescription = context.getString(
                    if (sasayakiIsPlaying || sasayakiWasPaused) {
                        R.string.sasayaki_pause
                    } else {
                        R.string.sasayaki_play
                    },
                ),
                thirdDescription = context.getString(R.string.sasayaki_play_from_cue),
                firstEnabled = true,
                secondEnabled = true,
                thirdEnabled = true,
                center = true,
            )
            sasayakiBar.onFirst = {
                WordAudioPlayer.get(context).stop()
                onSasayakiReplayCue(cue)
            }
            sasayakiBar.onSecond = {
                WordAudioPlayer.get(context).stop()
                if (sasayakiWasPaused) onSasayakiPauseStateCleared() else onSasayakiTogglePlayback()
            }
            sasayakiBar.onThird = {
                WordAudioPlayer.get(context).stop()
                onSasayakiPlayForward(cue)
            }
        }
    }

    private fun updateControlBar(state: LookupPopupState) {
        val show = state.popupActionBar || backCount > 0 || forwardCount > 0
        actionBar.visibility = if (show) VISIBLE else GONE
        if (!show) return
        actionBar.configure(
            tint = state.controlContentColor(),
            dividerColor = state.popupBorderColor(),
            firstIcon = R.drawable.ic_material_rounded_arrow_back,
            secondIcon = R.drawable.ic_material_rounded_arrow_forward,
            thirdIcon = R.drawable.ic_material_rounded_close,
            firstDescription = context.getString(R.string.action_back),
            secondDescription = context.getString(R.string.action_forward),
            thirdDescription = context.getString(R.string.action_close),
            firstEnabled = backCount > 0,
            secondEnabled = forwardCount > 0,
            thirdEnabled = true,
            center = false,
        )
    }

    private fun dismiss(
        index: Int,
        allPopups: List<LookupPopupItem>,
        onPopupsChange: (List<LookupPopupItem>) -> Unit,
        onRootPopupDismissed: () -> Boolean,
    ) {
        val rootDismissHandled = index == 0 && onRootPopupDismissed()
        if (!rootDismissHandled) onPopupsChange(dismissPopupAt(allPopups, index))
    }

    private fun renderHtml(
        state: LookupPopupState,
        results: List<LookupResult>,
        ankiSettings: AnkiPopupSettings,
    ): String = LookupPopupHtml.render(
        results = results,
        dictionaryStyles = state.dictionaryStyles,
        settings = state.dictionarySettings,
        swipeToDismiss = state.swipeToDismiss,
        swipeThreshold = state.swipeThreshold,
        reducedMotionScrolling = state.reducedMotionScrolling,
        reducedMotionScrollPercent = state.reducedMotionScrollPercent,
        reducedMotionSwipeThreshold = state.reducedMotionSwipeThreshold,
        darkMode = state.darkMode,
        eInkMode = state.eInkMode,
        audioSettings = state.audioSettings,
        ankiSettings = ankiSettings,
        fontFaceCss = fontManager.popupFontFaceCss(),
        popupScale = state.popupScale,
    )

    private fun createWebView(context: Context): PopupActionButtonWebView =
        PopupActionButtonWebView(context).apply {
            applyHoshiWebViewSecurityDefaults()
            isVerticalScrollBarEnabled = false
            isHorizontalScrollBarEnabled = false
            settings.offscreenPreRaster = true
            setBackgroundColor(AndroidColor.TRANSPARENT)
            addJavascriptInterface(
                PopupWebViewBridge(
                    webView = this,
                    callbackHolder = callbacks,
                    lookupResultsHolder = lookupResultsHolder,
                    selectionOffsetHolder = selectionOffsetHolder,
                ),
                "HoshiPopup",
            )
            webViewClient = PopupMessageWebViewClient(
                callbackHolder = callbacks,
                audioRequestHandler = AudioRequestHandler(LocalAudioRepository.fromContext(context)),
                assets = assets,
                fontManager = fontManager,
            )
        }

    private fun layoutAt(frame: PopupFrameDp) {
        val params = (layoutParams as? FrameLayout.LayoutParams) ?: FrameLayout.LayoutParams(1, 1)
        val width = frame.widthDp.dpToPx()
        val height = frame.heightDp.dpToPx()
        val left = frame.leftDp.dpToPx()
        val top = frame.topDp.dpToPx()
        if (params.width != width || params.height != height || params.leftMargin != left || params.topMargin != top) {
            params.width = width
            params.height = height
            params.leftMargin = left
            params.topMargin = top
            layoutParams = params
        }
    }

    private fun LookupPopupState.popupFrame(parentWidthPx: Int, parentHeightPx: Int): PopupFrameDp {
        val screenWidthDp = (parentWidthPx.takeIf { it > 0 } ?: width.dpToPx()) / density
        val screenHeightDp = (parentHeightPx.takeIf { it > 0 } ?: height.dpToPx()) / density
        val frame = LookupPopupLayout(
            selectionRect = selection.rect,
            screenWidth = screenWidthDp.toDouble(),
            screenHeight = screenHeightDp.toDouble(),
            maxWidth = width.toDouble(),
            maxHeight = height.toDouble(),
            isVertical = isVertical,
            isFullWidth = isFullWidth,
            topInset = topInset,
            bottomInset = bottomInset,
        ).calculate()
        return PopupFrameDp(
            leftDp = frame.centerX - frame.width / 2,
            topDp = frame.centerY - frame.height / 2,
            widthDp = frame.width,
            heightDp = frame.height,
        )
    }

    private fun syncSelectionOffsetForCurrentFrame(state: LookupPopupState, sasayakiCue: SasayakiMatch?) {
        syncSelectionOffset(currentFrame ?: return, state, sasayakiCue)
    }

    private fun syncSelectionOffset(
        frame: PopupFrameDp,
        state: LookupPopupState,
        sasayakiCue: SasayakiMatch?,
    ) {
        val controlsHeight = popupSelectionControlsHeight(
            popupActionBar = state.popupActionBar,
            backCount = backCount,
            forwardCount = forwardCount,
            hasSasayakiCue = sasayakiCue != null,
        )
        selectionOffsetHolder.offsetX = frame.leftDp
        selectionOffsetHolder.offsetY = frame.topDp + controlsHeight
        selectionOffsetHolder.highlightOffsetX = 0.0
        selectionOffsetHolder.highlightOffsetY = controlsHeight
    }

    private fun Double.dpToPx(): Int = (this * density).toInt().coerceAtLeast(1)
    private fun Int.dpToPx(): Int = (this * density).toInt().coerceAtLeast(1)
}

internal fun popupSelectionOffsetY(
    frameTopDp: Double,
    popupActionBar: Boolean,
    backCount: Int,
    forwardCount: Int,
    hasSasayakiCue: Boolean,
): Double =
    frameTopDp + popupSelectionControlsHeight(
        popupActionBar = popupActionBar,
        backCount = backCount,
        forwardCount = forwardCount,
        hasSasayakiCue = hasSasayakiCue,
    )

private fun popupSelectionControlsHeight(
    popupActionBar: Boolean,
    backCount: Int,
    forwardCount: Int,
    hasSasayakiCue: Boolean,
): Double =
    (if (popupActionBar || backCount > 0 || forwardCount > 0) PopupControlTotalHeightDp else 0.0) +
        (if (hasSasayakiCue) PopupControlTotalHeightDp else 0.0)

private class PopupControlBar(context: Context) : LinearLayout(context) {
    var onFirst: () -> Unit = {}
    var onSecond: () -> Unit = {}
    var onThird: () -> Unit = {}

    private val row = LinearLayout(context).apply {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(8.dp, 0, 8.dp, 0)
    }
    private val first = controlButton(context)
    private val second = controlButton(context)
    private val third = controlButton(context)
    private val spacer = View(context)
    private val divider = View(context)

    init {
        orientation = VERTICAL
        row.addView(first, LinearLayout.LayoutParams(ControlSizeDp.dp, ControlSizeDp.dp))
        row.addView(second, LinearLayout.LayoutParams(ControlSizeDp.dp, ControlSizeDp.dp).apply { leftMargin = 12.dp })
        row.addView(spacer, LinearLayout.LayoutParams(0, 1, 1f))
        row.addView(third, LinearLayout.LayoutParams(ControlSizeDp.dp, ControlSizeDp.dp).apply { leftMargin = 12.dp })
        addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ControlHeightDp.dp))
        addView(divider, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1.dp))
        first.setOnClickListener { onFirst() }
        second.setOnClickListener { onSecond() }
        third.setOnClickListener { onThird() }
    }

    fun configure(
        tint: Int,
        dividerColor: Int,
        firstIcon: Int,
        secondIcon: Int,
        thirdIcon: Int,
        firstDescription: String,
        secondDescription: String,
        thirdDescription: String,
        firstEnabled: Boolean,
        secondEnabled: Boolean,
        thirdEnabled: Boolean,
        center: Boolean,
    ) {
        row.gravity = if (center) Gravity.CENTER else Gravity.CENTER_VERTICAL
        spacer.visibility = if (center) GONE else VISIBLE
        first.update(firstIcon, tint, firstDescription, firstEnabled)
        second.update(secondIcon, tint, secondDescription, secondEnabled)
        third.update(thirdIcon, tint, thirdDescription, thirdEnabled)
        divider.setBackgroundColor(dividerColor)
    }

    private fun controlButton(context: Context): ImageButton =
        ImageButton(context).apply {
            setBackgroundColor(AndroidColor.TRANSPARENT)
            scaleType = ImageView.ScaleType.CENTER
            isFocusable = false
            setPadding(6.dp, 6.dp, 6.dp, 6.dp)
        }

    private fun ImageButton.update(icon: Int, tint: Int, description: String, enabled: Boolean) {
        setImageResource(icon)
        contentDescription = description
        isEnabled = enabled
        alpha = if (enabled) 1f else 0.3f
        imageTintList = ColorStateList.valueOf(tint)
    }
}

private class PopupSelectionHighlightView(context: Context) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var rects = emptyList<ReaderSelectionRect>()
    private var darkMode = false
    private var eInkMode = false
    private var verticalWriting = false

    fun update(
        rects: List<ReaderSelectionRect>,
        darkMode: Boolean,
        eInkMode: Boolean,
        verticalWriting: Boolean = false,
    ) {
        this.rects = rects
        this.darkMode = darkMode
        this.eInkMode = eInkMode
        this.verticalWriting = verticalWriting
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val density = resources.displayMetrics.density
        rects.forEach { rect ->
            if (rect.width <= 0.0 || rect.height <= 0.0) return@forEach
            val left = (rect.x * density).toFloat()
            val top = (rect.y * density).toFloat()
            val right = ((rect.x + rect.width) * density).toFloat()
            val bottom = ((rect.y + rect.height) * density).toFloat()
            paint.style = Paint.Style.FILL
            if (eInkMode) {
                paint.isAntiAlias = false
                paint.color = if (darkMode) AndroidColor.WHITE else AndroidColor.BLACK
                val lineHeight = (1.5f * density).roundToInt().coerceAtLeast(1).toFloat()
                if (verticalWriting) {
                    val lineLeft = (right - 2f * density - lineHeight).coerceAtLeast(left).roundToInt().toFloat()
                    canvas.drawRect(lineLeft, top, lineLeft + lineHeight, bottom, paint)
                } else {
                    val lineTop = (bottom - 2f * density).roundToInt().toFloat()
                    canvas.drawRect(left, lineTop, right, lineTop + lineHeight, paint)
                }
            } else {
                paint.isAntiAlias = true
                paint.color = if (darkMode) AndroidColor.argb(82, 255, 255, 255) else AndroidColor.argb(82, 160, 160, 160)
                canvas.drawRect(RectF(left, top, right, bottom), paint)
            }
        }
    }
}

private fun LookupPopupItem.withoutRootInsets(): LookupPopupItem {
    if (state.topInset == 0.0 && state.bottomInset == 0.0) return this
    return copy(
        state = state.copy(
            topInset = 0.0,
            bottomInset = 0.0,
        ),
    )
}

private fun LookupPopupState.popupBackground(): GradientDrawable =
    GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = if (eInkMode) 0f else 8.dp.toFloat()
        setColor(if (darkMode) AndroidColor.BLACK else AndroidColor.WHITE)
    }

private fun LookupPopupState.popupOutline(): GradientDrawable =
    GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = if (eInkMode) 0f else 8.dp.toFloat()
        setColor(AndroidColor.TRANSPARENT)
        setStroke(1.dp, popupBorderColor())
    }

private fun LookupPopupState.popupBorderColor(): Int = when {
    eInkMode && darkMode -> AndroidColor.WHITE
    eInkMode -> AndroidColor.BLACK
    darkMode -> AndroidColor.rgb(58, 58, 60)
    else -> AndroidColor.rgb(209, 209, 214)
}

private fun LookupPopupState.controlContentColor(): Int = when {
    eInkMode && darkMode -> AndroidColor.WHITE
    eInkMode -> AndroidColor.BLACK
    darkMode -> AndroidColor.rgb(235, 235, 245)
    else -> AndroidColor.argb(153, 60, 60, 67)
}

private val Int.dp: Int
    get() = (this * android.content.res.Resources.getSystem().displayMetrics.density).toInt().coerceAtLeast(1)

private data class PopupFrameDp(
    val leftDp: Double,
    val topDp: Double,
    val widthDp: Double,
    val heightDp: Double,
)

private const val PopupControlTotalHeightDp = 37.0
private const val ControlHeightDp = 36
private const val ControlSizeDp = 32
