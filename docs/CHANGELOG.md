# Changelog

All notable user-visible changes to Hoshi Reader Android are documented here.
The format follows a Keep a Changelog style, and release sections use Semantic Versioning.

## [Unreleased]

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
