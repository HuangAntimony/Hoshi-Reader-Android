# Hoshi Android Roadmap

> Status values: `todo`, `in_progress`, `blocked`, `done`.
> After every implemented feature, update this file before committing so development can resume from the current state.

## Execution Rules

- iOS is the only source of truth for user-visible UI and interaction behavior.
- If Android behavior differs from iOS, inspect the iOS implementation first and remove the difference instead of adding isolated compatibility patches.
- Use `testdata/test.epub` and `testdata/test2.epub` for EPUB reader validation and `testdata/JMdict_english.zip` for Yomitan dictionary validation.
- Use `testdata/freq.zip` and `testdata/pitch.zip` when validating frequency and pitch dictionary support.
- Use `testdata/KleeOne-SemiBold.ttf` when validating reader font import and WebView font rendering.
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
   - Verified Books toolbar scaling on emulator without clearing app data: installed the resize build on `emulator-5554`, opened Books, confirmed the sort/check/folder/add toolbar controls render smaller while preserving the existing Books interactions, and moved the shelf content upward so the books sit closer to the resized toolbar.

3. `in_progress` - Reader settings
   - `done` - Implement iOS-aligned reader Appearance sheet entry from the reader.
   - `done` - Apply theme, text orientation, font size, horizontal padding, vertical padding, and line height through WebView CSS without changing reader page-turn logic.
   - `done` - Persist reader Appearance settings across app restart.
   - `done` - Align reader chrome with iOS `ReaderView`: remove solid top/bottom bars, render title/progress as an overlay, use theme-aware floating circular controls, reserve transparent WebView space behind chrome, and set Android system bar icon contrast from the reader theme.
   - `done` - Align reader restore display timing with iOS: keep the WebView transparent while saved progress is restored, then fade it in after JS reports restore completion.
   - `done` - Align font family selection/import/delete with iOS `FontManager`: store imported fonts under `Fonts/`, use file basenames as font names, persist `selectedFont`, and inject selected imported fonts into the reader WebView through `@font-face`.
   - `done` - Add the iOS-aligned Chapters sheet from the reader menu, flatten EPUB TOC entries, show current full-book progress, and jump selected chapters to their matching spine item.
   - `done` - Reuse one iOS-aligned Appearance settings implementation from both Settings -> Appearance and the reader menu, backed by the same `ReaderSettingsStore`; add implemented iOS reader toggles for furigana, layout advanced options, display progress/title controls, and popup sizing/swipe controls.
   - `done` - Fix Appearance theme controls and dark-mode readability: match iOS native segmented picker behavior by giving multi-option pickers their own full-width row, and apply reader-theme-aware content colors to the shared Appearance page/sheet.
   - `done` - Align Appearance theme semantics with iOS at the app root: Dark applies the Android Material color scheme app-wide, while Sepia keeps the app UI light and only changes reader rendering colors.
   - `done` - Replace hand-drawn Compose icon glyphs in Books, Settings, Dictionary, and search chrome with Material Icons.
   - `done` - Align popup sizing, swipe dismissal, and dark rendering with iOS `PopupView` / `PopupWebView`: root reader popups honor Full-width, Swipe to Dismiss injects a zero threshold when disabled, popup touch handling stays inside the popup WebView, and Android WebView receives explicit iOS-equivalent light/dark popup color variables.
   - Verified on emulator with `testdata/test.epub`: opened reader, opened Appearance from the reader, changed font size from 22 to 23 and theme to Dark, confirmed WebView computed CSS changed to `fontSize=23px`, black background, white text, and confirmed those settings persisted after force-stopping and reopening the app.
   - Verified on emulator with `testdata/test.epub`: opened an imported book, confirmed the WebView bounds are inset below the top overlay and above the bottom controls, confirmed no solid title bar or white bottom bar is present, confirmed status/navigation icons switch to light on dark reader theme, and confirmed the Appearance menu is anchored to the right floating control.
   - Verified on emulator with `testdata/test.epub`: force-stopped the app from a saved reader position, opened the book from the shelf, captured 16 rapid screenshots during launch, and confirmed the sequence goes from shelf to a blank reader background/chrome to the saved page without ever showing the chapter beginning.
   - Verified on emulator with `testdata/test.epub` and `testdata/KleeOne-SemiBold.ttf`: imported the font through Android DocumentsUI from the reader Appearance sheet, confirmed `files/Fonts/KleeOne-SemiBold.ttf` exists, selected `KleeOne-SemiBold`, confirmed `reader-settings.xml` stores `selectedFont`, and used WebView DevTools to confirm `font-family: KleeOne-SemiBold, serif`, `@font-face`, `https://hoshi.local/fonts/KleeOne-SemiBold.ttf`, and `document.fonts` loaded status.
   - Verified on emulator without clearing app data: opened an existing EPUB, confirmed the reader bottom controls use Material icons inside theme-aware floating circular buttons, confirmed the reader menu renders as an iOS-style rounded floating card, opened Appearance, and confirmed it uses scrollable Material 3 grouped sections, segmented controls, and icon steppers.
   - Verified on emulator without clearing app data: opened existing `testdata/test2.epub`, opened Reader Menu -> Chapters, confirmed the iOS-style TOC sheet renders cover/header/full-book progress and TOC rows, tapped `六月` and confirmed chrome `24932 / 248250 10.04%` with `bookmark.json` `chapterIndex=11`, then reinstalled the refactor build and tapped `contents`, confirming chrome `591 / 248250 0.24%` with `bookmark.json` `chapterIndex=5`.
   - Verified on emulator without clearing app data: opened Settings -> Appearance and confirmed the full Material 3 page replaces the placeholder; toggled `Hide Furigana` and confirmed `shared_prefs/reader-settings.xml` wrote `readerHideFurigana=true`; opened the same Appearance implementation from the reader menu and confirmed the same toggle is checked; changed `Progress Position` to Bottom and confirmed reader chrome moved progress from the top overlay to the bottom overlay while the same `readerShowProgressTop=false` preference was saved.
   - Verified on emulator without clearing app data: opened Settings -> Appearance and Reader Menu -> Appearance, selected Dark, confirmed the Theme segmented control no longer overlaps or wraps `System`, and confirmed all Appearance labels, section titles, segmented controls, steppers, and sheet text remain readable in dark mode.
   - Verified on emulator without clearing app data: with Sepia selected, confirmed the Books UI remains light while the reader uses the sepia page color; then selected Dark in Settings -> Appearance and confirmed Books, Settings, Dictionary tab, and Dictionaries management all switch to readable dark Material colors with Material icon rendering.
   - Verified on emulator without clearing app data: opened Settings -> Appearance in Dark mode, confirmed `Progress Position` shows the full `Bottom` label, confirmed `Full-width` is enabled and `Swipe to Dismiss` is disabled, opened an existing EPUB, selected vertical text to show a full-width bottom popup, confirmed popup dictionary text is readable in dark mode, then performed a horizontal swipe over the popup and confirmed it stayed open without changing reader page.
   - Verified on emulator without clearing app data after the popup focus regression: opened an existing EPUB, selected text to show a full-width bottom popup, confirmed popup content still scrolls, confirmed horizontal swipe over the popup does not close it or page the reader when `Swipe to Dismiss` is disabled, and confirmed the reader menu button remains tappable while the popup is visible.
   - Verified on emulator without clearing app data after redoing the Appearance progress fix: with `layoutVerticalPadding=33` and `layoutHorizontalPadding=24`, opened an existing EPUB, confirmed the reader shows the expected large vertical and horizontal page padding, performed three page swipes without the WebView reloading or stalling, changed the reader theme from Dark to Light and back to Dark, and confirmed the chrome stayed at `1027 / 248250 0.41%` instead of jumping to the chapter start.
   - Verified reader bottom control scaling on emulator without clearing app data: opened an existing EPUB, confirmed the bottom Back/Menu floating controls render smaller with reduced bottom safe-area spacing, and tapped the resized Menu control to open the existing Chapters/Appearance reader menu using the user-requested `58.dp` menu bottom offset.
   - Verified popup background stabilization on emulator without clearing app data: opened Dictionary and searched `たべる`, confirmed the popup/result WebView uses a pure white light background after fixing HTML body, WebView, and Compose popup `Surface` backgrounds to explicit white/black instead of transparent/dynamic Material colors. Dark-mode real-device verification remains user-side because the mismatch only reproduced on physical hardware.

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
   - `done` - Align iOS dictionary popup settings plumbing: `maxResults`, `scanLength`, dictionary collapse, compact glossaries, expression tags, harmonic frequency, pitch deduplication, and custom CSS now feed the shared WebView popup renderer.
   - `done` - Add first-pass popup positioning using iOS `PopupLayout` geometry.
   - `done` - Close lookup popup from the same Reader-level tap-outside and page-turn paths as iOS, and add popup-level horizontal swipe dismissal for gestures that start on the popup itself.
   - `done` - Replace raw Compose glossary text with a popup WebView renderer that expands Yomitan structured-content JSON into HTML without adding search or other extra capabilities.
   - `done` - Copy iOS `popup.js` and `popup.css` into Android assets and render lookup entries through the same `entries-container` / `renderPopup()` pipeline and `lookupEntries` data shape as iOS.
   - `todo` - Wire remaining iOS `PopupWebView` behaviors: dictionary media scheme, audio controls, and Anki mining.
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
   - Verified on emulator without clearing app data: in Dark reader theme, selected vertical正文 `て`, confirmed the popup renders JMdict glossary body text in readable light color while Jiten/Meikyo-style dictionary content remains readable.
   - Verified on emulator without clearing app data after the popup WebView touch fix: searched `たべる` in Dictionary, confirmed the result WebView scrolls through a long JMdict/Jiten result list with repeated long vertical swipes and crash log remains empty; the popup-specific fix removes Android native touch interception so popup vertical scrolling is handled by WebView like iOS `WKWebView`, while swipe dismissal remains in the shared iOS-style popup JavaScript.
   - Verified on emulator without clearing app data after aligning Dictionary WebView reload behavior with iOS: searched `たべる`, scrolled the expanded Japanese dictionary result, triggered nested lookup for `田`, and confirmed the underlying result WebView kept its scroll position and JS marker, the child popup stayed open, and no original-entry audio playback was posted.
   - Verified on emulator without clearing app data after aligning Dictionary popup settings with iOS `UserConfig`: with Appearance `Swipe to Dismiss` enabled and threshold `35`, searched `たべる`, opened a nested `田` popup from the Dictionary tab, confirmed the popup WebView had `window.swipeThreshold=35`, and confirmed a horizontal swipe inside that Dictionary popup dismissed it.

6. `in_progress` - Dictionary import and management
   - `done` - Build Android native `hoshidicts_jni` from `third_party/hoshidicts-kotlin-bridge` while linking to `third_party/hoshidicts-gplv3`.
   - `done` - Import `testdata/JMdict_english.zip` through the GPLv3 `hoshidicts` bridge.
   - `done` - Persist iOS-shaped `Dictionaries/config.json` and list imported term dictionaries.
   - `done` - Align term dictionary enable/disable and swipe-to-delete with iOS `DictionaryView` list behavior.
   - `done` - Add iOS-aligned Term/Frequency/Pitch type picker and import-type menu, and route import/list/toggle/delete through the selected dictionary type.
   - `done` - Align iOS dictionary settings: default Dictionary tab, Lookup steppers, Behaviour toggles, custom CSS editor entry, and shared persistence matching `UserConfig` defaults.
   - `done` - Persist dictionary display order in `Dictionaries/config.json` and use Android long-press drag reordering to match iOS `.onMove`, so lookup order and management order stay aligned.
   - `todo` - Align download recommended dictionaries and update dictionaries state with iOS `DictionaryView`.
   - Do not reimplement Yomitan import or dictionary media handling outside the bridge.
   - Verified on emulator with `testdata/JMdict_english.zip`: imported through DocumentsUI, confirmed dictionary list shows `JMdict [2026-04-27]`, confirmed private files `Dictionaries/Term/JMdict [2026-04-27]/index.json`, `blobs.bin`, `hash.table`, and `Dictionaries/config.json`, and temporarily verified native lookup query returned `猫` for lookup text `猫` before removing the debug UI.
   - Verified on emulator with `testdata/JMdict_english.zip`: toggled `JMdict [2026-04-27]` off and confirmed `Dictionaries/config.json` wrote `"isEnabled": false`, swiped the row from right to left and confirmed `Dictionaries/Term` was empty with an empty config, then reimported the zip through DocumentsUI and confirmed the row and enabled config were restored.
   - Verified on emulator with `testdata/MK3.zip`: imported through DocumentsUI, confirmed dictionary list shows `明鏡国語辞典 第三版`, and confirmed private files plus `Dictionaries/config.json` are written under app storage.
   - Verified on emulator with existing `testdata/MK3.zip`, imported `testdata/freq.zip` as Frequency and `testdata/pitch.zip` as Pitch through the iOS-style import menu, confirmed the type picker lists `Jiten` under Frequency and `アクセント辞典` under Pitch, then ran an app-process instrumentation lookup for `食べる` and confirmed native results include both frequency and pitch metadata.
   - Verified on emulator without clearing app data: opened Settings -> Dictionaries, confirmed Default to Dictionary Tab, Settings, Term/Frequency/Pitch, custom CSS entry, and two Term dictionaries render; opened dictionary Settings and confirmed iOS Lookup/Behaviour defaults, changed `maxResults` and `collapseDictionaries`, confirmed `shared_prefs/dictionary-settings.xml`, then reset them.
   - Verified dictionary ordering on emulator with existing `明鏡国語辞典 第三版` and `JMdict [2026-04-27]`: long-pressed and dragged the first Term dictionary down with `adb input draganddrop`, confirmed `Dictionaries/config.json` rewrote the order, then dragged it back and confirmed the original order was restored.
   - Verified dictionary lookup on emulator with existing Term/Frequency/Pitch dictionaries and query `test`: Dictionary tab rendered the MK3 result through the WebView popup renderer while the app process stayed alive and crash log remained empty. During this verification, stale dictionary mmap offsets exposed a native crash path; fixed `hoshidicts` query rebuilding/lifetime and added mmap/hash bounds validation so invalid imported dictionaries are skipped instead of crashing lookup.
   - Verified dictionary management UI on emulator without clearing app data: opened Settings -> Dictionaries and dictionary Settings, confirmed the pages now use Material 3 app bars, native icon buttons, grouped list cards, neutral segmented controls, green switches, swipe-delete rows, and compact icon steppers while preserving the iOS page structure.
   - Verified dictionary row affordance on emulator without clearing app data: removed the misleading always-visible reorder handle, kept whole-row long-press drag ordering, and confirmed `Dictionaries/config.json` order still changes and can be restored.

7. `todo` - Highlights and notes foundation
   - Store highlight anchors based on WebView range data.
   - Restore highlights after chapter load through JS.
   - Align highlight tap, delete, and color behavior with iOS.

8. `todo` - Anki integration
   - Investigate AnkiDroid APIs before implementation.
   - Build the smallest card creation flow from dictionary lookup results.
   - Do not copy iOS AnkiMobile x-callback behavior directly.

9. `in_progress` - Audio and pronunciation
   - `done` - Align iOS `AudioView` under Settings -> Advanced with source ordering/toggles, custom source add/delete, auto-play, background audio mode, and Local Audio import/delete controls.
   - `done` - Inject iOS popup audio variables into the shared popup WebView renderer and route audio button taps through the same native playback bridge used by iOS `PopupWebView`.
   - `done` - Support iOS-compatible local `Audio/android.db` lookup semantics for `http://localhost:8765/localaudio/get/?term={term}&reading={reading}` and play returned local audio blobs.
   - `done` - Support configurable remote Yomitan audio source URLs, verified with `https://audiov2.animecards.site/audio/list?term={term}&reading={reading}&apiKey=04887404-aba9-43b8-abb8-0e3847038a76`.
   - `todo` - Read dictionary media through the existing dictionary bridge.
   - `todo` - Align Sasayaki/audiobook playback with iOS when that feature slice starts.
   - Verified on emulator without clearing app data: opened Settings -> Advanced -> Audio and confirmed Local, AnimeCards, and Default audio sources render with iOS-aligned controls; because the full `testdata/android.db` is 5.8GB and emulator `/data` had only 3.3GB free, extracted a schema-preserving `食べる/たべる` subset from the real `testdata/android.db` into app-private `files/Audio/android.db`, searched `たべる` in Dictionary, tapped the popup audio button, and confirmed `MediaPlayer` decoded local `audio/mpeg` without app crash.
   - Verified remote audio on emulator without clearing app data: temporarily disabled Local Audio, kept the AnimeCards source enabled, searched `たべる`, tapped the popup audio button, and confirmed Android `MediaHTTPService` fetched the remote audio URL and `MediaPlayer` decoded `audio/mpeg` without app crash; restored Local + AnimeCards audio settings afterward.
   - Verified popup audio button polish on emulator without clearing app data: searched `たべる` in Dictionary, confirmed the audio and Anki buttons share the same header-row alignment, then held the audio button and confirmed the iOS-style circular filled pressed state is visible before playback.

10. `todo` - Sync
    - Investigate Android Google Drive/OAuth integration.
    - Sync sidecar JSON, progress, settings, and dictionary configuration.
    - Do not reuse iOS token or keychain assumptions.

11. `todo` - Regression coverage and release hardening
    - `done` - Add GitHub Actions signed APK release workflow for `v*` tags and manual dispatch, using repository signing secrets and uploading the APK plus GPLv3 `LICENSE` to the GitHub Release.
    - `done` - Make Rust, UniFFI, and Android NDK Gradle paths portable across Windows and Unix hosts by resolving Cargo, SDK, NDK, and host library names from local properties and environment variables.
    - Verified on Windows host and Android emulator: ran `.\gradlew.bat check --console=plain --stacktrace`, `.\gradlew.bat :app:assembleDebug --console=plain --stacktrace`, and `.\gradlew.bat :app:installDebug --console=plain --stacktrace`; launched `moe.antimony.hoshi/.MainActivity` on `emulator-5554` and confirmed the app stayed running on the Books screen without native library load failures.
    - Add EPUB fixtures for cover, images, vertical text, horizontal text, complex spine, and broken resources.
    - Expand WebView pagination regression checks.
    - Keep Gradle `test`, `assembleDebug`, and `lint` passing before release-facing changes.
