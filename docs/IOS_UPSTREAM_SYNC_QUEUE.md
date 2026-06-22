# iOS Upstream Sync Queue

This document tracks open Android work after checking iOS upstream `develop`.

- Source: `reference/Hoshi-Reader-iOS`
- Baseline for this refresh: `cfc1e509a4b1f92a9d02dbf8c950cb392c0f25d9`
- Latest checked: `origin/develop` at `24e356f00cfc3b74675d5610d2ffeeb52516301c`
- Checked on: 2026-06-20

## Current Queue

### 1. Reader route open-failure fallback

Status: pending Android sync.

Commits:

- `53fdb72` - show book open failure.

Dependency/value reasoning:

- This is a small reader-route reliability slice. It keeps corrupted, missing, or unparsable books from leaving the user on a dead reader surface.

iOS behavior to mirror:

- If the reader loader cannot produce a `ReaderView`, iOS shows a neutral full-screen failure view with "Couldn't open book" and a Close button that dismisses the reader route.

Android current gap:

- `ReaderRouteStateHolder.load()` in `app/src/main/java/moe/antimony/hoshi/navigation/ReaderRouteStateHolder.kt` returns `ReaderRouteLoadState.Error(error.localizedMessage ?: "Failed to open EPUB.")`, so route-load failures can expose raw exception text such as "Book not found.".
- `ReaderRouteDestination()` in `app/src/main/java/moe/antimony/hoshi/navigation/ReaderRouteDestination.kt` renders only `Text(state.message)` for `ReaderRouteRenderState.Error`; it does not expose a Close action that routes through the normal reader close path.
- `BookshelfViewModel.openBook()` already reports pre-route open failures through `R.string.bookshelf_open_failed`, so the remaining gap is the Reader route load-failure state.

Suggested slice:

- Replace route-load error rendering with a localized generic message and Close action.
- Keep the error state inside the existing route lifecycle and call the same `onClose` path used by reader chrome.
- Add focused state/render tests for missing book or parser failure.

Validation:

- Open a book that is missing from storage or fails parsing and confirm the Reader shows a localized failure view with a working Close action.
- Confirm normal reader open/close and Android Back still preserve bookshelf state and bookmark refresh behavior.

### 2. Lookup popup two-column layout and visual sizing

Status: pending Android sync.

Commits:

- `ed25036` - masonry layout and popup visual redesign.

Dependency/value reasoning:

- This is the largest user-visible UI delta in the refresh. The storage/settings toggle must land before the popup HTML/JS/CSS can reliably render the new layout in Reader, Dictionary tab, and Process Text flows.

iOS behavior to mirror:

- Dictionary settings add a `Two-Column Layout` toggle with explanatory copy.
- Popup rendering receives `twoColumnLayout` and arranges multi-dictionary glossary sections in two columns, using native masonry when available or a measured fallback.
- Glossary groups get card-like padding, border, and dark-mode treatment; popup body padding is tightened.
- Definition image canvas rendering uses a larger maximum canvas size.
- Popup height setting can go up to 800.

Android current gap:

- `DictionarySettings` and `DictionarySettingsRepository` in `app/src/main/java/moe/antimony/hoshi/features/dictionary/DictionarySettings.kt` persist `compactGlossaries`, `showExpressionTags`, and pitch/frequency options, but no `twoColumnLayout`.
- `DictionaryView.kt` only exposes the Compact Glossaries toggle in the Behaviour section.
- `LookupPopupHtml.kt` injects `window.compactGlossaries`, but no `window.twoColumnLayout`.
- `app/src/main/assets/hoshi-web/popup/popup.js` appends each `glossary-group` directly under the entry and has no masonry layout, `ResizeObserver`, or two-column style injection.
- `app/src/main/assets/hoshi-web/popup/popup.css` still uses 10px body padding, simple glossary group spacing, and `maxCanvasSize = 128` in `popup.js`.
- `ReaderAppearanceView.kt` keeps popup height at `100f..500f`.

Suggested slice:

- Add `twoColumnLayout` to dictionary settings, profile-aware persistence, tests, and localized strings.
- Inject `window.twoColumnLayout` in the popup bootstrap used by Reader, Dictionary tab, and Process Text.
- Port the popup JS/CSS layout behavior into `app/src/main/assets/hoshi-web/popup`, keeping Android bridge calls intact.
- Raise the popup height setting range to 800 and update focused settings tests.

Validation:

- Reader lookup popup, Dictionary tab iframe popup, recursive popup lookup, and Process Text popup with one dictionary and multiple dictionaries.
- Full-width/larger popup with two-column layout enabled and disabled, including collapsed dictionary sections and long glossary content.
- Dark, light, e-ink, reduced-motion popup scrolling, Anki mining buttons, audio buttons, image glossary rendering, and outside-tap dismissal.
- `node --test app/src/test/js/*.test.mjs`, focused popup/settings unit tests, localization test, and `./gradlew lint` for resource changes.

### 3. Google Drive timeout and automatic-refresh error suppression

Status: pending Android sync.

Commits:

- `4dae37c` - time out Drive requests after 10s and suppress timeout errors.

Dependency/value reasoning:

- This affects Bookshelf remote-books refresh and sync perceived reliability. It should stay behind the existing Drive repository/data-source boundary.

iOS behavior to mirror:

- Google OAuth token and Drive API requests use a 10-second timeout.
- Automatic remote bookshelf refresh suppresses transient offline, timed-out, and network-lost errors instead of surfacing a user-facing failure.

Android current gap:

- `DeviceCodeDriveAuthorizer` uses `RequestTimeoutMillis = 15_000`; `GoogleDriveClient` uses `HttpConnectTimeoutMillis = 15_000` and `HttpReadTimeoutMillis = 30_000`.
- `BookshelfViewModel.isOfflineRemoteLoadError()` suppresses only `GoogleDriveApiException.NoInternetConnectionMessage` during automatic remote refresh; `SocketTimeoutException`, read timeouts, and connection-lost IO failures are not normalized into that path.
- `DeviceCodeAuthorizationPollingTest` covers transient polling failures for device-code auth, but there is no matching repository/ViewModel coverage for remote bookshelf refresh suppression.

Suggested slice:

- Normalize Drive timeout and transient network failures at the Drive data-source or repository boundary.
- Use Android networking APIs according to current Android/Jetpack guidance for timeouts and connectivity checks when implementing this slice.
- Suppress transient timeout/network-lost errors only for automatic remote refresh paths; keep user-triggered import/export/delete errors visible.
- Add tests around `BookshelfViewModel.reloadRemoteBookEntries()` or its repository boundary for timeout suppression.

Validation:

- Automatic Bookshelf remote refresh with no network, slow token request, slow Drive list request, and mid-request connection loss.
- Manual Google Drive connect, refresh, import, export, and delete still show actionable errors when user-triggered.
- Existing Google Drive sync, remote cover cache, stale-cache retry, and device-code polling tests.

### 4. Bookshelf cover decode pressure

Status: pending Android sync.

Commits:

- `b928010` - decode covers one at a time.

Dependency/value reasoning:

- This is a performance and memory-pressure slice for large libraries. It is independent of the Drive and popup work.

iOS behavior to mirror:

- Cover thumbnail decoding is serialized through a shared actor so multiple visible covers do not decode concurrently.
- Decoded thumbnails remain bounded to the existing maximum pixel size.

Android current gap:

- `BookCoverBitmapCache.load()` in `app/src/main/java/moe/antimony/hoshi/features/bookshelf/BookshelfView.kt` dispatches every cache miss to `Dispatchers.IO`; visible `BookCoverCard` instances can decode multiple `BitmapFactory.decodeFile()` calls concurrently.
- The cache bounds decoded bitmap memory with `LruCache` and samples to 768px, but there is no decode mutex, semaphore, or limited-parallelism dispatcher for cover bitmap decode.

Suggested slice:

- Serialize or tightly limit cover decode concurrency behind `BookCoverBitmapCache`, without blocking Compose main-thread rendering.
- Preserve cache-key behavior, sampled decode, and `prepareToDraw()`.
- Add focused tests around any extracted decode scheduler or cache behavior where feasible.

Validation:

- Bookshelf with many local and remote covers, fast scrolling, tab switching, and refresh after returning from Reader.
- Memory/jank spot check on a large library before and after if this slice is implemented as a performance fix.

### 5. Reader WebView line-box CSS parity

Status: pending Android sync.

Commits:

- `bdf71a6` - remove WebKit line-box property.

Dependency/value reasoning:

- This is a small reader layout parity change. It should be validated with ruby, replaced elements, cover pages, and vertical writing before removal.

iOS behavior to mirror:

- Reader CSS no longer injects `-webkit-line-box-contain: block glyphs replaced;` in paginated or scroll readers.

Android current gap:

- `app/src/main/assets/hoshi-web/reader/reader.css` still contains `-webkit-line-box-contain: block glyphs replaced;`.
- `ReaderSettingsTest` currently asserts that the generated reader CSS contains this property, so Android tests would preserve the old behavior.

Suggested slice:

- Remove the property from Android reader assets if WebView validation confirms the iOS final behavior is correct on Android.
- Update tests to assert the final CSS behavior rather than preserving the removed property.

Validation:

- Paginated and continuous reader in horizontal and vertical writing.
- Ruby text, images, cover pages, multi-image pages, line-height changes, and page progress/restore around image-heavy pages.
- Focused reader asset/unit tests and reader JS tests if touched.

## Open Commit Inventory

| Commit | Date | iOS summary | Android status |
| --- | --- | --- | --- |
| `53fdb72` | 2026-06-15 | Show a closeable book-open failure view | Pending route error UI |
| `ed25036` | 2026-06-14 | Add popup two-column layout, visual refresh, and 800px height range | Pending settings and popup asset sync |
| `4dae37c` | 2026-06-13 | Add 10s Drive timeout and suppress transient timeout errors | Pending Drive timeout/error normalization |
| `b928010` | 2026-06-15 | Serialize cover thumbnail decoding | Pending cover decode concurrency limit |
| `bdf71a6` | 2026-06-07 | Remove reader WebKit line-box CSS property | Pending reader CSS parity validation |

## Suggested Implementation Order

1. Reader route open-failure fallback.
2. Lookup popup two-column layout and visual sizing.
3. Google Drive timeout and automatic-refresh error suppression.
4. Bookshelf cover decode pressure.
5. Reader WebView line-box CSS parity.

## Covered Or No Android Action

- `17f6574`: GitHub issue templates only.
- `29cccb3`: Android AnkiConnect settings now store an optional API key and
  send it as the top-level `"key"` field on AnkiConnect requests when non-empty.
- `9e191af`: Android settings already use Nav3 typed routes through `AppShell`, `SettingsDetailRoute`, and independent tab back stacks.
- `5764c5c`: Android audio-source toggles update by `AudioSource` identity through `AudioSettings.withAudioSourceEnabled(source, enabled)`, not stale list indices.
- `35c928e`: iOS AVAudioSession deactivation threading has no direct Android analogue; Android Sasayaki owns playback through a Media3 `MediaSessionService`.
- `f4e9684`: iPad safe-area/focus workaround is platform-specific; Android reader chrome uses WindowInsets and already has focus-mode inset tests.
- `3f174c3`: Android does not run iOS document migrations or eager WebView preloading from Activity initialization; legacy book migration happens through the repository on IO with progress state.
- `929c6a6`, `387b6bb`, `20fa179`, `544aeb5`, `8c0e305`, `24e356f`: iOS release, warning, compiler setting, or Xcode project maintenance only.
