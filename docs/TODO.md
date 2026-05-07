# Hoshi Android Agent TODO

Last updated: 2026-05-07

This file is the short operational handoff for future agents.

## Maintenance Rules

- Keep this file under 150 lines.
- Record only current state, next actionable work, active blockers, and durable validation requirements.
- Do not paste long emulator transcripts, adb details, screenshot observations, release notes, or per-commit history here.
- Put user-visible shipped changes in `docs/CHANGELOG.md`.
- Keep architecture-refactor slice state out of tracked docs; use the local `.codex/skills/hoshi-refactoring-workflow` skill when available.
- Put detailed reproduction, verification logs, and investigation notes in the relevant issue, PR, commit message, or a focused doc.
- When completing a task, update the smallest relevant line here in the same commit.

## Active Work

### Architecture Refactoring

Source of truth: `docs/ARCHITECTURE_REFACTORING.md` for direction; local `.codex/skills/hoshi-refactoring-workflow` for execution workflow when available.

Status: `in_progress`

- Completed architecture slices: book repository/data-source APIs are main-safe, Sasayaki sidecar DTOs live outside feature playback/UI packages, and Reader selection bridge payload handling is extracted behind behavior tests.
- Next architecture slice: make Compose screen Flow collection lifecycle-aware with `collectAsStateWithLifecycle()`.
- Defer modularization until sidecar/model, Reader bridge/state, and native build boundaries are stable.
- Keep refactor commits slice-sized and follow the local refactoring workflow skill if present.

### Bookshelf

Status: `in_progress`

- Align multi-select, shelves, and batch actions with iOS.
- Keep EPUB import through Android SAF.
- Preserve iOS-shaped `Books/<safeTitle>` storage and sidecar JSON compatibility.

### Reader And Lookup

Status: `in_progress`

- Paginated swipe direction now follows iOS per text orientation: horizontal left-swipe advances, vertical right-swipe advances.
- Reader Appearance now allows font size increases up to 60.
- Paginated reader page turns now cache chapter page bounds after layout, trigger swipe turns during quick drags or short fast flicks, update visible progress from memory immediately, debounce bookmark saves until page turning is idle, flush pending page-turn saves before closing or backgrounding the reader, skip no-op selection bridge calls, and coalesce bookshelf refreshes until Reader close, reducing PageUp/PageDown and swipe jank on slower e-ink devices.
- Continuous scroll mode is implemented behind Appearance -> Layout -> Mode; keep paginated and continuous reader validation in scope for future reader changes, including forward chapter-boundary landings at the chapter start, Android visual-state-gated chapter jumps, and stable progress counters during rapid boundary flips.
- Wire remaining iOS `PopupWebView` Anki mining behavior beyond selected popup text export.
- Re-run diagonal popup swipe validation when a reliable Reader or nested Dictionary popup state is available.
- Reader fixes must start from `reference/Hoshi-Reader-iOS/Features/Reader/ReaderWebView/ReaderWebView.swift` plus the matching JS/CSS.
- Keep WebView-based reading and lookup. Do not replace it with native text rendering.
- Manual reader validation must cover cover image pages, multi-image illustration pages, long text paging, forward/backward chapter boundaries, reverse cross-chapter landing, lookup popup open, and bookmark restore.

### Dictionary

Status: `in_progress`

- Dictionary management now uses explicit drag handles and two-step swipe-to-delete to reduce accidental deletes.
- Dictionary popup entry colors now follow Hoshi's active app theme instead of Android's system night mode.
- Align recommended dictionary download/update state with iOS `DictionaryView`.
- Do not reimplement Yomitan import, lookup, media, or style extraction outside `third_party/hoshidicts-kotlin-bridge` unless the bridge gap is documented first.
- Frequency and pitch dictionaries must stay type-specific; do not treat metadata dictionaries as term fallback dictionaries.

### Highlights And Notes

Status: `todo`

- Store highlight anchors from WebView range data.
- Restore highlights after chapter load through JS.
- Align highlight tap, delete, and color behavior with iOS.

### Anki

Status: `in_progress`

- Android now targets AnkiDroid's native API instead of AnkiMobile callbacks.
- Current unit coverage tracks Anki settings field rows, the tags editor sharing the same focused text editing path without handlebar choices, Fetch preserving still-available deck/model selections, blank/non-Lapis -> Lapis applying defaults, and Lapis -> Lapis Fetch/mining not refilling edited mappings.
- Current emulator/device validation covers Lapis field mapping restore, field mapping list scrolling, dictionary-specific Anki handlebars without first-glossary fallback, MK3 SVG dictionary media/inline gaiji styling, selected sentence occurrence bolding, Sasayaki sentence-expanded cue audio mining, and local audio source mining.
- Next: expand duplicate and future AnkiConnect backend coverage.
- Keep AnkiConnect behind the Anki backend boundary; do not add it by coupling popup mining directly to HTTP calls.

### Audio And Sasayaki

Status: `in_progress`

- Read dictionary media through the existing dictionary bridge.
- Keep Sasayaki sidecars iOS-compatible.
- Sasayaki audiobook playback and dictionary word audio now use Media3 ExoPlayer; keep standard Android media previous/next controls, paused Sasayaki cue reveal, lookup audio, and Anki cue export behavior-protected.
- Dictionary word audio now owns short audio-focus policy instead of delegating it to ExoPlayer, preserving Interrupt, best-effort Lower Volume, and Keep Volume background-audio behavior.
- Local audio `android.db` import uses a broad Android picker MIME for vendor file-manager compatibility, with Hoshi-side `.db` validation after selection.

### Sync

Status: `todo`

- Investigate Android Google Sign-In/OAuth/Drive API integration.
- Match iOS upstream behavior by creating the root `ttu-reader-data` Drive folder when needed.
- Sync sidecar JSON, progress, settings, and dictionary configuration.
- Do not reuse iOS token/keychain assumptions.

### Regression Coverage And Release Hardening

Status: `in_progress`

- Add EPUB fixtures for cover, images, vertical text, horizontal text, complex spine, and broken resources.
- Expand WebView pagination regression checks.
- Replace brittle source-string tests with behavior tests in areas being refactored; keep source guards only for security, SAF/native/build wiring, or framework constraints that cannot be tested behaviorally.
- Release build speed is being hardened through cached Gradle/Rust native tasks, a slim release APK workflow, and a separate full test/lint CI gate.
- Keep release/debug native build behavior stable while architecture refactoring proceeds.

## Persistent Blockers

- Diagonal popup swipe validation is blocked until adb or manual setup can reliably reach a Reader or nested Dictionary popup state suitable for gesture verification.

## Required Validation

Before claiming implementation complete, run:

```bash
./gradlew test
./gradlew assembleDebug
```

Also run `./gradlew lint` when changing resources, manifest, UI, packaging, or release-facing build behavior.

For settings/navigation changes, verify settings controls update immediately and route changes avoid fade transitions on e-ink displays.

For dark-theme cold-start regressions, use emulator screen recording with the App Appearance theme set to Dark and confirm no light `No Books` app frame appears before the bookshelf loads.

For bookshelf tab-switch regressions, use real-device screen recording to confirm cover placeholders do not flash white when returning to Books from the bottom tab bar.

For bookshelf-to-reader regressions, use real-device continuous screenshots or screen recording to confirm no Bookshelf loading spinner or dark-mode white loading frame appears between tapping a book and showing the Reader.

For reader/dictionary/audio user flows, perform targeted emulator or device validation using the test data listed in `AGENTS.md`.
