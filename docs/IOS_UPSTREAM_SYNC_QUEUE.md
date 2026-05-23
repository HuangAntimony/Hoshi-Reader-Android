# iOS Upstream Sync Queue

This document was rewritten from scratch after checking every iOS upstream commit after `1e2aa8d11d5cd1687e11c6b8735a999e7a8ed16f`.

- Source: `reference/Hoshi-Reader-iOS`
- Baseline: `1e2aa8d11d5cd1687e11c6b8735a999e7a8ed16f`
- Latest checked: `origin/develop` at `9b3e135d18a49492bb6bffb9ce9cfaf2329c58c7`
- Checked on: 2026-05-23

## Current Queue

### 1. Reader pagination and continuous-layout correctness

Status: completed on Android in `codex/reader-pagination-layout-sync`.

Commits:

- `7057771` - apply padding in reading direction to viewport in continuous mode.
- `f07d8ea` - prevent restoring to unpadded position in continuous reading mode.
- `8705f86` - use page height for column width in vertical-rl.
- `9b3e135` - prevent continuous WebView crash by injecting viewport earlier, reverting the `f07d8ea` wait-for-viewport workaround.
- `b84bb79` - prevent layout shifts on 13 inch iPads.

Why this comes before reader UI polish:

- These changes affect scroll/page coordinates and restore targets. They should be stable before adding fullscreen image behavior, paragraph spacing, or reader chrome redesign work that also changes WebView dimensions.

iOS behavior to mirror:

- Continuous mode frames the scroll WebView inside the padded viewport: vertical writing applies horizontal padding to the viewport, horizontal writing applies vertical padding to the viewport.
- Continuous reader now injects a fixed-width viewport as a `WKUserScript` at document end, before the main setup script, to avoid WebView crashes and innerWidth/viewport desync during restore.
- The earlier restore/jump workaround that waited three animation frames was reverted upstream by `9b3e135`.
- Paginated vertical-rl uses page height for CSS column width, avoiding width/height mismatch in vertical writing.
- iPad-specific reader geometry avoids first-load safe-area layout shifts.

Android result:

- Continuous layout now splits padding by writing direction so the Compose viewport owns the padding along the reading axis, while body CSS keeps only cross-axis padding.
- Paginated vertical writing resolves CSS `column-width` from `--page-height`; horizontal writing keeps `--page-width`.
- Chapter XHTML/HTML resources get a single early viewport meta before reader setup runs, while chapter navigation keeps the existing `loadUrl` path instead of the slower `loadDataWithBaseURL` main-document load.
- The reverted three-animation-frame restore wait was not copied. `b84bb79` remains tablet validation context only.
- Covered by focused JVM tests plus emulator WebView inspection on `emulator-5554`: continuous vertical restore and paginated vertical both loaded text with one viewport meta and zero XHTML parser errors; paginated vertical reported `columnWidth` equal to `--page-height`.

Validation:

- Cover pages, multi-image pages, long text page turns, forward/backward chapter boundaries, continuous restore with nonzero padding in both writing modes, internal-link jumps, and rapid boundary flips.

### 2. Reader image tap, SVG media targeting, and fullscreen zoom

Status: completed on Android in `codex/reader-image-fullscreen-sync`.

Commits:

- `a0989ac` - fullscreen and zoomable images.
- `027ea05` - apply click event to inner image element for SVG containers.

Why this follows layout:

- Image sizing and click targets rely on the reader CSS/page metrics from the layout slice, especially for cover pages and vertical writing.

iOS behavior to mirror:

- Large block images and SVG containers with embedded image elements become tappable.
- If Blur Images is enabled, the first tap only removes blur and consumes the tap.
- A later tap opens a fullscreen image viewer with zoom, double-tap zoom, close, and share controls.
- SVG containers forward image handling through the inner `<image>` href so the fullscreen viewer opens the actual media URL.

Android result:

- Reader JS now uses one image setup path for large bitmap images and SVG containers. Bitmap images are wrapped with `.blur-wrapper` when Blur Images is enabled, while SVG click handling is attached to the inner `<image>` and forwards its resolved media URL.
- Reader selection distinguishes text, links, and image taps so native Android tap handling no longer opens lookup or clears popups for image/link taps.
- Tapped reader images open a fullscreen overlay. Raster images use native Compose zoom/pan/double-tap handling; SVG media renders in a contained zoomable WebView backed by the same EPUB resource bridge.
- The fullscreen viewer exposes close, copy, save, and Android share controls. Copy/share use temporary FileProvider-backed cache files, while save writes through MediaStore.
- Covered by focused JVM tests for reader image JS, selection result parsing, localized share strings, and local EPUB image resource targeting.

Validation:

- In paginated and continuous modes, verify cover image pages, multi-image illustration pages, SVG image containers, blurred images, inline/gaiji images, double-tap zoom, close/share controls if implemented, and no accidental reader chrome/page-turn/lookup on image taps.

### 3. Reader advanced paragraph spacing

Status: completed on Android.

Commits:

- `ebf5423` - paragraph spacing.

Why this is a small high-value slice:

- It is an independent user-facing setting with low architectural risk, but it touches strings, DataStore/legacy settings, WebView state keys, and generated reader CSS.

iOS behavior to mirror:

- Appearance -> Layout -> Advanced includes a `Paragraph Spacing` slider from `0...3` in `0.1em` steps, default `0`.
- In vertical writing, paragraph spacing applies to left/right paragraph margins.
- In horizontal writing, paragraph spacing applies to top/bottom paragraph margins.
- Changing the value rebuilds the reader WebView through the same state key path as line height and character spacing.

Android result:

- Reader settings now include paragraph spacing with default `0`, legacy SharedPreferences and DataStore persistence, and reader content reload-key participation.
- Appearance -> Layout -> Advanced now shows a localized Paragraph Spacing slider from `0...3` in `0.1em` steps.
- Generated reader CSS applies paragraph spacing to left/right paragraph margins in vertical writing and top/bottom margins in horizontal writing, only when Advanced layout is enabled.
- Covered by focused JVM tests for defaults, migration/persistence, reload-key changes, localized resources, and CSS output.

Validation:

- `./gradlew :app:testDebugUnitTest --tests moe.antimony.hoshi.features.reader.ReaderSettingsTest --tests moe.antimony.hoshi.features.reader.ReaderSettingsRepositoryTest --tests moe.antimony.hoshi.features.reader.ReaderWebViewStateHolderTest --tests moe.antimony.hoshi.LocalizationResourceTest`
- Device-validate visible spacing changes in both paginated/continuous and vertical/horizontal writing.

### 4. Reader chrome redesign and focus-mode interaction

Status: completed on Android in `codex/reader-chrome-focus-sync`.

Commits:

- `3c0de23` - redesign reader UI.
- `76409c9` - let ReaderView control status bar.
- `4438de8` - minor reader UI adjustments.
- `b84bb79` - prevent layout shifts on 13 inch iPads.

Why this is lower than layout and image work:

- This is high user-visible value, but Android already has substantial native reader chrome. Syncing it before the geometry fixes risks conflating visual redesign with page-position regressions.

iOS behavior to assess:

- Reader top title/progress moved into compact glass-styled overlays that hide in focus mode.
- Bottom Back/Menu controls and optional bottom progress/statistics moved into compact glass-styled controls.
- Statistics/Sasayaki quick toggles and jump-return controls are shown from the top safe area when focus mode is active.
- Taps outside popups toggle focus mode; selection, scroll, and page turns force focus mode on.
- ReaderView owns status-bar behavior instead of fullscreen image view special-casing.

Android result:

- ReaderView now owns Android system-bar behavior for the whole reader route. The top status bar remains visible outside focus mode, focus mode hides the status bar, the bottom navigation bar stays hidden with transient edge-swipe reveal, and reader exit restores the normal bars.
- Focus mode now follows the iOS interaction flow: text selection, paginated page turns, and continuous scrolling force focus mode on; empty reader taps toggle focus mode; taps while popups are visible dismiss popups before any focus toggle.
- Top title/progress and bottom Back/Menu/progress/statistics chrome float above reader text in glass-like center bubbles and hide in focus mode, while statistics, Sasayaki, and jump-return quick controls stay available as top overlays in focus mode.
- Android-specific reader affordances remain: E-ink opaque chrome colors, compact Android hit targets, and bottom Sasayaki skip buttons. Reader content keeps only the iOS-style top camera/control safety space; bottom chrome and transient navigation bars may overlay text to maximize the reading area.

Validation:

- Reader appearance chrome regression matrix from `docs/TODO.md`, plus tablet width, E-ink mode, focus mode status-bar hiding, jump-return controls, statistics/Sasayaki toggles, and popup coexistence.

### 5. Popup recursive selection scan length and zoom coordinates

Status: pending Android audit; may be partially covered.

Commits:

- `79fef08` - correct recursive selection coordinates on zoomed popups and pass scanLength instead of hardcoding.

Why this is independent:

- It is scoped to popup lookup recursion and dictionary scan length, not reader layout or storage. It can be fixed before or after reader UI slices.

iOS behavior to mirror:

- Popup WebViews receive the configured `scanLength` and pass it to `window.hoshiSelection.selectText()` instead of hardcoding `16`.
- On older iOS WebKit versions, recursive popup selection uses the touch point for hit testing and the popup-relative rect point for selection-rect calculation, fixing coordinate drift under popup zoom.

Android current gap:

- Android dictionary lookup and popup redirects already use `DictionarySettings.scanLength` for lookup results.
- Android needs an explicit check that popup-in-popup text selection inside scaled popup WebViews uses the configured scan length and reports child popup anchors correctly at popup scale `0.8`, `1.0`, and `1.5`.

Suggested slice:

- Audit `LookupPopupHtml`, `PopupWebViewMessages`, `LookupPopupAndroidOverlay`, and shared `selection.js` to confirm scan length is passed into recursive popup selection, not just lookup.
- Add a behavior/API test where possible; otherwise device-validate with scaled popups and long selectable text.

Validation:

- Popup-to-popup lookup from reader and Dictionary tab at popup scales `0.8`, `1.0`, and `1.5`, including lower-screen child popup placement and scan length values above/below `16`.

### 6. Dictionary pull-to-clear search reset

Status: pending Android sync.

Commit:

- `73a9e62` - pull to refresh.

iOS behavior to mirror:

- Dictionary search results now allow vertical bounce even inside the popup-backed results WebView.
- Pulling down past an 80-point threshold clears the current query when text is present.
- Pulling down with an empty query focuses the search field and shows the keyboard.
- While dragging, a small inset appears below the search bar with an arrow and text that changes from pull guidance to release guidance after the threshold.
- Releasing after the threshold clears text and focuses the search field; the reset indicator hides after deceleration.
- Dragging the results dismisses the keyboard.

Android notes:

- Android Dictionary tab currently has an explicit clear button in the search field but no pull-to-clear/show-keyboard gesture.
- Implement with Compose scroll state or nested scroll around the Dictionary results surface rather than adding iOS-specific WebView bounce semantics.
- New visible labels must be added to default English and Simplified Chinese string resources.

Validation:

- Search for a term in the Dictionary tab, pull the results down below threshold, and confirm the query clears, results reset, and keyboard focus returns.
- With an empty query, pull down below threshold and confirm the keyboard appears without changing results.
- Confirm ordinary vertical result scrolling and nested lookup popup interaction still work.

### 7. Dictionary automatic updates

Status: pending Android sync.

Commit:

- `94d0c41` - dictionary auto updates.

iOS behavior to mirror:

- Dictionary settings show an Updates section when at least one installed dictionary is updatable.
- Updates include an `Update Automatically` toggle, default enabled.
- When automatic updates are enabled, users can choose Daily, Weekly, or Monthly; default is Weekly.
- Settings show the last successful dictionary update time, or Never when none has succeeded.
- On app activation, iOS checks whether automatic updates are enabled, whether updatable dictionaries exist, and whether the selected interval has elapsed.
- Automatic update sessions disallow expensive and constrained network access.
- Failed dictionaries do not cancel the whole update batch.
- Last update is recorded after at least one dictionary update check/import succeeds.
- Manual update still reports failures to the user.
- Updated dictionaries are imported into a temporary directory first, then moved into place so failed imports do not corrupt the installed copy.

Android notes:

- Android already supports manual update checks for installed updatable dictionaries, preserving enabled state, order, and collapsed-title migration on rename.
- Android currently lacks automatic update settings, last-update display, activation-triggered background checks, non-expensive network restriction, partial-failure behavior, and temp-then-move update import.
- Use Android platform/network APIs for network constraints rather than mechanically copying iOS `URLSessionConfiguration`.
- New visible settings labels and interval values must be localized in English and Simplified Chinese.

Validation:

- Install an updatable dictionary, open Dictionaries, and confirm the Updates section shows automatic update controls, interval choices, last update, and manual Update.
- With automatic updates enabled and an elapsed interval, foreground the app on an unmetered network and confirm updates run without blocking normal dictionary use.
- Simulate one dictionary update failure and one success; confirm the success is applied, failure is surfaced only for manual update, and last update advances only after a successful check/import.
- Confirm a failed import leaves the previous installed dictionary intact.

## Deferred Until iOS Book Sync Stabilizes

### Book storage EPUB-file compatibility

Status: deferred until upstream iOS finishes book data sync with ッツ/TTU.

Commits:

- `ab6722e` - store books as epubs instead of unzipped folders.

Why this is deferred:

- This is the only new upstream change that can affect the filesystem contract shared by bookshelf import, reader open, backup/restore, sync, Sasayaki matching, cover export, and Anki book-cover mining.
- The iOS author is still working toward ッツ/TTU book data sync, and that work may change the book folder/metadata shape again.
- Android should avoid migrating its internal storage format until the upstream iOS book data sync contract settles. For now, keep Android's extracted-root model and only preserve enough knowledge to restore/read future iOS archives safely when implementation resumes.

iOS behavior to mirror:

- On launch, iOS repacks each legacy extracted book folder into `<folder>/<folder>.epub`, records that filename in `metadata.json` as `epub`, and removes extracted EPUB content except sidecars and the cover image.
- New reader and Sasayaki loads resolve the EPUB filename from metadata and unzip it to an app `Temp` directory before parsing.
- Backup/export now preserves a book folder containing sidecars, cover, and the packed EPUB file instead of the full extracted EPUB tree.

Android current gap:

- Android `BookRepository.importBook()` still extracts the selected EPUB into a book root, and `EpubBookParser.parse(root)` expects an extracted EPUB directory.
- Android `BookMetadata` has no `epub` field; current iOS-compatible backup tests cover sidecars, IDs, shelves, and covers, not the new packed-EPUB folder shape.
- Android backup/restore already zips `Books` safely with `java.util.zip`, so this is not a ZIP-library migration. The key decision is whether Android should adopt packed EPUB storage or support both shapes at restore/open time.

Suggested slice:

- Do not start Android's storage migration yet.
- Add metadata support for optional `epub`, restore/open compatibility for iOS packed-EPUB folders, and a migration strategy only after deciding whether Android keeps extracted roots or stores packed EPUBs.
- If Android keeps extracted roots, import iOS backups by detecting `metadata.epub`, extracting that EPUB into the restored book root, and preserving sidecars/covers.
- If Android adopts packed EPUB storage, update import, parser entry points, reader resource loading, cover resolution, Sasayaki matching, backup/restore, and sync together.

Validation:

- Unit-test both legacy extracted roots and iOS packed-EPUB restored roots.
- Device-validate import, reopen reader, cover display, backup/restore, Sasayaki matching, Anki book-cover export, and iOS-created `.hoshi` archive restore.

## Covered Or No Android Action

- `a713c0c`: iOS keeps command-center previous/next cue controls wired even when skip controls are enabled, while the visible skip controls replace them as media controls. Android already keeps cue navigation available through reader chrome, Sasayaki sheet controls, and media-session previous/next commands while reader skip buttons are controlled separately. Re-test media controls when touching Sasayaki command behavior.
- `09951b4`: iOS build-number bump only. No Android sync.
- `51bd0f2`: iOS project compiler setting bump to C++23 and ZIPFoundation package update. Android already uses platform `java.util.zip`; no Android dependency migration is implied unless the shared dictionary bridge later changes toolchain requirements.
- `612d350`: iOS build-number bump only. No Android sync.
- `b84bb79`: iPad-specific safe-area/layout-shift mitigation. Keep as context for Android tablet validation, not a direct Android sync item unless a matching tablet issue appears.
- `f07d8ea`: continuous restore wait-for-viewport workaround was reverted by `9b3e135`; keep only as context for why Android should not copy the wait-for-frames approach.

## Open Commit Inventory

| Commit | Date | iOS summary | Android status |
| --- | --- | --- | --- |
| `73a9e62` | 2026-05-18 | Dictionary pull-to-clear/show-keyboard gesture | Pending |
| `94d0c41` | 2026-05-19 | Automatic dictionary updates | Pending |
| `a0989ac` | 2026-05-21 | Fullscreen zoomable reader images | Pending image UX sync |
| `ebf5423` | 2026-05-21 | Reader paragraph spacing setting | Pending setting/CSS sync |
| `76409c9` | 2026-05-22 | Reader controls status bar ownership | Pending chrome comparison |
| `3c0de23` | 2026-05-22 | Reader UI redesign | Pending chrome comparison |
| `7057771` | 2026-05-22 | Continuous reader padding in reading direction | Synced in Android slice 1 |
| `79fef08` | 2026-05-22 | Zoomed popup recursive selection coordinate fix | Pending popup audit |
| `027ea05` | 2026-05-22 | SVG container image click targeting | Pending image UX sync |
| `4438de8` | 2026-05-22 | Reader UI minor adjustments | Pending chrome comparison |
| `8705f86` | 2026-05-23 | Vertical-rl column width uses page height | Synced in Android slice 1 |
| `9b3e135` | 2026-05-23 | Continuous WebView early viewport injection crash fix | Synced in Android slice 1 |

## Suggested Implementation Order

1. Reader pagination and continuous-layout correctness: stabilize WebView geometry, restore timing, and vertical-rl column sizing before UI work.
2. Popup recursive selection scan length and zoom coordinates: small cross-surface correctness slice with focused popup validation.
3. Reader image tap, SVG media targeting, and fullscreen zoom: depends on stable image/page metrics and shares blur/image event handling.
4. Reader advanced paragraph spacing: independent setting/CSS slice with localization and persistence tests.
5. Reader chrome redesign and focus-mode interaction: compare Android-specific chrome first, then sync concrete visible mismatches.
6. Dictionary pull-to-clear: user-facing gesture/UI slice in the Dictionary tab.
7. Dictionary automatic updates: broader settings, repository, networking, and lifecycle slice; implement after deciding Android's network constraint mechanism.
8. After upstream iOS/TTU book data sync stabilizes, revisit Book storage EPUB-file compatibility and decide packed EPUB vs dual-shape restore/open support with backup and reader-open tests.
