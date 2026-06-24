# Changelog

All notable user-visible changes to Hoshi Reader Android are documented here.
The format follows a Keep a Changelog style, and release sections use Semantic Versioning.
Historical release notes before v1.1.0 live in [CHANGELOG_ARCHIVE.md](CHANGELOG_ARCHIVE.md).

## [Unreleased]

### Added

- Add a tabbed Sasayaki reader menu with a player-style audiobook header, draggable playback progress, audiobook resources, reader-menu subtitle matching that replaces the old bookshelf match entry, current subtitle match rate, M4B chapter viewing, and chapter jumps.
- Allow Sasayaki audiobooks to be loaded and played without subtitle match data.

### Fixed

- Expose Go to and Sasayaki reader-menu tabs with proper selected-tab semantics for accessibility services.
- Keep Sasayaki playback owned by a service-backed MediaSession so background playback uses Android's media foreground-service path when permitted, system notification controls stay visible and routed through the media session, Reader restore does not leave a paused media notification, skip/seek controls work before first playback, reader exit stops playback without leaving a stale notification, and notification or launcher return opens the active reader.
- Keep the Sasayaki notification's rewind and fast-forward controls visually consistent and working from system media controls.
- Pause Sasayaki auto-scroll during natural playback on skipped image pages for one second, including multiple image pages, image-only chapters, vertical continuous reading, and fullscreen image interruptions, while cue or progress seeks jump directly without leaving playback paused.
- Keep Sasayaki cross-chapter cue jumps from briefly showing the chapter start, counting the hidden restore landing in reading statistics, or applying cue jumps before slow chapter restores finish.
- Keep Sasayaki cue highlights from dropping punctuation split across EPUB text nodes.

## [v1.2.2] - 2026-06-21

### Added

- Added reader in-book search with chapter-grouped, punctuation-preserving results, progress percentages, and a unified Go to panel for search, chapters, highlights, and character jumps.
- Add optional AnkiConnect API key support for servers that require authenticated requests.

### Changed

- Support installing Hoshi Reader Android on Android 8.0 and 8.1 devices.
- Allow popup scale to be increased up to 2.00.

### Fixed

- Update VN reveal speed in Reader Appearance without reloading the current chapter.
- Keep VN vertical text screens from splitting closing Japanese quotation marks onto their own page.
- Speed up VN Reader opening for long chapters with Sasayaki cue merging.
- Prevent repeated Reader re-entry from accumulating WebViews and causing long Sasayaki blank screens.
- Prevent Reader from briefly rendering with the global profile appearance before the book's automatic profile is applied.
- Keep continuous Reader scrolling from queuing expensive progress calculations that delay progress updates and lookup taps.
- Keep popup nested lookup taps working when popup scale and custom zoom CSS are both enabled.

## [v1.2.1] - 2026-06-18

### Added

- Add a Behavior setting to open the last read book on app launch.
- Add a VN reading mode with block and sentence screens, dedicated appearance controls, optional tap-to-advance, and Sasayaki cue merging and highlighting.
- Add a Reader Appearance Publisher font option that keeps EPUB-provided font families instead of forcing a reader font override.

### Fixed

- Speed up opening Reader chapters with large Sasayaki subtitle matches.
- Keep vertical reader lookup highlights from bleeding into adjacent ruby-aware columns.
- Restore Japanese deinflected lookup filtering for entries that lack matching part-of-speech metadata.

## [v1.2.0] - 2026-06-15

### Added

- Add Japanese and English dictionary profiles with active-profile and per-language default selectors.
- Add per-book dictionary profile overrides with automatic profile previews.
- Add automatic reading profile selection from EPUB language metadata.
- Scope dictionary, behavior, custom CSS, Anki, and Reader Appearance settings to profiles.
- Keep profile-aware dictionary backups compatible with single-profile restores.
- Show English phonetic transcriptions in lookup popups.
- Expose English phonetic transcriptions to Anki templates via `{phonetic-transcriptions}`.
- Add recommended English dictionary downloads.
- Add phrase-aware English reader lookups with word-start scanning, punctuation trimming, and scan-length limits.
- Show English profile progress, jump targets, chapter counts, and reading statistics as approximate words.
- Show all available deinflection trace paths in lookup popups.
- Add a screen-orientation lock setting.
- Add Sasayaki light/dark highlight color controls.

### Fixed

- Sort AnkiDroid and AnkiConnect deck pickers by deck name.
- Detect AnkiDroid duplicates across nested deck roots and sibling decks.
- Keep reader lookup popups and page turns responsive after landscape rotation.

## [v1.1.5] - 2026-06-11

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
