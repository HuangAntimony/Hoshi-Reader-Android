# iOS Upstream Sync Queue

This document tracks open Android work after checking iOS upstream `develop`.

- Source: `reference/Hoshi-Reader-iOS`
- Baseline for this refresh: `24e356f00cfc3b74675d5610d2ffeeb52516301c`
- Latest checked: `origin/develop` at `c31c9d0ce376ff83bf6a91d908bf9f8e0fb4947b`
- Checked on: 2026-08-01
- Upstream history note: `develop` was force-updated. The previous tip
  `24e356f00cfc3b74675d5610d2ffeeb52516301c` is no longer an ancestor of the
  current tip; its project-only commit has no Android action. All commits newly
  reachable from the current `develop` tip were audited against Android code.

## Current Queue

### 1. Dictionary categories, Kanji dictionaries, and complete pitch data

Status: pending Android sync.

Commits:

- `9eff7dd` - add an excluded dictionary category.
- `67fc9e8` - add basic Kanji dictionary import, lookup, and popup rendering.
- `3cd8294` - support string pitch patterns plus nasal and devoice indicators.

Dependency/value reasoning:

- This is the native bridge and stored-dictionary foundation for later Anki
  template work. Land the bridge/data model before UI and template consumers.

iOS behavior to mirror:

- Term dictionaries can be categorized as monolingual, bilingual, excluded, or
  uncategorized. Excluded dictionaries stay installed but do not participate in
  term lookup.
- Yomitan Kanji dictionaries are imported, configured, queried for a selected
  character, and rendered as Kanji readings and meanings in the popup.
- Pitch entries accept numeric downsteps or explicit high/low strings and render
  nasal and devoiced mora indicators without losing dictionary attribution.

Android current gap:

- `DictionaryType` and `DictionaryConfig` in
  `app/src/main/java/moe/antimony/hoshi/dictionary/DictionaryModels.kt` expose
  only Term, Frequency, and Pitch and have no dictionary category or Kanji list.
- `DictionaryNativeBridge` and `DictionaryLookupQueryService` in
  `app/src/main/java/moe/antimony/hoshi/dictionary/` pass only term/frequency/
  pitch paths and expose no Kanji query API. The current query service already
  serializes rebuilds and atomically swaps sessions, so `47b0bba` is covered;
  the remaining gap is bridge capability and its consumers.
- `app/src/main/java/de/manhhao/hoshi/HoshiDicts.kt` and the tracked
  `third_party/hoshidicts-kotlin-bridge` model pitch positions as `IntArray` and
  expose neither nasal/devoice metadata nor Kanji results.
- `LookupPopupHtml.kt` and `app/src/main/assets/hoshi-web/popup/popup.js` consume
  only `pitchPositions`; `DictionaryView.kt` has no category or Kanji sections.

Suggested slice:

- Update the tracked Kotlin/JNI bridge and ABI tests first, then extend import
  summaries, storage/config/profile backup compatibility, and query sessions.
- Add category and Kanji management UI through the existing dictionary
  repository/ViewModel boundary.
- Extend popup JSON and JS/CSS for Kanji entries and the final pitch schema.

Validation:

- Import term, frequency, pitch, and Kanji Yomitan archives; toggle, reorder,
  delete, back up, and restore each type.
- Confirm excluded dictionaries disappear from lookup without being deleted and
  category-aware config survives profile switching and iOS-compatible backup.
- Validate numeric/string pitch patterns, nasal/devoice markers, duplicate pitch
  suppression, Kanji lookup, dark/e-ink themes, and dictionary media.

### 2. Multiple Anki card formats and advanced handlebars

Status: partially synced on Android. Multi-format storage/UI/popup routing,
show-notes, cloze parts, selected-glossary fallback, and numeric pitch graphs
are complete. Category-aware monolingual/bilingual handlebars and the final
string/nasal/devoice pitch schema still depend on queue item 1.

Commits:

- `119fb5b` - advanced Anki settings and monolingual/bilingual definitions.
- `bd85c9b` - multiple named card formats.
- `c943171`, `395218a` - show-notes action and its Anki-owned setting.
- `8464a2c` - cloze prefix/body/suffix handlebars and precise sentence cloze.
- `f1bc74b` - pitch-accent graph handlebars.
- `2c86ed6` - brief/no-dictionary monolingual and bilingual variants.
- `47683d9` - guard against deleted card formats.
- `2702e31` - disable mining when the first field is not configured and handle a
  disconnected AnkiConnect backend.

Dependency/value reasoning:

- Mining configuration and output are a single user workflow. Implementing the
  storage model, popup format selection, and renderer together avoids formats
  that can be selected but cannot produce valid notes.

iOS behavior to mirror:

- Users can keep up to three named card formats with six icon choices, each with its own deck, note
  type, field mappings, and tags; legacy single-format config migrates to a
  default format.
- Popup mining exposes every valid format, tolerates deleted formats, disables
  mining if a format's first note field is unmapped, and can open existing notes
  unless the action is disabled.
- Advanced mappings include configurable selected-glossary fallback,
  monolingual/bilingual definitions and their variants, cloze parts, and pitch
  graph handlebars.

Android completed slice:

- `AnkiSettings` schema version 2 migrates legacy profile JSON into a stable-ID
  default format and stores one to three independently editable formats.
- Typed Navigation3 format/Advanced destinations, localized settings, guarded
  add/delete behavior, and confirmed bulk deck/model reset match current iOS.
- Reader, Dictionary, and Process Text popup bridges use stable format IDs for
  mining, per-format duplicate state, and show-notes. Missing/deleted formats
  and unavailable backends fail closed.
- AnkiConnect uses `guiBrowse`; AnkiDroid uses
  `anki://x-callback-url/browser?search=...` with first-field/model/scope search.
- The renderer supports precise UTF-16 cloze parts, selected-glossary fallback,
  numeric pitch SVGs, first-pitch extraction, and non-category advanced
  glossary variants. Legacy selected-glossary fallback aliases remain hidden.

Remaining gap:

- Add monolingual/bilingual definition handlebars only after dictionary
  categories are available from queue item 1.
- Feed final string pitch patterns plus nasal/devoice data into exported pitch
  graphs after the bridge and popup schema in queue item 1 land.

Validation:

- Migrate existing single-format settings, add/edit/delete formats, and
  restart the app with all formats intact.
- Mine through AnkiConnect and AnkiDroid using different formats, unmapped first
  fields, deleted formats, disconnected backends, duplicates, media, cloze
  offsets, category fallbacks, and pitch graphs.

### 3. Reader furigana reveal mode

Status: pending Android sync.

Commits:

- `15d4a6e` - add Off, Toggle, and Hidden furigana modes.
- `23e0764` - migrate the legacy hide-furigana preference.

Dependency/value reasoning:

- This is a self-contained reader setting, but it touches shared selection and
  all reader modes, so its state and tap semantics should land together.

iOS behavior to mirror:

- Off shows furigana normally. Hidden removes it. Toggle initially hides ruby
  annotations with a dotted base-text indicator and reveals one ruby annotation
  when tapped without opening lookup for that tap.
- Existing hide-furigana users migrate to the equivalent final mode.

Android current gap:

- `ReaderSettings` stores only `hideFurigana: Boolean`; `ReaderAppearanceView.kt`
  exposes a switch rather than a three-state mode.
- `ReaderContentStyles.kt` removes/hides ruby globally and shared
  `selection.js` has no `ruby.furigana-hidden` reveal tap result. Paginated,
  continuous, and VN therefore cannot reveal individual annotations.

Suggested slice:

- Replace the boolean with a compatible enum migration, add the segmented
  setting, and implement the same reveal marker and tap interception through the
  shared reader selection/text semantics used by all modes.

Validation:

- Verify Off/Toggle/Hidden in paginated, continuous, and VN modes, horizontal
  and vertical writing, with lookup, highlights, Sasayaki, restore, and ruby
  split across styled nodes.

### 4. Reader paginated paragraph splitting and explicit font readiness

Status: pending Android sync.

Commits:

- `eb86431` - split paragraphs that span pages so native selection remains
  visible.
- `ff86caa` - explicitly load the selected reader font before layout/restore.
- `c31c9d0` - handle ruby, empty elements, and boundary edge cases in paragraph
  splitting.

Dependency/value reasoning:

- Both changes stabilize the geometry used by pagination, native selection,
  progress, and restore. Font readiness must be established before fragment
  boundaries are calculated.

iOS behavior to mirror:

- Paginated paragraphs spanning multiple columns are split into layout-neutral
  fragments at measured page boundaries, preserving ruby and empty/replaced
  elements and keeping justified fragments visually correct.
- The selected custom font is explicitly requested and awaited before splitting,
  node-offset construction, initial restore, or fragment jumps.

Android current gap:

- `app/src/main/assets/hoshi-web/reader/reader-paginated.js` has native-selection
  scroll locking but no `splitPoints`/paragraph fragmentation equivalent, so a
  selection spanning a CSS-column boundary can still be created outside the
  visible column.
- Paginated and continuous scripts await `document.fonts.ready`, but do not call
  `document.fonts.load()` for the computed body family. Imported fonts are
  injected by `ReaderContentStyles.kt`, so passive readiness may not force the
  selected face to load before restore measurements.

Suggested slice:

- Add a shared font-await helper and a paginated-only fragmenter after a focused
  Android WebView geometry design. Keep offsets compatible with
  `reader-dom-text.js`, highlights, and Sasayaki rather than copying Swift glue.

Validation:

- Long-press native selection across page boundaries in horizontal/vertical
  writing with ruby, inline elements, empty spans, images, justified text, and
  custom fonts.
- Verify page count, progress, lookup offsets, highlights, font switching,
  chapter jumps, and bookmark restore before and after fragmentation.

### 5. Sasayaki import, media controls, and MP3 mining clips

Status: pending Android sync.

Commits:

- `947898c` - export mined Sasayaki sentence audio as MP3.
- `4a5cfde` - honor the command-center skip-control preference.
- `a9a0747` - accept MP4 audiobooks and TXT subtitle files.

Dependency/value reasoning:

- These changes share import validation, Media3 playback, notification/session
  commands, and Anki media export.

iOS behavior to mirror:

- Sasayaki accepts `.srt` or `.txt` subtitle files and `.mp3`, `.m4b`, or `.mp4`
  audiobooks.
- When skip controls are enabled, external previous/next commands seek by the
  configured skip interval; otherwise they move by cue.
- Mined sentence clips are broadly compatible MP3 files with MP3 filenames.

Android current gap:

- `ImportFileType.SasayakiSubtitle` accepts only `srt`, while
  `SasayakiAudiobook` and `SasayakiAudioRepository` accept only `mp3`/`m4b`.
- `SasayakiPlaybackServiceRuntime.previousFromSession()`/`nextFromSession()`
  always move by cue; `readerSkipButtonAction` affects Reader buttons but not the
  MediaSession command path.
- `SasayakiCueAudioExporter` transcodes to AAC/ADTS and names output `.aac`, not
  MP3. Current content-hashed Anki filenames are otherwise already correct.

Suggested slice:

- Expand SAF type validation and storage extension handling, then update
  MediaSession command routing.
- Choose an Android-supported MP3 encoding path only after checking current
  Media3/Android media guidance; preserve failure handling and background
  playback ownership in the existing MediaSessionService.

Validation:

- Import and play every accepted subtitle/audio extension through SAF; reject
  unsupported content without losing the previous source.
- Exercise cue/seconds skip modes from Reader, headset, notification, and system
  controls.
- Mine clips through AnkiDroid and AnkiConnect and verify valid MP3 playback,
  MIME type, hashed filenames, and sentence range expansion.

### 6. Bookshelf cover privacy and fallback artwork

Status: pending Android sync.

Commits:

- `c6b29c8` - show title/author fallback artwork when no cover exists.
- `1db2cd3` - add Show, Blur, and Hide cover modes.

Dependency/value reasoning:

- The fallback and privacy modes affect every local/remote/shelf preview cover
  and should reuse the existing shared Coil/thumbnail-store boundary.

iOS behavior to mirror:

- A deterministic gradient card with title and optional author replaces the gray
  placeholder when a book has no real cover.
- Bookshelf and shelf-management surfaces can show, blur, or hide covers.

Android current gap:

- `BookshelfSettings` stores only sort and Reading Shelf visibility; there is no
  cover mode.
- `BookCoverCard()` in `BookshelfView.kt` renders a gray box for missing covers
  and has no title/author input. `BookMetadata` does not store author, and
  `EpubBook` does not expose parsed author metadata.

Suggested slice:

- Add optional author metadata compatibly during import/parse, then add a
  deterministic Compose fallback and cover-mode setting applied to all cover
  consumers.

Validation:

- Missing-cover books with/without authors, legacy metadata, local/remote books,
  collapsed shelf previews, multi-select, Show/Blur/Hide, dark/e-ink themes.

### 7. Lookup popup two-column layout and visual sizing

Status: pending Android sync.

Commits:

- `ed25036` - masonry layout and popup visual redesign.
- `8d1442e` - add Yomitan danger/success theme variables.

Dependency/value reasoning:

- This is a shared popup asset/settings slice used by Reader, Dictionary tab,
  and Process Text. Land persistence and bootstrap values before JS/CSS layout.

iOS behavior to mirror:

- Dictionary settings add a Two-Column Layout toggle. Multi-dictionary glossary
  cards use masonry/two-column layout when enabled and keep one column otherwise.
- Popup cards, padding, theme accents, and definition image canvas sizing match
  the refreshed design; popup height can reach 800.

Android current gap:

- `DictionarySettings`/repository and `DictionaryView.kt` have no
  `twoColumnLayout` setting.
- `LookupPopupHtml.kt` injects compact glossary and pitch options but no two-
  column flag. `popup.js` has no masonry/ResizeObserver path, uses
  `maxCanvasSize = 128`, and `popup.css` lacks the refreshed cards and danger/
  success variables.
- `ReaderAppearanceView.kt` still constrains popup height to 500.

Suggested slice:

- Add profile-aware setting persistence and bootstrap injection, port the final
  asset behavior while preserving Android bridge calls, and raise the height
  range with focused tests.

Validation:

- Reader, Dictionary tab, recursive lookup, and Process Text with one/multiple
  dictionaries, collapsed sections, long glossaries, images, mining/audio
  buttons, dark/e-ink themes, reduced motion, and outside dismissal.
- Run `node --test app/src/test/js/*.test.mjs`, focused settings tests,
  localization tests, and lint.

### 8. Reader route open-failure fallback

Status: pending Android sync.

Commits:

- `53fdb72` - show a closeable book-open failure view.

Dependency/value reasoning:

- This is a small route reliability slice independent of reader runtime work.

iOS behavior to mirror:

- If loading cannot produce a Reader view, show a neutral full-screen
  "Couldn't open book" state with a Close action that dismisses Reader.

Android current gap:

- `ReaderRouteStateHolder.load()` returns raw localized exception text such as
  `Book not found.` through `ReaderRouteLoadState.Error`.
- `ReaderRouteDestination()` renders only `Text(state.message)` and offers no
  Close action through the normal `onClose` route path.

Suggested slice:

- Use localized generic error UI and the same close path as reader chrome, with
  state/render tests for missing and unparsable books.

Validation:

- Missing/corrupt book, working Close, normal Reader open/close, Android Back,
  bookshelf state preservation, and bookmark refresh.

### 9. Google Drive timeout and automatic-refresh error suppression

Status: pending Android sync.

Commits:

- `4dae37c` - use 10-second Drive timeouts and suppress transient automatic
  refresh errors.

Dependency/value reasoning:

- This belongs behind the existing Drive data-source/repository boundary and is
  independent of reader work.

iOS behavior to mirror:

- OAuth and Drive requests time out after 10 seconds. Automatic remote bookshelf
  refresh suppresses offline, timeout, and connection-lost failures while
  explicit user operations still report failures.

Android current gap:

- `DeviceCodeDriveAuthorizer` uses 15 seconds; `GoogleDriveClient` uses 15-second
  connect and 30-second read timeouts.
- `BookshelfViewModel.isOfflineRemoteLoadError()` suppresses only the normalized
  no-internet message, not socket/read timeout or connection-lost IO failures.

Suggested slice:

- Normalize transient failures at the Drive boundary using current Android
  networking guidance; suppress them only for automatic refresh and test manual
  operation errors separately.

Validation:

- Automatic refresh offline, slow token/list requests, and connection loss;
  manual connect/refresh/import/export/delete must still show actionable errors.

### 10. Reader WebView line-box CSS parity

Status: pending Android sync.

Commits:

- `bdf71a6` - remove the WebKit line-box property.

Dependency/value reasoning:

- Small independent layout parity change, but it needs device validation across
  writing modes and replaced elements.

iOS behavior to mirror:

- Reader CSS no longer sets
  `-webkit-line-box-contain: block glyphs replaced;`.

Android current gap:

- `app/src/main/assets/hoshi-web/reader/reader.css` still sets the property and
  `ReaderSettingsTest` explicitly preserves it.

Suggested slice:

- Remove it only after Android WebView comparison, then update tests to assert
  the final CSS behavior.

Validation:

- Paginated/continuous horizontal and vertical writing, ruby, cover and
  multi-image pages, line height, progress, and restore.

## Open Commit Inventory

| Commit | Date | iOS summary | Android status |
| --- | --- | --- | --- |
| `9eff7dd` | 2026-06-20 | Add excluded dictionary category | Pending dictionary schema/query support |
| `67fc9e8` | 2026-07-27 | Add Kanji dictionary support | Pending bridge, storage, query, and popup support |
| `3cd8294` | 2026-07-28 | Complete pitch string/nasal/devoice rendering | Pending bridge model and popup rendering |
| `119fb5b` | 2026-06-18 | Advanced Anki definition mappings | Pending category-aware renderer/settings |
| `bd85c9b` | 2026-06-19 | Multiple Anki card formats | Synced (current three-format limit) |
| `c943171`, `395218a` | 2026-06-19 / 2026-07-10 | Show existing Anki notes and own the setting in Anki | Synced for AnkiConnect and AnkiDroid |
| `8464a2c`, `f1bc74b`, `2c86ed6` | 2026-06-20 | Cloze, pitch graph, and definition variant handlebars | Partial: cloze/numeric pitch/non-category variants synced; category variants pending |
| `47683d9`, `2702e31` | 2026-06-20 | Guard invalid/deleted Anki formats | Synced |
| `15d4a6e`, `23e0764` | 2026-06-15 / 2026-06-20 | Three-state revealable furigana mode and migration | Pending enum, migration, and tap semantics |
| `eb86431`, `c31c9d0` | 2026-07-26 / 2026-07-31 | Split cross-page paragraphs with edge-case fixes | Pending Android paginated fragmenter |
| `ff86caa` | 2026-07-28 | Explicitly load selected reader font | Pending computed-font load await |
| `947898c` | 2026-07-01 | Export Sasayaki mining clips as MP3 | Pending Android MP3 encoder/export path |
| `4a5cfde` | 2026-07-14 | Honor media-control skip mode | Pending MediaSession command routing |
| `a9a0747` | 2026-07-28 | MP4/TXT Sasayaki imports | Pending SAF/storage updates |
| `c6b29c8`, `1db2cd3` | 2026-07-26 | Cover fallback and Show/Blur/Hide modes | Pending metadata/settings/Compose UI |
| `ed25036`, `8d1442e` | 2026-06-14 / 2026-07-01 | Popup masonry redesign and theme accents | Pending settings/assets/height range |
| `53fdb72` | 2026-06-15 | Closeable Reader open-failure view | Pending route error UI |
| `4dae37c` | 2026-06-13 | Drive timeouts and transient refresh suppression | Pending timeout/error normalization |
| `bdf71a6` | 2026-06-07 | Remove Reader WebKit line-box property | Pending Android WebView validation |

## Suggested Implementation Order

1. Dictionary categories, Kanji dictionaries, and complete pitch data.
2. Finish category-aware Anki definitions and final pitch graph schema.
3. Reader paginated paragraph splitting and explicit font readiness.
4. Reader furigana reveal mode.
5. Sasayaki import, media controls, and MP3 mining clips.
6. Bookshelf cover privacy and fallback artwork.
7. Lookup popup two-column layout and visual sizing.
8. Reader route open-failure fallback.
9. Google Drive timeout and automatic-refresh error suppression.
10. Reader WebView line-box CSS parity.

## Covered Or No Android Action

- `d7fe3f2`: Android Sasayaki now exposes -4...4-second delay and 0.5...3x
  playback-speed sliders with the existing 0.05 step size.
- `4940ab7`, `6655ffd`, `3bff390`: Android now removes numeric HTML entities
  before shared matchable character counting, leaves trailing ellipses and
  periods outside the selected lookup sentence, and scopes recursive lookup to
  the active `.expr-tag`.
- `b928010`: Android now serializes original-cover derivative generation in
  `BookCoverThumbnailStore` and bounds Coil bitmap decoding in the shared
  process-wide image loader.
- `fd124d4`, `bcbef64`, `2e1c958`, `51cb994`: Android now persists the
  first-appearance Reader image inventory and TOC fragment offsets, uses one
  true TOC range for Contents/chrome/statistics, and opens Gallery items in the
  existing fullscreen viewer.
- `f403c99`, `b4e6edd`, `54fab15`: Android now persists a minute-level
  statistics reset time, uses the adjusted local date in Reader and the
  Statistics dashboard, and pauses tracking across Reader sheets and fullscreen
  images without losing the active tracking state.
- `24e356f`: orphaned Xcode project fix from the previous force-updated tip; no
  Android behavior.
- `e63cb91`, `f09664d`, `ff31274`, `262df07`, `a90a83f`, `ede061f`,
  `f5c62d8`, `6cfb7b8`, `b02da68`, `d175c93`, `9fdd19b`, `25e57c5`,
  `b43c690`, and `b41ed09`: iOS release/version metadata only.
- `98f0ef4`: merge-only history integration; its reachable behavior commits are
  classified individually above.
- `e833279`, `e7b08b8`, `1992872`, `c1e4e57`: intermediate hoshidicts bumps are
  superseded by the final dictionary behavior audited above; only the missing
  final bridge capabilities remain queued.
- `77a7eaa`, `19bd095`: iOS cleanup and unwrap removal do not define additional
  Android-visible behavior.
- `188284b`: iOS local-audio launch/actor initialization fix has no direct
  Android analogue; Android local audio is repository-backed and Media3-owned.
- `0f8a3ac`, `6a1ad82`, `c842f0a`: iOS safe-area capture/fullscreen inset
  mechanics are platform-specific. Android uses persisted top/bottom safe-area
  settings, WindowInsets, and a full-screen Compose image overlay.
- `b717c57`: iOS CSS Highlight object reuse is an implementation optimization;
  no distinct Android behavior was found.
- `5c33790`, `61a8c9d`: Android `TtuBookDataConverter.rewriteImages()` already
  resolves and normalizes relative chapter image paths before writing TTU data.
- `e1d4b3b`: `reader-media-semantics.js` already resolves promises for complete
  failed images and `onerror`, so failed images do not block Reader setup.
- `f54b55f`: Android `LocalAudioResolver` already ranks exact expression and
  reading matches first, with released behavior and tests.
- `489895d`: Android popup glossary rows already carry `data-dictionary`, and
  frequency/pitch labels use dedicated spans.
- `236ca72`: AnkiMobile callback notification timing is iOS-specific; Android
  uses AnkiDroid or AnkiConnect backends.
- `7617784`: Android shared Reader/VN tests and runtime already preserve
  cross-node Sasayaki punctuation highlighting.
- `be88af1`: Android restores previous-chapter Sasayaki cues to cue-relative
  progress through `readerProgressForCue()` and the dedicated previous-cue fix.
- `e69aee7`, `50169c0`: Android provides an always-visible, opt-in pinned Reader
  playback control row in the bottom safe area; it has no collapsible state, so
  the iOS floating control-bar expansion option needs no separate Android flag.
- `ede999c`: Android implements configurable Sasayaki image holds through shared
  reader media semantics and `ReaderSasayakiAutoPage`, including fullscreen and
  continuous-mode fixes.
- `e969056`, `83eb319`: Android cue display actions distinguish reveal requests
  from passive paused position updates and explicitly reveal the target when a
  playback/seek command resumes.
- `47b0bba`: `DictionaryLookupQueryService` serializes rebuilds and atomically
  swaps complete sessions under read/write locks, preventing stale concurrent
  rebuilds from replacing the active query.
- `89feebd`, `44f47c3`, `0da83dd`, `9f94c32`: iOS-native search field,
  autocorrection, and touch-tolerance implementation changes have no direct
  Compose/WebView parity action beyond Android's existing IME and configurable
  popup swipe handling.
