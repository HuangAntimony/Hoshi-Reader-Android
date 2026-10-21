# Hoshi Android Current Architecture

Date: 2026-09-18

This document describes the current architecture that exists in the Android
repo. It is not a future plan and should not track task status. Long-lived
refactor goals belong in `docs/ARCHITECTURE_REFACTORING.md`.

## App Shape

- The app is a single Android application module under `app`.
- UI is Jetpack Compose + Material 3.
- `HoshiSurfaceRoles` owns native page, navigation, group, nested, and overlay colors derived
  from Material 3 and the resolved brightness. Shared container helpers provide
  E-ink outlines (including continuous lazy group edges) while ordinary groups
  use tonal separation without decorative borders or elevation. Reader content
  and dictionary HTML retain their existing color/style systems.
  Bottom/side navigation uses `surfaceContainer`; page and top-bar backgrounds
  remain continuous through the status-bar inset, including Dictionary search.
  The search field uses `surfaceContainerHigh` to remain distinct on that page.
  Reading Settings segments use inset rounded selections with the shared
  `selected`/`onSelected` colors inside a continuous neutral nested track.
  The full segment remains clickable; E-ink uses inverse black/white fills and
  text with explicit outlines around the track and selected capsule.
- Native accents default to Android 12+ dynamic color with the existing fixed
  fallback on older Android. Manual opaque seeds use the standalone Material Color
  Utilities 4.1.1 Tonal Spot algorithm to generate a complete Material 3 scheme;
  E-ink overrides the result without overwriting the stored seed or palettes.
  `withHoshiSurfaceColors` caps native surface HCT chroma at 4 while retaining
  the source hue and tone. It softens `outlineVariant` toward the group fill;
  accent, text and input `outline` roles retain their source colors. System,
  manual, fallback and accent-preview schemes share this treatment. E-ink
  bypasses it to retain pure black/white fills and boundaries.
- Navigation uses Navigation3 typed route keys, `AppShell`, and `NavDisplay`.
  Top-level Books, Dictionary, Statistics, and Settings tabs each own an
  independent Nav3 back stack with its own saveable entry state and per-entry
  ViewModel stores. The shared main navigation chrome is owned by a Nav3 scene
  decorator around top-level root scenes, while Reader and Settings detail
  routes remain full-screen outside that shell. Once visited, the Dictionary
  NavDisplay stays composed and attached across tab switches to retain its WebView
  compositor. `RetainedTabContent` leaves it unplaced while inactive and caps its
  lifecycle at CREATED, disabling hidden back handlers and lifecycle collection.
- Production dependency injection is Hilt-backed. `HoshiApplication` owns the
  app component through `@HiltAndroidApp`, and Android entry points receive
  dependencies from the Hilt graph.
- Compose still receives a Hilt-created `HoshiUiDependencies` through
  `LocalHoshiUiDependencies` as a lazy temporary bridge for app-wide
  dependencies that have not yet moved behind screen ViewModels. The bridge
  does not build the object graph manually and resolves each dependency from
  Hilt only when the current UI path reads it.
- Many screens use Hilt-backed ViewModels with immutable UI state exposed through
  `StateFlow`.
- `ui/ErrorDetails.kt` appends distinct exception messages and their cause chain
  to localized operation/category summaries, using the exception type when a
  message is absent. Existing error dialogs, snackbars and retry states retain
  these diagnostics across repository and UI state boundaries; cancellation is
  propagated rather than rendered as failure. Typed failure categories continue
  to control recovery without replacing the original reason.
- Handled runtime errors enter `ErrorDiagnosticEvents` through those adapters or
  explicit failure-boundary recording before a typed/string result drops the
  original exception. A Hilt-singleton `NonfatalDiagnosticsLogger` drains the
  bounded event buffer on the IO dispatcher and atomically persists the latest
  100 records in app-specific `diagnostics/handled-errors.json`. Records retain
  timestamp, localized operation, original message/type and causal stack;
  actual service/platform error responses can be recorded without inventing an
  exception. Repeated rendering of the same Throwable is deduplicated by weak
  identity, while distinct retries remain separate. Diagnostics snapshots flush
  queued events and preserve crash/process-exit history. Once drained, batches
  finish their bounded IO writes even if the requesting screen is cancelled.
  Invalid JSON retains its original parse failure in a rebuilt log and keeps one
  copy of the damaged file; other IO failures do not overwrite history.
  Report sharing uses a bounded cache of FileProvider-backed text files instead
  of large Intent text payloads. The Diagnostics overview shows
  app/WebView metadata and separate typed Nav3 entries for captured crashes,
  runtime errors and system process-exit history. Lists show short summaries;
  selecting a record opens its selectable detail dialog, without nested list
  scrolling. Save/share always use
  the complete report from either overview or record list.
- Build provenance comes from the `buildCommitSha` Gradle property in CI and Git
  HEAD for local checkouts, exposed as `BuildConfig.BUILD_COMMIT_SHA`. Source
  archives without Git metadata report it as unavailable. About shows the short
  commit; Diagnostics links it to the full-SHA GitHub source snapshot and includes
  the full SHA and URL in exported reports.
- Formal tag releases and post-CI main previews share the signed APK build
  workflow. APK previews use unique `apk-preview-<sha>-<run>-<attempt>` annotated
  tags and prereleases, publishing verified ARM APKs before retiring previous
  workflow-owned previews. Automatic App update checks use only the latest full
  GitHub release; optional transcription component releases are independent.
- About uses the shared settings group surfaces and compact action/value rows.
  Project and support links use a page-scoped Hilt `AboutActionsViewModel` for
  visible diagnostic errors; `AboutImageRepository` saves the original bundled
  appreciation PNG into MediaStore Pictures/Hoshi Reader on IO.
  Android 10+ uses pending media publication without a storage permission; Android
  8–9 requests write permission only when saving. Failed writes remove the partial
  media entry and retain the original error. WeChat support is shown only for a Chinese app configuration; GitHub Sponsors and
  Ko-fi remain available in every UI language. Related projects contains iOS and Desktop;
  Dependencies and Acknowledgements share a separate group at the bottom of About.
  Dependencies and acknowledgements open in separate typed settings routes with their own ViewModel scopes. Both lists show license identifiers and open the
  project homepage. The list groups direct runtime dependencies by project and includes
  bundled native dictionary components; it is not a transitive dependency inventory.
- About offers a manual preview action through a Hilt ViewModel. It selects only
  workflow-owned published APK previews, resolves the annotated tag to a full
  commit SHA, and compares that identity instead of semantic versions. Preview
  records use a separate DataStore and a `previews` download subdirectory; the
  shared DownloadManager implementation and receiver handle both stores without
  sharing files or version comparison. Only the confirmed, foreground About
  session can automatically open the system installer. Background completion or
  restored downloads require tapping Install. Preview cleanup compares commit
  SHA, so equal-version APKs remain available until that commit is installed.
- Settings and small persisted preferences are stored behind DataStore-backed
  repositories.
- Persisted settings controls are composed through the required
  `SettingsDetailScaffold(state = ...)` entry point, or `SettingsContent` for sheets
  and embedded panels. `SettingsLoadState` distinguishes `Loading`, `Loaded(value)`
  and localized `Failed`; a loaded nullable value is a valid result. Repository
  defaults become ready only after the repository confirms them. Multiple required
  sources must all emit before controls are created; initial constructor defaults
  never stand in for stored values. ViewModels own readiness for their screens;
  direct repository Flow consumers use the shared lifecycle-aware settings loader,
  whose retry and source identity reset readiness. Ordinary value updates retain
  the loaded controls. Profile-scoped Anki, Dictionary and reading state cancels and resets
  on profile changes, rejecting late results from the previous profile.
  `StaticSettingsDetailScaffold` serves static menus/operations; already-ready
  parent inputs can use `Loaded(actualValue)`. Navigation3 entry/ViewModel scope
  remains responsible for navigation lifetime, while readiness controls the first
  settings frame independently of navigation retention or switch animation.
- Profiles are Hilt-backed app-wide state. `ProfileRepository` stores profile
  metadata under app-specific files, exposes active profile state through
  `StateFlow`, and controls the effective content language for Reader,
  Dictionary search, Process Text lookup, Anki settings, and dictionary lookup
  sessions. Profile metadata mutations are main-safe suspend APIs backed by the
  injected IO dispatcher. Creating a profile copies existing profile-owned files
  from the current global active profile.

## Storage And Data

- Book storage is rooted in app-specific files and remains compatible with the
  iOS sidecar JSON layout.
- EPUB import enters through Android Storage Access Framework and copies content
  into app-specific storage.
- Current book folders store the packed EPUB as `<folder>/<folder>.epub` and
  persist the filename in `BookMetadata.epub`. Sidecar JSON and cached covers
  remain beside the EPUB; parser and reader paths extract packed EPUBs only into
  controlled app cache/temp directories when they need the EPUB tree.
- Android keeps the iOS-sanitized title as the physical book folder when its
  packed EPUB filename fits the platform limit. Longer multibyte titles retain
  the full value in `BookMetadata.title` while the physical basename is capped
  at 250 UTF-8 bytes with a readable prefix and deterministic SHA-256 suffix;
  TTU import and backup paths use the same byte-safe policy.
- Bookshelf and Statistics cover rendering uses one process-wide Coil loader.
  `BookCoverThumbnailStore` owns a versioned, source-fingerprinted 256/512/768 px
  WebP derivative cache under the app cache directory. Original-cover thumbnail
  generation is single-flight and serialized; Coil owns measured-size requests,
  small-thumbnail decode concurrency, lifecycle cancellation, and memory reuse.
  New local and remote imports prewarm the 768 px derivative, while existing
  books backfill lazily. Transient derivative-generation or cache failures use
  the original cover through Coil, and malformed or decoder-rejected
  derivatives invalidate only their size bucket before the next request
  rebuilds them.
- Imported EPUB metadata includes the trimmed first creator when nonblank as
  an optional iOS-compatible book author. Bookshelf cover cards render
  deterministic title/author gradient artwork when a declared cover is
  missing, fails to decode, or is hidden. A global DataStore-backed Bookshelf
  setting controls
  Show, Blur, and Hide across local, remote, expanded, and collapsed shelf
  previews; Hide never submits the cover source to Coil, while Blur uses the
  platform effect on Android 12+ and safely uses the hidden fallback on older
  Android versions.
  A separate DataStore-backed `hideCollapsedShelfThumbnails` preference defaults
  to false. When enabled, local, Reading, and Google Drive collapsed sections
  omit their preview rows entirely while retaining their title/count/toggle row;
  expanded cards continue to follow the cover mode.
- Book metadata, bookmarks, highlights, reading sessions, and Sasayaki data
  are persisted through iOS-compatible sidecar repositories and models.
  Highlights use timestamped UUID records with durable deletion markers;
  `shelves.json` maps names to timestamped nullable positions, while metadata
  holds timestamped memberships. Legacy arrays convert on first read as on iOS.
  New EPUB imports retain the selected source filename and fill cloud placeholders;
  importing an already-local book leaves it unchanged.
  Metadata and playback have modification stamps; bookmark dates retain the
  Apple reference epoch, while sync and session timestamps use Unix milliseconds.
- Statistics is always available from its top-level tab. Settings and folder-keyed
  session editors use that tab's Navigation3 back stack and entry-scoped Hilt
  ViewModels. Reader statistics display preferences remain in Reading Settings;
  TTU statistics and playback sync controls live in Sync settings.
- `BookStatisticsStore` is the shared Hilt singleton for reader sessions,
  transactional sync imports, session edits, archive/restore, and dashboard reads.
  `BookStorageLock` serializes sidecar and statistics transactions across IO
  suspensions; writes use atomic replacement. `statistics.json` maps UUIDs to
  timestamped nullable sessions. Legacy daily records convert with deterministic
  SHA-256-derived IDs shared with iOS. Daily totals are derived from sessions using
  the current reset time. TTU daily import/export remains at the provider boundary.
  A reading sitting keeps its ID across pauses and backgrounding; the next save
  overwrites edits to an active session, while deletion starts a new session.
- Deleting a book archives sessions, including deletion records, and compatible
  metadata under `Books/statistics_archive/<folder>/`, with an optional JPEG cover
  bounded to 240 px when live sessions remain. Required archive failures preserve
  the source book. Import restores archived sessions before TTU statistics merge;
  normalized folder identity and UUIDs prevent double counting. Equal stamps keep
  the first input: current book on both deletion and restore. Clearing archives
  records deletions and removes unused covers without removing sync history.
  The archive directory is excluded from book discovery and TTU exports but is
  included in Books `.hoshi` backups.
- Statistics repositories combine local and archived sidecars for the dashboard
  and session editors. The daily goal card combines a semicircular gauge,
  history metrics with shared text baselines, and a display-only reading-intensity
  heatmap. Sparse active dates back a lazy week grid with viewport-only drawing;
  heatmap scrolling and data are independent of chart selection. The fixed
  dashboard sections share a regular scrolling column, retaining their
  compositions and draw caches when they move offscreen. Book distribution
  still expands in five-row increments. Target editing
  uses an anchored popup with a snapping value wheel, independent remembered
  character/time goals, immediate tap selection and persistence when scrolling
  settles.
  There is no separate calendar picker or weekly goal.
- The reading-time card owns Week/Month/Year/All selection, initially showing
  the current Week. A horizontal pager browses natural periods from first activity to
  today, preparing chart data only for the selected and adjacent pages. Changing
  mode returns to the current period. The chart uses a shared continuous calendar
  scale for bar geometry and hit testing, locale week-aligned month ticks, and
  cached draw geometry/text. Bars drill into a day for week/month or a
  month for year/all; paging or changing mode clears that selection. The headline,
  summary and time-ranked books follow the selected bucket, or the whole period
  when none is selected. Calculations zero-fill buckets, use elapsed-period
  averages and recompute historical goals across the entire history.
  Heatmap and week periods follow the locale's first weekday. Reader tracking
  and the dashboard share the reset-time local-date provider; historical daily
  buckets are recalculated from session timestamps.
- Book metadata sidecars may include a forced profile id and parsed EPUB
  language. Reader opening resolves the effective profile from forced profile,
  then EPUB language primary profile, then the global active profile.
- Dictionary import, term/Kanji lookup, media, style extraction, deinflection,
  frequency, and complete pitch data are owned by
  `third_party/hoshidicts-kotlin-bridge`. The parent Kotlin ABI copy must remain
  constructor- and method-compatible with the bridge submodule.
- `DictionaryLookupQueryService` owns the active native lookup session. Rebuilds
  construct a new native query session for the active profile's dictionary
  language and enabled Term/Frequency/Pitch/Kanji paths before swapping it into
  service; term lookup, Kanji query, style, and dictionary-media reads use the
  currently published session and return empty results when no session is
  ready. Term-dictionary categories do not affect lookup-session membership:
  all enabled dictionaries remain searchable, including `exclude`. Changing
  a category persists the config without rebuilding the native session.
- Dictionary imports preserve typed native failures through the repository;
  batch results pair each failed file with a `UiText` reason. Error-dialog text
  resolves joined resource fragments in the UI locale, retaining native details
  together with the original diagnostic message and cause chain. Cancellation propagates while
  completed imports still publish the mutation change version.
- Lookup frequency order and selected dictionary are profile-scoped settings.
  Every lookup entry point passes typed options to the native query; sorting
  remains native-owned and does not require rebuilding the query session.
  Frequency-dictionary update renames migrate selected titles across profiles.
- Dictionary data directories remain global under `Dictionaries/`, while each
  profile owns `dictionary_config.json` and `dictionary_settings.json` under
  `Profiles/<profileId>/`. The config preserves per-type order and enable state,
  the optional Kanji list, and iOS-compatible term categories `none`,
  `monolingual`, `bilingual`, and `exclude`; missing newer fields use legacy-safe
  defaults.
- Dictionary `.hoshi` backups keep the legacy root archive shape for iOS/older
  Android compatibility and include profile-scoped dictionary metadata under the
  reserved `.hoshi-profiles/` payload. The root `config.json` projects the
  default Japanese profile for single-profile restore targets; newer Android
  restores merge the profile index and profile dictionary config/settings while
  preserving profile-owned Anki and Reader settings that are outside the
  dictionary payload.
- Reading Settings (font, layout, reading information, and lookup panel options)
  are stored per active/effective profile in `Profiles/<profileId>/reader_settings.json`.
  Reader Behavior and statistics sync settings remain global DataStore settings.
- `AppDisplaySettingsRepository` owns global Theme settings in a separate
  DataStore: system-driven switching, independent light/dark reading palettes
  with remembered custom colors, the manually selected slot, accent source/seed,
  E-ink mode and its independent manual brightness, and migration version.
  `resolveDisplaySettings(settings, systemDark)` is the pure source for active
  reading colors and native interface brightness. Both switching modes share the
  same six choices in two groups: automatic mode selects one per group, manual
  mode selects one across both. Disabling automatic switching keeps the active
  slot; re-enabling retains both stored selections. Custom colors preserve alpha;
  interface and popup brightness come from the selected group, never the custom
  background's luminance. E-ink follows system brightness when automatic switching
  is enabled; otherwise its remembered
  manual brightness takes precedence, defaulting to the palette's brightness until
  chosen. This override never changes stored palette or accent colors. Disabling
  automatic switching in E-ink keeps the currently displayed brightness.
  Display settings block repeated interactions while saving without removing
  previews, dimming the page, or changing list geometry; colors come from confirmed
  settings. The full settings page and Reader panel share this content. The Reader
  panel title is inside its scrollable content; the drag handle remains outside.
- `SystemThemeObserver` reads device brightness only from the Application Context,
  on configuration changes and process foreground entry. `LocalSystemDarkTheme`
  carries that value to display settings and Reader; Activity `uiMode` is the
  app's resolved appearance and must never be used to infer the device setting.
  Both entry Activities use AppCompat DayNight. After confirmed display settings
  load, `nativeThemeReady` maps the shared policy to `setDefaultNightMode` before
  mounting normal content. AppCompat 1.8 dispatches configuration changes to the
  retained View tree under the Activities' `uiMode` handling. No second persisted
  night-mode setting is introduced. Native selection menus and the highlight
  palette close on theme changes; the palette uses Compose overlay/outline roles.
  All WebViews inherit `HoshiWebView`: if first View construction resets native
  resources to the system theme, it reports the actual configuration to AppCompat
  and dispatches the restored configuration to the not-yet-attached View. This
  keeps cold-start media queries aligned without recreating Activities or manually
  mutating Resources; WebView pre-start alone does not prevent this provider reset.
- Display migration targets the settings format shipped in Android v1.3.3. It reads
  the global active profile before Reader profile initialization or book-specific
  profile activation. If that profile's settings file is missing or unreadable,
  it falls back to Reader DataStore, then legacy SharedPreferences, then defaults.
  Other profiles are not read, and source settings are not rewritten by migration.
  The new global display schema starts at migration version 1; only a successful
  write marks migration complete. Completed migration never reapplies old profile
  settings. Unreleased development schemas have no dedicated migration paths.
  Background luminance is used only to assign v1.3.3 custom colors to a light/dark
  group. Legacy Profile JSON color fields remain readable and are preserved on
  writes, but no longer control runtime
  display. Book sidecars, sync, and backup formats are unchanged.
- `ReaderSettingsRepository` combines global display state with profile reading
  preferences. Both MainActivity and Process Text use `ReaderSettingsHostViewModel`
  and wait for its first confirmed value before rendering content. ViewModels expose
  immutable state and localized load/save errors; profile changes never overwrite
  global display settings. The host retains the last confirmed settings for the app
  shell during profile reads, while its separate `loadState` blocks settings controls
  and edits until the new profile is ready; navigation entry stores stay composed.
  Reading Appearance also waits for confirmed Sasayaki preferences, including its
  Reader sheet. Reading setting events submit transformations against
  the latest stored value, preserving successive edits during delayed persistence.
  Each profile reading update captures its target before suspension and uses that
  profile for the entire read, missing-file initialization and write operation.
  Display updates use the existing WebView appearance
  bridge without rebuilding Reader content or lookup state. Both Activity hosts
  handle `uiMode` changes in place; Compose observes the updated system
  configuration and the existing WebViews receive the resolved appearance update.
- Reader font selections retain the legacy display-name field and additionally
  persist stable family/variant IDs plus each profile's last variant per family.
- Statistics daily target settings are global DataStore settings behind a
  repository.
- Profile-scoped Reading Settings, Dictionary, and Anki settings JSON reads and
  writes use injected IO dispatchers and repository-owned serialization locks.
- Frequency and pitch dictionaries are type-specific and are not treated as term
  fallback dictionaries.
- Dictionary storage/config mutations share a Hilt singleton mutation
  coordinator. Dictionary UI, manual updates, imports, and WorkManager automatic
  updates observe the same in-process busy/progress state and completed-change
  version; operational dictionary settings such as update interval, last update,
  and low-memory import remain in DataStore. Automatic updates force low-memory
  import at the update service boundary without modifying that preference;
  manual imports and updates continue to honor it.

## Reader

- `ReaderFontManager` owns the app-private font library under `Fonts/`, exposes
  an immutable revisioned family/variant state, groups user TTF/OTF files by
  bounded SFNT metadata, and keeps legacy basename-only WOFF/WOFF2 and malformed
  pre-existing imports readable. Managed recommended files live under
  `Fonts/System/` and cannot be deleted from the UI. A private atomic alias
  sidecar preserves legacy basename selections and dictionary CSS references
  when a parsed family/weight/style slot is replaced by a new internal file.
- Dictionary settings exposes a stroke-order font download after the active
  profile has at least one Kanji dictionary. The screen-scoped installer pins
  the source file size and SHA-256, downloads through a private temporary file,
  and enters the verified result through `ReaderFontManager`'s normal user-font
  import path. An already installed `KanjiStrokeOrders` family keeps the action
  visible but disabled.
- `ReaderAppearanceViewModel` owns visible font import/download/delete state.
  Recommended files come only from the pinned internal Google Fonts catalog,
  are streamed to a same-directory temporary file, and become visible only
  after exact size and SHA-256 verification followed by an atomic move. These
  short user-visible transfers use cancellable in-process coroutines rather
  than WorkManager.
  Catalog grouping uses the publisher's specific style classification, including
  Google Fonts style tags: Rounded fonts belong in the rounded group even when
  their broad Google Fonts metadata category is `SANS_SERIF`.
- Reader and lookup WebViews consume stable per-family CSS aliases and render
  specs containing installed faces, real weight/style values, variable axes,
  and the font-library revision. Generic system serif/sans-serif choices remain
  CSS-matched platform families because OEM builds may not include Noto; legacy
  Noto display-name values remain compatibility keys only. Publisher leaves EPUB
  family, style, and weight declarations intact.
- Reader rendering and lookup remain WebView-based to preserve iOS-aligned
  visible behavior. Renderer termination advances `ReaderWebViewStateHolder`'s
  WebView generation, preserving the latest accepted display position or pending
  navigation target. The host clears the dead view reference, popup state and
  auto-page work; the chapter view removes/destroys the old WebView and Compose
  creates a replacement. Existing restore setup replays settings, highlights
  and cues, then releases queued Sasayaki presentation. Released WebViews reject
  late JS results and popup/selection bridge events.
- Reader layout modes are WebView-backed assets for paginated, continuous, and
  VN reading. Kotlin selects the asset, injects typed settings, and keeps
  persisted progress as chapter progress mapped to whole-book character count.
  Continuous restore aligns live text and its same-line ruby geometry without
  inserting layout markers. Progress uses glyph centers to exclude clipped
  slivers of adjacent lines, keeping repeated save/restore stable at tight line heights.
  Continuous mode keeps the WebView at the full reading-region size and scrolls
  an inset, fixed-position `body`; popup iframe shells stay outside that body.
  The continuous runtime owns content bounds, scroll operations and chapter-edge
  checks. It sets measured CSS pixel dimensions before restore, so initial layout
  does not depend on WebView's first-frame viewport-unit resolution. Selection
  and source-highlight clipping use those content bounds in full-WebView coordinates.
  `HoshiReaderScroll` reports body scrolls with the current document token to the
  existing throttled progress/idle-save scheduler. Boundary-swipe replies reject
  replaced documents, listeners and gestures; rebinding unchanged reader settings
  retains the touch listener through progress recompositions.
  Paginated page height and page steps use the visible WebView viewport height;
  vertical padding and column gaps contain only configured user spacing, with
  no extra font-size overlap strip.
- Reader `bookinfo.json` sidecars persist whole-book/spine character counts plus
  optional iOS-compatible TOC fragment offsets and a first-appearance raster
  image inventory. A reader-facts schema version invalidates stale derived
  fields when their indexing semantics change. Contents rows, chapter progress,
  and chapter time remaining derive from one Kotlin-owned TOC range model;
  Gallery thumbnails and the fullscreen viewer reuse the existing safe EPUB
  resource path.
- Reader highlights keep plain text/raw offsets for positioning and optional
  iOS-compatible `textFurigana` for Contents display. The shared highlight asset
  owns exact-range identity and returns explicit create/recolor/remove results;
  it reads the first/last selected body slices through
  `reader-dom-text.js`'s `selectedTextSegments`, shared with native replacement
  context. Annotation and element endpoints never become highlight offsets;
  picker snapshots preserve the original native range until a color is chosen.
  Kotlin preserves record identity and creation time on edits through the
  existing sidecar repository. VN extracts highlight text/readings from its
  content stream and uses the range map only for screen projection. Batch
  wrapping refreshes node offsets after each highlight so overlapping ranges
  remain projectable after a screen rebuild.
- Book search keeps literal paragraph display text with a UTF-16 boundary map
  to normalized code-point positions. Result jumps queue a chapter-local range
  through the existing restore epoch; temporary search marks use shared raw
  range projection (VN source stream to current clone), without sidecar writes.
  Node-offset rebuilds refresh active search ranges after persistent-highlight
  or Sasayaki wrapping moves text nodes; live DOM Ranges do not follow those moves.
- Reader furigana mode is profile-scoped, with legacy hide-furigana booleans
  migrated to Hidden or Off. Shared selection consumes Toggle reveal taps and
  reveals whitespace-adjacent ruby groups; VN uses its existing source/clone
  projection to retain reveals when a screen is rendered again.
- Reader text semantics live in `reader-text-semantics.js` and are consumed by
  paginated, continuous, and VN assets for normalization, matchable character
  counting, raw character counting, and matchable-character checks. It also owns
  Sasayaki boundary-punctuation ownership and the matchable-to-raw display index;
  cue matching offsets remain unchanged.
- Paginated and continuous share live DOM ruby/text normalization through
  `reader-dom-text.js`; the mode assets keep thin public wrapper methods so
  existing reader commands and tests continue to call the same surface. Its
  Sasayaki DOM adapter reads paragraph, line-break, and media boundaries for all
  three modes. VN captures CSS boundaries before detaching the chapter DOM,
  builds and caches this index from the complete source stream
  and projects expanded raw ranges through its existing screen range map.
- Paginated/continuous narration keeps inline cue wrappers in normal mode and
  geometry overlays in E-ink mode. Do not replace narration painting with CSS
  Custom Highlights based on API support alone: WebView can clip the left edges
  of vertical Japanese glyphs, including ruby-adjacent text. Any replacement
  must first pass the real-WebView glyph-edge checks in `docs/VALIDATION.md`.
- Reader image setup semantics live in `reader-media-semantics.js` and are
  consumed by paginated, continuous, and VN assets for shared gaiji class
  recognition, transparent monochrome inline-image tinting, SVG image
  aspect-ratio correction, large image block marking, blur wrappers, native
  image tap bridging, and scoped setup. Paginated and continuous apply it to
  the chapter document and wait for image load/failure before restore; VN
  applies it to the current rendered screen without blocking screen rendering
  on image load.
- Reader publisher-layout sanitation lives in `reader-layout-semantics.js` and
  is consumed by paginated, continuous, and VN assets after fonts and source
  images settle but before offsets, restore, or VN screen construction. It
  converts oversized paragraph-bearing inline blocks into pageable blocks and
  removes or clamps empty inline-block struts that exceed the logical viewport.
- VN reading uses VN-specific reader-web runtime primitives for chapter content
  streams and rendered range mapping. `reader-vn-content-stream.js` owns source
  text/raw offsets, matchable offsets, ruby-aware text entries, structural IDs,
  and standalone media units. `reader-vn-range-map.js` maps VN rendered screens
  back to raw highlight ranges, matchable Sasayaki ranges, and source positions.
  `reader-vn-selection-projection.js` maps current-screen selection hits into
  the source stream for lookup text, complete sentence context, and normalized
  offsets, then projects semantic ranges back to the visible clone for popup
  anchors and underlines. VN keeps its mode-specific block/sentence boundaries,
  reveal behavior, cross-screen Sasayaki merge, viewport fitting, and
  current-screen rendering.
- Paginated and continuous production page/scroll runtime paths remain
  unchanged and are not wired to VN content stream instances or the VN range-map
  module.
- Reader fixes compare against the iOS `ReaderWebView` and matching JS/CSS
  before adding Android-specific behavior.
- Reader resource loading must stay on the repository's safe loading path and
  must not broaden file URL access.
- Durable reader JavaScript and CSS live under
  `app/src/main/assets/hoshi-web`; Kotlin owns typed commands, escaped
  parameters, asset loading, dynamic configuration fill-in, and WebView bridge
  invocation.
- Reader and lookup popup text selection use shared selection plumbing. Language
  utilities live in language-named assets such as `language-ja.js`, while
  selection scan policies live in `selection-ja.js` and `selection-en.js`;
  Kotlin loads the utility plus policy selected from `ContentLanguageProfile`,
  and the Japanese policy owns `scanNonJapaneseText` filtering. Shared selection
  accepts an optional semantic projection; paginated, continuous, and popup use
  the identity live-DOM path, while VN supplies its source/clone projection and
  fails closed when a clone hit cannot be mapped.
  Native selection context reads only selected body-text slices using the shared
  DOM text range extractor. Its first nonempty slice supplies the semantic source hit,
  even when the native range begins inside `rt/rp` or at a text/element end.
  Annotation-only ranges return no context; reading never changes native endpoints.
- Reader DOM `selectionchange` owns the selected-text state. It synchronously
  locks paginated/VN navigation and reports through `HoshiNativeSelection` with
  the document restore token; Android ignores stale document notifications.
  Native gesture/narration guards combine this state with the ActionMode and
  highlight color workflow. Dismissing a menu alone does not clear a surviving
  selection. Navigation blocked by selection returns `blocked`, never the
  chapter-boundary `limit` result.
  During paginated selection, CSS `overflow: clip` on the root and a relative
  body offset preserve the visible page without a scrollable selection ancestor.
  Logical page offsets stay unchanged; deselection restores the normal body
  scroll position. This prevents Blink autoscroll and the reader's rollback from
  moving selection endpoints back and forth. WebViews without overflow-clip
  support retain the scroll-lock fallback. Native menus keep WebView's valid
  ActionMode content bounds. If those bounds are empty or wholly offscreen,
  Android measures the current visible DOM endpoint carets once per ActionMode,
  keeping their anchor nonempty even in vertical writing. It uses clipped selected
  fragments only when neither endpoint provides visible geometry. Using all
  intervening columns can move a recovered menu to the bottom of the screen even
  when both handles are near the top. Selection changes, scroll, and resize discard
  this fallback without rereading layout during the gesture; document, mode, and
  request-generation guards reject stale replies. If Chromium sends a trusted
  handle context-menu request outside the viewport or selected text, shared
  selection validates a visible point inside the current range, choosing
  the one furthest from both endpoint carets. Vertical text uses each fragment's
  left interior edge: a single glyph's center can hit the start handle extending
  from its right, whereas the left interior stays above the end handle.
  Using the first glyph of fragmented text can hit a native handle instead of
  content and leave the menu hidden.
  Chromium's handle requests use mouse pointer events. Touch/pen context-menu
  events keep their native path: they can arrive before the original press ends,
  and injecting another touch would interrupt that gesture.
  The recovery press includes WebView's tap timeout plus long-press timeout and
  a release margin; releasing at the recognition threshold can instead trigger
  a tap that clears the selection.
  Android consumes
  that point once and replays a touch long press directly through WebView,
  bypassing reader page/tap gestures while preserving the selected range and
  WebView's ActionMode ownership. Mouse requests use a separate dropdown on
  Android 14+ large screens and must not be used for touch-menu recovery.
  Recovery validates request identity and the anchor generation; valid-position and
  secondary-button requests are never replayed. Native handle requests may report
  `pointerType="mouse"` with `button=-1`, so pointer type does not exclude them.
  Consuming a recovery point arms a document/request-scoped replay guard: the
  recovery gesture's context-menu events pass through to WebView even when
  Chromium repeats invalid coordinates. They cannot recursively request another
  recovery. Android clears that guard when rejecting or cancelling the request.
  Pending and replaying recoveries retain their exact anchor/focus endpoints.
  A duplicate `selectionchange` after the handle's context-menu request does not
  cancel an unchanged recovery; an actual endpoint or selection-direction change
  still invalidates it. Scroll and resize always invalidate recovery geometry.
  Queued replies and pending touches are cancelled on deselection, anchor expiry,
  document replacement, a new user touch, focus loss or release. Geometry is read
  only at the menu request, not during handle movement.
- Reader, Dictionary search, and Process Text lookup popups render through the
  shared `reader-popup-host.js` iframe stack and `ReaderLookupPopupWebBridge`.
  Kotlin owns popup payloads, resource handling, and native service bridges for
  audio, dictionary media, Anki, and external links; do not reintroduce Android
  native overlay popup fallback paths for these flows.
- `popup-theme.css` owns the iOS-compatible semantic palette and Android E-ink
  overrides. Default palette selectors use `:where` so dictionary/user CSS can
  override variables. Dictionary styles target `:where(div)[data-dictionary]`;
  default text colors never override custom colors with `!important`.
  Entry action buttons share opaque black/white enabled colors and gray disabled
  colors in all display modes, selected by CSS `:disabled`; JavaScript owns
  availability and icons without injecting button colors.
  `setPopupTheme` is sent before initial content and on appearance changes;
  the iframe URL excludes brightness/E-ink state and includes the shared asset
  version. Pure theme changes retain DOM, scroll, history and audio candidate
  selection. Monochrome image filters and canvas fills use that same palette;
  existing canvases redraw and late image loads read the current palette.
  WebView's native DayNight theme supplies `prefers-color-scheme` for copied iOS
  CSS. Popup shells give their iframe elements the same explicit `color-scheme`
  as the embedded document so Chromium preserves transparent canvas backgrounds,
  with algorithmic darkening disabled. Android keeps CSS `zoom` and exposes
  `--popup-scale: 1` within that coordinate system, avoiding double scaling.
- Dictionary's `DictionarySearchSession` is owned by the AppShell composition.
  It retains the WebView's current DOM, scroll position and JS history across tab
  removal, with root scroll state, child history counts and the bridge callback
  holder. Reattachment rebinds touch callbacks; the WebView pauses outside its
  resumed lifecycle, and the shell disposes it. Nonempty iframe sync waits for
  measured viewport/header dimensions; empty search/profile resets clear the
  stack immediately. Inactive search fields cannot request focus or show the
  keyboard. Do not put Activity-backed WebViews in ViewModels.
  A successful Reader load clears this session before activating the book's
  profile, including its query, results, recursive popups and JS history. The
  reset reaches the retained WebView and refreshes the hidden UI's collected
  state; lookup versions prevent pre-reset queries from publishing late results
  or failures. Loading and failed Reader loads leave the search intact.
- Built-in remote word audio uses Yomitan's Japanese source order:
  JapanesePod101, LanguagePod101, and Jisho. `BuiltInAudioSource` owns stable
  internal source URLs and resource-backed names. `AudioSettingsRepository`
  migrates the old built-in proxy source in place, preserving its enabled state
  and all custom sources. `RemoteWordAudioRepository` owns HTTP requests on the
  IO dispatcher, HTML parsing, and JapanesePod101 placeholder-audio validation.
  The Hilt-provided `AudioRequestHandler` adapts these sources to the existing
  audio-list JSON protocol at the WebView interception boundary; all popup
  hosts share it. Candidate URLs are actual remote media URLs, so playback and
  Anki continue through their existing bridges and backends.
- Popup audio sources cross the iframe boundary as ordered name/URL pairs.
  `LocalAudioRepository` returns every enabled, ranked local candidate and
  `AudioRequestHandler` exposes their descriptive labels and deduplicated URLs.
  Popup JS owns the entry-scoped candidate cache and selected URL, so playback
  and Anki mining use the same choice; replacing or restoring popup results
  clears that state. Source requests are cached separately: default playback
  and mining stop at the first matching source in configured order, while the
  recording menu opens immediately and resolves all sources concurrently.
  Source groups update in configured order, and available rows select by URL
  without waiting for other sources; closed/reset menus ignore late results.
- Shared iframe frame payloads accept optional root `sourceText` for Dictionary
  search and Process Text; Reader and recursive child frames omit it. Shared
  popup assets render character spans, look up exact suffixes on tap, mark the
  match, and preserve scroll on successful source redirects. Popup geometry
  converts the source-enabled entries minimum height to one visual viewport
  under HTML zoom; keep that reserve while replacement entries load, and clear
  it for ordinary/reset payloads. Profile-scoped
  `DictionarySettings.searchTextSize` defaults to 22 and is normalized to 12–48.
- Dictionary and Process Text root lookup state retain the complete original
  query as the mining sentence. Successful redirects use the UTF-16 length
  difference only when the requested query is an exact suffix, otherwise the
  mining offset is null. Dictionary clears that offset on a new search; Process
  Text initializes it to zero and also updates its root selection text/offset
  after successful redirects. Failed redirects preserve native results,
  selection/mining context, and history.
  Shared popup history snapshots retain source match ranges and UTF-16 mining
  offsets. Restoring a snapshot sends `sourceHistoryRestored` to update only the
  Dictionary or Process Text root mining context; Reader and child popups ignore
  this message. Source payloads also carry the initial `sourceSentenceOffset`
  so restoring the initial Process Text snapshot preserves its zero offset.
  Process Text creates its root even with no initial match, so source taps can
  recover a lookup; recursive popup creation still requires results.
- The shared popup term payload carries pitch entries as a numeric downstep or
  explicit H/L pattern plus 1-based nasal/devoice mora positions. Popup JS owns
  effective-pattern deduplication and visual rendering. A single Kanji in a term
  header routes through the same iframe bridge to the native Kanji query and is
  rendered in place with the popup's existing back/forward history.
- Lookup popup CSS `zoom` coordinate conversion and scrolling are owned by
  `popup.js` through `hoshiPopupGeometry`. Popup term alignment, reduced-motion
  viewport scrolling, history/reset positions, tap selection coordinates, and
  selection bridge rect scaling use that shared visual-coordinate boundary
  instead of mixing unscaled layout offsets with scaled scroll coordinates.
- Lookup opens from a single tap on reader text. Long press is reserved for
  native selection/highlight flows.
  `ReaderLookupPauseState` owns the shared Sasayaki/TTS lookup interruption policy:
  pause once, preserve the resume action across root/child popup replacement, consume
  it once when the stack becomes empty, and clear it when manual pause cancels recovery.
  Root lookup resolves before replacing the stack; it never transiently empties the
  stack between two successful selections. A lookup miss or final dismissal resumes
  the original source. TTS supplies its generation-checked resume action; Sasayaki
  resumes only the same player instance if it is still paused.

## Integrations

- Current-book cover publishing is an opt-in global platform integration backed
  by DataStore. After a Reader route finishes loading, a Hilt-backed publisher
  reuses the extracted book cover and renders it once onto a screen-sized PNG
  using the persisted Fit, Fill, or Stretch mode. The publisher independently
  updates the Android lock-screen wallpaper and/or a persisted Storage Access
  Framework document URI. On compatible iReader firmware, a third target
  atomically copies the rendered PNG into `/data/zhangyue/logo/book` under a
  unique name and explicitly notifies iReader SystemUI’s `BOOK` screen-saver
  backend.
  The target only publishes while the system `wallpaper_lock_screen_info`
  setting selects type `2`; it does not write that system setting or impersonate
  the built-in reader provider. Publishing failures do not block Reader
  loading, and the integration does not request broad storage access.
- Anki work stays behind the Anki backend/repository boundary.
- The optional Anki accidental-duplicate safeguard is active only with Allow
  Duplicates. Shared popup JS owns pending/success state on each rendered
  term/format button; history retains those nodes, while fresh results and new
  popups start fresh. Duplicate-check refreshes cannot override pending or
  successful guarded actions. Mining callbacks retain their original result
  and button container rather than resolving a replacement result by index.
  Failed guarded attempts restore the preceding button states without depending
  on another duplicate lookup against the potentially unavailable backend.
- Anki settings are stored per active profile in
  `Profiles/<profileId>/anki_config.json`. Schema version 2 owns one to three
  stable-ID `AnkiCardFormat` values, each with its own icon, deck, note type,
  field mappings, and tags; legacy single-format JSON is migrated and persisted
  as one default format. Popup mining, per-format duplicate checks, and opening
  existing notes all carry the stable format ID through the reader bridge and
  still go through the Anki repository/backend boundary. AnkiConnect opens
  notes with `guiBrowse`; AnkiDroid uses the public
  `anki://x-callback-url/browser?search=...` deep link scoped to its detected
  package. AnkiDroid 2.25 makes `CardBrowser` non-exported, so Hoshi does not
  start that activity directly. The public link retains AnkiDroid's selected
  browser deck, which can further narrow Hoshi's requested search scope.
  At mining time,
  glossary-first and monolingual/bilingual definition handlebars resolve from
  the current profile's persisted term-dictionary order and categories without
  extending the popup mining payload. Anki popup settings carry the profile's
  excluded dictionary titles and refresh after dictionary mutations and profile
  settings reloads. Aggregate `{glossary}` output omits excluded dictionaries;
  explicit single-dictionary and selected-glossary mappings retain them.
- Google Drive sync has Hoshi (`gdrive`) and TTU (`ttu`) providers. Hoshi is the
  default; existing TTU logins migrate to TTU. Hoshi uses Google Identity Services
  `AuthorizationClient` with `drive.file` and Android package/signing registration;
  when Play Services is unavailable, `GoogleDriveBrowserAuth` owns Google's browser
  authorization-code flow with S256 PKCE and the same scope. AndroidX Browser opens
  Custom Tabs; platform activities handle cancellation and the redirect.
  The auth window waits to regain focus before reporting a result,
  preserving sign-in across hidden activity recreation. Pending requests persist
  in DataStore and validate the redirect and state before a one-time code exchange.
  Browser tokens persist in DataStore
  under `noBackupFilesDir`, refresh independently of Play Services, and are cleared
  locally on sign-out. Debug/release browser client IDs and redirect schemes come
  from ignored `secrets.properties` or build environment variables; no client secret
  is used. TTU retains its device-code authorization. Preferences remain DataStore-backed.
  Native and browser authorization failures use the shared error-details dialog
  and diagnostic history; user cancellation remains silent. Browser token errors
  retain HTTP status and OAuth diagnostics without recording token response bodies.
  When native token acquisition requires user consent, the persisted connection
  flag clears before the original authorization-required failure reaches the UI,
  exposing Connect again. Silent refresh success, temporary failures and cancellation
  keep the connection; discovering revoked consent does not reset library bookkeeping.
- `GoogleDriveClient` shares cancellable HTTP transport, token refresh and network
  preflight. Hoshi connections use 60-second connect/read timeouts; TTU uses
  10 seconds. `GoogleDriveSyncHandler` owns the native Drive layout and changes
  feed, while `TtuDriveHandler` retains TTU folder/file discovery and cache behavior.
  TTU remote books remain `RemoteBookEntry` values; automatic TTU refresh suppresses
  offline, timeout and socket errors, while manual, HTTP and TLS errors remain visible.
- Hoshi v1 uses `Hoshi Reader/state/<book>.json`, `state/.shelves.json`, and
  immutable files under `books/<book>/<generation>/`. `SyncStorage` keeps
  `Books/.sync.json` records and atomically applies merged sidecars. The manager's
  `drive-sync.json` caches folder IDs, change cursor and per-document versions.
  Generations separate reimports; deletion wins within a generation and session
  deletions merge across generations. Unknown format versions prevent cursor
  advancement and file work until a successful full state pass.
- `GoogleDriveSyncManager` performs targeted synchronization on open, 120-second
  foreground polling and 30-second local-edit debounce in release builds; debug
  builds use 5-second polling and 2-second debounce for testing. It also performs
  network-restored passes, immutable upload/download and stale-file cleanup. Process lifecycle state
  and sync settings jointly gate polling, including cold background launches;
  a Hilt WorkManager job performs the final background pass
  with a connected-network constraint. A state task owns its file-work handoff,
  and the file task includes the follow-up state publication and any resulting
  file passes. Publication requires a fresh full state pass after upload, even
  when another pass is running or a reader-open sync supersedes it; the worker
  waits for this chain to finish before returning success.
  State and file failures, including post-upload publication failures, propagate to the
  worker: temporary network/server failures and HTTP 403/429 rate limits retry,
  even when another book or file has a permanent failure, while authorization and format
  failures wait for user action. Foreground run/book/file failures retain original
  causes in error details and diagnostic history; file aggregates record each original
  error before selecting a retryable cause for the worker. Cloud-book opening and
  manual library refresh preserve the original exception across repository boundaries.
  Hoshi cloud books are local metadata placeholders: tapping downloads the EPUB,
  Delete Local retains their cloud state, and Delete Everywhere archives sessions
  and publishes deletion. Covers and Sasayaki match data download automatically.
- Bookshelf and Statistics batch automatic change notifications into one reload two
  seconds after the first notification, and skip it while the reader's sync callback
  is registered. Statistics archive-count notifications also skip reloads while reading.
  Pending delayed reloads belong to the ViewModel scope; normal screen-entry refreshes
  reload the current data after returning from Reader.
- Synced bookmarks, highlights, sessions, playback and matches apply to an open
  reader through existing reader/controller boundaries. Pending saves flush before
  reconciliation; bookmark restoration suppresses local writes, and remote deletion
  or replacement closes the reader after saving statistics and stopping audio.
  Every Hoshi foreground return syncs the open book; backgrounding and reader
  exit flush session changes before synchronization. TTU retains its ten-minute
  foreground threshold.
  Books backup restore stops sync, resets connection bookkeeping, then restarts;
  a failed restore disables sync. Android profile, language and audio URI fields
  stay local when remote state is applied.
- Audio playback uses Media3/ExoPlayer with controller/repository boundaries.
- Sasayaki accepts MP3, M4B/M4A, and Ogg Opus audiobook sources. One repository
  inspection returns format, metadata, chapters, and static duration for
  seekable sources before playback starts. M4B inspection reads MP4 metadata, `moov/udta/chpl`, and
  `mvhd`; Opus inspection reads OpusTags and derives duration from the final
  Ogg granule position after pre-skip without invoking Android's platform
  metadata reader. MP3 keeps the platform metadata/duration path and has no
  app-level chapter parser. A provider that exposes only a non-seekable stream
  may leave static duration or container-only metadata unknown until playback
  preparation. Displayed artist normalization remains `ARTIST`, then
  `ALBUMARTIST`, then `AUTHOR`.
- Sasayaki Japanese transcription uses `NakuruTranscriptionBackend`, directly bound
  through Hilt to `SasayakiTranscriptionBackend`. The Nakuru C API is called through
  JNA with 16 kHz float PCM from the repository-owned FFmpeg decoder.
  DataStore-backed Lightweight/Balanced/Fast presets map to Nakuru's three
  performance levels; Balanced is the default. Each start/resume snapshots the
  preset, and selection is locked while running.
  `libnakuru.so` (arm64-v8a, armeabi-v7a) and `nakuru.pack` download on demand
  into `noBackupFilesDir/Nakuru/`. Installed `nakuru-runtime.json` and
  `nakuru-model.json` pin URLs, sizes and SHA-256 hashes. Other ABIs show a localized
  unavailable message. The FFmpeg decoder downloads to
  `noBackupFilesDir/SasayakiRuntime/<abi>` using content-addressed filenames from
  `transcription-audio-runtime.json`; no inference or audio libraries are bundled
  in the APK. All missing resources share one consent and byte-weighted progress
  flow. Native files become read-only before writing and are atomically published
  after verification, then loaded by absolute path. Downloads are excluded from
  Android backup. Hash-specific partial files survive interruption/cancellation and
  app restarts. Each file gets up to three attempts with cancellable 1/2-second
  backoff for connection/read failures and HTTP 408/429/5xx. Resume requests use
  Range and validate Content-Range and catalog sizes; a full 200 response restarts
  the file. SHA-256 still gates atomic publication. Read-only native checkpoints
  are copied through a new descriptor marked read-only before writing, never made
  writable again. Terminal failures carry the file, catalog URL, received/expected
  bytes, attempt count and original cause into localized UI details and Diagnostics.
  This is the GitHub APK distribution path; a Play distribution
  would require Play Feature Delivery.
  The minimal FFmpeg 9.0.2 JNI decoder reads SAF/private-file descriptors with
  offset/length bounds, downmixes/resamples to 16 kHz, and preserves source timestamps.
  Native reads return at most 4096 samples; a bounded channel queues at most
  sixteen chunks for Nakuru. Cancellation closes the decoder, descriptors and
  recognizer. CMake verifies the upstream source archive SHA-256 and builds only
  local audio components, with no CLI, network protocols, encoders or video decoders.
  FFmpeg's license text remains an input to component builds and publication.
  Transcription duration prefers the platform's first audio-track duration,
  then the parsed M4B/Opus container duration, then media metadata.
- Application startup launches `LegacySasayakiResourceCleanup` in application scope
  on the IO dispatcher. It removes the retired ReazonSpeech model directory and
  hash-named ONNX/sherpa libraries and partial downloads, preserving Nakuru, FFmpeg
  and all book data. It does not follow directory symlinks. Each failed removal
  records its path and original cause in Diagnostics; unfinished removals retry
  on the next process start without a migration flag or blocking the first frame.
- A process-wide `SasayakiTranscriptionCoordinator` serializes transcription,
  checkpoints `sasayaki_transcript.json` approximately every 15 seconds, and
  runs one conflated matching worker alongside Nakuru recognition. The first text batch
  requests a match immediately; subsequent updates are throttled to 15 seconds
  and consume the latest immutable token snapshot. Pausing flushes unmatched
  batches; completion and explicit realignment perform a final full-snapshot match.
  Match publication has its own revision and does not enter the blocking
  `Aligning` stage during recognition. Match sidecars use atomic replacement. The sidecar keeps iOS's
  `through`, `duration`, and timed-token schema with an optional audio-source
  identity to avoid resuming a different file of the same duration. Atomic
  replacement retains the previous checkpoint on interruption. `BookWorkRegistry`,
  keyed by canonical book paths,
  joins active work before local/cloud book deletion and sync generation replacement,
  before acquiring the shared book storage lock. Clearing transcription preserves
  existing matches; completed transcripts can be realigned without ASR.
  Typed failures distinguish audio access, model/runtime resource preparation,
  speech recognition, book matching, and transcript storage; the UI maps each
  category to a localized message, followed by the original diagnostic details.
  Unknown failures also show the original exception
  and cause chain in the UI, and Logcat records the full stack with its operation
  stage and category.
- Match sidecars record `source` as `subtitles` or `transcription`. Legacy Android
  matches without `images` remain readable; new Android matches write an empty
  `images` array for iOS decoding. Image entries received from iOS are retained
  on save but are not used by Android's image-pause handling. Legacy Android
  transcription matches are recognized by their chapter-offset cue IDs; this
  provenance selects the default matching tab without overriding a manual tab
  choice in the current Reader session. Transcript presence alone is not used.
  A Reader-scoped subtitle export ViewModel snapshots either source through an
  injected repository, which owns UTF-8 SRT cache files and SAF output I/O.
  A Reader-owned launcher stays registered when the sheet closes, saving via
  CreateDocument or sharing a temporary read-only FileProvider URI; a pending save retains its snapshot path in SavedStateHandle.
  Exports contain matched text and original audio timestamps, without playback delay/rate
  adjustments. Shared snapshots use separate cache paths and expire after seven days on next export.
- The Reader-route Hilt ViewModel exposes transcription state and delivers
  match revisions during transcription even after the sheet closes. Reader coalesces
  match snapshots, including synced matches, until lookup, image holds, and restoration finish. Data refreshes
  preserve playback/hold state and repaint changed cues without navigation; reader
  reattachment explicitly restores the current cue. Live VN cue updates defer
  pagination changes until the next navigation and preserve the current reveal.
  Reader owns audio-source
  binding and the combined keep-screen-on flag. Closing the sheet or backgrounding
  the app does not actively pause inference. Removing the Reader route clears
  its ViewModel, which pauses and saves the task; configuration recreation keeps
  that ViewModel. This remains process-bound work without a foreground service or
  WorkManager guarantee: Android may freeze or reclaim the background process,
  and a later start resumes its saved checkpoint. Transcription reuses the
  audiobook card's imported source;
  verified cached models do not emit download progress. The model store requests
  confirmation only after verifying the cache and before opening a download;
  the coordinator suspends until the Reader confirms or cancels. Cancelling
  before any new transcription leaves existing sidecars unchanged.
  `SasayakiSource` shares chapter exclusions with SRT matching.
  `NakuruTranscriptAligner` reuses parsed book text, normalized characters and
  chapter/sentence boundaries for a run, aligning the complete immutable token
  snapshot each time. It uses normalized probes, chapter anchors and bounded
  edit alignment, with timing/length budgets and short-gap interpolation when
  cutting display cues. Later snapshots may revise earlier cues. Matching runs
  on the Default dispatcher with cooperative cancellation; parsing and atomic
  match persistence remain repository-owned I/O. Only successfully saved results
  are published. Match coverage is summed matched character lengths divided by
  the parsed book character count. Transcript sidecars contain no engine selector;
  existing unknown fields are ignored by the normal sidecar parser.
- Sasayaki audiobook playback is owned by a Hilt-backed Media3
  `MediaSessionService`. The service `onCreate` lifecycle creates the active
  ExoPlayer and MediaSession, but Reader load paths do not connect to the
  service or restore media into the player. The first explicit audio control
  request connects to the `MediaSessionService`, restores the active audio
  source into the service player, and then runs the requested command so Reader
  restoration cannot leave a paused system media notification. The service
  runtime owns the active Sasayaki playback controller and active book id.
  Reader UI attaches/detaches
  cue sinks and sends explicit stop on reader exit; Android media controls and
  notification return actions route through the same service-owned session.
  Until Reader UI is fully MediaController-based, the runtime keeps one
  process-local controller connection after entering the MediaSessionService
  lifecycle, uses Sasayaki's foreground playback request state to distinguish
  user-paused task removal from ongoing background playback, clears the active
  service player before stopping paused playback on task removal, and otherwise
  follows Media3's ongoing-playback service semantics. Playback persistence
  uses the application scope with the
  injected IO dispatcher rather than Reader's Compose scope, and saves are
  serialized with latest-snapshot conflation.
  Background playback uses Android's `mediaPlayback` foreground-service path
  inside the Media3 `MediaSessionService`; Media3 owns foreground-service
  start/stop and Sasayaki does not call `startForegroundService()`,
  `startForeground()`, `stopForeground()`, `stopSelf()`, or `stopService()`
  directly for this lifecycle. Sasayaki customizes notification rendering
  through a Media3 `MediaNotification.Provider` using the service MediaSession
  token and Media3 player-command PendingIntents for transport controls, and
  the ExoPlayer uses local wake mode for long-running playback. Explicit
  Reader exit requests stop playback and clear the service player so a stopped
  session or notification cannot outlive the user-visible Reader playback
  session.
  If Android reports `ActivityManager.isBackgroundRestricted()` for the app,
  the platform treats background work as user-restricted; this can prevent
  media foreground-service startup after the Reader activity leaves the
  foreground, so the app must treat long-running background playback in that
  state as a device/user restriction rather than an in-process lifecycle
  guarantee.
- Update checks use WorkManager unique work, with worker dependencies supplied
  by Hilt's WorkManager integration. Release metadata requests try GitHub's API
  directly, then `ghproxy.vip`; APK download mirrors are configured separately.
  The HTTP client accepts JSON media types before release parsing and retains
  the final response URL, HTTP status, content type, rate-limit/retry headers
  and response body on failure. If every API candidate fails, the repository
  retains each attempted URL and original cause/stack in the terminal error
  shown to users and saved in runtime diagnostics.

## Native And Rust Build

The Android app currently has three native stacks:

- `app/src/main/cpp/CMakeLists.txt` builds the hoshidicts JNI bridge from the
  `third_party/hoshidicts-kotlin-bridge` submodule.
- `app/src/main/rust/hoshiepub` builds the Rust EPUB parser through UniFFI.
- Nakuru's pinned native library is downloaded and verified on demand, then loaded
  through its JNA C API. FFmpeg builds independently through
  `tools/build-transcription-audio.py` and has a checked-in, content-addressed
  catalog. Publish its binaries before shipping APKs that reference a new catalog;
  app release CI verifies their deployed hashes. Neither inference nor FFmpeg
  native code is bundled in the base APK. Native JNI entry points and the Nakuru
  JNA ABI are retained under R8.

Current build wiring lives in `app/build.gradle.kts`:

- Registers generated UniFFI Kotlin output under
  `build/generated/source/uniffi/main/kotlin`.
- Registers generated Rust JNI libraries under debug and release `jniLibs`
  directories.
- Builds the Rust host library for JVM tests and UniFFI Kotlin generation.
- Runs UniFFI bindgen from the host Rust build.
- Builds Android Rust libraries with `cargo-ndk` for debug and release ABIs.
- Wires generated sources into Kotlin compilation and Rust host libraries into
  JVM tests.
- Uses JNA AAR for Android packaging and JNA jar for JVM unit tests.
- Uses Java 17 targets and KSP-backed Hilt code generation for the app graph.
- Pins CMake 3.31.6 for local Android builds and both CI/release workflows,
  matching the minimum required by the tracked Glaze dependency.

Hard constraints:

- `app/src/main/rust/hoshiepub/uniffi.toml` must keep
  `[bindings.kotlin] android = true`.
- `cargo-ndk` should build library targets only and must not cross-compile
  UniFFI bindgen or other host binaries.

## Validation

Durable validation commands, emulator data-safety rules, test data, and manual
QA matrices live in `docs/VALIDATION.md`.

## Content Input

- `HoshiTextFields.hoshiContentKeyboardOptions` supplies content-language IME locale hints,
  keyboard type and focus behavior independently of app display language. Dictionary search
  uses its current content profile with the Search action; pronunciation written-form and
  reading fields request Japanese. IMEs decide whether to honor the locale hint.

## Japanese TTS

- `features/tts` is an independent Hilt feature. `TtsRepository` persists source,
  Japanese voice, engine speech rate, independent Hoshi playback speed, pitch, provider parameters, playback sentence delay, prefetch
  depth, named self-hosted server profiles, the global `useRubyReadings` flag (default true), and scoped text-replacement entries in the `japanese_tts`
  Preferences DataStore. It does not extend Sasayaki
  sidecars or sync payloads. UI events submit setting transforms; the repository applies
  them to the latest preferences inside one DataStore transaction, so independent edits
  do not replace newer settings with a captured UI snapshot.
  `routingBySource` owns voice selection, including when dialogue routing is off;
  `TtsSettings.voice` is derived from its narration entry. Custom profiles own their rate;
  installed/built-in sources share the engine speech rate.
  Cloud API keys are AES-GCM ciphertext bound to the provider ID, using a non-exportable
  Android Keystore key through `TtsCredentialCipher`; an unavailable device key
  requires the API key to be entered again. UI fields never read keys back or put them
  in saveable state, URLs, logs or visible errors.
- Optional narration/dialogue routing is stored per Source in `routingBySource`, default
  off. `TtsVoiceRouting.initialized` distinguishes first activation (both voices inherit
  the current voice) from reactivation (the dialogue choice is retained). The selected
  narration voice is read from the same source selection used when routing is off. Both routes share credentials,
  provider parameters and Hoshi speed; Voice presents shared style/role and pitch/rate
  capabilities. Connection tests omit routing and test only the draft source.
  `TtsDialogue` finds complete paired Japanese quotes before chapter sentence slicing,
  retains nested/cross-paragraph dialogue, resets at each chapter and treats unmatched
  markers as ordinary text. Sentence-local UTF-16 role ranges retain the original
  matchable Reader offsets. Lookup selection matches source text at its normalized
  anchor, preserving punctuation/symbol roles and distinguishing repeated occurrences;
  chapter parsing and selection projection stay on the book repository's IO dispatcher.
  Text replacement/ruby emission keeps original roles, including sentence-end zero-width
  glyph readings. A replacement spanning role boundaries belongs to its first non-quote
  source character. Original text, highlighter and Anki sentence text stay unchanged.
  `ttsSynthesisPlan` resolves two voices within one Source, coalesces adjacent identical
  effective voices and attaches punctuation-only ranges to spoken content. Custom voice
  overrides are request-local source selections; adapters read the effective voice/rate
  through `TtsSettings`, without copying speaker state into connection profiles.
  OpenAI's omitted voice remains the server default; its first catalog entry is not
  assumed to be that default. Optional discovery is not a synthesis prerequisite.
  Ordered effective configurations/text key one sentence cache entry, preserving the
  32-sentence bound; all sentence parts must finish before playback. Role changes do not
  alter sentence navigation, image holds or add an app-level intra-sentence pause.
  Export first uses lossless MP3/WAV concatenation. Only incompatible sample formats
  trigger the Hilt-injected `AndroidTtsExportFallback`: Media3 Transformer normalizes
  each part to 24 kHz mono and writes one AAC/M4A file. Transformer cancellation completes
  on its looper before temporary inputs/output are removed; no additional output option
  or Hoshi-speed processing is introduced.
- The Hilt-singleton `TtsBookRepository` reads complete chapter source on demand, including
  file-backed cached EPUB chapter shells, on the injected IO dispatcher. Its four-entry LRU
  cache is keyed by book instance and chapter, shared by playback, lookup/replacement
  previews and Anki. A reopened book gets a fresh source identity. Opening Reader only
  binds the book; opening its TTS sheet prepares the reading position without starting audio.
  Navigation loads the requested complete chapter and its nearest readable neighbors,
  skipping empty, non-linear and navigation chapters. Playback state holds one chapter
  and explicit neighbor indices; prefetch crosses chapters while retaining its sentence bound.
  `TtsText` retains
  matchable source offsets and UTF-16 ruby ranges, sentences and image boundaries.
  Chapter extraction uses the declared MIME type: XML parsing for XHTML preserves
  self-closing script/style boundaries; ordinary HTML retains HTML error recovery.
  Literal dictionary matching prefers longest surfaces, then book scope over global
  scope; ruby is used where no custom replacement overlaps and the global Use Ruby readings
  preference is enabled. Disabling it leaves original base text for the engine and suppresses
  zero-width image-glyph readings; custom replacements still apply. Playback, preview and Anki
  exports all read this flag through `TtsAudioRepository`, retaining source ruby/ranges for
  later re-enabling. It participates in synthesis invalidation and audio cache identity. Replacement values accept
  any plain text, preserving whitespace; empty replacements delete their matched source ranges.
  Empty synthesis plans return no playback files without discovering voices or calling engines.
  The controller traverses these sentences in its cancellable preparation loop, retaining image
  presentation and single-sentence boundaries without adding sentence delays; empty exports
  report `EmptyText` and produce no media file. Matching is a single pass over the
  original sentence; inserted text is never matched again. Literal replacement is the default. `TtsRegexReplacement` uses RE2/J with a bounded compiled-rule cache,
  numeric `$0`/`$1` capture templates and backslash escaping. Invalid patterns/templates
  are rejected on save; draft validation retains the original inline diagnostic through
  pure formatting, without recording a runtime error. Zero-width matches are ignored. Literal match ranges and usable
  ruby are protected from regex matches; each pattern scans the original sentence with
  non-overlapping matches, then rules at the same start prefer the longest match and book
  scope on ties (remaining ties retain saved order). Matches across dialogue boundaries
  retain first-non-quote ownership. Preview computes on Default using the same `TtsText`
  transform and passes its immutable preferences snapshot to synthesis. The editor retains the
  last result and action availability during recomputation; Preview clicks wait for the current
  result, and further edits cancel the queued click. Save validates the current draft through
  the repository without waiting for preview computation. Both literal/regex
  editors display before/after text; settings entry adds editable preview text. Replacements affect synthesis
  only, preserving original sentence boundaries, offsets, lookup text and Anki sentence text. Mining rebases ruby against
  the selected source occurrence, including full
  VN source sentences, rather than looking up the first matching text.
  Image-backed ruby uses zero-width insertions, emitted once at each source position;
  only positive, in-bounds ruby ranges may advance the source cursor. Playback text
  preprocessing, cache hashing and file access run on the repository's injected IO
  dispatcher, including calls made by the main-thread playback controller.
- `AndroidTtsEngine` serializes installed-engine discovery and `synthesizeToFile`,
  filters Japanese voices, bounds initialization/utterance waits, splits oversized
  inputs, and accepts files only after completion. `TtsEngineRouter` dispatches installed
  engines to this adapter and the five explicit online sources to `OnlineTtsEngine`:
  Edge Read Aloud WebSocket, Azure Speech REST, Microsoft Translator, Google Cloud
  Text-to-Speech REST and Google Translate's unofficial speech RPC. Network-required voices and unofficial
  sources are labeled in the UI. No voice model is bundled and failures never select
  another provider automatically.
  `CustomTtsEngine` is the third router backend for `custom:` IDs. OpenAI-compatible
  profiles use a base URL including the API prefix and `/audio/speech`; VITS Simple API
  uses the server root, `/voice/speakers`, and model-specific synthesis routes for VITS,
  W2V2-VITS, Bert-VITS2 and GPT-SoVITS. Voices retain model-type/speaker-ID identity and
  exclude advertised non-Japanese and audio-conversion models. JSON requests use exactly
  `application/json`: VITS Simple API compares this header literally and otherwise parses
  an empty form. VITS length is inverse engine rate; GPT-SoVITS uses server rate and an
  optional existing reference preset. OpenAI model/voice IDs can be entered manually;
  voice discovery is optional and never disables manual synthesis/preview controls. Unsupported
  discovery (404/405) may cache an empty list; transient failures remain retryable and are not
  persisted as empty catalogs. Only WAV/MP3 complete
  responses enter the existing audio pipeline. Self-hosted calls allow 180 seconds, preserve
  proxy path prefixes, disable redirects, use normal TLS validation, and share private HTTP
  host rules with AnkiConnect through `network/PrivateHttpHost`. Credentials are encrypted
  against each profile ID; protocol changes clear the draft's old credential. Named profiles
  independently persist model, voice, rate and format.
  Saving a newly created profile atomically selects it through `TtsRepository`;
  `TtsViewModel` stops preview and refreshes its catalog after successful persistence.
  Editing an existing unselected profile does not switch sources.
  Cloud and custom configuration sheets share `TtsConnectionTestControls` in a fixed
  footer above their Save/Cancel actions. A reserved status area above the test button
  displays localized success or failure without extending the scrolling form; failures
  take precedence over previous verification. Only an active connection test animates
  the button indicator; e-ink uses a static Material hourglass.
  Source configuration edits connection/model options and an optional OpenAI voice-ID list.
  OpenAI model discovery is anchored to the editable Model ID field: its trailing action
  loads `/models` and offers its IDs in the shared themed dropdown, even for a sole
  result. Discovery does not change the entered ID until selection. Missing lists leave
  manual entry available; model loading stays local to the field instead of using the
  voice-loading status.
  Output format is edited only among the Voice tab's parameters alongside other
  supported providers. The Voice tab separates its scrolling controls from a fixed Preview
  footer that consumes the bottom navigation inset. Parameters have no separate heading or
  expansion toggle; standalone settings consume scaffold
  padding before applying this footer inset.
  Provider pause switches reuse `ReaderSheetSwitchRow` with optional supporting text rather
  than individual cards; output format/sample rate also use plain rows, and remaining
  parameter cards use compact vertical padding. Shared
  slider heights and existing behavior-setting rows retain their defaults.
  Cloud/self-hosted synthesis defaults to MP3; users can also choose WAV.
  Installed Android engines generate WAV.
  Voice owns speaker selection. VITS speaker names come from `/voice/speakers`, with
  model-type/ID retained as secondary labels;
  configured OpenAI IDs supplement discovered voices without duplicating IDs or overriding
  server-provided labels. They are available during optional discovery and after failure.
  `TtsSynthesisConfiguration` explicitly identifies the active generation inputs and
  excludes display names, available voice-ID lists, other sources, prefetch count,
  Hoshi playback speed and sentence delay. Both playback invalidation and audio caching
  use it; changing sentence delay affects the next boundary without restarting synthesis.
  Catalogs are stored by profile ID and connection fingerprint without timed refresh.
  Missing `custom:` profiles fail configuration rather than falling through to Android.
  Japanese voice filtering accepts both `ja` and `jpn` locale language codes (including
  Samsung's ISO 639-2 locales). `AndroidTtsEngine` caches installed-engine catalogs in memory
  by requested engine ID, keeping automatic selection separate from explicit packages.
  Cached catalog reads bypass `AndroidTextToSpeech`'s serial platform synthesis queue;
  cold discovery is coalesced, and failed/cancelled refreshes retain the last catalog.
  Playback, preview and export reuse these catalogs, including narration/dialogue routing.
  The platform adapter also reuses native `Voice` objects until discovery or an engine
  connection change, avoiding a platform voice-list query for every synthesis request.
  TTS panels force installed-engine discovery on resume and manual refresh so returning
  from system voice installation updates their available voices. Device catalogs are not persisted.
- Online/self-hosted voice lists are shared by panel, preview and synthesis through the
  Hilt singleton `TtsVoiceCatalogCache`. It owns bounded memory storage and per-configuration
  request coalescing; network waits do not hold the metadata lock, so cached reads and
  other source requests remain available during refresh. Cancellation/failure retains the
  last successful catalog. `TtsVoiceCatalogStore` owns atomic JSON files on the adapters'
  IO dispatcher.
  Catalogs have no age-based refresh. Source configuration fingerprints prevent reuse after
  region/key changes without storing credentials in catalog files. Missing/corrupt catalogs
  are fetched again, failures are not cached, and explicit error retry or saving/deleting
  the active source's cloud credentials forces a fetch. Editing another source's cloud
  credentials does not refresh the active catalog or stop its preview.
  Every Voice picker exposes a fixed manual refresh action above the list;
  it bypasses the catalog cache without changing voice preferences or audio caches.
  Refresh failures retain a valid previous catalog and show the localized error in the picker.
  `TtsViewModel.catalogLoading` disables repeated refresh requests independently of preview
  readiness, keeping manually configured OpenAI voices usable during optional discovery.
  Warm panel openings retain the catalog and do not enter loading state;
  installed-engine discovery still refreshes so newly installed engines remain selectable.
- `OnlineTtsProtocol` owns service JSON/SSML, Japanese catalog filtering, UTF-8 length
  limits and Edge framing. `OnlineTtsTransport` owns bounded HTTPS/WebSocket requests;
  cancellation closes the active call/socket, and only a completed Edge turn may return
  audio. Source adapters synthesize chunks serially and remove partial files on failure
  or cancellation. Azure and Microsoft Translator share Speech REST synthesis and expose
  advertised styles/roles, intensity and exact silence controls; Google Cloud exposes gain, encoding, sample rate and device profile. Edge
  supports prosody rate/pitch/volume; Google Translate exposes only normal/slow speech.
  Pitch controls use provider units and omit unsupported Google voice families.
  Provider silence is bounded to 0–1000 ms and playback sentence delay to 0–2000 ms.
  `TtsPauseSlider` uses these fixed ranges in 10 ms increments.
  `TtsMp3` preserves MP3 without transcoding, joining long-sentence MPEG frames after
  removing per-file ID3 and seek/duration headers; WAV chunks use `TtsWav`.
- Cloud configuration, self-hosted configuration and model discovery share credential draft
  resolution: blank input retains ciphertext, explicit removal clears it, and replacement
  keys are trimmed/validated before encryption with the existing source-bound Keystore key.
  Cloud configuration exposes a separate confirmed Delete key action. Its repository
  transaction removes only that provider's saved ciphertext from the latest preferences,
  retaining region and other settings without submitting unsaved form input. Success clears
  the key draft and connection-test feedback; failure retains the saved key and permits retry.
  `TtsAzureRegion` lists the supported public-cloud neural TTS regions with stable region codes
  as display labels. Azure configuration uses the shared themed dropdown with compact rows and no
  default selection.
  Save/Test are gated by a supported region in both UI and ViewModel, while key deletion
  stays independent. Translator continues using its authorization response's region.
- `MicrosoftTranslatorSession` obtains ephemeral authorization from the unofficial
  Translator endpoint using its public client signing protocol. Tokens and their returned
  Speech region stay in memory, never in settings or exported media. A mutex shares token
  acquisition; expiry refresh has a 60-second margin, and a rejected Speech token gets
  one refresh/retry. The source never reads Azure subscription keys. Catalogs use the
  returned regional Speech endpoint, so offered voices/styles reflect that service
  rather than a hardcoded promise of Azure-wide availability.
- `TtsPlaybackController` exposes immutable StateFlow, owns sentence navigation,
  configuration invalidation and configurable sentence prefetch, and rejects stale operations
  after stop/book/source changes. Previous/next preserves the playing state; explicit
  start-at/replay still starts audio. Paused navigation invalidates preparation and lookup
  resume actions, presents the selected sentence directly, and synthesizes nothing until
  playback resumes. Decoder completion is recorded for the prepared sentence even while
  paused, so resuming after a late end event advances rather than replaying an exhausted
  buffer. Final/single-sentence completion remains stopped. The output inspects current
  playback state in Media3's batched `onEvents` and ignores detached players.
  `started` remains scoped to the Reader route/book session, so the
  default-off global Behavior setting `volumeKeysSeekSasayaki` controls both audiobook
  seeking and TTS sentence navigation. TTS becomes eligible only after that session has
  explicitly started narration; the existing preference key/value remains unchanged.
  Reader's existing hardware-key resolver gives enabled popup term navigation priority,
  then audiobook/TTS navigation, then page turning; direction reversal and repeat/key-up
  handling stay shared.
  `TtsAudioRepository` keys temporary playback audio by
  configuration (excluding prefetch depth and Hoshi playback speed) and resolved spoken text; independent export
  files are owned by their caller and cleaned on cancellation, including cancelled dispatcher return delivery.
  Cache lookups use a short metadata lock independently of the serial synthesis queue,
  so ready audio never waits for another sentence's network request. The 32-entry audio
  cache tracks access order; a hit promotes the complete sentence playlist before eviction.
  This retains just-reused audio through the maximum 20-sentence prefetch horizon. Continuous playback
  keeps valid in-flight prefetch and rolls its horizon forward by the saved 1–20 sentence
  depth (default 5). Depth changes cancel/rebuild prefetch without pausing the current
  sentence or invalidating completed audio. Hoshi playback speed (0.5–3.0×, default 1×)
  updates the attached player live with unchanged pitch; it preserves pause, preparation and
  prefetch, and is reapplied when the service player is recreated. Synthesis settings/dictionary changes and stop
  still cancel it. Cache clearing serializes with
  synthesis so late completions cannot repopulate a cleared cache.
- `TtsPlaybackService` owns the Media3 ExoPlayer/session. `TtsSessionPlayer` exposes a
  nonseekable sentence transport without seconds or a fabricated duration, and represents
  synthesis/image holds as ongoing buffering only when a sentence has been explicitly
  started. Book source parsing leaves an empty session idle and not loading, including
  when the service survives a Reader exit or book switch. Media3 owns foreground notification and
  audio focus. Reader attaches an optional presentation callback using the existing
  cue-range commands and shared behavior settings; background progress needs no WebView.
  Explicit Reader exit/route disposal stops playback; configuration recreation retains it.
  `ReaderPlaybackPresentation` owns the ordered media-stop/chapter traversal shared
  by Sasayaki and TTS. Both use the same image-hold/fullscreen-dismiss wait and
  before-visible target restore; audio pause/resume stays in each playback adapter.
  Only natural forward playback inspects intervening media; manual jumps and zero
  image-hold duration restore directly. `ReaderTtsPresentation` separates active
  playback following from passive highlight restoration. Paused narration may only
  passively restore its highlight in the displayed chapter,
  without revealing text or moving the page. An explicit paused sentence jump instead
  reveals its target (including another chapter) through the shared direct-jump path. Chapter loads, image holds and post-restore
  reveal decisions read current playback/sentence state, rejecting stopped or obsolete
  presentations; returning to foreground uses the same policy. Restoration cannot
  reveal a TTS sentence while synthesis or its image presentation is preparing;
  the playback callback completes image holds before sentence reveal and audio.
- Reader’s existing top-right playback button routes its playing state and callback
  together through `ReaderTopPlaybackControl`: loaded audiobooks use Sasayaki, while
  books without an audiobook use TTS only after explicit playback in the current Reader
  session, using the same eligibility as the bottom playback controls. Pausing keeps both
  controls available; preview alone does not enable them, and closing/switching books
  resets eligibility. The shared `showReaderToggle` preference controls visibility;
  Sasayaki’s enable switch only gates audiobooks. TTS toggles resume/pause the current
  sentence. During lookup auto-pause, the button retains its pause icon and cancels
  pending auto-resume when pressed. Reader-initiated playback
  errors open the TTS panel so its existing error/recovery controls remain accessible.
- Reader only offers the independent TTS sheet when no audiobook is associated.
  Its fixed sentence controls and Voice/Replacements/Settings tabs use `ReaderBottomPanel`
  and `ReaderSheetControls`, without book metadata or sentence previews. The segmented tabs, group card,
  switch row and slider row in `ReaderSheetControls` are also used by Sasayaki, including shared
  surface roles and e-ink outlines. TTS Settings uses the same tab content with its
  own settings scaffold; the replacement list is lazy and keeps playback controls
  outside its scroll area. Additional provider choices use anchored `HoshiDropdownMenu`
  rows. Source and Voice share one compact card, with independent
  48 dp touch rows opening lazy selection lists in `TtsSelectionSheet`, which reuses
  `ReaderBottomPanel`. Sources group installed engines, online services and key-based
  cloud services without guessing installed engine capabilities; an unavailable saved
  source or voice remains visible under its original ID. The source picker exposes cloud
  configuration actions for key-based services and editable self-hosted profiles. Its
  system-settings action always appears above the Device engines group as a separate row,
  independent of the selected source. Credential forms reuse the same sheet chrome, preserve encrypted
  storage/blank-key retention, and test unsaved drafts with a public synthesis sample.
  Canceling disposes the draft and its coroutine. Source errors appear beside the voice
  card; shared behavior errors have a separate StateFlow and cannot show source failures
  in the Settings tab. `TtsPreviewState` exposes idle/preparing/playing so the theme-colored
  preview button can cancel preparation or stop playback; reading controls stop preview
  before using the existing sentence controller. Additional synthesis controls use
  source/voice capabilities. TTS sliders hide tick marks
  while retaining their discrete steps and current-value labels, without endpoint labels; shared slider
  and card defaults remain unchanged for Sasayaki.
  `TtsCatalogLoading` shares the catalog-loading presentation between the Voice tab and
  picker: e-ink uses localized status text, while other themes use the animated linear bar.
  Voice names use catalog identifiers unchanged. Online/offline status belongs to the
  selected voice and its catalog network requirement, not the source. Unknown saved
  voices keep their raw identifiers without an inferred connection status.
  `SharedReaderPlaybackSettings` edits the same Sasayaki settings
  repository from both panels; TTS Settings also exposes its own global Ruby switch above
  the shared controls. Its dedicated field update preserves other TTS preferences and reports
  write errors through the Settings error flow without clearing source errors.
  Reading controls emit field transformations that run against the latest repository state
  inside its DataStore update; TTS, Sasayaki, Reader appearance and audiobook sync controls
  do not write old full-setting snapshots. TTS permits only shared reading fields, preserving
  audiobook preferences and keeping its own synthesis settings. Extra sentence delay is owned by
  playback, is cancellable and is excluded from preview/Anki audio; provider silence
  controls are part of synthesized audio. Preview uses the Hoshi playback speed and applies
  changes without stopping or re-synthesizing; Anki exports retain the engine-produced audio
  without applying this player speed. TTS navigation remains sentence-based
  without a timeline or the audiobook skip-action setting.
  Missing associated files remain Sasayaki recovery cases. The standalone Nav3 TTS
  Settings route and dictionary remain available regardless of that association.
  Native text selection exposes Replace, which captures the existing full source
  sentence into the same TTS replacement editor used by Settings. Its title identifies
  TTS, scope uses a Material segmented control, and preview uses `HoshiButton`.
  Reader resolves the full source sentence before mounting the editor, falling back
  to the captured sentence on failure. The editor waits for its initial background
  text/validation result before opening the dialog. Recomputations retain the last
  displayed result, while playback requires a snapshot matching the current draft.
  Both creation entry points default to global scope; editing retains the saved scope.
- Root Reader lookup popups retain their source chapter and expose independent TTS
  visibility/playback state and typed bridge commands. The shared three-button toolbar
  routes to the active source: TTS replay stops after one sentence, toggle can cancel
  lookup auto-resume, and play-forward closes the popup. Dictionary child popups have
  no book source anchor and do not expose TTS controls. Audiobook controls remain unchanged.
- Anki `{sentence-audio}` has its own mining context field and selected-format media
  preparation. Associated audiobooks require an existing cue; otherwise TTS synthesizes
  the full source sentence. Missing audiobook cues never trigger TTS. Existing
  `{sasayaki-audio}` remains audiobook-only. Shared audiobook paths upload once, and
  a failed requested sentence upload prevents silently creating a card without audio.
