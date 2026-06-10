# Reader Performance Handoff

Date: 2026-06-10

This is a local debug handoff for Android reader open/chapter-switch performance. The user explicitly asked not to run build checks, not to stage, and not to commit these debug logs yet.

## Current State

The branch/worktree has local uncommitted reader performance logging and a few local perf changes. `git diff --check` was clean before the latest handoff file was added. No Gradle build/test/lint was run after these local debug edits.

Important dirty items unrelated or pre-existing:

- `reference/Hoshi-Reader-iOS`
- `third_party/hoshidicts-kotlin-bridge`
- `app/release/`

Important local debug file:

- `app/src/main/java/moe/antimony/hoshi/features/diagnostics/PerformanceLog.kt`

Main touched areas:

- `app/src/main/assets/hoshi-web/reader/reader-paginated.js`
- `app/src/main/assets/hoshi-web/reader/reader-continuous.js`
- `app/src/main/java/moe/antimony/hoshi/features/reader/ReaderChapterWebView.kt`
- `app/src/main/java/moe/antimony/hoshi/features/reader/ReaderPaginationScripts.kt`
- `app/src/main/java/moe/antimony/hoshi/features/reader/ReaderWebResourceBridge.kt`
- `app/src/main/java/moe/antimony/hoshi/features/reader/ReaderWebView.kt`
- `app/src/main/java/moe/antimony/hoshi/features/reader/ReaderWebViewStateHolder.kt`
- `app/src/main/java/moe/antimony/hoshi/navigation/ReaderRouteDestination.kt`
- `app/src/main/java/moe/antimony/hoshi/navigation/ReaderRouteStateHolder.kt`
- `app/src/main/java/moe/antimony/hoshi/epub/EpubBookParser.kt`

## What The Logs Showed

Initial slow path, before fixes:

- Route/parser work was not the primary issue once book info cache was usable.
- WebView page load was roughly 0.7-1.3s.
- JS setup after page finish was several seconds at first.
- `initialize viewport/css/image variables` used to be hundreds to thousands of ms due to late viewport/style mutation.
- Switching to previous chapter with `progress=1.0` spent about 0.8-0.9s in `restoreProgress last page metrics`.

After local changes:

- `initialize viewport/css/image variables` is now effectively 0-1ms because early CSS is injected and viewport/page dimensions come from Kotlin instead of JS `window.innerWidth/innerHeight` reads.
- Previous-chapter restore improved. `restoreProgress last page target took 0ms` replaced the old ~829ms `last page metrics` path.
- Chapter 9 previous restore dropped from roughly 2.4s to roughly 1.4-1.6s in the user traces.

Remaining hot spots in the latest trace:

- Initial reader compose/viewport path: route ready to load URL still waits for WebView size, CSS build, and setup script build.
- Setup script build on main thread: ~100-330ms depending on first load vs chapter switch.
- WebView document load: ~0.6-1.3s.
- JS normalization and image layout:
  - `initialize normalize ruby text`: ~250ms on chapter 9, ~500-560ms on chapter 10.
  - `initialize image layout settled`: ~350-640ms.
  - `initialize buildNodeOffsets`: ~140-215ms.
- `buildPaginationMetrics` warm-up was running immediately after restore and blocking main thread for ~600-800ms. This was removed from `notifyRestoreComplete()` after the latest trace, but the user has not yet supplied a new trace with that removal.

## Applied Local Changes

### Logging

Added `PerformanceLog` and `HoshiReaderPerf` logs around:

- EPUB parse stages and book info reuse.
- Reader route load stages.
- WebView composition, viewport changes, and restore epochs.
- Web resource load timing for slow resources.
- WebView `loadUrl`, page finish, setup script evaluation, JS restore callback.
- JS paginated/continuous initialization stages.

### Early Reader CSS

`ReaderWebResourceBridge` now injects early viewport meta and initial reader CSS into XHTML via `readerHtmlWithEarlyViewport(...)`.

`ReaderChapterWebView` builds `readerContentCss` once the WebView viewport is known and passes it to the bridge. The JS setup script reuses `<style id="hoshi-reader-style">` instead of always appending a late style tag.

This is what made `initialStyle=true` and collapsed the old viewport/css mutation cost.

### Native Viewport Dimensions In JS

`ReaderPaginationScripts` now accepts `viewportCssSize` and replaces these placeholders in JS assets:

- `__HOSHI_VIEWPORT_WIDTH_JS__`
- `__HOSHI_VIEWPORT_HEIGHT_JS__`
- `__HOSHI_PAGE_HEIGHT_JS__`
- `__HOSHI_PAGE_WIDTH_JS__`

This avoids live `window.innerWidth/innerHeight` reads during initialization.

### Skip Setup Until Viewport Exists

`ReaderChapterWebView` skips CSS/setup-script construction while viewport is `0 x 0`. This removed one useless first setup build. It logs:

- `reader content CSS build skipped pending viewport`
- `reader setup script build skipped pending viewport`
- `reader WebView size changed`
- `reader viewport state changed`

### Fast Last Page Restore (Reverted 2026-06-10)

In `reader-paginated.js`, `progress >= 0.99` briefly used `fastLastPageScroll(context)` (raw `floor(maxScroll / pageSize) * pageSize`) when full pagination metrics were not already cached, to avoid the then-expensive full text-node + `getClientRects()` metrics pass during previous-chapter restore.

**Reverted during PR review**: `context.maxScroll` includes the trailing spacer and bottom padding, so on some devices the fast path landed one blank page past the last content page. The restore path now always uses `contentLastPageScroll(context)` again. This is affordable because `buildPaginationMetrics` has since become bounds-only with a `trim()` filter (~10-30ms expected, vs the original ~829ms full scan that motivated the shortcut). `fastLastPageScroll` was deleted; the `cachedMetrics=` field stays in the `restoreProgress last page target` log.

### Removed Immediate Pagination Metrics Warm-Up

`notifyRestoreComplete()` no longer calls `warmPaginationMetrics()`. The old automatic warm-up did a 600-800ms main-thread `buildPaginationMetrics` almost immediately after restore and delayed user input in the trace. Metrics remain lazy through existing call sites like progress calculation.

This removal was made after the last supplied trace, so it still needs local trace confirmation.

## Trace 2026-06-10 11:06 (After Warm-Up Removal)

The user supplied the post-warm-up-removal trace. Findings:

- Confirmed: no `warmPaginationMetrics start` after restore. Restore completes without the automatic post-restore metrics block.
- But `buildPaginationMetrics` moved onto the first user tap instead. `paginate()` lazily built full metrics on the first page turn in each chapter: 602ms on chapter 9, 972ms on chapter 10.
- The chapter-10 back-tap was the worst case: `paginate('backward')` on page 1 built full metrics (972ms) just to return `"limit"`, and only then did `previous chapter requested` fire (1ms after the metrics log). Perceived previous-chapter latency was ~972ms + ~1640ms switch = ~2.6s.
- Restore times: chapter 9 initial 1996ms total (page finished 1038ms), chapter 10 next 2356ms (page finished 941ms, ruby 547ms, image settle 653ms, buildNodeOffsets 194ms), chapter 9 previous 1470ms (page finished 689ms, ruby 255ms, image settle 321ms, buildNodeOffsets 162ms).

### Fix Applied: Bounds-Only buildPaginationMetrics

Verified that `paginationMetrics.progressStops` and `paginationMetrics.totalChars` had no consumers anywhere (JS or Kotlin) — only `minScroll`/`maxScroll` are read (by `paginate()`, `contentFirstPageScroll`, `contentLastPageScroll`). `calculateProgress()` does its own independent walker pass.

Rewrote `buildPaginationMetrics` in `reader-paginated.js` to:

- Collect text nodes without measuring (cheap, no forced layout).
- Measure `Range.getClientRects()` only on the DOM-first and DOM-last measurable text nodes. Under monotonic page flow one boundary node holds the min start edge and the other the max end edge regardless of flow direction (vertical/rtl), so min/max across both preserves the old result.
- Keep the existing media (`img, svg, image, video, canvas`) bounding pass unchanged.
- Drop `progressStops`/`totalChars` from the metrics object entirely.

Expected: first-tap `buildPaginationMetrics` drops from 600-970ms to ~1-5ms. Log format changed to `minScroll=... maxScroll=...` (no more `totalChars=`/`stops=`).

Known assumption: an interior node extending beyond both boundary text nodes in the scroll axis (weird absolute/table layouts) would shift bounds vs the old full scan. Media is still covered by the separate pass.

**Hardened during PR review (2026-06-10)**: instead of measuring only the single first/last measurable text node, `buildPaginationMetrics` now measures up to 64 measurable nodes from each end (`boundarySampleLimit`). With ruby stabilization splitting boundary text into per-character nodes (~2 chars/node), 64 nodes covers roughly one full column at default font size, so short positioned lines near chapter edges (colophon pages etc.) no longer shift bounds. Deep-interior outliers remain covered only by the media pass; cost is ~128 cheap `getClientRects()` calls.

Not yet addressed: `calculateProgress()` (full walker + `countCharsBeforeViewport` per node) still runs after every page turn for bookmark progress; its cost is not separately logged. If next trace shows taps still slow, instrument it.

## Trace 2026-06-10 11:14 (After Bounds-Only Metrics)

- `buildPaginationMetrics` dropped from 602-972ms to 117-167ms. Still on the tap path: each metrics log is immediately followed by `next/previous chapter requested`, so taps still stalled ~120-170ms. Remaining cost is likely the full-text-node walker + `countChars` (and possibly one forced layout), not rect reads.
- Key discovery from the old trace numbers: chapter 9 has ~12512 text nodes for 26667 chars, chapter 10 ~16644 nodes for 36640 chars (~2.2 chars/node). `stabilizeRubyAdjacentTextNodes` splits ruby-adjacent text into per-character nodes (up to 64 per ruby), which multiplies the cost of every walker pass: `buildNodeOffsets` (164-216ms), the metrics walk, `calculateProgress`, etc. Node-count reduction is the highest-leverage future direction, but requires understanding the stabilization first (see below).
- Chapter switch totals roughly unchanged: ch10 next 2221ms / 2055ms, ch9 prev 1450ms. Big items: page finished 665-903ms, ruby normalize 255-515ms, image settle 319-563ms, buildNodeOffsets 164-216ms.

### Changes Applied After This Trace

1. **Idle metrics warm-up** (`reader-paginated.js`): `notifyRestoreComplete()` now calls `schedulePaginationMetricsWarmup()`, which builds bounds-only metrics via `requestIdleCallback` (2s timeout; `setTimeout(250)` fallback). Unlike the old removed warm-up (600-800ms synchronous), this is ~120-170ms on idle. First tap should now hit cached metrics (`cachedMetrics=true` on prev-chapter restore too, since warm-up happens before the user navigates).
2. **Ruby instrumentation** (both `reader-paginated.js` and `reader-continuous.js`, behavior unchanged): `normalizeRubyTextNodes` logs `rubies= wrapped= removed=`; `stabilizeRubyAdjacentTextNodes` logs `rubies= candidates= splitNodes= createdNodes=` with per-function timing. This will show how much of the 255-515ms is normalize vs stabilize and quantify the node fragmentation.

### Next Trace Checklist

- `buildPaginationMetrics` should appear shortly after restore (idle) and not right before `next/previous chapter requested`.
- `restoreProgress last page target` may show `cachedMetrics=true` if metrics survive (they won't across chapter loads — each chapter is a fresh document — so this stays false; the warm-up only helps taps within the current chapter).
- New logs: `normalizeRubyTextNodes took ... rubies= wrapped= removed=` and `stabilizeRubyAdjacentTextNodes took ... rubies= candidates= splitNodes= createdNodes=` — compare the two times and note `createdNodes` (expected to roughly match the huge text-node counts).

## Trace 2026-06-10 11:20 (Idle Warm-Up + Ruby Instrumentation)

- Idle warm-up confirmed working: `buildPaginationMetrics` (116-189ms) now fires ~150-250ms after `restore completed` on idle, and chapter taps are no longer blocked behind it — `previous/next chapter requested` fires instantly on tap.
- Ruby instrumentation exposed the real cost of the "normalize ruby text" phase (257-573ms). The two functions are cheap:
  - `normalizeRubyTextNodes`: 28-46ms (rubies=567-757, wrapped=1029-1404, removed=0)
  - `stabilizeRubyAdjacentTextNodes`: 38-72ms (candidates=537-711, all split, createdNodes=11724-15546)
  - The missing ~190-460ms sits **between** the two calls: `stabilizeRubyAdjacentTextNodes` starts with `if (!this.isVertical()) return;` and `isVertical()` called `getComputedStyle(document.body)`, which forces a full style recalc of the document just dirtied by wrapping ~1400 spans.
- Node fragmentation quantified: stabilization creates 11.7k-15.5k text nodes per chapter (matches the ~2 chars/node seen in metrics). This is why `buildNodeOffsets` (141-211ms), the metrics walk, and `calculateProgress` are slow — they all walk every text node.

### Fix Applied: Memoized isVertical

`isVertical()` in both `reader-paginated.js` and `reader-continuous.js` now caches its result in `verticalModeCache` (per document). Safe because `verticalWriting` is part of `ReaderContentReloadKey` (ReaderWebViewStateHolder.kt), so a writing-mode change reloads the page and redefines `window.hoshiReader`, resetting the cache. The runtime appearance script only sets `--hoshi-reader-vertical-writing` as a CSS var; actual writing-mode changes always reload.

Startup now primes the cache before the ruby DOM mutations and logs it as `initialize prime vertical mode` (expected ~0-30ms on the clean DOM). Expected effect: `initialize normalize ruby text` drops from 257-573ms to ~70-120ms; the style recalc folds into the layout pass that `image layout settled` already pays.

### Next Trace Checklist

- `initialize prime vertical mode` should be small; `initialize normalize ruby text` should now be close to the sum of the two function logs.
- Watch whether `image layout settled` grows to absorb part of the recalc (net win expected, since the document was being recalced twice).
- Remaining hot spots after this: WebView `page finished` (650-1326ms), `image layout settled` (303-668ms), `buildNodeOffsets` (141-211ms), setup script build (~105-330ms).
- The biggest structural lever left in JS is reducing the 11.7k-15.5k split text nodes, but that requires first learning what regression `stabilizeRubyAdjacentTextNodes` prevents (see below) — ask the user before touching behavior.

## Trace 2026-06-10 11:27 (Memoized isVertical)

- `initialize normalize ruby text` dropped from 257-573ms to 66-118ms as predicted.
- But the recalc did not disappear — it moved into the new `initialize prime vertical mode` log (149-309ms). So that cost is the document's *initial* style resolution (94KB chapter + 54KB publisher stylesheet), not just the post-span-wrap recalc. Net win was only ~100-150ms on chapter 10 (prime+normalize 427ms vs 573ms) and ~40ms on chapter 9.
- Restore totals roughly flat: ch9 initial 2200ms, ch10 next 2293ms, ch9 prev 1510ms.

### Fix Applied: Inject Vertical Writing From Kotlin

JS no longer calls `getComputedStyle` for writing mode at all:

- `ReaderPaginationScripts.kt` replaces a new `__HOSHI_VERTICAL_WRITING_JS__` placeholder with `settings.verticalWriting`.
- Both JS files pre-fill `verticalModeCache: __HOSHI_VERTICAL_WRITING_JS__` (the `getComputedStyle` fallback in `isVertical()` remains but never fires).
- The `initialize prime vertical mode` block was removed from both startups.

Safe because reader CSS forces `writing-mode: ... !important` on `html, body` from the same setting (`ReaderContentStyles.kt:192/217`), so computed style always matches `settings.verticalWriting`, and Kotlin already uses that setting directly for swipe direction.

Expected effect: the standalone 149-309ms sync style recalc disappears from the initialize path; the renderer performs style+layout once inside the rAF wait, so part of it may reappear inside `initialize image layout settled`. Net win expected because style resolution now happens once, not twice.

### Next Trace Checklist

- No `initialize prime vertical mode` log; `initialize normalize ruby text` stays ~65-120ms.
- Compare `initialize image layout settled` (was 303-679ms) — it may absorb some recalc; the number to watch is total `reader chapter load restored`.
- Remaining hot spots: WebView `page finished` (650-1326ms), `image layout settled`, `buildNodeOffsets` (141-221ms), setup script build on main thread (~100-400ms).

## Trace 2026-06-10 11:31 (Vertical Writing Injected)

- `initialize prime vertical mode` gone; setup script evaluation dropped from 223-445ms to 78-125ms.
- `image layout settled` absorbed part of the recalc as expected (ch10 916-985ms vs 679ms; ch9 495-792ms vs 303-549ms) — style resolution now happens once, in the renderer's frame.
- Net totals improved modestly: ch9 initial 2112ms (was ~2200), ch10 next 2090-2270ms (was ~2293), ch9 prev 1396ms (was ~1510).
- Current per-switch breakdown (ch10): setup script build ~106-118ms, page finished 874-961ms, ruby normalize ~90ms, image layout settled 916-985ms, buildNodeOffsets ~197-216ms.

### Why Ruby Stabilization Exists (Resolved)

Git history answers it: commits `2dd0fbb` ("fix(reader): stabilize vertical ruby pagination") and `0e73b75` ("fix(reader): stabilize continuous ruby layout"), changelog entry: "Prevent vertical reader text from prematurely wrapping after furigana, leaving blank space in the current column." Chromium treats the text run after a `<ruby>` as one unit when deciding column placement in vertical-rl, pushing it all to the next column; per-character text nodes give it break opportunities. `splitLimit=64` is sized to cover at least one full column of characters (~35 at default font size), so reducing it would reintroduce the bug. There is a JS test suite: `app/src/test/js/reader-paginated.test.mjs`. The splits stay; downstream walkers must live with 11.7k-15.5k nodes.

### Fix Applied: Drop countChars From Metrics Walk

`buildPaginationMetrics` filtered candidate text nodes with `countChars()` (regex replace + `Array.from` per node, 12-16k calls). Replaced with a `nodeValue.trim()` check — the boundary measurement already skips zero-rect nodes. Minor semantic change: punctuation-only text nodes (excluded by the ttu regex) are now eligible as boundary nodes, which is more correct for content edges. Expected: metrics build drops from 116-181ms to ~10-30ms (it runs on idle, but also on cold `paginate()` paths).

### JS Test Harness Repaired

`app/src/test/js/reader-paginated.test.mjs` was broken by the local debug changes (not by this round's edits): the vm sandbox lacked `performance`, `requestAnimationFrame` (needed by the locally-added `waitForImageLayout`), `document.getElementById` (needed by the early-CSS style reuse), and replacements for the locally-added placeholders (`__HOSHI_VIEWPORT_*_JS__`, `__HOSHI_PAGE_*_JS__`, `__HOSHI_VERTICAL_WRITING_JS__` → `null` so tests exercise the `getComputedStyle` fallback). All fixed in the test harness only. All 51 JS tests pass:

```
node --test app/src/test/js/reader-paginated.test.mjs app/src/test/js/selection.test.mjs app/src/test/js/popup.test.mjs app/src/test/js/reader-popup-host.test.mjs
```

(Running `node --test app/src/test/js/` as a directory fails with an unrelated MODULE_NOT_FOUND; pass the files explicitly.)

### Remaining Hot Spots (Ordered)

1. WebView `page finished`: 642-1326ms per chapter load. Handoff item: investigate separately (HTML parse of ~127KB XHTML + 54KB CSS; `slow reader resource` shows the XHTML itself takes ~50ms to serve).
2. `image layout settled`: 495-985ms — actual style+layout of fragmented vertical text; partially intrinsic, inflated by the 11.7k-15.5k split nodes.
3. `buildNodeOffsets`: 149-216ms — two regex passes (`countChars` + `countRawChars`) per text node; could be merged or the regexes fast-pathed for 1-char strings.
4. Setup script build on Kotlin main thread: ~106-335ms (first build after viewport is the worst).

## Cold-Open Breakdown (Trace 2026-06-10 11:53) And Fixes

Cold process open (chapter 10) took 4542ms total: route load 357ms, route-ready-to-composition 446ms, viewport wait 315ms, recompose gap 149ms, CSS build 89ms, setup script build 404ms, page finished 1338ms, setup eval 144ms, image layout settled 1076ms, buildNodeOffsets 206ms, restore 6ms. Both renderer phases are inflated vs warm runs (page finished ~900ms, image settle ~500-680ms warm).

Three changes applied after this trace:

1. **WebView warmup at app start** (`ReaderWebViewWarmup.kt`, called from `HoshiApplication.onCreate` via a main-looper post so it runs after startup-critical work). Creates a dummy `WebView` with empty HTML to spawn the Chromium renderer process before the user opens a book — mirrors iOS `WebViewPreloader`. Released in the `ReaderChapterWebView` factory when the real WebView is created. Logs `reader WebView warmup created` / `released`. Should shrink the 446ms composition gap and the cold-load inflation of `page finished`.
2. **Setup script template cache** (`ReaderPaginationScripts.kt`): the 16-pass placeholder replacement over the ~94KB template is now split into a cached settings/viewport stage (keyed by `ReaderSettings` + `IntSize` + assets) plus two per-load replaces (`__HOSHI_RESTORE_TOKEN_LITERAL__`, `__HOSHI_RESTORE_SCRIPTS__`). First build per settings/viewport pays full cost; subsequent chapter loads should drop from ~105-404ms to a few ms.
3. **buildNodeOffsets single pass** (both JS files): replaced the two per-node `countChars`/`countRawChars` calls (each regex replace + `Array.from`) with one code-point loop using `ttuRegex.test` per char — equivalent per code point to the negated-class strip. Expected to cut the 141-216ms roughly in half or better.

All 51 JS tests pass after these changes.

## Trace 2026-06-10 12:01 (Warmup + Caches) — Mostly A Miss

Honest results, all three changes showed little to no effect:

- **Warmup**: `reader WebView warmup created took 269ms` fired at app start and released when the reader composed, but the route-ready-to-composition gap was 532ms (was 446ms) and cold `page finished` was 1363ms (was 1338ms). The gap is Compose-side work, not WebView process spawn; the cold page-load inflation must come from elsewhere (cold disk caches for the 54KB publisher CSS/fonts through the asset bridge, V8/JIT, per-instance costs). Kept anyway: 269ms off the critical path at app start is harmless and may help when a book is opened immediately after launch.
- **Setup script cache**: subsequent builds still ~113ms (same as the ~106-118ms before caching). Either the cache misses or — more likely — the 16 replaces were never the dominant cost; suspicion is now the wrapper assembly in `readerSetupScript` (interpolates the ~94KB pagination script into a multiline template and runs `trimIndent()` over the whole thing) or a cache miss.
- **buildNodeOffsets single pass**: unchanged (146-204ms vs 141-216ms). Conclusion: the cost is the TreeWalker traversal + 2 WeakMap sets per node across 12-16k nodes, not the char counting. Further improvement requires reducing node count, which is blocked by the furigana column-wrap fix.

### Instrumentation Added After This Trace

- `reader script settings stage rebuilt cached=...` logs on every settings-stage cache miss (`ReaderPaginationScripts.kt`). If this appears on every chapter switch, the cache key is unstable (check which of settings/viewport/assets changes identity).
- `reader pagination script stage` and `reader setup wrapper stage took ... chars=` split the setup script build (`ReaderChapterWebView.kt`) into shell-script construction vs wrapper template + `trimIndent()`.

Next trace: one chapter switch is enough. Read the three new logs to attribute the ~113ms, then fix the actual cost (drop `trimIndent` over the interpolated body / fix the cache key — whichever the data says).

### Resolved (Trace 2026-06-10 12:05 + 12:07)

The 12:05 trace attributed the cost: settings-stage cache works (`rebuilt cached=false` fires once), wrapper stage was 99ms — `trimIndent()` + template interpolation over the ~94KB script. The old `trimIndent` was an indentation no-op (interpolated scripts contain column-0 lines, so common indent was 0); it paid ~99ms to strip blank edge lines.

Fix: `readerSetupScript` now `trimIndent`s only the two small wrapper fragments and concatenates selection + pagination scripts via one pre-sized `buildString`. Output differs only in leading whitespace.

12:07 trace confirms: warm `reader setup script built` 32-37ms (was ~113ms; pagination stage 8-10ms + wrapper 8-10ms), cold first build 318ms (was ~406ms). Tap-to-loadUrl on chapter switch is now ~75ms.

Current warm chapter-switch breakdown (ch10): script build 32ms, page finished 844ms, ruby normalize 92ms, image layout settled 997ms, buildNodeOffsets 170ms. The renderer-bound phases (page load + layout) are now ~85% of switch time; Kotlin/JS orchestration is at its practical floor. The remaining levers are architectural (adjacent-chapter preload) per the earlier assessment.

## Cold-Open Round 2 (Applied 2026-06-10, Untraced)

Three changes targeting the ~1.3s of cold-open orchestration:

1. **Viewport seeding** (`ReaderViewportCache.kt` + `ReaderWebViewStateHolder.initialViewportSize` + wiring in `ReaderWebView.kt`): the last known WebView size is persisted in SharedPreferences keyed by orientation and seeds the state holder on open. `isReaderViewportReady` is then true at first composition, so CSS build, setup-script build, and `loadUrl` fire immediately instead of waiting ~600ms for `onSizeChanged`. A mismatched real size goes through the existing `updateViewportSize` reload path (same as rotation). First open after install still cold-paths (empty cache). Expected: `reader content CSS build skipped pending viewport` disappears on seeded opens.
2. **Resource warm-up** (`ReaderWebView.kt`, `LaunchedEffect(book)` on IO): reads and discards the bookmark chapter ±1 XHTML plus all `text/css` resources as soon as Ready composes, overlapping the compose gap, to pull them into the OS page cache before the WebView fetches them. Logs `reader resource warm took ... files= bytes=`.
3. **Compose gap instrumentation**: `reader WebView composable composed seededViewport=` (ReaderWebView first composition), `reader chapter WebView first composed chapter=` (ChapterWebView entry), `reader WebView factory created took ...` (AndroidView factory). These split the ~514ms route-ready-to-composition gap.

Next cold-open trace should show: seeded viewport in the composable log, no pending-viewport skips, loadUrl ~500ms earlier, and possibly reduced cold `page finished` from the warm page cache. Compare the three new timestamps against `reader route load ready` to attribute the remaining gap.

## Trace 2026-06-10 12:16 (Cold-Open Round 2 Results)

Items 1+2 netted ~30ms; the instrumentation was the real payoff:

- Route ready → `reader WebView composable composed`: 61ms. → `chapter WebView first composed`: **426ms** — the true gap; ChapterWebView is gated behind the reader UI's first composition (including the `highlights != null` gate in ReaderWebView). The old viewport wait was hiding inside this same window, which is why seeding barely moved loadUrl (+1032ms vs +1063ms after route ready).
- Viewport seeding works mechanically: seeded `1264 x 1576` matched, no pending-viewport skips, no reload.
- `reader resource warm took 6ms files=11 bytes=452748` — the OS page cache was already warm from prior runs. Implication: the repeated-test "cold" `page finished` ~1325ms is **not disk-bound**; the inflation vs warm loads (~720-910ms) is renderer-internal (font/GPU/V8 first-use). Item 2 only matters after reboot/eviction.
- Remaining serialized main-thread work after ChapterWebView composes: CSS build 89ms + cold settings-stage script build 299ms + factory 51ms.

### Fix Applied: Script Cache Warm During Route Load

`warmReaderSetupScriptCache(context, settings)` (in `ReaderChapterWebView.kt`) builds the settings-stage cache on Dispatchers.IO using the persisted viewport, called from a `LaunchedEffect(readerSettings)` in `ReaderRouteDestination` — so the ~300ms cold build runs in parallel with the EPUB parse (~343ms) instead of serialized on the main thread after composition. `cachedSettingsStage` is now `@Volatile`. Logs `reader setup script cache warm took ... viewport=`.

Expected next cold open: `reader setup script built` drops to ~40ms (cache hit + wrapper), pulling loadUrl from ~1032ms to ~730ms after route ready. The 426ms composition gap is the next attribution target if more is wanted (suspects: reader UI first composition cost, highlights gate).

### Reverted (User Request)

Viewport seeding and the resource warm-up were reverted after the 12:16 trace (~30ms combined benefit). Still in place:

- `ReaderViewportCache` and the save-on-change effect in `ReaderWebView.kt` — required by `warmReaderSetupScriptCache`, which reads the persisted viewport to build the right cache key.
- The script cache warm in `ReaderRouteDestination` (untraced, expected ~280ms).
- All round-2 instrumentation (`reader WebView composable composed`, `reader chapter WebView first composed`, `reader WebView factory created`).
- The app-start WebView warmup from the earlier round.

With seeding reverted, opens again wait for `onSizeChanged` (the `skipped pending viewport` logs return), but the script build at that point should be a cache hit thanks to the route-load warm.

## Trace 2026-06-10 12:21 (Script Cache Warm Confirmed)

- The warm ran on IO during the EPUB parse (`rebuilt cached=false` on thread 31355 at parse start, `reader setup script cache warm took 599ms viewport=632 x 788`) and the real build was a cache hit: `reader setup script built took 32ms` on the initial open (was ~322ms), with no rebuild log at build time.
- Route-ready → loadUrl: **717ms, down from 1032ms** (~315ms structural win).
- Absolute totals didn't drop as much (~150ms net) because this run was globally slower — warmup 361ms vs 270, native parse 219ms vs ~118, page finished 1489ms vs ~1325. Possibly IO contention between the warm (599ms on IO vs 299ms on main previously) and the parse, possibly thermal/device variance; single sample either way.
- Chapter switches unchanged and healthy: script built 32ms, switch totals ~1.56s / ~2.4s (run was slow overall).

Cold-open structure now: route load ~350-500ms, composition gap ~340-390ms (unattributed Compose work + highlights gate), viewport wait + CSS ~280ms, script build 32ms, page load ~1.3-1.5s (renderer), layout ~1.1s (renderer), buildNodeOffsets ~200ms. Everything app-controlled is at or near its floor except the composition gap; the rest is renderer territory (adjacent-chapter preload remains the only big lever).

## Logging Inventory: Must Be Removed Before A Clean Release

All performance logging in the tree is temporary debug tooling and is to be fully removed once the perf work is finished — do not gate it or keep it long-term. The tree mixes production fixes with this instrumentation; breakdown of what to delete versus what to keep:

### Pure logging — delete entirely

- `app/src/main/java/moe/antimony/hoshi/features/diagnostics/PerformanceLog.kt` — the logger itself (new file; unconditional, reflection-based `Log.d`).
- `readerPerfScript(...)` in `ReaderPaginationScripts.kt` (top of file) and its injection at the start of the setup script body (`"${readerPerfScript(mode)}\n" +`). This defines `window.hoshiReaderPerf`; without it, every JS perf call no-ops via its `window.hoshiReaderPerf &&` guard.
- `ReaderPerfConsoleClient` in `ReaderChapterWebView.kt` (~line 602) and the `webChromeClient = ReaderPerfConsoleClient()` assignment (~line 334) — only exists to pipe the JS perf console lines into logcat. There was no webChromeClient before.

### Logging-only diffs — entire file diff reverts to HEAD

- `EpubBookParser.kt` — parse-stage timing logs only, no logic changes.
- `ReaderRouteStateHolder.kt` — route-load timing logs only.

### Mixed files — strip `PerformanceLog` call sites, keep the logic

- `ReaderChapterWebView.kt` — keep: early-CSS plumbing, viewport gating, wrapper `buildString` build, warmup release, `warmReaderSetupScriptCache`. Strip: size/CSS/script-built logs, pagination/wrapper stage logs, factory-created log, first-composed log, and the log inside `warmReaderSetupScriptCache`.
- `ReaderWebView.kt` — keep: loading-indicator overlay, viewport-cache save effect. Strip: bookmark-save log, composable-composed log.
- `ReaderWebViewStateHolder.kt` — keep: nothing functional changed vs HEAD except logs (viewport-changed, reload-prepared). Strip logs → reverts to HEAD.
- `ReaderWebResourceBridge.kt` — keep: `initialReaderCss` parameter + `readerHtmlWithEarlyViewport` injection (this is the early-CSS production change). Strip: `logSlowResource` and its call sites.
- `ReaderRouteDestination.kt` — keep: script-cache warm effect, `ReaderLoadingIndicator` usage. Strip: sync-on-open timing logs.
- `ReaderWebViewWarmup.kt` — keep the file (production); strip its two `PerformanceLog` calls.
- `reader-paginated.js` / `reader-continuous.js` — remove all `window.hoshiReaderPerf && ...` lines and the bare `performance.now()` captures that feed them (`normalizeStart`, `stabilizeStart`, `metricsStart`, `initializeStart`, ...). The instrumentation counters added inside `normalizeRubyTextNodes`/`stabilizeRubyAdjacentTextNodes` (`wrapped`, `removed`, `candidates`, `splitNodes`, `createdNodes`) exist only for the perf logs and go with them.
- `reader-paginated.test.mjs` — keep `getElementById` shim, the viewport/vertical placeholder replacements, and the `requestAnimationFrame` shim (all needed by production changes — `waitForImageLayout` is production). The `performance` shim is only needed while the JS perf calls exist; remove it together with them.

### Clean production files (no logging concerns)

- `ReaderLoadingIndicator.kt`, `ReaderViewportCache.kt`, `HoshiApplication.kt` (one warmup call), `ReaderPaginationScripts.kt` apart from the perf script (the cache, placeholders, and structure are production — note `settingsStageBody` contains one `rebuilt cached=` log to strip).

### Removal checklist (when the perf work is done)

1. Delete `PerformanceLog.kt` and every `PerformanceLog.*` call site (the compiler will find them all once the file is gone).
2. Delete `readerPerfScript` and its injection from `ReaderPaginationScripts.kt`.
3. Delete `ReaderPerfConsoleClient` and the `webChromeClient` assignment from `ReaderChapterWebView.kt`.
4. Strip the `hoshiReaderPerf` lines, `performance.now()` captures, and instrumentation counters from both reader JS files; drop the `performance` shim from the JS test harness.
5. Run the JS test suite (`node --test app/src/test/js/*.test.mjs` files explicitly) and the Kotlin unit tests to confirm nothing functional was clipped.

## Do Not Casually Remove Ruby Stabilization

The user interrupted when I was about to consider changing the ruby normalization path and said: "this was done for a reason".

Do not remove or disable `stabilizeRubyAdjacentTextNodes()` just because it is expensive.

Current state:

- `stabilizeRubyAdjacentTextNodes()` is still present in both paginated and continuous JS.
- Startup still calls it:
  - `reader-paginated.js`: `window.hoshiReader.stabilizeRubyAdjacentTextNodes();`
  - `reader-continuous.js`: `window.hoshiReader.stabilizeRubyAdjacentTextNodes();`
- `normalizeReaderText(parent)` still calls it after local DOM mutations.

If investigating this path, first preserve behavior and add more detailed timing/count logs around:

- number of ruby nodes scanned
- number of adjacent text nodes considered
- number of nodes actually split
- time spent in `normalizeRubyTextNodes`
- time spent in `stabilizeRubyAdjacentTextNodes`

Only change behavior after identifying why the stabilization exists and what visual/selection regression it prevents.

## Suggested Next Trace

Ask the user to run the same open -> page turn -> previous chapter -> next chapter flow and inspect:

- `buildPaginationMetrics took ...` should now be ~1-5ms (was 602-972ms) and log `minScroll=`/`maxScroll=`.
- The gap between a back-tap on page 1 and `previous chapter requested` should be near zero.
- Page turns should land correctly at chapter start/end boundaries (validates the bounds-only metrics in vertical text).
- `initialize normalize ruby text`, `initialize image layout settled`, `initialize buildNodeOffsets`, `reader WebView page finished` remain the big restore costs.

## Practical Next Steps

1. Keep the debug logging local.
2. Get one more trace after the metrics warm-up removal.
3. If still slow, add detailed counts/timing inside the ruby normalization/stabilization functions without changing their behavior.
4. Consider moving setup-script construction off the main thread or caching static script bodies. Current setup script build is still ~100-330ms.
5. Investigate WebView document load separately. Even after JS fixes, `page finished` is still often ~0.6-1.3s.

Do not run full build checks or stage unless the user explicitly asks.
