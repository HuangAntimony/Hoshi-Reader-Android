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

### 1. Multiple Anki card formats and advanced handlebars

Status: partially synced on Android. Multi-format storage/UI/popup routing,
show-notes, cloze parts, selected-glossary fallback, dictionary categorization,
and numeric/string pitch graphs are complete. Category-aware
monolingual/bilingual handlebars and nasal/devoice Anki graph markers remain.

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
- Anki Advanced exposes the persisted none/monolingual/bilingual/exclude
  categories for term dictionaries. The renderer supports precise UTF-16 cloze
  parts, selected-glossary fallback, numeric and H/L pitch SVGs,
  first-pitch extraction, and non-category advanced glossary variants. Legacy
  selected-glossary fallback aliases remain hidden.

Remaining gap:

- First route glossary selection through the persisted dictionary categories,
  then add the monolingual/bilingual definition handlebars and fallback
  variants without changing the popup lookup schema.
- After category handlebars are stable, extend exported Anki pitch graphics with
  the already-available 1-based nasal/devoice mora markers. Popup rendering and
  numeric/H/L Anki pitch graphs are already complete.

Validation:

- Migrate existing single-format settings, add/edit/delete formats, and
  restart the app with all formats intact.
- Mine through AnkiConnect and AnkiDroid using different formats, unmapped first
  fields, deleted formats, disconnected backends, duplicates, media, cloze
  offsets, category fallbacks, and pitch graphs.

### 2. Reader furigana reveal mode

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

### 3. Bookshelf cover privacy and fallback artwork

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

### 4. Lookup popup two-column layout and visual sizing

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

### 5. Reader route open-failure fallback

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

### 6. Google Drive timeout and automatic-refresh error suppression

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

### 7. Reader WebView line-box CSS parity

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
| `8464a2c`, `f1bc74b`, `2c86ed6` | 2026-06-20 | Cloze, pitch graph, and definition variant handlebars | Pending only category-aware definition variants and the final pitch schema |
| `15d4a6e`, `23e0764` | 2026-06-15 / 2026-06-20 | Three-state revealable furigana mode and migration | Pending enum, migration, and tap semantics |
| `c6b29c8`, `1db2cd3` | 2026-07-26 | Cover fallback and Show/Blur/Hide modes | Pending metadata/settings/Compose UI |
| `ed25036`, `8d1442e` | 2026-06-14 / 2026-07-01 | Popup masonry redesign and theme accents | Pending settings/assets/height range |
| `53fdb72` | 2026-06-15 | Closeable Reader open-failure view | Pending route error UI |
| `4dae37c` | 2026-06-13 | Drive timeouts and transient refresh suppression | Pending timeout/error normalization |
| `bdf71a6` | 2026-06-07 | Remove Reader WebKit line-box property | Pending Android WebView validation |

## Suggested Implementation Order

1. Dictionary categories, Kanji dictionaries, and complete pitch data.
2. Finish category-aware Anki definitions and final pitch graph schema.
3. Reader furigana reveal mode.
4. Bookshelf cover privacy and fallback artwork.
5. Lookup popup two-column layout and visual sizing.
6. Reader route open-failure fallback.
7. Google Drive timeout and automatic-refresh error suppression.
8. Reader WebView line-box CSS parity.

## Covered Or No Android Action

- `eb86431`, `c31c9d0`, `ff86caa`: the paragraph fragmenter and explicit font
  request are WKWebView-specific selection/layout workarounds. Android's
  paginated reader already locks Chromium WebView scrolling during native
  selection and awaits used fonts before restore; copying the DOM fragmenter
  would add offset, highlight, progress, and Sasayaki mapping risk without a
  reproduced Android behavior gap.
- `947898c`, `4a5cfde`, `a9a0747`: no Android product action. Android's
  `SasayakiCueAudioExporter` emits platform-supported AAC/ADTS clips that both
  Anki backends already consume; adding an MP3 encoder only to match an iOS
  filename is not justified. Media3 keeps previous/next cue navigation separate
  from Reader skip-by-seconds controls, and the supported `.srt`, `.mp3`, and
  `.m4b` import set intentionally excludes generic `.txt` and `.mp4` aliases.
- `bd85c9b`, `c943171`, `395218a`, `47683d9`, `2702e31`: Android already has
  three independent Anki formats, show-notes routing for both backends, and
  guards for invalid, deleted, or unusable formats.
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
