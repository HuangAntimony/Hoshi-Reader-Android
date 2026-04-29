package moe.antimony.hoshi.features.bookshelf

import moe.antimony.hoshi.epub.BookEntry

enum class MainTab(val label: String) {
    Books("Books"),
    Dictionary("Dictionary"),
    Settings("Settings"),
}

enum class SettingsDestination {
    Dictionaries,
    Anki,
    Appearance,
    Advanced,
    GoogleDriveSync,
    ReportIssue,
    About,
}

const val ReportIssueUrl = "https://github.com/HuangAntimony/Hoshi-Reader-Android/issues"

data class SettingsRowModel(
    val label: String,
    val destination: SettingsDestination,
)

data class BookshelfSectionModel(
    val title: String,
    val books: List<BookEntry>,
)

fun settingsGroups(): List<List<SettingsRowModel>> = listOf(
    listOf(
        SettingsRowModel("Dictionaries", SettingsDestination.Dictionaries),
        SettingsRowModel("Anki", SettingsDestination.Anki),
        SettingsRowModel("Appearance", SettingsDestination.Appearance),
        SettingsRowModel("Advanced", SettingsDestination.Advanced),
        SettingsRowModel("Google Drive Sync", SettingsDestination.GoogleDriveSync),
    ),
    listOf(
        SettingsRowModel("Report an Issue", SettingsDestination.ReportIssue),
        SettingsRowModel("About", SettingsDestination.About),
    ),
)

fun bookshelfSections(entries: List<BookEntry>): List<BookshelfSectionModel> =
    if (entries.isEmpty()) {
        emptyList()
    } else {
        listOf(BookshelfSectionModel("Unshelved", entries))
    }

fun SettingsDestination.placeholderTitle(): String = when (this) {
    SettingsDestination.Anki -> "Anki"
    SettingsDestination.Appearance -> "Appearance"
    SettingsDestination.Advanced -> "Advanced"
    SettingsDestination.About -> "About"
    SettingsDestination.Dictionaries -> "Dictionaries"
    SettingsDestination.GoogleDriveSync -> "Google Drive Sync"
    SettingsDestination.ReportIssue -> "Report an Issue"
}
