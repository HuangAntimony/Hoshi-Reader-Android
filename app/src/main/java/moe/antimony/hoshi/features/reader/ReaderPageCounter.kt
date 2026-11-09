package moe.antimony.hoshi.features.reader

import android.os.Build
import android.widget.FrameLayout
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import androidx.webkit.WebViewCompat
import java.io.File
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import moe.antimony.hoshi.content.ContentLanguageProfile
import moe.antimony.hoshi.epub.BookRepository
import moe.antimony.hoshi.epub.EpubBook
import kotlin.math.roundToInt

/** The empty host is cheap; no measurement WebView is created on a cache hit. */
@Composable
internal fun ReaderPageCounter(
    book: EpubBook,
    bookRoot: File?,
    profileId: String,
    settings: ReaderSettings,
    viewportSize: IntSize,
    readerReady: Boolean,
    contentLanguage: ContentLanguageProfile,
    fontManager: ReaderFontManager,
    repository: ReaderPageCountRepository,
    bookRepository: BookRepository,
    onCountsChanged: (ReaderPageCounts) -> Unit,
    modifier: Modifier = Modifier,
    scheduler: ReaderPageMeasurementScheduler = remember(book) { ReaderPageMeasurementScheduler() },
) {
    SideEffect { scheduler.setReaderReady(readerReady) }
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val currentOnCounts = rememberUpdatedState(onCountsChanged)
    val fontLibrary by fontManager.libraryState.collectAsState()
    val font = remember(settings.selectedFont, settings.selectedFontFamilyId, settings.selectedFontVariantId, fontLibrary.revision) {
        fontManager.resolveRenderSpec(settings.selectedFont, settings.selectedFontFamilyId, settings.selectedFontVariantId)
    }
    val geometry = settings.readerContentReloadKey()
    val coordinator = remember(book, repository) { ReaderPageMeasurementCoordinator(repository) }
    var host by remember { mutableStateOf<FrameLayout?>(null) }
    val density = context.resources.displayMetrics.density.coerceAtLeast(1f)
    val cssSize = IntSize((viewportSize.width / density).roundToInt(), (viewportSize.height / density).roundToInt())
    var restoreGeneration by remember(scheduler) { mutableIntStateOf(0) }
    var failed by remember(book, geometry, viewportSize, density, font, contentLanguage, profileId, settings.usesPageCounts) {
        mutableStateOf(false)
    }
    var contentFingerprint by remember(book) { mutableStateOf<String?>(null) }
    AndroidView(modifier = modifier, factory = { FrameLayout(it).also { view -> host = view; view.isEnabled = false } },
        onRelease = { host = null })

    LaunchedEffect(book, geometry, viewportSize, density, font, contentLanguage, profileId, settings.usesPageCounts) {
        currentOnCounts.value(ReaderPageCounts())
    }
    // Reader resets counts for physical viewport changes, even when CSS rounding is unchanged.
    LaunchedEffect(host, book, geometry, viewportSize, density, font, contentLanguage, profileId,
        settings.usesPageCounts, restoreGeneration, scheduler, lifecycle) {
        val parent = host ?: return@LaunchedEffect
        if (failed || !settings.usesPageCounts || cssSize.width <= 0 || cssSize.height <= 0) return@LaunchedEffect
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            withContext(Dispatchers.Main.immediate) {
                var measurer: ReaderPageMeasurer? = null
                val job = currentCoroutineContext().job
                val cancelForRestore: () -> Unit = {
                    // A gate cannot interrupt native layout already queued by resume().
                    // Detach the unfinished document before loading foreground content.
                    job.cancel()
                    measurer?.close()
                    restoreGeneration++
                }
                try {
                    val content = contentFingerprint ?: repository.contentFingerprint(book,
                        bookRoot?.let { bookRepository.epubFile(it) }).also { contentFingerprint = it }
                    val fontFiles = font.faces.mapNotNull { face -> fontManager.fontFileForRequest(java.net.URI(face.url).path.removePrefix("/fonts/")) }
                    val fontFingerprint = repository.fontFingerprint(fontFiles)
                    val renderer = WebViewCompat.getCurrentWebViewPackage(context)?.let { "${it.packageName}:${it.versionName}" }
                    val key = readerPageCountKey(content, geometry, cssSize, density,
                        font, fontFingerprint, contentLanguage, renderer, Build.FINGERPRINT)
                    val bookId = bookRoot?.canonicalPath ?: book.rootDirectory?.canonicalPath ?: content
                    val index = coordinator.pagesFor(book, bookId, key) { spine ->
                        val rendererView = measurer ?: run {
                            // Cache lookup can finish while the visible chapter is restoring.
                            // Only a cache miss waits for reading to settle, before creating a WebView.
                            scheduler.awaitIdle()
                            scheduler.onRestoreStarted = cancelForRestore
                            val assets = ReaderWebAssets.load(context)
                            ReaderPageMeasurer(parent, book, assets, fontManager::fontFileForRequest, scheduler) { token, resources ->
                                val measurementFont = resources.font(font)
                                readerSetupScript(0.0, null, settings.copy(textAnimation = false), measurementFont?.faces?.firstOrNull()?.url,
                                    measurementFont, false, false, contentLanguage, cssSize, 0L, 0L, null, null, token, assets, measurementOnly = true)
                            }.also { measurer = it }
                        }
                        rendererView.measure(spine)
                    }
                    ensureActive()
                    currentOnCounts.value(ReaderPageCounts(index))
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    failed = true
                    recordPageMeasurementFailure(error)
                    currentOnCounts.value(ReaderPageCounts(failed = true))
                } finally {
                    if (scheduler.onRestoreStarted === cancelForRestore) scheduler.onRestoreStarted = null
                    measurer?.close()
                }
            }
        }
    }
}
