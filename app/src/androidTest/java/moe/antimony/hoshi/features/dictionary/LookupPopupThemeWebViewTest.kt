package moe.antimony.hoshi.features.dictionary

import android.content.res.Configuration
import android.os.SystemClock
import android.view.ContextThemeWrapper
import android.view.MotionEvent
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.viewinterop.AndroidView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import moe.antimony.hoshi.webview.applyHoshiWebViewSecurityDefaults
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Real Chromium CSS/media queries. Synthetic content; never writes user settings or dictionaries. */
@RunWith(AndroidJUnit4::class)
class LookupPopupThemeWebViewTest {
    @get:Rule val compose = createComposeRule()

    @Test fun transparentIframeMatchesItsEmbeddingColorSchemeAcrossThemeChanges() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val assets = LookupPopupAssets.load(context)
        val child = LookupPopupHtml.renderIframeDocument(assets = assets)
        withPopup(false, false) { web ->
            evaluate(web, """
                (() => {
                    document.documentElement.style.colorScheme = 'normal';
                    window.eval(${JSONObject.quote(assets.readerPopupHostJs)});
                    window.themeFrame = {id:'dictionary-search-root', frame:{left:0,top:0,width:300,height:250},
                        iframeUrl:'about:blank', darkMode:false, eInkMode:false, entriesCount:0};
                    hoshiReaderPopupHost.renderStack({popups:[themeFrame]});
                    const frame = document.querySelector('iframe');
                    frame.srcdoc = ${JSONObject.quote(child)};
                    return {};
                })()
            """)
            for (dark in listOf(false, true, false)) {
                evaluate(web, "(() => { themeFrame = {...themeFrame, darkMode:$dark}; hoshiReaderPopupHost.renderStack({popups:[themeFrame]}); return {}; })()")
                val deadline = android.os.SystemClock.uptimeMillis() + 5000
                var actual: JSONObject
                do {
                    actual = evaluate(web, """
                        (() => {
                            const frame = document.querySelector('iframe'), doc = frame.contentDocument;
                            return {ready:doc.documentElement.dataset.hoshiColorScheme === '${if (dark) "dark" else "light"}',
                                outer:getComputedStyle(frame).colorScheme,
                                inner:frame.contentWindow.getComputedStyle(doc.documentElement).colorScheme,
                                background:doc.body && frame.contentWindow.getComputedStyle(doc.body).backgroundColor};
                        })()
                    """)
                    if (actual.optBoolean("ready")) break
                    android.os.SystemClock.sleep(20)
                } while (android.os.SystemClock.uptimeMillis() < deadline)
                assertTrue(actual.toString(), actual.optBoolean("ready"))
                assertEquals(if (dark) "dark" else "light", actual.getString("inner"))
                assertEquals("A scheme mismatch makes Chromium paint an opaque iframe canvas", actual.getString("inner"), actual.getString("outer"))
                assertEquals("rgba(0, 0, 0, 0)", actual.getString("background"))
            }
        }
    }

    @Test fun appPaletteIsIndependentOfNativeMediaTheme() {
        for (nativeDark in listOf(false, true)) for (appDark in listOf(false, true)) for (eInk in listOf(false, true)) {
            withPopup(nativeDark, appDark, eInk = eInk) { web ->
                val result = evaluate(web, """
                    (() => {
                        document.body.insertAdjacentHTML('beforeend', '<div id="box" style="background:var(--danger-color-lightest, #ffdddd)"></div><div class="audio-candidate-menu"></div><div class="frequency-group"></div><button class="button-slot">audio</button><table><tr><th class="gloss-sc-th">table</th></tr></table>');
                        document.body.insertAdjacentHTML('beforeend', '<table data-sc-content="formsTable"><tr><th class="gloss-sc-th"><span>forms</span></th></tr></table>');
                        applyHoshiPopupThemeOverrides();
                        const css = selector => getComputedStyle(document.querySelector(selector));
                        return {box:css('#box').backgroundColor, menu:css('.audio-candidate-menu').backgroundColor,
                            body:css('body').backgroundColor, overlay:css('.overlay').backgroundColor, frequency:css('.frequency-group').color,
                            button:css('button.button-slot').color, text:css('.audio-candidate-menu').color, table:css('.gloss-sc-th').backgroundColor,
                            forms:css('table[data-sc-content="formsTable"] th').backgroundColor,
                            formsText:css('table[data-sc-content="formsTable"] span').color,
                            media:matchMedia('(prefers-color-scheme: dark)').matches};
                    })()
                """)
                val label = "native=$nativeDark app=$appDark eink=$eInk $result"
                assertEquals(label, nativeDark, result.getBoolean("media"))
                assertEquals(label, if (appDark) "rgb(115, 35, 23)" else "rgb(238, 179, 170)", result.getString("box"))
                assertEquals(label, if (appDark) { if (eInk) "rgb(0, 0, 0)" else "rgb(51, 51, 51)" } else "rgb(255, 255, 255)", result.getString("menu"))
                assertEquals(label, if (appDark) "rgb(255, 255, 255)" else "rgb(0, 0, 0)", result.getString("text"))
                assertEquals(label, if (appDark) { if (eInk) "rgb(0, 0, 0)" else "rgb(51, 51, 51)" } else { if (eInk) "rgb(255, 255, 255)" else "rgb(238, 238, 238)" }, result.getString("table"))
                assertEquals(label, "rgba(0, 0, 0, 0)", result.getString("body"))
                assertEquals(label, result.getString("table"), result.getString("forms"))
                assertEquals(label, result.getString("text"), result.getString("formsText"))
                assertEquals(label, if (appDark) "rgb(255, 255, 255)" else "rgb(0, 0, 0)", result.getString("button"))
                if (eInk) {
                    assertEquals(label, if (appDark) "rgb(0, 0, 0)" else "rgb(255, 255, 255)", result.getString("overlay"))
                    assertEquals(label, if (appDark) "rgb(255, 255, 255)" else "rgb(0, 0, 0)", result.getString("frequency"))
                }
            }
        }
    }

    @Test fun iosSemanticColorContractUsesRenderedReferenceCss() {
        val css = InstrumentationRegistry.getInstrumentation().context.assets.open("ios-reference/popup.css").bufferedReader().use { it.readText() }
        for (dark in listOf(false, true)) {
            lateinit var reference: JSONObject
            withPopup(dark, dark, referenceCss = css) { web ->
                reference = evaluate(web, """
                    (() => {
                        const names = new Set();
                        function visit(rules) { for (const rule of rules) {
                            if (rule.style) for (const key of rule.style) if (key.startsWith('--') && key.includes('color')) names.add(key);
                            if (rule.cssRules) visit(rule.cssRules);
                        }}
                        for (const sheet of document.styleSheets) visit(sheet.cssRules);
                        return Object.fromEntries([...names].map(key => [key, getComputedStyle(document.body).getPropertyValue(key).trim()]));
                    })()
                """)
            }
            withPopup(dark, dark) { web ->
                val keys = reference.keys().asSequence().toList()
                assertTrue("iOS contract must include its palette", keys.size >= 17)
                val actual = evaluate(web, "Object.fromEntries(" + org.json.JSONArray(keys).toString() +
                    ".map(key => [key, getComputedStyle(document.body).getPropertyValue(key).trim()]))")
                for (key in keys) assertEquals("dark=$dark $key", reference.getString(key), actual.getString(key))
            }
        }
    }

    @Test fun unchangedUserMediaCssAndDictionaryOverridesWorkAtEveryScale() {
        val userCss = """
            @media (prefers-color-scheme: light) {
                html,
                body {
                    --danger-color-lightest: #eeb3aa;
                }
            }
            @media (prefers-color-scheme: dark) {
                html,
                body {
                    --danger-color-lightest: #732317;
                }
            }
            div[data-dictionary="fixture"] { color: rgb(12, 34, 56); --success-color: #123456; }
        """.trimIndent()
        for (dark in listOf(false, true)) for (scale in listOf(0.8, 1.0, 2.0)) {
            withPopup(dark, dark, customCss = userCss, scale = scale) { web ->
                val actual = evaluate(web, """
                    (() => {
                        window.dictionaryStyles = {fixture: 'div { border: 3px solid red; }'};
                        const section = createGlossarySection('fixture', [{content:'word', termTags:'', definitionTags:''}], true, 0);
                        document.getElementById('entries-container').append(section);
                        const wrapper = section.querySelector('div[data-dictionary]');
                        const probe = document.createElement('div');
                        probe.style.cssText='width:calc(20px * var(--popup-scale));height:20px;background:var(--danger-color-lightest)';
                        wrapper.append(probe);
                        return {box:getComputedStyle(probe).backgroundColor, width:probe.getBoundingClientRect().width,
                            color:getComputedStyle(wrapper).color, success:getComputedStyle(wrapper).getPropertyValue('--success-color').trim(),
                            labelBorder:getComputedStyle(section.querySelector('summary')).borderTopWidth};
                    })()
                """)
                assertEquals(if (dark) "rgb(115, 35, 23)" else "rgb(238, 179, 170)", actual.getString("box"))
                assertEquals(20 * scale, actual.getDouble("width"), 0.1)
                assertEquals("rgb(12, 34, 56)", actual.getString("color"))
                assertEquals("#123456", actual.getString("success"))
                assertEquals("0px", actual.getString("labelBorder"))
            }
        }
    }

    @Test fun liveThemeRedrawsCanvasAndImageWithoutResettingContent() {
        withPopup(false, false) { web ->
            evaluate(web, """
                (() => {
                    document.body.insertAdjacentHTML('beforeend', '<div class="gloss-image-link" data-appearance="monochrome"><img class="gloss-image"></div><canvas id="ink" class="gloss-image"></canvas>');
                    const source = document.createElement('canvas'); source.width=8; source.height=8;
                    source.getContext('2d').fillRect(0,0,8,8);
                    const canvas = document.getElementById('ink');
                    renderDefinitionImageToCanvas(canvas, source, 1, 1, 'monochrome');
                    window.retainedCanvas = canvas;
                    window.lookupEntries = [{expression:'retained'}];
                    audioUrls[0] = 'https://fixture.invalid/selected.mp3';
                    backStack.push({marker:'previous'}); forwardStack.push({marker:'next'});
                    const scroll = document.createElement('div'); scroll.id='retained-scroll';
                    scroll.style.cssText='height:80px;overflow:auto';
                    scroll.innerHTML='<div style="height:800px">long definition</div>';
                    document.body.append(scroll); scroll.scrollTop=120;
                    return {};
                })()
            """)
            for (dark in listOf(true, false)) for (eInk in listOf(false, true)) {
                val actual = evaluate(web, """
                    (() => {
                        window.setHoshiPopupTheme({darkMode:$dark, eInkMode:$eInk});
                        const canvas = document.getElementById('ink');
                        const pixel = [...canvas.getContext('2d').getImageData(0,0,1,1).data];
                        return {pixel:pixel.join(','), same:canvas === window.retainedCanvas,
                            audio:audioUrls[0], back:backStack[0].marker, forward:forwardStack[0].marker,
                            scroll:document.getElementById('retained-scroll').scrollTop,
                            expression:window.lookupEntries[0].expression, filter:getComputedStyle(document.querySelector('img')).filter};
                    })()
                """)
                assertEquals(if (dark) "255,255,255,255" else "0,0,0,255", actual.getString("pixel"))
                assertTrue(actual.getBoolean("same"))
                assertEquals("retained", actual.getString("expression"))
                assertEquals("https://fixture.invalid/selected.mp3", actual.getString("audio"))
                assertEquals("previous", actual.getString("back"))
                assertEquals("next", actual.getString("forward"))
                assertEquals(120, actual.getInt("scroll"))
                assertEquals(if (dark) "brightness(0) invert(1)" else "brightness(0)", actual.getString("filter"))
            }
        }
    }

    @Test fun imageFinishingAfterThemeChangeUsesCurrentInkColor() {
        withPopup(false, false) { web ->
            evaluate(web, """
                (() => {
                    const url = 'data:image/svg+xml,' + encodeURIComponent('<svg xmlns="http://www.w3.org/2000/svg" width="8" height="8"><rect width="8" height="8" fill="black"/></svg>');
                    const canvas = createDefinitionImageCanvas(url, '', (c, source) => {
                        renderDefinitionImageToCanvas(c, source, 1, 1, 'monochrome');
                        window.lateInkPixel = [...c.getContext('2d').getImageData(0,0,1,1).data].join(',');
                    });
                    document.getElementById('entries-container').append(canvas);
                    canvas.loadDictionaryMedia();
                    window.setHoshiPopupTheme({darkMode:true, eInkMode:false});
                    return {};
                })()
            """)
            val deadline = android.os.SystemClock.uptimeMillis() + 5000
            var pixel = ""
            while (pixel.isEmpty() && android.os.SystemClock.uptimeMillis() < deadline) {
                pixel = evaluate(web, "({pixel:window.lateInkPixel || ''})").getString("pixel")
                if (pixel.isEmpty()) android.os.SystemClock.sleep(20)
            }
            assertEquals("255,255,255,255", pixel)
        }
    }

    @Test fun restoredHistoryRefreshesDeferredCanvasAndButtons() {
        withPopup(false, false) { web ->
            val result = evaluate(web, """
                (() => {
                    const container = document.getElementById('entries-container');
                    const source = document.createElement('canvas'); source.width=8; source.height=8;
                    source.getContext('2d').fillRect(0,0,8,8);
                    for (let n = 0; n < 8; n++) {
                        const entry = document.createElement('div');
                        entry.dataset.entryIndex = String(n);
                        entry.innerHTML = '<button class="button-slot">audio</button><canvas class="gloss-image"></canvas>';
                        entry.querySelector('button').disabled = n % 2 === 1;
                        container.append(entry);
                        renderDefinitionImageToCanvas(entry.querySelector('canvas'), source, 1, 1, 'monochrome');
                    }
                    applyHoshiPopupThemeOverrides();
                    window.entryCount = 8;
                    const saved = snapshot();
                    container.replaceChildren();
                    window.setHoshiPopupTheme({darkMode:true, eInkMode:true});
                    restore(saved);
                    flushPendingHistoryRestore();
                    return {pixels:[...container.querySelectorAll('canvas')].map(c => [...c.getContext('2d').getImageData(0,0,1,1).data].join(',')),
                        buttons:[...container.querySelectorAll('button')].map(b => getComputedStyle(b).color)};
                })()
            """)
            for (n in 0 until 8) {
                assertEquals("history canvas $n", "255,255,255,255", result.getJSONArray("pixels").getString(n))
                assertEquals("history button $n", if (n % 2 == 0) "rgb(255, 255, 255)" else "rgb(102, 102, 102)", result.getJSONArray("buttons").getString(n))
            }
        }
    }

    @Test fun customButtonColorsSurviveThemeAndEInkSwitches() {
        for (customCss in listOf(
            "body { --hoshi-button-color: #162c42; --hoshi-button-disabled-color: #445566; }",
            "button.button-slot { color: #162c42; } button.button-slot:disabled { color: #445566; }",
        )) withPopup(false, false, customCss = customCss) { web ->
            for (dark in listOf(false, true, false)) for (eInk in listOf(false, true, false)) {
                val result = evaluate(web, """
                    (() => {
                        if (!document.querySelector('button.button-slot')) document.body.insertAdjacentHTML('beforeend', '<button class="button-slot">audio</button><button class="button-slot" disabled>mine</button>');
                        window.setHoshiPopupTheme({darkMode:$dark, eInkMode:$eInk});
                        return {enabled:getComputedStyle(document.querySelector('button:enabled')).color,
                            disabled:getComputedStyle(document.querySelector('button:disabled')).color};
                    })()
                """)
                assertEquals("rgb(22, 44, 66)", result.getString("enabled"))
                assertEquals("rgb(68, 85, 102)", result.getString("disabled"))
            }
        }
    }

    @Test fun buttonAvailabilityRemainsDistinctAcrossThemesAndScale() {
        for (nativeDark in listOf(false, true)) for (scale in listOf(0.8, 1.0, 2.0)) {
            withPopup(nativeDark, false, scale = scale) { web ->
                evaluate(web, """
                    (() => {
                        window.themeButtons = [];
                        const container = document.getElementById('entries-container');
                        for (const icon of ['square', 'circle', 'diamond', 'square-small', 'circle-small', 'diamond-small']) {
                            for (const state of ['default', 'duplicate']) for (const enabled of [true, false]) {
                                const button = createButtonSlot('mine', 0, enabled, icon, icon);
                                updateButtonSlot(button, {state, enabled});
                                themeButtons.push(button);
                            }
                        }
                        for (const kind of ['audio', 'notes']) for (const enabled of [true, false]) {
                            themeButtons.push(createButtonSlot(kind, 0, enabled));
                        }
                        container.append(...themeButtons);
                        return {};
                    })()
                """)
                for (dark in listOf(false, true, false)) for (eInk in listOf(false, true, false)) {
                    val result = evaluate(web, """
                        (() => {
                            window.setHoshiPopupTheme({darkMode:$dark, eInkMode:$eInk});
                            return {buttons:themeButtons.map(button => {
                                const css = getComputedStyle(button), icon = getComputedStyle(button.firstElementChild);
                                return {enabled:!button.disabled, kind:button.dataset.kind, color:css.color,
                                    opacity:css.opacity, iconColor:icon.backgroundColor, iconOpacity:icon.opacity,
                                    width:button.getBoundingClientRect().width, background:css.backgroundColor};
                            })};
                        })()
                    """)
                    val buttons = result.getJSONArray("buttons")
                    assertEquals(28, buttons.length())
                    for (index in 0 until buttons.length()) {
                        val button = buttons.getJSONObject(index)
                        val label = "native=$nativeDark dark=$dark eink=$eInk scale=$scale button=$index $button"
                        val expectedColor = if (button.getBoolean("enabled")) {
                            if (dark) "rgb(255, 255, 255)" else "rgb(0, 0, 0)"
                        } else {
                            if (dark) "rgb(102, 102, 102)" else "rgb(153, 153, 153)"
                        }
                        assertEquals(label, expectedColor, button.getString("color"))
                        assertEquals(label, expectedColor, button.getString("iconColor"))
                        assertEquals(label, "1", button.getString("opacity"))
                        assertEquals(label, "1", button.getString("iconOpacity"))
                        assertEquals(label, "rgba(0, 0, 0, 0)", button.getString("background"))
                        assertEquals(label, (if (button.getString("kind") == "notes") 14 else 28) * scale,
                            button.getDouble("width"), 0.1)
                    }
                }
            }
        }
    }

    @Test fun pressedButtonsKeepTheirIconVisibleAndDisabledButtonsHaveNoFeedback() {
        withPopup(false, false) { web ->
            compose.setContent { AndroidView(factory = { web }, modifier = Modifier.fillMaxSize()) }
            compose.waitForIdle()
            evaluate(web, """
                (() => {
                    window.pressedButton = createButtonSlot('mine', 0, true);
                    document.getElementById('entries-container').append(pressedButton);
                    return {};
                })()
            """)
            for (dark in listOf(false, true)) for (eInk in listOf(false, true)) for (enabled in listOf(true, false)) {
                val point = evaluate(web, """
                    (() => {
                        window.setHoshiPopupTheme({darkMode:$dark, eInkMode:$eInk});
                        updateButtonSlot(pressedButton, {enabled:$enabled});
                        const rect = pressedButton.getBoundingClientRect();
                        return {x:rect.x + rect.width / 2, y:rect.y + rect.height / 2, viewport:innerWidth};
                    })()
                """)
                val ratio = web.width / point.getDouble("viewport")
                val downTime = SystemClock.uptimeMillis()
                fun touch(action: Int) {
                    InstrumentationRegistry.getInstrumentation().runOnMainSync {
                        val event = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action,
                            (point.getDouble("x") * ratio).toFloat(), (point.getDouble("y") * ratio).toFloat(), 0)
                        try { web.dispatchTouchEvent(event) } finally { event.recycle() }
                    }
                }
                touch(MotionEvent.ACTION_DOWN)
                try {
                    val deadline = SystemClock.uptimeMillis() + 3000
                    var result: JSONObject
                    do {
                        result = evaluate(web, """
                            (() => {
                                const css = getComputedStyle(pressedButton);
                                return {active:pressedButton.matches(':active'), color:css.color,
                                    background:css.backgroundColor, outline:css.outlineStyle};
                            })()
                        """)
                        if (result.getBoolean("active")) break
                        SystemClock.sleep(20)
                    } while (SystemClock.uptimeMillis() < deadline && enabled)
                    val label = "dark=$dark eink=$eInk enabled=$enabled $result"
                    if (enabled) {
                        assertTrue(label, result.getBoolean("active"))
                        assertEquals(label, if (dark) "rgb(255, 255, 255)" else "rgb(0, 0, 0)", result.getString("color"))
                        assertEquals(label, if (eInk) {
                            if (dark) "rgb(0, 0, 0)" else "rgb(255, 255, 255)"
                        } else "rgba(128, 128, 128, 0.16)", result.getString("background"))
                    } else {
                        assertEquals(label, "rgba(0, 0, 0, 0)", result.getString("background"))
                        assertEquals(label, "none", result.getString("outline"))
                    }
                } finally { touch(MotionEvent.ACTION_CANCEL) }
            }
        }
    }

    private fun withPopup(nativeDark: Boolean, appDark: Boolean, customCss: String = "", scale: Double = 1.0, referenceCss: String? = null, eInk: Boolean = false, block: (WebView) -> Unit) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val base = instrumentation.targetContext
        val config = Configuration(base.resources.configuration).apply {
            uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or
                if (nativeDark) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO
        }
        val context = ContextThemeWrapper(base.createConfigurationContext(config),
            if (nativeDark) android.R.style.Theme_Material_NoActionBar else android.R.style.Theme_Material_Light_NoActionBar)
        val html = if (referenceCss != null) "<html><head><style>$referenceCss</style></head><body></body></html>" else LookupPopupHtml.renderIframeDocument(assets = LookupPopupAssets.load(base), darkMode = appDark, eInkMode = eInk,
            settings = DictionarySettings(customCSS = customCss), popupScale = scale)
        val loaded = CountDownLatch(1)
        lateinit var web: WebView
        instrumentation.runOnMainSync {
            web = WebView(context).apply {
                applyHoshiWebViewSecurityDefaults()
                webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView, url: String) { loaded.countDown() }
                }
                loadDataWithBaseURL("https://appassets.androidplatform.net/popup/", html, "text/html", "utf-8", null)
            }
        }
        try {
            assertTrue("Popup loaded", loaded.await(10, TimeUnit.SECONDS))
            block(web)
        } finally { instrumentation.runOnMainSync { web.destroy() } }
    }

    private fun evaluate(web: WebView, script: String): JSONObject {
        val completed = CountDownLatch(1)
        var result = ""
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            web.evaluateJavascript("(() => { try { return " + script.trim() + "; } catch(e) { return {javascriptError:String(e), stack:e.stack}; } })()") { result = it; completed.countDown() }
        }
        assertTrue("JS completed", completed.await(5, TimeUnit.SECONDS))
        return JSONObject(result)
    }
}
