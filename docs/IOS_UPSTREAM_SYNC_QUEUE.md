# iOS Upstream Sync Queue

Temporary working document for syncing user-visible iOS upstream changes into Android without mixing the queue into `docs/TODO.md`.

Source range: `reference/Hoshi-Reader-iOS` commits `07b5c09` through `839d486`.

## Implementation Order

1. Popup quick fixes - implemented
   - `b063d7d`: ignore clicks on dictionary `summary` headers so collapse/expand does not trigger lookup or close.
   - `cf81fcb`: after popup redirect, reset scroll position across two animation frames.
   - `d99bef3`: respect `em` sizing for glossary images when dimensions are derived after image load.
   - `da0361a`: batch popup entry retrieval with `getEntries(start, count)` and reduce render yielding.

2. Sasayaki matching - implemented
   - `839d486`: include cue text length in matcher search range.
   - `839d486`: widen match UI search window from `50..350` to `50..1000`.

3. Lookup scanning setting - implemented
   - `07b5c09`: add `scanNonJapaneseText` setting, default `true`.
   - Pass the setting into dictionary popup and Reader selection JavaScript.
   - When disabled, treat non-Japanese code points as scan boundaries.

4. Dictionary collapse configuration - implemented
   - `2bef599`: replace boolean auto-collapse with `Expand All`, `Collapse All`, and `Custom`.
   - Add `expandFirstDictionary`.
   - Persist custom collapsed dictionary titles.
   - Add a settings screen for toggling per-dictionary collapsed state.

## Android Notes

- Android keeps collapsed dictionary titles in `DictionarySettings` DataStore instead of a separate iOS-style `collapsed.json`.
- Dictionary rename/update migration is not separately implemented because Android does not currently expose the iOS dictionary update flow that renames imported dictionaries in place.

## Not Implementing

- `dea01ca`, `af7426b`, `05b19b4`: merge/version-only iOS commits.
- `d84a2a5`: iOS status-bar tap scroll-to-top behavior has no Android equivalent.
- `f6d0392`: iOS WKWebView scheme handler and AVPlayer notification main-thread fixes do not map directly to Android WebView/Media3.

## Deferred

- `6c92780`: reading statistics midnight reset and date-key dedupe. Sync when Android implements reader statistics.

## Already Covered

- `6cdf39e`: hoshidicts upstream bump is covered by the Kotlin bridge submodule sync.
- `975088f`: popup JS/CSS resource loading is already covered by Android `https://hoshi.local/popup/...` asset routing for popup WebViews.
