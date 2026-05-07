# Changelog

All notable user-visible changes to Hoshi Reader Android are documented here.
The format follows a Keep a Changelog style, and release sections use Semantic Versioning.

## [Unreleased]

### Fixed

- Speed up paginated reader page turns by caching chapter page bounds after layout, triggering swipe page turns during quick drags or short fast flicks, updating visible progress from memory immediately, debouncing bookmark saves until page turning is idle, flushing pending page-turn saves before closing or backgrounding the reader, skipping no-op selection bridge calls, and avoiding bookshelf refresh invalidations while the reader is open.

## [v0.3.1] - 2026-05-06

### Changed

- Raise the reader Appearance maximum font size to 60 for larger text. #46
- Move Sasayaki audiobook playback and dictionary word audio onto Media3 ExoPlayer for more consistent playback, seeking, speed control, and system media integration.

### Fixed

- Make paginated reader swipes match iOS text orientation: horizontal text advances on left swipe, while vertical text continues to advance on right swipe.
- Keep Sasayaki previous/next cue controls available from Android system media controls, and make paused cue seeks reveal the correct text without flickering back to the old highlight.
- Make dictionary word audio respect the Background Audio setting, so Interrupt can hand audio focus back afterward while Lower Volume uses Android's best-effort ducking request and Keep Volume keeps background audio playing.
- Let local audio `android.db` imports appear in stricter vendor Android file pickers while still rejecting non-database files after selection.

## [v0.3.0] - 2026-05-05

### Added

- Add the iOS-style reader continuous scroll mode with an Appearance layout toggle and chapter boundary swipe distance control.
- Add AnkiDroid mining from dictionary lookup results, including Lapis field defaults, iOS-compatible handlebars, duplicate checks, tags, and media export support.

### Fixed

- Keep dictionary popup entries readable when Android is in system night mode but Hoshi is using the light app theme.
- Make dictionary management reordering use an explicit drag handle and require tapping the revealed trash button after a left swipe, reducing accidental dictionary deletion.
- Keep repeated dictionary reordering aligned with the row being dragged when several dictionaries are installed, and prevent the release animation from snapping back before settling.
- Match the bookshelf cover loading placeholder to the shelf background, preventing white cover flashes when returning to Books from the bottom tab bar in dark mode.
- Wait for saved reader appearance settings and the first bookshelf load before drawing the app shell, avoiding light-theme empty-library flashes during dark-theme cold starts.
- Let the Android launch screen follow the system light or dark mode before the app UI is ready.
- Match the reader loading screen to the active reader background, preventing a white flash when opening books in dark mode.
- Keep the bookshelf visible while opening an existing book, preventing a brief loading-spinner flash before the reader appears.
- Refresh Settings detail controls immediately after toggles or option changes, and remove Navigation3 fade transitions so page switches are e-ink friendly.
- Keep external EPUB opens and Sasayaki media-control returns in the existing Hoshi task, and ignore duplicate EPUB import requests while an import is already running.

## [v0.2.1] - 2026-05-03

### Added

- Add Android system media controls for Sasayaki audiobook playback, including the current book cover, so play/pause, previous/next cue, and seeking can be controlled from the media controls area while reading.

### Fixed

- Keep the paged reader snapped to full pages while selecting text, so Android WebView selection handles no longer leave vertical text split between page offsets. #43
- Keep Sasayaki cue highlighting aligned after compatibility ideographs such as `猪`, so subsequent audiobook cues no longer drift by one character.
- Keep the reader open when returning from Android system media controls during Sasayaki audiobook playback, preventing overlapping audio from a second app entry.
- Keep the screen awake during Sasayaki audiobook playback only when Auto-Scroll is enabled, matching iOS and restoring normal sleep behavior when playback pauses or Auto-Scroll is off.
- Keep reader Appearance and Sasayaki sheets fixed at half height so their internal settings lists scroll without fighting bottom-sheet expansion gestures, while preserving drag-handle swipe-down dismissal and matching the Appearance handle background to the settings page. #42

## [v0.2.0] - 2026-05-03

### Added

- Add Sasayaki audiobook read-along support, including SRT matching, cue highlighting, audiobook playback controls, delay and speed adjustment, auto-scroll, and saved per-book playback state.
- Add Sasayaki controls in lookup popups and reader settings for replaying or continuing from the selected cue, pausing playback during lookup, and choosing whether audiobooks stay linked as external files or are copied into app storage.
- Add an Appearance option to invert Sepia reader pages in system dark mode.
- Add fixed Page Up/Page Down reader paging and a Settings -> Behavior page for optional volume-key paging with reversible direction.
- Add lookup popup link redirects with back/forward history, optional popup action bar controls, and a Compact Pitch Accents dictionary setting.

### Fixed

- Reject mismatched import files before reading them, so EPUB, Sasayaki SRT, Sasayaki audiobook, local audio database, dictionary, and reader font imports only accept their supported file extensions.
- Use the same compact Settings detail header for Dictionaries, Appearance, and Advanced so subpage content starts directly below a single top bar.
- Prevent dictionary lookups from starting when tapping links, ruby text, or popup cross-reference links, and align reader WebView text sizing with iOS.
- Keep reader progress and page turns in sync after tapping EPUB internal chapter links from the in-book table of contents. #39
- Keep lookup popup text, tags, and controls readable when E-ink Mode and Dark theme are both enabled. #37
- Let long Dictionary tab lookup results scroll normally after search. #40
- Keep Dictionary tab cross-reference links responsive after repeated redirects and fast when swiping back to prior results. #41

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
