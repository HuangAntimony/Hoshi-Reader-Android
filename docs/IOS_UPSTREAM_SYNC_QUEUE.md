# iOS Upstream Sync Queue

This document was rewritten from scratch after checking every iOS upstream commit after `5e01ba4ba9e2fd458536d48efdecaa48c7906cc0`.

- Source: `reference/Hoshi-Reader-iOS`
- Baseline: `5e01ba4ba9e2fd458536d48efdecaa48c7906cc0`
- Latest checked: `origin/develop` at `1e2aa8d11d5cd1687e11c6b8735a999e7a8ed16f`
- Checked on: 2026-05-16

## Current Queue

### 1. Popup typography and scaling

Status: completed on Android.

Commits:

- `f524178` - custom fonts in popup.
- `a384533` - popup scale/zoom setting.

iOS behavior to mirror:

- Imported and downloadable fonts can be used inside dictionary popup CSS via generated `@font-face` declarations.
- The CSS editor has a Font menu that inserts `font-family: "..." !important;` at the cursor.
- Popup WebViews accept a persisted `popupScale` setting, default `1.0`, slider range `0.8...1.5`, step `0.05`.
- Popup zoom updates live without rebuilding the popup content.

Android notes:

- Android reuses reader font import storage and the `https://hoshi.local/fonts/...` resource boundary for popup fonts.
- Custom CSS editing now includes Font and Selector menus that insert `font-family: "..." !important;` and `[data-dictionary="..."]` snippets.
- Popup scale is persisted in reader appearance settings and updates existing popup WebViews through JS instead of reloading the popup HTML.

Validation:

- Import `testdata/KleeOne-SemiBold.ttf`, select it through popup CSS insertion, and confirm lookup popup text uses the font.
- Change popup scale while a reader lookup popup is open and confirm content scales without losing selection, child popup, redirect, action bar, or scroll state.

### 2. Popup action buttons and frame sync

Status: queued

Commits:

- `851202d` - replace popup JS audio/mining buttons with native UIKit buttons.
- `8f0d827` - prevent horizontal popup scroll while holding buttons.
- `cd8ecf2` - refresh button frames when dictionary details expand/collapse.
- `1e2aa8d` - move dictionary toggle frame refresh to a document-level listener.

iOS behavior to mirror:

- Audio and Anki mining buttons are no longer ordinary HTML buttons inside the popup document.
- JS emits placeholder button frames; native controls are positioned over those slots.
- Button state still reflects audio error, duplicate check result, disabled mining state, and `allowDupes`.
- Autoplay invokes the same native audio path.
- Button frames refresh after resize, zoom, redirect/back/forward restore, and any dictionary collapse/expand toggle event that bubbles through the popup document.
- Holding or tapping the overlay controls must not horizontally scroll the popup content.

Android notes:

- Do not copy the iOS UIKit implementation. Implement with Android/Compose/WebView primitives while preserving the same user-visible control behavior.
- Existing popup JS still owns much of the button rendering path; keep the WebView bridge as the integration point.
- This slice should also re-check child popup selections and action-bar history, because button-frame refresh touches redirect/back/forward state.

Validation:

- Dictionary tab lookup and reader lookup both show audio/mining controls aligned to entry headers.
- Duplicate state, Anki add state, audio error state, autoplay, redirect history, collapsed dictionary toggles, and child popups all keep controls aligned.
- Slow horizontal drags/long presses on controls do not move popup content sideways.

### 3. Bookshelf title rename and metadata fallback

Status: completed on Android.

Commit:

- `96f44d3` - option to rename book titles; migrate optional metadata fields to non-optional on iOS.

iOS behavior to mirror:

- Long-pressing a book exposes `Rename`.
- Rename opens a text prompt seeded with the current display title.
- Empty or whitespace-only rename clears the custom title.
- Sorting by title uses the display title.
- Delete and Mark Read dialogs use the display title.
- EPUB imports fall back to the source file name when the EPUB title is missing or empty.

Android notes:

- Android already falls back to the unpacked root/file name in EPUB parsing, but `BookMetadata.title` and `folder` are still nullable for iOS sidecar compatibility.
- Prefer adding `renamedTitle` and a `displayTitle` model/helper instead of breaking existing sidecar reads.
- Keep old iOS/Android sidecars readable when `renamedTitle` is absent.
- Android now stores optional `renamedTitle`, seeds Rename with the current display title, clears the custom title on blank input, sorts by display title, and keeps reader/sync visible metadata on the display title while preserving the original EPUB title in metadata.
- EPUB import now passes the selected file name as the parser fallback when OPF title metadata is blank.

Validation:

- Unit coverage: `BookMetadataStorageTest`, `EpubBookParserTest`, `MainShellUiTest`, and `BookshelfViewModelTest`.
- Device validation remains recommended for long-press Rename, clearing the rename, reader metadata, delete/Mark Read dialogs, backup/restore, and importing an EPUB with missing title metadata.

### 4. Dictionary import, recommendation list, and collapsed cleanup

Status: synced on Android

Commits:

- `4cd688f` - add JMnedict to recommended dictionaries and switch JMdict to the no-proper-names package.
- `2f5d71a` - autodetect dictionary type during manual import.
- `b3312d9` - remove deleted dictionaries from collapsed dictionary config.

iOS behavior to mirror:

- Recommended download set is JMdict without proper names, JMnedict, and Jiten.
- Manual dictionary import no longer asks the user to choose Term/Frequency/Pitch first.
- Import result determines which type directories receive term, frequency, and pitch data.
- Deleting any dictionary removes its title from collapsed dictionary settings.
- Updating a renamed dictionary preserves collapsed state under the new title.

Android notes:

- Android now offers JMdict without proper names, JMnedict, and Jiten individually, while intentionally retaining Jitendex as an additional Android recommendation.
- Android manual import uses the native `ImportResult` counts to place term, frequency, and pitch dictionaries in their matching tabs.
- Android now removes deleted dictionary titles from collapsed settings and still migrates collapsed titles during dictionary update rename.

Validation:

- Unit coverage: `DictionaryRepositoryTest` and `DictionaryViewModelTest`.
- Device validation remains recommended for importing term, frequency, pitch, and mixed archives from one picker path; deleting a collapsed dictionary from Collapse Dictionaries -> Configure; and downloading JMdict, JMnedict, Jiten, and Jitendex from the recommended list.

### 5. Highlight grouping for unlabeled chapters

Status: synced on Android

Commit:

- `d5e966f` - group highlights whose chapter has no label into the previous labeled section.

iOS behavior to mirror:

- Highlight list sections use the nearest previous TOC label when a highlighted spine item has no chapter label.
- This avoids separate blank/untitled sections for front matter, split chapters, image pages, or other unlabeled spine entries.

Android notes:

- Android `ReaderHighlightSections` now mirrors the iOS fallback loop: while the target spine index is greater than zero and has no label, it walks backward until a labeled section is found.
- Highlight rows keep the original highlight objects, so jump and delete actions still target the original character positions.

Validation:

- Unit coverage: `ReaderHighlightBehaviorTest`.
- Device validation remains recommended with an EPUB that has highlights in unlabeled spine items between labeled TOC chapters.

## Covered Or No Android Action

- `746a7ac` / `a7f4750`: iOS adds and renames Sasayaki seek controls to skip controls. Android already has richer Sasayaki skip controls with cue/5s/10s/15s/30s actions shared by reader chrome, Sasayaki sheet controls, and Android media controls. Keep the existing validation entry in `docs/TODO.md`.
- `9626f84`: iOS removes direct index mutation when closing popups. Android popup stacks are already state-list driven rather than mutating SwiftUI binding indices directly. Re-test nested popup dismissals when touching popup controls.
- `53c6980`: iOS-specific SwiftUI reader button/glass-effect cleanup. Android reader chrome has its own Material implementation; no direct sync unless a user-visible mismatch is found.
- `130f6cf`: iOS reader background ignores safe area. Android does not share the same safe-area rendering model; handle any Android status/navigation background issue as a separate platform fix.
- `478f78d`: iOS build-number bump only. No Android sync.

## Full Commit Inventory

| Commit | Date | iOS summary | Android status |
| --- | --- | --- | --- |
| `f524178` | 2026-05-14 | Custom fonts in popup | Completed |
| `a384533` | 2026-05-15 | Popup scale/zoom | Completed |
| `96f44d3` | 2026-05-15 | Rename book titles; metadata fallback | Completed |
| `746a7ac` | 2026-05-15 | Add Sasayaki seek controls | Covered |
| `53c6980` | 2026-05-15 | Simplify iOS reader buttons | No direct Android action |
| `9626f84` | 2026-05-15 | Avoid direct popup index access | Covered; re-test with popup work |
| `d5e966f` | 2026-05-15 | Group unlabeled highlight chapters | Synced |
| `4cd688f` | 2026-05-15 | Recommended dictionary changes | Synced |
| `130f6cf` | 2026-05-16 | Reader background safe area | No direct Android action |
| `2f5d71a` | 2026-05-16 | Autodetect dictionary type | Synced |
| `b3312d9` | 2026-05-16 | Clean collapsed config on delete | Synced |
| `851202d` | 2026-05-16 | Native popup action buttons | Queued |
| `8f0d827` | 2026-05-16 | Prevent popup horizontal scroll | Queued with popup buttons |
| `478f78d` | 2026-05-16 | iOS build bump | No Android action |
| `a7f4750` | 2026-05-16 | Rename seek to skip | Covered |
| `cd8ecf2` | 2026-05-16 | Refresh popup button frames on toggle | Queued with popup buttons |
| `1e2aa8d` | 2026-05-16 | Move toggle frame refresh listener to document | Queued with popup buttons |

## Suggested Implementation Order

1. Popup action buttons and frame sync.

This keeps the remaining risky WebView/popup work grouped together.
