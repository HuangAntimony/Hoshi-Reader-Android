package moe.antimony.hoshi.features.wallpaper

import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.runCurrent
import moe.antimony.hoshi.ui.resolve
import moe.antimony.hoshi.R
import moe.antimony.hoshi.ui.ErrorDiagnosticEvents
import moe.antimony.hoshi.ui.UiText
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BookCoverWallpaperViewModelTest {
    @Test
    fun exposesIReaderCapabilityForSettingsUi() {
        val capability = IReaderBookCoverCapability(
            isSupported = true,
            isBookCoverScreenSaverSelected = false,
        )
        val viewModel = viewModel(
            BookCoverWallpaperSettings(),
            FakePublisher(),
            iReaderCapability = capability,
        )

        assertEquals(capability, viewModel.iReaderCapability())
    }

    @Test
    fun missingCoverIsSkippedWhenFeatureIsDisabled() = runBlocking {
        val publisher = FakePublisher()
        val viewModel = viewModel(BookCoverWallpaperSettings(), publisher)

        val result = viewModel.publishCurrentCover(null)

        assertEquals(BookCoverPublishResult.Skipped, result)
        assertEquals(0, publisher.callCount)
    }

    @Test
    fun missingCoverFailsOnlyEnabledTargets() = runBlocking {
        val publisher = FakePublisher()
        val viewModel = viewModel(
            BookCoverWallpaperSettings(
                updateLockScreen = true,
                updateIReaderBookCover = true,
                exportEnabled = true,
            ),
            publisher,
        )

        val result = viewModel.publishCurrentCover(null)

        assertEquals(
            BookCoverTargetResult.Failed(BookCoverPublishFailure.MissingCover),
            result.lockScreen,
        )
        assertEquals(
            BookCoverTargetResult.Failed(BookCoverPublishFailure.MissingCover),
            result.export,
        )
        assertEquals(
            BookCoverTargetResult.Failed(BookCoverPublishFailure.MissingCover),
            result.iReader,
        )
        assertTrue(result.hasFailures)
        assertEquals(0, publisher.callCount)
    }

    @Test
    fun existingCoverIsPublishedExactlyOncePerInvocation() = runBlocking {
        val publisher = FakePublisher()
        val viewModel = viewModel(BookCoverWallpaperSettings(updateLockScreen = true), publisher)
        val cover = File("cover.jpg")

        val result = viewModel.publishCurrentCover(cover)

        assertFalse(result.hasFailures)
        assertEquals(1, publisher.callCount)
        assertEquals(cover, publisher.lastCover)
    }

    @Test
    fun unexpectedPublisherFailureIsReturnedInsteadOfEscapingReaderEffect() = runBlocking {
        val viewModel = viewModel(
            BookCoverWallpaperSettings(updateLockScreen = true),
            FakePublisher(throws = true),
        )

        val result = viewModel.publishCurrentCover(File("cover.jpg"))

        val failure = result.lockScreen as BookCoverTargetResult.Failed
        assertEquals(BookCoverPublishFailure.UnexpectedFailure, failure.reason)
        assertTrue(failure.details.orEmpty().contains("publisher failed"))
        assertEquals(failure, result.lockScreen)
        assertEquals(failure, result.export)
        assertEquals(failure, result.iReader)
    }

    @Test
    fun missingCoverSettingsFailureIsReturnedInsteadOfEscapingReaderEffect() = runBlocking {
        val viewModel = BookCoverWallpaperViewModel(
            settings = flow { throw IllegalStateException("settings unavailable") },
            updateSettings = {},
            capabilityProvider = BookCoverWallpaperCapabilityProvider {
                BookCoverWallpaperCapability(isSupported = true, isSetAllowed = true)
            },
            iReaderCapabilityProvider = IReaderBookCoverCapabilityProvider {
                IReaderBookCoverCapability(
                    isSupported = true,
                    isBookCoverScreenSaverSelected = true,
                )
            },
            publisher = FakePublisher(),
            coroutineScope = null,
        )

        val result = viewModel.publishCurrentCover(null)

        val failure = result.lockScreen as BookCoverTargetResult.Failed
        assertEquals(BookCoverPublishFailure.SettingsUnavailable, failure.reason)
        assertTrue(failure.details.orEmpty().contains("settings unavailable"))
        assertEquals(failure, result.lockScreen)
        assertEquals(failure, result.export)
        assertEquals(failure, result.iReader)
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test
    fun failedSettingsWriteExposesItsSpecificReason() = runTest {
        val viewModel = BookCoverWallpaperViewModel(
            settings = MutableStateFlow(BookCoverWallpaperSettings()),
            updateSettings = { throw java.io.IOException("wallpaper preferences storage is read only") },
            capabilityProvider = BookCoverWallpaperCapabilityProvider {
                BookCoverWallpaperCapability(isSupported = true, isSetAllowed = true)
            },
            iReaderCapabilityProvider = IReaderBookCoverCapabilityProvider {
                IReaderBookCoverCapability(isSupported = true, isBookCoverScreenSaverSelected = true)
            },
            publisher = FakePublisher(),
            coroutineScope = backgroundScope,
        )
        viewModel.setExportEnabled(true)
        runCurrent()
        val message = viewModel.error.value?.resolve({ _, _ -> "Save failed" }, { _, _, _ -> "" }).orEmpty()
        assertTrue(message.contains("wallpaper preferences storage is read only"))
    }

    @Test
    fun unexpectedPublisherFailurePublishesTheOriginalCauseForDiagnostics() = runBlocking {
        ErrorDiagnosticEvents.buffer.drain()
        val cause = java.io.IOException("disk quota exceeded")
        val error = IllegalStateException("Wallpaper renderer unavailable", cause)
        val viewModel = viewModel(
            BookCoverWallpaperSettings(updateLockScreen = true),
            object : BookCoverPublisher {
                override suspend fun publish(coverFile: File): BookCoverPublishResult = throw error
            },
        )

        val result = viewModel.publishCurrentCover(File("cover.jpg"))
        val event = ErrorDiagnosticEvents.buffer.drain().singleOrNull()

        assertTrue(result.hasFailures)
        assertNotNull("The ViewModel's fallback must retain a diagnostic event", event)
        assertSame(error, event!!.error)
        assertSame(cause, requireNotNull(event.error).cause)
        assertEquals("Wallpaper renderer unavailable", requireNotNull(event.error).message)
        assertEquals(UiText.Resource(R.string.book_cover_wallpaper_publish_failed), event.operation)
    }

    @Test
    fun publisherCancellationStillCancelsReaderEffect() = runBlocking {
        val viewModel = viewModel(
            BookCoverWallpaperSettings(updateLockScreen = true),
            FakePublisher(cancels = true),
        )

        try {
            viewModel.publishCurrentCover(File("cover.jpg"))
            throw AssertionError("Expected cancellation")
        } catch (_: CancellationException) {
            // Expected: closing Reader cancels its publication effect.
        }
    }

    private fun viewModel(
        settings: BookCoverWallpaperSettings,
        publisher: BookCoverPublisher,
        iReaderCapability: IReaderBookCoverCapability = IReaderBookCoverCapability(
            isSupported = true,
            isBookCoverScreenSaverSelected = true,
        ),
    ): BookCoverWallpaperViewModel = BookCoverWallpaperViewModel(
        settings = MutableStateFlow(settings),
        updateSettings = {},
        capabilityProvider = BookCoverWallpaperCapabilityProvider {
            BookCoverWallpaperCapability(isSupported = true, isSetAllowed = true)
        },
        iReaderCapabilityProvider = IReaderBookCoverCapabilityProvider { iReaderCapability },
        publisher = publisher,
        coroutineScope = null,
    )

    private class FakePublisher(
        private val throws: Boolean = false,
        private val cancels: Boolean = false,
    ) : BookCoverPublisher {
        var callCount = 0
        var lastCover: File? = null

        override suspend fun publish(coverFile: File): BookCoverPublishResult {
            if (cancels) throw CancellationException("reader closed")
            if (throws) error("publisher failed")
            callCount += 1
            lastCover = coverFile
            return BookCoverPublishResult(
                lockScreen = BookCoverTargetResult.Success,
                export = BookCoverTargetResult.Skipped,
            )
        }
    }
}
