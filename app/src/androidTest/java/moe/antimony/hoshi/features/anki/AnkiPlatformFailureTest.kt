package moe.antimony.hoshi.features.anki

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AnkiPlatformFailureTest {
    @Test fun syncKeepsTheOriginalActivityLaunchFailure() {
        val original = ActivityNotFoundException("AnkiDroid sync activity is unavailable")
        val api = failingApi(original)

        assertSame(original, runCatching { api.sync() }.exceptionOrNull())
    }

    @Test fun browserKeepsTheOriginalActivityLaunchFailure() {
        val original = SecurityException("AnkiDroid browser activity access was denied")
        val api = failingApi(original)

        assertSame(original, runCatching { api.openNotes("猫") }.exceptionOrNull())
    }

    private fun failingApi(error: Throwable): AndroidAnkiContentApi {
        val context = object : ContextWrapper(ApplicationProvider.getApplicationContext<Context>()) {
            override fun getApplicationContext(): Context = this
            override fun startActivity(intent: Intent) { throw error }
        }
        return AndroidAnkiContentApi(context)
    }
}
