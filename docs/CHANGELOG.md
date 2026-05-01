# Changelog

All notable user-visible changes to Hoshi Reader Android are documented here.
The format follows a Keep a Changelog style, and release sections use Semantic Versioning.

## [Unreleased]

### Fixed

- Reduce extra top and bottom spacing in the reader so page content uses more of the visible reading area. #29
- Prevent the reader from briefly flashing the start of a chapter before restoring the saved reading position. #30

## [v0.1.4] - 2026-05-01

### Changed

- Redesign the Books, Dictionary, and Settings shell with Material 3 adaptive navigation, responsive bookshelf sizing, and constrained large-screen settings layouts.
- Tighten the Books screen chrome by reducing book title weight, compacting shelf and row spacing, matching the phone bottom navigation surface to the page background with a divider, disabling bookshelf overscroll stretch, and smoothing bookshelf scrolling by caching scaled cover thumbnails and loading reading progress outside each grid item.
- Reduce decorative shadows, elevation, transparency, and reader fade animation across the main shell, dictionary search/popup, reader chrome, and settings groups for lower-power e-ink devices, while keeping low-cost outline borders for visible control boundaries.
- Use full-width Material-style dividers between Settings entries instead of iOS-style inset separators.
- Remove the duplicate large-screen navigation rail inset so tablet and landscape layouts do not waste extra blank space beside the left navigation rail.
- Align the Books shelf content to the start of the large-screen content area instead of centering the constrained grid with a wide empty gutter.

### Fixed

- Fix EPUBs with XHTML self-closing script tags rendering blank in the reader by loading chapter XHTML directly before injecting reader assets, matching iOS image sizing, and skipping blank pages produced by malformed short/image chapters. #24
- Clear reader and nested popup word highlights when dismissing lookup popups, so tapping the same word again opens a fresh popup instead of only clearing stale highlight state. #25
- Replace iOS-only reader font presets with Android Japanese Mincho and Gothic system font presets so switching fonts changes reader rendering. #26
- Keep the reader open when the device display orientation changes, instead of returning to the bookshelf. #27
- Import large local audio databases in the background with progress, require deleting the existing `android.db` before importing another one, and explain the extra free-space requirement for the copied database. #28

## [v0.1.2] - 2026-04-30

### Fixed

- Preserve embedded cover image ratios for Calibre SVG cover wrappers instead of stretching the cover image. #4
- Make Android system Back return from Settings -> Appearance to Settings instead of closing the app. #21
- Let EPUB, dictionary, font, and local audio database imports show Android providers that appear under "Browse files in other apps".
- Make dictionary popup swipe-to-dismiss default on with a lower threshold range, tolerate natural diagonal horizontal swipes, and apply Appearance changes immediately inside an open reader.

## [v0.1.1] - 2026-04-30

### Fixed

- Fix the Settings `Report an Issue` link so it opens the Android issue tracker. #2
- Fix dark-mode readability across the app, reader, and settings surfaces. #1
- Fix release APK EPUB import and reader startup failures.
- Keep the Background Audio segmented control evenly sized when labels wrap. #3
- Disable the Android stretch effect when dragging past the edge of the reader or dictionary popups.

## [v0.1.0] - 2026-04-28

### Added

- Add EPUB import through Android DocumentsUI with multi-book bookshelf storage.
- Add bookshelf covers, title metadata, recent/title sorting, duplicate import handling, progress display, and single-book deletion.
- Add the reader with saved position restore, page and chapter navigation, vertical Japanese text support, cover/image page handling, and reader chrome.
- Add reader appearance controls for theme, text orientation, font size, spacing, line height, furigana visibility, progress/title display, popup sizing, and imported reader fonts.
- Add the reader Chapters sheet for table-of-contents navigation and full-book progress.
- Add text selection in the reader with dictionary lookup popup positioning and selection highlighting.
- Add Yomitan dictionary import and management for term, frequency, and pitch dictionaries.
- Add the Dictionary tab search experience with iOS-style result rendering, nested lookup popups, dictionary ordering, enable/disable, and swipe delete.
- Add dictionary settings for default tab behavior, lookup limits, scan length, dictionary collapse, compact glossaries, expression tags, harmonic frequency, pitch deduplication, and custom CSS.
- Add local and remote word audio playback from dictionary result audio buttons.

### Changed

- Align Books, Dictionary, Settings, reader chrome, Appearance, dictionary management, and lookup popups with the iOS user-visible behavior.
- Use Material icons and compact iOS-style controls across the main shell, search chrome, reader menu, and settings pages.

### Fixed

- Restore saved reader progress without briefly flashing the beginning of the chapter.
- Keep lookup popups correctly positioned below the top system area.
- Keep reader and dictionary popup interactions consistent for menus, nested lookups, scrolling, and tap-outside dismissal.
- Preserve dictionary result scroll position when opening nested lookups.
- Let long dictionary popup content scroll consistently.
- Keep popup backgrounds and dictionary text readable in light and dark themes.
- Apply reader popup sizing and swipe-dismiss settings consistently in both Reader and Dictionary flows.
- Improve reader and bookshelf chrome spacing.
- Polish popup audio button alignment and pressed feedback.
