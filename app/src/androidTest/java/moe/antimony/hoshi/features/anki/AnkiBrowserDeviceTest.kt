package moe.antimony.hoshi.features.anki

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AnkiBrowserDeviceTest {
    @Test
    fun opensBrowserFromHoshiWithoutChangingCards() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("ankiBrowserSmoke") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val api = AndroidAnkiContentApi(context)

        assertTrue("AnkiDroid must be installed and initialized", api.isAvailable())
        assertTrue("Hoshi must be allowed to open AnkiDroid's browser", api.openNotes("nid:0"))
    }
}
