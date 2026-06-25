# Hoshi Android Architecture Refactoring

Date: 2026-06-25

This document tracks future architecture direction only. It is not a current
architecture overview, task log, handoff file, or completion ledger. Current
architecture lives in `docs/ARCHITECTURE.md`. Active work belongs in the current
issue, PR, commit message, or conversation.

`AGENTS.md` should contain only rules that every agent must keep in context.
This file owns longer-lived refactor targets, especially official Android best
practices that the project has not fully adopted yet.

When a refactor target is completed and becomes the repository baseline, update
`AGENTS.md` in the same change to lock that baseline into the always-loaded
agent contract. Remove or rewrite the completed target here so this file keeps
describing only future work.

## Official Android Guidance

Use the current Android, Google, and Jetpack documentation as the implementation
source for Android-specific behavior. The iOS app remains the source of truth
for user-visible Hoshi behavior, not for Android implementation mechanics.

Relevant official guidance reviewed for this direction:

- [Guide to app architecture](https://developer.android.com/topic/architecture):
  modern Android architecture emphasizes layered architecture, UDF, UI state
  holders, coroutines/Flow, and dependency injection.
- [Architecture recommendations](https://developer.android.com/topic/architecture/recommendations):
  prefer lifecycle-aware Flow collection, dependency injection, constructor
  injection, scoped containers, and Hilt for sufficiently complex apps.
- [Dependency injection with Hilt](https://developer.android.com/training/dependency-injection/hilt-android):
  Hilt standardizes Android DI containers and lifecycle-aware object creation.
- [UI layer](https://developer.android.com/topic/architecture/ui-layer) and
  [state hoisting](https://developer.android.com/develop/ui/compose/state-hoisting):
  screen UI state belongs in ViewModels or state holders; UI logic stays close
  to the UI; state flows down and events flow up.
- [Compose state](https://developer.android.com/develop/ui/compose/state) and
  [save UI state](https://developer.android.com/develop/ui/compose/state-saving):
  Compose Flow collection should be lifecycle-aware, and transient UI state
  should be saved with the correct UI-state API.
- [Coroutine best practices](https://developer.android.com/kotlin/coroutines/coroutines-best-practices):
  inject dispatchers, keep suspend functions main-safe, expose immutable state,
  and let ViewModels create coroutines for business work.
- [DataStore](https://developer.android.com/topic/libraries/architecture/datastore):
  keep DataStore behind repositories and expose data through ViewModels instead
  of reading or writing DataStore directly in composables.
- [WorkManager persistent work](https://developer.android.com/develop/background-work/background-tasks/persistent):
  use WorkManager for work that must continue reliably after the app leaves the
  visible state, app process exits, or the device restarts.
- [Android modularization](https://developer.android.com/topic/modularization):
  modules should be loosely coupled, self-contained, and introduced when they
  enforce real ownership boundaries.
- [Baseline Profiles](https://developer.android.com/topic/performance/baselineprofiles/overview)
  and [Compose performance](https://developer.android.com/develop/ui/compose/performance):
  performance-sensitive apps should benchmark real journeys and ship profile
  rules for startup and hot paths.
- [WebView unsafe file inclusion](https://developer.android.com/privacy-and-security/risks/webview-unsafe-file-inclusion):
  avoid broad file URL access; prefer safe local resource loading such as
  `WebViewAssetLoader` where it fits.

## Target 1: Inject Dispatchers And Long-Lived Scopes

Priority: high

Repositories should be main-safe, and coroutine dispatchers should be injected
so tests can use deterministic dispatchers. Current code is partially main-safe
but often hardcodes dispatchers or creates scopes internally.

Target shape:

- Introduce a small dispatcher/scope abstraction only when a slice needs it.
- Move blocking disk, native, network, archive, and hashing work behind
  main-safe suspend APIs.
- Inject IO/default dispatchers into repositories and data sources.
- Inject app-wide external scopes for work that must outlive a screen but does
  not require WorkManager persistence.
- Let ViewModels create coroutines for business actions through `viewModelScope`.

Exit criteria:

- Tests can run repository/ViewModel coroutine work with test dispatchers.
- Callers no longer need to guess whether a repository method needs
  `withContext(Dispatchers.IO)`.
- No new ad hoc `CoroutineScope(...)` is introduced outside a deliberate owner.

## Target 2: Reduce Composable Business Orchestration

Priority: high

Compose UI should render state and emit events. Complex screen behavior should
live in ViewModels or plain UI state holders depending on whether the logic is
business logic or UI logic.

Target shape:

- Move screen data loading and mutation paths out of Composables into
  ViewModels or narrow coordinators.
- Keep UI-only state close to Compose with `rememberSaveable` or plain state
  holders.
- Use UDF: state flows down, user events flow up, owners mutate their own data.
- Keep Android SDK types out of ViewModels except where a documented platform
  boundary requires an adapter.

Exit criteria:

- Bookshelf, dictionary, reader, update, and settings screens expose clear
  event functions and immutable UI state.
- Composables can be read as rendering/wiring code, not business workflows.

## Target 3: Split Reader WebView Responsibilities

Priority: high

Reader remains the biggest orchestration hotspot and the highest-risk user
journey. Refactor it only in behavior-protected slices.

Target shape:

- `ReaderWebBridge`: typed JavaScript commands, results, selection, restore,
  pagination, resource loading, and progress callbacks.
- `ReaderLookupCoordinator`: lookup popup creation, child popups, dictionary
  settings snapshots, audio settings, and selection highlight state.
- `ReaderSessionStateHolder`: chapter position, chrome/menu/sheet state,
  popup stack, and UI-only reader state.
- `ReaderSasayakiIntegration`: cue highlight commands, auto-pause, auto-scroll,
  screen-awake state, and playback callbacks.
- `ReaderWebView`: Compose rendering, WebView attachment, and dependency wiring.

Guidelines:

- Preserve WebView reading and single-tap lookup behavior.
- Cold start opens Bookshelf; manual reader validation must tap a book cover
  before asserting reader behavior unless the slice explicitly tests route
  restore.
- Local WebView resources should use `WebViewAssetLoader` or the repository's
  existing safe loading path. Do not broaden file URL access.
- Move one command family at a time and keep JS/CSS behavior aligned with the
  iOS reader.

Exit criteria:

- Reader validation covers cover image page, multi-image page, long text
  paging, forward/backward chapter boundaries, reverse cross-chapter landing,
  lookup popup open, bookmark restoration, and Sasayaki cue behavior.
- `ReaderWebView.kt` no longer owns business orchestration.

## Target 4: Keep DataStore Behind Repositories

Priority: medium-high

DataStore is already the right primitive for Hoshi settings. The remaining goal
is consistency.

Target shape:

- DataStore access stays in repository/data-layer classes.
- Composables observe ViewModel or repository state, not raw DataStore objects.
- Keep legacy SharedPreferences migrations where they already protect user
  settings.
- Use Room only if a future dataset needs relational queries, partial updates,
  or referential integrity. Do not replace sidecar JSON or dictionary native
  storage just because Room exists.

Exit criteria:

- Settings storage remains Flow-based and transactionally updated.
- UI code does not directly read or write DataStore.

## Target 5: Harden WorkManager Usage

Priority: medium

WorkManager is already used for update checks. Future background work should use
it only when reliability after app exit/restart matters.

Target shape:

- Use unique work names for periodic or user-triggered work that must not be
  duplicated.
- Use constraints, backoff, expedited work, and foreground work only when the
  official background-work guidance fits the user-visible requirement.
- Keep in-process coroutines for work that may stop when the app process stops.
- Keep worker dependencies on Hilt-compatible worker injection instead of manual
  lookups.

Exit criteria:

- Update checks remain unique and non-duplicating.
- Sync, backup, download, or import work uses WorkManager only when the
  persistence requirement is explicit.

## Target 6: Split Rust And Native Build Logic

Priority: medium-high

`app/build.gradle.kts` currently owns normal Android config plus release
signing, CMake/JNI dictionary bridge configuration, Rust host builds, UniFFI
generation, `cargo-ndk`, generated source registration, generated JNI libs, and
JNA test wiring. This is too much responsibility for the app build file.

Hard constraints:

- `app/src/main/rust/hoshiepub/uniffi.toml` must keep
  `[bindings.kotlin] android = true`.
- Android packaging uses the JNA AAR; JVM unit tests use the JNA jar.
- `cargo-ndk` should build library targets only and must not cross-compile
  UniFFI bindgen or other host binaries.

Target shape:

- Move Rust EPUB parser build logic into focused Gradle convention build logic,
  a dedicated included build, or a small custom Gradle plugin.
- Keep CMake/hoshidicts JNI bridge wiring separate from Rust/UniFFI parser
  wiring; they are independent native stacks.
- Keep release signing, version metadata, app resources, Compose setup, and
  native/Rust generation in separate build-logic responsibilities.
- Model generated UniFFI Kotlin sources and generated Rust JNI libs as explicit
  Gradle outputs consumed by Android/Kotlin tasks.
- Preserve debug and release ABI behavior: debug builds arm64-v8a and x86_64;
  release builds arm64-v8a.
- Add verification for Rust host build, UniFFI generation, Android Rust builds,
  CMake/JNI outputs, and JNA test runtime wiring before extracting logic.

Exit criteria:

- `app/build.gradle.kts` no longer directly contains the full Rust/UniFFI task
  graph.
- Rust/UniFFI, hoshidicts CMake/JNI, release signing, and regular Android app
  configuration can be changed independently.
- `./gradlew test`, `./gradlew assembleDebug`, and release-facing native outputs
  remain stable.
- Generated source and native library registration remains deterministic.

## Target 7: Modularize After Boundaries Stabilize

Priority: medium

Modularization should enforce ownership that already exists; it should not be
used to invent boundaries while feature packages still depend on each other
freely.

Prerequisites:

- DI graph and dispatcher injection are stable.
- Book sidecar/model contracts no longer depend on feature UI packages.
- Reader bridge/state responsibilities are split.
- Native/Rust/UniFFI build behavior is characterized or extracted.

Likely first modules:

- `:core:model` for pure serializable models.
- `:core:storage` for sidecar JSON and safe file abstractions.
- `:core:epub` for parser API and reader-facing EPUB models.
- `:core:dictionary-api` for dictionary model/import/query interfaces.
- `:core:settings` after settings dependencies are no longer feature-UI-shaped.

Avoid:

- Extracting many feature modules before dependencies point inward.
- Moving native or Rust packaging into another module before task behavior is
  protected.

## Target 8: Add Baseline Profiles And Macrobenchmarks

Priority: medium

Hoshi has several performance-sensitive journeys: startup, bookshelf, opening a
reader, page turning, lookup popup creation, dictionary search, import, and
Sasayaki playback startup.

Target shape:

- Add a Macrobenchmark/Baseline Profile module when emulator validation is safe
  and repeatable.
- Cover cold start and the most common reader/lookup paths first.
- Keep benchmark test data explicit and stable.
- Do not run destructive connected tasks on a non-disposable device; follow
  `docs/VALIDATION.md`.

Exit criteria:

- Release builds ship generated Baseline Profile rules for startup and hot
  paths.
- Macrobenchmarks can compare before/after reader and lookup changes.

## Target 9: Replace Brittle Source-String Tests

Priority: medium

Source-string tests make refactors expensive and often fail for non-behavioral
changes. Keep them only where behavior coverage is not practical.

Target shape:

- Prefer behavior, API, state-flow, repository, parser, and fake-backed tests.
- For manifest, resources, Gradle, permissions, providers, and native build
  declarations, parse structured configuration before asserting.
- Keep explicit source guards only for security/build seams that cannot be
  exercised more directly.

Exit criteria:

- Refactoring can move methods/classes without breaking unrelated tests.
- Remaining source-shape tests document why behavior coverage is insufficient.

## Target 10: Finish Sasayaki MediaController Boundary

Priority: high

Sasayaki playback now creates the player/session from the
`MediaSessionService` lifecycle, but Reader still calls an in-process runtime
facade for load, cue attachment, and playback commands. This keeps the current
UI behavior small, but the remaining boundary should be completed so Reader
talks to `SasayakiPlaybackService` through a deliberate Media3
controller/session API rather than a direct runtime bridge.

Target shape:

- Move Reader playback commands and load/stop handoff behind a MediaController
  or equivalently explicit service-session boundary.
- Remove the process-local service connection once Reader no longer needs it to
  enter the service lifecycle.
- Keep cue callbacks and Reader-only UI state outside the long-lived service
  owner.

Exit criteria:

- Reader can open, control, detach from, and return to active Sasayaki playback
  through the media-session boundary.
- The service runtime no longer exposes a direct UI-facing playback facade.
- Background playback, notification controls, and explicit reader-exit stop
  remain covered by tests and manual validation.

## Recommended Sequence

1. Continue dispatcher/scope injection in the next repository or ViewModel slice
   that already needs coroutine cleanup.
2. Reduce Composable business orchestration while touching screen state flows.
3. Split reader WebView command families with behavior tests or manual
   validation after each slice.
4. Stabilize sidecar/model contracts.
5. Extract long reader JavaScript/CSS out of Kotlin string script files.
6. Characterize and split Rust/native build logic.
7. Finish the Sasayaki MediaController boundary after service-owned playback is
   stable on device.
8. Add baseline profile and macrobenchmark entry points.
9. Modularize only after the Hilt graph and model, reader, and build boundaries
   are stable.

Every slice must preserve iOS-aligned user-visible behavior unless it explicitly
fixes a known Android behavior bug. When a slice completes a target or changes
the accepted baseline, update `AGENTS.md` before handoff so the new rule is
enforced in future work.
