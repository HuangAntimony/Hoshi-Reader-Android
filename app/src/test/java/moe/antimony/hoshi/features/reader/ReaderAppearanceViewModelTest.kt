package moe.antimony.hoshi.features.reader

import java.io.File
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.job
import kotlin.io.path.createTempDirectory
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import moe.antimony.hoshi.ui.resolve
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ReaderAppearanceViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val viewModels = mutableListOf<ReaderAppearanceViewModel>()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() = runTest(dispatcher) {
        viewModels.forEach { it.viewModelScope.coroutineContext.job.cancelAndJoin() }
        Dispatchers.resetMain()
    }

    @Test
    fun completedSelectionRemainsPendingUntilUiAcknowledgesIt() = runTest(dispatcher) {
        val viewModel = viewModel()
        val family = viewModel.uiState.value.library.families.first {
            it.source == ReaderFontSource.SYSTEM
        }
        val variant = family.variants.first { it.weight == 400 }
        val expected = ReaderFontSelection(family.id, variant.id)

        viewModel.selectVariant(family.id, variant.id, ReaderFontDownloadOrigin.FAMILY)
        runCurrent()

        assertEquals(expected, viewModel.uiState.value.selectionToApply)

        viewModel.acknowledgeSelection(expected)
        runCurrent()

        assertNull(viewModel.uiState.value.selectionToApply)
    }

    @Test
    fun activeDownloadKeepsAcceptedOriginWhenAnotherSelectionIsAttempted() = runTest(dispatcher) {
        val downloadStarted = CompletableDeferred<Unit>()
        val releaseDownload = CompletableDeferred<Unit>()
        val viewModel = viewModel(
            remoteDataSource = object : ReaderFontRemoteDataSource {
                override suspend fun open(remoteFile: ReaderRemoteFontFile): ReaderFontRemoteResponse {
                    downloadStarted.complete(Unit)
                    releaseDownload.await()
                    error("Download should have been cancelled by the test.")
                }
            },
        )
        val family = viewModel.uiState.value.library.families.first {
            it.id == "recommended:kleeone"
        }
        val regular = family.variants.first { it.weight == 400 }
        val semiBold = family.variants.first { it.weight == 600 }

        viewModel.selectVariant(family.id, regular.id, ReaderFontDownloadOrigin.FAMILY)
        downloadStarted.await()
        runCurrent()
        viewModel.selectVariant(family.id, semiBold.id, ReaderFontDownloadOrigin.VARIANT)
        runCurrent()

        val download = requireNotNull(viewModel.uiState.value.download)
        assertEquals(family.id, download.familyId)
        assertEquals(regular.id, download.variantId)
        assertEquals(ReaderFontDownloadOrigin.FAMILY, download.origin)

        viewModel.cancelDownload()
        advanceUntilIdle()
    }

    @Test
    fun fontDownloadErrorRetainsServerAndUnderlyingReasonForRetry() = runTest(dispatcher) {
        val viewModel = viewModel(object : ReaderFontRemoteDataSource {
            override suspend fun open(remoteFile: ReaderRemoteFontFile): ReaderFontRemoteResponse {
                throw java.io.IOException("Font server rejected request", java.io.IOException("HTTP 429 quota exhausted"))
            }
        })
        val family = viewModel.uiState.value.library.families.first { it.id == "recommended:kleeone" }
        val variant = family.variants.first { it.weight == 400 }
        viewModel.selectVariant(family.id, variant.id, ReaderFontDownloadOrigin.FAMILY)
        runCurrent()
        val state = viewModel.uiState.value
        val message = requireNotNull(state.error).resolve({ _, _ -> "Font download failed" }, { _, _, _ -> "" })
        assertTrue(message.contains("Font download failed"))
        assertTrue(message.contains("Font server rejected request"))
        assertTrue(message.contains("HTTP 429 quota exhausted"))
        assertEquals(ReaderFontSelectionRequest(family.id, variant.id, ReaderFontDownloadOrigin.FAMILY), state.failedSelection)
        assertNull(state.download)
        assertNull(state.selectionToApply)
    }

    private fun viewModel(
        remoteDataSource: ReaderFontRemoteDataSource = HttpReaderFontRemoteDataSource(),
    ): ReaderAppearanceViewModel {
        val fontManager = ReaderFontManager(File(createTempDirectory().toFile(), "files"))
        return ReaderAppearanceViewModel(
            fontManager = fontManager,
            downloaderFactory = ReaderFontDownloaderFactory(remoteDataSource, dispatcher),
            ioDispatcher = dispatcher,
        ).also(viewModels::add)
    }
}
