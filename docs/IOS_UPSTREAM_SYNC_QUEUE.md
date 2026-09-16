# iOS Upstream Sync Queue

This document tracks open Android work after checking iOS upstream `develop`.

- Source: `reference/Hoshi-Reader-iOS`
- Baseline for this refresh: `c31c9d0ce376ff83bf6a91d908bf9f8e0fb4947b`
- Latest checked: `origin/develop` at `42e7b81d441c164c3a446152f4bf2356135d1e82`
- Checked on: 2026-09-16
- This refresh advances the reference by 43 reachable commits. The baseline is
  an ancestor of the new tip; new behavior and earlier open work were checked
  against current Android code. The older force-update is historical context
  only (`24e356f` remains classified below).

## Current Queue

### 1. Reader text normalization for Korean and ruby fallback text

Status: pending Android sync.

Commits: `703347a`, `b7f09ca` (shared `filtered()` ruby cleanup).

Dependency/value reasoning:

- Land shared counting parity before search/highlight slices; native book facts,
  WebView offsets, progress, and Sasayaki must agree.

iOS behavior to mirror:

- Matchable text includes Hangul `가-힣` and compatibility Jamo `ㄱ-ㆎ`.
  Native normalization removes both `rt` and `rp` contents.

Android current gap:

- `ReaderTextFilter.kt.isReaderMatchableCodePoint()` and
  `reader-text-semantics.js` omit both Korean ranges.
  `visibleReaderText()` strips `rt` but leaves `rp` text, unlike live DOM
  `reader-dom-text.js`. This changes native counts/search/Sasayaki offsets.

Suggested slice:

- Update both shared counting boundaries and invalidate derived book facts
  where necessary; retain raw highlight and VN source/clone offset contracts.

Validation:

- Mixed Korean/Japanese/Latin, ruby with `rp`, supplementary characters, native
  versus all-mode JS counts, cached book reopen, progress/restore and Sasayaki.

### 2. Reader renderer termination recovery

Status: pending Android sync.

Commits: `7d7321f`.

Dependency/value reasoning:

- Independent route/runtime reliability improvement with high value when the
  OS terminates a background renderer. Use the closeable fallback in slice 3
  if recovery cannot complete.

iOS behavior to mirror:

- Retain the latest progress, re-enter loading/restore, reload the chapter,
  restore cues/highlights, and resume Sasayaki transition handling after restore.

Android current gap:

- `ReaderChapterWebView.kt.EpubWebViewClient.onRenderProcessGone()`
  only calls `view.destroy()` and returns true. It does not notify the state
  holder, remove/recreate the view or replay chapter/progress/settings/cues and
  highlights. `ReaderWebView.kt` has no renderer-recovery generation.

Suggested slice:

- Add a typed termination event and recreate the WebView through its existing
  host/state lifecycle, restoring from the latest accepted state. Confirm the
  replacement lifecycle against Android WebView official guidance during
  implementation.

Validation:

- Background renderer exit/crash, latest position, loading frame, popup/native
  selection cleanup, highlight/Sasayaki restore, repeated termination and Close.

### 3. Reader route open-failure fallback

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

### 4. Statistics lifecycle, archive, editing and overview parity

Status: partial Android implementation; remaining parity work.

Commits: `d8c086d`, `93ba3be` (sync default).

Dependency/value reasoning:

- Archive/restore storage must precede editing archived books and dashboard
  aggregation. Android already has a dashboard, goals, calendar and trends;
  retain only missing behavior rather than queueing a replacement dashboard.

iOS behavior to mirror:

- Statistics availability cannot be disabled, while tracking can still be
  started/stopped. Sync defaults on for unset preferences.
- Deleting a book archives active daily statistics, compatible metadata and a
  small cover; reimport merges each date by latest modification and unarchives.
  Archived statistics remain visible and can be cleared in settings.
- Book rows open daily editors for characters and hours/minutes, daily delete
  and confirmed delete-all. Empty archived records remove the archive entry.
- Overview includes week/month/year/all-time selection, previous-period average
  reading-time comparison and all-history goal summaries (longest streak,
  days met and best day). Default goal is time, 20 minutes or 5000 characters.

Android current gap:

- `ReaderSettings.kt.enableStatistics` gates `AppShell.kt`, tracking and display
  controls; `statisticsSyncEnabled` defaults false.
- `BookRepository.deleteBook()` deletes the folder without archiving.
  `AndroidStatisticsRepository.loadSnapshot()` enumerates only local book
  folders. Its interface has no archive/restore/clear or daily mutation APIs.
- `StatisticsEvent`/`StatisticsViewModel`/`StatisticsDistributionList.kt` have
  no book daily editor or deletion actions. `StatisticsRangeMode` has no All
  period, `StatisticsCalculations.kt` exposes current streaks but no all-history
  longest/best-day summaries or previous-period time delta. Targets default to
  Characters and 30 minutes in `StatisticsModels.kt`.

Suggested slice:

- Implement archive/restore and compatible deduplication behind repositories,
  then daily editing, always-available statistics/unset-only defaults and the
  missing overview calculations/UI. Preserve existing explicit user settings.

Validation:

- Delete/reimport local EPUB and TTU/Drive book, archived covers, newest-date
  merge/ties, daily edits/deletes, empty archive cleanup, sync edits, goal/default
  migration, all-time and previous-period results, reset-time and Chinese layouts.

### 5. Reader highlight ruby text and exact-range editing

Status: pending Android sync.

Commits: `00f95c4`, `21971bb`.

Dependency/value reasoning:

- Uses the shared text/offset contract in slice 1; adds useful highlight editing
  without replacing Android's existing native selection menu.

iOS behavior to mirror:

- Persist optional `textFurigana` as base text plus parenthesized readings and
  show it in Contents. Selecting an identical raw range recolors its existing
  highlight; choosing its current color removes it.

Android current gap:

- `ReaderHighlight.kt`, `ReaderHighlightCreationResult`, `highlights.js`, and
  `ReaderHighlightSheet.kt` carry/display only plain `text`.
  `hoshiHighlights.createHighlight()` always wraps a new ID; it has no range
  identity metadata or recolor/remove result. VN's highlight creation adapter
  in `reader-visual-novel.js` also needs the same source-range behavior.

Suggested slice:

- Extend compatible sidecars and creation/update results, share exact-range
  matching, and update the Kotlin persistence/Contents flow.

Validation:

- Legacy sidecars, ruby across styled nodes, repeated selection with same/new
  color, restart/sync, all reader modes, and raw versus normalized offsets.

### 6. Book search literal matching and landing highlight

Status: partial Android implementation; remaining parity work.

Commits: `b7f09ca` (search behavior only).

Dependency/value reasoning:

- Build on slice 1 and the existing Contents search; reuse highlight range
  projection after slice 5 without storing transient search marks.

iOS behavior to mirror:

- Search plain visible text case-insensitively, preserving punctuation/spaces
  and paragraph boundaries, with sentence/bracket-aware snippets and a 100-hit
  limit. Jumping restores the result position and shows a transient blue match
  highlight; page navigation clears it.

Android current gap:

- `ReaderSearchEngine.search()` normalizes both query and document, dropping
  punctuation/space, uses fixed 24/48-character snippets and a 1000-hit limit.
  `ReaderSearchDocumentBuilder` does not preserve paragraph boundaries.
- `ReaderWebView.kt.onSearchResultJump` only navigates; `ReaderSearchResult`
  lacks a normalized match length and `highlights.js` has no transient search
  highlight command. The search entry/results/history already exist.

Suggested slice:

- Keep literal display-text matching with an explicit normalized position map,
  return match length, and apply/clear a temporary projected highlight after
  restore in each mode.

Validation:

- Queries containing spaces/punctuation, paragraph boundaries, case, ruby,
  bracketed complete sentences, 100-hit limit, jump/back/forward, page-turn
  clearing and supplementary-character offsets in all reader modes.

### 7. Lookup popup two-column layout and dictionary CSS isolation

Status: pending Android sync.

Commits:

- `ed25036` - masonry layout and popup visual redesign.
- `8d1442e` - add Yomitan danger/success theme variables.
- `0a91398` - scope dictionary CSS to div wrappers.

Dependency/value reasoning:

- This is a shared popup asset/settings slice used by Reader, Dictionary tab,
  and Process Text. Land persistence and bootstrap values before JS/CSS layout.

iOS behavior to mirror:

- Dictionary settings add a Two-Column Layout toggle. Multi-dictionary glossary
  cards use masonry/two-column layout when enabled and keep one column otherwise.
- Popup cards, padding, theme accents, and definition image canvas sizing match
  the refreshed design. Dictionary styles target `:where(div)[data-dictionary]`
  so labels carrying the same dictionary name do not inherit glossary styles.

Android current gap:

- `DictionarySettings`/repository and `DictionaryView.kt` have no
  `twoColumnLayout` setting.
- `LookupPopupHtml.kt` injects compact glossary and pitch options but no two-
  column flag. `popup.js` has no masonry/ResizeObserver path, uses
  `maxCanvasSize = 128`, and `popup.css` lacks the refreshed cards and danger/
  success variables.
- The glossary selector in `popup.js` still targets every `[data-dictionary]`
  element. Height is already configurable to 1000 in `ReaderAppearanceView.kt`;
  the upstream height increase requires no remaining Android work.

Suggested slice:

- Add profile-aware setting persistence and bootstrap injection, port the final
  asset behavior and div-scoped dictionary styles while preserving Android
  bridge calls, with focused behavior tests.

Validation:

- Reader, Dictionary tab, recursive lookup, and Process Text with one/multiple
  dictionaries, collapsed sections, long glossaries, images, mining/audio
  buttons, dark/e-ink themes, reduced motion, and outside dismissal.
- Run `node --test app/src/test/js/*.test.mjs`, focused settings tests,
  localization tests, and lint.

### 8. Popup audio candidate selection

Status: pending Android sync.

Commits: `baccc84`.

Dependency/value reasoning:

- Extend the existing audio repository/request boundary before popup menus;
  playback and mining must use the same selected URL.

iOS behavior to mirror:

- Long-press audio to list named candidates from enabled sources, mark the
  selected one, choose/play it and use it for mining. Local audio returns all
  ranked candidates, deduplicated URLs and descriptive source/match labels.
  Redirect/history changes reset entry-scoped audio candidate state.

Android current gap:

- `AudioRequestHandler.localAudioResponse()` returns only one resolved entry;
  `LocalAudioRepository` resolves a preferred result, with no popup candidate
  list API. `AudioSettings.enabledAudioSourceUrls` and
  `LookupPopupHtml.audioSourcesJson()` send URL strings without source names.
- `popup.js.fetchAudioUrl()` takes only the first candidate, and
  `playEntryAudio()` has no source index/menu or selected-candidate cache.
  Global source ordering/enable controls are already present.

Suggested slice:

- Expose named candidates through the existing bridge, add an entry menu with
  current choice and no-audio state, and share the chosen URL with mining.

Validation:

- Local/remote mixed sources, exact/reading-only matches, duplicate names/URLs,
  empty/failing sources, autoplay, mining, recursive lookup and history resets.

### 9. Frequency sorting controls and import/update feedback

Status: partial native support; pending Android UI/bridge integration.

Commits: `165992a`, `e849e36` (Auto naming), `222a72b`,
`7dd3f49` (automatic low-RAM policy only).

Dependency/value reasoning:

- Native frequency options already exist in vendored hoshidicts; extend the
  parent/JNI ABI consistently before settings/query callers. Import diagnostics
  similarly need a typed result before UI can report useful per-file reasons.

iOS behavior to mirror:

- Auto/Ascending/Descending/Disabled frequency sorting, optional enabled
  frequency dictionary for explicit order, initial valid dictionary selection
  and preservation across dictionary title updates.
- Batch import continues after individual failures and reports filename plus
  reason. Automatic dictionary updates always use low-RAM import.

Android current gap:

- `DictionarySettings` has no sort order/dictionary fields;
  `HoshiDicts.lookup`, `DictionaryNativeBridge` and
  `DictionaryLookupQueryService.lookup()` accept no frequency options, although
  the vendored C++ `LookupOptions`/C API support them.
- `ImportResult` omits native error text; `DictionaryImportDataSource` replaces
  failure with a generic message. `DictionaryViewModel` retains failed items'
  names but drops individual reasons. Per-import staging/continuation exists.
- `DictionaryAutoUpdateRunner` uses `DictionaryUpdateService`, whose
  `lowRamImport = settings.lowRamDictionaryImport` defaults false even for
  `DictionaryMutationOperation.AutoUpdate`.

Suggested slice:

- Expose typed native options/results, persist sort settings and update renamed
  references, retain per-file localized failure context, force low-RAM only for
  automatic updates. Keep Android's serialized atomic query-session replacement;
  iOS `releaseQuery()` is an implementation choice, not an extra product gap.

Validation:

- All sorting modes, missing/disabled/reordered/renamed dictionaries, profiles,
  equal/missing frequencies; mixed valid/invalid batch imports and recovery;
  automatic low-RAM with the manual setting off and unchanged manual behavior.

### 10. Anki tag handlebars

Status: pending Android sync.

Commits: `7b9dda8`.

Dependency/value reasoning:

- Small independent mining slice; reuse the existing handlebar resolver for
  tags, preserving both backend paths and per-format configuration.

iOS behavior to mirror:

- Resolve handlebars in tags; join whitespace inside substituted values with
  underscores before splitting tags. New/reset formats use the default tag
  `hoshi`.

Android current gap:

- `AnkiRepository.kt` builds tags by splitting raw `format.tags`; it never calls
  the field resolver for tag substitutions. `AnkiModels.kt` defaults tags to
  an empty string, and format creation/reset follows that default.

Suggested slice:

- Resolve each substitution using the same mining context as fields, escape its
  whitespace, and apply the new-format default without overwriting saved tags.

Validation:

- Literal plus title/expression tags, whitespace/newlines, missing title,
  unknown handlebars, multiple formats, saved custom tags and both backends.

### 11. Google Drive timeout and automatic-refresh error suppression

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

### 12. Remote bookshelf last-access ordering

Status: pending Android sync.

Commits: `e6e2b4b`.

Dependency/value reasoning:

- Independent of timeout slice 11; reuse the existing TTU filename timestamp
  parsers and grouped Drive file discovery.

iOS behavior to mirror:

- Remote last access is the newest progress/audiobook timestamp, falling back
  to bookdata last access when neither exists; Recent sort reflects it.

Android current gap:

- `RemoteBookEntry` has no last-access field. `BookshelfRepository.kt`
  `loadRemoteBooksOnce()` builds remote entries and sorts by title regardless of
  recent progress/audio filenames. `DriveSyncFiles` has no last-access projection.
  `GoogleDriveClient.toDriveSyncFiles()` already selects latest files by type.

Suggested slice:

- Add the timestamp projection and apply selected bookshelf sorting to remote
  entries without changing local metadata or downloading full books.

Validation:

- Progress versus audio newest timestamp, bookdata fallback, missing/malformed
  names, multiple remote books, Recent/Title switch and refresh/import.

### 13. Reader navigation and options toolbar

Status: partial Android implementation; remaining visual/interaction parity.

Commits: `42e7b81`.

Dependency/value reasoning:

- Uses existing Compose chrome/settings and should follow always-available
  statistics in slice 4; UIKit itself is not an Android implementation target.

iOS behavior to mirror:

- A top navigation title/subtitle and bottom Close, centered information and
  Options menu replace individual sheet buttons. Options contains Appearance,
  Contents, Statistics and eligible Sasayaki. Focus hides both bars while
  configured tracking/playback/history controls remain in the top safe strip.
  Continuous content reserves navigation/toolbar insets without covering text.

Android current gap:

- `ReaderWebViewChrome.kt.ReaderBottomChrome` still exposes separate Appearance,
  Contents, Statistics and Sasayaki buttons rather than the Options menu and
  centered toolbar information. The current title/progress bubble layout also
  differs from the navigation title/subtitle in `ReaderViewController.swift`.
- `ReaderChrome.kt` already owns focus visibility and content insets; keep those
  boundaries and adapt their final dimensions/state for the new arrangement.

Suggested slice:

- Mirror the final actions and information placement with Compose/Material 3,
  reuse close/focus/menu state and verify continuous-mode inset handling.

Validation:

- Title/progress/statistics combinations, Close and Android Back, menu sheet
  routing, Sasayaki eligibility, focus toggles/history, horizontal/vertical
  continuous and paginated/VN content, custom/dark/e-ink themes and rotation.

### 14. Reader WebView line-box CSS parity

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

- Compare Android WebView layout, remove the retained declaration, and replace
  the source-string preservation assertion with meaningful layout coverage.

Validation:

- Paginated/continuous horizontal and vertical writing, ruby, cover and
  multi-image pages, line height, progress, and restore.

### 15. App accent and stroke-order font attribution

Status: pending Android sync.

Commits: `bd21e24`, `8024df1` (font attribution only).

Dependency/value reasoning:

- Independent small UI slices; accent must preserve Android dark/e-ink contrast.
  Attribute the font Android already offers, without adding unused SwiftLAME.

iOS behavior to mirror:

- Use the new blue accent (Display-P3 components 0.523/0.668/0.904) and expose
  Kanji Stroke Order Font source/BSD-3 attribution in About.

Android current gap:

- `Theme.kt`/`Color.kt` still use default purple Material accent colors.
  `AboutView.kt` has no stroke-order font source/license entry, although
  `KanjiStrokeOrderFontInstaller` offers that font for download.

Suggested slice:

- Choose a color-managed equivalent in the Android palette and add localized
  source/license UI using the existing About surface.

Validation:

- Ordinary app controls in light/dark/custom and pure e-ink themes; About links
  and font license text in English/Chinese.

## Open Commit Inventory

| Commit | Date | iOS summary | Android status |
| --- | --- | --- | --- |
| `ed25036`, `8d1442e`, `0a91398` | 2026-06-14 / 07-01 / 08-22 | Popup layout/themes and dictionary CSS isolation | Pending settings/assets and div-scoped styles |
| `53fdb72` | 2026-06-15 | Closeable Reader open-failure view | Pending localized route error UI |
| `4dae37c` | 2026-06-13 | Drive timeouts and transient refresh suppression | Pending timeout/error normalization |
| `bdf71a6` | 2026-06-07 | Remove Reader WebKit line-box property | Pending removal of retained Android declaration |
| `703347a` | 2026-08-12 | Count Korean characters | Pending native/shared web counting parity |
| `00f95c4`, `21971bb` | 2026-08-12 / 08-13 | Highlight ruby text and exact-range editing | Pending sidecar/bridge/range editing |
| `b7f09ca` | 2026-08-13 | Book search and shared ruby normalization | Pending literal search, snippets, landing marks and rp cleanup |
| `7d7321f` | 2026-08-05 | Restore after renderer termination | Pending WebView recreation/state restore |
| `d8c086d`, `93ba3be` | 2026-08-09 / 08-21 | Statistics lifecycle/archive/editing and sync default | Pending remaining storage/editor/overview/default behavior |
| `baccc84` | 2026-08-09 | Choose popup audio candidate | Pending candidate API/menu/mining choice |
| `165992a`, `e849e36` | 2026-08-16 / 08-17 | Frequency sorting and final labels | Pending Kotlin/JNI/settings; remaining overview wording |
| `222a72b`, `7dd3f49` | 2026-08-31 / 09-02 | Import diagnostics and automatic low-RAM updates | Pending per-file reasons and automatic import policy |
| `7b9dda8` | 2026-08-20 | Tag handlebars and new-format default | Pending tag resolver/default |
| `e6e2b4b` | 2026-08-19 | Remote book last access | Pending timestamp projection/Recent ordering |
| `42e7b81` | 2026-09-14 | Reader navigation/options toolbar | Pending final Compose action/information layout |
| `bd21e24`, `8024df1` | 2026-08-09 / 08-22 | Blue accent and font attribution | Pending palette/About UI |

## Suggested Implementation Order

1. Shared Korean/ruby normalization (slice 1), before offset-dependent changes.
2. Renderer recovery (2) and localized open-failure fallback (3).
3. Statistics archive/restore, then daily editing and lifecycle/overview parity (4).
4. Highlight sidecar/range editing (5), then book search remaining parity (6).
5. Popup layout/CSS isolation (7).
6. Audio candidate API, then popup selection/mining (8).
7. Native frequency options/import diagnostics, then settings and automatic
   low-RAM update policy (9).
8. Anki tag handlebars (10).
9. Drive timeout/error suppression (11) and remote Recent sorting (12).
10. Reader navigation/options toolbar (13), after statistics availability (4).
11. Reader line-box CSS parity (14), app accent and font attribution (15).

## Covered Or No Android Action

- `beb46ba`, `969b978`: Dictionary search now shows tappable source text with
  profile-scoped 12–48 sizing, matched spans, preserved redirect scroll, and
  original-sentence/UTF-16 Anki cloze context. The same root-only behavior also
  covers Android Process Text/shared lookups, including an empty initial match.
- `15d4a6e`, `23e0764`, `a4e16df`, `253a589`: Android now provides
  profile-scoped Off/Dimmed/Toggle/Hidden furigana modes, migrates legacy hide
  booleans, consumes Toggle reveal taps, reveals whitespace-adjacent ruby, and
  retains VN reveal state across screen rendering.
- `c6b29c8`, `1db2cd3`: Android now stores the first EPUB creator as optional
  compatible book metadata, renders deterministic title/author fallback artwork,
  and applies persisted Show/Blur/Hide privacy modes across local, remote,
  expanded, and collapsed bookshelf covers. Android 8-11 safely maps Blur to the
  hidden fallback because the platform blur effect starts on Android 12.
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
- `9eff7dd`, `67fc9e8`, `3cd8294`: Android now persists term-dictionary
  categories, supports Kanji dictionaries, and renders numeric/H/L pitch plus
  1-based nasal/devoice popup indicators. The iOS Anki pitch SVG generator does
  not encode nasal/devoice indicators, so Android's exported SVGs require no
  additional platform action.
- `119fb5b`, `bd85c9b`, `c943171`, `395218a`, `8464a2c`, `f1bc74b`,
  `2c86ed6`, `47683d9`, `2702e31`: Android now has three independent Anki
  formats, show-notes routing for both backends, guards for invalid formats,
  precise cloze and pitch graph handlebars, and category-aware monolingual/
  bilingual definition variants resolved from persisted dictionary order.
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
  superseded by the final dictionary behavior; Android already exposes Kanji,
  pitch and transcription data. Explicit frequency option integration is the
  remaining bridge gap described in slice 9.
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
- `16825e4`: Android's native EPUB parser and `EpubBookParser.toReaderBook()`
  already resolve nested EPUB3 nav/EPUB2 NCX paths; tracked generated fixtures
  in `EpubBookParserTest` cover both paths and fragments.
- `eced649`: `ReaderGoToSheet.kt` uses
  `rememberInitiallyCenteredLazyListState()` for the current chapter, including
  hiding content until centering completes and no ongoing selection-following.
- `dd5e7a2`: `KanjiStrokeOrderFontInstaller`, `DictionaryViewModel` and
  Dictionary UI already implement confirmed verified download/import and the
  installed-font disabled state, with installer tests.
- `c96acb3`: `SasayakiMatcher.selectCoherentAlignment()` ranks starting
  candidates and `selectRecoveryPlan()` handles consecutive local misses;
  `SasayakiMatcherTest` covers repeated text and multi-volume recovery.
- `63d96a1`: the SwiftUI geometry feedback/crash fix has no matching Android
  path. `StatisticsDistributionList.kt` uses Compose bounded row/bar sizing
  rather than a geometry observer feeding its own measured width.
- `7994b59`: CoreText continuation double-resume is iOS-specific; Android font
  downloads use cancellable repository coroutines and verified temporary files.
- `17ceb79`: iOS modal presenter rejection is platform-specific. Android Reader
  is a typed Navigation3 route with an explicit close path, not a separately
  presented reader window; its open-error UI gap remains slice 3.
- `f6b15bc`: iOS AppIntents are platform shortcuts, not a portable API.
  Android already routes Page Up/Down and enabled volume keys through
  `ReaderHardwareKeyNavigation`; no distinct Android external shortcut contract
  is introduced by this upstream commit.
- `7c50443`, `434ed70`, `aa1994f`: dependency revision metadata only. Android's
  vendored native library already supports frequency `LookupOptions`, IPA/
  transcriptions and importer error results; expose missing Kotlin/JNI behavior
  in slice 9 rather than queueing revision bumps.
- `7dd3f49` (query-release mechanics): Android's
  `DictionaryLookupQueryService.rebuild()` serializes complete replacement
  sessions and destroys the prior session after its atomic swap; the Swift
  bundle-release sequence is not an extra Android behavior requirement. The
  automatic low-RAM difference remains slice 9.
- `93ba3be` (popup defaults): Android already defaults popup width/height to
  500/500 and permits height 1000, exceeding the iOS increase to 350/310. The
  unset statistics-sync default remains slice 4.
- `8024df1` (SwiftLAME attribution): Android does not ship SwiftLAME; no action.
  Attribution for the downloadable stroke-order font remains slice 15.
- `efd89fc`, `e1b0854`: README/issue-template changes only.
- `0425880`, `c71a2a9`, `d76127d`, `f86eb95`, `d8e150d`, `8137e1e`:
  iOS version metadata only.
- `50aaa6f`: merge integration only; reachable behavior commits are classified
  individually above.
- `27510b0`: removal of old iOS storage migrations has no Android action;
  retain Android's own compatibility migrations.
