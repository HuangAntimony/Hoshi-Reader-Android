package moe.antimony.hoshi.features.reader

import android.webkit.ValueCallback
import android.webkit.WebView
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Test

/** Only an isolated WebView and captured callbacks; no persisted reader state. */
class ReaderTtsCueInstallationDeviceTest {
    @Test fun restoredDocumentsDoNotReinstallTheirInitialCuesButAcceptChangesAndNewVersions() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            val scripts = mutableListOf<String>()
            val callbacks = mutableListOf<ValueCallback<String>>()
            val view = object : WebView(instrumentation.targetContext) {
                override fun evaluateJavascript(script: String, resultCallback: ValueCallback<String>?) {
                    scripts += script
                    resultCallback?.let(callbacks::add)
                }
            }
            try {
                for (initial in listOf(null, "[]", "[{\"id\":\"cue\",\"start\":0,\"length\":2}]")) {
                    scripts.clear()
                    callbacks.clear()
                    var ready = 0
                    val version = Any()
                    view.evaluateReaderSetupScript("setup", "chapter", initial, version)
                    // Production only delivers cue updates after this setup's restore is accepted.
                    view.applyReaderSasayakiCues("chapter", initial ?: "[]", version) { ready++ }
                    assertEquals("The setup already installed this snapshot", listOf("setup"), scripts)
                    assertEquals(1, ready)
                    view.applyReaderSasayakiCues("chapter", initial ?: "[]", Any()) { ready++ }
                    assertEquals("A new segmentation must still be installed", 2, scripts.size)
                    callbacks.removeAt(0).onReceiveValue("null")
                    assertEquals(2, ready)
                    val changed = if (initial == null || initial == "[]") "[{\"id\":\"changed\"}]" else "[]"
                    view.applyReaderSasayakiCues("chapter", changed, version) { ready++ }
                    assertEquals("Changed content, including explicit clearing, must be delivered", 3, scripts.size)
                    view.evaluateReaderSetupScript("next setup", "next-chapter", null, null)
                    callbacks.removeAt(0).onReceiveValue("null")
                    assertEquals("The old document's pending update cannot become ready", 2, ready)
                    view.applyReaderSasayakiCues("next-chapter", "[]") { ready++ }
                    assertEquals("A new empty document needs no clearing operation", "next setup", scripts.last())
                    assertEquals(3, ready)
                }
            } finally { view.destroy() }
        }
    }

    @Test fun oldInstallCallbacksCannotMarkANewSegmentationReadyEvenWhenJsonRepeats() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            val callbacks = mutableListOf<ValueCallback<String>>()
            val installed = mutableListOf<Int>()
            val view = object : WebView(instrumentation.targetContext) {
                override fun evaluateJavascript(script: String, resultCallback: ValueCallback<String>?) {
                    resultCallback?.let(callbacks::add)
                }
            }
            try {
                view.applyReaderSasayakiCues("chapter", "[]", Any()) { installed += 1 }
                view.applyReaderSasayakiCues("chapter", "[{\"id\":\"fine\"}]", Any()) { installed += 2 }
                view.applyReaderSasayakiCues("chapter", "[]", Any()) { installed += 3 }
                assertEquals(3, callbacks.size)
                callbacks[1].onReceiveValue("null")
                callbacks[0].onReceiveValue("null")
                assertEquals(emptyList<Int>(), installed)
                callbacks[2].onReceiveValue("null")
                assertEquals(listOf(3), installed)
            } finally { view.destroy() }
        }
    }
}
