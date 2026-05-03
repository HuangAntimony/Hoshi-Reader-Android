# Navigation And Reader Entry Characterization

Date: 2026-05-04
Slice: R-000 Characterize Navigation and Reader Entry Behavior

This baseline protects the next Navigation3 migration slice. It records current Android behavior and the iOS reference behavior that should remain the user-visible source of truth. It does not define new behavior.

## Source References

Android:

- `app/src/main/java/moe/antimony/hoshi/MainActivity.kt`
- `app/src/main/java/moe/antimony/hoshi/features/bookshelf/BookshelfView.kt`
- `app/src/main/java/moe/antimony/hoshi/features/settings/SettingsDetailScaffold.kt`
- `app/src/main/java/moe/antimony/hoshi/features/reader/ReaderWebView.kt`
- `app/src/main/java/moe/antimony/hoshi/features/sasayaki/SasayakiMediaSession.kt`

iOS reference:

- `reference/Hoshi-Reader-iOS/Features/Bookshelf/BookshelfView.swift`
- `reference/Hoshi-Reader-iOS/Features/Bookshelf/ShelfView.swift`
- `reference/Hoshi-Reader-iOS/Features/Reader/ReaderView/ReaderWindow.swift`
- `reference/Hoshi-Reader-iOS/Features/Reader/ReaderView/ReaderViewModel.swift`
- `reference/Hoshi-Reader-iOS/Features/Reader/ReaderView/ReaderView.swift`
- `reference/Hoshi-Reader-iOS/App/HoshiReader.swift`
- `reference/Hoshi-Reader-iOS/Features/Sasayaki/SasayakiPlayer.swift`

## Current Android Baseline

### Top-level tabs

- `BookshelfView` owns the selected top-level tab in local Compose state.
- Initial tab is `Dictionary` only when `DictionarySettingsStore.load().dictionaryTabDefault` is true; otherwise it is `Books`.
- Top-level tab order is `Books`, `Dictionary`, `Settings`.
- Selecting a top-level tab clears `settingsDestination`.

### Settings detail return

- Settings details are owned by `settingsDestination` in `BookshelfView`.
- Dictionaries, Appearance, Behavior, Advanced, and Diagnostics are rendered as exclusive early-return full-screen destinations.
- Settings details return by setting `settingsDestination = null`.
- Details that use `SettingsDetailScaffold` handle both toolbar back and Android Back through `onClose`.

### Reader open and close

- Tapping a book parses the book root, refreshes metadata access time, loads `bookmark.json`, and sets `isReading = true`.
- Import success selects the Books tab and opens the reader.
- While `isReading && book != null`, `BookshelfView` renders only `ReaderWebView` and returns before the main shell.
- Reader toolbar close calls `onClose`, which currently sets `isReading = false`.

### Android Back from reader

- `ReaderWebView` registers `BackHandler(onBack = onClose)`.
- Current Android Back from reader therefore exits the reader surface and returns to the previous `BookshelfView` state instead of paging or changing chapters.

### External EPUB open

- `MainActivity` extracts a pending import only from `Intent.ACTION_VIEW` data.
- `onNewIntent` updates the activity intent and replaces `pendingImportUri`.
- `BookshelfView` consumes a non-null `pendingImportUri` once, clears it via `onPendingImportConsumed`, then imports the EPUB.
- External import success selects Books and opens the reader.

### Bookmark restoration

- `BookshelfView` passes `bookmark?.chapterIndex ?: 0` and `bookmark?.progress ?: 0.0` into `ReaderWebView`.
- `ReaderWebView` reports bookmark updates through `onSaveBookmark`.
- `BookshelfView` writes `bookmark.json` and updates in-memory shelf progress from the saved character count.

### Sasayaki media-session return

- `SasayakiMediaSession` sets a session activity and notification content intent.
- The intent uses the package launch intent with `FLAG_ACTIVITY_SINGLE_TOP | FLAG_ACTIVITY_REORDER_TO_FRONT`.
- `AndroidManifest.xml` keeps `MainActivity` as `singleTop`, so tapping the media session should return to the existing app task rather than creating a duplicate task.

## iOS Reference Baseline

- iOS `BookshelfView` uses a `TabView` with Books, Dictionary, and Settings, with initial tab selected from `dictionaryTabDefault`.
- Tapping Dictionary while already selected toggles dictionary search focus.
- iOS Settings details are pushed from a Settings `NavigationStack`; returns are normal back-pops that preserve the selected Settings tab.
- iOS reader entry is presented outside the tab stack through `ReaderWindow` over full screen; closing resets the selected book, reloads books, and dismisses the reader window.
- iOS pending EPUB imports clear the bookshelf navigation path before importing, then clear the pending URL. They import into the bookshelf rather than opening the reader automatically.
- iOS `hoshi://search` and share-extension text route to Dictionary. If a reader is open, the reader is closed before switching to Dictionary.
- iOS `ReaderViewModel` loads `bookmark.json` during reader initialization and falls back to chapter `0`, progress `0.0`.
- iOS reader progress changes persist a `Bookmark` with chapter index, progress, character count, and last modified date.
- iOS page turns are WebView/JavaScript-first; native chapter movement happens only when JavaScript reports a page boundary.
- iOS Sasayaki playback owns system remote controls through `MPNowPlayingSession`; Android should preserve the user-visible ability to return from system media UI to the active reading task.

## Known Parity Notes

- Android currently opens the reader after external EPUB import success. iOS imports the EPUB into the bookshelf and clears the pending URL without automatically opening Reader. R-000 records the current Android behavior; a later behavior-fix slice should decide whether to align this with iOS before or during Navigation3 route migration.
- Android currently routes only `Intent.ACTION_VIEW` file data through `pendingImportUri`. iOS also has `hoshi://search`, `hoshi://open?url=`, and shared-text routing. Those routes are not part of the current Android baseline.

## Manual Verification Checklist

Run this checklist before and after Navigation3 route migration. Use the default retained emulator app data unless the test explicitly requires a fresh import.

### Top-level tabs

- Launch the app and confirm the initial tab is Books unless dictionary default-tab setting is enabled.
- Tap Dictionary, Settings, then Books; each tab should show its expected top-level screen.
- From Settings, switch to Books and back to Settings; no previous detail screen should remain open.

### Settings detail return

- Open Settings.
- Open Dictionaries, Appearance, Behavior, Advanced, and Diagnostics.
- For each detail, tap the toolbar back button and confirm Settings list returns.
- Repeat at least one detail with Android Back and confirm Settings list returns.

### Reader open and close

- From Books, open an imported EPUB.
- Confirm the reader replaces the main shell.
- Tap the reader close button and confirm the Books tab returns with the same shelf state.

### Android Back from reader

- Open an imported EPUB.
- Press Android Back.
- Confirm the reader closes and returns to Books, without navigating pages or changing chapters.

### External EPUB open

- Open an EPUB through Android Documents or another `ACTION_VIEW` source.
- Confirm Hoshi imports the EPUB once, switches to Books, and opens the reader.
- Return to Books and confirm the imported book appears in the shelf.

### Bookmark restoration

- Open `testdata/test.epub`, move to a later page or chapter, then close the reader.
- Reopen the same book.
- Confirm it restores the saved chapter and progress.

### Sasayaki media-session return

- Enable Sasayaki with matched audio data.
- Start playback in the reader so the system media controls are active.
- Leave Hoshi using Home or Recents.
- Tap the Sasayaki notification/media session.
- Confirm Android returns to the existing Hoshi task and reader state, without creating a duplicate task or overlapping playback.
