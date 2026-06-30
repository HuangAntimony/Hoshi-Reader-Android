package moe.antimony.hoshi.features.bookshelf

import androidx.annotation.StringRes
import moe.antimony.hoshi.R
import moe.antimony.hoshi.epub.BookEntry
import moe.antimony.hoshi.epub.BookShelf
import moe.antimony.hoshi.epub.BookSortOption

enum class MainTab(@param:StringRes val labelRes: Int) {
    Books(R.string.main_tab_books),
    Dictionary(R.string.main_tab_dictionary),
    Statistics(R.string.main_tab_statistics),
    Settings(R.string.main_tab_settings),
}

enum class MainShellNavigationLayout {
    BottomBar,
    NavigationRail,
}

enum class MainShellTextStyle {
    BodyLarge,
    TitleMedium,
    TitleLarge,
}

enum class MainShellFontWeight {
    Normal,
    Medium,
    SemiBold,
}

enum class MainShellTextOverflow {
    Clip,
    Ellipsis,
}

data class BookshelfHeaderTextLayout(
    val titleMaxLines: Int,
    val titleOverflow: MainShellTextOverflow,
    val titleUsesRemainingWidth: Boolean,
    val countMaxLines: Int,
    val countSoftWrap: Boolean,
)

internal fun bookshelfHeaderTextLayout(): BookshelfHeaderTextLayout =
    BookshelfHeaderTextLayout(
        titleMaxLines = 1,
        titleOverflow = MainShellTextOverflow.Ellipsis,
        titleUsesRemainingWidth = true,
        countMaxLines = 1,
        countSoftWrap = false,
    )

data class MainShellLayoutSpec(
    val navigationLayout: MainShellNavigationLayout,
    val pageHorizontalPaddingDp: Int,
    val contentMaxWidthDp: Int,
    val bookGridMinCellWidthDp: Int,
    val bookGridMaxColumns: Int,
    val bookGridSpacingDp: Int,
    val compactNavigationHeightDp: Int,
    val shelfTitleTextStyle: MainShellTextStyle,
    val shelfTitleFontWeight: MainShellFontWeight,
    val shelfCountTextStyle: MainShellTextStyle,
    val shelfHeaderVerticalPaddingDp: Int,
    val bookGridTopPaddingDp: Int,
    val bookGridVerticalSpacingDp: Int,
    val bookGridBottomPaddingDp: Int,
    val bookTitleTextStyle: MainShellTextStyle,
    val bookTitleFontWeight: MainShellFontWeight,
) {
    fun constrainedContentWidthDp(windowWidthDp: Int): Int =
        windowWidthDp.coerceAtMost(contentMaxWidthDp)

    fun bookGridColumns(contentWidthDp: Int): Int {
        val available = (contentWidthDp - pageHorizontalPaddingDp * 2).coerceAtLeast(bookGridMinCellWidthDp)
        val columns = (available + bookGridSpacingDp) / (bookGridMinCellWidthDp + bookGridSpacingDp)
        return columns.coerceIn(1, bookGridMaxColumns)
    }

    fun collapsedShelfPreviewColumns(contentWidthDp: Int): Int {
        val available = (contentWidthDp - pageHorizontalPaddingDp * 2).coerceAtLeast(CollapsedShelfCoverTargetWidthDp)
        val adaptiveColumns =
            ((available + CollapsedShelfCoverSpacingDp) / (CollapsedShelfCoverTargetWidthDp + CollapsedShelfCoverSpacingDp))
                .coerceAtLeast(1)
        val minimumPreviewColumns = (bookGridColumns(contentWidthDp) + 2).coerceAtLeast(4)
        return maxOf(adaptiveColumns, minimumPreviewColumns)
    }

    fun collapsedShelfPreviewCoverWidthDp(contentWidthDp: Int): Int {
        val available = (contentWidthDp - pageHorizontalPaddingDp * 2).coerceAtLeast(CollapsedShelfCoverTargetWidthDp)
        val columns = collapsedShelfPreviewColumns(contentWidthDp)
        return ((available - CollapsedShelfCoverSpacingDp * (columns - 1)) / columns).coerceAtLeast(1)
    }

    companion object {
        fun forWidthDp(widthDp: Int): MainShellLayoutSpec = when {
            widthDp < MediumWidthBreakpointDp -> MainShellLayoutSpec(
                navigationLayout = MainShellNavigationLayout.BottomBar,
                pageHorizontalPaddingDp = 16,
                contentMaxWidthDp = widthDp,
                bookGridMinCellWidthDp = 148,
                bookGridMaxColumns = 2,
                bookGridSpacingDp = 16,
                compactNavigationHeightDp = 64,
                shelfTitleTextStyle = MainShellTextStyle.TitleLarge,
                shelfTitleFontWeight = MainShellFontWeight.SemiBold,
                shelfCountTextStyle = MainShellTextStyle.TitleMedium,
                shelfHeaderVerticalPaddingDp = 0,
                bookGridTopPaddingDp = 0,
                bookGridVerticalSpacingDp = 12,
                bookGridBottomPaddingDp = 0,
                bookTitleTextStyle = MainShellTextStyle.BodyLarge,
                bookTitleFontWeight = MainShellFontWeight.Normal,
            )
            widthDp < ExpandedWidthBreakpointDp -> MainShellLayoutSpec(
                navigationLayout = MainShellNavigationLayout.NavigationRail,
                pageHorizontalPaddingDp = 24,
                contentMaxWidthDp = 640,
                bookGridMinCellWidthDp = 176,
                bookGridMaxColumns = 3,
                bookGridSpacingDp = 20,
                compactNavigationHeightDp = 64,
                shelfTitleTextStyle = MainShellTextStyle.TitleLarge,
                shelfTitleFontWeight = MainShellFontWeight.SemiBold,
                shelfCountTextStyle = MainShellTextStyle.TitleMedium,
                shelfHeaderVerticalPaddingDp = 0,
                bookGridTopPaddingDp = 0,
                bookGridVerticalSpacingDp = 12,
                bookGridBottomPaddingDp = 0,
                bookTitleTextStyle = MainShellTextStyle.BodyLarge,
                bookTitleFontWeight = MainShellFontWeight.Normal,
            )
            else -> MainShellLayoutSpec(
                navigationLayout = MainShellNavigationLayout.NavigationRail,
                pageHorizontalPaddingDp = 32,
                contentMaxWidthDp = 1040,
                bookGridMinCellWidthDp = 176,
                bookGridMaxColumns = 5,
                bookGridSpacingDp = 24,
                compactNavigationHeightDp = 64,
                shelfTitleTextStyle = MainShellTextStyle.TitleLarge,
                shelfTitleFontWeight = MainShellFontWeight.SemiBold,
                shelfCountTextStyle = MainShellTextStyle.TitleMedium,
                shelfHeaderVerticalPaddingDp = 0,
                bookGridTopPaddingDp = 0,
                bookGridVerticalSpacingDp = 12,
                bookGridBottomPaddingDp = 0,
                bookTitleTextStyle = MainShellTextStyle.BodyLarge,
                bookTitleFontWeight = MainShellFontWeight.Normal,
            )
        }
    }
}

private const val MediumWidthBreakpointDp = 600
private const val ExpandedWidthBreakpointDp = 840
internal const val CollapsedShelfCoverTargetWidthDp = 80
internal const val CollapsedShelfCoverSpacingDp = 12
internal const val GoogleDriveSectionCollapseKey = "__google_drive__"
private const val CompletedProgressThreshold = 0.999

enum class SettingsDestination {
    Dictionaries,
    Anki,
    Profiles,
    Appearance,
    Behavior,
    Advanced,
    ReportIssue,
    Diagnostics,
    About,
}

data class SettingsRowModel(
    @param:StringRes val labelRes: Int,
    val destination: SettingsDestination,
)

data class BookshelfSectionModel(
    val title: String,
    val books: List<BookEntry>,
    @param:StringRes val titleRes: Int? = null,
    val shelfName: String? = null,
    val isReading: Boolean = false,
    val isCollapsible: Boolean = false,
) {
    val layoutKey: String
        get() = when {
            isReading -> "__reading__"
            shelfName != null -> "shelf:$shelfName"
            else -> "unshelved"
        }

    val collapseKey: String?
        get() = when {
            isCollapsible -> layoutKey
            else -> null
        }
}

fun settingsGroups(): List<List<SettingsRowModel>> = listOf(
    listOf(
        SettingsRowModel(R.string.settings_profiles, SettingsDestination.Profiles),
        SettingsRowModel(R.string.settings_dictionaries, SettingsDestination.Dictionaries),
        SettingsRowModel(R.string.settings_anki, SettingsDestination.Anki),
        SettingsRowModel(R.string.settings_appearance, SettingsDestination.Appearance),
        SettingsRowModel(R.string.settings_behavior, SettingsDestination.Behavior),
        SettingsRowModel(R.string.settings_advanced, SettingsDestination.Advanced),
    ),
    listOf(
        SettingsRowModel(R.string.settings_report_issue, SettingsDestination.ReportIssue),
        SettingsRowModel(R.string.settings_diagnostics, SettingsDestination.Diagnostics),
        SettingsRowModel(R.string.settings_about, SettingsDestination.About),
    ),
)

fun bookshelfSections(
    entries: List<BookEntry>,
    shelves: List<BookShelf> = emptyList(),
    progressById: Map<String, Double> = emptyMap(),
    showReading: Boolean = false,
    sortOption: BookSortOption = BookSortOption.Recent,
): List<BookshelfSectionModel> {
    if (entries.isEmpty()) return emptyList()

    val entriesById = entries.associateBy { it.metadata.id }
    val sections = mutableListOf<BookshelfSectionModel>()
    fun List<BookEntry>.sortedForShelf(): List<BookEntry> =
        when (sortOption) {
            BookSortOption.Recent -> sortedByDescending { it.metadata.lastAccess }
            BookSortOption.Title -> sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.displayTitle })
        }

    if (showReading) {
        val reading = entries
            .filter { entry ->
                val progress = progressById[entry.metadata.id] ?: 0.0
                progress > 0.0 && progress < 0.999
            }
            .sortedForShelf()
        if (reading.isNotEmpty()) {
            sections += BookshelfSectionModel(
                title = "Reading",
                books = reading,
                titleRes = R.string.bookshelf_section_reading,
                isReading = true,
                isCollapsible = true,
            )
        }
    }

    shelves.forEach { shelf ->
        sections += BookshelfSectionModel(
            title = shelf.name,
            books = shelf.bookIds.mapNotNull(entriesById::get).sortedForShelf(),
            shelfName = shelf.name,
            isCollapsible = true,
        )
    }

    val shelvedIds = shelves.flatMapTo(mutableSetOf()) { it.bookIds }
    val unshelved = entries
        .filterNot { it.metadata.id in shelvedIds }
        .sortedForShelf()
    if (unshelved.isNotEmpty()) {
        sections += BookshelfSectionModel("Unshelved", unshelved, titleRes = R.string.bookshelf_section_unshelved)
    }
    return sections
}

internal fun googleDriveSectionInsertionIndex(sections: List<BookshelfSectionModel>): Int {
    val unshelvedIndex = sections.indexOfFirst { it.titleRes == R.string.bookshelf_section_unshelved }
    return if (unshelvedIndex >= 0) unshelvedIndex else sections.size
}

internal data class GoogleDriveSectionPresentation(
    val isCollapsible: Boolean,
    val isExpanded: Boolean,
    val alpha: Float,
    val allowsHitTesting: Boolean,
)

internal fun googleDriveSectionPresentation(
    shelfExpansionState: Map<String, Boolean>,
    isSelecting: Boolean,
): GoogleDriveSectionPresentation {
    val isExpanded = !isSelecting && (shelfExpansionState[GoogleDriveSectionCollapseKey] ?: false)
    return GoogleDriveSectionPresentation(
        isCollapsible = true,
        isExpanded = isExpanded,
        alpha = if (isSelecting) 0.4f else 1.0f,
        allowsHitTesting = !isSelecting,
    )
}

fun isBookCompleted(progress: Double): Boolean =
    progress >= CompletedProgressThreshold

fun bookshelfProgressText(progress: Double): String {
    val percentage = if (isBookCompleted(progress)) {
        100.0
    } else {
        progress.coerceIn(0.0, 1.0) * 100.0
    }
    return String.format(java.util.Locale.US, "%.1f%%", percentage)
}
