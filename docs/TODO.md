# Hoshi Android Agent TODO

Last updated: 2026-05-26

This file is the short operational handoff for future agents.

## Maintenance Rules

- Keep this file under 150 lines.
- Record only current state, next actionable work, active blockers, and durable validation requirements.
- Do not paste long emulator transcripts, adb details, screenshot observations, release notes, or per-commit history here.
- Put user-visible shipped changes in `docs/CHANGELOG.md`.
- Keep architecture-refactor slice state out of tracked docs; use the local `.codex/skills/hoshi-refactoring-workflow` skill when available.
- Put detailed reproduction, verification logs, and investigation notes in the relevant issue, PR, commit message, or a focused doc.
- When completing a task, update the smallest relevant line here in the same commit.
- Keep `docs/CHANGELOG.md` `[Unreleased]` free of fixup notes for not-yet-released features; fold them into the original feature entry or omit them until they describe a fix to already shipped user-visible behavior.

## Open Alignment Work

### Architecture And Hardening

- Make screen-level Compose Flow collection lifecycle-aware with `collectAsStateWithLifecycle()` where the UI lifecycle is the right owner.
- Continue Reader state/WebView bridge extraction in behavior-protected slices from `docs/ARCHITECTURE_REFACTORING.md`; keep `ReaderWebView` focused on composition and wiring.
- Replace remaining brittle source-string tests in touched areas with behavior, API, state-flow, or structured-config coverage where possible.
- Add EPUB/WebView regression fixtures for cover pages, multi-image pages, vertical text, horizontal text, complex spines, and broken resources.
- Add repeatable benchmark or baseline-profile entry points for cold start, EPUB import/open reader, reader page turn, dictionary search, and lookup popup open.

### Bookshelf, Import, And Backup

- Device-validate shelf-name entry, including user shelves named Reading alongside the virtual Reading Shelf, and multi-EPUB DocumentsUI import in a session where text input and picker interaction can be driven reliably.
- Device-validate editable text fields in dark and E-ink themes, confirming visible cursors and cursor-driven horizontal scrolling for long search, Audio source, Sync, Anki, shelf, and book-title values.
- Cross-validate Android-created `Books` and `Dictionaries` `.hoshi` archives restored by iOS.

### Reader And Lookup

- Use `docs/IOS_UPSTREAM_SYNC_QUEUE.md` as the current iOS upstream sync queue; checked through `9b3e135`, with Dictionary pull-to-clear and dictionary auto-update sync work pending; defer EPUB storage-format migration until upstream iOS/TTU book data sync stabilizes.
- Completed `docs/IOS_UPSTREAM_SYNC_QUEUE.md` slice 1: continuous reader padding now belongs to the visible viewport, vertical paginated columns resolve from page height, and chapter HTML receives an early XHTML-safe viewport while retaining the fast `loadUrl` chapter path.
- Completed `docs/IOS_UPSTREAM_SYNC_QUEUE.md` slice 2: large reader images and SVG image media now use iOS-style tap handling, Blur Images first-tap reveal, fullscreen zoom, and copy/save/share controls.
- Completed `docs/IOS_UPSTREAM_SYNC_QUEUE.md` slice 3: Advanced reader Layout now includes iOS-style paragraph spacing with persistence, WebView reload-key participation, and vertical/horizontal CSS margin mapping.
- Completed `docs/IOS_UPSTREAM_SYNC_QUEUE.md` slice 4: reader chrome now uses Android immersive system bars with transient edge-swipe reveal, iOS-aligned focus-mode entry on selection/page/scroll, floating center info bubbles, top text safety spacing, screen-edge focus quick controls, a small bottom gesture-safe progress band, and bottom chrome overlays without reserving button space in reader content.
- Completed `docs/IOS_UPSTREAM_SYNC_QUEUE.md` slice 5: recursive lookup popup selection now uses the configured scan length; zoom-coordinate handling was aligned with iOS but the pre-fix drift was not reproduced on Android WebView.
- Device-validate the reader lookup iframe popup path across paged and continuous mode, vertical and horizontal writing, recursive child lookup, parent-scroll child dismissal, duplicate state, audio error/autoplay, popup scale levels, redirect history, Sasayaki popup controls, E-ink selection marks, swipe dismiss, outside tap/stylus dismiss, dictionary media images, and absence of invisible touch blockers after dismissal.
- Reader lookup iframe now preloads/reuses the root iframe, gates visibility on first renderable content plus root selection highlight readiness, restores E-ink underline-style root marks, keeps action/Sasayaki controls aligned with the native popup layout, and lazy-loads popup dictionary media; it has real-device smoke coverage for vertical lookup, Sasayaki control-bar layout, popup bottom overscroll isolation, and swipe dismiss, while the full validation matrix above remains open.
- Smoke-test Dictionary tab and Process Text lookup popups after reader iframe work, confirming their cold native overlay path still supports recursive lookup, audio/Anki buttons, redirects, selection marks, and touch passthrough.
- Device-validate vertical lookup selection on ruby text, confirming E-ink underlines, regular highlights, and popup placement share one furigana-aware selection area.
- Device-validate continuous-mode lookup popup placement with nonzero reader padding in both vertical and horizontal writing.
- Device-validate paginated page turns with top and bottom progress counters enabled on E-ink, confirming the counter no longer refreshes before the page flip.
- Device-validate E-ink reader lookup underlines in horizontal and vertical text, confirming the line sits close to selected text without obscuring glyphs.
- Device-validate reader popup Reduced Motion Scrolling on an E-ink target, including vertical swipe threshold, 40%-100% scroll amount, mouse wheel/page-wheel input, and coexistence with horizontal swipe-to-dismiss.
- Device-validate popup-to-popup lookup selections, confirming child popup display syncs with iframe/native parent selection marks, E-ink mode uses underlines, and scrolling a parent popup dismisses child popups.
- Device-validate reader lookup with a real tablet stylus, confirming hover plus tap opens lookup, tapping outside closes the lookup popup, and finger taps and popup interactions still work.
- Device-validate reader lookup popup open and dismiss on a slow E-ink target, confirming popup content appears before interaction, autoplay does not outrun first visible content, iframe selection marks appear and disappear with the popup, and highlighted text stays readable.
- Finish remaining iOS `PopupWebView` Anki mining behavior beyond selected popup text export.
- Validate paginated and continuous reader modes together for cover image pages, multi-image illustration pages, long text paging, chapter-list jumps into mid-book chapters, forward/backward progress monotonicity, per-page progress updates and restore landing inside large text nodes, forward and backward chapter boundaries, reverse cross-chapter landing at the previous chapter end, lookup popup open, and bookmark restore.
- Device-validate bookshelf-to-reader open latency after the reader route stopped doing duplicate EPUB text parsing when valid `bookinfo.json` sidecars are present.
- Device-validate iOS-style reader jump return controls after chapter, character, highlight, and internal-link jumps, confirming back/forward targets remain stable through paginated and continuous manual movement.
- Re-check forward chapter-boundary landings at chapter start, restore-gated chapter jumps, and stable progress counters during rapid boundary flips after reader pagination changes.
- When touching Sasayaki reader highlighting, validate reader open/restore remains fast and stable at positions with matched cues.
- Re-run diagonal popup swipe validation once a Reader or nested Dictionary popup state is reliably reachable.
- Future reader fixes must start from `reference/Hoshi-Reader-iOS/Features/Reader/ReaderWebView/ReaderWebView.swift` plus the matching JS/CSS, and must keep WebView-based reading and lookup.

### Dictionary

- Device-validate recommended dictionary downloads from the Dictionaries screen, covering JMdict, JMnedict, Jiten, and Jitendex individual downloads and confirming each imported dictionary remains updatable.
- Device-validate Low Memory Usage Mode with a large Yomitan archive, confirming the setting defaults off, persists, reduces peak memory when enabled, and keeps imported term/frequency/pitch dictionaries usable.
- Device-validate settings segmented controls in Dictionaries, Dictionary Settings, and Advanced Audio, confirming selected labels no longer shift.
- For deinflection regressions, verify conjugated lookup results such as `食べた` show iOS-style explanation overlays when tapping deinflection tags.
- Keep frequency and pitch dictionaries type-specific; do not treat metadata dictionaries as term fallback dictionaries.
- Do not reimplement Yomitan import, lookup, media, or style extraction outside `third_party/hoshidicts-kotlin-bridge` unless the bridge gap is documented first.

### Highlights And Notes

- Blocked: device-validate reader Highlights sheet grouping for highlights in unlabeled EPUB spine entries once a repeatable fixture or saved reader state exists.
- Device-validate remaining reader highlight restore, jump, delete, all-color, compact color-picker swatches, and continuous-mode behavior against iOS after paginated creation, primary Highlight toolbar placement, anchored color-picker placement, and native selection drag page-locking were verified on Android targets.
- Blocked: real-device WebView selection can enter a valid native text-selection state while Samsung/Android does not show the floating selection toolbar; keep this tracked as a platform interaction issue before adding more highlight toolbar patches.
- Add note editing only if/when iOS exposes a user-visible notes flow.

### Anki

- Device-validate Android AnkiConnect against both an HTTPS internet host and a private HTTP host: connect, fetch, duplicate check, media storage, add-note, and optional force-sync behavior.
- Keep backend coverage for duplicate checks, AnkiDroid fetch failures, and AnkiConnect request shaping.
- Keep popup mining decoupled from direct HTTP calls; route backend differences through the Anki backend boundary.

### Sync

- Preserve the shared lifecycle-aware loaded-settings collection pattern when adding settings pages so controls do not flash default values before saved preferences load.
- Keep reader auto-export save/upload work on a scope that survives reader route disposal so close and background flushes can finish after navigation.
- Blocked: device-validate the first Android Google Drive sync slice with `testdata/test.epub` on a user-configured Device Code OAuth client from the same project as iOS/ッツ: connect/sign-out state, transient network backoff and another-device authorization guidance, long-press manual import/export result dialogs, reader-open import-only, iOS-aligned paginated/continuous auto-export timing, close/background flush export, statistics Merge/Replace, and Sasayaki last-position sync.

### Release Distribution

- Before F-Droid distribution, split update behavior by distribution channel so F-Droid builds do not bypass F-Droid update checks.
- Device-validate GitHub update prompts after the check/download split, covering skip-version, manual checks, completed-download prompts, user-triggered install, and same-version APK cleanup.

## Required Validation

Before claiming implementation complete, run:

```bash
./gradlew test
./gradlew assembleDebug
```

Also run `./gradlew lint` when changing resources, manifest, UI, packaging, or release-facing build behavior.

For settings/navigation changes, verify settings controls update immediately and route changes avoid fade transitions on e-ink displays.

For dark-theme cold-start regressions, use emulator screen recording with the App Appearance theme set to Dark and confirm no light `No Books` app frame appears before the bookshelf loads.

For build label regressions, verify build variant manifest labels override localized app name resources: release builds keep the launcher label `Hoshi Reader` and debug builds show `Hoshi Debug` on English and Simplified Chinese devices.

For bookshelf tab-switch regressions, use real-device screen recording to confirm cover placeholders do not flash white when returning to Books from the bottom tab bar.

For bookshelf-to-reader regressions, use real-device continuous screenshots or screen recording to confirm no Bookshelf loading spinner or dark-mode white loading frame appears between tapping a book and showing the Reader.

For reader/dictionary/audio user flows, perform targeted emulator or device validation using the test data listed in `AGENTS.md`; include external AnkiconnectAndroid Local Audio URL add behavior and built-in Local Audio enable behavior when touching audio sources, and use the `pixivで読む` definition link case for dictionary external-link regressions.

For reader/dictionary theme regressions, verify open Dictionary tab results, the Dictionary search cursor, reader lookup taps and open reader lookup popups, system status/navigation icon contrast in Light, Sepia Light, Dark, and Sepia Dark under Android system dark mode, reader theme-family switches update colors without WebView reload, and System theme's Use Sepia as Light Theme toggle update immediately when switching between Light, Dark, System, and E-ink appearance modes.

For reader process-restore regressions, verify returning directly to an open book after app process eviction still rebuilds dictionary lookup and opens reader lookup popups without first visiting the bookshelf.

For Dictionary tab input regressions, verify opening the tab focuses the search field, shows the soft keyboard, and hints Japanese input when a Japanese-capable keyboard is installed.

For reader appearance chrome regressions, verify Show Title off, Progress Position Bottom, compact bottom buttons, Sasayaki top-right toggle spacing, top title centering with asymmetric top buttons, bottom reader-menu spacing and light-mode menu outline visibility, focus mode status-bar hiding without text reflow, Android Back revealing chrome before closing the reader, and all progress indicators hidden against the paginated reader text area.

For reader appearance controls, verify Layout Mode shows both Paginated and Continuous labels without truncation in the settings page and reader sheet.

For reader statistics regressions, verify Advanced -> Statistics defaults off, enabling it turns on the three Appearance statistics toggles, Off/Page Turn/On autostart modes, the reader Statistics sheet without an extra header close row, single 70%-height reader sheet behavior without detent jitter, compact reader sheet row density, smooth Appearance and Chapters sheet scrolling, the Chapters sheet without the extra large title/close row while keeping the book cover header, untruncated Appearance segmented labels with clear selected-state contrast in E-ink mode, compact single-row Appearance font selection, the top-left session toggle using chart/timer icons, bottom speed/time display, page-turn delayed saves, close/background `statistics.json` persistence, and background pause/resume without counted elapsed time.

For Sasayaki settings regressions, verify fresh installs default Sasayaki, Show Sasayaki Toggle, Auto-Scroll, and Auto-Pause on Lookup on, and that Appearance can toggle the reader Sasayaki button.

For Sasayaki matching regressions, verify short low-confidence `＊` subtitle cues are skipped while longer `＊` cues still match and advance playback alignment.

For Sasayaki skip-control regressions, verify the reader bottom skip buttons flank the existing Back/Menu buttons, the same cue/5s/10s/15s/30s action applies from reader chrome, Sasayaki sheet controls, and Android system media controls, and Reverse Vertical Bottom Buttons only swaps the visible bottom reader buttons in vertical writing.

For Sasayaki volume-key regressions, verify volume-key seek with loaded audiobook audio, fallback without loaded audio, priority over Volume Keys Turn Pages, and Reverse Volume Key Direction affecting both seek and page-turn controls.

For reader keep-screen-on regressions, verify Behavior -> Keep Screen On defaults off, persists after leaving settings, keeps the display awake while the reader is foregrounded when enabled, clears after closing the reader when disabled, and still keeps Sasayaki playback awake only while playback and Auto-Scroll are active.

For reader text layout regressions, verify Appearance -> Layout changes such as Vertical Padding reload the current chapter at the displayed position and visibly affect text spacing.

For continuous reader layout regressions, verify vertical-writing Horizontal Padding and horizontal-writing Vertical Padding inset the current visible viewport rather than only the chapter ends, and continuous reader chrome only re-enters focus mode from a new drag gesture after tapping to reveal controls.

For reader popup settings regressions, verify changing every Popup section control while a continuous reader is open does not rebuild the WebView and does not stop scroll progress updates.

For localization changes, run `./gradlew :app:testDebugUnitTest --tests moe.antimony.hoshi.LocalizationResourceTest` and keep `docs/TRANSLATING.md` aligned with supported locale resource directories.
