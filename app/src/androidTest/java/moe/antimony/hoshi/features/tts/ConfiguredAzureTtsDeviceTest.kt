package moe.antimony.hoshi.features.tts

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Explicit opt-in only: uses the existing Azure configuration entirely inside the app. */
@RunWith(AndroidJUnit4::class)
class ConfiguredAzureTtsDeviceTest {
    @Test fun configuredAzureHonorsExplicitEllipsisDuration() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("hoshiAzureTtsLive") == "true")
        val context = ApplicationProvider.getApplicationContext<Context>()
        val preferences = TtsRepository(context).preferences.first()
        val source = TtsOnlineSource.Azure
        assumeTrue(preferences.settings.online[source.id]?.encryptedKey?.isNotBlank() == true)
        val engine = OnlineTtsEngine(OnlineTtsTransport(), TtsCredentialCipher(Dispatchers.IO), Dispatchers.IO)
        val config = TtsSettings(engine = source.id, online = preferences.settings.online)
        val catalog = engine.catalog(config)
        val voice = catalog.voices.firstOrNull { it.id == "ja-JP-NanamiNeural" } ?: catalog.voices.first()
        val directory = File(context.cacheDir, "azure-ellipsis-device-test")
        try {
            suspend fun synthesize(pause: Int): File = engine.synthesize("こんにちは……お元気ですか。",
                config.copy(voice = voice.id).withOptions(TtsSynthesisOptions(ellipsisPauseMs = pause)), directory).single()
            val short = synthesize(200)
            val long = synthesize(1200)
            // Both requests explicitly use 24 kHz mono 16-bit PCM with otherwise identical settings.
            val addedSeconds = (long.length() - short.length()) / 48000.0
            assertTrue("A 1-second longer explicit break should lengthen the audio", addedSeconds > .7 && addedSeconds < 1.3)
        } finally { directory.deleteRecursively() }
    }
}
