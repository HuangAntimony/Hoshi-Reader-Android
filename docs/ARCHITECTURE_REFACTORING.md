# Hoshi Android Architecture Refactoring Directions

Date: 2026-05-03

This document records technical-debt-oriented refactoring directions for Hoshi Reader Android. These directions are not mutually exclusive. They cover different layers and modules, and are meant to be selected and sequenced before implementation.

The goal is maintainability, testability, and Android architecture alignment. This is not primarily an iOS UI parity plan, although UI architecture and visual-system cleanup can be part of the work.

UI and interaction improvements are allowed when they are tied to the refactor itself: for example, when moving route state to Navigation3 fixes awkward back behavior, when extracting a ViewModel makes loading/error/empty states clearer, or when splitting a fragile composable removes duplicated UI state. These improvements should be explicit in the slice scope, preserve iOS-aligned user-visible behavior where it already matters, and include focused verification of the affected flow. Avoid broad visual redesigns that are unrelated to the structural change being made.

## Current Assessment

The project is no longer a small proof of concept. It has a clear main flow and a reasonable package split, but several screen-level files have become orchestration hubs:

- `features/bookshelf/BookshelfView.kt`: Bookshelf UI, tab/settings navigation, EPUB import, parsing, metadata/bookmark updates, dictionary lookup rebuild, and reader launch state.
- `features/reader/ReaderWebView.kt`: Reader UI, WebView lifecycle, chapter/page navigation, lookup popup creation, Sasayaki integration, system bars, screen-awake behavior, and JavaScript command dispatch.
- `features/dictionary/DictionaryView.kt`: dictionary settings UI, import flow, drag reorder state, repository calls, lookup rebuild, and error/loading state.
- `features/sasayaki/SasayakiPlayer.kt`: media playback, media session, cue matching timeline integration, persisted playback state, temporary popup playback, and Compose-observed state.
- `epub/BookStorage.kt`: book directory management, import extraction, sidecar JSON read/write, metadata/bookmark/bookinfo/Sasayaki persistence, and import naming policy.
- `dictionary/DictionaryRepository.kt`: SAF validation, temporary zip copying, native bridge import, config persistence, dictionary listing, and lookup-query rebuild.

There is also only one Gradle module, `:app`, and `app/build.gradle.kts` owns Android app config, release signing, CMake, Rust host build, UniFFI generation, `cargo-ndk`, dependencies, and test native library wiring.

## Android Architecture Baseline

The recommendations below are grounded in official Android guidance:

- [Guide to app architecture](https://developer.android.com/topic/architecture): separate concerns, drive UI from data models, assign a single source of truth, use unidirectional data flow, and use state holders for UI complexity.
- [UI layer](https://developer.android.com/topic/architecture/ui-layer): UI should consume immutable UI state and send events upward; `ViewModel` is the recommended screen-level state holder when state and business logic outgrow simple UI state.
- [Data layer](https://developer.android.com/topic/architecture/data-layer): repositories should expose app data, centralize data changes, abstract data sources, and be main-safe.
- [Domain layer](https://developer.android.com/topic/architecture/domain-layer): use cases are optional and should be introduced only for complex or reused business logic.
- [Compose state hoisting](https://developer.android.com/develop/ui/compose/state-hoisting): keep state close to where it is consumed, expose immutable state and events, and hoist screen state to a state holder when business logic is involved.
- [DataStore](https://developer.android.com/topic/libraries/architecture/datastore): consider migrating from `SharedPreferences`; DataStore exposes Flow-based reads and transactional writes.
- [Navigation 3](https://developer.android.com/guide/navigation/navigation-3): model navigation as an explicit back stack, display it through `NavDisplay`, retain state while entries stay on the stack, and support adaptive layouts that can show multiple destinations when appropriate.
- [Navigation 3 basics](https://developer.android.com/guide/navigation/navigation-3/basics): create a back stack of content keys and push or pop keys as users navigate.
- [Guide to Android app modularization](https://developer.android.com/topic/modularization): modularization improves ownership, encapsulation, scalability, and build behavior, but too many modules add overhead.
- [Baseline Profiles](https://developer.android.com/topic/performance/baselineprofiles/overview): cover startup and performance-sensitive journeys so ART can precompile common code paths.
- [Media3 ExoPlayer](https://developer.android.com/media/media3/exoplayer): Media3 provides a modern player abstraction with better buffering, streaming, customization, and updateability than framework `MediaPlayer`.

## Direction 1: Introduce Screen State Holders

Priority: high

Move screen-level state and business-event handling out of large composables and into state holders:

- `BookshelfViewModel`
- `DictionaryViewModel`
- `ReaderViewModel`
- optionally plain state holders for WebView-specific UI mechanics that should not live in a `ViewModel`

Target shape:

- ViewModels expose immutable `UiState` through `StateFlow`.
- Composables render state and send events.
- Repositories and use cases are injected into state holders.
- UI element state that is purely visual and local can stay in composables.

Candidate first slice:

1. Extract `BookshelfUiState`.
2. Move book list reload, import result handling, selected book/open reader state, bookmark save, and error/loading state into `BookshelfViewModel`.
3. Leave file picker launchers and `ActivityResultContract` in Compose, but pass selected URIs to the ViewModel.

Expected benefits:

- Less recomposition-time I/O orchestration.
- More reliable behavior across configuration changes.
- Cleaner tests for import/open/delete/sort flows.
- Smaller composables with clearer responsibilities.

Main risks:

- Reader state is sensitive because it spans WebView, JS, bookmarks, Sasayaki, lookup popup, and system UI. Start with Bookshelf and Dictionary before Reader.

## Direction 2: Split Data Sources from Repositories

Priority: high

Separate file/native/platform access from business-facing repositories.

Suggested book storage split:

- `BookRepository`: public book API and single source of truth.
- `BookFileDataSource`: safe directory traversal and sidecar JSON read/write.
- `BookImportDataSource`: SAF import, EPUB zip extraction, duplicate/title policy.
- `BookmarkDataSource`: bookmark-specific persistence and progress calculation.
- `BookClock`: Apple-reference timestamp generation, testable with fake time.

Suggested dictionary split:

- `DictionaryRepository`: public dictionary API.
- `DictionaryStorageDataSource`: `config.json`, dictionary directories, ordering/enabled state.
- `DictionaryImportDataSource`: content URI validation, temp zip copy, bridge import.
- `LookupQueryService`: active dictionary path calculation and native query rebuild.

Expected benefits:

- Clearer single source of truth per data type.
- Better test seams for corruption, duplicate imports, and import failures.
- Easier future sync/backup because sidecar persistence is isolated.
- Less risk when native bridge behavior changes.

Main risks:

- Sidecar JSON compatibility must be preserved.
- Import deduplication and iOS-shaped files need characterization tests before movement.

## Direction 3: Move Navigation to Navigation3

Priority: medium-high

Current screen switching is mostly controlled by nullable state and early returns from `BookshelfView`. This is workable now, but it will become harder to manage as Settings, Sync, Sasayaki, external EPUB open, and media-session return paths grow.

Use Navigation3 as the target navigation model. This should be a deliberate Navigation3 migration, not a temporary route enum that later needs to be replaced. The useful idea to borrow from Mori is its explicit root back stack and serializable route keys; do not borrow its unrelated KMP or visual-system choices.

Suggested routes:

- `MainRoute`
- `BooksRoute`
- `DictionaryRoute`
- `SettingsRoute`
- `SettingsDetailRoute(section)`
- `ReaderRoute(bookId)`
- `SasayakiMatchRoute(bookId)`
- future `SyncRoute`

Guidelines:

- Represent routes as serializable Navigation3 keys.
- Own the root back stack in an `AppShell`-level state holder, then render it with `NavDisplay`.
- Pass stable identifiers, not large `EpubBook` or `File` objects, through routes.
- Keep bottom/rail navigation in the existing adaptive shell.
- Preserve per-route state while entries remain on the back stack.
- Keep truly local modal sheets as local UI state.
- Preserve reader WebView lifecycle intentionally; do not let navigation refactoring accidentally reload chapters or lose progress.
- Do not introduce Anki routes or AnkiDroid integration as part of this refactor. Card creation is a later feature slice.

Expected benefits:

- Explicit back stack and destination model.
- Cleaner deep link handling for external EPUB open and media-session return.
- Easier isolated testing of destinations.
- A better foundation for adaptive layouts without burying route state in screen composables.

Main risks:

- Reader WebView lifecycle and initial bookmark restoration require careful migration.
- Navigation3 is newer than classic Navigation Compose. Keep the migration scoped to app routing and verify Android Back, media-session return, settings detail return, and reader close behavior.

## Direction 4: Replace Settings Stores with DataStore-backed Repositories

Priority: medium-high

Current settings stores use synchronous `SharedPreferences` APIs and are often read directly from composables or helper objects.

Suggested target:

- `ReaderSettingsRepository`
- `DictionarySettingsRepository`
- `AudioSettingsRepository`
- `SasayakiSettingsRepository`

Each repository should expose:

- `Flow<Settings>`
- `suspend fun update(transform: (Settings) -> Settings)`
- one-time migration from existing `SharedPreferences`

Use Preferences DataStore for small key-value settings, or typed JSON DataStore if stronger typed persistence is preferred.

Expected benefits:

- Settings become observable data sources.
- ViewModels can combine settings with other screen state using Flow.
- Writes become transactional and easier to test.
- Synchronous preference reads disappear from composables.

Main risks:

- Must preserve all existing preference keys and defaults during migration.
- Some UI currently expects immediate synchronous `load()`. Those call sites need a transition adapter or ViewModel-owned initial state.

## Direction 5: Modularize Gradually

Priority: medium

Do not split into many feature modules immediately. Start by extracting low-risk, mostly pure Kotlin boundaries.

Recommended first modules:

- `:core:model`: shared serializable/domain models that do not depend on Android UI.
- `:core:storage`: sidecar JSON and file-storage abstractions.
- `:core:epub`: EPUB parser API and reader-facing EPUB models.
- `:core:dictionary-api`: dictionary model and lookup/import interfaces around the native bridge.
- `:core:settings`: settings models and repositories once DataStore migration starts.

Later modules:

- `:feature:bookshelf`
- `:feature:dictionary`
- `:feature:reader`
- `:feature:sasayaki`
- `:feature:settings`

Native/Rust build logic should stay in `:app` until the Kotlin boundaries are stable, or move into a dedicated native module only after the build behavior is understood.

Expected benefits:

- Better encapsulation and ownership.
- Faster focused tests.
- Reduced accidental dependencies between feature code.
- Cleaner path toward build-logic extraction.

Main risks:

- Over-modularization can add build complexity and boilerplate.
- Native/JNA/UniFFI wiring may make early module splits expensive if chosen too soon.

## Direction 6: Rebuild Sasayaki Playback Around a Playback Controller

Priority: medium

`SasayakiPlayer` currently combines playback engine, media session, cue matching, persisted state, callback-to-reader behavior, and Compose-observed mutable state.

Suggested split:

- `SasayakiPlaybackController`: playback commands and observable playback state.
- `CueTimelineEngine`: cue lookup, next/previous cue, cue-at-time behavior.
- `SasayakiPlaybackRepository`: sidecar playback persistence.
- `AudioSourceRepository`: external URI/private-copy audio source handling.
- `SasayakiMediaSessionController`: media session integration.

Consider migrating the playback implementation from framework `MediaPlayer` to Media3 `ExoPlayer` after behavior tests are in place.

Expected benefits:

- Playback behavior becomes testable without reading source code.
- Media-session and seek behavior become less fragile.
- Future audio features have a stable foundation.

Main risks:

- Sasayaki behavior has subtle iOS-aligned semantics. Add behavior tests before replacing the player engine.
- Media3 migration should be a separate slice, not mixed with UI cleanup.

## Direction 7: Establish a Typed WebView and JavaScript Bridge Layer

Priority: medium

Reader, dictionary search, and lookup popup all create WebViews, inject scripts, intercept resources, evaluate JavaScript, and parse string results.

Suggested split:

- `HoshiWebViewFactory`: common WebView settings and security defaults.
- `WebResourceBridge`: EPUB resources, fonts, popup assets, dictionary images, audio request routing.
- `JavascriptCommand`: typed command builders for reader pagination, selection, popup history, and Sasayaki highlighting.
- `JavascriptResultParser`: typed parsing for progress, selection, and bridge responses.
- `ReaderWebBridge` and `LookupWebBridge`: feature-specific bridge APIs.

Expected benefits:

- Fewer duplicated WebView settings and bridge patterns.
- Less raw string JavaScript in composables.
- Easier testing of generated commands and parsed results.
- Safer changes to resource loading and security defaults.

Main risks:

- Reader WebView behavior is the app's most sensitive path. Migrate command families one at a time and run manual reader validation after each slice.

## Direction 8: Replace Source-String Tests with Behavior Tests

Priority: high, paired with other refactors

The project has many useful tests, but some tests assert source strings with `File(...).readText()` and `contains(...)`. These tests catch regressions but make internal refactoring unnecessarily expensive.

Suggested target:

- Keep behavior-oriented unit tests for pure logic.
- Add fake repositories/fake media controllers/fake WebView bridges where needed.
- Convert source-string tests to behavior tests as each area is refactored.
- Keep a small number of source-shape tests only when they guard security or build integration that cannot be exercised otherwise.

Candidate conversions:

- `SasayakiPlayerSourceTest` -> fake player clock/controller tests.
- UI source-string tests around layout choices -> Compose semantics or screenshot/instrumentation tests where practical.
- WebView command source tests -> typed command output tests.

Expected benefits:

- Refactors can change implementation without breaking unrelated tests.
- Tests explain behavior, not source layout.
- Critical paths become safer to change.

Main risks:

- Some Android framework behavior is hard to exercise in JVM tests. Use fakes or focused instrumentation tests instead of deleting coverage.

## Direction 9: Clean Up Build Logic and Add Performance Profiles

Priority: medium-low

The app build script currently owns release signing, Rust host build, UniFFI generation, Android Rust build, CMake, JNA test wiring, dependencies, and normal Android build configuration.

Suggested build cleanup:

- Extract Rust/UniFFI task registration into build logic or a separate Gradle script.
- Keep release signing configuration isolated.
- Add clearer task names and inputs/outputs where possible.
- Avoid broad task dependencies that rebuild native code unnecessarily.

Suggested performance work:

- Add Macrobenchmark/Baseline Profile coverage for:
  - cold start to Bookshelf,
  - EPUB import/open reader,
  - reader page turn,
  - dictionary search,
  - lookup popup open.

Expected benefits:

- Easier build maintenance.
- Less native-build surprise during normal Kotlin changes.
- More stable startup and reader/dictionary performance for users.

Main risks:

- Build refactoring can easily break CI/release packaging. Keep changes small and verify release/debug/native/test tasks.

## Suggested Sequencing

Recommended order if this is done incrementally:

### Phase 0: Characterize Current Behavior Before Movement

Scope:

- Add or convert only the tests needed to protect the next touched area.
- Record current behavior for Android Back, settings detail return, open-reader flow, external EPUB open flow, bookmark restoration, dictionary import/search, and lookup popup open.
- Identify any known UI or interaction problem that should be intentionally fixed as part of the next structural slice.
- Keep this phase small. It should create confidence for movement, not become a test rewrite project.

Why first:

- The current code relies on Compose state and early returns rather than a navigation framework. Before moving that state into route keys and state holders, the important user-visible flows need a baseline.
- This phase is especially important for reader behavior, where WebView lifecycle, bookmark restoration, and chapter state can regress without obvious compile errors.

Exit criteria:

- The next refactor target has behavior tests, characterization tests, or a written manual verification checklist.
- Any UI or interaction improvement included in the next slice has a concrete before/after expectation.
- No app behavior is intentionally changed.

### Phase 1: Introduce Navigation3 AppShell

Scope:

- Add Navigation3 dependencies.
- Introduce typed route keys such as `MainRoute`, `BooksRoute`, `DictionaryRoute`, `SettingsRoute`, `SettingsDetailRoute(section)`, `ReaderRoute(bookId)`, and `SasayakiMatchRoute(bookId)`.
- Extract an `AppShell` that owns the root back stack and renders destinations with `NavDisplay`.
- Keep the current adaptive bottom/rail UI visually unchanged unless a specific navigation interaction problem is being fixed in this slice.
- Route reader opening by stable book id instead of passing large `EpubBook` or `File` objects.
- Leave Anki routes, Anki settings, AnkiDroid integration, and card-creation behavior out of scope.

Why before ViewModel extraction:

- Current navigation state is mixed into `BookshelfView`. If `BookshelfViewModel` is extracted first, route state is likely to move once into the ViewModel and then move again into Navigation3.
- Navigation3 gives the rest of the refactor a clear destination boundary: screen state holders can own screen data, while `AppShell` owns app navigation.

Exit criteria:

- Top-level tabs, settings detail screens, reader open/close, Android Back, external EPUB open routing, and media-session return still behave as before.
- Any intentional navigation UI or interaction improvement is documented in the slice and verified directly.
- `BookshelfView` no longer owns app-level route decisions such as whether the reader or a settings detail screen is currently displayed.

### Phase 2: Extract Screen State Holders for Bookshelf and Dictionary

Scope:

- Introduce `BookshelfUiState` and `BookshelfViewModel`.
- Introduce `DictionaryUiState` and `DictionaryViewModel`.
- Keep file picker launchers and `ActivityResultContract` calls in Compose, but send selected URIs to the ViewModel.
- Move book list reload, import result handling, selected/open reader events, bookmark save triggers, dictionary import state, lookup rebuild state, and error/loading state out of composables.

Why after Navigation3:

- Route keys are now stable, so ViewModels do not need to model app navigation as nullable screen state.
- Bookshelf and Dictionary are lower risk than Reader, but they are large enough to prove the state-holder pattern.

Exit criteria:

- `BookshelfView` and `DictionaryView` mostly render immutable state and dispatch events.
- Existing visuals and user-visible behavior remain unchanged except for explicit loading, error, empty, or interaction improvements included in the slice scope.
- JVM tests cover import success/failure, open-reader event emission, dictionary ordering/enabled changes, and lookup rebuild state.

### Phase 3: Split Repositories and Data Sources

Scope:

- Split `BookStorage` behind a repository-facing API and focused file/import/bookmark data sources.
- Split `DictionaryRepository` into storage, import, and lookup-query responsibilities.
- Preserve all existing sidecar JSON names, directory layout, dictionary config format, and native bridge behavior.

Why here:

- ViewModels from Phase 2 create natural consumers for repository interfaces.
- Data movement is easier to test once UI orchestration no longer directly owns file/native calls.

Exit criteria:

- Public repository APIs are small and main-safe.
- Tests cover duplicate import, metadata fallback, unsafe path rejection, bookmark read/write/progress, dictionary import validation, dictionary ordering, and lookup query rebuild.

### Phase 4: Move Settings to DataStore-backed Repositories

Scope:

- Add settings repositories for reader, dictionary, audio, and Sasayaki settings.
- Expose settings as `Flow<Settings>`.
- Add one-time migration from existing `SharedPreferences`.
- Keep existing preference keys and defaults compatible during migration.

Why after repository boundaries:

- Settings become another data source consumed by ViewModels instead of being read directly from composables and helpers.
- This prepares reader and Sasayaki refactors without forcing all settings call sites to change at once.

Exit criteria:

- Existing settings survive upgrade from `SharedPreferences`.
- UI observes settings through ViewModel or repository flows.
- No synchronous preference reads remain in newly refactored screens.

### Phase 5: Refactor Reader State and WebView Bridge

Scope:

- Introduce `ReaderViewModel` or a reader-specific state holder for chapter navigation, bookmark persistence, lookup stack coordination, and reader settings.
- Keep the existing WebView loading strategy, Rust EPUB parser, resource interception, pagination scripts, and iOS-aligned reader behavior.
- Gradually introduce typed WebView settings, resource bridge, JavaScript command builders, and result parsers.

Why later:

- Reader is the highest-risk path in the app.
- Navigation, settings, and repository boundaries should be stable before moving reader lifecycle and JavaScript orchestration.

Exit criteria:

- Manual reader validation covers cover image page, multi-image illustration page, long text page turns, forward chapter boundary, backward chapter boundary, reverse cross-chapter landing point, lookup popup open, and bookmark restoration.
- WebView lifecycle does not regress across route changes.

### Phase 6: Split Sasayaki Playback Responsibilities

Scope:

- Extract `SasayakiPlaybackController`, `CueTimelineEngine`, `SasayakiPlaybackRepository`, `AudioSourceRepository`, and `SasayakiMediaSessionController`.
- Keep framework `MediaPlayer` initially.
- Consider Media3 `ExoPlayer` only after behavior tests protect the existing semantics.

Why after Reader:

- Sasayaki interacts with reader state, cue matching, popup playback, and media-session return. It should sit on top of the stabilized Navigation3 and reader boundaries.

Exit criteria:

- Cue matching, seek, next/previous cue, playback persistence, temporary popup playback, and media-session actions are covered by tests or manual validation.
- Any Media3 migration is planned as a separate follow-up slice.

### Phase 7: Modularize and Clean Up Build/Performance Work

Scope:

- Extract modules only after APIs have stabilized: start with pure Kotlin or low-Android-dependency boundaries such as `:core:model`, `:core:storage`, `:core:epub`, `:core:dictionary-api`, and `:core:settings`.
- Extract Rust/UniFFI Gradle task registration only after the current build behavior is well characterized.
- Add Macrobenchmark/Baseline Profile coverage for startup, EPUB import/open reader, reader page turn, dictionary search, and lookup popup open.

Why last:

- Module boundaries should follow proven APIs, not guesses.
- Build logic and performance profiles are valuable, but they are easier to stabilize once app architecture has clearer ownership.

Exit criteria:

- Module extraction reduces accidental coupling without moving native/Rust packaging prematurely.
- `./gradlew test`, `./gradlew assembleDebug`, and relevant lint/build tasks still pass after each slice.

## Practical Starting Points

Good first refactoring issue:

- Add Navigation3 runtime/UI dependencies and a minimal route-key model.
- Extract `AppShell` and move app-level route ownership out of `BookshelfView`.
- Keep Books/Dictionary/Settings visuals unchanged unless the slice explicitly fixes a navigation-related interaction problem.
- Support `MainRoute`, settings detail routes, and `ReaderRoute(bookId)` first.
- Verify Android Back from reader and settings detail screens, external EPUB open routing, and media-session return.
- Leave Anki routes out of this migration.

Good second issue:

- Extract `BookshelfUiState` and `BookshelfViewModel`.
- Keep import launchers in Compose.
- Move book list reload, import success/failure, selected/open reader events, and bookmark save into the ViewModel.
- Add JVM tests around state transitions.

Good third issue:

- Split `BookStorage` into a repository-facing API and sidecar/file data-source helpers.
- Preserve all existing file names and JSON shapes.
- Add tests for duplicate import, metadata fallback, bookmark progress, unsafe path rejection, and sidecar read/write.

Good fourth issue:

- Convert the most brittle source-string tests in the touched area into behavior tests before larger movement.

## Non-goals

- Do not rewrite the app into a generic Clean Architecture template.
- Do not introduce every possible domain use case. Use a domain layer only where logic is complex or reused.
- Do not force unrelated UI visual changes just for architecture work. UI and interaction improvements are acceptable when they directly fall out of the code-structure refactor, fix a known awkward behavior, or reduce duplicated/fragile UI state. Keep those changes explicit in the slice scope and verify the affected flow.
- Do not touch AnkiDroid integration, Anki settings, Anki routes, or card-creation behavior during this technical-debt refactor. That belongs to the later Anki feature slice.
- Do not replace the reader WebView.
- Do not alter sidecar JSON shapes without a migration and explicit compatibility reason.
- Do not modularize the native/Rust build until the Kotlin boundaries are stable.
