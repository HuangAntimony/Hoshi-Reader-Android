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

        val eInkHandle = chromeSource.substringAfter("if (sheetStyle.eInkMode) {")
            .substringBefore("} else {")
        assertTrue(eInkHandle.contains("ReaderSheetTopOutline()"))
        assertFalse(chromeSource.substringAfter("} else {").contains("ReaderSheetTopOutline()"))
    }

    @Test
    fun appearanceSettingsScreenHandlesSystemBackLikeToolbarBack() {
        val source = File("src/main/java/moe/antimony/hoshi/features/reader/ReaderAppearanceView.kt").readText()
        val screen = source.substringAfter("internal fun ReaderAppearanceScreen(")
            .substringBefore("@OptIn(ExperimentalMaterial3Api::class)\n@Composable\ninternal fun ReaderAppearanceSheet(")

        assertTrue(screen.contains("BackHandler(onBack = onClose)"))
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

        listOf(chapterSource, appearanceSource, sasayakiSource).forEach { source ->
            assertTrue(source.contains("val sheetStyle = readerSheetStyle()"))
            assertTrue(source.contains("containerColor = sheetStyle.containerColor"))
            assertTrue(source.contains("contentColor = sheetStyle.contentColor"))
            assertTrue(source.contains("scrimColor = sheetStyle.scrimColor"))
            assertTrue(source.contains("dragHandle = { ReaderSheetDragHandle(sheetStyle) }"))
        }
    }
}
