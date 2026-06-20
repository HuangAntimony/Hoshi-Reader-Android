package moe.antimony.hoshi.features.reader

import androidx.compose.ui.unit.IntSize
import kotlin.io.path.createTempDirectory
import moe.antimony.hoshi.content.ContentLanguageProfile
import moe.antimony.hoshi.features.dictionary.LookupPopupItem
import moe.antimony.hoshi.features.dictionary.LookupPopupState
import moe.antimony.hoshi.features.sasayaki.SasayakiSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReaderWebViewStateHolderTest {
    @Test
    fun recordsDisplayedProgressWithoutChangingLoadTarget() {
        val holder = stateHolder(initialIndex = 2)

        val saved = holder.recordDisplayedProgress(0.42)

        assertEquals(ReaderChapterPosition(index = 2, progress = 0.0), holder.readerPosition.loadPosition)
        assertEquals(ReaderChapterPosition(index = 2, progress = 0.42), holder.readerPosition.displayedPosition)
        assertEquals(holder.readerPosition.displayedPosition, saved)
    }

    @Test
    fun chapterLimitNavigationUpdatesLoadAndDisplayedPositions() {
        val holder = stateHolder(initialIndex = 2)

        val next = holder.goToNextChapter(lastIndex = 3)
        val previous = holder.goToPreviousChapter()

        assertEquals(ReaderChapterPosition(index = 3, progress = 0.0), next)
        assertEquals(ReaderChapterPosition(index = 2, progress = 1.0), previous)
        assertEquals(previous, holder.readerPosition.loadPosition)
        assertEquals(previous, holder.readerPosition.displayedPosition)
    }

    @Test
    fun activeJumpRecordsDisplayedOriginAndClearsForwardHistory() {
        val holder = stateHolder(initialIndex = 2)
        holder.recordDisplayedProgress(0.42)

        holder.jumpToWithHistory(ReaderChapterPosition(index = 5, progress = 0.0))
        holder.navigateBackInJumpHistory()
        holder.navigateForwardInJumpHistory()
        holder.jumpToWithHistory(ReaderChapterPosition(index = 7, progress = 0.0))

        assertEquals(ReaderChapterPosition(index = 5, progress = 0.0), holder.backTargetPosition)
        assertNull(holder.forwardTargetPosition)
        assertEquals(ReaderChapterPosition(index = 7, progress = 0.0), holder.readerPosition.displayedPosition)
    }

    @Test
    fun jumpingToCurrentDisplayedPositionDoesNotStartRestoreOrRecordHistory() {
        val holder = stateHolder(initialIndex = 2)
        holder.markWebViewRestored()
        holder.recordDisplayedProgress(0.42)

        val saved = holder.jumpToWithHistory(ReaderChapterPosition(index = 2, progress = 0.42))

        assertEquals(ReaderChapterPosition(index = 2, progress = 0.42), saved)
        assertEquals(ReaderChapterPosition(index = 2, progress = 0.42), holder.readerPosition.displayedPosition)
        assertTrue(holder.canAcceptReaderNavigationInput())
        assertNull(holder.backTargetPosition)
    }

    @Test
    fun jumpHistoryBackAndForwardMirrorIosReaderBehavior() {
        val holder = stateHolder(initialIndex = 2)
        holder.recordDisplayedProgress(0.42)
        holder.jumpToWithHistory(ReaderChapterPosition(index = 5, progress = 0.0))
        holder.recordDisplayedProgress(0.25)

        val back = holder.navigateBackInJumpHistory()
        val forward = holder.navigateForwardInJumpHistory()

        assertEquals(ReaderChapterPosition(index = 2, progress = 0.42), back)
        assertEquals(ReaderChapterPosition(index = 5, progress = 0.25), forward)
        assertEquals(ReaderChapterPosition(index = 2, progress = 0.42), holder.backTargetPosition)
        assertNull(holder.forwardTargetPosition)
    }

    @Test
    fun ordinaryChapterNavigationDoesNotRecordJumpHistory() {
        val holder = stateHolder(initialIndex = 2)

        holder.goToNextChapter(lastIndex = 3)
        holder.goToPreviousChapter()

        assertNull(holder.backTargetPosition)
        assertNull(holder.forwardTargetPosition)
    }

    @Test
    fun manualProgressClearsForwardHistoryOnlyWhenNoBackTargetRemains() {
        val holder = stateHolder(initialIndex = 2)
        holder.jumpToWithHistory(ReaderChapterPosition(index = 5, progress = 0.0))
        holder.navigateBackInJumpHistory()

        holder.recordDisplayedProgress(0.6)
        holder.clearForwardHistoryAfterManualMovement()

        assertNull(holder.backTargetPosition)
        assertNull(holder.forwardTargetPosition)
    }

    @Test
    fun manualProgressKeepsForwardHistoryWhenBackTargetStillExists() {
        val holder = stateHolder(initialIndex = 1)
        holder.jumpToWithHistory(ReaderChapterPosition(index = 2, progress = 0.0))
        holder.jumpToWithHistory(ReaderChapterPosition(index = 3, progress = 0.0))
        holder.navigateBackInJumpHistory()

        holder.recordDisplayedProgress(0.6)
        holder.clearForwardHistoryAfterManualMovement()

        assertEquals(ReaderChapterPosition(index = 1, progress = 0.0), holder.backTargetPosition)
        assertEquals(ReaderChapterPosition(index = 3, progress = 0.0), holder.forwardTargetPosition)
    }

    @Test
    fun continuousScrollProgressIsIgnoredWhileWebViewIsRestoringLikeIos() {
        val holder = stateHolder(initialIndex = 2)

        val next = holder.goToNextChapter(lastIndex = 3)
        val ignored = holder.recordContinuousScrollProgress(0.72, holder.webViewRestoreEpoch)

        assertEquals(ReaderChapterPosition(index = 3, progress = 0.0), next)
        assertNull(ignored)
        assertEquals(ReaderChapterPosition(index = 3, progress = 0.0), holder.readerPosition.displayedPosition)

        holder.markWebViewRestored()
        val saved = holder.recordContinuousScrollProgress(0.12, holder.webViewRestoreEpoch)

        assertEquals(ReaderChapterPosition(index = 3, progress = 0.12), saved)
        assertEquals(saved, holder.readerPosition.displayedPosition)
    }

    @Test
    fun restoreCompletionActionKeepsReaderRestoringUntilWebViewIsVisible() {
        var restoreCompletedCount = 0

        val afterVisible = readerRestoreCompletionAfterVisibleAction(
            chapterFragment = null,
            evaluateProgress = { error("Progress should not be evaluated without a fragment") },
            onSaveProgress = { error("Progress should not be saved without a fragment") },
            onRestoreCompleted = { restoreCompletedCount += 1 },
        )

        assertEquals(0, restoreCompletedCount)

        afterVisible()

        assertEquals(1, restoreCompletedCount)
    }

    @Test
    fun fragmentRestoreCompletionEvaluatesProgressOnlyAfterWebViewIsVisible() {
        val events = mutableListOf<String>()

        val afterVisible = readerRestoreCompletionAfterVisibleAction(
            chapterFragment = "chapter-anchor",
            evaluateProgress = { callback ->
                events += "evaluate"
                callback("\"0.42\"")
            },
            onSaveProgress = { progress -> events += "save $progress" },
            onRestoreCompleted = { events += "restored" },
        )

        assertTrue(events.isEmpty())

        afterVisible()

        assertEquals(listOf("evaluate", "save 0.42", "restored"), events)
    }

    @Test
    fun readerNavigationInputIsIgnoredWhileWebViewIsRestoring() {
        val holder = stateHolder(initialIndex = 2)

        assertFalse(holder.canAcceptReaderNavigationInput())

        holder.markWebViewRestored()
        assertTrue(holder.canAcceptReaderNavigationInput())

        holder.goToNextChapter(lastIndex = 3)
        assertFalse(holder.canAcceptReaderNavigationInput())
    }

    @Test
    fun staleContinuousScrollProgressFromPreviousRestoreEpochIsIgnored() {
        val holder = stateHolder(initialIndex = 2)
        holder.markWebViewRestored()
        val oldEpoch = holder.webViewRestoreEpoch

        holder.goToNextChapter(lastIndex = 3)
        holder.markWebViewRestored()
        val staleProgress = holder.recordContinuousScrollProgress(0.72, oldEpoch)

        assertNull(staleProgress)
        assertEquals(ReaderChapterPosition(index = 3, progress = 0.0), holder.readerPosition.displayedPosition)
    }

    @Test
    fun staleContinuousScrollDisplayProgressFromPreviousRestoreEpochIsIgnored() {
        val holder = stateHolder(initialIndex = 2)
        holder.markWebViewRestored()
        val oldEpoch = holder.webViewRestoreEpoch

        holder.goToNextChapter(lastIndex = 3)
        holder.markWebViewRestored()
        val staleProgress = holder.recordContinuousScrollDisplayProgress(1.0, oldEpoch)

        assertNull(staleProgress)
        assertEquals(ReaderChapterPosition(index = 3, progress = 0.0), holder.readerPosition.displayedPosition)
    }

    @Test
    fun viewportResizeAfterInitialMeasureReloadsAtDisplayedPosition() {
        val holder = stateHolder(initialIndex = 1)

        holder.updateViewportSize(IntSize(800, 1200))
        holder.recordDisplayedProgress(0.35)
        holder.updateViewportSize(IntSize(900, 1200))

        assertEquals(ReaderChapterPosition(index = 1, progress = 0.35), holder.readerPosition.loadPosition)
        assertEquals(IntSize(900, 1200), holder.webViewViewportSize)
    }

    @Test
    fun viewportResizeAfterInitialMeasureDismissesLookupPopups() {
        val holder = stateHolder(initialIndex = 1)
        holder.updateViewportSize(IntSize(800, 1200))
        holder.markWebViewRestored()
        holder.markSasayakiPausedByLookup()
        holder.setLookupPopups(listOf(lookupPopup()))

        holder.updateViewportSize(IntSize(900, 1200))

        assertTrue(holder.lookupPopups.isEmpty())
        assertFalse(holder.sasayakiWasPausedByLookup)
    }

    @Test
    fun readerWebViewWaitsForMeasuredViewportBeforeInitialLoad() {
        assertFalse(readerWebViewReadyToLoad(IntSize.Zero))
        assertTrue(readerWebViewReadyToLoad(IntSize(800, 1200)))
    }

    @Test
    fun readerWebViewLoadKeyTracksContentReloadKey() {
        val baseSettings = ReaderSettings()
        val changedSettings = baseSettings.copy(fontSize = 28)
        val setupReloadKey = ReaderWebViewSetupReloadKey(
            initialProgress = 0.2,
            initialFragment = null,
            scanNonJapaneseText = false,
            contentLanguageProfile = ContentLanguageProfile.Default,
            fontFaceUrl = "https://appassets.androidplatform.net/fonts/default.ttf",
        )
        val viewportSize = IntSize(800, 1200)

        val baseLoadKey = readerWebViewLoadKey(
            baseUrl = "https://appassets.androidplatform.net/epub/chapter.xhtml",
            readerContentReloadKey = baseSettings.readerContentReloadKey(),
            readerSetupReloadKey = setupReloadKey,
            webViewViewportSize = viewportSize,
        )
        val changedLoadKey = readerWebViewLoadKey(
            baseUrl = "https://appassets.androidplatform.net/epub/chapter.xhtml",
            readerContentReloadKey = changedSettings.readerContentReloadKey(),
            readerSetupReloadKey = setupReloadKey,
            webViewViewportSize = viewportSize,
        )

        assertFalse(baseLoadKey == changedLoadKey)
    }

    @Test
    fun readerWebViewLoadKeyTracksChapterRestoreTarget() {
        val settings = ReaderSettings()
        val viewportSize = IntSize(800, 1200)

        val baseLoadKey = readerWebViewLoadKey(
            baseUrl = "https://appassets.androidplatform.net/epub/chapter.xhtml",
            readerContentReloadKey = settings.readerContentReloadKey(),
            readerSetupReloadKey = ReaderWebViewSetupReloadKey(
                initialProgress = 0.2,
                initialFragment = null,
                scanNonJapaneseText = false,
                contentLanguageProfile = ContentLanguageProfile.Default,
                fontFaceUrl = "https://appassets.androidplatform.net/fonts/default.ttf",
            ),
            webViewViewportSize = viewportSize,
        )
        val changedLoadKey = readerWebViewLoadKey(
            baseUrl = "https://appassets.androidplatform.net/epub/chapter.xhtml",
            readerContentReloadKey = settings.readerContentReloadKey(),
            readerSetupReloadKey = ReaderWebViewSetupReloadKey(
                initialProgress = 0.6,
                initialFragment = null,
                scanNonJapaneseText = false,
                contentLanguageProfile = ContentLanguageProfile.Default,
                fontFaceUrl = "https://appassets.androidplatform.net/fonts/default.ttf",
            ),
            webViewViewportSize = viewportSize,
        )

        assertFalse(baseLoadKey == changedLoadKey)
    }

    @Test
    fun readerWebViewLoadKeyIgnoresThemeOnlySettings() {
        val baseSettings = ReaderSettings(theme = ReaderTheme.Light)
        val changedSettings = baseSettings.copy(theme = ReaderTheme.Dark)
        val setupReloadKey = ReaderWebViewSetupReloadKey(
            initialProgress = 0.2,
            initialFragment = null,
            scanNonJapaneseText = false,
            contentLanguageProfile = ContentLanguageProfile.Default,
            fontFaceUrl = "https://appassets.androidplatform.net/fonts/default.ttf",
        )
        val viewportSize = IntSize(800, 1200)

        val baseLoadKey = readerWebViewLoadKey(
            baseUrl = "https://appassets.androidplatform.net/epub/chapter.xhtml",
            readerContentReloadKey = baseSettings.readerContentReloadKey(),
            readerSetupReloadKey = setupReloadKey,
            webViewViewportSize = viewportSize,
        )
        val changedLoadKey = readerWebViewLoadKey(
            baseUrl = "https://appassets.androidplatform.net/epub/chapter.xhtml",
            readerContentReloadKey = changedSettings.readerContentReloadKey(),
            readerSetupReloadKey = setupReloadKey,
            webViewViewportSize = viewportSize,
        )

        assertEquals(baseLoadKey, changedLoadKey)
    }

    @Test
    fun readerChapterHtmlInjectsSingleEarlyViewportMetaBeforeBodyContent() {
        val html = """
            <!doctype html>
            <html>
            <head>
                <title>Chapter</title>
                <meta name="viewport" content="width=320">
            </head>
            <body><p>Reader text</p></body>
            </html>
        """.trimIndent()

        val prepared = readerHtmlWithEarlyViewport(html)

        assertEquals(1, Regex("""<meta\s+name=["']viewport["']""").findAll(prepared).count())
        assertTrue(
            prepared.indexOf("width=device-width, initial-scale=1.0, maximum-scale=1.0, user-scalable=no") <
                prepared.indexOf("<body>"),
        )
        assertTrue(prepared.contains("<p>Reader text</p>"))
    }

    @Test
    fun readerChapterHtmlKeepsXmlDeclarationAtDocumentStart() {
        val html = """

              <?xml version="1.0" encoding="UTF-8"?>
              <html xmlns="http://www.w3.org/1999/xhtml">
              <head><title>Reader</title></head>
              <body><p>Reader text</p></body>
              </html>
        """.trimIndent()

        val prepared = readerHtmlWithEarlyViewport(html)

        assertTrue(prepared.startsWith("<?xml version=\"1.0\" encoding=\"UTF-8\"?>"))
        assertFalse(prepared.startsWith("\n"))
        assertFalse(prepared.startsWith(" "))
        assertTrue(prepared.contains("<meta name=\"viewport\""))
    }

    @Test
    fun readerChapterHtmlDoesNotFabricateHeadForMalformedXhtmlLikeIos() {
        val html = """
            <html>
            <body><p>Reader text</p></body>
            </html>
        """.trimIndent()

        val prepared = readerHtmlWithEarlyViewport(html)

        assertFalse(prepared.contains("<head>"))
        assertFalse(prepared.contains("<meta name=\"viewport\""))
        assertTrue(prepared.contains("<p>Reader text</p>"))
    }

    @Test
    fun sasayakiTopToggleSpaceIsReservedBeforeSidecarsAreParsed() {
        val root = createTempDirectory("hoshi-sasayaki-sidecar").toFile()
        try {
            assertFalse(readerShouldReserveSasayakiTopToggle(root, SasayakiSettings()))

            root.resolve("sasayaki_match.json").writeText("{}")
            root.resolve("sasayaki_playback.json").writeText("{}")

            assertTrue(readerShouldReserveSasayakiTopToggle(root, SasayakiSettings()))
            assertFalse(readerShouldReserveSasayakiTopToggle(root, SasayakiSettings(enabled = false)))
            assertFalse(readerShouldReserveSasayakiTopToggle(root, SasayakiSettings(showReaderToggle = false)))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun syncedLayoutSettingsReloadAtDisplayedPosition() {
        val holder = stateHolder(initialIndex = 1)
        holder.markWebViewRestored()
        holder.recordDisplayedProgress(0.35)
        val previousEpoch = holder.webViewRestoreEpoch

        holder.syncSettings(ReaderSettings(verticalPadding = 22))

        assertEquals(ReaderChapterPosition(index = 1, progress = 0.35), holder.readerPosition.loadPosition)
        assertEquals(22, holder.effectiveSettings.verticalPadding)
        assertTrue(holder.isWebViewRestoring)
        assertEquals(previousEpoch + 1, holder.webViewRestoreEpoch)
    }

    @Test
    fun syncedChromeOnlySettingsDoNotReloadWebView() {
        val holder = stateHolder(initialIndex = 1)
        holder.markWebViewRestored()
        val previousEpoch = holder.webViewRestoreEpoch

        holder.syncSettings(ReaderSettings(showTitle = false, alwaysShowProgress = false, showProgressTop = false))

        assertFalse(holder.isWebViewRestoring)
        assertEquals(previousEpoch, holder.webViewRestoreEpoch)
    }

    @Test
    fun syncedEInkModeDoesNotReloadWebView() {
        val holder = stateHolder(initialIndex = 1)
        holder.markWebViewRestored()
        val previousEpoch = holder.webViewRestoreEpoch

        holder.syncSettings(ReaderSettings(eInkMode = true))

        assertFalse(holder.isWebViewRestoring)
        assertEquals(previousEpoch, holder.webViewRestoreEpoch)
    }

    @Test
    fun appliedPopupSettingsDoNotReloadWebView() {
        val holder = stateHolder(initialIndex = 1)
        holder.markWebViewRestored()
        val previousEpoch = holder.webViewRestoreEpoch

        holder.applySettings(
            ReaderSettings(
                popupWidth = 420,
                popupHeight = 360,
                popupActionBar = true,
                popupFullWidth = true,
                popupSwipeToDismiss = false,
                popupSwipeThreshold = 45,
            ),
        )

        assertFalse(holder.isWebViewRestoring)
        assertEquals(previousEpoch, holder.webViewRestoreEpoch)
    }

    @Test
    fun readerContentReloadKeyIgnoresPopupSettings() {
        val base = ReaderSettings(viewMode = ReaderViewMode.Continuous)
        val popupOnly = base.copy(
            popupWidth = 420,
            popupHeight = 360,
            popupActionBar = true,
            popupFullWidth = true,
            popupSwipeToDismiss = false,
            popupSwipeThreshold = 45,
        )

        assertEquals(base.readerContentReloadKey(), popupOnly.readerContentReloadKey())
    }

    @Test
    fun readerContentReloadKeyChangesForContentSettings() {
        val base = ReaderSettings()

        assertFalse(base.readerContentReloadKey() == base.copy(fontSize = 28).readerContentReloadKey())
        assertFalse(base.readerContentReloadKey() == base.copy(verticalWriting = false).readerContentReloadKey())
        assertFalse(base.readerContentReloadKey() == base.copy(paragraphSpacing = 1.2).readerContentReloadKey())
    }

    @Test
    fun readerContentReloadKeyChangesForVisualNovelModeAndSplitSettings() {
        val base = ReaderSettings()
        val visualNovel = base.copy(viewMode = ReaderViewMode.VisualNovel)

        assertFalse(base.readerContentReloadKey() == visualNovel.readerContentReloadKey())
        assertFalse(
            visualNovel.readerContentReloadKey() ==
                visualNovel.copy(visualNovelScreenMode = VisualNovelScreenMode.Sentences).readerContentReloadKey(),
        )
        assertFalse(
            visualNovel.readerContentReloadKey() ==
                visualNovel.copy(visualNovelSentencesPerScreen = 4).readerContentReloadKey(),
        )
        assertFalse(
            visualNovel.readerContentReloadKey() ==
                visualNovel.copy(visualNovelPreserveDialogueBubbles = true).readerContentReloadKey(),
        )
        assertFalse(
            visualNovel.readerContentReloadKey() ==
                visualNovel.copy(visualNovelMergeCrossScreenSasayakiCues = true).readerContentReloadKey(),
        )
    }

    @Test
    fun readerContentReloadKeyIgnoresVisualNovelRevealSpeed() {
        val base = ReaderSettings(viewMode = ReaderViewMode.VisualNovel)
        val changedSpeed = base.copy(visualNovelRevealSpeed = 0)

        assertEquals(base.readerContentReloadKey(), changedSpeed.readerContentReloadKey())
    }

    @Test
    fun syncedVisualNovelRevealSpeedDoesNotReloadWebView() {
        val holder = stateHolder(initialIndex = 1)
        holder.markWebViewRestored()
        val previousEpoch = holder.webViewRestoreEpoch

        holder.syncSettings(ReaderSettings(visualNovelRevealSpeed = 80))

        assertFalse(holder.isWebViewRestoring)
        assertEquals(previousEpoch, holder.webViewRestoreEpoch)
        assertEquals(80, holder.effectiveSettings.visualNovelRevealSpeed)
    }

    @Test
    fun readerContentReloadKeyIgnoresVisualNovelClickAdvanceSetting() {
        val base = ReaderSettings(viewMode = ReaderViewMode.VisualNovel)

        assertEquals(base.readerContentReloadKey(), base.copy(visualNovelClickAdvance = false).readerContentReloadKey())
    }

    @Test
    fun readerContentReloadKeyIgnoresAppearanceColors() {
        val base = ReaderSettings()

        assertEquals(base.readerContentReloadKey(), base.copy(theme = ReaderTheme.Dark).readerContentReloadKey())
        assertEquals(base.readerContentReloadKey(), base.copy(systemLightSepia = true).readerContentReloadKey())
        assertEquals(base.readerContentReloadKey(), base.copy(sepiaInvertInDark = true).readerContentReloadKey())
        assertEquals(base.readerContentReloadKey(), base.copy(eInkMode = true).readerContentReloadKey())
    }

    @Test
    fun readerAppearanceUpdateKeyTracksCustomContentColorsWithoutReloadingContent() {
        val base = ReaderSettings(
            theme = ReaderTheme.Custom,
            customBackgroundColor = 0xFF112233,
            customTextColor = 0xFF445566,
        )
        val backgroundChanged = base.copy(customBackgroundColor = 0xFF778899)
        val textChanged = base.copy(customTextColor = 0xFFABCDEF)

        assertEquals(base.readerContentReloadKey(), backgroundChanged.readerContentReloadKey())
        assertEquals(base.readerContentReloadKey(), textChanged.readerContentReloadKey())
        assertFalse(
            readerAppearanceUpdateKey(base, systemDark = false, sasayakiTextColor = 0xFF111111, sasayakiBackgroundColor = 0xFFFFFFFF) ==
                readerAppearanceUpdateKey(backgroundChanged, systemDark = false, sasayakiTextColor = 0xFF111111, sasayakiBackgroundColor = 0xFFFFFFFF),
        )
        assertFalse(
            readerAppearanceUpdateKey(base, systemDark = false, sasayakiTextColor = 0xFF111111, sasayakiBackgroundColor = 0xFFFFFFFF) ==
                readerAppearanceUpdateKey(textChanged, systemDark = false, sasayakiTextColor = 0xFF111111, sasayakiBackgroundColor = 0xFFFFFFFF),
        )
    }

    @Test
    fun readerAppearanceUpdateKeyTracksVisualNovelRevealSpeedWithoutReloadingContent() {
        val base = ReaderSettings(
            viewMode = ReaderViewMode.VisualNovel,
            visualNovelRevealSpeed = 45,
        )
        val changedSpeed = base.copy(visualNovelRevealSpeed = 90)

        assertEquals(base.readerContentReloadKey(), changedSpeed.readerContentReloadKey())
        assertFalse(
            readerAppearanceUpdateKey(base, systemDark = false, sasayakiTextColor = 0xFF111111, sasayakiBackgroundColor = 0xFFFFFFFF) ==
                readerAppearanceUpdateKey(changedSpeed, systemDark = false, sasayakiTextColor = 0xFF111111, sasayakiBackgroundColor = 0xFFFFFFFF),
        )
    }

    @Test
    fun focusModeTogglesWithoutReloadingTheReaderContent() {
        val holder = stateHolder(initialIndex = 1)
        holder.markWebViewRestored()
        val previousEpoch = holder.webViewRestoreEpoch

        holder.toggleFocusMode()

        assertTrue(holder.focusMode)
        assertFalse(holder.isWebViewRestoring)
        assertEquals(previousEpoch, holder.webViewRestoreEpoch)

        holder.toggleFocusMode()

        assertFalse(holder.focusMode)
        assertEquals(previousEpoch, holder.webViewRestoreEpoch)
    }

    @Test
    fun readerInteractionsEnterFocusModeWithoutReloadingTheReaderContent() {
        val holder = stateHolder(initialIndex = 1)
        holder.markWebViewRestored()
        val previousEpoch = holder.webViewRestoreEpoch

        holder.enterFocusModeForReaderInteraction()

        assertTrue(holder.focusMode)
        assertFalse(holder.isWebViewRestoring)
        assertEquals(previousEpoch, holder.webViewRestoreEpoch)

        holder.enterFocusModeForReaderInteraction()

        assertTrue(holder.focusMode)
        assertEquals(previousEpoch, holder.webViewRestoreEpoch)
    }

    @Test
    fun acceptedReaderNavigationInputEntersFocusModeWithoutReloadingTheReaderContent() {
        val holder = stateHolder(initialIndex = 1)
        holder.markWebViewRestored()
        holder.showReaderMenu()
        val previousEpoch = holder.webViewRestoreEpoch

        assertTrue(holder.beginReaderNavigationInput())

        assertTrue(holder.focusMode)
        assertFalse(holder.showReaderMenu)
        assertFalse(holder.isWebViewRestoring)
        assertEquals(previousEpoch, holder.webViewRestoreEpoch)
    }

    @Test
    fun readerNavigationInputIsRejectedWhileWebViewIsRestoringWithoutChangingFocus() {
        val holder = stateHolder(initialIndex = 1)

        assertFalse(holder.beginReaderNavigationInput())

        assertFalse(holder.focusMode)
        assertTrue(holder.isWebViewRestoring)
    }

    @Test
    fun readerTapTogglesFocusModeOnlyWhenNoPopupIsVisible() {
        val holder = stateHolder(initialIndex = 1)
        holder.markWebViewRestored()
        val previousEpoch = holder.webViewRestoreEpoch

        assertTrue(holder.toggleFocusModeFromReaderTap(hasVisiblePopups = false))
        assertTrue(holder.focusMode)

        assertFalse(holder.toggleFocusModeFromReaderTap(hasVisiblePopups = true))
        assertTrue(holder.focusMode)

        assertTrue(holder.toggleFocusModeFromReaderTap(hasVisiblePopups = false))
        assertFalse(holder.focusMode)
        assertEquals(previousEpoch, holder.webViewRestoreEpoch)
    }

    @Test
    fun continuousScrollFocusTrackerOnlyStartsFocusForRealScrollGestures() {
        val tracker = ReaderContinuousScrollFocusTracker()

        tracker.onDown()
        assertFalse(tracker.onMove(2f, 2f))
        assertTrue(tracker.onMove(12f, 1f))
        assertFalse(tracker.onMove(20f, 1f))

        tracker.onCancel()
        assertFalse(tracker.onMove(2f, 2f))

        tracker.onDown()
        assertTrue(tracker.onMove(0f, -12f))
    }

    @Test
    fun enteringFocusModeClosesTheReaderMenu() {
        val holder = stateHolder()
        holder.showReaderMenu()
        assertTrue(holder.showReaderMenu)

        holder.enterFocusModeForReaderInteraction()

        assertTrue(holder.focusMode)
        assertFalse(holder.showReaderMenu)
    }

    @Test
    fun backNavigationExitsFocusModeBeforeClosingReader() {
        val holder = stateHolder()
        holder.enterFocusModeForReaderInteraction()

        assertFalse(holder.handleBackNavigation())
        assertFalse(holder.focusMode)

        assertTrue(holder.handleBackNavigation())
    }

    @Test
    fun emptyLookupStackConsumesSasayakiResumeRequest() {
        val holder = stateHolder()
        holder.markSasayakiPausedByLookup()
        var resumed = 0

        holder.setLookupPopups(emptyList()) { resumed += 1 }
        holder.setLookupPopups(emptyList()) { resumed += 1 }

        assertEquals(1, resumed)
        assertFalse(holder.sasayakiWasPausedByLookup)
    }

    @Test
    fun lookupAutoPauseOnlyMarksWhenEnabledAutoPauseAndPlaying() {
        val holder = stateHolder()

        assertFalse(holder.shouldPauseSasayakiForLookup(enabled = true, autoPause = true, isPlaying = false))
        assertFalse(holder.sasayakiWasPausedByLookup)
        assertTrue(holder.shouldPauseSasayakiForLookup(enabled = true, autoPause = true, isPlaying = true))
        assertTrue(holder.sasayakiWasPausedByLookup)
        assertFalse(holder.shouldPauseSasayakiForLookup(enabled = true, autoPause = false, isPlaying = true))
        assertFalse(holder.sasayakiWasPausedByLookup)
    }

    @Test
    fun menuActionsOpenOnlyTheRequestedSheet() {
        val holder = stateHolder()

        holder.showReaderMenu()
        holder.openAppearanceFromMenu()
        assertFalse(holder.showReaderMenu)
        assertTrue(holder.showAppearance)

        holder.dismissAppearance()
        holder.showReaderMenu()
        holder.openGoToFromMenu()
        assertFalse(holder.showReaderMenu)
        assertTrue(holder.showGoTo)

        holder.dismissGoTo()
        holder.showReaderMenu()
        holder.openSasayakiFromMenu()
        assertFalse(holder.showReaderMenu)
        assertTrue(holder.showSasayaki)
    }

    @Test
    fun readerMenuButtonTogglesMenuVisibility() {
        val holder = stateHolder()

        holder.toggleReaderMenu()
        assertTrue(holder.showReaderMenu)

        holder.toggleReaderMenu()
        assertFalse(holder.showReaderMenu)
    }

    private fun stateHolder(
        initialIndex: Int = 0,
        initialProgress: Double = 0.0,
    ): ReaderWebViewStateHolder =
        ReaderWebViewStateHolder(
            initialSettings = ReaderSettings(),
            initialPosition = ReaderChapterPosition(
                index = initialIndex,
                progress = initialProgress,
            ),
        )

    private fun lookupPopup(): LookupPopupItem =
        LookupPopupItem(
            state = LookupPopupState(
                selection = ReaderSelectionData(
                    text = "corner",
                    sentence = "corner",
                    rect = ReaderSelectionRect(x = 100.0, y = 100.0, width = 50.0, height = 24.0),
                    normalizedOffset = 0,
                ),
                results = emptyList(),
                isVertical = false,
            ),
        )
}
