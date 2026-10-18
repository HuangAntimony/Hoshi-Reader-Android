package moe.antimony.hoshi.features.display

import android.content.Intent
import android.content.res.Configuration
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.Lifecycle
import moe.antimony.hoshi.features.dictionary.ProcessTextLookupActivity
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import moe.antimony.hoshi.MainActivity
import moe.antimony.hoshi.features.dictionary.OpenDictionaryLookupAction
import moe.antimony.hoshi.features.dictionary.OpenDictionaryLookupTextExtra
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Opt-in, populated physical-device acceptance. Back up global display preferences before invoking. */
@RunWith(AndroidJUnit4::class)
class AppThemeDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    @Test fun systemAndAppMatrixKeepsLiveDictionaryWebView() {
        org.junit.Assume.assumeTrue("Explicit device opt-in required",
            InstrumentationRegistry.getArguments().getString("verifyThemeMatrix") == "true")
        val intent = Intent(context, MainActivity::class.java).apply {
            action = OpenDictionaryLookupAction
            putExtra(OpenDictionaryLookupTextExtra, "食べる")
        }
        val originalSystemMode = shell("cmd uimode night").substringAfter(": ").trim()
        val report = mutableListOf<JSONObject>()
        val activity = instrumentation.startActivitySync(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
        try {
            lateinit var vm: DisplaySettingsViewModel
            instrumentation.runOnMainSync { vm = ViewModelProvider(activity)[DisplaySettingsViewModel::class.java] }
            waitUntil { vm.uiState.value.settings != null }
            val original = vm.uiState.value.settings!!
            var retained: WebView? = null
            try {
                for (eInk in listOf(false, true)) for (systemDark in listOf(false, true)) {
                    shell("cmd uimode night ${if (systemDark) "yes" else "no"}")
                    waitUntil { activity.systemThemeObserver.dark.value == systemDark }
                    for (mode in listOf("light", "dark", "auto")) {
                        change(vm) { setEInkMode(false) }
                        change(vm) { setAutoSwitch(mode == "auto", systemDark) }
                        val dark = if (mode == "auto") systemDark else mode == "dark"
                        if (mode != "auto") change(vm) { selectPalettePreset(
                            if (dark) DisplayPaletteSlot.Dark else DisplayPaletteSlot.Light,
                            if (dark) DisplayPalettePreset.Dark else DisplayPalettePreset.Light) }
                        if (eInk) {
                            change(vm) { setEInkDarkTheme(dark) }
                            change(vm) { setEInkMode(true) }
                        }
                        waitUntil { activity.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK ==
                            if (dark) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO }
                        var web: WebView? = null
                        waitUntil {
                            instrumentation.runOnMainSync { web = webViews(activity.window.decorView).firstOrNull() }
                            web != null && evaluate(web!!, "Boolean(document.querySelector('iframe')?.contentWindow?.lookupEntries?.length)") == "true"
                        }
                        if (retained != null) assertSame("theme must retain host WebView", retained, web)
                        retained = web
                        var values = JSONObject()
                        waitUntil {
                            values = JSONObject(evaluate(web!!, """
                                (() => {
                                    const w = document.querySelector('iframe').contentWindow;
                                    const d = w.document;
                                    if (!w.themeTestIdentity) { w.themeTestIdentity = String(Math.random()); w.themeTestEntries = w.lookupEntries; }
                                    return {dark:d.documentElement.dataset.hoshiColorScheme === 'dark',
                                        eink:d.documentElement.dataset.hoshiEinkMode === 'true', media:w.matchMedia('(prefers-color-scheme:dark)').matches,
                                        danger:w.getComputedStyle(d.body).getPropertyValue('--danger-color-lightest').trim(),
                                        identity:w.themeTestIdentity, retained:w.themeTestEntries === w.lookupEntries,
                                        expression:w.lookupEntries[0]?.expression};
                                })()
                            """))
                            values.optBoolean("dark") == dark && values.optBoolean("media") == dark && values.optBoolean("eink") == eInk
                        }
                        assertEquals(systemDark, activity.systemThemeObserver.dark.value)
                        assertEquals(if (dark) "#732317" else "#eeb3aa", values.getString("danger"))
                        assertTrue("lookup results retained", values.getBoolean("retained"))
                        if (report.isNotEmpty()) assertEquals("iframe DOM retained", report.first().getString("identity"), values.getString("identity"))
                        val popup = JSONObject(evaluate(web!!, """
                            (() => {
                                const w = document.querySelector('iframe').contentWindow, d = w.document;
                                w.showAudioCandidateMenu(0, d.querySelector('button[data-kind="audio"]'));
                                const menu = d.querySelector('.audio-candidate-menu');
                                const box = d.querySelector('[data-sc-hinkaku]');
                                return {menu:w.getComputedStyle(menu).backgroundColor, text:w.getComputedStyle(menu).color,
                                    box:box ? w.getComputedStyle(box).backgroundColor : null};
                            })()
                        """))
                        assertEquals(if (dark) { if (eInk) "rgb(0, 0, 0)" else "rgb(51, 51, 51)" } else "rgb(255, 255, 255)", popup.getString("menu"))
                        assertEquals(if (dark) "rgb(255, 255, 255)" else "rgb(0, 0, 0)", popup.getString("text"))
                        assertEquals(if (dark) "rgb(115, 35, 23)" else "rgb(238, 179, 170)", popup.getString("box"))
                        values.put("popup", popup).put("systemDark", systemDark).put("mode", mode)
                        report += values
                        instrumentation.sendStatus(0, android.os.Bundle().apply { putString("themeMatrix", values.toString()) })
                        // Allow Chromium to present the newly opened native-composited menu.
                        SystemClock.sleep(250)
                        val screenshot = instrumentation.uiAutomation.takeScreenshot()
                        File(context.cacheDir, "theme-$systemDark-$mode-$eInk.png").outputStream().use {
                            screenshot.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
                        }
                        evaluate(web!!, "document.querySelector('iframe').contentWindow.closeAudioCandidateMenu()")
                    }
                }
                // Actual system configuration changes while the app is stopped, then resumed.
                change(vm) { setEInkMode(false) }
                change(vm) { setAutoSwitch(true, true) }
                for (dark in listOf(false, true)) {
                    shell("input keyevent KEYCODE_HOME")
                    waitUntil { activity.lifecycle.currentState == Lifecycle.State.CREATED }
                    shell("cmd uimode night ${if (dark) "yes" else "no"}")
                    shell("am start -W -n ${context.packageName}/moe.antimony.hoshi.MainActivity -f 0x30020000")
                    waitUntil { activity.lifecycle.currentState == Lifecycle.State.RESUMED }
                    waitUntil { activity.systemThemeObserver.dark.value == dark &&
                        evaluate(retained!!, "document.querySelector('iframe').contentWindow.matchMedia('(prefers-color-scheme:dark)').matches") == dark.toString() }
                }
                // Custom backgrounds do not choose native brightness; the palette group does.
                change(vm) { setAutoSwitch(false, true) }
                for ((slot, preset) in listOf(DisplayPaletteSlot.Light to DisplayPalettePreset.Sepia,
                    DisplayPaletteSlot.Dark to DisplayPalettePreset.DarkSepia,
                    DisplayPaletteSlot.Light to DisplayPalettePreset.Custom,
                    DisplayPaletteSlot.Dark to DisplayPalettePreset.Custom)) {
                    val dark = slot == DisplayPaletteSlot.Dark
                    if (preset == DisplayPalettePreset.Custom) {
                        instrumentation.runOnMainSync {
                            vm.openPaletteEditor(slot)
                            vm.updatePaletteDraft(backgroundColor = if (dark) 0xFFFFFFFFL else 0xFF000000L)
                        }
                        change(vm) { savePaletteDraft() }
                    } else change(vm) { selectPalettePreset(slot, preset) }
                    waitUntil { evaluate(retained!!, "document.querySelector('iframe').contentWindow.matchMedia('(prefers-color-scheme:dark)').matches") == dark.toString() }
                    assertEquals(true, activity.systemThemeObserver.dark.value)
                }
                // The second entry point must initialize from confirmed persisted settings too.
                change(vm) { selectPalettePreset(DisplayPaletteSlot.Light, DisplayPalettePreset.Light) }
                val external = Intent(context, ProcessTextLookupActivity::class.java).apply {
                    action = Intent.ACTION_PROCESS_TEXT
                    type = "text/plain"
                    putExtra(Intent.EXTRA_PROCESS_TEXT, "食べる")
                }
                val overlay = instrumentation.startActivitySync(external.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as ProcessTextLookupActivity
                try {
                    var overlayWeb: WebView? = null
                    waitUntil {
                        instrumentation.runOnMainSync { overlayWeb = webViews(overlay.window.decorView).firstOrNull() }
                        overlayWeb != null && evaluate(overlayWeb!!, "Boolean(document.querySelector('iframe')?.contentWindow?.lookupEntries?.length)") == "true"
                    }
                    assertEquals("false", evaluate(overlayWeb!!, "document.querySelector('iframe').contentWindow.matchMedia('(prefers-color-scheme:dark)').matches"))
                    assertEquals("\"light\"", evaluate(overlayWeb!!, "document.querySelector('iframe').contentDocument.documentElement.dataset.hoshiColorScheme"))
                } finally {
                    instrumentation.runOnMainSync { overlay.finish() }
                    waitUntil { activity.lifecycle.currentState == Lifecycle.State.RESUMED }
                }
            } finally {
                // Restore the visible preferences even if an assertion fails; the documented backup
                // also restores nullable E Ink override and remembered palette slots byte-for-byte.
                change(vm) { setEInkMode(false) }
                change(vm) { setAutoSwitch(false, originalSystemMode == "yes") }
                change(vm) { selectPalettePreset(original.manualPaletteSlot, original.selection(original.manualPaletteSlot).preset) }
                change(vm) { setAutoSwitch(original.autoSwitch, originalSystemMode == "yes") }
                change(vm) { setEInkMode(original.eInkMode) }
                shell("cmd uimode night $originalSystemMode")
                File(context.cacheDir, "theme-matrix.json").writeText(org.json.JSONArray(report).toString(2))
            }
        } finally {
            instrumentation.runOnMainSync { activity.finish() }
        }
        assertEquals(12, report.size)
    }

    @Test fun coldNativeThemeIsAppliedBeforeFirstLookupWebView() {
        org.junit.Assume.assumeTrue("Explicit device opt-in required",
            InstrumentationRegistry.getArguments().getString("verifyThemeMatrix") == "true")
        val processText = InstrumentationRegistry.getArguments().getString("themeEntry") == "processText"
        val intent = if (processText) Intent(context, ProcessTextLookupActivity::class.java).apply {
            action = Intent.ACTION_PROCESS_TEXT
            type = "text/plain"
            putExtra(Intent.EXTRA_PROCESS_TEXT, "食べる")
        } else Intent(context, MainActivity::class.java).apply {
            action = OpenDictionaryLookupAction
            putExtra(OpenDictionaryLookupTextExtra, "食べる")
        }
        val activity = instrumentation.startActivitySync(intent.apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }) as androidx.appcompat.app.AppCompatActivity
        try {
            var web: WebView? = null
            waitUntil {
                instrumentation.runOnMainSync { web = webViews(activity.window.decorView).firstOrNull() }
                web != null && evaluate(web!!, "Boolean(document.querySelector('iframe')?.contentWindow?.lookupEntries?.length)") == "true"
            }
            val actual = JSONObject(evaluate(web!!, """
                (() => { const w=document.querySelector('iframe').contentWindow; return {
                    dark:w.document.documentElement.dataset.hoshiColorScheme === 'dark',
                    media:w.matchMedia('(prefers-color-scheme:dark)').matches,
                    danger:w.getComputedStyle(w.document.body).getPropertyValue('--danger-color-lightest').trim()}; })()
            """))
            instrumentation.runOnMainSync {
                val light = android.util.TypedValue()
                activity.theme.resolveAttribute(android.R.attr.isLightTheme, light, true)
                actual.put("activityIsLight", light.data != 0).put("activityUiMode", activity.resources.configuration.uiMode)
                web!!.context.theme.resolveAttribute(android.R.attr.isLightTheme, light, true)
                actual.put("webContextIsLight", light.data != 0).put("webContextUiMode", web!!.context.resources.configuration.uiMode)
                actual.put("systemDark", when (activity) {
                    is MainActivity -> activity.systemThemeObserver.dark.value
                    is ProcessTextLookupActivity -> activity.systemThemeObserver.dark.value
                    else -> error("Unexpected entry Activity")
                }).put("entry", if (processText) "processText" else "main")
            }
            instrumentation.sendStatus(0, android.os.Bundle().apply { putString("coldTheme", actual.toString()) })
            assertEquals(actual.toString(), actual.getBoolean("dark"), actual.getBoolean("media"))
            InstrumentationRegistry.getArguments().getString("expectedDark")?.let {
                assertEquals(it.toBoolean(), actual.getBoolean("dark"))
            }
            InstrumentationRegistry.getArguments().getString("themeSystemDark")?.let {
                assertEquals(it.toBoolean(), actual.getBoolean("systemDark"))
            }
            assertEquals(!actual.getBoolean("dark"), actual.getBoolean("activityIsLight"))
            assertEquals(if (actual.getBoolean("dark")) "#732317" else "#eeb3aa", actual.getString("danger"))
        } finally { instrumentation.runOnMainSync { activity.finish() } }
    }

    /** Invoke separately before a fresh instrumentation process runs the cold-entry assertion. */
    @Test fun preparePersistedThemeForColdStart() {
        val args = InstrumentationRegistry.getArguments()
        org.junit.Assume.assumeTrue("Explicit preparation arguments required",
            args.getString("verifyThemeMatrix") == "true" && args.containsKey("themeMode"))
        val mode = requireNotNull(args.getString("themeMode"))
        require(mode in listOf("light", "dark", "auto"))
        val systemDark = args.getString("themeSystemDark") == "true"
        val eInk = args.getString("themeEInk") == "true"
        shell("cmd uimode night ${if (systemDark) "yes" else "no"}")
        val activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
        try {
            lateinit var vm: DisplaySettingsViewModel
            instrumentation.runOnMainSync { vm = ViewModelProvider(activity)[DisplaySettingsViewModel::class.java] }
            waitUntil { vm.uiState.value.settings != null && activity.systemThemeObserver.dark.value == systemDark }
            change(vm) { setEInkMode(false) }
            change(vm) { setAutoSwitch(mode == "auto", systemDark) }
            val dark = if (mode == "auto") systemDark else mode == "dark"
            change(vm) { selectPalettePreset(if (dark) DisplayPaletteSlot.Dark else DisplayPaletteSlot.Light,
                if (dark) DisplayPalettePreset.Dark else DisplayPalettePreset.Light) }
            change(vm) { setEInkDarkTheme(dark) }
            change(vm) { setEInkMode(eInk) }
            waitUntil { activity.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK ==
                if (dark) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO }
        } finally { instrumentation.runOnMainSync { activity.finish() } }
    }

    private fun change(vm: DisplaySettingsViewModel, action: DisplaySettingsViewModel.() -> Unit) {
        instrumentation.runOnMainSync { vm.action() }
        waitUntil { !vm.uiState.value.isSaving }
        assertNull(vm.uiState.value.error)
        instrumentation.waitForIdleSync()
    }
    private fun webViews(view: View): List<WebView> = when (view) {
        is WebView -> listOf(view)
        is ViewGroup -> (0 until view.childCount).flatMap { webViews(view.getChildAt(it)) }
        else -> emptyList()
    }
    private fun shell(command: String): String = instrumentation.uiAutomation.executeShellCommand(command)
        .use { android.os.ParcelFileDescriptor.AutoCloseInputStream(it).bufferedReader().readText() }
    private fun evaluate(web: WebView, script: String): String {
        val latch = CountDownLatch(1)
        var result = ""
        instrumentation.runOnMainSync { web.evaluateJavascript(script) { result = it; latch.countDown() } }
        check(latch.await(5, TimeUnit.SECONDS))
        return result
    }
    private fun waitUntil(condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 15000
        while (!condition()) {
            check(SystemClock.uptimeMillis() < deadline) { "Theme/lookup did not settle within 15 seconds" }
            SystemClock.sleep(50)
        }
    }
}
