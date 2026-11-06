package moe.antimony.hoshi.features.reader

import android.webkit.ValueCallback
import android.webkit.WebView
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Test

/** Only an isolated WebView and captured callbacks; no persisted reader state. */
class ReaderTtsCueInstallationDeviceTest {
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
