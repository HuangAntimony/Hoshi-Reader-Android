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

### 1. Show sub-minute transcription remaining time

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
| `3dfa9ef` | Sub-minute transcription ETA label | 1 |

## Suggested Implementation Order

All remaining slices are independent.

1. Show sub-minute transcription remaining time.

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
| `18072c8` | `ReaderWebView` and `ReaderChrome` hide the status bar for fullscreen images from the gallery or page and restore focus-dependent visibility on dismissal. |
| `f4a7db0` | `reader/reader.css` uses `visibility: hidden` for toggled ruby annotations, preserving geometry; `shared/selection.js` reveals adjacent ruby. Layout-preserving toggle behavior is covered; Android retains its dotted marker. |
| `bc80d6e`, `864deb9` | `DictionarySearchIframe` renders unmatched source text; `shared/selection.js::findParagraph` keeps lookup within `li`. Existing iframe and selection tests cover both. |
| `30b3b19`, `d226044`, `f12897d`, `5a85d4e`, `67237b0`, `d8bba19`, `fb707c7` | Android owns chrome, focus, safe-area and viewport sizing in `ReaderChrome`, `ReaderWebViewChrome`, `ReaderViewportCssLayout` and generated layout; bottom progress disables soft wrapping. SwiftUI/UIKit label, edge-effect and home-button workarounds do not transfer directly. |
| `c2e323b`, `59c6120`, `d310ea2`, `0288a79`, `44c92da`, `0cb196a`, `e8d5ffa` | Paragraph mode follows the `bb4e92d` reader implementation in `reader/reader-paragraph.js`, extending Android pagination with a live DOM and Range reveal. `ReaderAppearanceView` follows its sentence/speed sliders, labels and conditional controls; Android's page-swipe threshold follows the paragraph settings. Sasayaki settings and the playback sheet group page advance under Paragraph Mode. Legacy VN configuration migrates to profile-owned paragraph settings; Android retains optional blank-tap advance and uses CSS to center passages and continuation pages in both axes. Narration following leaves the current reveal running, while document restore still animates; Sasayaki chapter cues and, after TTS is first started, TTS chapter cues are installed before reveal without replacing the inline highlighter. `69ed0d1` page-end overlap handling and page-bounded Sasayaki playback are owned by the service controller with generation-checked Reader callbacks. Page ranges follow iOS crossing-based stopping and never restrict cue following; restore and chapter jumps replace the range from the destination page. |
| `95ce7f5` | `ReaderProgressCount`, `ReaderPageIndex` and Reader-owned measurement/cache provide Off/Characters/Pages, book/TOC chapter pages and contents/search/highlight positions. Android retains estimated English words, character-based durable positions and shared-offset TOC grouping; image pages count separately. JVM/JS and isolated WebView fixtures cover migration, layout invalidation and actual page stops. |
| `6403ae3` | `ReaderContentStyles` clears publisher root max-width/max-height in all modes while preserving descendant limits and continuous insets; publisher writing modes are stripped. |
| `d9f1b72`, `62982b7`, `3d2f77a`, `daf0fd7`, `e596fe1`, `c69f203`, `62e5889`, `1cfbf71`, `ca7e053`, `45ac6e9`, `241c92b`, `1c5d525`, `77eaee3`, `4777715`, `def6cec`, `08bcbb4`, `55988cc` | `SyncStorage`, `GoogleDriveSyncManager`, `GoogleDriveClient`, `BookRepository`, `SyncReloadScheduler` and `SyncAttachPrompt` cover provider separation, sidecars/tombstones, archive covers, shelf recreation, cached versions, coalesced reload, retries, streaming, conflict choices and on-demand downloads. Swift file extraction requires no port. |
| `a8d422c`, `d9f1b72` (book actions) | `BookContextMenu` groups Read/Unread under Mark. `BookRepository` enables Read from cached bookinfo without a local EPUB. Confirmed Unread resets the bookmark and retains session tombstones through `BookStatisticsStore`; `BookshelfViewModel` refreshes progress and action availability. |
| `a1c9996` | Single/bulk new-shelf moves exist. `NewShelfMoveDialog`, `BookshelfView`, `BookshelfRepository` and `BookshelfViewModel` use NFC names for creation, rename, duplicate checks, move targets and published state; `BookRepository.saveShelves` rejects canonical-name collisions before writing sidecars. |
| `21d5546` (queue presentation) | `SyncQueueAccounting` and `SyncQueueView` provide directional byte totals and ETA, pending sizes and covers, retained interruptions and grouped rows. Android shows ETA once size and effective speed are available and keeps the full waiting list. |
| `21d5546` (transport portion), `bb4e92d` | `GoogleDriveFileTransfer`, `DriveUploadSessions` and `BookTransferScheduler` implement resumable uploads and shared bounded upload/download scheduling; upstream job classification adds no further transport gap. |
| `3f53018`, `e2e0f31` (reload portion), `642ad22` | Bookshelf/statistics repositories dispatch blocking work off main; `StatisticsLifecycleReloader` refreshes on resume. `TtuStatistics.reconciled` and `GoogleDriveSyncManager.mergeBook` remove overlap between legacy totals and sessions. |
| `e2e0f31` (selected period) | `StatisticsViewModel` advances the current period on date changes, preserves historical selection, and returns to the current period when reset time changes. `StatisticsChartPager` follows the resulting page selection. |
| `a1babc9` | Engine `55e56e3` accepts quoted integer frequencies in `yomitan_parser::parse_frequency` and caches Japanese preprocessing definitions. `DictionaryLookupQueryService` atomically replaces sessions after remove/reorder. iOS leaves the native mutation APIs unused. |
| `e266954` | Engine `55e56e3` maps the media index when loading a dictionary. `DictionaryQuery::get_media_file_view` maps and caches each requested payload on demand. |
| `81c60d2`, `9e815be` | `AnkiRepository` reads dictionary media referenced by rendered note fields and filters cover and audio by template use. `AnkiConnectBackend` reuses exact content-hashed cover names, with upload as the fallback. `AnkiUiState.popupSettings` propagates template reachability into rebuilt popup documents. |
| `3b75b1e` | Android's `SasayakiCueAudioExporter` exports clipped AAC off main; `AacAdtsCueAudioRewriter` repackages encoded samples without a second lossy encode. The SwiftLAME-to-LAME migration does not require an Android codec change. |
| `4969643`, `91af547` | `NakuruResources`/`SasayakiModelStore` retain downloaded models independently of recognizer lifetime; Apple locale reservation is inapplicable. `NakuruTranscriptAligner` already has unique/chapter anchors, bounded tail assignment, exact-match density and timing trim, with fixture tests. |
| `f093cf2`, `d1bb57f` | Swift/C++ string conversion workaround and Apple memory entitlements have no Android counterpart to port. |
| `c9d7ac1`, `f5b69a3`, `3eac2ac`, `57dd306`, `432eeb0`, `c848385`, `f6ed9cd`, `2bfdebb`, `b1fb2b4`, `8e950c5` | iOS version/build/plist maintenance; `2bfdebb` also refreshes the sentence-limit string already represented by Android's paragraph sentence limit. |

Other changes before the previous checkpoint are covered or have no Android
action. Earlier per-commit assessments remain in this file's Git history.
