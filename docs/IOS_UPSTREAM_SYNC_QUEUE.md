# iOS Upstream Sync Queue

This document tracks open Android work after checking iOS upstream `develop`.

- Source: `reference/Hoshi-Reader-iOS`
- Baseline for this refresh: `9b3e135d18a49492bb6bffb9ce9cfaf2329c58c7`
- Latest checked: `origin/develop` at `61306c70570c911c288d217d5a111d45204b345b`
- Checked on: 2026-05-31

## Current Queue

### 1. Popup scale, selection coordinates, and vertical reader anchors

Status: pending Android sync.

Commits:

- `7d49301` - scale popup units directly instead of relying on CSS zoom.
- `cce1693` - align popups to selection in paginated/continuous vertical.

Why this comes first:

- Popup coordinates are shared by Dictionary tab lookup, reader lookup, recursive popup lookup, Anki buttons, and native action overlays. Android currently still uses WebView/CSS zoom plus coordinate scale helpers, so this is a small but foundational correctness slice before adding more popup/template behavior.

iOS behavior to mirror:

- Popup HTML no longer applies `html { zoom: ... }`. Instead, CSS lengths that need to scale use `calc(... * var(--popup-scale))`, and custom CSS `px` values are converted to scale-aware `calc()` expressions.
- Button frame reporting returns raw `getBoundingClientRect()` coordinates; native overlay buttons use the popup scale only for icon sizing.
- Recursive popup selection no longer passes separate zoom-adjusted rect points.
- In reader vertical writing, selection rectangles are offset by the WebView scroll origin so lookup popups align with the selected text in paginated and continuous modes.

Android current gap:

- `LookupPopupHtml`, `LookupPopupAndroidOverlay`, Dictionary tab popup rendering, and `hoshi-popup` assets still use `html { zoom: ... }`, `getButtonRectScale()`, and separate rect coordinates.
- Android has prior zoom-coordinate work from the earlier `79fef08` slice, but this upstream commit supersedes that implementation model.
- Android popup alignment needs a focused re-check in paginated and continuous vertical writing after removing zoom-based coordinate compensation.

Suggested slice:

- Move popup scaling to CSS variables and scale-aware lengths in shared popup assets and generated popup HTML.
- Convert custom dictionary CSS pixel lengths to scale-aware expressions before injection.
- Remove zoom-based button-frame and selection-rect compensation, then keep native overlay icons sized from the setting value.
- Re-run reader vertical selection anchor tests in paginated and continuous modes.

Validation:

- Popup-to-popup lookup from reader and Dictionary tab at popup scales `0.8`, `1.0`, and `1.5`.
- Reader vertical lookup in paginated and continuous modes, including lower-screen child popup placement and action-button alignment.
- E-ink popup controls, deinflection overlays, dictionary media images, and outside-tap dismissal after scaling changes.

### 2. Reader image hit testing, bottom safe-area taps, and selection highlight follow-up

Status: pending Android sync.

Commits:

- `b1509d9` - allow reader UI to be toggled on SVG image pages.
- `a7a8380` - allow bottom safe area to toggle focus mode and dismiss popups.
- `55a32cd` - prevent images with blur from being hidden with 0 horizontal padding in vertical.
- `2b8a599` - prevent random margins from being highlighted and not cleared.

Why this follows popup geometry:

- Most of these fixes touch hit testing, selection clearing, or reader chrome tap behavior. Popup coordinate cleanup should land first so reader tap/selection regressions are isolated.

iOS behavior to mirror:

- Taps on SVG image pages can still toggle reader UI; SVG `<image>` media taps remain image taps, but the SVG container itself is no longer treated as a blocked image hit target.
- The bottom safe area is tappable: it clears selection, closes popups if present, or toggles focus mode if no popup is open.
- Blurred images in vertical writing use a one-pixel width reduction so zero horizontal padding does not hide them.
- Text selection highlights each scanned character range separately, avoiding accidental margin highlight blocks that are not cleared.

Android current gap:

- Android already exposes an Always Show Progress setting, persists `readerAlwaysShowProgress`, suppresses the normal top/bottom progress bubbles, and renders progress in the bottom safe-area band.
- Android `ReaderSelectionScripts` still treats `svg` containers as image taps; upstream now treats only `img`, SVG inner `image`, and `.blur-wrapper` as image taps.
- Android bottom safe-area tap behavior and popup/selection dismissal order need a focused comparison against `a7a8380`.
- Android reader image sizing and selection highlight range generation need comparison against the one-pixel blurred-image width fix and per-character highlight fix.

Suggested slice:

- Audit Android reader chrome and reader JS against the four commits, then implement only confirmed mismatches.
- Keep Android-specific immersive navigation behavior, but align tappable safe-area semantics and popup/selection dismissal order.

Validation:

- SVG-only image pages, SVG containers with inner `<image>` media, blurred vertical image pages with zero horizontal padding, and blank margin taps.
- Focus-mode toggling from the bottom safe-area band with no popup, with a root popup, and with recursive child popups.

### 3. Dictionary search pull-to-clear reset

Status: pending Android sync.

Commit:

- `73a9e62` - pull to refresh.

Why this is independent:

- It is scoped to the Dictionary tab search/results surface and can be implemented without waiting for storage or sync work.

iOS behavior to mirror:

- Dictionary search results allow a downward pull gesture even inside the popup-backed results WebView.
- Pulling past an 80-point threshold clears the current query when text is present.
- Pulling with an empty query focuses the search field and shows the keyboard.
- While dragging, a small inset below the search bar shows pull/release guidance.
- Dragging results dismisses the keyboard.

Android current gap:

- Android has an explicit clear button in the search field but no pull-to-clear/show-keyboard gesture.

Suggested slice:

- Implement with Compose nested scroll or scroll state around the Dictionary results surface rather than iOS WebView bounce semantics.
- Add localized pull/release labels to English and Simplified Chinese resources.

Validation:

- Search for a term, pull below threshold, confirm query clears, results reset, and keyboard focus returns.
- With an empty query, pull below threshold and confirm keyboard appears.
- Confirm normal result scrolling and nested popup lookup still work.

### 4. Dictionary automatic updates

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

### 5. Dictionary IPA display and Anki glossary handlebars

Status: pending Android sync; native frequency-sort dependency already present.

Commits:

- `8ef25f4` - glossary-brief, glossary-first-brief, selected-glossary-fallback, selected-glossary-brief, selected-glossary-brief-fallback handlebars.
- `36be339` - support for IPA dicts.
- `5cbdaa8` - glossary-no-dictionary, use regex to create alt glossary handlebars.

Why this follows core popup rendering:

- IPA pitch display and Anki glossary payloads are generated from popup/lookup data. Land popup scale/coordinate cleanup first, then adjust payload semantics.

iOS behavior to mirror:

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

Suggested slice:

- Extend the bridge-facing pitch model only if `third_party/hoshidicts-kotlin-bridge` exposes transcription data; otherwise document the bridge gap first.
- Render IPA/transcription rows in popup pitch groups without breaking compact pitch and pitch deduplication behavior.
- Add Anki handlebar rendering and focused tests for brief, no-dictionary, fallback, and per-dictionary suffix forms.
- Keep insertion UI aligned with iOS by hiding advanced variants that iOS does not surface directly.

Validation:

- Import an IPA-capable pitch dictionary and confirm lookup popups show transcription rows.
- Mine Anki notes through AnkiDroid and AnkiConnect with each new handlebar variant, including dictionary media embedding and selected-dictionary fallback.

### 6. TTU/Google Drive book data sync, backup import/export, and remote bookshelf

Status: pending Android sync; replaces the earlier book-storage deferral with a concrete TTU bookdata queue.

Commits:

- `67bdbb9` - add option to export epubs.
- `1aaee97` - prevent autosync being stuck when mobile data is disabled.
- `c2e1c09` - ttu book sync (#63).
- `32d76d2` - some edge cases in ttu bookdata.

Why this is a large dependent slice:

- This crosses bookshelf state, Drive listing and cache invalidation, remote cover thumbnails, EPUB export, TTU `bookdata_*.zip` conversion, backup import/export, reader-open import-only behavior, and existing progress/statistics/Sasayaki sync.

iOS behavior to mirror:

- Book context menus can share/export the stored EPUB when `metadata.epub` is available.
- Sync settings include cache clearing, sign-out confirmation, and separate Data toggles for uploading book data, statistics, and audiobook progress.
- Bookshelf shows remote Google Drive books that are not present locally, using Drive cover thumbnails and remote progress where available.
- Tapping a remote book imports its TTU bookdata from Google Drive, then removes it from the remote-only section.
- Remote Google Drive books can be deleted/trash-moved from the remote-only section.
- Backup settings can export all local books as TTU-compatible bookdata zip folders and import TTU backup zips, merging new books and overwriting stats/progress for existing books.
- Sync uploads bookdata only when enabled and missing remotely; existing progress/statistics/audio sync remains independent.
- Drive requests detect no network before request execution so autosync does not hang behind disabled mobile data.
- TTU conversion preserves/sanitizes XHTML wrappers, images, cover, CSS, table of contents, progress, statistics, and edge cases around `<br>`, `<hr>`, and wrapper divs.

Android current gap:

- Android has the first Google Drive progress/statistics/Sasayaki sync slice and uses Device Code auth, but it does not list/import/delete remote-only Drive books.
- Android sync does not upload/download TTU bookdata zips or expose an Upload Books toggle.
- Android Backup supports `.hoshi` Books/Dictionaries archives but not TTU backup import/export.
- Android `BookMetadata`/repository still use extracted EPUB roots and do not retain a packed EPUB filename for direct EPUB export.
- Network unavailability is handled in parts of authorization/sync, but the exact autosync no-network stuck case needs re-checking against Android's Drive client.

Suggested slice:

- Split into smaller Android work:
  1. Drive data-source support for paginated folder listing, per-folder sync file listing, remote cover thumbnail caching, cache clear, and trash.
  2. Remote-only bookshelf section and import/delete UI with localized strings.
  3. EPUB retention/export strategy that is compatible with the deferred packed-EPUB storage decision.
  4. TTU bookdata converter and Backup import/export flows.
  5. Sync Upload Books setting and bookdata upload/import-only reader behavior.
  6. Network-unavailable autosync guard.
- Confirm Android Drive API, SAF, and background/network constraints against current Google/Jetpack docs before implementation.

Validation:

- On a user-configured Google Drive project, list remote-only ッツ books, show covers/progress, import one book, delete one remote book, and clear cached folder IDs/covers.
- Export an Android book as EPUB and as TTU backup; import the TTU backup into iOS/ッツ where possible.
- Import an iOS/ッツ TTU backup into Android; verify reader open, cover, progress, statistics, and Sasayaki progress.
- Disable network/mobile data during autosync and confirm it fails or defers without hanging.
- Regression-test existing manual sync, reader auto import/export, close/background export flush, statistics Merge/Replace, and Sasayaki last-position sync.

### 7. EPUB CSS sanitization for Calibre-generated books

Status: pending Android comparison.

Commit:

- `32aa342` - add sanitization for some css in calibre generated epubs.

Why this is reader/storage adjacent:

- Android already has an EPUB resource sanitizer for private `-epub-*` CSS declarations, but iOS now mutates Calibre CSS files before reader load to remove layout-breaking rules. The two behaviors overlap but are not equivalent.

iOS behavior to mirror:

- On reader load, every CSS file in the extracted EPUB content directory is scanned.
- Rules for Calibre-like selectors strip writing-mode properties and line-height.
- If a rule with writing-mode also has height, height is stripped.
- Positive `text-indent` is reset to `0`; negative text indentation is preserved.
- If any line-height was stripped, `body { line-height: 1.65; }` is appended.

Android current gap:

- Android `ReaderResourceSanitizer` removes EPUB-private declarations when serving CSS, but it does not target Calibre selector rules, line-height, height, or positive text-indent in the same way.

Suggested slice:

- Add behavior-level sanitizer tests using Calibre-like CSS fixtures.
- Implement non-destructive CSS sanitization in the reader resource bridge or import pipeline, keeping the parser/resource model unchanged.

Validation:

- Open Calibre-generated vertical and horizontal EPUB fixtures with problematic line-height, writing-mode, height, and text-indent rules.
- Confirm reader layout, line height, indentation, and CSS resource serving remain stable across chapter reloads.

## Covered Or No Android Action

- `a713c0c`: iOS keeps command-center previous/next cue controls wired even when skip controls are enabled. Android already keeps cue navigation available through reader chrome, Sasayaki sheet controls, and media-session previous/next commands.
- `09951b4`, `612d350`, `ad71067`, `4b26d8a`: iOS version/build bumps only.
- `51bd0f2`: iOS compiler setting and ZIPFoundation update. Android uses its own ZIP/Java/Kotlin stack; no direct action.
- `b84bb79`, `adcbc96`, `7b98ec7`: iPad-specific safe-area/layout adjustments. Keep as Android tablet validation context rather than direct sync unless a matching tablet issue appears.
- `f07d8ea`: continuous restore wait-for-viewport workaround was reverted by `9b3e135`; Android should not copy that approach.
- `5518193`: Android already has `readerAlwaysShowProgress` persistence, the Appearance toggle, suppression of normal top/bottom progress bubbles while enabled, and bottom safe-area progress rendering.
- `e70008d`: iOS hoshidicts package revision bump to `497578824f...`; Android's hoshidicts bridge submodule already points at the same native revision.
- `3405d69`: iOS settings UI cleanup and documentation links. No direct Android sync beyond keeping future settings copy localized and Android-specific.
- `147e3b9`: Android already ships default English and Simplified Chinese resources with localization tests. Future queue items that add user-visible strings still need the normal paired `values` / `values-zh-rCN` updates.
- `61306c7`: formatting and whitespace cleanup only.

## Open Commit Inventory

| Commit | Date | iOS summary | Android status |
| --- | --- | --- | --- |
| `73a9e62` | 2026-05-18 | Dictionary pull-to-clear/show-keyboard gesture | Pending |
| `94d0c41` | 2026-05-19 | Automatic dictionary updates | Pending |
| `7d49301` | 2026-05-24 | Popup scale via CSS units, not WebView zoom | Pending |
| `8ef25f4` | 2026-05-24 | New Anki glossary brief/fallback handlebars | Pending |
| `b1509d9` | 2026-05-25 | Reader UI toggles on SVG image pages | Pending |
| `a7a8380` | 2026-05-25 | Bottom safe area toggles focus/dismisses popups | Pending |
| `55a32cd` | 2026-05-25 | Blurred vertical images remain visible with zero horizontal padding | Pending |
| `2b8a599` | 2026-05-25 | Selection highlights scanned characters separately | Pending |
| `67bdbb9` | 2026-05-25 | Export stored EPUB from book menu | Pending |
| `36be339` | 2026-05-25 | IPA/transcription pitch dictionary display | Pending bridge/UI sync |
| `1aaee97` | 2026-05-27 | Autosync no-network guard | Pending |
| `cce1693` | 2026-05-27 | Vertical reader popup anchor correction | Pending |
| `c2e1c09` | 2026-05-28 | TTU book sync, remote Drive bookshelf, backup import/export | Pending |
| `5cbdaa8` | 2026-05-29 | Glossary no-dictionary handlebars and regex stripping | Pending |
| `32aa342` | 2026-05-29 | Calibre CSS sanitization | Pending |
| `32d76d2` | 2026-05-29 | TTU bookdata edge cases | Pending with TTU slice |

## Suggested Implementation Order

1. Popup scale and vertical anchor correctness.
2. Reader image hit testing, bottom safe-area taps, and selection highlight follow-up fixes.
3. Dictionary pull-to-clear.
4. Dictionary automatic updates.
5. Dictionary IPA display and Anki glossary handlebars.
6. TTU/Google Drive bookdata sync, EPUB export, and backup import/export in smaller sub-slices.
7. Calibre CSS sanitization.
