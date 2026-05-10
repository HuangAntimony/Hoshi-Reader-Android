# Hoshi Android Agent TODO

Last updated: 2026-05-10

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

- Bookshelf management now supports iOS-style custom shelves, the optional collapsible Reading shelf, rotation-stable compact collapsed shelf previews, read-completion badges, single-book moves, selection mode, batch moves/deletes, and Mark Read.
- Next: complete shelf-name entry and multi-EPUB DocumentsUI validation on a device session where text input can be driven manually or through adb.
- Keep EPUB import through Android SAF.
- Preserve iOS-shaped `Books/<safeTitle>` storage and sidecar JSON compatibility, including UUID book ids in `metadata.json`/`shelves.json` and `Books/<folder>/<cover>` metadata cover paths for backup/restore interop. Keep the legacy Android field migration isolated so it can be removed after the supported upgrade window.
- Advanced -> Backup exports and restores iOS-shaped `Books` and `Dictionaries` `.hoshi` archives; iOS-created Books restore is device-validated, and next validation should cover Android-created archives restored by iOS.

### Reader And Lookup

Status: `in_progress`

- Paginated swipe direction now follows iOS per text orientation: horizontal left-swipe advances, vertical right-swipe advances.
- Reader Appearance now allows font size increases up to 60.
- Paginated reader page turns now cache chapter page bounds after layout, trigger swipe turns during quick drags or short fast flicks, update visible progress from memory immediately, debounce bookmark saves until page turning is idle, flush pending page-turn saves before closing or backgrounding the reader, skip no-op selection bridge calls, and coalesce bookshelf refreshes until Reader close, reducing PageUp/PageDown and swipe jank on slower e-ink devices.
- Lookup popup placement follows iOS clamp semantics even when safe-area constraints leave less room than the configured popup height.
- Continuous scroll mode is implemented behind Appearance -> Layout -> Mode; keep paginated and continuous reader validation in scope for future reader changes, including forward chapter-boundary landings at the chapter start, Android visual-state-gated chapter jumps, and stable progress counters during rapid boundary flips.
- Wire remaining iOS `PopupWebView` Anki mining behavior beyond selected popup text export.
- Re-run diagonal popup swipe validation when a reliable Reader or nested Dictionary popup state is available.
- Reader fixes must start from `reference/Hoshi-Reader-iOS/Features/Reader/ReaderWebView/ReaderWebView.swift` plus the matching JS/CSS.
- Keep WebView-based reading and lookup. Do not replace it with native text rendering.
- Manual reader validation must cover cover image pages, multi-image illustration pages, long text paging, forward/backward chapter boundaries, reverse cross-chapter landing, lookup popup open, and bookmark restore.

### Dictionary

Status: `in_progress`

- Android selected-text and shared-text lookups use the system `PROCESS_TEXT`, `TRANSLATE`, and plain-text `SEND` actions to show a top-safe, centered Hoshi lookup popup over the current app; nested lookups keep position-based popup placement.
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
- Current unit coverage tracks Anki settings field rows, duplicate collection/deck scope and cross-model settings, the tags editor sharing the same focused text editing path without handlebar choices, Fetch preserving still-available deck/model selections, blank/non-Lapis -> Lapis applying defaults, and Lapis -> Lapis Fetch/mining not refilling edited mappings.
- Current emulator/device validation covers Lapis field mapping restore, field mapping list scrolling, dictionary-specific Anki handlebars without first-glossary fallback, MK3 SVG dictionary media/inline gaiji styling, selected sentence occurrence bolding, Sasayaki sentence-expanded cue audio mining with playable `.aac` media, and local audio source mining.
- Next: expand duplicate and future AnkiConnect backend coverage.
- Keep AnkiConnect behind the Anki backend boundary; do not add it by coupling popup mining directly to HTTP calls.

### Audio And Sasayaki

Status: `in_progress`

- Read dictionary media through the existing dictionary bridge.
- Keep Sasayaki sidecars iOS-compatible.
- Sasayaki audiobook playback and dictionary word audio now use Media3 ExoPlayer; Sasayaki Anki cue export uses Media3 Transformer. Keep standard Android media previous/next controls, paused Sasayaki cue reveal, lookup audio, and Anki cue export behavior-protected.
- Dictionary word audio now owns short audio-focus policy instead of delegating it to ExoPlayer, preserving Interrupt, best-effort Lower Volume, and Keep Volume background-audio behavior.
- Local audio `android.db` import uses a broad Android picker MIME for vendor file-manager compatibility, with Hoshi-side `.db` validation after selection.

### Updates

Status: `in_progress`

- GitHub Releases are the current update source. Behavior -> Automatically Download Updates defaults on, registers immediate and periodic WorkManager checks, falls back to GitHub release proxy mirrors when direct GitHub access fails, and downloads newer APK assets through DownloadManager using a stable update package file name.
- Settings -> About shows the current version, links to the GitHub repository, can manually check GitHub Releases regardless of the automatic update setting, and opens downloaded APKs through Android's package installer only when they are newer than the installed app; app startup prompts to install a newer already-downloaded update.
- Next: before F-Droid distribution, split update behavior by distribution channel so F-Droid builds do not bypass F-Droid update checks.

### Sync

Status: `todo`

- Investigate Android Google Sign-In/OAuth/Drive API integration.
- Match iOS upstream behavior by creating the root `ttu-reader-data` Drive folder when needed.
- Sync sidecar JSON, progress, settings, and dictionary configuration.
- Do not reuse iOS token/keychain assumptions.

### Regression Coverage And Release Hardening

Status: `in_progress`

- Add EPUB fixtures for cover, images, vertical text, horizontal text, complex spine, and broken resources.
- Diagnostics now persists uncaught Java crash stack traces before Android terminates the process; keep this covered when changing startup or Application wiring.
- Expand WebView pagination regression checks.
- Replace brittle source-string tests with behavior tests in areas being refactored; keep source guards only for security, SAF/native/build wiring, or framework constraints that cannot be tested behaviorally.
- Release build speed is being hardened through cached Gradle/Rust native tasks, a slim release APK workflow, and a separate full test/lint CI gate.
- Keep release/debug native build behavior stable while architecture refactoring proceeds.

## Persistent Blockers

- Diagonal popup swipe validation is blocked until adb or manual setup can reliably reach a Reader or nested Dictionary popup state suitable for gesture verification.
- Manual reader/Anki popup validation is blocked in the current device session because adb lists devices but install and shell commands hang; retry on a responsive emulator or physical device.

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
