# Hoshi Android Current Architecture

Date: 2026-06-06

This document describes the current architecture that exists in the Android
repo. It is not a future plan and should not track task status. Long-lived
refactor goals belong in `docs/ARCHITECTURE_REFACTORING.md`.

## App Shape

- The app is a single Android application module under `app`.
- UI is Jetpack Compose + Material 3.
- Navigation uses Navigation3 typed route keys, `AppShell`, and `NavDisplay`.
  Top-level Books, Dictionary, and Settings tabs each own an independent Nav3
  back stack with its own saveable entry state and per-entry ViewModel stores.
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

## Storage And Data

- Book storage is rooted in app-specific files and remains compatible with the
  iOS sidecar JSON layout.
- EPUB import enters through Android Storage Access Framework and copies content
  into app-specific storage.
- Current book folders store the packed EPUB as `<folder>/<folder>.epub` and
  persist the filename in `BookMetadata.epub`. Sidecar JSON and cached covers
  remain beside the EPUB; parser and reader paths extract packed EPUBs only into
  controlled app cache/temp directories when they need the EPUB tree.
- Book metadata, bookmarks, highlights, reading statistics, and Sasayaki data
  are persisted through book sidecar repositories and models.
- Dictionary import, lookup, media, style extraction, and deinflection behavior
  are owned by `third_party/hoshidicts-kotlin-bridge`.
- Frequency and pitch dictionaries are type-specific and are not treated as term
  fallback dictionaries.

## Reader

- Reader rendering and lookup remain WebView-based to preserve iOS-aligned
  visible behavior.
- Reader fixes compare against the iOS `ReaderWebView` and matching JS/CSS
  before adding Android-specific behavior.
- Reader resource loading must stay on the repository's safe loading path and
  must not broaden file URL access.
- Durable reader JavaScript and CSS live under
  `app/src/main/assets/hoshi-web`; Kotlin owns typed commands, escaped
  parameters, asset loading, dynamic configuration fill-in, and WebView bridge
  invocation.
- Reader, Dictionary search, and Process Text lookup popups render through the
  shared `reader-popup-host.js` iframe stack and `ReaderLookupPopupWebBridge`.
  Kotlin owns popup payloads, resource handling, and native service bridges for
  audio, dictionary media, Anki, and external links; do not reintroduce Android
  native overlay popup fallback paths for these flows.
- Lookup opens from a single tap on reader text. Long press is reserved for
  native selection/highlight flows.

## Integrations

- Anki work stays behind the Anki backend/repository boundary.
- Google Drive sync uses Android/Google OAuth and Drive APIs through the
  repository/sync boundary. The Drive data source owns paginated folder listing,
  grouped sync-file discovery, bookdata upload/download, trash, cache clearing,
  and network preflight; Books keeps remote-only Google Drive books as
  `RemoteBookEntry` models rather than local `BookEntry` placeholders.
- Audio and Sasayaki playback use Media3/ExoPlayer with controller/repository
  boundaries.
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

Hard constraints:

- `app/src/main/rust/hoshiepub/uniffi.toml` must keep
  `[bindings.kotlin] android = true`.
- `cargo-ndk` should build library targets only and must not cross-compile
  UniFFI bindgen or other host binaries.

## Validation

Durable validation commands, emulator data-safety rules, test data, and manual
QA matrices live in `docs/VALIDATION.md`.
