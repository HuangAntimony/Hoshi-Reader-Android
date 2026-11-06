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
| Reader layout, paragraph mode, highlighting | Web `reader/` (ownership table below) | Mode runtimes and shared text/media semantics |
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
| WebView debugging, release inspection | `features/diagnostics/WebViewDebuggingSettings.kt`, `features/diagnostics/WebViewDebuggingSetting.kt` | App-local persisted debugging policy and the Diagnostics toggle |
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
  `ContentLanguageProfile` supports `ja`, `en`, `zh` and `ko`; Chinese region/script
  tags resolve to the single `zh` profile. Content language controls dictionary
  sessions, input locale, typography and shared selection policies independently
  of the app UI language. Chinese and Korean scan from the tapped codepoint,
  while English rewinds to the word start. The legacy `scanNonJapaneseText` key
  controls scanning outside the current Japanese/Chinese/Korean character ranges;
  explicit dictionary searches remain unrestricted. Reader and popup asset loaders
  must supply the same language policy, including externally loaded iframe scripts.
  Load language policies before calling selection `configure`, which binds the
  boundary functions using the profile's canonical language ID. Character scanning
  extracts each codepoint once and reuses its UTF-16 width; it does not normalize
  language tags or reselect boundary implementations. `SelectionLanguageAssets`
  shares lazy language-script reads between reader documents and popup resource routes.
- Reading appearance, dictionary and Anki settings are profile-owned JSON.
  Reader behavior, theme/E-ink, statistics goals and TTS preferences are global;
  TTS voice routes are partitioned by content language and source, shared by profiles of the same language.
  `ReaderSettingsRepository` combines display and profile reading state;
  `ReaderSettingsHostViewModel` gates both Activity hosts on confirmed settings.
  Statistics reads the repository's dedicated global reset-time flow, without
  loading profile appearance or display settings for each book/session operation.
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
- `AppLanguage` uses `AppCompatDelegate` for the global app language on API 26+;
  AndroidX auto-storage owns pre-33 persistence and framework migration. Keep
  language preferences out of DataStore and Profiles. Locale changes use normal
  Activity recreation; consumed launch requests must not replay after restore.
  `ui/AppLanguageResources.kt` supplies locale-aware resources outside AppCompat
  Activities; `UiText.resolve(context)` uses it too. Resolve background text at
  generation time instead of caching a localized Context. Media notifications
  adopt the language on normal generation/update without restarting playback;
  cached session buttons refresh when regenerated. Existing download requests
  and historical diagnostics retain their original text.
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
- `ProcessExitDiagnosticsReport` supplies mutually exclusive crash and other-exit
  categories to the screen and exports. Local captures and Android Java/native
  crash entries belong to crash history, preserving each original source record;
  all other exit reasons stay in process-exit history. `NativeCrashTrace` decodes
  API 31+ tombstone protobuf into the original signal, abort message, causes and
  crashing-thread frames. Trace reads
  are bounded; missing or malformed traces never remove the crash event.

- `WebViewDebuggingSettings` owns the app-local Diagnostics toggle and applies
  `WebView.setWebContentsDebuggingEnabled` on the main thread, including to existing
  WebViews without reloading documents. Release defaults off; debug builds retain
  automatic debugging until explicitly disabled. Application startup restores the
  saved choice without initializing WebView for a disabled release setting.
  The switch only shows confirmed state; failed writes restore the previous
  runtime choice and report the error. This setting is independent of profiles.

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
  Directory ordering snapshots modification times before sorting; comparators
  must not reread timestamps that concurrent sidecar writes can change.
- Bookshelf loading reuses the migrated `BookEntry` snapshot for shelf membership
  instead of rereading every book's metadata. `SyncStorage.prepareLibrary` prepares
  only missing live bookkeeping and checks empty archives; live-book sessions are loaded/migrated
  when needed by sync/statistics, not eagerly under the startup storage lock.
  A live directory with a deleted sync record is marked pending during preparation
  so the next sync reapplies its state even when the remote version is cached.
  Sync directory lookup checks the normalized exact path first, enumerating the
  library only for legacy Unicode-equivalent names that need fallback resolution.
- Shelf identity uses NFC (`syncKey`): trim new name input, then use the canonical
  name for duplicate checks, creation, rename, move targets and published UI state.
  Existing identity lookups normalize without trimming stored names.
  `BookRepository.saveShelves` rejects canonical-name collisions before sidecar
  writes, preserving shelf order and book memberships when validation fails.
- `BookshelfViewModel` owns import progress from folder scanning through the final
  shelf load. Background reloads cannot clear or replace that progress; local/sync
  notifications during import are coalesced into the final refresh, with changes
  arriving during that load refreshed afterward. Cloud transfers continue normally.
  A final shelf-load failure is reported as a shelf refresh error independently
  of per-book import results.
- Bookshelf progress and read/unread capabilities come from `BookRepository`'s
  persisted reading state, including cached bookinfo on cloud placeholders.
  Corrupt statistics leave other shelf state available with diagnostics; resets
  fail without replacing unreadable statistics. Read/unread actions join the
  reader route's registered pending saves before acquiring the storage lock.
  Mark Unread resets the bookmark and tombstones live/coalesced archived sessions
  through `BookStatisticsStore` under the shared storage lock; once writes begin,
  both sidecars finish across caller cancellation. Shelf reloads and progress
  refreshes publish updated action availability together with progress.
  Opening updates cached recency immediately. Returning refreshes only that
  book's metadata, reading state and cover after joining registered Reader saves;
  return results are versioned per book, so opening another book does not discard
  a pending update. Reopening the same book rejects its older snapshot, and
  concurrent full loads wait for all pending return reads before superseding them.
  Sync changes received during Reader remain dirty and trigger a full load after the single
  book is published. Local and remote load generations are independent, so a
  Reader round trip does not discard pending remote results. Stored sort
  preferences reorder the loaded snapshot without another library read.
- `BookStatisticsStore` is the shared owner for reader sessions, edits, sync and
  archive/restore. UUID-keyed nullable records retain deletions; daily totals
  derive from sessions and reset time. `Books/statistics_archive/` participates
  in Books backups but not ordinary book discovery or TTU exports.
  `ReaderStatisticsTracker` rolls sessions at the configured statistics-day
  boundary and queues completed segments through the reader's serialized saves.
  Pending segments remain in totals and save snapshots until store readback
  confirms their values or deletions; late readback must not drop unsaved segments.
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
| `reader-text-semantics.js` | Normalization, raw/matchable counts and character predicate, Sasayaki punctuation ownership and search range semantics | Paginated (including paragraph) and continuous |
| `reader-dom-text.js` | Live DOM ruby normalization, selected body-text slices and Sasayaki structural boundaries | Paginated/continuous normalization; selection and Sasayaki adapters across modes |
| `reader-media-semantics.js` | Image/gaiji setup, SVG ratio, blur/tint, tap bridge, scoped loading, asynchronous image-reference counts and image-hold eligibility | Paginated (including paragraph) and continuous |
| `reader-document-preparation.js` | Defer initial publisher layout until reader viewport/styles and ruby/paragraph DOM are prepared | Chapter WebView document-start hook; all modes resume before content geometry/font readiness |
| `reader-paginated.js` | Columns, snapping, page metrics and page restore | Paginated |
| `reader-continuous.js` | Inset body scrolling, viewport bounds and scroll restore | Continuous |
| `reader-paragraph.js` | iOS paragraph splitting/centering, cross-column fragments, Range reveal and current-page cue IDs | Paginated when paragraph mode is enabled |
| `reader-sasayaki.js`, `highlights.js` | Live-DOM cue presentation and persistent/search highlight operations | Both modes |

### Prohibited narration-highlighting changes

- **Do not change the TTS/Sasayaki highlighting implementation without explicit
  user approval.** Permission to fix animation, migrate paragraph mode or refactor
  Reader does not authorize replacing its highlighting renderer. Preserve the
  established inline cue wrappers for normal text/background coloring and the
  existing E-ink geometry overlay.
- **Do not replace TTS/Sasayaki coloring with CSS Custom Highlights
  (`CSS.highlights` / `::highlight`), including in paragraph mode.** Android
  WebView can clip the painted edges of vertical Japanese glyphs through this
  path. The regression was reproduced with complete cue text, unchanged DOM,
  unchanged Range rectangles and unchanged page position: removing the Custom
  Highlight restored the missing strokes; reapplying it clipped them again.
  Correct ranges, stable layout and visible highlight pixels therefore do not
  establish rendering correctness. See the
  [glyph comparison procedure](VALIDATION.md#narration-highlight-glyph-integrity).
- Range objects used for offsets/geometry and the existing paragraph reveal
  mechanism do not authorize reusing Custom Highlights for narration coloring.
  Fix animation conflicts at the animation/DOM-mutation boundary; do not trade
  away intact glyphs or silently introduce another renderer as a workaround.

### Reader runtime contracts

- `ReaderContentStyles` owns root viewport sizing in both modes, clearing
  publisher `max-width`/`max-height` on `html` and `body` while retaining
  descendant size limits and the continuous body's reader-configured insets.
- Raw code-point offsets, normalized matchable offsets and DOM UTF-16 positions
  are different coordinate systems. Convert through the live DOM text index.
- Reader initialization starts image-reference counts alongside image setup and
  DOM preparation. Ruby normalization and paragraph sentence/block preparation run
  before document preparation resumes display, avoiding layout of the unprepared
  chapter. Then await fonts/images before measuring cross-column fragments and
  building character indexes; image counts must settle before cues/highlights and
  position restore so media holds use the correct counts. Unknown counts preserve
  ordinary holds. No counts request blocks structural DOM preparation.
- Paragraph mode extends the ordinary paginated DOM: split only inside paragraphs,
  center blocks and mark cross-column fragments. Vertical
  Android pagination uses Y/page-height; horizontal pagination uses X/page-width.
  Paragraphs and continuation fragments use the same page-sized CSS block box,
  `align-content`, intrinsic inline sizing and automatic inline margins to center
  content in both axes, retaining the common starting edge of explicit lines.
  Fragment wrappers provide per-page layout containers; Android does not require
  the iOS selection workaround that originally motivated them. Spacing-only
  paragraphs keep their anchors in flow without occupying page space.
  Continuous/ordinary pagination do not run paragraph preprocessing.
- Paginated `paginationMetrics` retains text-node bounds in content coordinates
  and document order. Progress and paragraph reveal reuse that same layout pass;
  progress measures individual characters only for nodes crossing the viewport
  boundary. Punctuation-only nodes participate in reveal, while ruby annotations
  stay outside the body-text index. `buildNodeOffsets()` invalidates the metrics
  after cue/highlight wrapping or unwrapping; content reflow replaces the document.
- Inline narration cue spans reserve their paint boxes with a transparent outline
  during initial layout. Blink otherwise creates a box on the first background
  highlight and invalidates the containing layout, making each new cue reflow a
  long chapter (see Blink's [inline box creation](https://chromium.googlesource.com/chromium/src.git/+/de2d3a5fc72ebf0c3f84c8c5aff4088112812165/third_party/blink/renderer/core/layout/layout_inline.cc)).
  The outline takes no layout space; active foreground/background
  coloring still uses the existing span classes, and E-ink still uses its overlay.
- Paragraph reveal uses a Custom Highlight Range and elapsed-time animation frames;
  its clock starts at the first frame timestamp. Native page-turn commands return
  the new character progress in the same JS task, before reveal frames paint;
  a delayed second progress query must not interrupt the reveal.
  Gaiji images and generated fallback spans toggle visibility at the same text
  boundary; their layout space and character offsets stay unchanged. Completing
  or cancelling reveal restores all pending gaiji. Frames never split nodes or
  change text. Restore (including previous chapter) and forward page turns animate;
  same-chapter backward turns show complete text.
  Like iOS, same-document narration following neither starts nor finishes an
  existing reveal: its Range remains on the original page, so following to another
  page shows that destination's text in full. Automatic cross-chapter restores
  still animate. Paragraph Sasayaki cues must be
  loaded before document setup. TTS chapter cues are prepared only after this
  reading session has actually started TTS, then installed before following or
  restoring the next document; enabling the TTS preference alone adds no work.
  Both reuse the existing inline highlighter with chapter targets installed before
  reveal. Paragraph following never lazily wraps missing cues. Cue-source changes
  finish the old reveal before replacing nodes; ordinary cue changes do not.
  Paragraph E-ink changes reload the chapter at the displayed character position
  through the existing restore gate. Do not convert the chapter's cue DOM during
  appearance refresh: saved highlights can invalidate cached text segments, and
  bulk wrapping with existing live geometry ranges can stall WebView. Restore
  persistent highlights before building narration cues so their node splitting
  cannot distort the initial E-ink ranges.
  The first tap during reveal only completes it. Native and DOM callbacks share
  one decision per tap, so a delayed click cannot finish a new page's reveal.
  Afterwards lookup/media/link/popup operations retain priority; optional blank tap
  calls the same native navigation entry as swipe/keys. Native selection and restore
  locks apply to every entry.
- `ReaderParagraphSettingsMigration` converts legacy VN preferences and profile JSON
  before enum decoding, including display migration/backup reads. New fields win,
  only new fields are written, and blank-tap false is preserved. Paragraph layout
  fields reload content; animation/speed/blank-tap updates do not. Character-based
  bookmarks, highlights and sync formats are unchanged.
- `EpubBook.imageReferenceCounts` lazily caches original image references across
  distinct linear, non-navigation spine documents for that book snapshot; first
  access runs on a resource/loading worker. `ReaderWebResourceBridge` serves the
  counts at `/reader-media/image-references.json`. Shared media semantics start
  loading them during Reader initialization and wait for completion before
  cues/highlights and position restore. They suppress holds at three references
  without changing layout. TTS source extraction consumes the same counts,
  including background and cross-chapter barriers. Unknown counts preserve holds.
- `ReaderWebViewStateHolder` owns document/restore generations. Stale bridge
  callbacks cannot alter a replacement document. Renderer recovery preserves
  the latest accepted position and rebuilds WebView-local state.
- Reader navigation callbacks passed through Compose use lambdas when invoking
  local functions that capture changing values. Local function references can
  compare equal despite different captures, leaving `rememberUpdatedState` with
  a pre-load null statistics tracker or outdated settings.
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
- Ordinary Dictionary/Process Text queries ensure the existing native session is
  ready; dictionary mutations rebuild it and profile identity changes invalidate it.
  `DictionaryRepository` captures one profile and its dictionary configuration for
  each rebuild, publishes that same identity with the ready session, and retries if
  the active profile changes during initialization. Each committed recommended
  import invalidates readiness and notifies the mutation coordinator, including when
  a later download or configuration write fails. Rebuild failures leave readiness
  invalid so the next query retries; unchanged queries reuse the existing session.
  `DictionaryLookupQueryService` caches dictionary styles with the committed session
  under the same query lock. Failed rebuilds preserve the previous session and styles.
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
| Anki | All mining/media/duplicates go through `AnkiRepository` and its backend. `AnkiDroidBackendFactory` binds each operation to the profile-selected AnkiDroid or kuma3 client; its package, provider authority, database permission, media grants and intents must agree. Provider-returned note URIs contribute only their ID; rebuild subsequent URIs for the bound client. Changing clients invalidates deck/model bindings and cached lists; fetch writes and permission callbacks reject stale profile/connection identities (including client revisions). Stable format IDs cross popup bridges. `{sasayaki-audio}` is audiobook-only; `{sentence-audio}` uses an associated audiobook cue or, without an audiobook, enabled TTS. A missing audiobook cue must not silently fall back to TTS. |
| Sync | Hoshi and TTU retain distinct provider protocols behind the shared transport/settings. `SyncStorage` applies atomic sidecar merges; Android-only profile/language/audio URI fields stay local. |
| Hoshi Drive state | State documents reference immutable generation files. Deletion wins within a generation; session deletions survive generations. Unknown versions block cursor advancement. Background work completes only after uploaded references are published. |
| Hoshi metadata failures | `GoogleDriveClient` retries replay-safe Hoshi metadata requests after I/O failures with at most four exponential backoffs (1/2/4/8 seconds plus jitter), releasing the HTTP slot before waiting. Ambiguous POST creates are reconciled by the next sync pass, never blindly replayed. Exhausted metadata and payload network failures stay on the affected book/pass and preserve pending state/cursors without cancelling healthy payloads; authentication failures stop the transfer run, as do explicit stop/reset and format failures. TTU and payload retry policies are separate. |
| Hoshi automatic retries | `GoogleDriveSyncManager.requestSync` gates new automatic requests through `DriveSyncRetry`; the first fresh network or native authorization-recovery failure per admitted request advances a monotonic deadline from 30 seconds exponentially to one hour. State checks, in-flight transfers and publication continue without consulting that deadline, so healthy uploaded references publish immediately. Failed book checks rejoin later state passes; failed payloads are not replayed within the same file pass. Manual requests and recovered network capabilities release the deadline immediately; successful idle completion resets outage history. Automatic diagnostics retain the first network failure and first authorization-recovery transition separately per outage. WorkManager owns persistent background retries and shares diagnostic suppression. |
| Hoshi reconnection | `GoogleDriveSyncManager.reauthorize` stops transport and persists root verification before accepting credentials, blocking lifecycle restarts until acceptance finishes. Before consuming an old cursor or starting payloads, verify the new authorization's root: the same root retains generations, file references and cloud placeholders; a different root follows the existing reset/attach flow. Priority reader downloads require a completed state pass and verified connection too; a superseded check cancels that download before looking up old references. Native recovery stays persisted until the manager saves a successful root check; entry points reconstruct verification from that auth marker before requesting tokens, including after process death. Recovery failures stop queued and priority payloads; same-root silent recovery needs no attach choice. Verification survives process death, offline failures and cache clearing. |
| Hoshi file transport | `GoogleDriveFileTransfer` owns cancellable OkHttp payload I/O and resumable uploads; metadata and TTU stay on `GoogleDriveClient`. Socket and response I/O retain `DrivePayloadNetworkIOException` classification through credential redaction; upload source and download destination failures remain local errors. `DriveUploadSessions` atomically persists local-only session capabilities in `noBackupFilesDir`, never diagnostics or backups. Resume queries the server offset; completed responses survive until local file references are saved. Payload requests start at 4 MiB and adapt from confirmed bytes / full request duration toward 30 seconds, aligned to 256 KiB within 256 KiB–8 MiB; network failures reduce the next chunk. Source versions, connection resets and removed books invalidate sessions. |
| Hoshi transfer queue | `BookTransferScheduler` shares the configured 1–8 book slots across uploads, queued downloads and priority reader downloads; the default is 8 and saved choices take precedence. Reductions drain existing work. `SyncTransferTracker` accepts identity-checked byte callbacks, uses a monotonic clock, and the manager publishes every 500 ms only while active. UI progress and effective speed are per file: useful progress since this attempt’s starting offset / elapsed payload time, including confirmation and retry waits, excluding preparation and duplicate retransmitted bytes. In-flight progress is provisional and can roll back on recovery; 60 seconds without payload or acknowledgement progress yields zero. Upload/download totals sum active file rates. The file queue is built after the state pass; an empty queue only claims completion after a successful state check, and otherwise shows checking, interrupted or not-yet-checked status. |
| Authentication | Hoshi uses native Google authorization or browser PKCE; TTU keeps device-code auth. Native `hasResolution` preserves the connected flag as `RecoveryRequired`, allowing automatic and manual retries plus explicit recovery/sign-out actions; only explicit sign-out/provider replacement clears that native link. Browser tokens stay under `noBackupFilesDir`; credentials and token bodies must not enter diagnostics. `NativeDriveAuthorization` preserves SDK error codes/causes and safe consent, token-presence, Play Services and network flags when authorization fails or requires resolution; HTTP 401 token invalidation is recorded without its token. Never dump authorization results, Intents, accounts or scope contents. Build configuration is in VALIDATION. |
| Reader sync | Flush pending local saves before reconciliation; suppress write-back during remote restore. `SyncStorage` reports audiobook changes against the persisted position/delay/rate under the storage lock; local snapshot echoes and timestamp-only updates must not seek live playback. Reader highlights refresh only when visible records change; reading back local snapshots or timestamps must not overwrite pending highlight edits in the UI. Remote deletion/replacement closes the reader after statistics/audio cleanup. |
| Backup restore | `BackupSettingsView` coordinates `HoshiBackupRepository` with sync: stop sync before Books replacement, reset bookkeeping on success, disable sync on failure before restarting the manager. |
| Playback | Sasayaki and TTS services own their Media3 player/session. Media3 manages foreground-service and notification lifecycle; controls use that session. Reader supplies presentation callbacks and explicit exit/stop. Playback and its notification start on explicit audio interaction. The Sasayaki process-local connection remains a documented refactor target. |
| Reader presentation | `ReaderPlaybackPresentation` owns chapter/media traversal shared by Sasayaki/TTS. Reader interaction cancels pending presentation for both; TTS presentation is a child of playback, so abandoning an image/restore resumes speech while pausing playback cancels both. Late Reader callbacks require the presentation to remain active. TTS document restoration only highlights without navigating or revealing; playback and foreground resume may follow the sentence. Image holds require auto-scroll, including TTS background fallback. `ReaderLookupPauseState` owns lookup interruption/resume across popup replacement. |
| Paragraph audio | `ReaderParagraphPlaybackState` rejects stale page queries by request/document/restore generation and retains manual cross-chapter direction. `SasayakiPlaybackController` owns the transient `SasayakiPageRange`, independent of audition stops. Every accepted page-cue query replaces the range, including chapter jumps, restore and reflow; passive updates neither seek nor change transport. Forward turns seek/play, backward turns seek paused, and empty pages pause without seeking. As on iOS, page range only defines a stop boundary: crossing its end pauses before cue dispatch; it must never filter cue display or following by membership or current time. Resuming after a page-end stop can follow into the next page. The boundary persists in the service when Reader detaches. Disabling page advance, leaving paragraph mode, changing books, clearing matches or seeking outside the range clears it. When Sasayaki following moves to another page, Reader refreshes that page's range without seeking or changing the playing/paused state; following within the same page adds no query. |
| Transcription | `SasayakiTranscriptionCoordinator` serializes recognition, checkpoints and matching. Live matches stay in memory and take precedence over synced matches in the active reader. Completion, pause and failure finalize the latest successful match under the storage lock; only changed persisted matches notify sync. Deletion/replacement joins the task and suppresses final writes. Its work is process-bound; the reader-route VM pauses/saves on disposal. Process death retains the previous persisted match and transcript checkpoints for resumption and realignment. |
| TTS text | `TtsBookRepository` extracts full chapter source. `TtsText`/dialogue/replacement transforms preserve source offsets and original Anki text. Block boundaries delimit quote pairing; inline nodes, line breaks and images retain the current paragraph context. Paragraph-local paired outer Japanese, curly and ASCII double quotes create separate playback units alongside existing sentence/block boundaries; nested quotes and ruby spans remain intact, unmatched openers are discarded at paragraph boundaries and cannot leak dialogue roles, and Latin-word apostrophes do not close dialogue. Optional clause splitting handles `、,，─；;` with short-fragment protection in TTS only; Sasayaki transcription and stored matches are independent. The immutable segmentation setting travels through chapter/selection/navigation reads and chapter cache keys. All units share navigation, prefetch and sentence-pause behavior. Replacements operate per playback unit; full selections/Anki exports retain their selected text across units. Synthesis/cache is owned by `TtsAudioRepository`; distinct playback requests share a two-slot budget, identical audio identities serialize through reference-counted key locks, and cache clearing drains writers while a generation/barrier rejects obsolete work. |
| TTS transport | `TtsPlaybackController` queues ready units through `TtsPlaybackOutput` into the service-owned Media3 playlist, including ordinary chapter boundaries. Actual media transitions select the current unit; asynchronous Reader highlighting/following does not gate ordinary audio. Explicit sentence pauses and image holds remain barriers; `TtsBookRepository` retains trailing images and detects image-only intervening chapters. Queue exhaustion appends to the prepared player; manual navigation resets playback. Settings changes discard future units after reconciling the actual current token; segmentation changes capture the pending or reconciled source offset, pause, replace old position work and resolve the new cues without resuming. Reader cue preparation and WebView installation carry a generation to reject obsolete results, including a rapid switch back to identical cues; chapter extraction remains lazy until TTS starts. Other settings changes discard future units, and suspended prefetch rechecks its epoch and live queue tail before appending. Prefetch uses a bounded two-request pipeline within the configured sentence horizon, publishes results in source order without waiting for farther chapter discovery, and continues caching later units after a failed unit without skipping it in playback. |
| TTS providers | `TtsRepository` combines persisted global settings with the effective profile content language (including book activation), independent of UI locale; writes capture the displayed language before suspension and transform the latest DataStore value. Legacy source routes migrate to Japanese, and source deletion/protocol replacement removes routes in all languages. `TtsEngineRouter` dispatches installed, online and custom engines from that snapshot. Raw online/custom catalogs retain locale metadata and use a versioned source/connection cache; device catalogs and automatic engine selection are language-specific. Filtering normalizes regional/script tags and ISO aliases, including zh/cmn/yue, while synthesis uses the selected matching locale. OpenAI-compatible services permit unknown/manual voices; unavailable UI placeholders cannot become VITS speakers. Language participates in synthesis identity, cancels preview/discovery and pauses playback; unsupported style/role controls are normalized per request. Voice discovery is separate from synthesis; playback speed/prefetch/delay are separate from synthesis identity. Online/custom synthesis permits two concurrent calls; device TTS retains its engine serialization. Playback synthesis retries transient network/timeout/rate-limit failures at most twice with 500/1000 ms backoff and any longer server `Retry-After`, releasing request permits during waits; cancellation and permanent errors are not retried. A fully read HTTP 200 synthesis response with zero audio bytes contributes no file across playback, preview and export; Google envelopes must parse successfully before this check. Partial nonempty audio, malformed payloads, empty authorization/catalog responses and empty responses with other status codes remain errors; Edge and device TTS retain their existing completion rules. Empty file lists use the existing silent-unit playback traversal, while all-empty preview/export fails with `EmptyText`. Background failures retain diagnostic history. Disabling TTS cancels work without deleting configuration. |
| Updates | Stable releases and manual previews have separate records/downloads. Preview identity is the full commit SHA. Only the confirmed foreground About session may auto-open its installer. Component publication is independent of APK releases. |

## Maintaining this map

Document ownership and update rules live in [AGENTS.md](../AGENTS.md#文档维护规则).
