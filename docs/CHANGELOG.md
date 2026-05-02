# Changelog

All notable user-visible changes to Hoshi Reader Android are documented here.
The format follows a Keep a Changelog style, and release sections use Semantic Versioning.

## [Unreleased]

### Added

- Add Sasayaki audiobook read-along support with an adjustable SRT Match page from the bookshelf, reader cue highlighting, audiobook import/playback controls, delay and speed adjustment, and persistent per-book playback state.
- Add iOS-aligned Sasayaki controls to lookup popups for replaying the selected cue, toggling the current playback pause state, or continuing playback from the selected cue.
- Add a Sasayaki setting for choosing whether selected audiobooks stay linked as external files or are copied into app storage.
- Add the Sasayaki audiobook storage toggle to the reader Sasayaki sheet.

### Fixed

- Align Sasayaki lookup popup control icons with the iOS symbols for replaying the cue, toggling playback, and playing forward from the cue.
- Use only system icons for Sasayaki lookup popup controls, including the play-forward action.
- Keep Sasayaki popup Replay as temporary cue playback so it does not overwrite the saved audiobook position.
- Make the Sasayaki lookup popup controls more compact so the three buttons fit in narrow popup windows.
- Keep restored Sasayaki audiobooks paused until the user explicitly starts playback, and expose the iOS-aligned Auto-Scroll controls in the reader Sasayaki sheet.
- Resume Sasayaki playback after closing a lookup popup when Auto-Pause on Lookup paused it.
- Switch reader sheet styling by E-ink Mode: Sasayaki no longer dims e-ink pages, while normal mode uses the same Material sheet chrome for Chapters, Appearance, and Sasayaki.
- Keep Sasayaki popup Play Forward anchored on the selected cue instead of briefly jumping to a stale or edge-aligned page with no highlight.
- Keep reader progress and saved book position in sync when Sasayaki Auto-Scroll follows audiobook cues across pages.
- Highlight the current Sasayaki cue immediately when audiobook playback starts, matching iOS read-along behavior.
- Match Sasayaki SRT files with the same cue accounting and non-reader TOC filtering as iOS so identical search windows report matching rates consistently.
- Keep Sasayaki audiobooks linked to the selected external media file instead of importing another private copy, matching iOS storage behavior.
- Reject mismatched import files before reading them, so EPUB, Sasayaki SRT, Sasayaki audiobook, local audio database, dictionary, and reader font imports only accept their supported file extensions.
- Simplify the reader Sasayaki sheet by removing the redundant header row, moving the audiobook storage toggle next to Load Audio, showing whether loaded audio is an external reference or app-private copy, and replacing Open with Remove while an audiobook is loaded.

## [v0.1.6] - 2026-05-02

### Added

- Add a Settings -> Diagnostics page that shows Android process exit diagnostics, saves them as a `.txt` file, and shares them as text for issue reports.

### Fixed

- Reduce excess top spacing in Books, Settings, and the Advanced settings header so those screens use more visible space near the status bar. #36
- Make Android system Back return from Settings -> Diagnostics to Settings instead of closing the app.
- Render lookup popups in e-ink mode with high-contrast square black/white styling for dictionary tags, frequency labels, and popup controls.
- Fix Dictionary tab lookup results rendering blank after search in v0.1.5 by loading popup CSS and JavaScript from the shared WebView bridge regardless of the result page base URL. #33
- Render SVG dictionary media in lookup popups, restoring icons embedded in structured dictionary definitions. #35
- Fix a crash when opening `また、同じ夢を見ていた.epub` by normalizing EPUB-private CSS before Android WebView renders reader chapters. #34

## [v0.1.5] - 2026-05-02

### Added

- Add an Appearance e-ink mode that maps app chrome, reader surfaces, selected controls, and progress indicators to pure black/white colors for e-ink displays.

### Fixed

- Improve reader sheet contrast for e-ink displays: Chapters is opaque, Chapters and Appearance no longer dim the page behind them, and both use a visible top outline boundary.
- Restore readable selected Appearance segmented controls and their Material selected check indicator in e-ink light/dark reader settings.
- Keep dictionary imports from dimming the Dictionaries page while the import spinner is shown.
- Remove the outline from the reader bottom Back/Menu buttons in light and dark themes.
- Reduce extra top and bottom spacing in the reader so page content uses more of the visible reading area. #29
- Prevent the reader from briefly flashing the start of a chapter before restoring the saved reading position. #30
- Delay lookup popup display until its first rendered entry is ready, avoiding a blank white popup flash on slow-refresh e-ink screens.
- Speed up dictionary results and lookup popups by serving shared popup assets from the WebView bridge and fetching entries lazily instead of embedding every entry in the initial HTML.
- Reflow reader pages after device orientation changes so the chapter is rendered for the new screen ratio. #31

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
