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
   - `todo` - Align multi-select, shelves, and batch actions with iOS.
   - Verified on emulator with `testdata/test.epub`: cleared app data, imported the EPUB twice through DocumentsUI, confirmed two shelf rows and two independent `files/Books/<uuid>/metadata.json` files, then opened a listed book into the reader.
   - Verified cover thumbnails on emulator with `testdata/test.epub`: imported through DocumentsUI, confirmed `metadata.cover` is `item/image/cover.jpg`, and visually checked the real cover image renders in the shelf row.
   - Verified sorting/deletion on emulator: opened sort menu, switched from `Recent` to `Title`, long-pressed a book row, used `Delete`, confirmed the dialog, and checked `files/Books` metadata count decreased.

3. `todo` - Reader settings
   - Implement iOS-aligned theme, font, font size, line spacing, and margin controls.
   - Apply settings through WebView CSS/JS without changing reader interaction logic.

4. `done` - WebView selection bridge
   - `done` - Implement JS-side text selection, selected text extraction, range data, and popup anchor rectangles.
   - `done` - Keep native Android as the receiver of JS results instead of reimplementing DOM logic.
   - Verified on emulator with `testdata/test.epub`: imported through DocumentsUI, opened a vertical text chapter in WebView, confirmed `window.hoshiSelection` and `HoshiTextSelection` are injected, performed a real tap on正文 text, and observed JS selection data returning selected text `見です` with sentence `「そこまでは俺も同意見です。`.

5. `todo` - Dictionary lookup popup
   - Connect lookup to `third_party/hoshidicts-kotlin-bridge`.
   - Trigger lookup from WebView selection results.
   - Align popup presentation, dismissal, and layering with iOS.

6. `todo` - Dictionary import and management
   - Import `testdata/JMdict_english.zip` through the GPLv3 `hoshidicts` bridge.
   - Implement dictionary list, enable/disable, delete, and import state.
   - Do not reimplement Yomitan import or dictionary media handling outside the bridge.

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
