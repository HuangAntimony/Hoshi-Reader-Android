# iOS Upstream Sync Queue

This document tracks open Android work after checking iOS upstream `develop`.

- Source: `reference/Hoshi-Reader-iOS`
- Baseline for this refresh: `61306c70570c911c288d217d5a111d45204b345b`
- Latest checked: `origin/develop` at `cfc1e509a4b1f92a9d02dbf8c950cb392c0f25d9`
- Checked on: 2026-06-06

## Current Queue

### 1. Dictionary automatic updates

Status: pending Android sync.

Commit:

- `94d0c41` - dictionary auto updates.

Why this is broader:

- It crosses settings, dictionary repository import/update code, app foreground lifecycle, network constraints, partial-failure behavior, and update-result persistence.

iOS behavior to mirror:

- Dictionary settings show an Updates section when installed dictionaries are updatable.
- Users can enable automatic updates, choose Daily/Weekly/Monthly, and see the last successful update time or Never.
- On app activation, iOS checks elapsed interval and installed updatable dictionaries before starting updates.
- Automatic update sessions disallow expensive and constrained network access.
- Failed dictionaries do not cancel the whole batch; last-update time advances after at least one successful check/import.
- Manual update still reports failures.
- Updated dictionaries import into a temporary directory first, then replace the installed copy.

Android current gap:

- Android supports manual update checks for installed updatable dictionaries, preserving enabled state, order, and collapsed-title migration on rename.
- Android lacks automatic update settings, last-update display, activation-triggered checks, non-expensive network gating, partial-failure batch behavior, and temp-then-move update import.

Suggested slice:

- Add persisted auto-update settings and localized settings UI.
- Use Android network APIs/WorkManager constraints after confirming the current recommended Jetpack behavior.
- Make repository update import transactional per dictionary and record last successful update after at least one success.

Validation:

- Install an updatable dictionary, open Dictionaries, and confirm automatic update controls, interval choices, last update, and manual Update.
- Foreground the app on an allowed network after the interval elapses; confirm update runs without blocking dictionary use.
- Simulate one failure and one success; confirm success is applied, manual failure is surfaced, and last-update advances only after success.
- Confirm failed imports leave installed dictionaries intact.

### 2. Dictionary lookup normalization and query rebuild threading

Status: pending Android sync.

Commits:

- `0d6c072` - bump hoshidicts to `1198201a...`.
- `cfc1e50` - build query off main thread.

Why this precedes popup/Anki payload work:

- Lookup normalization and query rebuild behavior sit below Dictionary search, reader lookup, recursive popup lookup, and Anki mining. Updating this foundation first keeps later popup and template validation tied to the current native matching semantics.

iOS behavior to mirror:

- The hoshidicts revision adds Japanese text processors for NFKC normalization, alphanumeric-to-fullwidth conversion, and kanji variant standardization.
- Lookup query construction runs on a detached user-initiated task with a generation token so stale builds cannot overwrite newer query bundles.
- Lookup and style reads return empty results while no query bundle is ready instead of force-unwrapping a partially rebuilt query.

Android current gap:

- `third_party/hoshidicts-kotlin-bridge/app/src/main/cpp/hoshidicts` is still at `497578824f...`, while iOS now uses `1198201a...`; Android therefore lacks the new native text processors and their `utf8proc` / kanji-processor dependencies.
- `DictionaryRepository.rebuildLookupQuery()` and `DictionaryLookupQueryService.rebuild()` synchronously call `HoshiDicts.rebuildQuery(...)`. `DictionarySearchViewModel` wraps search rebuilds in `withContext(ioDispatcher)`, but the repository/service contract itself does not enforce an IO boundary or stale-build tokening for other callers.
- `LookupEngine.lookup()` and `LookupEngine.getStyles()` read the singleton `HoshiDicts.lookupObject` directly, so there is no Android-side ready/empty guard equivalent if query rebuild becomes asynchronous.

Suggested slice:

- Update `third_party/hoshidicts-kotlin-bridge` and nested hoshidicts submodules to the new native revision, wiring any new CMake/JNI dependencies without changing Android lookup models unnecessarily.
- Move lookup rebuild ownership to a coroutine/dispatcher-aware service API with stale-build protection, then adapt Dictionary tab, Bookshelf startup, backup restore, dictionary import/update, reader lookup, and process-text lookup callers.
- Add behavior tests around lookup rebuild ordering and native normalization where feasible; if native fixture coverage is limited, add a small tracked dictionary fixture or construct one in test.

Validation:

- Lookup terms containing half-width/full-width alphanumerics, NFKC-normalizable forms, and kanji variants in Dictionary tab, reader popup, recursive popup, and selected-text overlay.
- Import, enable/disable, reorder, delete, update, and backup-restore dictionaries while search/reader lookup is active; confirm the UI remains responsive and stale rebuilds do not replace newer dictionaries.
- `./gradlew test` and `./gradlew assembleDebug` on a clean native build after submodule updates.

### 3. Anki field templates, dictionary IPA display, and glossary handlebars

Status: pending Android sync; native frequency-sort dependency already present, newer normalization dependency tracked separately above.

Commits:

- `8ffca61` - autofill Lapis, Kiku, and Senren field mappings.
- `8ef25f4` - glossary-brief, glossary-first-brief, selected-glossary-fallback, selected-glossary-brief, selected-glossary-brief-fallback handlebars.
- `36be339` - support for IPA dicts.
- `5cbdaa8` - glossary-no-dictionary, use regex to create alt glossary handlebars.

Why this follows lookup normalization:

- IPA pitch display and Anki glossary payloads are generated from lookup data. Land the native normalization/query rebuild slice first, then adjust payload and settings-template semantics.

iOS behavior to mirror:

- Anki settings autofill field mappings for Lapis, Kiku, and Senren note types when no fields in the selected model are already mapped. Selecting/fetching a note type triggers autofill, but existing user mappings block automatic replacement.
- Pitch dictionaries can provide IPA/transcription strings alongside numeric pitch positions. Popup pitch groups render both, and duplicate pitch positions are still deduplicated across dictionaries.
- Anki mining supports new glossary handlebar variants:
  - brief variants strip glossary header labels.
  - no-dictionary variants remove dictionary names from labels.
  - selected-glossary fallback variants fall back to the first glossary when no selected dictionary value exists.
  - per-dictionary `{single-glossary-<dict>-brief}` and `{single-glossary-<dict>-no-dictionary}` are supported even if not all variants are shown in the insertion picker.

Android current gap:

- The Android hoshidicts bridge submodule is already at the newer `497578824f...` native revision, matching the iOS package update context and covering the native frequency-sort change from `e70008d`.
- Android JNI models currently expose `pitchPositions` but not transcription/IPA strings.
- Android popup HTML and Anki renderer support core glossary and selected/single glossary handlebar values, but not the new brief/no-dictionary/fallback variants.
- Android `LapisPreset` supports only Lapis-like fields. `AnkiViewModel.selectNoteType()` currently replaces mappings with `LapisPreset.applyDefaults(noteType, emptyMap())`, so Kiku/Senren are not autofilled and existing user mappings can be cleared on note-type selection instead of preserving iOS's "autofill only when no selected-model fields are mapped" behavior.

Suggested slice:

- Extend the bridge-facing pitch model only if `third_party/hoshidicts-kotlin-bridge` exposes transcription data; otherwise document the bridge gap first.
- Render IPA/transcription rows in popup pitch groups without breaking compact pitch and pitch deduplication behavior.
- Add Anki handlebar rendering and focused tests for brief, no-dictionary, fallback, and per-dictionary suffix forms.
- Replace `LapisPreset` with a template set covering Lapis, Kiku, and Senren field names, and gate autofill so existing selected-model field mappings are never overwritten.
- Keep insertion UI aligned with iOS by hiding advanced variants that iOS does not surface directly.

Validation:

- Import an IPA-capable pitch dictionary and confirm lookup popups show transcription rows.
- Fetch/select Lapis, Kiku, and Senren models through AnkiDroid and AnkiConnect; confirm only empty model mappings are autofilled and custom mappings persist.
- Mine Anki notes through AnkiDroid and AnkiConnect with each new handlebar variant, including dictionary media embedding and selected-dictionary fallback.

## Covered Or No Android Action

- `ab6722e`, `67bdbb9`, `1aaee97`, `c2e1c09`, `32d76d2`: Android now covers the TTU/Google Drive book-data slice. Book folders retain `<folder>.epub` and `BookMetadata.epub`; legacy extracted books are repacked only after parse verification; Books can export stored EPUBs; Backup settings expose TTU bookdata export/import; Google Drive sync lists remote book folders, discovers TTU sidecars, uploads missing bookdata when Upload Books is enabled, imports remote-only books, trashes remote folders, clears cached Drive IDs/covers, and only preflights for an active network with internet capability before Drive REST requests.
- `73a9e62`: Android now mirrors the Dictionary pull-to-clear/show-keyboard slice with reader-style iframe search results, localized pull/release labels, active Dictionary tab refocus/select-all, and measured search-result placement below the search field.
- `a713c0c`: iOS keeps command-center previous/next cue controls wired even when skip controls are enabled. Android already keeps cue navigation available through reader chrome, Sasayaki sheet controls, and media-session previous/next commands.
- `09951b4`, `612d350`, `ad71067`, `4b26d8a`, `172577c`, `be42499`: iOS version/build bumps only.
- `51bd0f2`: iOS compiler setting and ZIPFoundation update. Android uses its own ZIP/Java/Kotlin stack; no direct action.
- `b84bb79`, `adcbc96`, `7b98ec7`: iPad-specific safe-area/layout adjustments. Keep as Android tablet validation context rather than direct sync unless a matching tablet issue appears.
- `f07d8ea`: continuous restore wait-for-viewport workaround was reverted by `9b3e135`; Android should not copy that approach.
- `5518193`: Android already has `readerAlwaysShowProgress` persistence, the Appearance toggle, suppression of normal top/bottom progress bubbles while enabled, and bottom safe-area progress rendering.
- `e70008d`: iOS hoshidicts package revision bump to `497578824f...`; Android's hoshidicts bridge submodule already points at the same native revision.
- `7d49301`, `cce1693`: upstream author confirmed the popup scale, selection-coordinate, and vertical-anchor changes are WebKit-bug-specific and should not be copied to Android.
- `3405d69`: iOS settings UI cleanup and documentation links. No direct Android sync beyond keeping future settings copy localized and Android-specific.
- `147e3b9`: Android already ships default English and Simplified Chinese resources with localization tests. Future queue items that add user-visible strings still need the normal paired `values` / `values-zh-rCN` updates.
- `61306c7`: formatting and whitespace cleanup only.
- `32aa342`: Android now sanitizes Calibre-like EPUB CSS rules in `ReaderResourceSanitizer`, with behavior coverage for writing mode, line height, height, positive text indentation, negative text indentation, non-Calibre rules, and appended default body line height.
- `2ffde40`: iOS changed NWPathMonitor gating to block only explicitly unsatisfied paths. Android Drive requests now only require an active network with `NET_CAPABILITY_INTERNET`; device-code auth still treats transient network failures as retryable.
- `691baa2`, `323449c`: Android already localizes the Reading shelf title through `BookshelfSectionModel.titleRes = R.string.bookshelf_section_reading`, `BookshelfSectionHeader`, and paired English/Simplified Chinese resources.
- `078d59f`: Android already overrides publisher column counts in paginated mode through `ReaderContentStyles` with `body * { column-count: auto !important; -webkit-column-count: auto !important; }`.
- `1fcf287`: iOS SwiftUI file-importer placement fix. Android backup restore uses dedicated `rememberLauncherForActivityResult(FileImportContent())` launchers for `.hoshi` and TTU `.zip` imports.
- `b1509d9`, `a7a8380`, `55a32cd`, `2b8a599`, `98b6534`: Android reader now matches this slice. `selection.js` keeps SVG containers outside image-hit blocking while preserving SVG `<image>` hits and emits per-character highlight ranges; `ReaderBottomSafeProgress` handles bottom safe-area taps for focus/popup dismissal; `ReaderGeneratedLayout` applies the vertical one-pixel image-width guard; paginated and continuous reader JS remove whitespace-only ruby text nodes and wrap ruby base text before lookup offsets are built.

## Open Commit Inventory

| Commit | Date | iOS summary | Android status |
| --- | --- | --- | --- |
| `94d0c41` | 2026-05-19 | Automatic dictionary updates | Pending |
| `8ef25f4` | 2026-05-24 | New Anki glossary brief/fallback handlebars | Pending |
| `36be339` | 2026-05-25 | IPA/transcription pitch dictionary display | Pending bridge/UI sync |
| `5cbdaa8` | 2026-05-29 | Glossary no-dictionary handlebars and regex stripping | Pending |
| `8ffca61` | 2026-06-02 | Autofill Lapis, Kiku, and Senren Anki field mappings | Pending |
| `0d6c072` | 2026-06-04 | hoshidicts normalization processor bump | Pending bridge/native sync |
| `cfc1e50` | 2026-06-04 | Build lookup query off main thread | Pending |

## Suggested Implementation Order

1. Dictionary automatic updates.
2. Dictionary lookup normalization and query rebuild threading.
3. Anki field templates, dictionary IPA display, and glossary handlebars.
