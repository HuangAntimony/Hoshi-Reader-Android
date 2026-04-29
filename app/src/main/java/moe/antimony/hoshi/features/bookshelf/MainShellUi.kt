package moe.antimony.hoshi.features.bookshelf

import androidx.annotation.StringRes
import moe.antimony.hoshi.R
import moe.antimony.hoshi.epub.BookEntry

enum class MainTab(
    val label: String,
    @param:StringRes val labelRes: Int,
) {
    Books("Books", R.string.main_tab_books),
    Dictionary("Dictionary", R.string.main_tab_dictionary),
    Settings("Settings", R.string.main_tab_settings),
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
    @param:StringRes val labelRes: Int,
    val destination: SettingsDestination,
)

data class BookshelfSectionModel(
    val title: String,
    val books: List<BookEntry>,
)

fun settingsGroups(): List<List<SettingsRowModel>> = listOf(
    listOf(
        SettingsRowModel("Dictionaries", R.string.settings_dictionaries, SettingsDestination.Dictionaries),
        SettingsRowModel("Anki", R.string.settings_anki, SettingsDestination.Anki),
        SettingsRowModel("Appearance", R.string.settings_appearance, SettingsDestination.Appearance),
        SettingsRowModel("Advanced", R.string.settings_advanced, SettingsDestination.Advanced),
        SettingsRowModel("Google Drive Sync", R.string.settings_google_drive_sync, SettingsDestination.GoogleDriveSync),
    ),
    listOf(
        SettingsRowModel("Report an Issue", R.string.settings_report_issue, SettingsDestination.ReportIssue),
        SettingsRowModel("About", R.string.settings_about, SettingsDestination.About),
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
