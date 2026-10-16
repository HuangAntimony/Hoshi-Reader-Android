package moe.antimony.hoshi.features.wallpaper

import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import moe.antimony.hoshi.R
import moe.antimony.hoshi.ui.ErrorDiagnosticEvents
import moe.antimony.hoshi.ui.UiText
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class BookCoverPublisherTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun fitCenterPreservesPortraitCoverInsideLandscapeCanvas() {
        assertEquals(
            PixelRect(left = 400, top = 0, width = 400, height = 800),
            coverDestinationRect(
                mode = BookCoverScaleMode.Fit,
                sourceWidth = 600,
                sourceHeight = 1200,
                targetWidth = 1200,
                targetHeight = 800,
            ),
        )
    }

    @Test
    fun fitCenterPreservesLandscapeCoverInsidePortraitCanvas() {
        assertEquals(
            PixelRect(left = 0, top = 750, width = 1200, height = 600),
            coverDestinationRect(
                mode = BookCoverScaleMode.Fit,
                sourceWidth = 1600,
                sourceHeight = 800,
                targetWidth = 1200,
                targetHeight = 2100,
            ),
        )
    }

    @Test
    fun fillPreservesAspectRatioAndCentersOverflowForCropping() {
        assertEquals(
            PixelRect(left = 0, top = -800, width = 1200, height = 2400),
            coverDestinationRect(
                mode = BookCoverScaleMode.Fill,
                sourceWidth = 600,
                sourceHeight = 1200,
                targetWidth = 1200,
                targetHeight = 800,
            ),
        )
    }

    @Test
    fun stretchUsesTheWholeCanvasWithoutPreservingAspectRatio() {
        assertEquals(
            PixelRect(left = 0, top = 0, width = 1200, height = 800),
            coverDestinationRect(
                mode = BookCoverScaleMode.Stretch,
                sourceWidth = 600,
                sourceHeight = 1200,
                targetWidth = 1200,
                targetHeight = 800,
            ),
        )
    }

    @Test
    fun decodeSampleKeepsEnoughPixelsForRenderedDestination() {
        assertEquals(
            8,
            coverDecodeSampleSize(
                mode = BookCoverScaleMode.Fit,
                sourceWidth = 4800,
                sourceHeight = 9600,
                targetWidth = 1200,
                targetHeight = 800,
            ),
        )
        assertEquals(
            4,
            coverDecodeSampleSize(
                mode = BookCoverScaleMode.Fill,
                sourceWidth = 4800,
                sourceHeight = 9600,
                targetWidth = 1200,
                targetHeight = 800,
            ),
        )
        assertEquals(
            4,
            coverDecodeSampleSize(
                mode = BookCoverScaleMode.Stretch,
                sourceWidth = 4800,
                sourceHeight = 9600,
                targetWidth = 1200,
                targetHeight = 800,
            ),
        )
    }

    @Test
    fun disabledBackendsDoNotRender() = runBlocking {
        val fixture = fixture(BookCoverWallpaperSettings())

        val result = fixture.publisher.publish(fixture.source)

        assertEquals(BookCoverTargetResult.Skipped, result.lockScreen)
        assertEquals(BookCoverTargetResult.Skipped, result.export)
        assertEquals(0, fixture.renderer.renderCount)
    }

    @Test
    fun enabledBackendsShareOneRenderedFile() = runBlocking {
        val fixture = fixture(
            BookCoverWallpaperSettings(
                updateLockScreen = true,
                exportEnabled = true,
                exportTargetUri = "content://documents/cover",
            ),
        )

        val result = fixture.publisher.publish(fixture.source)

        assertEquals(BookCoverTargetResult.Success, result.lockScreen)
        assertEquals(BookCoverTargetResult.Success, result.export)
        assertEquals(1, fixture.renderer.renderCount)
        assertEquals(listOf(BookCoverScaleMode.Fit), fixture.renderer.modes)
        assertEquals(listOf(fixture.renderer.output), fixture.lockTarget.files)
        assertEquals(listOf(fixture.renderer.output to "content://documents/cover"), fixture.exportTarget.files)
    }

    @Test
    fun iReaderBackendSharesTheRenderedFileWithOtherTargets() = runBlocking {
        val fixture = fixture(
            BookCoverWallpaperSettings(
                updateLockScreen = true,
                updateIReaderBookCover = true,
                exportEnabled = true,
                exportTargetUri = "content://documents/cover",
            ),
        )

        val result = fixture.publisher.publish(fixture.source)

        assertEquals(BookCoverTargetResult.Success, result.iReader)
        assertEquals(1, fixture.renderer.renderCount)
        assertEquals(listOf(fixture.renderer.output), fixture.iReaderTarget.files)
    }

    @Test
    fun oneBackendFailureDoesNotPreventTheOtherBackend() = runBlocking {
        val fixture = fixture(
            settings = BookCoverWallpaperSettings(
                updateLockScreen = true,
                exportEnabled = true,
                exportTargetUri = "content://documents/cover",
            ),
            lockFailure = BookCoverPublishFailure.WallpaperUpdateFailed,
        )

        val result = fixture.publisher.publish(fixture.source)

        assertEquals(
            BookCoverTargetResult.Failed(BookCoverPublishFailure.WallpaperUpdateFailed),
            result.lockScreen,
        )
        assertEquals(BookCoverTargetResult.Success, result.export)
        assertTrue(result.hasFailures)
    }

    @Test
    fun thrownBackendExceptionDoesNotPreventTheOtherBackend() = runBlocking {
        val fixture = fixture(
            settings = BookCoverWallpaperSettings(
                updateLockScreen = true,
                exportEnabled = true,
                exportTargetUri = "content://documents/cover",
            ),
            lockThrows = true,
        )

        val result = fixture.publisher.publish(fixture.source)

        val failure = result.lockScreen as BookCoverTargetResult.Failed
        assertEquals(BookCoverPublishFailure.WallpaperUpdateFailed, failure.reason)
        assertTrue(failure.details.orEmpty().contains("wallpaper service failed"))
        assertEquals(BookCoverTargetResult.Success, result.export)
        assertEquals(1, fixture.exportTarget.files.size)
    }

    @Test
    fun exportFailureDoesNotChangeSuccessfulLockScreenResult() = runBlocking {
        val fixture = fixture(
            settings = BookCoverWallpaperSettings(
                updateLockScreen = true,
                exportEnabled = true,
                exportTargetUri = "content://documents/cover",
            ),
            exportFailure = BookCoverPublishFailure.ExportPermissionLost,
        )

        val result = fixture.publisher.publish(fixture.source)

        assertEquals(BookCoverTargetResult.Success, result.lockScreen)
        assertEquals(
            BookCoverTargetResult.Failed(BookCoverPublishFailure.ExportPermissionLost),
            result.export,
        )
    }

    @Test
    fun renderFailureIsReportedForEveryEnabledTarget() = runBlocking {
        val fixture = fixture(
            settings = BookCoverWallpaperSettings(
                updateLockScreen = true,
                exportEnabled = true,
                exportTargetUri = "content://documents/cover",
            ),
            renderThrows = true,
        )

        val result = fixture.publisher.publish(fixture.source)

        val failure = result.lockScreen as BookCoverTargetResult.Failed
        assertEquals(BookCoverPublishFailure.RenderFailed, failure.reason)
        assertTrue(failure.details.orEmpty().contains("render failed"))
        assertEquals(failure, result.export)
    }

    @Test
    fun settingsReadFailureIsReturnedInsteadOfThrown() = runBlocking {
        val renderer = FakeRenderer(tempFolder.newFile("rendered.png"))
        val publisher = DefaultBookCoverPublisher(
            settings = flow { throw IOException("preferences unavailable") },
            renderer = renderer,
            lockScreenTarget = FakeLockTarget(null, false),
            exportTarget = FakeExportTarget(),
            iReaderTarget = FakeIReaderTarget(),
        )

        val result = publisher.publish(tempFolder.newFile("source.jpg"))

        val failure = result.lockScreen as BookCoverTargetResult.Failed
        assertEquals(BookCoverPublishFailure.SettingsUnavailable, failure.reason)
        assertTrue(failure.details.orEmpty().contains("preferences unavailable"))
        assertEquals(failure, result.lockScreen)
        assertEquals(failure, result.export)
        assertEquals(failure, result.iReader)
        assertEquals(0, renderer.renderCount)
    }

    @Test
    fun typedPlatformFailureRetainsItsCategoryAndOnlyTheOriginalCauseDetails() = runBlocking {
        val publisher = DefaultBookCoverPublisher(
            settings = MutableStateFlow(BookCoverWallpaperSettings(exportEnabled = true, exportTargetUri = "content://cover")),
            renderer = BookCoverImageRenderer { source, _ -> source },
            lockScreenTarget = BookCoverLockScreenTarget { null },
            exportTarget = BookCoverExportTarget { _, _ ->
                throw BookCoverTargetException(
                    BookCoverPublishFailure.ExportPermissionLost,
                    java.io.IOException("Document write failed", SecurityException("Permission denial: uid 10345")),
                )
            },
            iReaderTarget = BookCoverIReaderTarget { null },
        )
        val failure = publisher.publish(tempFolder.newFile("cover.png")).export as BookCoverTargetResult.Failed
        assertEquals(BookCoverPublishFailure.ExportPermissionLost, failure.reason)
        assertEquals("Document write failed\nPermission denial: uid 10345", failure.details)
    }

    @Test
    fun blankOriginalPlatformErrorDoesNotExposeTheInternalWrapperType() = runBlocking {
        val publisher = DefaultBookCoverPublisher(
            settings = MutableStateFlow(BookCoverWallpaperSettings(updateLockScreen = true)),
            renderer = BookCoverImageRenderer { source, _ -> source },
            lockScreenTarget = BookCoverLockScreenTarget {
                throw BookCoverTargetException(BookCoverPublishFailure.WallpaperUpdateFailed, java.io.IOException())
            },
            exportTarget = BookCoverExportTarget { _, _ -> null },
            iReaderTarget = BookCoverIReaderTarget { null },
        )
        val failure = publisher.publish(tempFolder.newFile("cover.png")).lockScreen as BookCoverTargetResult.Failed
        assertEquals("IOException", failure.details)
    }

    @Test
    fun typedTargetFailurePublishesTheOriginalThrowableBeforeConvertingDetailsToText() = runBlocking {
        ErrorDiagnosticEvents.buffer.drain()
        val cause = SecurityException("Permission denial: uid 10345")
        val error = IOException("Document write failed", cause)
        val publisher = DefaultBookCoverPublisher(
            settings = MutableStateFlow(BookCoverWallpaperSettings(exportEnabled = true, exportTargetUri = "content://cover")),
            renderer = BookCoverImageRenderer { source, _ -> source },
            lockScreenTarget = BookCoverLockScreenTarget { null },
            exportTarget = BookCoverExportTarget { _, _ ->
                throw BookCoverTargetException(BookCoverPublishFailure.ExportPermissionLost, error)
            },
            iReaderTarget = BookCoverIReaderTarget { null },
        )

        val result = publisher.publish(tempFolder.newFile("cover.png"))
        val event = ErrorDiagnosticEvents.buffer.drain().singleOrNull()

        assertTrue(result.export is BookCoverTargetResult.Failed)
        assertNotNull("The real platform failure must reach built-in Diagnostics", event)
        assertSame(error, event!!.error)
        assertSame(cause, requireNotNull(event.error).cause)
        assertEquals("Document write failed", requireNotNull(event.error).message)
        assertEquals(UiText.Resource(R.string.book_cover_wallpaper_publish_failed), event.operation)
    }

    @Test
    fun concurrentPublicationsAreSerializedInOpenOrder() = runBlocking {
        val first = tempFolder.newFile("first.jpg")
        val second = tempFolder.newFile("second.jpg")
        val renderer = BlockingRenderer()
        val lockTarget = FakeLockTarget(null, false)
        val publisher = DefaultBookCoverPublisher(
            settings = MutableStateFlow(
                BookCoverWallpaperSettings(
                    updateLockScreen = true,
                    scaleMode = BookCoverScaleMode.Fill,
                ),
            ),
            renderer = renderer,
            lockScreenTarget = lockTarget,
            exportTarget = FakeExportTarget(),
            iReaderTarget = FakeIReaderTarget(),
        )

        val firstPublish = async { publisher.publish(first) }
        renderer.firstEntered.await()
        val secondPublish = async { publisher.publish(second) }
        yield()

        assertFalse(renderer.secondEntered.isCompleted)
        renderer.releaseFirst.complete(Unit)
        firstPublish.await()
        secondPublish.await()

        assertEquals(1, renderer.maxActive.get())
        assertEquals(listOf(first, second), lockTarget.files)
        assertEquals(listOf(BookCoverScaleMode.Fill, BookCoverScaleMode.Fill), renderer.modes)
    }

    @Test
    fun cancellationIsNotConvertedIntoAnOperationalFailure() = runBlocking {
        val publisher = DefaultBookCoverPublisher(
            settings = MutableStateFlow(BookCoverWallpaperSettings(updateLockScreen = true)),
            renderer = BookCoverImageRenderer { _, _ -> throw CancellationException("reader closed") },
            lockScreenTarget = FakeLockTarget(null, false),
            exportTarget = FakeExportTarget(),
            iReaderTarget = FakeIReaderTarget(),
        )

        try {
            publisher.publish(tempFolder.newFile("source.jpg"))
            throw AssertionError("Expected cancellation")
        } catch (_: CancellationException) {
            // Expected: route cancellation must remain cancellation.
        }
    }

    @Test
    fun enabledExportWithoutUriFailsWithoutRendering() = runBlocking {
        val fixture = fixture(BookCoverWallpaperSettings(exportEnabled = true))

        val result = fixture.publisher.publish(fixture.source)

        assertEquals(
            BookCoverTargetResult.Failed(BookCoverPublishFailure.ExportTargetMissing),
            result.export,
        )
        assertEquals(0, fixture.renderer.renderCount)
        assertFalse(result.lockScreen is BookCoverTargetResult.Failed)
    }

    private fun fixture(
        settings: BookCoverWallpaperSettings,
        lockFailure: BookCoverPublishFailure? = null,
        lockThrows: Boolean = false,
        exportFailure: BookCoverPublishFailure? = null,
        renderThrows: Boolean = false,
    ): Fixture {
        val source = tempFolder.newFile("source.jpg").apply { writeText("cover") }
        val renderer = FakeRenderer(tempFolder.newFile("rendered.png"), renderThrows)
        val lockTarget = FakeLockTarget(lockFailure, lockThrows)
        val exportTarget = FakeExportTarget(exportFailure)
        val iReaderTarget = FakeIReaderTarget()
        return Fixture(
            source = source,
            renderer = renderer,
            lockTarget = lockTarget,
            exportTarget = exportTarget,
            iReaderTarget = iReaderTarget,
            publisher = DefaultBookCoverPublisher(
                settings = MutableStateFlow(settings),
                renderer = renderer,
                lockScreenTarget = lockTarget,
                exportTarget = exportTarget,
                iReaderTarget = iReaderTarget,
            ),
        )
    }

    private data class Fixture(
        val source: File,
        val renderer: FakeRenderer,
        val lockTarget: FakeLockTarget,
        val exportTarget: FakeExportTarget,
        val iReaderTarget: FakeIReaderTarget,
        val publisher: BookCoverPublisher,
    )

    private class FakeRenderer(
        val output: File,
        private val throws: Boolean = false,
    ) : BookCoverImageRenderer {
        var renderCount = 0
        val modes = mutableListOf<BookCoverScaleMode>()

        override suspend fun render(source: File, scaleMode: BookCoverScaleMode): File {
            renderCount += 1
            modes += scaleMode
            if (throws) error("render failed")
            return output
        }
    }

    private class BlockingRenderer : BookCoverImageRenderer {
        val firstEntered = CompletableDeferred<Unit>()
        val secondEntered = CompletableDeferred<Unit>()
        val releaseFirst = CompletableDeferred<Unit>()
        val maxActive = AtomicInteger()
        val modes = mutableListOf<BookCoverScaleMode>()
        private val active = AtomicInteger()
        private val callCount = AtomicInteger()

        override suspend fun render(source: File, scaleMode: BookCoverScaleMode): File {
            modes += scaleMode
            val call = callCount.incrementAndGet()
            val activeNow = active.incrementAndGet()
            maxActive.updateAndGet { maxOf(it, activeNow) }
            try {
                if (call == 1) {
                    firstEntered.complete(Unit)
                    releaseFirst.await()
                } else {
                    secondEntered.complete(Unit)
                    delay(1)
                }
                return source
            } finally {
                active.decrementAndGet()
            }
        }
    }

    private class FakeLockTarget(
        private val failure: BookCoverPublishFailure?,
        private val throws: Boolean,
    ) : BookCoverLockScreenTarget {
        val files = mutableListOf<File>()

        override suspend fun publish(image: File): BookCoverPublishFailure? {
            files += image
            if (throws) error("wallpaper service failed")
            return failure
        }
    }

    private class FakeExportTarget(
        private val failure: BookCoverPublishFailure? = null,
    ) : BookCoverExportTarget {
        val files = mutableListOf<Pair<File, String>>()

        override suspend fun publish(image: File, targetUri: String): BookCoverPublishFailure? {
            files += image to targetUri
            return failure
        }
    }

    private class FakeIReaderTarget(
        private val failure: BookCoverPublishFailure? = null,
    ) : BookCoverIReaderTarget {
        val files = mutableListOf<File>()

        override suspend fun publish(image: File): BookCoverPublishFailure? {
            files += image
            return failure
        }
    }
}
