# Hoshi Android Roadmap

> Status values: `todo`, `in_progress`, `blocked`, `done`.
> After every implemented feature, update this file before committing so development can resume from the current state.

## Execution Rules

- iOS is the only source of truth for user-visible UI and interaction behavior.
- If Android behavior differs from iOS, inspect the iOS implementation first and remove the difference instead of adding isolated compatibility patches.
- Use `testdata/test.epub` and `testdata/test2.epub` for EPUB reader validation and `testdata/JMdict_english.zip` for Yomitan dictionary validation.
- Each implemented feature must be verified in an Android emulator before commit.
- Each implemented feature commit must include the matching status update in this file.
- If a feature is blocked, mark it as `blocked`, record the blocker, and continue with the next feasible item.

## Roadmap

1. `done` - Stabilize EPUB reading main flow
   - Save and restore reader position using the iOS `bookmark.json` shape.
   - Preserve iOS WebView paging semantics: page scroll first, chapter transition only at boundaries.
   - Keep reverse chapter transitions landing at the previous chapter end.
   - Verified on emulator with `testdata/test.epub`: saved `chapterIndex=9`, `progress=0.2734952481520591`, relaunched app, reopened reader, restored to `item/xhtml/p-003.xhtml` with `scrollTop=1668` and matching progress.

2. `in_progress` - Bookshelf and book metadata
   - `done` - Move from a single `current.epub` directory to multi-book storage.
   - `done` - Persist title, cover path, folder, and last access in iOS-shaped `metadata.json`.
   - `done` - Open imported/listed books from their independent storage directories.
   - `done` - Render cover thumbnails in the bookshelf.
   - `done` - Align basic bookshelf sorting with iOS `Recent` / `Title`.
   - `done` - Align single-book delete flow with iOS long-press context action plus confirmation.
   - `done` - Align the main Books/Dictionary/Settings shell with iOS: floating Books chrome, cover-grid bookshelf, grouped Settings list, and bottom capsule tab bar.
   - `done` - Align EPUB import deduplication with iOS title-based `Books/<safeTitle>` storage and calculate bookshelf progress from `bookmark.characterCount / bookinfo.characterCount`.
   - `todo` - Align multi-select, shelves, and batch actions with iOS.
   - Verified on emulator with `testdata/test.epub`: cleared app data, imported the EPUB twice through DocumentsUI, confirmed two shelf rows and two independent `files/Books/<uuid>/metadata.json` files, then opened a listed book into the reader.
   - Verified cover thumbnails on emulator with `testdata/test.epub`: imported through DocumentsUI, confirmed `metadata.cover` is `item/image/cover.jpg`, and visually checked the real cover image renders in the shelf row.
   - Verified sorting/deletion on emulator: opened sort menu, switched from `Recent` to `Title`, long-pressed a book row, used `Delete`, confirmed the dialog, and checked `files/Books` metadata count decreased.
   - Verified main shell UI on emulator with `testdata/test.epub`: imported through Android DocumentsUI from `testdata`, opened the reader, returned to Books, confirmed the `Unshelved` cover grid with two imported books, checked the Settings tab renders iOS-ordered grouped cards above the bottom capsule tab bar, confirmed the Books page no longer renders an artificial fixed blurred cover strip at the top, and confirmed custom toolbar/tab icons no longer carry fixed pink/blue/ripple color artifacts.
   - Verified import deduplication and full-book progress on emulator with `testdata/test.epub`: cleared app data, imported the same EPUB twice through Android DocumentsUI, confirmed `files/Books` contains only `屍人荘の殺人`, swiped to正文, confirmed `bookmark.json` saved `characterCount=90`, reader chrome showed `90 / 169326 0.05%`, and the bookshelf showed `0.1%`.
   - Verified bookshelf card layout on emulator with `testdata/test.epub`: imported through Android DocumentsUI, confirmed the progress bar and percentage render below the cover image instead of overlaying it, and confirmed the Books shell toolbar/tab icons are scaled down closer to the iOS proportions.

3. `in_progress` - Reader settings
   - `done` - Implement iOS-aligned reader Appearance sheet entry from the reader.
   - `done` - Apply theme, text orientation, font size, horizontal padding, vertical padding, and line height through WebView CSS without changing reader page-turn logic.
   - `done` - Persist reader Appearance settings across app restart.
   - `done` - Align reader chrome with iOS `ReaderView`: remove solid top/bottom bars, render title/progress as an overlay, use theme-aware floating circular controls, reserve transparent WebView space behind chrome, and set Android system bar icon contrast from the reader theme.
   - `todo` - Align font family selection/import with iOS `FontManager`.
   - Verified on emulator with `testdata/test.epub`: opened reader, opened Appearance from the reader, changed font size from 22 to 23 and theme to Dark, confirmed WebView computed CSS changed to `fontSize=23px`, black background, white text, and confirmed those settings persisted after force-stopping and reopening the app.
   - Verified on emulator with `testdata/test.epub`: opened an imported book, confirmed the WebView bounds are inset below the top overlay and above the bottom controls, confirmed no solid title bar or white bottom bar is present, confirmed status/navigation icons switch to light on dark reader theme, and confirmed the Appearance menu is anchored to the right floating control.

4. `done` - WebView selection bridge
   - `done` - Implement JS-side text selection, selected text extraction, range data, and popup anchor rectangles.
   - `done` - Keep native Android as the receiver of JS results instead of reimplementing DOM logic.
   - Verified on emulator with `testdata/test.epub`: imported through DocumentsUI, opened a vertical text chapter in WebView, confirmed `window.hoshiSelection` and `HoshiTextSelection` are injected, performed a real tap on正文 text, and observed JS selection data returning selected text `見です` with sentence `「そこまでは俺も同意見です。`.

5. `in_progress` - Dictionary lookup popup
   - `done` - Connect lookup to `third_party/hoshidicts-kotlin-bridge`.
   - `done` - Trigger lookup from WebView selection results.
   - `done` - Add iOS-aligned Dictionary tab lookup: submit a search query, use `LookupEngine`, and render results through the existing popup WebView HTML/JS/CSS pipeline.
   - `done` - Align Dictionary tab search chrome with iOS: floating glass-style search capsule, search icon, compact circular clear button, and WebView result spacer so entries do not render under the search field.
   - `done` - Align Dictionary tab popup interaction with iOS `PopupWebView`: intercept internal `hoshi-popup://` messages, inject iOS `selection.js`, and support nested lookup popups from selected definition text.
   - `done` - Share the iOS-style lookup popup stack between Dictionary and Reader, including child popup dismissal and popup-local selection coordinate conversion.
   - `done` - Align Reader popup coordinate space with iOS by rendering popup stack in the same padded content layer as the chapter WebView, so first lookup popups stay below the system status area.
   - `done` - Align Reader lookup selection highlighting with iOS by adding the same `::highlight(hoshi-selection)` style used by popup WebViews.
   - `done` - Add first-pass popup positioning using iOS `PopupLayout` geometry.
   - `done` - Close lookup popup from the same Reader-level tap-outside and page-turn paths as iOS, and add popup-level horizontal swipe dismissal for gestures that start on the popup itself.
   - `done` - Replace raw Compose glossary text with a popup WebView renderer that expands Yomitan structured-content JSON into HTML without adding search or other extra capabilities.
   - `done` - Copy iOS `popup.js` and `popup.css` into Android assets and render lookup entries through the same `entries-container` / `renderPopup()` pipeline and `lookupEntries` data shape as iOS.
   - `todo` - Wire remaining iOS `PopupWebView` behaviors: dictionary media scheme, audio controls, Anki mining, and user-configurable popup settings.
   - Verified on emulator with `testdata/test.epub` and imported `testdata/JMdict_english.zip`: opened a vertical text chapter, tapped `お冷や`, and confirmed a popup appears over the reader with `お冷や`, reading `おひや`, and JMdict glossary content.
   - Verified on emulator with `testdata/test.epub` and imported `testdata/JMdict_english.zip`: tapped `お冷や`, confirmed the popup reading `ひや` appears, then confirmed popup-level horizontal swipe, tap outside the popup, and Reader page swipe each dismiss the popup without leaving the reading node visible.
   - Verified on emulator with `testdata/test.epub` and imported `testdata/JMdict_english.zip`: tapped `お冷や`, inspected the popup WebView through Chrome DevTools Protocol, and confirmed it renders `冷や`, `ひや`, `cold water`, and `JMdict [2026-04-27]` as HTML while no longer showing raw `structured-content` JSON; also rechecked popup WebView horizontal swipe dismissal.
   - Verified on emulator with `testdata/test.epub` and imported `testdata/JMdict_english.zip`: inspected the popup WebView DOM and confirmed iOS-generated structure is present (`.entry-header`, `.expression ruby`, `details.glossary-group`, `.dict-label .dict-name`, `.glossary-content`, `.mine-button`) and raw `structured-content` JSON is absent; rechecked popup horizontal swipe dismissal after switching to the iOS JS/CSS pipeline.
   - Verified on emulator with `testdata/MK3.zip`: cleared app data, imported MK3 through Android DocumentsUI, opened Dictionary tab, searched `test`, and confirmed the result WebView renders `テスト [test]` with `明鏡国語辞典 第三版` glossary content.
   - Verified on emulator with imported `testdata/MK3.zip`: searched `test` again after the search chrome change and confirmed the first result starts below the search capsule while the clear affordance renders as a compact circular x.
   - Verified on emulator with imported `testdata/MK3.zip`: searched `test`, tapped definition text to open a nested `試験` popup, tapped inside that popup to open a second nested popup, and confirmed tap-outside no longer navigates to `hoshi-popup://tapOutside` or shows `Webpage not available`.
   - Verified on emulator with imported `testdata/MK3.zip` and `testdata/test.epub`: opened the reader, tapped vertical正文 `える` to create the root popup, tapped popup definition text `簡にして要を得る` to create a second popup, then tapped `要点` in that popup to create a third popup without the stack closing unexpectedly.
   - Verified on emulator with imported `testdata/MK3.zip` and `testdata/test.epub`: opened the reader, tapped vertical正文 `える`, confirmed the first popup WebView bounds are `[18,390][817,1140]` while the chapter WebView bounds are `[0,372][1280,2496]`, and confirmed a nested popup still opens from `簡にして要を得る`.
   - Verified on emulator with imported `testdata/MK3.zip` and `testdata/test.epub`: opened the reader, tapped vertical正文 `える`, confirmed the reader text behind the popup shows the same gray `hoshi-selection` highlight as iOS and popup nested lookups.

6. `in_progress` - Dictionary import and management
   - `done` - Build Android native `hoshidicts_jni` from `third_party/hoshidicts-kotlin-bridge` while linking to `third_party/hoshidicts-gplv3`.
   - `done` - Import `testdata/JMdict_english.zip` through the GPLv3 `hoshidicts` bridge.
   - `done` - Persist iOS-shaped `Dictionaries/config.json` and list imported term dictionaries.
   - `done` - Align term dictionary enable/disable and swipe-to-delete with iOS `DictionaryView` list behavior.
   - `todo` - Align type picker, reordering, and detailed import/update state with iOS `DictionaryView`.
   - Do not reimplement Yomitan import or dictionary media handling outside the bridge.
   - Verified on emulator with `testdata/JMdict_english.zip`: imported through DocumentsUI, confirmed dictionary list shows `JMdict [2026-04-27]`, confirmed private files `Dictionaries/Term/JMdict [2026-04-27]/index.json`, `blobs.bin`, `hash.table`, and `Dictionaries/config.json`, and temporarily verified native lookup query returned `猫` for lookup text `猫` before removing the debug UI.
   - Verified on emulator with `testdata/JMdict_english.zip`: toggled `JMdict [2026-04-27]` off and confirmed `Dictionaries/config.json` wrote `"isEnabled": false`, swiped the row from right to left and confirmed `Dictionaries/Term` was empty with an empty config, then reimported the zip through DocumentsUI and confirmed the row and enabled config were restored.
   - Verified on emulator with `testdata/MK3.zip`: imported through DocumentsUI, confirmed dictionary list shows `明鏡国語辞典 第三版`, and confirmed private files plus `Dictionaries/config.json` are written under app storage.

7. `todo` - Highlights and notes foundation
   - Store highlight anchors based on WebView range data.
   - Restore highlights after chapter load through JS.
   - Align highlight tap, delete, and color behavior with iOS.

8. `todo` - Anki integration
   - Investigate AnkiDroid APIs before implementation.
   - Build the smallest card creation flow from dictionary lookup results.
   - Do not copy iOS AnkiMobile x-callback behavior directly.

9. `todo` - Audio and pronunciation
   - Play dictionary audio with AndroidX Media3/ExoPlayer.
   - Read dictionary media through the existing dictionary bridge.
   - Align playback triggers and UI with iOS.

10. `todo` - Sync
    - Investigate Android Google Drive/OAuth integration.
    - Sync sidecar JSON, progress, settings, and dictionary configuration.
    - Do not reuse iOS token or keychain assumptions.

11. `todo` - Regression coverage and release hardening
    - Add EPUB fixtures for cover, images, vertical text, horizontal text, complex spine, and broken resources.
    - Expand WebView pagination regression checks.
    - Keep Gradle `test`, `assembleDebug`, and `lint` passing before release-facing changes.
