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

internal data class LookupPopupAssets(
    val popupJs: String,
    val popupCss: String,
    val selectionJs: String = "",
) {
    companion object {
        fun load(context: Context): LookupPopupAssets = LookupPopupAssets(
            popupJs = context.assets.open("hoshi-popup/popup.js")
                .bufferedReader()
                .use { it.readText() },
            popupCss = context.assets.open("hoshi-popup/popup.css")
                .bufferedReader()
                .use { it.readText() },
            selectionJs = context.assets.open("hoshi-popup/selection.js")
                .bufferedReader()
                .use { it.readText() },
        )
    }
}

internal object LookupPopupHtml {
    fun render(
        results: List<LookupResult>,
        dictionaryStyles: Map<String, String> = emptyMap(),
        topSpacerPx: Int = 0,
        settings: DictionarySettings = DictionarySettings(),
        swipeToDismiss: Boolean = false,
        swipeThreshold: Int = 40,
        darkMode: Boolean = false,
        audioSettings: AudioSettings = AudioSettings(),
    ): String {
        val entryCount = results.size
        val styles = dictionaryStylesJson(dictionaryStyles)
        val normalizedSettings = settings.normalized()
        val effectiveSwipeThreshold = if (swipeToDismiss) swipeThreshold.coerceAtLeast(0) else 0
        val colorScheme = if (darkMode) "dark" else "light"
        val topSpacer = if (topSpacerPx > 0) {
            """<div style="height: ${topSpacerPx}px;"></div>"""
        } else {
            ""
        }
        val entriesContainer = if (topSpacerPx > 0) {
            """<div id="entries-container" style="min-height: 100vh;"></div>"""
        } else {
            """<div id="entries-container"></div>"""
        }
        return """
            <!DOCTYPE html>
            <html data-hoshi-color-scheme="$colorScheme">
            <head>
                <meta name="viewport" content="width=device-width, initial-scale=1.0, maximum-scale=1.0, user-scalable=no">
                <link rel="stylesheet" href="popup.css">
                <style>$androidColorSchemeCss</style>
                <script src="selection.js"></script>
                <script src="popup.js"></script>
            </head>
            <body>
                <script>
                    window.HoshiAndroidPopup = window.HoshiAndroidPopup || {
                        postMessage: function(name, body) {
                            try {
                                if (window.HoshiPopup && window.HoshiPopup.postMessage) {
                                    window.HoshiPopup.postMessage(JSON.stringify({ name: name, body: body || null }));
                                }
                            } catch (e) {
                                console.warn('HoshiPopup bridge failed', e);
                            }
                            if (name === 'tapOutside' || name === 'swipeDismiss') {
                                window.location.href = 'hoshi-popup://' + name;
                            }
                        }
                    };
                    window.webkit = {
                        messageHandlers: {
                            openLink: { postMessage: function(url) { window.HoshiAndroidPopup.postMessage('openLink', url); } },
                            textSelected: { postMessage: function(selection) { window.HoshiAndroidPopup.postMessage('textSelected', selection); } },
                            tapOutside: { postMessage: function() { window.HoshiAndroidPopup.postMessage('tapOutside'); } },
                            swipeDismiss: { postMessage: function() { window.HoshiAndroidPopup.postMessage('swipeDismiss'); } },
                            playWordAudio: { postMessage: function(content) { window.HoshiAndroidPopup.postMessage('playWordAudio', content); } },
                            contentReady: { postMessage: function() { window.HoshiAndroidPopup.postMessage('contentReady'); } },
                            mineEntry: { postMessage: async function() { return false; } },
                            duplicateCheck: { postMessage: async function() { return false; } },
                            getEntry: { postMessage: async function(index) {
                                if (window.HoshiPopup && window.HoshiPopup.getEntry) {
                                    var entryJson = window.HoshiPopup.getEntry(index);
                                    return entryJson ? JSON.parse(entryJson) : null;
                                }
                                return window.lookupEntries[index];
                            } }
                        }
                    };
                    window.collapseDictionaries = ${normalizedSettings.collapseDictionaries};
                    window.compactGlossaries = ${normalizedSettings.compactGlossaries};
                    window.showExpressionTags = ${normalizedSettings.showExpressionTags};
                    window.harmonicFrequency = ${normalizedSettings.harmonicFrequency};
                    window.deduplicatePitchAccents = ${normalizedSettings.deduplicatePitchAccents};
                    window.audioSources = ${audioSourcesJson(audioSettings)};
                    window.audioRequestEndpoint = "https://hoshi.local/audio";
                    window.audioEnableAutoplay = ${audioSettings.enableAutoplay};
                    window.audioPlaybackMode = "${audioSettings.playbackMode.rawValue}";
                    window.needsAudio = false;
                    window.allowDupes = false;
                    window.useAnkiConnect = false;
                    window.embedMedia = false;
                    window.compactGlossariesAnki = false;
                    window.customCSS = ${JsonPrimitive(normalizedSettings.customCSS)};
                    window.swipeThreshold = $effectiveSwipeThreshold;
                    window.dictionaryStyles = $styles;
                    window.lookupEntries = [];
                    window.entryCount = $entryCount;
                </script>
                <script>
                    (function() {
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
                </script>
                $topSpacer
                $entriesContainer
                <div class="overlay">
                    <div class="overlay-close" onclick="closeOverlay()">x</div>
                    <div class="overlay-content"></div>
                </div>
                <script>
                    (function() {
                        var container = document.getElementById('entries-container');
                        var posted = false;
                        function postReady() {
                            if (posted) return;
                            posted = true;
                            requestAnimationFrame(function() {
                                requestAnimationFrame(function() {
                                    webkit.messageHandlers.contentReady.postMessage(null);
                                });
                            });
                        }
                        if (container) {
                            var observer = new MutationObserver(function() {
                                if (container.querySelector('.entry')) {
                                    postReady();
                                    observer.disconnect();
                                }
                            });
                            observer.observe(container, { childList: true, subtree: true });
                        }
                        window.renderPopup();
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

    private fun audioSourcesJson(settings: AudioSettings): String =
        buildJsonArray {
            settings.enabledAudioSourceUrls.forEach { add(JsonPrimitive(it)) }
        }.toString()

    private fun LookupResult.toEntryJson(): JsonObject = buildJsonObject {
        put("expression", term.expression)
        put("reading", term.reading)
        put("matched", matched)
        putJsonArray("deinflectionTrace") {
            process.reversedArray().forEach { name ->
                add(
                    buildJsonObject {
                        put("name", name)
                        put("description", "")
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
        }

        html[data-hoshi-color-scheme="dark"] .glossary-group > div[data-dictionary] {
            color: var(--text-color) !important;
        }
    """
}
