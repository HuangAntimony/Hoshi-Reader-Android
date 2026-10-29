# iOS Upstream Sync Queue

## Checkpoint

- Source: `reference/Hoshi-Reader-iOS`, upstream `develop`
- Latest checked: `bb4e92d22e170ab4edf8f8e67bcb0f28ec6b5c7e`
- Checked on: 2026-10-29

The checked revision matches the current reference pointer. Use it as the start
of the next upstream comparison.

Kotlin paths below are relative to `app/src/main/java/moe/antimony/hoshi/`;
web paths are relative to `app/src/main/assets/hoshi-web/`. Test names identify
their existing JVM, JS or device fixtures. Implementation checks and device-data
protection follow [VALIDATION.md](VALIDATION.md). Confirm Android/Jetpack API
details against official documentation when implementing platform-facing slices.

## Current Queue

### 1. Reader page counts and position labels

Status: Pending

Commits: `95ce7f5`.

- Dependency/value: builds on reader-owned viewport sizing; adds a user-selectable
  page unit for book/chapter progress and location labels.
- iOS behavior: choose Off/Characters/Pages; collect page-start offsets per
  spine, cache them for the layout, and derive book/chapter page counts and
  contents/search/highlight position labels.
  Continuous mode retains character counts; unfinished measurements show a
  waiting state.
- Android current gap: `features/reader/ReaderSettings.kt` and
  `ReaderAppearanceView.kt` expose a count visibility toggle;
  `ReaderProgressDisplay.kt` supports only Characters/Words. Existing chapter
  pagination in `ReaderPaginationScripts.kt` has no whole-book page-start cache
  or page-unit setting feeding chrome and contents/search/highlight labels.
- Suggested slice: add page-unit preference, layout-keyed page measurements and
  offset conversion through existing reader owners. Retain Android word counts
  and durable character-based bookmarks; invalidate counts when layout or book
  content changes, and cancel obsolete measurement work.
- Validation: settings migration and count/position-label tests; JS pagination tests;
  real WebView checks across chapters, writing directions, font/padding changes,
  images and mode switches, including cache invalidation and restore.

### 2. Dictionary numeric-string frequencies and on-demand media mapping

Status: Pending

Commits: `a1babc9` (frequency parsing only), `e266954`.

- Dependency/value: independent native-engine work; accepts additional Yomitan
  frequency data and avoids mapping every dictionary media payload up front.
- iOS behavior: the hoshidicts updates add numeric-string frequency parsing and
  map media blobs on demand. Exact dependency changes are
  [a28d82e to fcea33f](https://github.com/Manhhao/hoshidicts/compare/a28d82eb0f169b8ceff79e8c99ffe0b96709ab27...fcea33f98f940e45505d51049b2f855123d54f39)
  and [e6af33e](https://github.com/Manhhao/hoshidicts/commit/e6af33e1ab3241e16b885ec81f048f05d81f766b).
- Android current gap: bridge `35ab0f0` embeds engine `4aa43ad`. Under
  `third_party/hoshidicts-kotlin-bridge/app/src/main/cpp/hoshidicts/`,
  `src/json/yomitan_parser.cpp::parse_frequency` lacks the quoted-number fallback;
  `src/query.cpp::add_dict` maps all of `media.bin`, and `get_media_file_view`
  reads that mapping directly without a per-blob cache.
- Suggested slice: update the native engine through the bridge owner, preserving
  the multilingual pipeline and Kotlin ABI, then advance Android's bridge pointer.
  Do not add unused upstream remove/reorder APIs merely to match the revision.
- Validation: extend `DictionaryFrequencyNativeTest` with numeric strings;
  exercise repeated/concurrent media reads, missing media, query replacement and
  session destruction. Measure large-media dictionary memory on a device before
  claiming an improvement; run native packaging checks from VALIDATION.

### 3. Sasayaki playback bounded by the current VN page

Status: Pending

Commits: `c2e323b` (page-advance playback only), `69ed0d1`.

- Dependency/value: independent; VN layout, cue matching and normal auto-scroll
  already exist. Enables listening one displayed page at a time.
- iOS behavior: optional Advance on Page Turn plays the displayed page's cues
  after a forward turn, seeks without playing after a backward turn, and pauses
  at the page end. Cap the end before the next cue to avoid flipping pages when
  adjacent cues overlap.
- Android current gap: `features/sasayaki/SasayakiSettings.kt` has no equivalent
  setting; `features/reader/ReaderChapterWebView.kt::navigatePage` updates reader
  progress without passing the page's cue set to playback. The playback controller
  has no current-page time range or page-end stop boundary.
- Suggested slice: publish displayed cue IDs through the reader bridge and let
  the service-owned playback controller handle page ranges, delay, seek and stop;
  preserve existing continuous playback when the option is off.
- Validation: settings/controller and VN JS tests for overlapping cues, next-cue
  boundary, delay, backward turns, cue-free/image pages, pause and manual seek;
  verify page-turn audio and no unwanted advance on a device.

### 4. Anki media filtering and remote cover reuse

Status: Pending

Commits: `81c60d2` (dictionary media and existing covers only).

- Dependency/value: independent of slice 2; reduces unnecessary media reads and
  uploads during card creation.
- iOS behavior: upload media referenced by the final rendered fields; reuse a
  cover already stored by AnkiConnect.
- Android current gap: `features/anki/AnkiRepository.kt::mineEntry` uploads every
  `payload.dictionaryMedia` before rendering fields, although cover/audio fields
  are already filtered. `AnkiConnectBackend.kt::addMediaFromBytes` always calls
  `storeMediaFile`, without checking the existing content-hashed cover name.
- Suggested slice: filter dictionary media from final fields and check remote
  cover existence for AnkiConnect, preserving the other Anki backends.
- Validation: extend `AnkiRepositoryBackendSelectionTest` and
  `AnkiConnectBackendTest` for selected/excluded glossaries, absent media fields,
  existing/missing covers and lookup-failure fallback; assert request sequences.

### 5. Transfer queue totals, remaining time and interrupted rows

Status: Pending

Commits: `21d5546` (presentation and aggregate accounting only).

- Dependency/value: independent; streaming, resumable uploads and shared bounded
  transfer scheduling are already implemented.
- iOS behavior: total/completed bytes and remaining time per direction, pending
  row sizes/covers, interrupted progress, and Syncing/Waiting/Failed groups.
- Android current gap: `features/sync/GoogleDriveSyncManager.kt` exposes book
  counts and active file snapshots; `SyncQueueItem` lacks pending sizes/covers
  and retained transfer state. `trackTransfer` removes snapshots in `finally`.
  `SyncTransferTracker.kt` accounts only for the current file; `SyncQueueView.kt`
  displays active speeds and a flat list without aggregate byte progress or ETA.
- Suggested slice: add application-owned queue accounting and interrupted
  snapshots, then render summaries and groups. Preserve Android's dedicated queue
  page, E-ink behavior, configurable concurrency and effective-speed semantics.
- Validation: deterministic clock/byte tests for completion, retries, resumed
  offsets, unknown sizes, new work and failure retention; extend
  `SyncTransferTrackerTest`, `GoogleDriveSyncManagerTest` and `SyncQueueViewTest`,
  including device scroll retention and E-ink rendering.

### 6. Keep the selected statistics period current across date changes

Status: Pending

Commits: `e2e0f31` (selected-period anchor only).

- Dependency/value: independent; dashboard reload and Today/heatmap refresh exist.
- iOS behavior: after a day change, advance a selected period containing the old
  today to the new current period; preserve deliberate historical browsing.
  Changing reset time resets the selected reference to today.
- Android current gap: `features/statistics/StatisticsViewModel.kt` stores a
  non-null `referenceDate` on `SelectPeriodPage`; neither `reload` nor reset-minute
  changes update it. Paging away and back to the latest period can leave the
  previous week/month/year selected after a boundary, despite fresh Today values.
- Suggested slice: reconcile selection against the previous/current statistics
  date and reset setting while retaining historical-period and bucket behavior.
- Validation: extend `StatisticsViewModelTest` with mutable date/reset flows,
  current-period rollover, historical browsing, return to latest and reset changes;
  retain existing `StatisticsViewReloadTest` lifecycle coverage.

### 7. Hide the status bar while a fullscreen image is open

Status: Pending

Commits: `18072c8` (system status bar only).

- Dependency/value: independent; gallery and reader images already cover app chrome.
- iOS behavior: opening a fullscreen image hides the status bar even outside focus mode.
- Android current gap: `features/reader/ReaderWebView.kt`'s system-bar effect and
  `ReaderChrome.kt::readerSystemBarVisibility` depend only on `focusMode`.
  `fullscreenImage` drives `ReaderFullscreenImageOverlay` but does not hide the
  status bar when opened from the gallery in ordinary mode.
- Suggested slice: include fullscreen-image visibility in the existing system-bar
  owner and restore the correct focus-dependent state on dismissal.
- Validation: extend `ReaderChromeTest`; device checks for gallery and inline
  images, dismissal/back, both focus states and existing navigation-bar behavior.

### 8. Show sub-minute transcription remaining time

Status: Pending

Commits: `3dfa9ef`.

- Dependency/value: independent small UI correction near transcription completion.
- iOS behavior: display "less than 1 min left" below 60 seconds.
- Android current gap: `features/sasayaki/SasayakiTranscriptionSection.kt` always
  formats `ceil(seconds / 60)` with `sasayaki_transcription_remaining`; EN/ZH
  resources contain only the approximate-minute form, including zero minutes.
- Suggested slice: add the localized sub-minute branch while keeping the current
  finite/nonnegative and Transcribing-stage guards.
- Validation: boundary cases at 0, below 60 and 60 seconds, absent/nonfinite values
  and non-transcribing stages; run localization checks and verify the UI label.

## Open Commit Inventory

Only concrete remaining behavior is listed; covered portions are summarized below.

| Commit | Remaining Android work | Slice |
| --- | --- | --- |
| `95ce7f5` | Page-count preference, measurements, display and position labels | 1 |
| `a1babc9` | Numeric-string frequency parsing | 2 |
| `e266954` | On-demand native dictionary media mapping | 2 |
| `c2e323b` | Optional VN page-bounded Sasayaki playback | 3 |
| `69ed0d1` | Stop page playback before the next overlapping cue | 3 |
| `81c60d2` | Filter dictionary media and reuse uploaded AnkiConnect covers | 4 |
| `21d5546` | Aggregate transfer accounting and pending/interrupted/grouped rows | 5 |
| `e2e0f31` | Reconcile selected statistics period on day/reset-time changes | 6 |
| `18072c8` | Fullscreen-image status-bar visibility | 7 |
| `3dfa9ef` | Sub-minute transcription ETA label | 8 |

## Suggested Implementation Order

All remaining slices are independent. Address state/layout correctness first,
then broader reader/dictionary/Anki value, followed by independent UI refinements:

1. Reader page counts and position labels.
2. Dictionary numeric-string frequencies and on-demand media mapping.
3. Sasayaki playback bounded by the current VN page.
4. Anki media filtering and remote cover reuse.
5. Transfer queue totals, remaining time and interrupted rows.
6. Keep the selected statistics period current across date changes.
7. Hide the status bar while a fullscreen image is open.
8. Show sub-minute transcription remaining time.

## Covered Or No Android Action

Project decisions retained for future comparisons:

| Upstream change | Android decision |
| --- | --- |
| `ed25036`: two-column popup layout and associated visual redesign | Intentionally excluded; retain the current Android popup layout. |
| `bd21e24`: fallback accent change | Retain Android's dynamic, custom and E-ink color choices. |
| `16a2f00`: force custom reader background opaque | Retain configured alpha: `ReaderSettings.backgroundColorCss` and `ReaderSettingsTest.customReaderCssPreservesConfiguredColorAlpha` explicitly preserve it; display-settings migration also preserves released custom colors. |
| `326e9ea`: two-hour statistics chart floor | Retain Android's explicit one-hour floor in `StatisticsTrendAxis.trendMaxHours`, `StatisticsTrendChartTest` and the current changelog. |
| `040aaab`: restart statistics on page turns | `ReaderStatisticsTracker` and reader wiring implement page-turn autostart; retain Android's separately configurable book-open/page-turn triggers. |
| `58c0e02`: manual statistics-stop latch | Intentionally excluded as an interaction-design difference: Android retains configured page-turn autostart after a manual stop, with no manual-stop latch. |

Code-backed coverage for the refreshed range:

| Upstream changes | Covered behavior / reason |
| --- | --- |
| `f4a7db0` | `reader/reader.css` uses `visibility: hidden` for toggled ruby annotations, preserving geometry; `shared/selection.js` reveals adjacent ruby. Layout-preserving toggle behavior is covered; Android retains its dotted marker. |
| `bc80d6e`, `864deb9` | `DictionarySearchIframe` renders unmatched source text; `shared/selection.js::findParagraph` keeps lookup within `li`. Existing iframe and selection tests cover both. |
| `30b3b19`, `d226044`, `f12897d`, `5a85d4e`, `67237b0`, `d8bba19`, `fb707c7` | Android owns chrome, focus, safe-area and viewport sizing in `ReaderChrome`, `ReaderWebViewChrome`, `ReaderViewportCssLayout` and generated layout; bottom progress disables soft wrapping. SwiftUI/UIKit label, edge-effect and home-button workarounds do not transfer directly. |
| `c2e323b`, `59c6120`, `d310ea2`, `0288a79`, `44c92da`, `0cb196a`, `e8d5ffa` | VN layout, settings, sentence limits, first-page handling and reveal cleanup exist in `reader/reader-visual-novel.js`, shared VN assets and reader settings. WebView callbacks use Android state holders. Only optional page-bounded Sasayaki playback remains open. |
| `95ce7f5` (chapter/spine distinction) | `ReaderTocRange` already distinguishes spine positions from true TOC chapters, including fragments within a single XHTML file; page-count behavior remains open. |
| `6403ae3` | `ReaderContentStyles` clears publisher root max-width/max-height in all modes while preserving descendant limits and continuous insets. `reader/reader-layout-semantics.js::sanitizeInlineBlocks` handles wrappers, struts and image centering; publisher writing modes are stripped. |
| `d9f1b72`, `62982b7`, `3d2f77a`, `daf0fd7`, `e596fe1`, `c69f203`, `62e5889`, `1cfbf71`, `ca7e053`, `45ac6e9`, `241c92b`, `1c5d525`, `77eaee3`, `4777715`, `def6cec`, `08bcbb4`, `55988cc` | `SyncStorage`, `GoogleDriveSyncManager`, `GoogleDriveClient`, `BookRepository`, `SyncReloadScheduler` and `SyncAttachPrompt` cover provider separation, sidecars/tombstones, archive covers, shelf recreation, cached versions, coalesced reload, retries, streaming, conflict choices and on-demand downloads. Swift file extraction requires no port. |
| `a8d422c`, `d9f1b72` (book actions) | `BookContextMenu` groups Read/Unread under Mark. `BookRepository` enables Read from cached bookinfo without a local EPUB. Confirmed Unread resets the bookmark and retains session tombstones through `BookStatisticsStore`; `BookshelfViewModel` refreshes progress and action availability. |
| `a1c9996` | Single/bulk new-shelf moves exist. `NewShelfMoveDialog`, `BookshelfView`, `BookshelfRepository` and `BookshelfViewModel` use NFC names for creation, rename, duplicate checks, move targets and published state; `BookRepository.saveShelves` rejects canonical-name collisions before writing sidecars. |
| `21d5546` (transport portion), `bb4e92d` | `GoogleDriveFileTransfer`, `DriveUploadSessions` and `BookTransferScheduler` implement resumable uploads and shared bounded upload/download scheduling; upstream job classification adds no further transport gap. |
| `3f53018`, `e2e0f31` (reload portion), `642ad22` | Bookshelf/statistics repositories dispatch blocking work off main; `StatisticsLifecycleReloader` refreshes on resume. `TtuStatistics.reconciled` and `GoogleDriveSyncManager.mergeBook` remove overlap between legacy totals and sessions. Only selected-period rollover remains open. |
| `a1babc9` (other portions) | The multilingual engine caches Japanese preprocessing definitions; `DictionaryLookupQueryService` atomically replaces sessions after remove/reorder. iOS does not call the newly added native mutation APIs, so they do not create an Android UI gap. |
| `81c60d2` (cover/audio selection), `9e815be` | `AnkiRepository` filters cover/word/sentence audio by template use; `AnkiUiState.popupSettings` propagates reachability into rebuilt popup documents. Only dictionary-media pruning and existing-cover reuse remain open. |
| `3b75b1e` | Android's `SasayakiCueAudioExporter` exports clipped AAC off main; `AacAdtsCueAudioRewriter` repackages encoded samples without a second lossy encode. The SwiftLAME-to-LAME migration does not require an Android codec change. |
| `4969643`, `91af547` | `NakuruResources`/`SasayakiModelStore` retain downloaded models independently of recognizer lifetime; Apple locale reservation is inapplicable. `NakuruTranscriptAligner` already has unique/chapter anchors, bounded tail assignment, exact-match density and timing trim, with fixture tests. |
| `f093cf2`, `d1bb57f` | Swift/C++ string conversion workaround and Apple memory entitlements have no Android counterpart to port. |
| `c9d7ac1`, `f5b69a3`, `3eac2ac`, `57dd306`, `432eeb0`, `c848385`, `f6ed9cd`, `2bfdebb`, `b1fb2b4`, `8e950c5` | iOS version/build/plist maintenance; `2bfdebb` also refreshes the sentence-limit string already represented by Android's VN maximum setting. |

Other changes before the previous checkpoint are covered or have no Android
action. Earlier per-commit assessments remain in this file's Git history.
