# Changelog

All notable user-visible changes to Hoshi Reader Android are documented here.
The format follows a Keep a Changelog style, and release sections use Semantic Versioning.

## [Unreleased]

### Added

- Add shelf renaming from each shelf row's management menu.
- Add iOS-style automatic dictionary updates with Daily, Weekly, and Monthly intervals, Last Update display, foreground checks on unmetered networks, shared import/update busy state, and partial-failure handling for manual updates.
- Add Anki field templates for Lapis, Kiku, and Senren models using Hoshi's Android Lapis defaults on the corresponding model fields, plus additional glossary handlebars for brief, no-dictionary, selected-dictionary fallback, and per-dictionary glossary variants.

### Changed

- Update the ッツ Sync setup guidance to link to Hoshi Reader's ッツ Sync guide and match the Android Device Code client setup steps.

### Fixed

- Keep the shelf management dialog usable for long custom-shelf lists.
- Keep long bookshelf shelf names from wrapping over the book count and expansion chevron.
- Recover packed EPUB reader caches when a cached chapter file is missing, preventing migrated books from showing a WebView 404 for an otherwise valid chapter.
- Improve lookup popup responsiveness after repeated lookups by removing obsolete native button frame synchronization from iframe popups.
- Keep Sasayaki reader highlights from clipping the left edge of non-ruby characters in vertical writing mode.
- Keep reader page turns responsive after entering chapters with Sasayaki cues by avoiding duplicate WebView cue reapplication.

## [v1.1.4] - 2026-06-08

### Added

- Show the installed WebView package and version in Settings -> Diagnostics.
- Add iOS-compatible Books backup storage, EPUB export, ッツ bookdata import/export with edge-case handling, Google Drive Upload Books sync, and remote-only book import/trash actions.

### Changed

- Prepare existing books for the new backup-compatible storage, with Bookshelf progress shown.
- Align Dictionary search and Process Text lookup popups with Reader behavior.
- Tighten Dictionary search layout and interactions, including replace-in-place searches and active-tab refocus.

### Fixed

- Keep the Advanced Audio Local source switch synchronized with the Local Audio setting, preventing duplicate Local sources when turning it off from the source list.
- Show the update prompt during the same app session when the startup update check finds a new APK, instead of waiting until the next launch.
- Import stored-entry EPUB ZIP archives that previously failed.
- Improve vertical reader and ruby handling across pagination, lookup highlights, safe-area taps, zero-padding blurred images, final partial pages, large images, and E-ink Sasayaki underlines.
- Trim unmatched quotation brackets from reader lookup sentences before Anki mining. #98
- Render Japanese text with Android Japanese font fallbacks in Reader, Dictionary search, Process Text, and lookup popups.
- Keep Reader chapters visible when EPUB image resources are broken.

## [v1.1.3] - 2026-06-04

### Added

- Add an Android 13+ app language picker in Advanced settings for following the system app language, English, or Simplified Chinese.
- Add a long-press entry point for revealing dictionary deletion from the Dictionaries screen while keeping the reorder handle dedicated to dragging.
- Add iOS-style custom reader themes with a separate interface mode plus configurable reader background, text, and info colors.

### Changed

- Split Sasayaki reader highlighting into inline WebView highlights for non-E-ink readers and ruby-aware overlay marks for E-ink readers.

### Fixed

- Keep EPUB publisher CSS from overriding reader layout with embedded writing mode, line height, height, positive indentation, or nested column-count rules, including image-page vertical writing rules that could crash Android WebView. #78 #90
- Prevent reader chapter loading crashes from optional restore payload setup and avoid app freezes when mining Anki cards from lookup popups.
- Prevent Sasayaki highlights from reflowing reader text, keep emphasized text marks visible, improve Sasayaki and lookup marks around furigana, restore colored highlights after toggling E-ink Mode off, and let books open before Sasayaki match sidecars finish loading.

## [v1.1.2] - 2026-05-31

### Added

- Add a Reader Appearance switch to hide the reader's bottom back button while keeping Android system back gestures available.
- Add an Anki setting to sync AnkiDroid automatically after a card is added. #86
- Add local audio database source ordering, including automatic default order generation on import and simple up/down controls for lookup playback and Anki audio export.
- Add EPUB folder import from the Books screen, recursively finding books in the selected folder and its subfolders.

### Changed

- Replace Sasayaki's old bottom skip buttons with safe-area pinned rewind, play or pause, and fast-forward controls, preserving the old bottom-controls setting value and keeping bottom progress text on the right when both are shown.

### Fixed

- Match the reader's bottom-right menu order to iOS, with Sasayaki and Statistics appearing above Highlights, Chapters, and Appearance when available.
- Keep bookshelf covers filled and cached when returning to Books, reducing cover flashes and scroll jank.
- Keep reader lookup popup action buttons readable in dark mode while preserving a distinct disabled state.
- Keep lookup popup deinflection explanation overlays and JMdict forms tables readable across Light, Sepia Light, and Dark themes. #87 #88
- Show unselected bookshelf multi-select books with an empty circle instead of a check mark, keeping selected and unselected states distinguishable in E-ink mode.
- Play word audio from imported local audio databases that store entries as Opus/Ogg files, and export those clips to Anki with the correct audio type. #48
- Continue importing later dictionary archives when one selected archive fails, then report the files that could not be imported.

## [v1.1.1] - 2026-05-27

### Changed

- Rework reader lookup popups to render inside the reader WebView through an iframe layer, improving popup dismissal, touch stability, large-result first paint, and selection-highlight reveal timing while preserving E-ink underline marks, recursive lookup, audio, Anki, redirect history, and native-aligned action and Sasayaki controls.
- Support ABI-specific GitHub update APKs while retaining compatibility with the existing arm64 update package during the transition.
- Speed up opening books from the bookshelf by avoiding duplicate EPUB parsing and reusing saved reader character-count sidecars when they still match the EPUB spine.

### Fixed

- Prevent Anki mining from adding book covers or audio media when their corresponding field placeholders are not used.
- Keep fullscreen reader image zoom aligned with the tapped or pinched area, prevent panning beyond the image bounds, and keep image edges visible outside the safe-area-aware top controls.

## [v1.1.0] - 2026-05-24

### Added

- Add fullscreen reader image viewing with zoom, copy, save, and share controls for large images and SVG illustrations, while keeping Blur Images taps from opening lookup until the image is revealed.
- Add an Advanced reader Layout setting for paragraph spacing, matching iOS vertical and horizontal text spacing behavior.
- Add iOS-aligned immersive reader chrome and focus mode, with floating controls over fullscreen text, transient system bars, focus quick controls, Android Back revealing chrome before closing the reader, and theme/scroll interactions that avoid unnecessary reader reloads.

### Fixed

- Keep status bar icons readable when Android is in system dark mode but the reader uses Light, Sepia Light, or Sepia Dark themes. #73
- Improve reader pagination and continuous layout so vertical text uses page-height columns, and continuous padding restores and displays against the padded viewport.
- Keep recursive lookup popup selection honoring the configured scan length instead of always scanning 16 characters.

## [v1.0.2] - 2026-05-24

### Fixed

- Keep reader word lookup working when Android restores directly back into an open book after the app process is reclaimed in the background.
- Keep reader word lookup clickable after toggling E-ink Mode from the reader Appearance sheet.
- Restore stylus taps for reader lookup on tablets and let stylus taps outside the reader lookup popup close it, while preserving finger taps and lookup popup interactions.
- Keep the light reader bottom menu outline visible against the white reader background.
- Prevent Books from crashing when the Reading Shelf is enabled while a user-created shelf is also named Reading.
- Prevent rapid reader page turns during chapter restore from skipping over the newly loading chapter or briefly showing boundary progress from a stale scroll callback.

## [v1.0.1] - 2026-05-22

### Added

- Add a Sasayaki setting to reverse only the reader bottom skip button actions in vertical writing mode.

### Changed

- Allow Sasayaki audiobook playback speed up to 2x.

### Fixed

- Keep editable text-field cursors visible in dark and E-ink themes, and keep single-line fields horizontally scrolling with cursor movement when editing long text. #79
- Keep continuous reader backward chapter turns landing at the previous chapter end after jumping to a mid-book chapter.
- Allow AnkiconnectAndroid's Local Audio URL to be added as an external reusable audio source without being swallowed by Hoshi's built-in Local Audio source. #80

## [v1.0.0] - 2026-05-21

### Added

- Add a Reader Appearance option to blur large reader images and SVG image containers until tapped.
- Add a Dictionaries import setting for Low Memory Usage Mode, with default-off behavior and a short import-speed tradeoff note.

### Fixed

- Keep lookup popup audio and Anki buttons aligned after toggling harmonic frequency rows, and prefer the fitting right side for vertical-text lookup popups.
- Prewarm lookup popup custom fonts so user CSS with imported fonts does not leave popup text blank while the font loads.

## [v0.7.4] - 2026-05-20

### Added

- Add initial Simplified Chinese localization support for core Bookshelf, Dictionary, Reader, Backup, and update prompt flows, with Android system/app-language resource support and translator contribution docs.

### Changed

- Rewrite lookup popup presentation around a shared native Android overlay for reader, Dictionary tab, and Process Text popups. Popup WebViews now render through Android-managed popup hosts instead of Compose popup surfaces, while preserving the warmed reader root popup path for faster repeated lookups, keeping recursive popup placement aligned after internal dictionary redirects, and preserving popup gestures that cross the popup edge.

### Fixed

- Keep Google Drive Device Code authorization polling through transient DNS, timeout, and network failures, restore clearer Google Cloud Console client setup guidance, and clarify the fallback of opening the device-code page on another phone or computer.
- Keep Dictionary tab recursive lookup highlights and popup placement aligned in E-ink mode, including line-style selection marks and lower-screen child popups.
- Prevent settings segmented controls from shifting option labels when changing selection.

## [v0.7.3] - 2026-05-18

### Fixed

- Keep reader lookup highlights on ruby text starting at the tapped character instead of expanding to the whole ruby base.
- Prevent scrolled reader lookup popups from leaving the hidden warm shell at the old position and blocking reader input after dismissal.
- Prevent stale native lookup popup audio and Anki button positions from being drawn while dictionary sections expand or collapse on slow E-ink devices.

## [v0.7.2] - 2026-05-18

### Fixed

- Keep reader lookup popups pre-rendering at their real size before they become touchable, preventing slow first render, white popup flashes, and delayed content after autoplay starts.

## [v0.7.1] - 2026-05-17

### Added

- Keep lookup popup audio and Anki controls native and aligned to entry headers while popup content scrolls, including under Sasayaki popup controls, matching iOS behavior.

### Fixed

- Prevent reader lookup popups from leaving an invisible touch-blocking layer after scrolling and dismissing the popup.

## [v0.7.0] - 2026-05-16

### Added

- Add iOS-style reader highlights with color selection, persistent `highlights.json` storage, restore on chapter load, and a reader Highlights sheet for jumping to or deleting highlights.
- Add iOS-style reader jump return controls so chapter, character, highlight, and internal-link jumps can return to the previous reading position.
- Add iOS-style lookup popup font CSS support and a popup scale setting, including Custom CSS insertion menus for imported reader fonts and dictionary selectors.
- Add iOS-style book title renaming from the Books long-press menu, including renamed titles in sorting, reader metadata, confirmations, and sync messages.
- Add JMnedict to recommended dictionary downloads and switch the recommended JMdict package to the no-proper-names edition.
- Show iOS-aligned deinflection explanation popups when tapping conjugation tags in dictionary lookup results.
- Detect dictionary archive types during manual import and place term, frequency, and pitch data in their matching tabs without choosing a type first.
- Add Advanced AnkiConnect settings and AnkiConnect mining support, including HTTPS internet hosts, private HTTP hosts, deck/model fetch, duplicate checks, media storage, add-note, and optional force sync.

### Fixed

- Use the selected EPUB file name as the visible book title when imported metadata has no usable title.
- Remove deleted dictionaries from the collapsed dictionary configuration.
- Group highlights from unlabeled EPUB spine entries under the nearest previous visible chapter in the reader Highlights sheet.

## [v0.6.3] - 2026-05-15

### Added

- Add a Behavior setting to keep the screen awake while reading books without Sasayaki audio.
- Add iOS-style dictionary update checks for installed updatable dictionaries, including revision comparison, download/install progress, preservation of dictionary order and enabled state, and Anki single-glossary field migration when a dictionary title changes.
- Add recommended dictionary downloads for JMdict, Jiten, and Jitendex, with each dictionary downloaded individually.

### Fixed

- Keep continuous-mode reader lookup popups aligned with selected text when reader padding is applied.
- Skip low-confidence short Sasayaki subtitle cues during matching, matching iOS behavior and avoiding poor read-along alignments.

## [v0.6.2] - 2026-05-14

### Added

- Add a reader popup Reduced Motion Scrolling option that scrolls lookup popups by a configurable percentage of the current popup height.

### Changed

- Change GitHub release updates to automatically check only, prompt before downloading, support skipping a version, and clean up installed-version APKs.
- Draw reader lookup selection marks as close underlines in E-ink mode instead of filled highlights.

### Fixed

- Prevent the About update section from flashing stale default status while loading saved update state, and keep update prompt actions aligned on one row.
- Reuse a warm reader root lookup popup shell so repeated reader lookups avoid rebuilding the popup WebView.
- Keep reader popup internal dictionary redirects from rendering stale entries from the previous popup result.
- Synchronize popup-to-popup selection marks with child popup display and draw E-ink popup selections as underlines.
- Keep vertical lookup selection marks and popup placement aligned to one ruby-aware selection area so furigana is not covered.
- Keep reader progress counters from refreshing ahead of paginated page turns on slow E-ink screens by waiting for the WebView page state to be ready to draw.
- Synchronize reader lookup popup visibility with the selected-word highlight on slow E-ink screens, while keeping highlighted text readable.
- Prevent reader lookup popups from briefly showing a blank white shell while opening or dismissing on slow E-ink screens.

## [v0.6.1] - 2026-05-14

### Fixed

- Replace Google Drive sync authorization with user-configured Device Code flow so Android can use the same Google Cloud project as iOS/ッツ sync.

## [v0.6.0] - 2026-05-14

### Added

- Add iOS-compatible Google Drive sync with Advanced -> Syncing settings, Google Cloud OAuth setup guidance, bookshelf long-press manual sync, reader auto import/export triggers, statistics sync options, and Sasayaki playback-position sync.

### Fixed

- Keep the reader top title centered with the progress text when only one top chrome button is visible.
- Prevent paginated reader progress from moving backward on page turns when vertical text layout reorders text nodes across columns, while still updating and restoring progress inside large text nodes.
- Prevent books from shifting text after opening at positions with Sasayaki matches, without slowing reader restore.
- Align the Advanced Backup entry with iOS by moving it into its own section with a storage icon.
- Prevent Behavior, Statistics, Sasayaki, Audio, and other settings pages from briefly rendering default switch values before saved settings load.

## [v0.5.0] - 2026-05-13

### Added

- Add iOS-compatible reader reading statistics with Advanced settings, reader Statistics sheet, optional session toggle, bottom speed/time display, and per-book `statistics.json` sidecar storage.

### Changed

- Align the reader Statistics sheet and top-left statistics toggle with iOS by removing the sheet header close row and using a timer icon while tracking.
- Stabilize reader bottom sheets so Chapters, Appearance, Statistics, and Sasayaki open as a single 70%-height panel with a top dismiss area, and hand fast internal scrolling to their content instead of jittering between detents.
- Make reader sheets denser and smooth Appearance sheet scrolling by reducing row heights across Chapters, Appearance, Statistics, and Sasayaki, tightening Appearance rows, and preventing Appearance segmented controls from truncating labels or losing selected-state contrast in E-ink mode.
- Keep the reader Chapters sheet cover header while removing the extra large Chapters title and close button, and reduce boundary scroll jank in the chapter list.

### Fixed

- Pause active reader statistics tracking while the reader is backgrounded, persist the flushed values, and resume the active session on return so background time is not counted.

## [v0.4.4] - 2026-05-13

### Added

- Focus the Dictionary tab search field when opening it and hint Japanese input to installed keyboards.
- Add an optional Behavior setting that lets volume keys seek Sasayaki playback when the current book has an audiobook loaded.
- Add an Appearance switch for System theme to use Sepia as the light reader theme, matching iOS.

### Fixed

- Show clear AnkiDroid setup errors when fetching decks and note types fails, distinguish missing AnkiDroid from denied access, and offer a shortcut to app settings after permission denial.
- Keep reader popup appearance changes from rebuilding the open reader WebView, preserving continuous-mode scroll progress updates.

## [v0.4.3] - 2026-05-12

### Added

- Add an iOS-style reader focus mode that hides reader chrome and the Android status bar while keeping the reading layout stable.

### Changed

- Move the reader Sasayaki play/pause shortcut to the top-right chrome and let its visible state reserve top reader space like iOS.
- Turn on Sasayaki, the reader Sasayaki toggle, auto-scroll, and lookup auto-pause by default, and expose the reader Sasayaki toggle from Appearance settings.
- Add Sasayaki reader skip controls that can show bottom rewind/fast-forward buttons and choose whether all Sasayaki skip actions jump by cue or by 5, 10, 15, or 30 seconds.

## [v0.4.2] - 2026-05-12

### Fixed

- Keep the Dictionary tab search cursor visible in dark theme. #54
- Refresh Dictionary tab results and open reader lookup popups immediately when the app or reader theme changes. #55
- Keep the reader text area aligned with iOS when hiding the title or moving progress to the bottom, avoiding unused top space and progress text overlapping the book text.
- Shrink the reader bottom buttons and menu, keeping the reader text area and bottom menu aligned to the compact controls so the bottom progress text is not covered.
- Apply saved reader text layout settings such as Vertical Padding to already-open reader WebViews instead of leaving the text rendered with stale defaults.
- Make Continuous reader padding affect each visible viewport: Horizontal Padding in vertical writing and Vertical Padding in horizontal writing. #52
- Keep the Appearance Layout Mode control wide enough to show the Continuous label without truncation.

## [v0.4.1] - 2026-05-12

### Fixed

- Open dictionary definition web links in Android's external default browser, matching iOS popup behavior.
- Update the paginated reader's top progress counter on every page turn when the next page begins exactly at a text boundary.
- Let pitch dictionaries whose content banks have bad ZIP CRC metadata import successfully when their `index.json` is readable.

## [v0.4.0] - 2026-05-11

### Added

- Add iOS-style bookshelf management with custom shelves, a Reading shelf toggle, shelf previews, toolbar action grouping, single-book and batch moves, batch deletion, and Mark Read.
- Add iOS-compatible Books and Dictionaries backup restore in Advanced -> Backup using `.hoshi` archives.
- Add GitHub release update checks with automatic APK downloads, mirror fallbacks, Settings -> About manual checks, update-ready startup prompts, and Android package-installer handoff.
- Add a GitHub repository link to Settings -> About for starring this app's project.
- Add a Settings -> About storage cleanup tool that scans app-private leftovers by category and asks for confirmation before deleting them.
- Add e-ink-friendly blocking progress overlays for EPUB, dictionary, font, Sasayaki audio, local audio database, and backup file tasks, including current archive names during bulk dictionary imports.
- Let Books import multiple EPUB files in one picker session, with batch progress and automatic return to the bookshelf when finished.

### Changed

- Refresh the Android launcher icon with the iOS Hoshi artwork, circular-mask padding, and no bundled generated PNG assets.

### Fixed

- Show concrete Java crash stack traces in Settings -> Diagnostics after Hoshi restarts from an uncaught exception.
- Prevent reader lookup popups from crashing when the popup is taller than the available screen area.
- Make dictionary imports safer by staging data before committing it and skipping already-installed dictionary archives whose title and type already match an installed dictionary.
- Release persisted external Sasayaki audio permissions when deleting a book.

## [v0.3.4] - 2026-05-10

### Added

- Let selected or shared text from Android Translate and Share actions open directly in Hoshi's lookup popup.
- Add AnkiDroid duplicate checking settings for collection, deck, or deck-root scope and optional checks across all note models. #49

### Fixed

- Keep reader lookup popups responsive after configuring AnkiDroid by checking duplicate status asynchronously.
- Export Sasayaki sentence audio for Anki cards through Media3 Transformer, improving compatibility with `.m4b` audiobooks that Android's legacy extractor cannot parse.
- Export Sasayaki sentence audio for AnkiDroid as playable ADTS AAC, avoiding broken AAC-in-MP4 sentence clips and misleading `.mp3` filenames.

## [v0.3.3] - 2026-05-09

### Added

- Add Hoshi to Android's selected-text context menu so text selected in other apps opens a lookup popup over the current app.

## [v0.3.2] - 2026-05-08

### Added

- Add a Dictionary setting to stop lookup scanning at non-Japanese text, matching the latest iOS selection behavior.

### Changed

- Replace the old auto-collapse dictionary toggle with iOS-style Expand All, Collapse All, and Custom dictionary collapse modes.
- Match iOS Sasayaki subtitle matching by considering cue length in the search window and allowing a wider configurable matching window.

### Fixed

- Speed up paginated reader page turns by caching chapter page bounds after layout, triggering swipe page turns during quick drags or short fast flicks, updating visible progress from memory immediately, debouncing bookmark saves until page turning is idle, flushing pending page-turn saves before closing or backgrounding the reader, skipping no-op selection bridge calls, and avoiding bookshelf refresh invalidations while the reader is open.
- Speed up lookup popup rendering by fetching dictionary entries in small batches instead of one at a time.
- Keep lookup popups scrolled to the top after cross-reference redirects finish rendering.
- Let dictionary section headers expand and collapse without triggering a lookup or dismissing the popup.
- Preserve `em` sizing for structured glossary images after their natural dimensions load.

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
