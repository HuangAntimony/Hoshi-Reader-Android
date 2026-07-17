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

        assertEquals(
            BookCoverTargetResult.Failed(BookCoverPublishFailure.WallpaperUpdateFailed),
            result.lockScreen,
        )
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

        val failure = BookCoverTargetResult.Failed(BookCoverPublishFailure.RenderFailed)
        assertEquals(failure, result.lockScreen)
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
        )

        val result = publisher.publish(tempFolder.newFile("source.jpg"))

        val failure = BookCoverTargetResult.Failed(BookCoverPublishFailure.SettingsUnavailable)
        assertEquals(failure, result.lockScreen)
        assertEquals(failure, result.export)
        assertEquals(0, renderer.renderCount)
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
        return Fixture(
            source = source,
            renderer = renderer,
            lockTarget = lockTarget,
            exportTarget = exportTarget,
            publisher = DefaultBookCoverPublisher(
                settings = MutableStateFlow(settings),
                renderer = renderer,
                lockScreenTarget = lockTarget,
                exportTarget = exportTarget,
            ),
        )
    }

    private data class Fixture(
        val source: File,
        val renderer: FakeRenderer,
        val lockTarget: FakeLockTarget,
        val exportTarget: FakeExportTarget,
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
}
