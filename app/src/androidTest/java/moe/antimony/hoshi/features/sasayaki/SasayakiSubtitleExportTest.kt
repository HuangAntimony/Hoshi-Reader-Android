package moe.antimony.hoshi.features.sasayaki

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.core.app.ActivityOptionsCompat
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import moe.antimony.hoshi.R
import moe.antimony.hoshi.epub.SasayakiMatch
import moe.antimony.hoshi.epub.SasayakiMatchData
import moe.antimony.hoshi.epub.SasayakiMatchSource
import moe.antimony.hoshi.ui.UiText
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SasayakiSubtitleExportTest {
    @get:Rule val compose = createComposeRule()
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val repository = SasayakiSubtitleExportRepository(context.contentResolver, context.cacheDir, Dispatchers.IO)
    private val files = mutableListOf<File>()
    private val models = ViewModelStore()
    private fun match(source: SasayakiMatchSource) = SasayakiMatchData(
        listOf(SasayakiMatch("cue", 1.25, 3.5, "日本語の字幕\n二行目", 0, 0, 10)), 0, source,
    )

    @After fun cleanUp() {
        androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().runOnMainSync { models.clear() }
        files.forEach { file ->
            file.delete()
            if (file.parentFile?.parentFile?.name == "sasayaki-subtitles") file.parentFile?.delete()
        }
    }

    @Test fun bothSourcesShareReadableUtf8FilesWithIndependentSnapshots() = runBlocking {
        val first = repository.prepare("本/題", match(SasayakiMatchSource.Transcription)).also(files::add)
        val original = first.readBytes()
        val second = repository.prepare("本/題", match(SasayakiMatchSource.Subtitles).copy(matches = listOf(
            SasayakiMatch("new", 4.0, 5.0, "新しい字幕", 0, 20, 5),
        ))).also(files::add)
        assertEquals("本_題.srt", first.name)
        assertNotEquals(first, second)
        assertArrayEquals(original, first.readBytes())
        for (file in listOf(first, second)) {
            val intent = sasayakiSubtitleShareIntent(context, file)
            assertEquals(Intent.ACTION_SEND, intent.action)
            assertEquals(SasayakiSrt.mimeType, intent.type)
            assertTrue(intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
            @Suppress("DEPRECATION") val uri = intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)!!
            assertEquals("content", uri.scheme)
            assertEquals(uri, intent.clipData!!.getItemAt(0).uri)
            val received = context.contentResolver.openInputStream(uri)!!.use { it.readBytes() }
            assertArrayEquals(file.readBytes(), received)
            assertEquals(1, SasayakiParser.parseCues(received).size)
        }
    }

    @Test fun saveUsesPreparedSnapshotAfterViewModelRecreation() = runBlocking {
        val saved = SavedStateHandle()
        val first = model(saved)
        val data = match(SasayakiMatchSource.Transcription)
        withContext(Dispatchers.Main) { first.prepare("本", data, SasayakiSubtitleExportAction.Save) }
        val request = withTimeout(5000) { first.uiState.first { it.request != null }.request!! }
        files += request.file
        val restoredState = withContext(Dispatchers.Main) {
            first.launched()
            SavedStateHandle(saved.keys().associateWith { saved.get<Any>(it) })
        }
        val restored = model(restoredState)
        val destination = File.createTempFile("hoshi-srt-test-", ".srt", context.cacheDir).also(files::add)
        withContext(Dispatchers.Main) { restored.save(Uri.fromFile(destination)) }
        val result = withTimeout(5000) { restored.uiState.first { !it.busy } }
        assertEquals(UiText.Resource(R.string.sasayaki_srt_saved), result.message)
        assertEquals(SasayakiSrt.encode(data), destination.readText())
        assertNull(result.request)
    }

    @Test fun cancelAndFailedSaveAllowRetryWithoutStalePickerRequests() = runBlocking {
        val model = model(SavedStateHandle())
        suspend fun prepare(): File {
            withContext(Dispatchers.Main) { model.prepare("本", match(SasayakiMatchSource.Subtitles), SasayakiSubtitleExportAction.Save) }
            val request = withTimeout(5000) { model.uiState.first { it.request != null }.request!! }
            files += request.file
            withContext(Dispatchers.Main) { model.launched() }
            assertNull(model.uiState.value.request)
            return request.file
        }
        prepare()
        withContext(Dispatchers.Main) { model.save(null) }
        assertFalse(model.uiState.value.busy)
        assertNull(model.uiState.value.message)
        val missing = prepare()
        missing.delete()
        val destination = File.createTempFile("hoshi-srt-test-", ".srt", context.cacheDir).also(files::add)
        destination.writeText("keep")
        withContext(Dispatchers.Main) { model.save(Uri.fromFile(destination)) }
        val failed = withTimeout(5000) { model.uiState.first { !it.busy } }
        assertEquals(UiText.Resource(R.string.sasayaki_srt_export_failed), failed.message)
        assertEquals("keep", destination.readText())
        prepare()
        withContext(Dispatchers.Main) { model.launchFailed() }
        assertFalse(model.uiState.value.busy)
        assertNull(model.uiState.value.request)
    }

    @Test fun pickerResultSurvivesHiddenPanelAndRestoredComposition() = runBlocking {
        val saved = SavedStateHandle()
        val model = model(saved)
        var launchedCode: Int? = null
        var launches = 0
        val registry = object : ActivityResultRegistry() {
            override fun <I, O> onLaunch(requestCode: Int, contract: ActivityResultContract<I, O>, input: I, options: ActivityOptionsCompat?) {
                launchedCode = requestCode
                launches++
            }
        }
        val owner = object : ActivityResultRegistryOwner { override val activityResultRegistry = registry }
        val panelVisible = mutableStateOf(true)
        val restoration = StateRestorationTester(compose)
        restoration.setContent {
            CompositionLocalProvider(LocalActivityResultRegistryOwner provides owner) {
                SasayakiSubtitleExportHost(model)
                if (panelVisible.value) SasayakiSubtitleExportControl("本", match(SasayakiMatchSource.Subtitles), model)
            }
        }
        withContext(Dispatchers.Main) { model.prepare("本", match(SasayakiMatchSource.Subtitles), SasayakiSubtitleExportAction.Save) }
        compose.waitUntil { launchedCode != null }
        files += saved.keys().mapNotNull { saved.get<String>(it)?.let(::File) }
        val destination = File.createTempFile("hoshi-srt-test-", ".srt", context.cacheDir).also(files::add)
        compose.runOnIdle { panelVisible.value = false }
        restoration.emulateSavedInstanceStateRestore()
        compose.runOnIdle { registry.dispatchResult(launchedCode!!, Activity.RESULT_OK, Intent().setData(Uri.fromFile(destination))) }
        compose.waitUntil { !model.uiState.value.busy }
        assertEquals(SasayakiSrt.encode(match(SasayakiMatchSource.Subtitles)), destination.readText())
        assertEquals(1, launches)
        assertNull(model.uiState.value.request)
    }

    private suspend fun model(saved: SavedStateHandle) = withContext(Dispatchers.Main) {
        SasayakiSubtitleExportViewModel(repository, saved).also { models.put("model-${System.nanoTime()}", it) }
    }
}
