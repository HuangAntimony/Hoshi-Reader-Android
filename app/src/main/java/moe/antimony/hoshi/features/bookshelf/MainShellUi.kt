package moe.antimony.hoshi.features.bookshelf

import moe.antimony.hoshi.epub.BookEntry
import moe.antimony.hoshi.epub.BookShelf
import moe.antimony.hoshi.epub.BookSortOption

enum class MainTab(val label: String) {
    Books("Books"),
    Dictionary("Dictionary"),
    Settings("Settings"),
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

enum class SettingsDestination {
    Dictionaries,
    Anki,
    Appearance,
    Behavior,
    Advanced,
    ReportIssue,
    Diagnostics,
    About,
}

data class SettingsRowModel(
    val label: String,
    val destination: SettingsDestination,
)

data class BookshelfSectionModel(
    val title: String,
    val books: List<BookEntry>,
    val shelfName: String? = null,
    val isReading: Boolean = false,
    val isCollapsible: Boolean = false,
)

fun settingsGroups(): List<List<SettingsRowModel>> = listOf(
    listOf(
        SettingsRowModel("Dictionaries", SettingsDestination.Dictionaries),
        SettingsRowModel("Anki", SettingsDestination.Anki),
        SettingsRowModel("Appearance", SettingsDestination.Appearance),
        SettingsRowModel("Behavior", SettingsDestination.Behavior),
        SettingsRowModel("Advanced", SettingsDestination.Advanced),
    ),
    listOf(
        SettingsRowModel("Report an Issue", SettingsDestination.ReportIssue),
        SettingsRowModel("Diagnostics", SettingsDestination.Diagnostics),
        SettingsRowModel("About", SettingsDestination.About),
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
            BookSortOption.Title -> sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.metadata.title.orEmpty() })
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
                isReading = true,
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
        sections += BookshelfSectionModel("Unshelved", unshelved)
    }
    return sections
}
