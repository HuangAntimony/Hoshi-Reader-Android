# Hoshi Android Agent TODO

Last updated: 2026-05-13

This file is the short operational handoff for future agents.

## Maintenance Rules

- Keep this file under 150 lines.
- Record only current state, next actionable work, active blockers, and durable validation requirements.
- Do not paste long emulator transcripts, adb details, screenshot observations, release notes, or per-commit history here.
- Put user-visible shipped changes in `docs/CHANGELOG.md`.
- Keep architecture-refactor slice state out of tracked docs; use the local `.codex/skills/hoshi-refactoring-workflow` skill when available.
- Put detailed reproduction, verification logs, and investigation notes in the relevant issue, PR, commit message, or a focused doc.
- When completing a task, update the smallest relevant line here in the same commit.

## Open Alignment Work

### Architecture And Hardening

- Make screen-level Compose Flow collection lifecycle-aware with `collectAsStateWithLifecycle()` where the UI lifecycle is the right owner.
- Continue Reader state/WebView bridge extraction in behavior-protected slices from `docs/ARCHITECTURE_REFACTORING.md`; keep `ReaderWebView` focused on composition and wiring.
- Replace remaining brittle source-string tests in touched areas with behavior, API, state-flow, or structured-config coverage where possible.
- Add EPUB/WebView regression fixtures for cover pages, multi-image pages, vertical text, horizontal text, complex spines, and broken resources.
- Add repeatable benchmark or baseline-profile entry points for cold start, EPUB import/open reader, reader page turn, dictionary search, and lookup popup open.

### Bookshelf, Import, And Backup

- Device-validate shelf-name entry and multi-EPUB DocumentsUI import in a session where text input and picker interaction can be driven reliably.
- Cross-validate Android-created `Books` and `Dictionaries` `.hoshi` archives restored by iOS.

### Reader And Lookup

- Finish remaining iOS `PopupWebView` Anki mining behavior beyond selected popup text export.
- Validate paginated and continuous reader modes together for cover image pages, multi-image illustration pages, long text paging, forward and backward chapter boundaries, reverse cross-chapter landing, lookup popup open, and bookmark restore.
- Re-check forward chapter-boundary landings at chapter start, visual-state-gated chapter jumps, and stable progress counters during rapid boundary flips after reader pagination changes.
- Re-run diagonal popup swipe validation once a Reader or nested Dictionary popup state is reliably reachable.
- Future reader fixes must start from `reference/Hoshi-Reader-iOS/Features/Reader/ReaderWebView/ReaderWebView.swift` plus the matching JS/CSS, and must keep WebView-based reading and lookup.

### Dictionary

- Align recommended dictionary download/update behavior with iOS `DictionaryView`, including recommended downloads, update availability, and revision-based update flow.
- Keep frequency and pitch dictionaries type-specific; do not treat metadata dictionaries as term fallback dictionaries.
- Do not reimplement Yomitan import, lookup, media, or style extraction outside `third_party/hoshidicts-kotlin-bridge` unless the bridge gap is documented first.

### Highlights And Notes

- Store highlight anchors from WebView range data.
- Restore highlights after chapter load through JS.
- Align highlight tap, delete, and color behavior with iOS.

### Anki

- Add Android AnkiConnect parity behind the existing Anki backend boundary: connection, fetch, duplicate checks, media storage, add-note, and optional force-sync behavior.
- Keep backend coverage for duplicate checks, AnkiDroid fetch failures, and the future AnkiConnect implementation.
- Keep popup mining decoupled from direct HTTP calls; route backend differences through the Anki backend boundary.

### Sync

- Blocked: end-to-end Google Drive transfer validation still needs an Android OAuth client configured for the installed package/SHA-1 and a signed-in test account; this session validated the settings surface and bookshelf sync menu on emulator only.
- Device-validate the first Android Google Drive sync slice with `testdata/test.epub`: connect Google Drive, long-press manual import/export, reader-open import-only, close/background flush export, statistics Merge/Replace, and Sasayaki last-position sync.
- Future no-GMS sync auth should add Device Code or Browser PKCE behind `DriveAuthorizer` / `DriveAccessTokenProvider` without changing `SyncManager` or Drive sidecar rules.

### Release Distribution

- Before F-Droid distribution, split update behavior by distribution channel so F-Droid builds do not bypass F-Droid update checks.

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

For reader/dictionary/audio user flows, perform targeted emulator or device validation using the test data listed in `AGENTS.md`; for dictionary external-link regressions, use the `pixivで読む` definition link case.

For reader/dictionary theme regressions, verify open Dictionary tab results, the Dictionary search cursor, open reader lookup popups, and System theme's Use Sepia as Light Theme toggle update immediately when switching between Light, Dark, System, and E-ink appearance modes.

For Dictionary tab input regressions, verify opening the tab focuses the search field, shows the soft keyboard, and hints Japanese input when a Japanese-capable keyboard is installed.

For reader appearance chrome regressions, verify Show Title off, Progress Position Bottom, compact bottom buttons, Sasayaki top-right toggle spacing, bottom reader-menu spacing, focus mode status-bar hiding without text reflow, and all progress indicators hidden against the paginated reader text area.

For reader appearance controls, verify Layout Mode shows both Paginated and Continuous labels without truncation in the settings page and reader sheet.

For reader statistics regressions, verify Advanced -> Statistics defaults off, enabling it turns on the three Appearance statistics toggles, Off/Page Turn/On autostart modes, the reader Statistics sheet without an extra header close row, single 70%-height reader sheet behavior without detent jitter, compact reader sheet row density, smooth Appearance and Chapters sheet scrolling, the Chapters sheet without the extra large title/close row while keeping the book cover header, untruncated Appearance segmented labels with clear selected-state contrast in E-ink mode, compact single-row Appearance font selection, the top-left session toggle using chart/timer icons, bottom speed/time display, page-turn delayed saves, close/background `statistics.json` persistence, and background pause/resume without counted elapsed time.

For Sasayaki settings regressions, verify fresh installs default Sasayaki, Show Sasayaki Toggle, Auto-Scroll, and Auto-Pause on Lookup on, and that Appearance can toggle the reader Sasayaki button.

For Sasayaki skip-control regressions, verify the reader bottom skip buttons flank the existing Back/Menu buttons, and the same cue/5s/10s/15s/30s action applies from reader chrome, Sasayaki sheet controls, and Android system media controls.

For Sasayaki volume-key regressions, verify volume-key seek with loaded audiobook audio, fallback without loaded audio, priority over Volume Keys Turn Pages, and Reverse Volume Key Direction affecting both seek and page-turn controls.

For reader text layout regressions, verify Appearance -> Layout changes such as Vertical Padding reload the current chapter at the displayed position and visibly affect text spacing.

For continuous reader layout regressions, verify vertical-writing Horizontal Padding and horizontal-writing Vertical Padding inset the current visible viewport rather than only the chapter ends.

For reader popup settings regressions, verify changing every Popup section control while a continuous reader is open does not rebuild the WebView and does not stop scroll progress updates.
