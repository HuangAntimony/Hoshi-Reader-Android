package moe.antimony.hoshi.features.dictionary

import android.content.Context
import de.manhhao.hoshi.LookupResult
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

internal data class LookupPopupAssets(
    val popupJs: String,
    val popupCss: String,
) {
    companion object {
        fun load(context: Context): LookupPopupAssets = LookupPopupAssets(
            popupJs = context.assets.open("hoshi-popup/popup.js")
                .bufferedReader()
                .use { it.readText() },
            popupCss = context.assets.open("hoshi-popup/popup.css")
                .bufferedReader()
                .use { it.readText() },
        )
    }
}

internal object LookupPopupHtml {
    fun render(
        results: List<LookupResult>,
        assets: LookupPopupAssets,
    ): String {
        val entries = entriesJson(results)
        return """
            <!DOCTYPE html>
            <html>
            <head>
                <meta name="viewport" content="width=device-width, initial-scale=1.0, maximum-scale=1.0, user-scalable=no">
                <style>${assets.popupCss}</style>
                <script>${assets.popupJs.escapeScriptEnd()}</script>
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
                            mineEntry: { postMessage: async function() { return false; } },
                            duplicateCheck: { postMessage: async function() { return false; } },
                            getEntry: { postMessage: async function(index) { return window.lookupEntries[index]; } }
                        }
                    };
                    window.hoshiSelection = window.hoshiSelection || {
                        selectText: function() { return null; },
                        clearSelection: function() {}
                    };
                    window.collapseDictionaries = false;
                    window.compactGlossaries = true;
                    window.showExpressionTags = false;
                    window.harmonicFrequency = false;
                    window.deduplicatePitchAccents = false;
                    window.audioSources = [];
                    window.audioEnableAutoplay = false;
                    window.audioPlaybackMode = "interrupt";
                    window.needsAudio = false;
                    window.allowDupes = false;
                    window.useAnkiConnect = false;
                    window.embedMedia = false;
                    window.compactGlossariesAnki = false;
                    window.customCSS = "";
                    window.swipeThreshold = 80;
                    window.dictionaryStyles = {};
                    window.lookupEntries = $entries;
                    window.entryCount = window.lookupEntries.length;
                </script>
                <div id="entries-container"></div>
                <script>
                    (function() {
                        var startX, startY;
                        document.addEventListener('touchstart', function(e) {
                            startX = e.touches[0].clientX;
                            startY = e.touches[0].clientY;
                        });
                        document.addEventListener('touchend', function(e) {
                            var dx = e.changedTouches[0].clientX - startX;
                            var dy = e.changedTouches[0].clientY - startY;
                            var hasSelection = window.getSelection().toString();
                            if (Math.abs(dx) > window.swipeThreshold && Math.abs(dy) < 20 && !hasSelection) {
                                webkit.messageHandlers.swipeDismiss.postMessage(null);
                            }
                        });
                    })();
                </script>
                <div class="overlay">
                    <div class="overlay-close" onclick="closeOverlay()">x</div>
                    <div class="overlay-content"></div>
                </div>
                <script>window.renderPopup();</script>
            </body>
            </html>
        """.trimIndent()
    }

    private fun entriesJson(results: List<LookupResult>): JsonArray =
        buildJsonArray {
            results.forEach { result ->
                add(result.toEntryJson())
            }
        }

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

    private fun String.escapeScriptEnd(): String =
        replace("</script>", "<\\/script>")
}
