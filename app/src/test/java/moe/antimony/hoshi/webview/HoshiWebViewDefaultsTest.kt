package moe.antimony.hoshi.webview

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HoshiWebViewDefaultsTest {
    @Test
    fun secureDefaultsEnableJavascriptButRejectBroadFileAndContentAccess() {
        val settings = FakeHoshiWebViewSettings()

        settings.applyHoshiWebViewSecurityDefaults()

        assertTrue(settings.javaScriptEnabled)
        assertFalse(settings.domStorageEnabled)
        assertFalse(settings.allowFileAccess)
        assertFalse(settings.allowContentAccess)
    }
}

private class FakeHoshiWebViewSettings : HoshiWebViewSettings {
    override var javaScriptEnabled: Boolean = false
    override var domStorageEnabled: Boolean = true
    override var allowFileAccess: Boolean = true
    override var allowContentAccess: Boolean = true
    override var forceDarkAllowed: Boolean = true
    override var algorithmicDarkeningAllowed: Boolean = true
}
