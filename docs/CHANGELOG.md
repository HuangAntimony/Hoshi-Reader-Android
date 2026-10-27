# Changelog

What's new and fixed in Hoshi Reader Android.
Release notes before v1.3.0 are in [CHANGELOG_ARCHIVE.md](CHANGELOG_ARCHIVE.md).

## [Unreleased]

### Added

- Optionally show a sentence replay button beside the reader’s bottom playback controls to restart the current Sasayaki or TTS sentence and continue playback.
- Optionally exit the reader with a single Back action without first revealing its controls, using a new Behavior setting that is off by default.
- Revisit the last 100 full-text queries from the Dictionary tab and external lookups, with tap-to-search, individual deletion and clearing, without recording in-book lookups or subsequent character and word selections.

- Sync books, reading progress, highlights, shelves, statistics and audiobook playback with Hoshi on iOS through Google Drive, including devices without Google Play Services, with less startup delay for large bookshelves and without replaying part of the current audiobook cue, losing newly created highlights when local state is synced, leaving deleted books as blank cloud entries or interrupting the bookshelf and progress display during batch imports; upload transcription matches when transcription finishes or stops, while keeping live subtitle updates without repeatedly uploading temporary versions.
- Download cloud books on demand, choose local-only or cloud-wide deletion, resume interrupted uploads across app restarts, keep healthy uploads running while metadata requests recover from network timeouts, stop transfers when Google Drive requires reconnection, and track per-book progress, separate effective upload/download speeds and errors on a dedicated queue page that preserves your position, avoids a dim backdrop on E-ink screens, and shows checking status until transfers are known; upload chunks adapt to the connection, and transfer concurrency is configurable in Sync settings (default 8).
- Read Japanese books aloud with device, online or self-hosted TTS voices (with Edge TTS access restored after Microsoft's service update), background playback, adjustable speed, separate narration/dialogue voices, independently replayable Japanese dialogue passages and smoother continuous speech across sentences and chapters with parallel online prefetch and recovery from temporary synthesis failures; skip empty audio from successful HTTP synthesis responses without interrupting reading or blocking preview and Anki audio export; skip image holds by turning pages manually without interrupting speech or immediately returning to the narration page, and disable image holds with auto-scroll in the background too.
- Customize TTS pronunciation with book-specific or global replacements, optional regular expressions and ruby readings, without changing book text.
- Export audiobook or TTS sentence audio to AnkiDroid and AnkiConnect with the optional `{sentence-audio}` field.
- Optionally disable a term's Anki button after successful mining until the popup is reopened.
- View, copy or export recent runtime errors alongside crash history and build information in Diagnostics.
- Install the latest development preview from About.
- Add LINE Seed JP to the recommended reader fonts.

### Changed

- Skip Sasayaki and TTS image holds for images referenced three or more times in a book’s main text, while retaining holds for images used once or twice.

- Make Statistics charts easier to read with a one-hour minimum scale and compact previous/next period buttons alongside swipe navigation, with stable single-line period titles and duration/comparison rows when paging.
- Label the selected-text menu entry “Hoshi Lookup” (“Hoshi 查词” in Simplified Chinese) to make dictionary lookup easier to identify.
- Simplify About into compact groups for updates, project links, support and storage cleanup.
- Edit individual reading sessions in Statistics, with deletions preserved across sync.
- Replace BIZ UDPGothic with BIZ UDGothic in the recommended fonts.

### Fixed

- Reduce chapter loading delays by processing large publisher stylesheets faster, avoiding initial layout work before reader styles are ready, speeding up pagination in chapters with extensive ruby annotations, and avoiding repeated chapter scans in visual novel mode.
- Restore missing illustration chapters and gallery images in EPUBs with XHTML fallbacks, recover bitmap covers from simple SVG wrappers or alternative declared covers when the preferred cover is broken on import or opening existing books, and keep image-only pages visible and properly centered in the reader.
- Keep word lookups and sentence context within the tapped dictionary list item instead of joining neighboring definitions.
- Keep the full query visible and tappable in the Dictionary tab even when its beginning has no dictionary match.
- Start reading statistics reliably when Sasayaki turns pages automatically, including after statistics finish loading or autostart settings change, without treating subpixel adjustments on the same page as page turns.
- Restore continuous reading without clipped text, furigana or position drift, and keep full-width lookup popups independent of reader padding.
- Keep the selection menu and handles usable after dragging across paragraphs or ruby, without unexpected page turns or selection flicker.
- Create, recolor and remove highlights when selections touch ruby annotations.
- Show saved settings immediately when their controls appear, avoiding a flash of defaults.
- Keep names containing middle dots intact during lookup and prevent old Dictionary audio from replaying when opening a book.
- Preserve lookup access to dictionaries excluded from aggregate Anki fields, and handle selected-definition fallbacks correctly.
- Keep audio-source menus inside scaled lookup popups.
- Show useful error details when operations fail, and improve update checks when download mirrors return invalid responses.
- Improve Japanese audiobook transcription with Nakuru on 64-bit and 32-bit ARM, resumable component downloads and live cue updates; remove retired model files while preserving book data.

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
