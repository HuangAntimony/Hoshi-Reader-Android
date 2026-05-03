# Hoshi Android Refactoring Tracker

This tracker is the execution state for `docs/ARCHITECTURE_REFACTORING.md`.
The architecture document records direction; this file records what an agent can safely pick up next.

## Agent Protocol

1. Read `AGENTS.md`, `docs/ARCHITECTURE_REFACTORING.md`, and this tracker before editing code.
2. Pick the first `todo` slice whose dependencies are `done` and whose scope fits one commit.
3. Change exactly one slice to `in_progress` before implementation.
4. Keep unrelated architecture directions out of the active slice.
5. Add or update characterization tests/checklists before moving behavior.
6. Preserve iOS-aligned user-visible behavior unless the slice explicitly names a before/after improvement.
7. Before marking a slice `done`, run its verification checklist and record the result.
8. Update `docs/TODO.md` in the same commit. Update `docs/CHANGELOG.md` only for user-visible app changes.

## Status Model

Allowed values: `todo`, `in_progress`, `blocked`, `review`, `done`.

Flow:

```text
todo -> in_progress -> review -> done
                 \-> blocked
blocked -> todo | in_progress
```

Rules:

- Keep at most one `in_progress` slice in this file.
- `done` slices must include `Commit`, checked verification evidence, result notes, and a next handoff.
- `blocked` slices must include `Blocker:` and `Resume when:` in result notes.
- `review` slices must include `Reviewer:` in result notes.
- Use `none` for dependencies only when a slice truly has no dependency.

## Slice Template

```md
### R-000 Short slice title

Status: todo
Phase: 0
Owner: unassigned
Depends on: none
Scope:
- One focused outcome.
Non-goals:
- Explicitly excluded work.
Touched areas:
- app/src/main/...
iOS reference:
- reference/Hoshi-Reader-iOS/... or N/A
Exit criteria:
- Concrete completion signal.
Verification:
- [ ] ./gradlew test
Result notes:
- Not started.
Next handoff:
- What the next agent should do.
```

## Active Queue

### R-000 Characterize Navigation and Reader Entry Behavior

Status: todo
Phase: 0
Owner: unassigned
Depends on: none
Scope:
- Record the current Android behavior for top-level tab switching, Settings detail return, reader open/close, Android Back from reader, external EPUB open routing, bookmark restoration, and Sasayaki media-session return.
- Add focused characterization tests where JVM or instrumentation coverage is practical.
- Add a manual verification checklist for flows that need emulator evidence.
Non-goals:
- Do not migrate navigation.
- Do not extract ViewModels.
- Do not change app behavior.
Touched areas:
- docs/REFACTORING_TRACKER.md
- docs/TODO.md
- app/src/test
- app/src/androidTest
iOS reference:
- N/A for navigation framework shape; preserve existing Android behavior unless it already documents an iOS-alignment bug.
Exit criteria:
- The next Navigation3 slice has a written baseline for every flow it can regress.
- Any intentionally fixed navigation interaction has a before/after expectation.
- No production app behavior changes are introduced.
Verification:
- [ ] python3 tools/check_refactoring_tracker.py docs/REFACTORING_TRACKER.md
- [ ] ./gradlew test
- [ ] Manual or instrumentation checklist for Android Back, settings detail return, reader open/close, external EPUB open, bookmark restoration, and Sasayaki media-session return.
Result notes:
- Not started.
Next handoff:
- Start `R-001` after the baseline is recorded.

### R-001 Add Navigation3 Dependencies and Route Keys

Status: todo
Phase: 1
Owner: unassigned
Depends on: R-000
Scope:
- Add the Navigation3 runtime/UI dependencies needed by the app.
- Introduce serializable route keys for `MainRoute`, `BooksRoute`, `DictionaryRoute`, `SettingsRoute`, `SettingsDetailRoute(section)`, `ReaderRoute(bookId)`, and `SasayakiMatchRoute(bookId)`.
- Keep route keys small and pass stable identifiers instead of `EpubBook`, `File`, or repository objects.
Non-goals:
- Do not render destinations through Navigation3 yet.
- Do not add Anki routes.
- Do not change the visual shell.
Touched areas:
- app/build.gradle.kts
- app/src/main/java/moe/antimony/hoshi
- app/src/test
- docs/TODO.md
iOS reference:
- N/A. This is Android routing infrastructure while preserving existing user-visible behavior.
Exit criteria:
- Route keys compile and have tests for serialization/equality where applicable.
- No existing screen-switching behavior changes.
Verification:
- [ ] python3 tools/check_refactoring_tracker.py docs/REFACTORING_TRACKER.md
- [ ] ./gradlew test
- [ ] ./gradlew assembleDebug
Result notes:
- Not started.
Next handoff:
- Start `R-002` to introduce `AppShell` and render routes.

### R-002 Extract AppShell and Move Route Ownership out of BookshelfView

Status: todo
Phase: 1
Owner: unassigned
Depends on: R-001
Scope:
- Extract an `AppShell`-level state holder for the root back stack.
- Render top-level destinations through Navigation3 `NavDisplay`.
- Keep the current adaptive bottom/rail navigation visually unchanged.
- Move app-level decisions for Settings detail and Reader display out of `BookshelfView`.
Non-goals:
- Do not extract `BookshelfViewModel`.
- Do not alter reader WebView loading or bookmark persistence.
- Do not redesign Books, Dictionary, or Settings surfaces.
Touched areas:
- app/src/main/java/moe/antimony/hoshi
- app/src/test
- app/src/androidTest
- docs/TODO.md
iOS reference:
- N/A for Navigation3; preserve iOS-aligned visible behavior already implemented in Android.
Exit criteria:
- Books, Dictionary, Settings, Settings detail, Reader, and Sasayaki Match destinations are owned by `AppShell`.
- `BookshelfView` no longer decides whether the app is showing Settings detail or Reader.
- Top-level tab appearance remains unchanged.
Verification:
- [ ] python3 tools/check_refactoring_tracker.py docs/REFACTORING_TRACKER.md
- [ ] ./gradlew test
- [ ] ./gradlew assembleDebug
- [ ] Manual emulator check for top-level tabs, Settings detail back, Reader open/close, and Android Back from Reader.
Result notes:
- Not started.
Next handoff:
- Start `R-003` to harden Reader route behavior and external entry points.

### R-003 Stabilize ReaderRoute Book Id Migration and Back Behavior

Status: todo
Phase: 1
Owner: unassigned
Depends on: R-002
Scope:
- Ensure `ReaderRoute(bookId)` opens books by stable id through repository/storage lookup.
- Preserve bookmark restoration and current reader lifecycle across route changes.
- Verify Android Back, toolbar close, and media-session return paths.
Non-goals:
- Do not refactor reader WebView internals.
- Do not change pagination JavaScript.
- Do not introduce `ReaderViewModel`.
Touched areas:
- app/src/main/java/moe/antimony/hoshi
- app/src/test
- app/src/androidTest
- docs/TODO.md
iOS reference:
- `reference/Hoshi-Reader-iOS/Features/Reader/ReaderView.swift`
- `reference/Hoshi-Reader-iOS/Features/Reader/ReaderWebView/ReaderWebView.swift`
Exit criteria:
- Reader opens from a route key containing only a stable book id.
- Closing the reader and Android Back restore the previous destination as before.
- Bookmark restoration evidence is recorded.
Verification:
- [ ] python3 tools/check_refactoring_tracker.py docs/REFACTORING_TRACKER.md
- [ ] ./gradlew test
- [ ] ./gradlew assembleDebug
- [ ] Manual emulator check with `testdata/test.epub` for reader open, bookmark restore, close, Android Back, and media-session return.
Result notes:
- Not started.
Next handoff:
- Start `R-004` to verify external EPUB open routing after Navigation3.

### R-004 Verify External EPUB Open and Navigation Return Paths

Status: todo
Phase: 1
Owner: unassigned
Depends on: R-003
Scope:
- Verify external EPUB open routing after the Navigation3 shell migration.
- Ensure transient SAF import behavior remains intact.
- Confirm media-session return does not create a duplicate app entry or overlapping playback.
Non-goals:
- Do not add new import features.
- Do not change Sasayaki playback internals.
- Do not change import deduplication policy.
Touched areas:
- app/src/main/java/moe/antimony/hoshi
- app/src/test
- app/src/androidTest
- docs/TODO.md
iOS reference:
- N/A for Android external intents. Preserve existing Android behavior and iOS-aligned import/storage semantics.
Exit criteria:
- External EPUB open still imports or opens through the intended app path.
- Returning from media controls restores the existing reader task.
- Any regression found is fixed inside this slice before moving on.
Verification:
- [ ] python3 tools/check_refactoring_tracker.py docs/REFACTORING_TRACKER.md
- [ ] ./gradlew test
- [ ] ./gradlew assembleDebug
- [ ] Manual emulator check for external EPUB open and Sasayaki media-session return.
Result notes:
- Not started.
Next handoff:
- Start `R-005` after Navigation3 behavior is stable.

### R-005 Extract BookshelfUiState and BookshelfViewModel

Status: todo
Phase: 2
Owner: unassigned
Depends on: R-004
Scope:
- Introduce immutable `BookshelfUiState`.
- Move book list reload, import result handling, selected/open reader events, bookmark save triggers, and loading/error state into `BookshelfViewModel`.
- Keep file picker launchers and `ActivityResultContract` calls in Compose.
Non-goals:
- Do not move app-level route ownership back into Bookshelf.
- Do not split `BookStorage` yet.
- Do not redesign Books UI.
Touched areas:
- app/src/main/java/moe/antimony/hoshi/features/bookshelf
- app/src/test
- docs/TODO.md
iOS reference:
- N/A unless a visible Books behavior changes; iOS behavior remains the parity source for user-visible interactions.
Exit criteria:
- Bookshelf composables mostly render immutable state and dispatch events.
- Tests cover import success/failure, open-reader event emission, delete/sort state where touched, and error/loading state.
- Existing Books visuals and behavior remain unchanged except explicit improvements recorded in scope.
Verification:
- [ ] python3 tools/check_refactoring_tracker.py docs/REFACTORING_TRACKER.md
- [ ] ./gradlew test
- [ ] ./gradlew assembleDebug
Result notes:
- Not started.
Next handoff:
- Start `R-006` to apply the same state-holder pattern to Dictionary.

### R-006 Extract DictionaryUiState and DictionaryViewModel

Status: todo
Phase: 2
Owner: unassigned
Depends on: R-005
Scope:
- Introduce immutable `DictionaryUiState`.
- Move dictionary import state, type selection, ordering/enabled changes, lookup rebuild state, and loading/error state into `DictionaryViewModel`.
- Keep SAF picker launchers in Compose.
Non-goals:
- Do not split `DictionaryRepository` yet.
- Do not alter native bridge behavior.
- Do not redesign Dictionary UI.
Touched areas:
- app/src/main/java/moe/antimony/hoshi/features/dictionary
- app/src/test
- docs/TODO.md
iOS reference:
- `reference/Hoshi-Reader-iOS/Features/Dictionary/DictionaryView.swift`
Exit criteria:
- Dictionary composables mostly render immutable state and dispatch events.
- Tests cover import success/failure, dictionary type selection, ordering/enabled changes, and lookup rebuild state.
- Frequency and pitch dictionaries are still managed by type and not treated as term fallback dictionaries.
Verification:
- [ ] python3 tools/check_refactoring_tracker.py docs/REFACTORING_TRACKER.md
- [ ] ./gradlew test
- [ ] ./gradlew assembleDebug
Result notes:
- Not started.
Next handoff:
- Start `R-007` to split Book storage responsibilities.

### R-007 Split BookStorage Behind Repository and Data Sources

Status: todo
Phase: 3
Owner: unassigned
Depends on: R-005
Scope:
- Introduce a repository-facing book API and focused helpers for file storage, import extraction, bookmark persistence, and metadata/bookinfo sidecars.
- Preserve all existing directory names, sidecar JSON names, JSON shapes, and title-based import behavior.
- Add characterization tests before moving persistence code.
Non-goals:
- Do not change EPUB parser APIs.
- Do not migrate to Room.
- Do not alter user-visible import or progress behavior.
Touched areas:
- app/src/main/java/moe/antimony/hoshi/epub
- app/src/test
- docs/TODO.md
iOS reference:
- `reference/Hoshi-Reader-iOS` for sidecar shape and user-visible storage semantics when behavior is affected.
Exit criteria:
- Public book API is smaller, main-safe at call sites, and backed by focused data-source helpers.
- Tests cover duplicate import, metadata fallback, unsafe path rejection, bookmark read/write/progress, and sidecar read/write compatibility.
- Existing imported books remain readable.
Verification:
- [ ] python3 tools/check_refactoring_tracker.py docs/REFACTORING_TRACKER.md
- [ ] ./gradlew test
- [ ] ./gradlew assembleDebug
- [ ] Manual emulator check with an existing import and a fresh `testdata/test.epub` import.
Result notes:
- Not started.
Next handoff:
- Start the dictionary repository split from `docs/ARCHITECTURE_REFACTORING.md` after book storage boundaries are stable.
