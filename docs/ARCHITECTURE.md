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
  routes remain full-screen outside that shell.
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
- Settings and small persisted preferences are stored behind DataStore-backed
  repositories.
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
- Book metadata, bookmarks, highlights, reading statistics, and Sasayaki data
  are persisted through book sidecar repositories and models.
- Statistics is always available from its top-level tab. Its settings and
  folder-keyed daily editor use that tab's Navigation3 back stack and
  entry-scoped Hilt ViewModels. Reader statistics display preferences remain in Reading Settings;
  the Sync and Statistics settings screens share the global sync preference.
- `BookStatisticsStore` is the shared Hilt singleton for reader statistics,
  transactional sync imports, daily edits, archive/restore, and dashboard reads.
  File operations run on the IO dispatcher behind one mutex and use atomic
  replacement. Reader saves submit only changed days, merge by modification
  timestamp, and respect in-process editor deletions so queued writes cannot
  undo a later edit. No deletion markers are added to the sidecar/sync format.
- Deleting a book first stores active dates and compatible metadata under
  `Books/statistics_archive/<folder>/`, with an optional JPEG cover bounded to
  240 px. Required archive failures preserve the source book. Import restores
  after external sidecars are written; normalized folder identity joins active
  and archived records by date without double counting. Equal timestamps keep
  the first input: existing archive on deletion, current book on restore.
  The archive directory is excluded from book discovery and TTU exports but is
  included in Books `.hoshi` backups.
- Statistics repositories combine local and archived sidecars for the dashboard
  and all-date book editors. The daily goal card combines a semicircular gauge,
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
  and the dashboard share the reset-time local-date provider; historical date
  keys are not rewritten.
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
  ready. Enabled term dictionaries categorized as `exclude` remain stored and
  manageable but are omitted from the replacement session.
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
  global display settings. Reading setting events submit transformations against
  the latest stored value, preserving successive edits during delayed persistence.
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
  and low-memory import remain in DataStore.

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
- Reader and lookup WebViews consume stable per-family CSS aliases and render
  specs containing installed faces, real weight/style values, variable axes,
  and the font-library revision. Generic system serif/sans-serif choices remain
  CSS-matched platform families because OEM builds may not include Noto; legacy
  Noto display-name values remain compatibility keys only. Publisher leaves EPUB
  family, style, and weight declarations intact.
- Reader rendering and lookup remain WebView-based to preserve iOS-aligned
  visible behavior.
- Reader layout modes are WebView-backed assets for paginated, continuous, and
  VN reading. Kotlin selects the asset, injects typed settings, and keeps
  persisted progress as chapter progress mapped to whole-book character count.
- Reader `bookinfo.json` sidecars persist whole-book/spine character counts plus
  optional iOS-compatible TOC fragment offsets and a first-appearance raster
  image inventory. A reader-facts schema version invalidates stale derived
  fields when their indexing semantics change. Contents rows, chapter progress,
  and chapter time remaining derive from one Kotlin-owned TOC range model;
  Gallery thumbnails and the fullscreen viewer reuse the existing safe EPUB
  resource path.
- Reader furigana mode is profile-scoped, with legacy hide-furigana booleans
  migrated to Hidden or Off. Shared selection consumes Toggle reveal taps and
  reveals whitespace-adjacent ruby groups; VN uses its existing source/clone
  projection to retain reveals when a screen is rendered again.
- Reader text semantics live in `reader-text-semantics.js` and are consumed by
  paginated, continuous, and VN assets for normalization, matchable character
  counting, raw character counting, and matchable-character checks.
- Paginated and continuous share live DOM ruby/text normalization through
  `reader-dom-text.js`; the mode assets keep thin public wrapper methods so
  existing reader commands and tests continue to call the same surface.
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
- Reader, Dictionary search, and Process Text lookup popups render through the
  shared `reader-popup-host.js` iframe stack and `ReaderLookupPopupWebBridge`.
  Kotlin owns popup payloads, resource handling, and native service bridges for
  audio, dictionary media, Anki, and external links; do not reintroduce Android
  native overlay popup fallback paths for these flows.
- Popup audio sources cross the iframe boundary as ordered name/URL pairs.
  `LocalAudioRepository` returns every enabled, ranked local candidate and
  `AudioRequestHandler` exposes their descriptive labels and deduplicated URLs.
  Popup JS owns the entry-scoped candidate cache and selected URL, so playback
  and Anki mining use the same choice; replacing or restoring popup results
  clears that state.
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
- Anki settings are stored per active profile in
  `Profiles/<profileId>/anki_config.json`. Schema version 2 owns one to three
  stable-ID `AnkiCardFormat` values, each with its own icon, deck, note type,
  field mappings, and tags; legacy single-format JSON is migrated and persisted
  as one default format. Popup mining, per-format duplicate checks, and opening
  existing notes all carry the stable format ID through the reader bridge and
  still go through the Anki repository/backend boundary. AnkiConnect opens
  notes with `guiBrowse`; AnkiDroid uses its browser deep link. At mining time,
  glossary-first and monolingual/bilingual definition handlebars resolve from
  the current profile's persisted term-dictionary order and categories without
  extending the popup mining payload.
- Google Drive sync uses Android/Google OAuth and Drive APIs through the
  repository/sync boundary. The Drive data source owns paginated folder listing,
  grouped sync-file discovery, bookdata upload/download, trash, cache clearing,
  and network preflight; Books keeps remote-only Google Drive books as
  `RemoteBookEntry` models rather than local `BookEntry` placeholders.
- Audio playback uses Media3/ExoPlayer with controller/repository boundaries.
- Sasayaki accepts MP3, M4B, and Ogg Opus audiobook sources. One repository
  inspection returns format, metadata, chapters, and static duration for
  seekable sources before playback starts. M4B inspection reads MP4 metadata, `moov/udta/chpl`, and
  `mvhd`; Opus inspection reads OpusTags and derives duration from the final
  Ogg granule position after pre-skip without invoking Android's platform
  metadata reader. MP3 keeps the platform metadata/duration path and has no
  app-level chapter parser. A provider that exposes only a non-seekable stream
  may leave static duration or container-only metadata unknown until playback
  preparation. Displayed artist normalization remains `ARTIST`, then
  `ALBUMARTIST`, then `AUTHOR`.
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
  by Hilt's WorkManager integration.

## Native And Rust Build

The Android app currently has two native stacks:

- `app/src/main/cpp/CMakeLists.txt` builds the hoshidicts JNI bridge from the
  `third_party/hoshidicts-kotlin-bridge` submodule.
- `app/src/main/rust/hoshiepub` builds the Rust EPUB parser through UniFFI.

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
