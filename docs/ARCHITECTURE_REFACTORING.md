# Hoshi Android Architecture Refactoring Directions

Date: 2026-05-04

This document is the durable direction for the next wave of technical-debt refactoring. It replaces the previous implementation-history plan. Old slice records, completed migration notes, and per-commit progress belong in issues, PRs, commit messages, or the local `.codex/skills/hoshi-refactoring-workflow/references/refactoring-slices.md` queue, not in this tracked document.

The goal is maintainability, testability, and alignment with current Android architecture guidance. This is not an iOS UI parity plan. Preserve iOS-aligned user-visible behavior where it already matters, but choose Android-native architecture boundaries.

## Current Baseline

Already completed or mostly completed:

- Navigation3 is in place with typed route keys, `AppShell`, `NavDisplay`, reader routes, settings detail routes, and explicit back-stack helpers.
- Bookshelf and dictionary management now have ViewModels and immutable UI state.
- Reader, dictionary, audio, and Sasayaki settings use DataStore-backed repositories with legacy `SharedPreferences` migration.
- Dictionary storage/import/query responsibilities are partially split.
- Book storage is partially split into repository, file data source, import data source, and sidecar data source.
- WebView security defaults are centralized.
- Sasayaki playback has a high-level controller facade and several narrow coordinators.

Remaining architectural debt:

- `features/reader/ReaderWebView.kt` is still the largest risk. It owns reader UI, WebView lifecycle, JavaScript dispatch, lookup popup orchestration, Sasayaki integration, settings collection, system bars, screen-awake behavior, and bookmark-saving callbacks.
- Data repositories are not consistently main-safe. Several repositories and data sources perform blocking file/native work synchronously while callers decide when to use `Dispatchers.IO`.
- `epub/BookRepository.kt` still imports Sasayaki feature models for sidecar persistence, which blocks clean model/storage module boundaries.
- Compose screens still collect some Flows with `collectAsState()` or manual `LaunchedEffect` collection instead of lifecycle-aware collection.
- Source-string tests remain common. Some protect security or build wiring, but many make internal refactoring more expensive than necessary.
- `app/build.gradle.kts` still owns normal app config, release signing validation, CMake/JNI dictionary wiring, Rust host build, UniFFI generation, `cargo-ndk`, generated source registration, generated JNI libs, and JNA test runtime wiring.
- There is no dedicated baseline profile or macrobenchmark coverage for startup, reader, dictionary, or lookup flows.

## Android Architecture Baseline

Use these official Android recommendations as constraints:

- [Guide to app architecture](https://developer.android.com/topic/architecture): separate UI and data layers, use single sources of truth, prefer unidirectional data flow, and use state holders for UI complexity.
- [Compose state hoisting](https://developer.android.com/develop/ui/compose/state-hoisting): keep state at the lowest common owner, expose immutable state and events, and use ViewModels or plain state holders when logic outgrows composables.
- [Compose state](https://developer.android.com/develop/ui/compose/state): use `collectAsStateWithLifecycle()` for Flow collection in Android Compose UI.
- [Data layer](https://developer.android.com/topic/architecture/data-layer): repositories and data sources should be main-safe and should move blocking work to the right dispatcher internally.
- [Domain layer](https://developer.android.com/topic/architecture/domain-layer): use cases are optional; introduce them only for complex or reused business logic.
- [DataStore](https://developer.android.com/topic/libraries/architecture/datastore): settings and small persisted state should be Flow-based and transactionally updated.
- [Navigation 3](https://developer.android.com/guide/navigation/navigation-3): model navigation as an explicit back stack and retain entry state while keys stay on the stack.
- [Android modularization](https://developer.android.com/topic/modularization): modularization should enforce clear ownership and encapsulation, but overly fine-grained modules add build and boilerplate overhead.
- [Baseline Profiles](https://developer.android.com/topic/performance/baselineprofiles/overview): cover startup and performance-sensitive user journeys so ART can precompile common paths.
- [Media3 ExoPlayer](https://developer.android.com/media/media3/exoplayer): prefer Media3's `Player`/`ExoPlayer` abstraction for future playback evolution once behavior is protected.

## Direction 1: Make Data APIs Main-Safe

Priority: high

Move dispatcher responsibility into repositories and data sources instead of requiring every ViewModel/composable caller to wrap calls in `withContext(Dispatchers.IO)`.

Scope:

- Convert blocking repository/data-source APIs to `suspend` where appropriate.
- Inject `CoroutineDispatcher` into file/native-heavy data sources.
- Keep pure, cheap in-memory helpers synchronous.
- Start with EPUB/book storage because it is used by Bookshelf, Reader, Sasayaki, and future sync.
- Preserve `Books/<safeTitle>` layout and sidecar JSON compatibility.

Candidate first slice:

1. Add behavior tests for `BookRepository` import, listing, metadata, bookmark, sidecar load/save, and unsafe path rejection.
2. Make `BookRepository`, `BookFileDataSource`, `BookImportDataSource`, and `BookSidecarDataSource` main-safe.
3. Remove redundant caller-side dispatcher wrapping where the repository now owns the thread boundary.

Exit criteria:

- `BookRepository` public methods that touch disk/native work are safe to call from main.
- Existing book storage tests still pass.
- Reader open, bookshelf reload, import, delete, bookmark save, and Sasayaki sidecar access remain behaviorally unchanged.

## Direction 2: Split Book Sidecar And Model Contracts

Priority: high

Unblock future module boundaries by removing feature package dependencies from EPUB/book storage.

Scope:

- Move sidecar data contracts that are persisted with books into a stable storage/model package.
- Keep UI and playback behavior in feature packages.
- Keep JSON names and serialized field compatibility unchanged.
- Do not extract Gradle modules in this slice.

Target shape:

- `epub` or `storage` owns book metadata, bookmark, bookinfo, and generic sidecar persistence.
- Sasayaki feature owns playback behavior, but persisted Sasayaki sidecar data no longer forces `epub` to import `features.sasayaki`.
- Future `:core:model` or `:core:storage` extraction becomes a mechanical move, not a semantic redesign.

Exit criteria:

- `BookRepository` no longer imports UI/playback feature packages.
- Existing Sasayaki sidecar files still load and save in the same format.
- Tests cover migration-free compatibility with existing sidecar JSON.

## Direction 3: Refactor Reader State And WebView Bridge

Priority: high

Reader is now the main composable-level orchestration hotspot. Refactor it in narrow behavior-protected slices.

Target shape:

- `ReaderSessionStateHolder`: chapter position, lookup popup stack, reader menu/sheet state, and local reader UI mechanics.
- `ReaderWebBridge`: typed JavaScript command dispatch, result parsing, selection bridge, restore bridge, and page progress callbacks.
- `ReaderLookupCoordinator`: popup creation, child popup handling, dictionary/audio settings snapshot, and selection highlight count.
- `ReaderSasayakiIntegration`: lookup auto-pause, cue highlight dispatch, auto-scroll callbacks, and screen-awake state.
- `ReaderWebView`: renders UI and wires dependencies; it should not own business orchestration.

Guidelines:

- Keep WebView-based reading and lookup.
- Do not change pagination, chapter boundary, bookmark restoration, or lookup popup behavior unless the slice explicitly fixes a known bug.
- Move one command family at a time: selection, pagination, restore, resource loading, then Sasayaki cue commands.
- Use typed command/result tests before deleting old source-shape tests.

Exit criteria:

- Manual reader validation covers cover image page, multi-image illustration page, long text paging, forward chapter boundary, backward chapter boundary, reverse cross-chapter landing, lookup popup open, and bookmark restoration.
- WebView lifecycle remains stable across route changes.
- `ReaderWebView.kt` becomes mostly composition and wiring.

## Direction 4: Make Compose Flow Collection Lifecycle-Aware

Priority: medium-high

Replace direct `collectAsState()` and manual `LaunchedEffect` settings collection where UI lifecycle-aware collection is the right owner.

Scope:

- Add `androidx.lifecycle:lifecycle-runtime-compose`.
- Use `collectAsStateWithLifecycle()` in Android UI for ViewModel and settings Flows.
- Keep long-lived app/root settings collection centralized where it controls theme or app shell state.
- Avoid pushing UI element state into ViewModels when it is purely local.

Exit criteria:

- Screen-level Flows are collected lifecycle-aware.
- Recomposition behavior and immediate settings updates remain unchanged.
- Tests or focused manual checks cover Settings detail controls and Reader appearance/behavior controls.

## Direction 5: Replace Brittle Source-String Tests

Priority: medium-high

Reduce tests that inspect production source strings. Keep source-shape tests only when they protect security, build integration, or Android framework wiring that cannot be exercised behaviorally.

Scope:

- Convert source-string tests in the touched area before large movement.
- Prefer behavior tests with fakes for repositories, media controllers, clocks, WebView bridges, and resource handlers.
- Keep explicit source guards for WebView file access restrictions, SAF-only import paths, and native/build task wiring if no better test seam exists.

Exit criteria:

- Refactoring can rename methods and move files without breaking unrelated tests.
- Remaining source-string tests state why behavior coverage is insufficient.

## Direction 6: Stabilize Sasayaki Playback API Before Media3

Priority: medium

Sasayaki is partially decomposed, but the controller still wires many collaborators and exposes Compose-observed state through a facade. Stabilize the high-level API before replacing the engine.

Scope:

- Introduce a clearer command/state surface for playback.
- Prefer `StateFlow<SasayakiPlaybackUiState>` or another explicit observable state boundary over scattered mutable state reads.
- Preserve the current framework `MediaPlayer` engine first.
- Plan Media3 `ExoPlayer` as a separate behavior-protected slice.

Exit criteria:

- Cue matching, seek, next/previous cue, playback persistence, temporary popup playback, and media-session actions are covered by behavior tests or manual validation.
- Media3 migration can be implemented as an engine adapter change rather than a full controller rewrite.

## Direction 7: Build Logic And Performance Hardening

Priority: medium

Do not modularize native/Rust wiring blindly. First characterize and isolate build behavior.

Scope:

- Add task inputs/outputs or explicit verification tests for Rust host build, UniFFI Kotlin generation, debug/release `cargo-ndk`, CMake/JNI dictionary bridge, and JNA test wiring.
- Extract build logic only after behavior is characterized.
- Add Macrobenchmark/Baseline Profile coverage for cold start, EPUB import/open reader, reader page turn, dictionary search, and lookup popup open.

Exit criteria:

- `./gradlew test`, `./gradlew assembleDebug`, and release-facing native build tasks remain stable after each build-logic change.
- Performance-sensitive flows have repeatable benchmark/profile entry points.

## Direction 8: Modularize Only After Boundaries Stabilize

Priority: medium-low

Module extraction should follow stable APIs, not create them.

Prerequisites:

- Book sidecar/model contracts no longer depend on feature packages.
- Repository APIs are main-safe.
- Reader bridge/state responsibilities have clear package boundaries.
- Build/Rust/UniFFI behavior is characterized.

Likely first modules after prerequisites:

- `:core:model` for pure serializable app models.
- `:core:storage` for sidecar JSON and safe file abstractions.
- `:core:epub` for parser API and reader-facing EPUB models.
- `:core:dictionary-api` for dictionary model/import/query interfaces.
- `:core:settings` after settings call sites no longer depend on feature UI packages.

Avoid:

- Extracting many feature modules while the app still has cross-feature model dependencies.
- Moving native/Rust packaging into another module before task behavior is well understood.

## Recommended Next Sequence

1. Main-safe book repository/data sources.
2. Book sidecar/model contract split.
3. Reader state and WebView bridge extraction, one command family at a time.
4. Lifecycle-aware Flow collection cleanup.
5. Source-string test replacement in touched areas.
6. Sasayaki playback state API stabilization.
7. Build-logic characterization and baseline profiles.
8. Gradual module extraction.

Each slice should be small enough for one focused commit, should preserve existing user-visible behavior unless explicitly scoped otherwise, and should include targeted tests or manual validation before handoff.
