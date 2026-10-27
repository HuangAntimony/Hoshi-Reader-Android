# Architecture and Implementation Contracts

Agent entry point for current owners and the constraints their callers must preserve.
For App changes, read the common contracts, then the relevant domain and its code/tests.
Local algorithms, UI specifications and individual regression cases belong there.
Future work belongs in [ARCHITECTURE_REFACTORING.md](ARCHITECTURE_REFACTORING.md);
execution and measurement guidance lives in [VALIDATION.md](VALIDATION.md).

## Find the owner

Kotlin paths below are relative to `app/src/main/java/moe/antimony/hoshi/`.
Web paths are relative to `app/src/main/assets/hoshi-web/`.
Names without an extension are searchable types, not necessarily filenames.

| Task / search terms | Start here | Responsibility |
| --- | --- | --- |
| App startup, dependency injection | `HoshiApplication.kt`, `di/` | Hilt graph, application scope and dispatchers; `HoshiUiDependencies` is a lazy Compose bridge |
| Tabs, back stacks, opening books | `navigation/AppShell.kt`, `navigation/AppRoute.kt`, `navigation/ReaderRouteStateHolder.kt` | Route lifetime, per-tab state, book loading and profile activation |
| Settings loading, retry | `features/settings/SettingsDetailScaffold.kt`, `SettingsLoadState` | Readiness gate shared by pages and embedded settings |
| Settings writes, concurrent edits | `features/reader/ReaderSettings.kt`, `features/sasayaki/SasayakiSettings.kt`, `features/tts/TtsRepository.kt` | Field transformations, persistence transactions and profile write targets |
| Profiles, content language | `profiles/ProfileRepository.kt`, `profiles/ProfileActivationService.kt` | Profile metadata, activation and effective language |
| Theme, E-ink, app surfaces | `features/display/`, `ui/theme/HoshiSurfaceRoles.kt`, `webview/HoshiWebView.kt` | Global display policy, native containers and WebView theme integration |
| Books, import, sidecars | `epub/BookRepository.kt`, `epub/BookStorage.kt`, `importing/` | SAF input, packed EPUB storage and iOS-compatible sidecar I/O |
| EPUB parsing, search text, counts | `epub/EpubBookParser.kt`, `epub/ReaderTextFilter.kt`, `app/src/main/rust/hoshiepub/` (repo root) | Rust/UniFFI parser boundary and Kotlin reader models |
| Bookshelf, covers | `features/bookshelf/`, `BookCoverThumbnailStore` | Shelf UI/repository and persistent cover derivatives |
| Reading statistics | `epub/BookStatisticsStore.kt`, `features/statistics/` | Session transactions, archives, aggregation and dashboard state |
| Reader lifecycle, progress, restore | `features/reader/ReaderWebView.kt`, `features/reader/ReaderChapterWebView.kt`, `features/reader/ReaderWebViewStateHolder.kt` | WebView attachment, document generations, navigation and accepted position |
| Reader commands and resources | `ReaderPaginationScripts.kt`, `ReaderWebAssets.kt`, `ReaderWebResourceBridge.kt` in `features/reader/` | Typed commands, asset composition and safe resource loading |
| Reader layout, VN, highlighting | Web `reader/` (ownership table below) | Mode runtimes and shared text/media semantics |
| Selection, native handles, lookup context | `features/reader/ReaderSelectionBridge.kt`, Web `shared/selection*.js` | Selection state, source offsets and native menu integration |
| Popup stack, history, geometry | `features/reader/ReaderLookupPopupBridge.kt`, Web `popup/reader-popup-host.js`, `popup/popup.js` | Shared iframe lookup host and native service bridge |
| Dictionary import/query | `dictionary/DictionaryRepository.kt`, `dictionary/DictionaryNativeBridge.kt`, `dictionary/DictionaryLookupQueryService.kt` | Native session and repository boundary; algorithms live in the bridge submodule |
| Dictionary UI, updates | `features/dictionary/`, `DictionaryMutationCoordinator` | Retained search session, profile configuration and serialized mutations |
| Lookup submission history | `features/dictionary/DictionaryLookupHistoryRepository.kt` | Global persistent original submissions from Dictionary and external lookup entry points |
| Word audio | `features/audio/LocalAudioRepository.kt`, `features/audio/RemoteWordAudioRepository.kt`, `AudioRequestHandler` | Ordered audio candidates shared by popup playback and Anki |
| Anki cards and media | `features/anki/AnkiRepository.kt`, `features/anki/AnkiBackend.kt` | Backend selection, formats, duplicate checks and export |
| Hoshi/TTU Drive sync | `features/sync/GoogleDriveSyncManager.kt`, `features/sync/GoogleDriveSyncHandler.kt`, `features/sync/GoogleDriveFileTransfer.kt`, `features/sync/TtuSyncManager.kt`, `SyncStorage` | Provider protocols, state merge and background synchronization |
| Backup, cleanup, cover publishing | `features/backup/`, `features/storage/`, `features/wallpaper/` | Repository-owned archive/cleanup operations and platform output targets |
| Audiobook playback | `features/sasayaki/SasayakiPlaybackService.kt`, `SasayakiPlaybackController` | Service-owned Media3 player/session and reader cue callbacks |
| Transcription, subtitle matching | `features/sasayaki/SasayakiTranscriptionCoordinator.kt`, `features/sasayaki/SasayakiTranscriptionRepository.kt`, `features/sasayaki/transcription/` | Checkpointed recognition, alignment and verified native resources |
| TTS source text, speech, playback | `features/tts/TtsBookRepository.kt`, `features/tts/TtsAudioRepository.kt`, `TtsEngineRouter`, `TtsPlaybackController`, `TtsPlaybackService` | Source extraction, synthesis/cache, provider adapters and sentence transport |
| [Errors and diagnostics](#errors-and-diagnostics) | `ui/ErrorDetails.kt`, `ui/ErrorDiagnosticEvents.kt`, `features/diagnostics/NonfatalDiagnostics.kt` | Visible error adaptation, original failure events and application-owned persistence |
| [Network and credentials](#network-and-credential-boundaries) | `network/PrivateHttpHost.kt`, `features/tts/OnlineTtsTransport.kt`, `TtsCredentialCipher` | Endpoint policy, cancellable transport, secret storage and diagnostic redaction |
| App updates, About | `features/update/`, `.github/workflows/` and `tools/` (repo root) | Stable releases, manual previews, APK downloads and build provenance |

## Common implementation contracts

### App and settings boundaries

- One Android `app` module; Compose/Material 3 UI, Hilt DI, coroutine/Flow state.
  Remaining Compose orchestration is tracked in ARCHITECTURE_REFACTORING.
- New dependencies use constructor injection and Hilt modules/qualifiers;
  ViewModels use `@HiltViewModel`, immutable `StateFlow` state and state down /
  events up. `HoshiUiDependencies` resolves the Hilt graph lazily for Compose.
- Repositories own file/database/native/network operations and blocking-work
  dispatcher boundaries. Small global preferences use DataStore behind a
  repository. Persistent scheduled/retryable jobs use WorkManager with Hilt worker injection;
  current workers cover app updates, dictionary updates and Drive sync.
- Books, Dictionary, Statistics and Settings own independent Navigation3 back
  stacks and per-entry ViewModel/saveable stores. Reader and detail routes sit
  outside the shared root chrome. Dictionary's retained WebView stays attached
  but inactive at CREATED lifecycle.
  Each stack uses `rememberSaveableStateHolderNavEntryDecorator()` and
  `rememberViewModelStoreNavEntryDecorator()` with typed route keys.
- Sync settings and its transfer queue are separate Settings-stack routes.
  The queue observes application-owned transfer state and uses the regular page
  scaffold; leaving it does not stop synchronization.
- Persisted controls require a confirmed `SettingsLoadState.Loaded`; pages use
  `SettingsDetailScaffold`, embedded controls use `SettingsContent`. Profile
  switches reset readiness and reject late results from the previous profile.
  All required sources must load; show Loading or a retryable error beforehand.
  Actual values confirmed by an upstream owner may be wrapped as Loaded directly.
  `StaticSettingsDetailScaffold` serves static menus/actions.
- `ProfileRepository` owns profile identity. A book resolves forced profile,
  then EPUB-language primary profile, then global active profile.
- Reading appearance, dictionary and Anki settings are profile-owned JSON.
  Reader behavior, theme/E-ink, statistics goals and TTS preferences are global.
  `ReaderSettingsRepository` combines display and profile reading state;
  `ReaderSettingsHostViewModel` gates both Activity hosts on confirmed settings.
- Controls submit field transformations against the latest repository state.
  TTS/Sasayaki repositories apply them to the latest state inside DataStore edits.
  Reader profile writes capture the target profile before suspension and keep
  that target through initialization/read/write under the profile settings lock.
  Shared TTS reading controls preserve audiobook-specific preferences.
- `AppDisplaySettingsRepository` owns display persistence; `resolveDisplaySettings`
  resolves it with `SystemThemeObserver`'s Application-level system brightness.
  Activity `uiMode` contains the resolved app theme.
  `HoshiSurfaceRoles` owns native container colors/outlines; reader content and
  dictionary HTML keep their separate styling paths.

### UI and localization contracts

- App-authored visible text uses resources in both `values/strings.xml` and
  `values-zh-rCN/strings.xml`, following [TRANSLATING.md](TRANSLATING.md).
  Compose uses `stringResource`/`pluralStringResource`;
  non-UI messages use `UiText` or an equivalent resource reference, without
  retaining a `Context`. Original external diagnostics retain their actual text.
- New/replacement icons use Material Icons, Material vector assets or established
  brand assets.
- Accessibility-only work (TalkBack, screen-reader semantics, announcements and
  focus/traversal adaptations) requires an explicit user request.
  Existing accessibility-only code may be removed after checking ordinary input
  and UI behavior are unaffected.

### Errors and diagnostics

This contract applies across repositories, backends, controllers, workers and UI.
Typed categories select recovery alongside the original failure.

| Boundary | Required behavior / owner |
| --- | --- |
| Failure → visible state | `ui/ErrorDetails.kt`: localized operation/category plus original nonempty messages and cause chain; missing messages fall back to the original type or actual platform/service code. Dialogs, Snackbar and retry states retain these details for terminal/manual failures. Batch results retain each object's name and reason. |
| Throwable → runtime history | `withErrorDetails` formats and enqueues the original Throwable. If a boundary converts it to a string/enum/batch result first, call `ErrorDiagnosticEvents.buffer.record(operation, originalError)` there. Background failure/retry paths also record the original failure. |
| Service response → runtime history | With no Throwable, `recordServiceFailure` records the actual platform/service response details. |
| Event → persistent history | `NonfatalDiagnosticsLogger`, started by `HoshiApplication`, drains `ErrorDiagnosticBuffer` on the injected IO dispatcher in application scope. `NonfatalDiagnosticsStore` serializes and atomically replaces bounded `diagnostics/handled-errors.json` history. UI error formatting does no disk I/O. |

- `CancellationException` propagates to the caller. Runtime history records
  operational failures; live form validation uses pure `diagnosticMessage()`
  formatting with `UiText`. Successful optional/cache fallbacks and explicitly
  defined automatic offline suppression may stay quiet.
- The buffer deduplicates the same Throwable by weak identity; separate retry
  failures remain separate. Snapshots flush pending events. A drained batch
  finishes its bounded writes even if the requesting screen is cancelled.
- Logger failures are isolated from the business error and event buffer.
  Corrupt history JSON keeps a damaged-file backup and its parse failure in the
  rebuilt log; other read failures must not overwrite the source history.
- Error dialogs/details expose diagnostics to the user. The producing boundary
  removes credentials/auth headers before display or recording; `ErrorDetails`
  formats the supplied failure.
  Diagnostics reports combine handled errors with crash/process-exit history;
  sharing uses FileProvider-backed files.

Validation guidance and failure injection methods are in
[VALIDATION.md](VALIDATION.md#test-and-fixture-pitfalls).

## Storage and native boundaries

- Books live in app-specific `Books/` folders with a packed EPUB and sidecar JSON.
  SAF supplies imports. `BookRepository` owns import/extraction/I/O; parsing
  extracts only into controlled cache/temp locations. Metadata keeps the full
  title independently of its byte-bounded physical filename.
- `EpubBookParser` resolves unsupported spine items through cycle-checked manifest
  fallbacks to HTML. Chapter resources, reader facts and media use the resolved
  href; the original href remains a navigation alias. Rebuilt reader facts restore
  bookmarks from character counts. Rust exposes explicit cover candidates in
  EPUB 3 `cover-image` then EPUB 2 `meta name="cover"` order; Kotlin validates
  local resources and bitmap dimensions, trying later declarations when a
  preferred cover is unusable. The existing cover-ID heuristic applies only
  without explicit candidates; body illustrations are never promoted to covers.
  Parsing unwraps only lossless single-raster SVG covers and preserves complex
  SVGs when no declared bitmap is usable.
  On opening, `BookRepository.restoreCoverIfNeeded` reuses the parsed EPUB
  to replace missing/SVG covers with a local bitmap and marks only the cover for
  sync. Publication finishes across cancellation; repair failures stay nonfatal
  and retry on the next opening. Normal covers, books without a usable bitmap
  cover and Sasayaki matches remain unchanged; there is no library-wide migration or remote orphan cleanup.
- `BookStorageLock` serializes atomic sidecar/statistics transactions.
  `BookWorkRegistry` joins active book work before deletion or generation
  replacement, before acquiring that lock.
  Book discovery rechecks each directory and completes its legacy metadata/EPUB
  migration under the same storage lock; an earlier directory snapshot must not
  recreate or return a book that has since been deleted.
- Bookshelf loading reuses the migrated `BookEntry` snapshot for shelf membership
  instead of rereading every book's metadata. `SyncStorage.prepareLibrary` prepares
  only missing live bookkeeping and checks empty archives; live-book sessions are loaded/migrated
  when needed by sync/statistics, not eagerly under the startup storage lock.
  A live directory with a deleted sync record is marked pending during preparation
  so the next sync reapplies its state even when the remote version is cached.
- `BookshelfViewModel` owns import progress from folder scanning through the final
  shelf load. Background reloads cannot clear or replace that progress; local/sync
  notifications during import are coalesced into the final refresh, with changes
  arriving during that load refreshed afterward. Cloud transfers continue normally.
- `BookStatisticsStore` is the shared owner for reader sessions, edits, sync and
  archive/restore. UUID-keyed nullable records retain deletions; daily totals
  derive from sessions and reset time. `Books/statistics_archive/` participates
  in Books backups but not ordinary book discovery or TTU exports.
- Sidecars preserve iOS contracts. Bookmark dates use the Apple reference epoch;
  sync/session timestamps use Unix milliseconds. Provider conversion belongs
  at the sync boundary. JSON uses Kotlin Serialization or Moshi. Compatibility
  migrations protect released user data; unreleased features use current formats
  and interactions.
- Dictionaries are global under `Dictionaries/`; profile config/settings live
  under `Profiles/<profileId>/`. Enabled dictionary membership is independent
  of Anki categories: `exclude` removes aggregate Anki definitions, not lookup.
- `DictionaryLookupQueryService` atomically replaces the active native session.
  Import, term/Kanji query, deinflection, frequency, pitch, styles and media are
  owned by `third_party/hoshidicts-kotlin-bridge`; keep the parent's Kotlin ABI
  copy compatible. UI accesses them through `DictionaryNativeBridge`/repository APIs.
- Dictionary backup retains the legacy root shape and profile metadata in
  `.hoshi-profiles/`, preserving unrelated profile settings.
- `app/build.gradle.kts` wires two independent bundled native stacks: CMake/JNI
  hoshidicts and Rust/UniFFI EPUB parsing. UniFFI keeps `android = true`;
  `cargo-ndk` builds library targets, while bindgen runs on the host. Android
  uses the JNA AAR, JVM tests the JNA jar. SDK/NDK/CMake/ABI values come from the
  build file and workflows.
- Nakuru and the FFmpeg decoder are separately downloaded, hash-verified native
  resources under `noBackupFilesDir`. Catalogs
  live in assets, component build/publication tools in `tools/`. Native files
  are marked read-only before writing and atomically published after verification.

## Network and credential boundaries

- `network/PrivateHttpHost.kt` is shared by AnkiConnect URL validation and
  `TtsCustomSource`: internet hosts require HTTPS; HTTP is limited to the accepted
  private/local hosts. Custom TTS retains proxy path prefixes and rejects embedded
  credentials/query/fragment in its base URL.
- `OnlineTtsTransport` disables redirects, uses normal TLS verification and
  cancels the active HTTP call/socket with coroutine cancellation. It redacts
  request credentials from service/transport diagnostics before errors leave
  the transport. Only complete synthesis responses enter the audio pipeline.
- `TtsCredentialCipher` binds Keystore ciphertext to a source/profile ID. Stored
  keys remain ciphertext in settings; UI drafts expose keep/remove/replace actions.
  `TtsEngineRouter` uses the selected provider consistently
  for Reader, preview and Anki, including failure recovery.

## Reader web ownership

Reading and lookup remain WebView-based. The three reading modes share semantics
while retaining their navigation and rendering mechanics. Kotlin composes assets
and sends typed commands; durable JavaScript/CSS stays in assets. Local resources
use the existing safe loader.
`allowUniversalAccessFromFileURLs` stays disabled.
Kotlin retains only small typed commands, parameter escaping, asset loading,
dynamic configuration and bridge calls.

| Web asset under `reader/` | Owns | Consumers |
| --- | --- | --- |
| `reader-text-semantics.js` | Normalization, raw/matchable counts and character predicate, Sasayaki punctuation ownership and search range semantics | All three modes |
| `reader-dom-text.js` | Live DOM ruby normalization, selected body-text slices and Sasayaki structural boundaries | Paginated/continuous normalization; selection and Sasayaki adapters across modes |
| `reader-media-semantics.js` | Image/gaiji setup, SVG ratio, blur/tint, tap bridge, scoped loading and image-hold eligibility | All three modes |
| `reader-layout-semantics.js` | Publisher inline-block/empty-strut sanitation, pure-image wrapper centering and overflowing image paragraph margins | All three modes before offsets/restore; VN also sanitizes displayed clones after media setup |
| `reader-document-preparation.js` | Defer initial publisher layout until reader viewport/styles are installed | Chapter WebView document-start hook; all modes resume before content geometry/font readiness |
| `reader-paginated.js` | Columns, snapping, page metrics and page restore | Paginated |
| `reader-continuous.js` | Inset body scrolling, viewport bounds and scroll restore | Continuous |
| `reader-visual-novel.js` | Block/sentence screens, fitting, reveal, cross-screen cue merge and navigation | VN |
| `reader-vn-content-stream.js` | Complete source text, ruby entries, raw/matchable offsets and standalone media | VN |
| `reader-vn-range-map.js` | Source-to-clone offsets and rendered highlight/cue ranges | VN |
| `reader-vn-selection-projection.js` | Source sentence/lookup context and visible clone projection | VN |
| `reader-sasayaki.js`, `highlights.js` | Cue presentation and persistent/search highlight operations, with VN projection adapters | Reader modes through their integrations |

- Raw code-point offsets, normalized matchable offsets and DOM UTF-16 positions
  are different coordinate systems. Convert through the owning index/projection.
- VN's detached content stream caches media units for the source snapshot;
  consumers treat the list and descriptors as read-only. Source changes require
  `rebuild()`, which invalidates media discovery alongside text indexes.
- VN lookup/mining uses complete source sentences; the current clone supplies
  hit testing, visible ranges and anchors only. Paginated/continuous do not
  consume VN stream/range-map instances. New sharing requires equivalent
  semantics and tested production consumers.
  VN image setup may scope to the current screen without waiting for image load,
  through the shared media owner. Text, media classification and clone mapping
  stay with the owners above.
  Connecting other mode runtimes to VN stream/range-map instances requires an
  explicit design and validation plan first.
- `EpubBook.imageReferenceCounts` lazily caches original image references across distinct linear, non-navigation spine documents for that book snapshot; first access runs on a resource/loading worker. `ReaderWebResourceBridge` serves the counts at `/reader-media/image-references.json` before Reader runtime initialization. Shared media semantics suppress holds at three references without changing layout; VN keeps media screens and filters their hold eligibility. TTS source extraction consumes the same counts, including background and cross-chapter barriers. Unknown counts preserve holds.
- `ReaderWebViewStateHolder` owns document/restore generations. Stale bridge
  callbacks cannot alter a replacement document. Renderer recovery preserves
  the latest accepted position and rebuilds WebView-local state.
- `HoshiReaderWebView` installs one document-start preparation script when supported
  and removes it on release. It only defers top-level `/epub/` documents at the
  appassets origin, preserving the publisher's inline display property. Mode
  initialization resumes layout before measuring content geometry or awaiting fonts;
  the existing restore/visual-state gate still controls visibility. Unsupported
  WebViews retain the ordinary initialization path.
- DOM selection state owns the navigation lock. `blocked` is distinct from a
  chapter-boundary `limit` result.
- Reader, Dictionary and Process Text use the same iframe popup host and native
  service bridge. `popup.js`'s `hoshiPopupGeometry` owns CSS-zoom coordinates.
  It converts between unscaled layout coordinates and visual scroll/viewport
  coordinates. Theme-only changes preserve popup DOM/history.
- `DictionarySearchSession` retains search/popup navigation history across tabs.
  Successful book opening resets it before book-profile activation; failed opening
  leaves it. Clearing the Dictionary input preserves displayed lookup content and
  popup navigation; it does not reset the search session. Dictionary and external
  lookup retain tappable source text even when a successful query has no matches.
- `DictionaryLookupHistoryRepository` separately owns global original-submission
  history in `dictionary-lookup-history` DataStore. Only explicit Dictionary
  submissions and fresh external lookup requests write it; Reader lookup, popup
  selection/redirects and recreation/profile refreshes do not. Application-scoped
  serialized operations retain the latest 100 exact, trimmed texts and publish
  only persisted changes. Resetting a search or opening a book preserves this
  history; only the empty Dictionary page displays it. Storage failures retain
  confirmed entries and report original diagnostics without blocking lookup.

## Integration boundaries

| Area | Contract to preserve |
| --- | --- |
| Anki | All mining/media/duplicates go through `AnkiRepository` and its backend. Stable format IDs cross popup bridges. `{sasayaki-audio}` is audiobook-only; `{sentence-audio}` uses an associated audiobook cue or, without an audiobook, enabled TTS. A missing audiobook cue must not silently fall back to TTS. |
| Sync | Hoshi and TTU retain distinct provider protocols behind the shared transport/settings. `SyncStorage` applies atomic sidecar merges; Android-only profile/language/audio URI fields stay local. |
| Hoshi Drive state | State documents reference immutable generation files. Deletion wins within a generation; session deletions survive generations. Unknown versions block cursor advancement. Background work completes only after uploaded references are published. |
| Hoshi metadata failures | `GoogleDriveClient` retries replay-safe Hoshi metadata requests after I/O failures with at most four exponential backoffs (1/2/4/8 seconds plus jitter), releasing the HTTP slot before waiting. Ambiguous POST creates are reconciled by the next sync pass, never blindly replayed. Exhausted metadata network failures stay on the affected book/pass and preserve pending state/cursors without cancelling healthy payloads; authentication failures stop the transfer run, as do explicit stop/reset and format failures. TTU and payload retry policies are separate. |
| Hoshi file transport | `GoogleDriveFileTransfer` owns cancellable OkHttp payload I/O and resumable uploads; metadata and TTU stay on `GoogleDriveClient`. `DriveUploadSessions` atomically persists local-only session capabilities in `noBackupFilesDir`, never diagnostics or backups. Resume queries the server offset; completed responses survive until local file references are saved. Payload requests start at 4 MiB and adapt from confirmed bytes / full request duration toward 30 seconds, aligned to 256 KiB within 256 KiB–8 MiB; network failures reduce the next chunk. Source versions, connection resets and removed books invalidate sessions. |
| Hoshi transfer queue | `BookTransferScheduler` shares the configured 1–8 book slots across uploads, queued downloads and priority reader downloads; the default is 8 and saved choices take precedence. Reductions drain existing work. `SyncTransferTracker` accepts identity-checked byte callbacks, uses a monotonic clock, and the manager publishes every 500 ms only while active. UI progress and effective speed are per file: useful progress since this attempt’s starting offset / elapsed payload time, including confirmation and retry waits, excluding preparation and duplicate retransmitted bytes. In-flight progress is provisional and can roll back on recovery; 60 seconds without payload or acknowledgement progress yields zero. Upload/download totals sum active file rates. The file queue is built after the state pass; an empty queue only claims completion after a successful state check, and otherwise shows checking, interrupted or not-yet-checked status. |
| Authentication | Hoshi uses native Google authorization or browser PKCE; TTU keeps device-code auth. Browser tokens stay under `noBackupFilesDir`; credentials and token bodies must not enter diagnostics. Build configuration is in VALIDATION. |
| Reader sync | Flush pending local saves before reconciliation; suppress write-back during remote restore. `SyncStorage` reports audiobook changes against the persisted position/delay/rate under the storage lock; local snapshot echoes and timestamp-only updates must not seek live playback. Reader highlights refresh only when visible records change; reading back local snapshots or timestamps must not overwrite pending highlight edits in the UI. Remote deletion/replacement closes the reader after statistics/audio cleanup. |
| Backup restore | `BackupSettingsView` coordinates `HoshiBackupRepository` with sync: stop sync before Books replacement, reset bookkeeping on success, disable sync on failure before restarting the manager. |
| Playback | Sasayaki and TTS services own their Media3 player/session. Media3 manages foreground-service and notification lifecycle; controls use that session. Reader supplies presentation callbacks and explicit exit/stop. Playback and its notification start on explicit audio interaction. The Sasayaki process-local connection remains a documented refactor target. |
| Reader presentation | `ReaderPlaybackPresentation` owns chapter/media traversal shared by Sasayaki/TTS. Reader interaction cancels pending presentation for both; TTS presentation is a child of playback, so abandoning an image/restore resumes speech while pausing playback cancels both. Late Reader callbacks require the presentation to remain active. TTS document restoration only highlights without navigating or revealing; playback and foreground resume may follow the sentence. Image holds require auto-scroll, including TTS background fallback. `ReaderLookupPauseState` owns lookup interruption/resume across popup replacement. |
| Transcription | `SasayakiTranscriptionCoordinator` serializes recognition, checkpoints and matching. Live matches stay in memory and take precedence over synced matches in the active reader. Completion, pause and failure finalize the latest successful match under the storage lock; only changed persisted matches notify sync. Deletion/replacement joins the task and suppresses final writes. Its work is process-bound; the reader-route VM pauses/saves on disposal. Process death retains the previous persisted match and transcript checkpoints for resumption and realignment. |
| TTS text | `TtsBookRepository` extracts full chapter source. `TtsText`/dialogue/replacement transforms preserve source offsets and original Anki text. Chapter-wide paired outer Japanese quotes create separate playback units alongside existing sentence/block boundaries; nested quotes and ruby spans remain intact. All units share navigation, prefetch and sentence-pause behavior. Replacements operate per playback unit; full selections/Anki exports retain their selected text across units. Synthesis/cache is owned by `TtsAudioRepository`; distinct playback requests share a two-slot budget, identical audio identities serialize through reference-counted key locks, and cache clearing drains writers while a generation/barrier rejects obsolete work. |
| TTS transport | `TtsPlaybackController` queues ready units through `TtsPlaybackOutput` into the service-owned Media3 playlist, including ordinary chapter boundaries. Actual media transitions select the current unit; asynchronous Reader highlighting/following does not gate ordinary audio. Explicit sentence pauses and image holds remain barriers; `TtsBookRepository` retains trailing images and detects image-only intervening chapters. Queue exhaustion appends to the prepared player; manual navigation resets playback. Settings changes discard future units after reconciling the actual current token, and suspended prefetch rechecks its epoch and live queue tail before appending. Prefetch uses a bounded two-request pipeline within the configured sentence horizon, publishes results in source order without waiting for farther chapter discovery, and continues caching later units after a failed unit without skipping it in playback. |
| TTS providers | `TtsEngineRouter` dispatches installed, online and custom engines. Voice discovery is separate from synthesis; playback speed/prefetch/delay are separate from synthesis identity. Online/custom synthesis permits two concurrent calls; device TTS retains its engine serialization. Playback synthesis retries transient network/timeout/rate-limit failures at most twice with 500/1000 ms backoff and any longer server `Retry-After`, releasing request permits during waits; cancellation and permanent errors are not retried. A fully read HTTP 200 synthesis response with zero audio bytes contributes no file across playback, preview and export; Google envelopes must parse successfully before this check. Partial nonempty audio, malformed payloads, empty authorization/catalog responses and empty responses with other status codes remain errors; Edge and device TTS retain their existing completion rules. Empty file lists use the existing silent-unit playback traversal, while all-empty preview/export fails with `EmptyText`. Background failures retain diagnostic history. Disabling TTS cancels work without deleting configuration. |
| Updates | Stable releases and manual previews have separate records/downloads. Preview identity is the full commit SHA. Only the confirmed foreground About session may auto-open its installer. Component publication is independent of APK releases. |

## Maintaining this map

Document ownership and update rules live in [AGENTS.md](../AGENTS.md#文档维护规则).
