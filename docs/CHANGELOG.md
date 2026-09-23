# Changelog

All notable user-visible changes to Hoshi Reader Android are documented here.
The format follows a Keep a Changelog style, and release sections use Semantic Versioning.
Historical release notes before v1.3.0 live in [CHANGELOG_ARCHIVE.md](CHANGELOG_ARCHIVE.md).

## [Unreleased]

### Added

- Transcribe Japanese audiobooks on-device in Sasayaki, with a downloadable
  speech model, progress, pause/resume, and matching to book text. Download
  transcription components only when needed to keep the base app small;
  ask once for all missing files and show transcription progress in one place. Continue
  transcribing with the audiobook panel closed, and keep running when switching
  apps instead of actively pausing; exiting the Reader still saves and pauses.
  Reuse completed transcripts or clear them while keeping the current match.
  Default to the Transcription tab when the current match comes from transcription.
  Save or share current matches as SRT from either transcription or imported subtitles,
  including partial results; preserve multiline subtitles when importing.
  Show current match coverage and export together above both matching tabs.
  Support M4A audio and show matched character coverage for both subtitles and transcription.
  Segment clean audiobooks by adaptive audio energy to retain dialogue missed
  by speech detection, including short replies with pauses between syllables.
  Preserve more sentence-opening audio at segment boundaries to reduce missing
  words and sentences, and recover short missing word fragments when matching
  existing transcripts. Use sentence context to match kana/kanji spelling differences
  and recognition errors without losing recognized sentence endings, short replies,
  or adjacent cue edges around a comma. Retain recognized text at EPUB chapter edges,
  short kana/kanji replies, contracted names, and comma-separated numeric expressions;
  preserve recognized prefixes after pauses. Include short sentences and replies
  omitted by recognition in the neighboring highlight that best fits their timing
  and sentence context, including cries, without requiring a new transcription.
  Handle omitted replies beside short cues and missing sentence-edge characters,
  keeping those edge characters with their original sentences.
  Keep recognized words beside omitted cues and inside long missing passages;
  retain recognized words and supported spelling changes even when estimated token
  durations are long, and keep a recognized sentence's ending from being assigned
  to a later omitted sentence. Preserve recognized
  words on both sides of overlapping audio segments, including pause/resume, while
  removing repeated context. Match small-vowel spellings and adjacent reading changes
  without assigning the next word's opening to an omitted reply. Use surrounding
  matched text to recover short misrecognized replies and contracted phrases,
  including rewritten cue edges and percentages emitted as symbols by recognition.
  Match new transcription automatically while reading, preserving playback,
  reading position, dictionary lookups, and image holds as coverage grows.
  Speed up transcription with native audio decoding/resampling running alongside
  speech recognition, while keeping audio buffers bounded. Choose Lightweight, Balanced
  (default), or Fast transcription, with the selection remembered for later sessions;
  recognize audio segments in parallel while preserving ordered progress and resume.
  Show distinct localized errors for unreadable audio, transcription resource
  preparation, recognition, book matching, and progress saving failures.
  Show the original exception and cause chain when an unexpected transcription
  failure cannot be classified.

- Configure lookup frequency sorting per profile: Auto, Ascending, Descending,
  or Disabled, with an enabled frequency dictionary for explicit ordering.
  Both controls share the Lookup settings card with the scan controls.

- Preserve furigana readings in Reader highlights and show them in Contents.
  Select an existing highlight's exact text range to change its color, or choose
  its current color to remove it, in paginated, continuous, and VN modes.

- Add an optional “Hide thumbnails when collapsed” switch in Manage Shelves.
  It defaults off; when enabled, collapsed shelves show only their title row.

- Add global Theme settings in Settings and Reader. Follow system
  brightness with one palette selected in each light/dark group, or choose one
  of the same six options manually. Custom Light and Custom Dark retain separate
  reading colors and explicitly select light/dark interfaces and dictionary
  popups. Customize background, text and secondary text colors with previews.
  Choose system, preset or custom interface accents, and apply E-ink optimization
  across the app. v1.3.3 display settings migrate from the active profile; missing
  or unreadable profile settings fall back to legacy global preferences or defaults.
  E-ink optimization sits below automatic switching and hides palette/accent
  choices while enabled; follow system brightness or choose light/dark
  independently of saved colors.
  Bottom/side tabs and the Dictionary search header have no extra separator in E-ink mode.
  Color changes preserve the visible controls and scroll position while saving.
  The Reader Theme panel's title scrolls with its settings.
- Preserve deleted books' reading statistics in an archive, restore them on
  reimport, and edit daily records from book distribution rows. Single-day
  deletion is available inside the day editor; deleting all records requires
  confirmation. Daily records use compact grouped rows with dates and character
  counts on the left, durations and navigation arrows on the right. Archived
  books have a small trash marker in the book list. Local and archived book
  headers use the dashboard title style, avoiding a font-size jump while loading.
  E-ink mode outlines the daily-record group and its delete-all button.
- Add natural calendar periods and all-time Statistics overview, with compact
  period controls in the Reading Time card and the current week selected by default.
  Tap chart bars to inspect a day within a week/month or a month within a
  year/all-time period. Include elapsed-period averages and comparisons,
  plus historical goal streaks and best-day summaries.
- Support Anki tag handlebars such as `{document-title}` and `{expression}`,
  joining whitespace inside substituted values with underscores; new card
  formats default to the `hoshi` tag while saved tags stay unchanged.
- Long-press a lookup result's audio button to choose among named local and
  remote audio candidates; the selected recording is also used for Anki cards.
- Show source text above Dictionary and externally shared lookup results; tap a
  character to look up from that position while keeping the full sentence for
  Anki cards. Back/Forward restores the selected occurrence for mining, and
  source text size is adjustable from 12 to 48.
- Add Off, Dimmed, Toggle, and Hidden furigana modes in Appearance;
  Toggle reveals whitespace-adjacent ruby annotations together on the first tap.

- Add Show, Blur, and Hide privacy modes for bookshelf covers, plus deterministic
  title and author artwork when a book has no visible cover.
- Warn users before enabling AnkiConnect that most setups can create cards
  directly through AnkiDroid and that an incorrect AnkiConnect configuration
  prevents fetching decks and note types or creating cards.
- Add term dictionary categories with category-aware Anki definition mappings
  and iOS-aligned advanced category/fallback controls, Kanji dictionary
  import/management and popup lookup, a verified one-tap
  stroke-order font download for Kanji users, plus complete pitch data with H/L
  patterns and nasal/devoice markers.
- Add downloadable recommended Japanese font families to Appearance,
  including separate family and named variant selectors, real static and
  variable weight selection, verified app-private downloads, and family/variant
  grouping for imported TTF and OTF fonts, with compact one-level type grouping
  in the font menu.
- Add an optional Reader Behavior setting that uses the volume keys to jump
  between terms in the topmost lookup popup.
- Add `hoshi://search?text=...` deep links for opening lookup results in the
  existing popup overlay, with `mode=app` support for opening the Dictionary
  tab instead.

### Changed

- Translate the Anki selected-glossary fallback label in Simplified Chinese.
- Use the Sasayaki accent colors to highlight the current chapter in the
  Reader Contents list outside E-ink mode.

- Pulling down in Dictionary or tapping the search field's clear button now
  clears only the search field, focuses it, and opens the keyboard, preserving
  the current lookup results and navigation history.

- Use JapanesePod101, LanguagePod101, and Jisho as the default word-audio
  sources, matching Yomitan's Japanese defaults. Each source can be enabled
  and reordered independently; existing default sources migrate in place and
  retain their enabled state, while custom sources stay unchanged. Default
  playback and mining use the first matching source immediately, without
  waiting for later remote sources when local audio is available. The recording
  menu opens immediately, loads sources concurrently, and lets you select
  available recordings while other sources are still loading. Scrolling inside
  the menu has no overscroll stretch and never scrolls the definitions
  underneath, including at either end
  and when the menu has too few recordings to scroll. Long menus fit above
  or below the playback button without covering it, scrolling internally
  when the popup is short.

- Remove the Anki Advanced “Embed media” switch; dictionary images now always
  export to Anki as media files, including when the old setting was disabled.

- Let Reader use the WebView default line-box sizing, matching current iOS
  reading styles.

- Automatic dictionary updates always use low-memory import; manual imports
  and updates continue to follow the Low Memory Usage Mode setting.

- Book search now matches spaces and punctuation literally, shows complete
  sentence context, and returns up to 100 results. Jumping to a result temporarily
  marks the match in blue until page navigation, in all three reading modes.

- Include surrounding Japanese quotation marks and punctuation in Sasayaki
  sentence highlights across all Reader modes, with consistent ownership
  between adjacent cues and across VN screens.

- Apply the selected Recent/Title sort to Google Drive books. Recent uses the
  latest reading or audiobook progress time, falling back to bookdata last access.

- Separate global display settings from profile Appearance settings, which now show
  the edited profile name. Opening books or switching profiles keeps the same
  colors and E-ink setting.
- Unify page, grouped card, nested control and popup colors across tabs, settings
  and native Reader panels. Ordinary groups use tonal backgrounds, while E-ink
  uses visible outlines and selection shapes, including continuous lazy lists.
  Bottom and side navigation use a subtle theme tint, and Dictionary search
  shares the page background through the status bar for a continuous top area.
  Native backgrounds use a softer, more neutral tint and lighter separators,
  while buttons and active states retain the selected accent color.
  Theme uses the same inset row dividers as other settings pages.
  Appearance uses inset rounded selections inside continuous neutral
  segment tracks, keeping the current choice clear without vertical separators.
- Keep Statistics always available and move its settings to the Stats tab's
  upper-right corner. Statistics sync defaults on only when unset; saved daily goals,
  sync opt-outs and Reader display preferences are preserved. Book Open and
  Page Turn autostart remain independent and default off. Group settings into
  autostart, reset time, Sync and Archive sections, with explanatory footers
  and the archived-book count below the clear action.
- Simplify Statistics to daily goals and reading-time results. Include a
  display-only reading-intensity heatmap with fully visible, row-aligned weekday
  labels in the daily card and a compact
  goal popup with a scrollable value picker. Show reading-time
  bars with calendar-aligned dashed grid lines, summary rows labeled Characters
  Read and Reading Speed, and time-ranked books with per-book character counts
  and reading times together instead of three tabs,
  with consistent section headings, grouped cards and compact charts and rows.
  Identify daily averages in Week/Month and monthly averages in Year/All.
  Show complete period comparisons with larger text and direction arrows
  alongside rounded percentages; keep Show More left-aligned with
  a divider above it. In E-ink mode, chart bars are all filled until a single
  bar is selected; then only that bar stays filled and the others become hollow.
  Clearing selection restores all filled bars. Period and goal-type selectors use
  outlined tracks with filled, inverse-text selections, and the goal value wheel
  outlines its center selection. Remove the separate This Week
  card and weekly goal; weekly totals and trends remain available through the
  reading-time chart. Keep dashboard cards in memory while scrolling to avoid
  rebuilding the heatmap and charts during fast vertical swipes.

### Fixed

- Keep the current Dictionary result and back/forward history when switching tabs,
  including after following a definition link and swiping back. Return to the
  retained page without briefly blanking the results.

- Recognize existing AnkiDroid cards for canonically equivalent Unicode spellings,
  including compatibility kanji such as `難` and decomposed kana, so mined words
  correctly show the existing-card icon and cannot bypass duplicate checking.

- Open matching AnkiDroid cards even when its browser previously selected a
  different deck, while preserving the configured duplicate-search scope.

- Show each failed dictionary import with its filename and reason, while
  continuing other files in the batch.

- Show a localized Reader fallback with a Close action when a book is missing
  or cannot be parsed, instead of exposing internal loading errors.

- Recover the reader after Android terminates its WebView renderer, preserving
  the latest reading position, highlights, and Sasayaki cue display.

- Use 10-second Google Drive connection/read timeouts and suppress transient
  network errors during automatic bookshelf refresh. Manual operations still
  report failures, and cached books remain visible.

- Count Korean text consistently in Reader progress, book search, and new
  Sasayaki subtitle matches, and exclude ruby fallback text from native counts
  and search. Existing books refresh their cached counts when reopened.
- Ask for confirmation before resetting Dictionary custom CSS to prevent
  accidental clearing.
- Keep text and images reachable in every Reader mode when publisher CSS wraps
  paragraphs or empty layout struts in oversized inline blocks.
- Restore book files and covers from iOS Books backups when equivalent Unicode
  paths use different composed forms.
- Match Sasayaki subtitles immediately after selecting an SRT, including unique
  cues immediately before the stable starting sequence, combined-volume EPUBs,
  and large text gaps, without requiring Search Window tuning or a separate
  Match action.
- Import EPUB and TTU bookdata with multibyte titles that exceed Android's
  filename byte limit while preserving the complete visible title and cleaning
  temporary EPUB data after failed imports.
- Keep wide inline gaiji at the publisher's text-relative size, recognize any
  publisher class containing `gaiji`, and render gaiji plus transparent
  monochrome images embedded in text with the active Reader text color while
  blending away their image backgrounds in standard and custom themes and
  excluding gaiji from image navigation.
- Use EPUB fallback text for failed inline gaiji images, while retaining a
  broken-image marker and its inline space when no fallback text is available.
- Open EPUB pages that use paired XHTML viewport metadata instead of remaining
  on the Reader loading screen.
- Keep oversized lookup popup frames fully inside the visible screen so their
  bottom border and all scrollable content remain reachable.
- Keep the Dictionary type selector's `Frequency` label on one line on compact
  screens.
- Prefer Arial throughout lookup popups before Android's Japanese font fallback
  so pitch-accent markers stay aligned with their reading, and keep pitch
  dictionary labels intact when compact entries wrap.

## [v1.3.3] - 2026-08-13

### Added

- Automatically center the current chapter when opening the Reader Contents or
  Sasayaki chapter list.
- Add per-source enable controls for imported local audio databases while
  preserving each source's configured priority.
- Add Ogg Opus audiobook import with embedded title, artist, cover, and chapter
  metadata in Sasayaki, load its artist without the platform-reader delay, and
  show MP3, M4B, and Opus audiobook duration before playback starts.
- Add a Reader Appearance swipe-threshold control for paginated and VN modes;
  setting it to zero disables swipe page turns while preserving hardware page
  keys.
- Add up to three named Anki card formats with independent icons, decks, note
  types, field mappings, tags, and duplicate states.
- Add confirmation before deleting Anki formats and an edit-screen action that
  duplicates a format and returns to the format list.
- Add Anki duplicate-note search buttons that appear only for matching notes,
  plus grouped per-format mining and search actions placed before audio.
- Add precise cloze-part handlebars, numeric pitch accent graph handlebars, and
  advanced glossary mapping options for Anki cards.

### Changed

- Increase the default lookup popup size to 500 × 500 dp and allow its
  height to be adjusted up to 1000 dp.
- Align the built-in Lapis, Kiku, and Senren field presets with iOS by no longer
  setting sentence-card marker fields.

### Fixed

- Allow importing supported files whose display names contain `#` or `?`,
  including EPUB volume numbers such as `Book #01.epub`.
- Base reduced-motion popup scrolling on the portion of the popup that is
  actually visible when its configured height exceeds the screen.
- Show Reader Contents, fragment jumps, and chapter labels for EPUBs whose
  navigation document is stored in a subdirectory.
- Prevent staggered multi-touch taps in paginated and VN Reader modes from
  being mistaken for a page-turn swipe.
- Keep Google Drive sync and TTU backup restore compatible with progress files
  whose book data IDs exceed Android's 32-bit integer range.
- Keep dictionary lookup available when tapping Sasayaki-highlighted text in VN
  Reader mode.

## [v1.3.2] - 2026-08-04

### Added

- Add the option to create a new shelf while moving one or more selected books.
- Add a Reader image gallery, true table-of-contents chapter ranges, and optional
  current-chapter progress in Reader chrome and statistics.
- Add a configurable daily statistics reset time and pause reading statistics
  while Reader sheets or fullscreen images are open.
- Add optional current-book cover publishing for the Android lock screen and a
  fixed PNG file used by compatible E-ink sleep-screen tools, plus direct
  integration with iReader’s built-in Book Cover screen saver on compatible
  domestic and Musnap overseas firmware using standard PNG output, with Fit,
  Fill, and Stretch scaling modes.

### Changed

- Expand Sasayaki delay adjustment to -4...4 seconds and playback speed to
  0.5...3x.
- Rename the Reader Go to panel to Contents, order its tabs as Chapters,
  Highlights, Gallery, and Search, and remove overscroll deformation from
  scrolling surfaces throughout the app.

### Fixed

- Keep Reader progress, search, and Sasayaki character offsets stable around
  numeric HTML entities, and keep lookup sentence expansion and recursive
  expression-tag scanning within the selected text boundary.
- Keep manual bookshelf sync from rebuilding the entire shelf, while refreshing
  imported reading progress in place.
- Keep large bookshelves smooth during repeated scrolling by reusing
  size-appropriate persistent cover thumbnails instead of decoding original
  covers again after they leave memory, while recovering from transient
  generation failures or damaged thumbnail-cache entries without hiding valid
  covers.
- Remember the selected Contents and Sasayaki tabs for the current Reader
  session, and keep Sasayaki on the current tab after importing an audiobook.
- Keep VN lookups and mined Anki sentences complete when a word or sentence
  continues onto a later screen.
- Keep Anki audio, book covers, Sasayaki clips, and dictionary media from
  overwriting different exported media by using content-specific filenames.
- Keep dictionary definitions in the configured dictionary order when an
  inflected lookup merges multiple deinflection candidates.
- Keep Sasayaki jumps to cues in the previous chapter from counting the target
  chapter in the current reading session when image holding is enabled.

## [v1.3.1] - 2026-07-11

### Added

- Add a Reader Appearance setting for top safe area height.

### Changed

- Improve dictionary lookup and import behavior by honoring Yomitan term scores
  and normalizing Japanese iteration marks, full-width numbers, and emphatic
  sequences.
- Raise Statistics daily goal limits to 200,000 characters and 12 hours.

### Fixed

- Keep the Statistics tab visible after enabling it and switching away from Settings.
- Refresh Statistics by-book covers when changing calendar ranges.
- Keep Reader lookup highlights from expanding to an entire ruby annotation when
  selecting a shorter word inside it.
- Keep VN vertical text from jumping to a new column immediately after a ruby
  annotation.
- Keep long-pressed Reader volume keys paging or seeking Sasayaki instead of
  falling back to system volume changes after the first press.

## [v1.3.0] - 2026-07-01

### Added

- Add a full-library Statistics tab with habit summaries, calendar range browsing, per-book distribution, daily and weekly goals, and an Advanced Statistics visibility switch.

### Changed

- Open the Reader Go to panel on Chapters by default, order its tabs as Chapters, Highlights, and Search, and focus the search field when Search is selected.

### Fixed

- Prefer exact expression-and-reading local audio matches before falling back to reading-only or expression-only entries.
- Read Sasayaki M4B title, author, and cover metadata from MP4 atoms when Android's platform metadata reader returns empty.
- Improve VN reader media screens, first-highlight display, vertical layout, punctuation wrapping, and lookup and Sasayaki highlight alignment.
- Keep VN and continuous vertical reader content aligned to the configured vertical padding instead of the bottom overlap area.
- Prevent reader lookups from crashing on words that begin with supplementary-plane kanji such as 𠮟.
- Keep Sasayaki image hold active while viewing fullscreen Reader images, and avoid repeated holds once the continuous Reader target image is already visible.
