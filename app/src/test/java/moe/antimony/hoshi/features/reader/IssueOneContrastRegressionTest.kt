package moe.antimony.hoshi.features.reader

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class IssueOneContrastRegressionTest {
    @Test
    fun mainShellProvidesReadableContentColorOnAppBackground() {
        val source = File("src/main/java/moe/antimony/hoshi/features/bookshelf/BookshelfView.kt").readText()
        val shell = source.substringAfter("internal fun HoshiMainShell(")
            .substringBefore("private val NavigationRailInset")

        assertTrue(shell.contains("NavigationSuiteScaffold("))
        assertTrue(shell.contains("containerColor = MaterialTheme.colorScheme.background"))
        assertTrue(shell.contains("contentColor = MaterialTheme.colorScheme.onBackground"))
    }

    @Test
    fun chapterSheetUsesOpaqueSurfaceSoReaderDoesNotShowThrough() {
        val chromeSource = File("src/main/java/moe/antimony/hoshi/features/reader/ReaderSheetChrome.kt").readText()

        assertTrue(chromeSource.contains("val containerColor = if (eInkMode) MaterialTheme.colorScheme.surface else BottomSheetDefaults.ContainerColor"))
        assertTrue(chromeSource.contains("contentColor = if (eInkMode) MaterialTheme.colorScheme.onSurface else contentColorFor(containerColor)"))
    }

    @Test
    fun readerHalfSheetsDoNotDimPureReaderBackgrounds() {
        val chromeSource = File("src/main/java/moe/antimony/hoshi/features/reader/ReaderSheetChrome.kt").readText()

        assertTrue(chromeSource.contains("scrimColor = if (eInkMode) Color.Transparent else BottomSheetDefaults.ScrimColor"))
    }

    @Test
    fun readerHalfSheetsDrawTopOutlineBoundary() {
        val chromeSource = File("src/main/java/moe/antimony/hoshi/features/reader/ReaderSheetChrome.kt").readText()
        val dragHandle = chromeSource.substringAfter("internal fun ReaderSheetDragHandle(")
            .substringBefore("@Composable\ninternal fun ReaderSheetTopOutline")

        val eInkHandle = dragHandle.substringAfter("if (sheetStyle.eInkMode) {")
            .substringBefore("} else {")
        assertTrue(eInkHandle.contains("ReaderSheetTopOutline()"))
        assertFalse(dragHandle.substringAfter("} else {").contains("ReaderSheetTopOutline()"))
    }

    @Test
    fun appearanceSettingsScreenHandlesSystemBackLikeToolbarBack() {
        val source = File("src/main/java/moe/antimony/hoshi/features/reader/ReaderAppearanceView.kt").readText()
        val screen = source.substringAfter("internal fun ReaderAppearanceScreen(")
            .substringBefore("@OptIn(ExperimentalMaterial3Api::class)\n@Composable\ninternal fun ReaderAppearanceSheet(")
        val scaffold = File("src/main/java/moe/antimony/hoshi/features/settings/SettingsDetailScaffold.kt").readText()

        assertTrue(screen.contains("SettingsDetailScaffold("))
        assertTrue(scaffold.contains("BackHandler(onBack = onClose)"))
    }

    @Test
    fun appearanceSegmentedButtonsKeepMaterialSelectedIndicatorAndContrast() {
        val source = File("src/main/java/moe/antimony/hoshi/features/reader/ReaderAppearanceView.kt").readText()
        val segmentedRow = source.substringAfter("private fun SegmentedRow(")
            .substringBefore("internal fun segmentedControlWidthDp(")

        assertFalse(segmentedRow.contains("icon = {}"))
        assertFalse(segmentedRow.contains("colors ="))
        assertFalse(source.contains("private fun segmentedButtonColors("))
    }

    @Test
    fun readerSheetsUseModeAwareSharedChrome() {
        val chapterSource = File("src/main/java/moe/antimony/hoshi/features/reader/ReaderChapterSheet.kt").readText()
        val appearanceSource = File("src/main/java/moe/antimony/hoshi/features/reader/ReaderAppearanceView.kt").readText()
        val sasayakiSource = File("src/main/java/moe/antimony/hoshi/features/sasayaki/SasayakiSheet.kt").readText()

        listOf(chapterSource, sasayakiSource).forEach { source ->
            assertTrue(source.contains("val sheetStyle = readerSheetStyle()"))
            assertTrue(source.contains("containerColor = sheetStyle.containerColor"))
            assertTrue(source.contains("contentColor = sheetStyle.contentColor"))
            assertTrue(source.contains("scrimColor = sheetStyle.scrimColor"))
        }
        assertTrue(appearanceSource.contains("val sheetStyle = readerSheetStyle().copy("))
        assertTrue(appearanceSource.contains("containerColor = sheetStyle.containerColor"))
        assertTrue(appearanceSource.contains("contentColor = sheetStyle.contentColor"))
        assertTrue(appearanceSource.contains("scrimColor = sheetStyle.scrimColor"))
        assertTrue(chapterSource.contains("dragHandle = { ReaderSheetDragHandle(sheetStyle) }"))
        assertTrue(appearanceSource.contains("dragHandle = { ReaderSheetDismissDragHandle(sheetStyle, sheetState, onDismiss) }"))
        assertTrue(sasayakiSource.contains("ReaderSheetDismissDragHandle(sheetStyle, sheetState)"))
        assertTrue(sasayakiSource.contains("if (!isImporting) {\n                    onDismiss()"))
    }

    @Test
    fun appearanceSheetStaysPartiallyExpandedAndScrollsInternally() {
        val source = File("src/main/java/moe/antimony/hoshi/features/reader/ReaderAppearanceView.kt").readText()
        val sheet = source.substringAfter("internal fun ReaderAppearanceSheet(")
            .substringBefore("@Composable\nprivate fun ReaderAppearanceContent(")

        assertTrue(sheet.contains("rememberModalBottomSheetState(skipPartiallyExpanded = false)"))
        assertTrue(sheet.contains("sheetState = sheetState"))
        assertTrue(sheet.contains("sheetGesturesEnabled = false"))
        assertTrue(sheet.contains("ReaderSheetDismissDragHandle("))
        assertTrue(sheet.contains("containerColor = palette.background"))
        assertTrue(sheet.contains("modifier = Modifier.readerMediumSheetContentHeight()"))
    }

    @Test
    fun readerFixedSheetsDismissOnlyFromDragHandleRow() {
        val chromeSource = File("src/main/java/moe/antimony/hoshi/features/reader/ReaderSheetChrome.kt").readText()
        val handle = chromeSource.substringAfter("internal fun ReaderSheetDismissDragHandle(")
            .substringBefore("@Composable\n@OptIn(ExperimentalMaterial3Api::class)\ninternal fun ReaderSheetDragHandle")
        val sasayakiSource = File("src/main/java/moe/antimony/hoshi/features/sasayaki/SasayakiSheet.kt").readText()
        val appearanceSource = File("src/main/java/moe/antimony/hoshi/features/reader/ReaderAppearanceView.kt").readText()

        assertTrue(handle.contains("detectVerticalDragGestures("))
        assertTrue(handle.contains("sheetState.hide()"))
        assertTrue(handle.contains("onDismiss()"))
        assertTrue(handle.contains(".background(sheetStyle.containerColor)"))
        assertTrue(sasayakiSource.contains("sheetGesturesEnabled = false"))
        assertTrue(sasayakiSource.contains("ReaderSheetDismissDragHandle(sheetStyle, sheetState)"))
        assertTrue(sasayakiSource.contains("if (!isImporting) {\n                    onDismiss()"))
        assertTrue(appearanceSource.contains("sheetGesturesEnabled = false"))
        assertTrue(appearanceSource.contains("dragHandle = { ReaderSheetDismissDragHandle(sheetStyle, sheetState, onDismiss) }"))
    }
}
