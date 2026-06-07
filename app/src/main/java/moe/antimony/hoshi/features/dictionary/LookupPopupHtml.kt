package moe.antimony.hoshi.features.dictionary

import android.content.Context
import de.manhhao.hoshi.LookupResult
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import moe.antimony.hoshi.features.audio.AudioSettings
import moe.antimony.hoshi.features.anki.AnkiPopupSettings
import java.util.Locale

internal data class LookupPopupAssets(
    val popupJs: String,
    val popupCss: String,
    val selectionJs: String = "",
    val readerPopupHostJs: String = "",
) {
    companion object {
        @Volatile
        private var cached: LookupPopupAssets? = null

        fun load(context: Context): LookupPopupAssets =
            cached ?: synchronized(this) {
                cached ?: read(context.applicationContext).also { cached = it }
            }

        private fun read(context: Context): LookupPopupAssets = LookupPopupAssets(
            popupJs = context.assets.open("hoshi-web/popup/popup.js")
                .bufferedReader()
                .use { it.readText() },
            popupCss = context.assets.open("hoshi-web/popup/popup.css")
                .bufferedReader()
                .use { it.readText() },
            selectionJs = context.assets.open("hoshi-web/shared/selection.js")
                .bufferedReader()
                .use { it.readText() },
            readerPopupHostJs = context.assets.open("hoshi-web/popup/reader-popup-host.js")
                .bufferedReader()
                .use { it.readText() },
        )
    }
}

internal object LookupPopupHtml {
    fun renderIframeDocument(
        assets: LookupPopupAssets? = null,
        dictionaryStyles: Map<String, String> = emptyMap(),
        settings: DictionarySettings = DictionarySettings(),
        swipeToDismiss: Boolean = false,
        swipeThreshold: Int = 40,
        reducedMotionScrolling: Boolean = false,
        reducedMotionScrollPercent: Int = 100,
        reducedMotionSwipeThreshold: Int = 40,
        darkMode: Boolean = false,
        eInkMode: Boolean = false,
        audioSettings: AudioSettings = AudioSettings(),
        ankiSettings: AnkiPopupSettings = AnkiPopupSettings(),
        fontFaceCss: String = "",
        popupScale: Double = 1.0,
    ): String {
        val normalizedSettings = settings.normalized()
        val collapsedDictionaries = dictionaryNamesJson(normalizedSettings.collapsedDictionaries)
        val effectiveSwipeThreshold = if (swipeToDismiss) swipeThreshold.coerceAtLeast(0) else 0
        val effectiveReducedMotionScrollScale = reducedMotionScrollPercent.coerceIn(40, 100) / 100.0
        val effectiveReducedMotionSwipeThreshold = reducedMotionSwipeThreshold.coerceAtLeast(0)
        val colorScheme = if (darkMode) "dark" else "light"
        val styles = dictionaryStylesJson(dictionaryStyles)
        val popupCss = assets?.let { """<style>${it.popupCss}</style>""" }
            ?: """<link rel="stylesheet" href="$PopupAssetBaseUrl/popup.css">"""
        val popupTypographyCss = """
            <style>
                ${fontFaceCss.trim()}
                html { zoom: ${popupCssNumber(popupScale.coerceIn(0.8, 1.5))}; }
            </style>
        """.trimIndent()
        val customCss = customCssStyle(normalizedSettings.customCSS)
        val fontPrewarmScript = """<script>${popupFontPrewarmScript()}</script>"""
        val eInkCss = if (eInkMode) """<style>$eInkPopupCss</style>""" else ""
        val selectionJs = assets?.let { """<script>${it.selectionJs}</script>""" }
            ?: """<script src="$PopupAssetBaseUrl/selection.js"></script>"""
        val popupJs = assets?.let { """<script>${it.popupJs}</script>""" }
            ?: """<script src="$PopupAssetBaseUrl/popup.js"></script>"""
        return """
            <!DOCTYPE html>
            <html data-hoshi-color-scheme="$colorScheme" data-hoshi-eink-mode="$eInkMode">
            <head>
                <meta name="viewport" content="width=device-width, initial-scale=1.0, maximum-scale=1.0, user-scalable=no">
                $popupCss
                <style>$androidColorSchemeCss</style>
                $popupTypographyCss
                $customCss
                $fontPrewarmScript
                $eInkCss
                <style>
                    html,
                    body {
                        overscroll-behavior: none;
                    }
                </style>
                <script>
                    window.nativePopupButtons = false;
                    window.HoshiAndroidPopup = window.HoshiAndroidPopup || (function() {
                        var nextMessageId = 1;
                        var pendingMessages = {};
                        function postMessage(name, body, id) {
                            try {
                                window.parent.postMessage({
                                    source: 'hoshi-popup-iframe',
                                    popupId: window.popupId || null,
                                    name: name,
                                    id: id || null,
                                    body: body === undefined ? null : body
                                }, 'https://appassets.androidplatform.net');
                            } catch (e) {
                                console.warn('Hoshi reader popup bridge failed', e);
                            }
                        }
                        return {
                            postMessage: postMessage,
                            requestMessage: function(name, body) {
                                return new Promise(function(resolve) {
                                    var id = String(nextMessageId++);
                                    pendingMessages[id] = resolve;
                                    postMessage(name, body, id);
                                });
                            },
                            resolveMessage: function(id, result) {
                                var resolve = pendingMessages[id];
                                if (!resolve) return;
                                delete pendingMessages[id];
                                resolve(result);
                            }
                        };
                    })();
                    window.webkit = {
                        messageHandlers: {
                            openLink: { postMessage: function(url) { window.HoshiAndroidPopup.postMessage('openLink', url); } },
                            textSelected: { postMessage: function(selection) { window.HoshiAndroidPopup.postMessage('textSelected', selection); } },
                            tapOutside: { postMessage: function() { window.HoshiAndroidPopup.postMessage('tapOutside'); } },
                            swipeDismiss: { postMessage: function() { window.HoshiAndroidPopup.postMessage('swipeDismiss'); } },
                            playWordAudio: { postMessage: function(content) { window.HoshiAndroidPopup.postMessage('playWordAudio', content); } },
                            buttonFrames: { postMessage: function() {} },
                            visualStateButtonFrames: { postMessage: function() {} },
                            shellReady: { postMessage: function() { window.HoshiAndroidPopup.postMessage('shellReady'); } },
                            contentReady: { postMessage: function(frames) { window.HoshiAndroidPopup.postMessage('contentReady', frames); } },
                            popupScrolled: { postMessage: function() { window.HoshiAndroidPopup.postMessage('popupScrolled'); } },
                            mineEntry: { postMessage: function(content) { return window.HoshiAndroidPopup.requestMessage('mineEntry', content); } },
                            duplicateCheck: { postMessage: function(expression) { return window.HoshiAndroidPopup.requestMessage('duplicateCheck', expression); } },
                            getEntry: { postMessage: function(index) { return window.HoshiAndroidPopup.requestMessage('getEntry', index); } },
                            lookupRedirect: { postMessage: function(query) { return window.HoshiAndroidPopup.requestMessage('lookupRedirect', query); } }
                        }
                    };
                    window.scanNonJapaneseText = ${normalizedSettings.scanNonJapaneseText};
                    window.scanLength = ${normalizedSettings.scanLength};
                    window.collapseMode = "${normalizedSettings.collapseMode.rawValue}";
                    window.expandFirstDictionary = ${normalizedSettings.expandFirstDictionary};
                    window.collapsedDictionaries = $collapsedDictionaries;
                    window.compactGlossaries = ${normalizedSettings.compactGlossaries};
                    window.showExpressionTags = ${normalizedSettings.showExpressionTags};
                    window.harmonicFrequency = ${normalizedSettings.harmonicFrequency};
                    window.deduplicatePitchAccents = ${normalizedSettings.deduplicatePitchAccents};
                    window.compactPitchAccents = ${normalizedSettings.compactPitchAccents};
                    window.audioSources = ${audioSourcesJson(audioSettings)};
                    window.audioRequestEndpoint = "https://appassets.androidplatform.net/audio";
                    window.dictionaryMediaRequestEndpoint = "https://appassets.androidplatform.net/image";
                    window.disablePopupImageViewportMaxHeight = true;
                    window.audioEnableAutoplay = ${audioSettings.enableAutoplay};
                    window.audioPlaybackMode = "${audioSettings.playbackMode.rawValue}";
                    window.needsAudio = ${ankiSettings.needsAudio};
                    window.allowDupes = ${ankiSettings.allowDupes};
                    window.useAnkiConnect = ${ankiSettings.useAnkiConnect};
                    window.embedMedia = ${ankiSettings.embedMedia};
                    window.compactGlossariesAnki = ${ankiSettings.compactGlossaries};
                    window.customCSS = ${JsonPrimitive(normalizedSettings.customCSS)};
                    window.swipeThreshold = $effectiveSwipeThreshold;
                    window.reducedMotionScrolling = $reducedMotionScrolling;
                    window.reducedMotionScrollScale = $effectiveReducedMotionScrollScale;
                    window.reducedMotionSwipeThreshold = $effectiveReducedMotionSwipeThreshold;
                    window.dictionaryStyles = $styles;
                    window.lookupEntries = [];
                    window.entryCount = 0;
                    window.popupId = null;
                    window.hoshiPostPopupScrollState = function() {
                        var scrollRoot = document.scrollingElement || document.documentElement || document.body;
                        var scrollTop = scrollRoot ? (scrollRoot.scrollTop || window.scrollY || 0) : 0;
                        window.HoshiAndroidPopup.postMessage('scrollState', {
                            atTop: scrollTop <= 1,
                            scrollTop: scrollTop
                        });
                    };
                    window.addEventListener('scroll', function() {
                        window.hoshiPostPopupScrollState();
                    }, { passive: true });
                </script>
                $selectionJs
                $popupJs
            </head>
            <body>
                <script>${popupGestureScript()}</script>
                <div id="entries-container"></div>
                <div class="overlay">
                    <div class="overlay-close" onclick="closeOverlay()">×</div>
                    <div class="overlay-content"></div>
                </div>
                <script>
                    (function() {
                        var container = document.getElementById('entries-container');
                        var posted = false;
                        var observer = null;
                        function postReady() {
                            if (posted) return;
                            posted = true;
                            webkit.messageHandlers.contentReady.postMessage(collectButtonFrames());
                        }
                        function hasRenderableContent() {
                            if (!container || !window.entryCount) {
                                return true;
                            }
                            return !!container.querySelector('.entry .glossary-content');
                        }
                        window.hoshiPopupObserveContentReady = function() {
                            posted = false;
                            if (observer) {
                                observer.disconnect();
                                observer = null;
                            }
                            if (hasRenderableContent()) {
                                postReady();
                                return;
                            }
                            observer = new MutationObserver(function() {
                                if (hasRenderableContent()) {
                                    postReady();
                                    observer.disconnect();
                                    observer = null;
                                }
                            });
                            observer.observe(container, { childList: true, subtree: true });
                            if (hasRenderableContent()) {
                                postReady();
                            }
                        };
                        window.addEventListener('message', function(event) {
                            if (event.origin !== 'https://appassets.androidplatform.net') return;
                            var message = event.data || {};
                            if (message.type === 'reply') {
                                window.HoshiAndroidPopup.resolveMessage(message.id, message.body);
                                return;
                            }
                            if (message.type === 'highlightSelection') {
                                window.hoshiSelection?.highlightSelection(message.count || 0);
                                return;
                            }
                            if (message.type === 'clearSelection') {
                                window.hoshiSelection?.clearSelection();
                                return;
                            }
                            if (message.type === 'resetPopup') {
                                window.popupId = null;
                                closeOverlay();
                                window.hoshiSelection?.clearSelection();
                                window.resetPopupResults?.();
                                requestAnimationFrame(window.hoshiPostPopupScrollState);
                                return;
                            }
                            if (message.type === 'renderPopup') {
                                window.popupId = message.popupId || null;
                                closeOverlay();
                                window.entryCount = message.entriesCount || 0;
                                var initialEntries = [];
                                if (message.initialEntryJson) {
                                    try {
                                        initialEntries[0] = JSON.parse(message.initialEntryJson);
                                    } catch (e) {
                                        initialEntries = [];
                                    }
                                }
                                if (window.replacePopupResults) {
                                    window.replacePopupResults(window.entryCount, initialEntries);
                                } else {
                                    window.lookupEntries = initialEntries;
                                    window.hoshiPopupObserveContentReady?.();
                                    window.renderPopup();
                                }
                                requestAnimationFrame(window.hoshiPostPopupScrollState);
                                return;
                            }
                            if (message.type === 'navigateBack') {
                                window.navigateBack?.();
                                return;
                            }
                            if (message.type === 'navigateForward') {
                                window.navigateForward?.();
                            }
                        });
                        webkit.messageHandlers.shellReady.postMessage(null);
                        requestAnimationFrame(window.hoshiPostPopupScrollState);
                    })();
                </script>
            </body>
            </html>
        """.trimIndent()
    }

    internal fun entryJsonString(result: LookupResult): String = result.toEntryJson().toString()

    private fun dictionaryStylesJson(styles: Map<String, String>): JsonObject =
        buildJsonObject {
            styles.forEach { (dictionary, css) ->
                put(dictionary, css)
            }
        }

    private fun dictionaryNamesJson(names: Set<String>): String =
        buildJsonArray {
            names.sorted().forEach { add(JsonPrimitive(it)) }
        }.toString()

    private fun audioSourcesJson(settings: AudioSettings): String =
        buildJsonArray {
            settings.audioSources
                .filter { it.isEnabled }
                .forEach { source ->
                    add(JsonPrimitive(if (source == AudioSettings.LocalAudioSource) AudioSettings.InternalLocalAudioUrl else source.url))
                }
        }.toString()

    private fun popupCssNumber(value: Double): String {
        val formatted = String.format(Locale.US, "%.2f", value).trimEnd('0')
        return if (formatted.endsWith('.')) "${formatted}0" else formatted
    }

    private fun customCssStyle(css: String): String {
        val content = css.trim()
        if (content.isEmpty()) return ""
        return """<style id="popup-custom-css">${content.styleElementContentEscaped()}</style>"""
    }

    private fun String.styleElementContentEscaped(): String =
        replace(Regex("</style", RegexOption.IGNORE_CASE), "<\\/style")

    private fun popupFontPrewarmScript(): String = """
        (function() {
            var prewarmedFaces = typeof WeakSet === 'function' ? new WeakSet() : null;
            function rememberFace(face) {
                if (!face) return false;
                if (prewarmedFaces) {
                    if (prewarmedFaces.has(face)) return false;
                    prewarmedFaces.add(face);
                }
                return true;
            }
            function syncAfterFontLoad() {
                if (typeof scheduleButtonFrameSyncAtVisualState === 'function') {
                    scheduleButtonFrameSyncAtVisualState();
                } else if (typeof scheduleButtonFrameSync === 'function') {
                    scheduleButtonFrameSync();
                }
            }
            window.hoshiPopupPrewarmFonts = function() {
                if (!document.fonts) return;
                var loads = [];
                try {
                    document.fonts.forEach(function(face) {
                        if (!rememberFace(face) || face.status !== 'unloaded' || typeof face.load !== 'function') {
                            return;
                        }
                        try {
                            loads.push(face.load().catch(function() {}));
                        } catch (e) {}
                    });
                } catch (e) {}
                if (!loads.length) return;
                Promise.all(loads).then(syncAfterFontLoad, syncAfterFontLoad);
            };
            window.hoshiPopupPrewarmFonts();
            setTimeout(window.hoshiPopupPrewarmFonts, 0);
            setTimeout(window.hoshiPopupPrewarmFonts, 100);
        })();
    """.trimIndent()

    private fun popupGestureScript(): String = """
        (function() {
            if (window.reducedMotionScrolling) {
                var reducedMotionStartY = 0;
                var root = function() {
                    return document.scrollingElement || document.documentElement || document.body;
                };
                var scrollByPopupHeight = function(direction) {
                    var scrollRoot = root();
                    var popupHeight = document.documentElement.clientHeight || window.innerHeight || scrollRoot.clientHeight;
                    var maxScroll = Math.max(0, scrollRoot.scrollHeight - popupHeight);
                    var current = scrollRoot.scrollTop || window.scrollY || 0;
                    var target = Math.max(0, Math.min(maxScroll, current + popupHeight * window.reducedMotionScrollScale * direction));
                    scrollRoot.scrollTop = target;
                    window.scrollTo(0, target);
                };
                document.addEventListener('touchstart', function(e) {
                    if (e.touches.length === 1) {
                        reducedMotionStartY = e.touches[0].clientY;
                    }
                }, { passive: true });
                document.addEventListener('touchmove', function(e) {
                    if (e.touches.length === 1 && e.cancelable) {
                        e.preventDefault();
                    }
                }, { passive: false });
                document.addEventListener('touchend', function(e) {
                    if (!e.changedTouches.length) return;
                    var delta = reducedMotionStartY - e.changedTouches[0].clientY;
                    var threshold = window.reducedMotionSwipeThreshold;
                    if (delta > threshold) {
                        scrollByPopupHeight(1);
                    } else if (delta < -threshold) {
                        scrollByPopupHeight(-1);
                    }
                }, { passive: true });
                document.addEventListener('wheel', function(e) {
                    if (e.deltaY === 0) return;
                    scrollByPopupHeight(e.deltaY > 0 ? 1 : -1);
                    e.preventDefault();
                }, { passive: false });
            }
            if (!window.swipeThreshold) {
                return;
            }
            var startX, startY;
            document.addEventListener('touchstart', function(e) {
                startX = e.touches[0].clientX;
                startY = e.touches[0].clientY;
            });
            document.addEventListener('touchend', function(e) {
                var dx = e.changedTouches[0].clientX - startX;
                var dy = e.changedTouches[0].clientY - startY;
                var absDx = Math.abs(dx);
                var absDy = Math.abs(dy);
                var isHorizontalDismiss = absDx > window.swipeThreshold && absDx > absDy * 1.75;
                var hasSelection = window.getSelection().toString();
                if (isHorizontalDismiss && !hasSelection) {
                    webkit.messageHandlers.swipeDismiss.postMessage(null);
                }
            });
        })();
    """.trimIndent()

    private fun LookupResult.toEntryJson(): JsonObject = buildJsonObject {
        put("expression", term.expression)
        put("reading", term.reading)
        put("matched", matched)
        putJsonArray("deinflectionTrace") {
            process.reversedArray().forEach { transformGroup ->
                add(
                    buildJsonObject {
                        put("name", transformGroup.name)
                        put("description", transformGroup.description)
                    },
                )
            }
        }
        putJsonArray("glossaries") {
            term.glossaries.forEach { glossary ->
                add(
                    buildJsonObject {
                        put("dictionary", glossary.dictName)
                        put("content", glossary.glossary)
                        put("definitionTags", glossary.definitionTags)
                        put("termTags", glossary.termTags)
                    },
                )
            }
        }
        putJsonArray("frequencies") {
            term.frequencies.forEach { frequency ->
                add(
                    buildJsonObject {
                        put("dictionary", frequency.dictName)
                        putJsonArray("frequencies") {
                            frequency.frequencies.forEach { tag ->
                                add(
                                    buildJsonObject {
                                        put("value", tag.value)
                                        put("displayValue", tag.displayValue)
                                    },
                                )
                            }
                        }
                    },
                )
            }
        }
        putJsonArray("pitches") {
            term.pitches.forEach { pitch ->
                add(
                    buildJsonObject {
                        put("dictionary", pitch.dictName)
                        putJsonArray("pitchPositions") {
                            pitch.pitchPositions.distinct().forEach { add(JsonPrimitive(it)) }
                        }
                    },
                )
            }
        }
        putJsonArray("rules") {
            term.rules.splitToSequence(' ')
                .filter { it.isNotBlank() }
                .forEach { add(JsonPrimitive(it)) }
        }
    }

    private const val androidColorSchemeCss = """
        html[data-hoshi-color-scheme="light"],
        html[data-hoshi-color-scheme="light"] body {
            --background-color: #fff;
            --background-color-light: #fff;
            --text-color: #000;
            color-scheme: light;
            background-color: #fff !important;
        }

        html[data-hoshi-color-scheme="light"] .overlay {
            background: #eee;
            color: #000;
        }

        html[data-hoshi-color-scheme="dark"],
        html[data-hoshi-color-scheme="dark"] body {
            --background-color: #000;
            --background-color-light: #000;
            --text-color: #fff;
            --text-color-light1: #aaaaaa;
            --text-color-light2: #999999;
            --text-color-light3: #888888;
            --text-color-light4: #777777;
            --background-color-dark1: #333333;
            color-scheme: dark;
            background-color: #000 !important;
        }

        html[data-hoshi-color-scheme="dark"] .overlay {
            background: #000;
            color: #fff;
        }

        html[data-hoshi-color-scheme="dark"] .glossary-group > div[data-dictionary] {
            color: var(--text-color) !important;
        }
    """

    private const val eInkPopupCss = """
        /*
         * Adapted from E Ink CSS for Yomitan:
         * https://github.com/Mansive/yomitan-eink-css
         */
        html[data-hoshi-eink-mode="true"],
        html[data-hoshi-eink-mode="true"] body {
            --background-color: #fff;
            --background-color-light: #fff;
            --text-color: #000;
            --text-color-light1: #000;
            --text-color-light2: #000;
            --text-color-light3: #000;
            --text-color-light4: #000;
            --background-color-dark1: #fff;
            color-scheme: light;
        }

        html[data-hoshi-eink-mode="true"] *,
        html[data-hoshi-eink-mode="true"] *::before,
        html[data-hoshi-eink-mode="true"] *::after {
            transition: none !important;
            animation-duration: 0s !important;
            box-shadow: none !important;
            text-shadow: none !important;
        }

        html[data-hoshi-eink-mode="true"] ::highlight(hoshi-selection) {
            background-color: transparent !important;
            color: inherit;
            text-decoration-line: underline;
            text-decoration-color: #000;
            text-decoration-thickness: 1.5px;
            text-underline-offset: 2px;
        }

        html[data-hoshi-eink-mode="true"] .deinflection-tag,
        html[data-hoshi-eink-mode="true"] .expr-tag,
        html[data-hoshi-eink-mode="true"] .glossary-tag,
        html[data-hoshi-eink-mode="true"] .pitch-dict-label {
            background-color: #fff !important;
            color: #000 !important;
            border: 1px solid #000 !important;
            border-radius: 0 !important;
        }

        html[data-hoshi-eink-mode="true"] .frequency-group {
            background-color: #fff !important;
            color: #000 !important;
            border: 1px solid #000 !important;
            border-radius: 0 !important;
        }

        html[data-hoshi-eink-mode="true"] .frequency-dict-label {
            background-color: #fff !important;
            color: #000 !important;
            border-right: 1px solid #000 !important;
        }

        html[data-hoshi-eink-mode="true"] .frequency-values {
            background-color: #fff !important;
            color: #000 !important;
        }

        html[data-hoshi-eink-mode="true"] .button-slot {
            border-radius: 0 !important;
            background-color: transparent !important;
            color: #000 !important;
            opacity: 1 !important;
        }

        html[data-hoshi-eink-mode="true"] .button-slot:active {
            background-color: #fff !important;
            outline: 1px solid #000 !important;
            outline-offset: -1px !important;
        }

        html[data-hoshi-eink-mode="true"] .glossary-group > summary::before {
            opacity: 1 !important;
        }

        html[data-hoshi-eink-mode="true"] .dict-label {
            opacity: 1 !important;
        }

        html[data-hoshi-eink-mode="true"] .overlay {
            background: #fff !important;
            color: #000 !important;
            border-top: 1px solid #000 !important;
        }

        html[data-hoshi-color-scheme="dark"][data-hoshi-eink-mode="true"],
        html[data-hoshi-color-scheme="dark"][data-hoshi-eink-mode="true"] body {
            --background-color: #000;
            --background-color-light: #000;
            --text-color: #fff;
            --text-color-light1: #fff;
            --text-color-light2: #fff;
            --text-color-light3: #fff;
            --text-color-light4: #fff;
            --background-color-dark1: #000;
            color-scheme: dark;
        }

        html[data-hoshi-color-scheme="dark"][data-hoshi-eink-mode="true"] .deinflection-tag,
        html[data-hoshi-color-scheme="dark"][data-hoshi-eink-mode="true"] .expr-tag,
        html[data-hoshi-color-scheme="dark"][data-hoshi-eink-mode="true"] .glossary-tag,
        html[data-hoshi-color-scheme="dark"][data-hoshi-eink-mode="true"] .pitch-dict-label,
        html[data-hoshi-color-scheme="dark"][data-hoshi-eink-mode="true"] .frequency-group {
            background-color: #000 !important;
            color: #fff !important;
            border: 1px solid #fff !important;
        }

        html[data-hoshi-color-scheme="dark"][data-hoshi-eink-mode="true"] ::highlight(hoshi-selection) {
            text-decoration-color: #fff;
        }

        html[data-hoshi-color-scheme="dark"][data-hoshi-eink-mode="true"] .frequency-dict-label {
            background-color: #000 !important;
            color: #fff !important;
            border-right: 1px solid #fff !important;
        }

        html[data-hoshi-color-scheme="dark"][data-hoshi-eink-mode="true"] .frequency-values,
        html[data-hoshi-color-scheme="dark"][data-hoshi-eink-mode="true"] .button-slot,
        html[data-hoshi-color-scheme="dark"][data-hoshi-eink-mode="true"] .overlay {
            background-color: #000 !important;
            color: #fff !important;
        }

        html[data-hoshi-color-scheme="dark"][data-hoshi-eink-mode="true"] .button-slot:active {
            outline: 1px solid #fff !important;
        }

        html[data-hoshi-color-scheme="dark"][data-hoshi-eink-mode="true"] .overlay {
            border-top: 1px solid #fff !important;
        }
    """

    private const val PopupAssetBaseUrl = "https://appassets.androidplatform.net/popup"
}
