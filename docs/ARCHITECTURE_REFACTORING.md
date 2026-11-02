# Architecture Refactoring

Remaining architecture gaps, ordered by priority. Current contracts and owners
live in [ARCHITECTURE.md](ARCHITECTURE.md); checks are in
[VALIDATION.md](VALIDATION.md). Document maintenance follows
[AGENTS.md](../AGENTS.md#文档维护规则).

Kotlin paths below are relative to `app/src/main/java/moe/antimony/hoshi/`.
Each slice preserves current behavior except for changes required by the task.

## High priority

### Move blocking audio work behind repository APIs

`features/audio/AudioView.kt` chooses `Dispatchers.IO` around synchronous
`LocalAudioRepository` operations. Hilt already provides dispatchers and
application scope through `di/HoshiAppModule.kt` and `HoshiQualifiers.kt`.

Move blocking work into repository-owned suspend APIs using those injected
boundaries. Completion means callers can invoke them safely from Main and tests
can control their execution. Scope cleanup elsewhere can follow the same pattern
when the affected owner is changed.

### Reduce Compose business orchestration

`features/backup/BackupSettingsView.kt` coordinates backup/restore and sync from
remembered coroutine scope. `features/audio/AudioView.kt` also loads and mutates
repository data directly. Move these operations and their error/state flows into
ViewModels or focused coordinators, leaving rendering and platform launchers in
Compose.

Completion means screen lifecycle, retries and overlapping operations can be
exercised through event/state APIs. Reader orchestration is the separate target
below.

### Separate Reader orchestration

`features/reader/ReaderWebView.kt` still coordinates dictionary settings, popup
mining/audio and reader lifecycle. Existing selection, popup, resource and state
owners are listed in the architecture map. Extract one remaining responsibility
at a time and preserve document generations, restore and playback contracts.

Paginated/continuous restore still duplicate character-target calculation.
Extract equivalent operations while keeping page and scroll landing with their
mode owners.

Completion means the affected behavior has one owner used by production callers,
with mode-specific regression coverage. Painting and gesture claims use the
WebView evidence described in VALIDATION.

### Finish the Sasayaki session boundary

`features/sasayaki/SasayakiPlaybackServiceRuntime.kt` creates its player/session
through `MediaSessionService`, but exposes a process-local load/control facade to
Reader. Route Reader commands and load/stop handoff through MediaController or an
explicit service-session API, then remove the direct runtime facade.

Reader cue sinks remain attached presentation callbacks. Completion covers
open/control/detach/return, background media controls and explicit Reader exit
through the same service boundary.

## Medium priority

### Separate native build logic

`app/build.gradle.kts` combines Android configuration, signing, CMake/JNI,
Rust host builds, UniFFI generation, cargo-ndk, generated outputs and JNA wiring.
Extract these into focused build logic while preserving the independent native
stacks and packaging contracts in ARCHITECTURE.

Characterize host generation and debug/release outputs first. Completion means
those responsibilities can change independently with deterministic task inputs
and outputs. ABI coverage follows `releaseAbi` and the publication workflows.

### Untangle shared data dependencies before modularizing

`epub/BookStatisticsStore.kt`, `BookRepository.kt` and `ReadingSession.kt` import
sync types; the statistics store also depends on reader settings. Move shared
sidecar/session contracts to a data-owned boundary, keeping existing formats.

Once dependencies support it, model/storage/EPUB/dictionary API packages are
candidates for separate modules. Extract a module when it enforces an established
boundary; use the resulting dependency direction as the completion criterion.

### Add repeatable release-performance benchmarks

`settings.gradle.kts` includes only `:app`; there is no Macrobenchmark/Baseline
Profile module. Add isolated fixtures and release measurements for startup,
book opening and lookup first, following VALIDATION's device-data rules.

Completion means repeatable before/after measurements and generated profiles
included in release builds. Broader journeys can follow measured hotspots.
