# Changelog

What's new and fixed in Hoshi Reader Android.
Release notes before v1.3.0 are in [CHANGELOG_ARCHIVE.md](CHANGELOG_ARCHIVE.md).

## [Unreleased]

### Fixed

- Show manual update check errors in a dialog without expanding the About update section, and stop reopening dialogs for old preview download failures.

## [v1.5.0] - 2026-11-10

### Added

- Read books aloud using device, online, or self-hosted text-to-speech with voice previews, separate narration and dialogue voices, custom pronunciation per book or across your library, and sentence audio export to Anki via `{sentence-audio}`.
- Sync your library and reading activity with Hoshi on iOS through Google Drive and download books on demand, even on devices without Google Play Services.
- Show reading progress in pages for the current chapter and the whole book.
- Use dedicated Chinese and Korean profiles with automatic profile switching by book language, pinyin grouping for Chinese, and lookup of inflected Korean words.
- App language switching now works on Android 8–12.
- Mark books as read from the bookshelf, or choose **Mark Unread** to reset a book’s reading position and statistics.
- Use kuma3 Anki to create cards.
- Enable **Exit Reader on First Back** to leave the reader with one Back action.
- Revisit or delete up to 100 recent dictionary searches, including lookups from other apps.
- Use a local word-audio database from its original location to save storage.
- View and export recent errors and crash reports from Diagnostics.

### Changed

- Replace Visual Novel Mode with Paragraph Mode, which centers text and offers animated text reveal, sentence limits, and tap-to-advance.
- Use an optional sentence replay button and see the current sentence and book cover in system media controls for audiobook and text-to-speech playback.
- Improve Japanese audiobook transcription with more accurate matching for repeated passages and resumable model downloads.
- Browse Statistics with previous and next buttons and see the current period update automatically as the date changes.
- Edit individual reading sessions in Statistics and count reading time on the correct day when a session crosses your daily reset.
- Reimport a dictionary ZIP to replace the installed dictionary with the same title and type.
- Preserve more of the original EPUB layout, including blank space and image margins.
- Recommend LINE Seed JP and replace BIZ UDPGothic with BIZ UDGothic.
- Find updates more easily in About, including the option to install the latest development preview.

### Fixed

- Play local word audio for capitalized terms when the recording uses the lowercase spelling ([#177](https://github.com/HuangAntimony/Hoshi-Reader-Android/issues/177)).
- Keep the line you tap in the Custom CSS editor visible when the keyboard opens ([#126](https://github.com/HuangAntimony/Hoshi-Reader-Android/issues/126)).
- Open books with long chapters or large tables of contents faster and keep page turns responsive.
- Refresh progress and recent reading order promptly when you return to the bookshelf.
- Prevent duplicate shelf names from disrupting book assignments.
- Restore missing illustrations and covers in more EPUBs.
- Hide the status bar when viewing an illustration fullscreen.
- Fix clipped text and position drift in continuous reading.
- Keep selection controls usable across paragraphs and fix highlighting around furigana.
- Keep lookup buttons visible and audio menus inside the popup.
- Recognize more Japanese inflections and keep names with middle dots intact in lookups.
- Avoid repeated error messages when automatic update checks fail.

## [v1.4.0] - 2026-09-24

### Added

- Transcribe Japanese audiobooks on-device, with downloadable models, performance presets, pause/resume and subtitle matching while reading.
- Import multiline subtitles and export partial or complete matches as SRT.
- Configure lookup frequency sorting per profile.
- Preserve furigana in highlights; select an exact highlight to recolor or remove it.
- Choose global light/dark palettes, custom reading colors, interface accents and app-wide E-ink optimization.
- Archive deleted-book statistics, restore them on reimport and edit daily records.
- Browse calendar and all-time statistics with comparisons, averages and goal history.
- Use Anki tag placeholders such as `{document-title}` and `{expression}`.
- Long-press a lookup audio button to choose a recording for playback and Anki.
- Tap source text above Dictionary or shared lookup results while retaining sentence context for Anki.
- Choose Off, Dimmed, Toggle or Hidden furigana display.
- Show, blur or hide book covers, and optionally hide collapsed-shelf thumbnails.
- Add dictionary categories, category-aware Anki fields, Kanji lookup, stroke-order fonts and more complete pitch accents.
- Download Japanese reader fonts with family, variant and weight choices.
- Navigate popup terms with volume keys and open lookups through `hoshi://search?text=...` links.
- Explain when AnkiDroid can be used directly without AnkiConnect.

### Changed

- Keep display settings global across profiles and use consistent theme colors and clearer E-ink selections throughout the app.
- Simplify Statistics around daily goals, reading time, heatmaps and period charts; move its settings into the always-visible Stats tab.
- Clearing Dictionary search now opens the keyboard while preserving results and history.
- Use JapanesePod101, LanguagePod101 and Jisho as independently configurable default word-audio sources.
- Always export dictionary images to Anki as media files and translate the selected-glossary fallback label into Chinese.
- Make book search literal, show full sentence context, return up to 100 results and mark the selected result.
- Align reader line spacing more closely with iOS and include surrounding Japanese punctuation in Sasayaki highlights.
- Highlight the current audiobook chapter in Contents and apply Recent/Title sorting to Google Drive books.
- Use low-memory dictionary imports for automatic updates.

### Fixed

- Keep Sasayaki paused while switching between lookup words and preserve Dictionary history across tab switches.
- Prevent Unicode-equivalent AnkiDroid duplicates and restore duplicate-note search with AnkiDroid 2.25.
- Continue dictionary import batches after errors and identify each failed file.
- Show recoverable errors for unreadable books and restore Reader after a renderer crash.
- Keep cached Google Drive books visible during temporary automatic-refresh failures.
- Count Korean consistently in progress, search and subtitle matching while excluding furigana fallback text.
- Confirm before resetting dictionary CSS and keep large lookup popups and compact labels usable.
- Preserve text and images in unusually formatted EPUBs, including inline character images and their fallback text.
- Restore iOS backup files despite filename variations and import long, non-Latin book titles.
- Match subtitles immediately after SRT selection and fix transcription setup for some M4B and Ogg Opus audiobooks.
- Open EPUBs that previously stayed on the loading screen and keep pitch accents aligned when entries wrap.

## [v1.3.3] - 2026-08-13

### Added

- Center the current chapter when opening Contents or the Sasayaki chapter list.
- Enable or disable individual local audio sources while preserving their order.
- Import Ogg Opus audiobooks with metadata and chapters, and show audiobook duration before playback.
- Adjust the paginated/VN swipe threshold, including disabling swipe page turns.
- Create up to three Anki formats with independent icons, decks, fields, tags and duplicate states, with copy and confirmed-delete actions.
- Find existing Anki notes from per-format lookup buttons.
- Add precise cloze fields, numeric pitch graphs and advanced glossary mapping options.

### Changed

- Increase the default popup size to 500 × 500 dp and maximum configurable height to 1000 dp.
- Align Lapis, Kiku and Senren field presets with iOS.

### Fixed

- Import supported files with `#` or `?` in their names.
- Keep reduced-motion popup scrolling within the visible screen area.
- Restore Contents and fragment jumps for EPUBs with nested navigation documents.
- Prevent staggered multi-touch taps from turning pages.
- Support large book IDs in Google Drive sync and TTU backups.
- Keep lookup available on Sasayaki-highlighted VN text.

## [v1.3.2] - 2026-08-04

### Added

- Create a shelf while moving selected books.
- Add a reader image gallery and chapter-based progress in Reader and Statistics.
- Configure the daily statistics reset time and pause tracking while sheets or fullscreen images are open.
- Publish the current cover to the lock screen, a PNG file or supported iReader screen savers, with Fit, Fill and Stretch modes.

### Changed

- Expand Sasayaki delay to ±4 seconds and playback speed to 0.5–3×.
- Rename Go to as Contents, order its tabs as Chapters/Highlights/Gallery/Search, and remove overscroll deformation.

### Fixed

- Preserve progress and lookup offsets around numeric HTML entities.
- Refresh manual bookshelf sync in place and improve repeated cover scrolling.
- Remember Contents and Sasayaki tabs during the reader session.
- Keep VN lookups and Anki sentences complete across screen boundaries.
- Prevent exported Anki media from overwriting different files.
- Preserve dictionary order when merging inflected lookup results.
- Avoid incorrect statistics when Sasayaki jumps to the previous chapter with image holds enabled.

## [v1.3.1] - 2026-07-11

### Added

- Configure Reader's top safe area height.

### Changed

- Improve lookup ranking and normalization of iteration marks, full-width numbers and emphatic text.
- Raise daily goal limits to 200,000 characters and 12 hours.

### Fixed

- Keep Statistics visible after enabling it and refresh book covers when changing date ranges.
- Keep lookup highlights within the selected word near ruby.
- Prevent VN vertical text from jumping columns after ruby.
- Keep long-pressed volume keys paging or seeking instead of changing system volume.

## [v1.3.0] - 2026-07-01

### Added

- Add full-library Statistics with habit summaries, calendar ranges, per-book totals, daily/weekly goals and an Advanced visibility switch.

### Changed

- Open Go to on Chapters, followed by Highlights and Search, and focus the search field when selected.

### Fixed

- Prefer exact expression-and-reading local audio matches.
- Read M4B metadata when Android's metadata reader returns empty.
- Improve VN media, highlights, vertical layout, punctuation and lookup alignment.
- Respect vertical padding in VN and continuous modes.
- Prevent lookup crashes on supplementary-plane kanji such as 𠮟.
- Preserve Sasayaki image holds during fullscreen viewing and avoid repeated holds in continuous mode.
