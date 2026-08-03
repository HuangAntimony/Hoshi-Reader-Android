# Performance Guide

This document records durable performance investigation practices for Hoshi
Reader Android. It is not a task log. Keep entries general, repeatable, and
useful for future Reader, WebView, lookup, Sasayaki, and dictionary performance
work.

## Principles

- Measure before changing code. A fix without a baseline usually only moves the
  bottleneck or hides it.
- Use a real adb target for user-visible latency claims. Emulator evidence is
  useful for development, but real-device timing is the baseline when a user
  reports slowness.
- Keep the measured flow narrow: one book, one chapter, one mode, one setting
  change, one entry/exit path.
- Preserve app data unless the target explicitly requires a disposable device.
- Compare against iOS-visible behavior before changing Reader semantics. Android
  performance fixes should remove unnecessary work, not change the user-visible
  state flow.
- Save artifacts outside the repo in a run-specific temp directory.

## Baseline Measurement

For every performance run, record:

- device serial, model, Android version, package variant, and commit.
- book title, chapter, bookmark/progress, profile, view mode, writing direction,
  e-ink state, Sasayaki state, and whether the run is cold or warm.
- exact start and stop markers.
- run count and outliers.

Reader open latency should not be measured as a vague "tap felt slow" interval.
Use explicit markers:

- tap on the bookshelf item.
- first WebView DevTools target for `appassets.androidplatform.net`.
- Reader restore/ready completion.
- first visible text or stable screenshot, when blank-screen time is the bug.

For WebView-backed Reader flows, `tap -> WebView target` separates native route,
EPUB, and WebView creation time from reader JavaScript time. `WebView target ->
Reader ready` isolates the web asset setup, restore, layout, and highlighting
work.

Use controls before implementation. Common Reader controls:

- paginated, continuous, and VN modes.
- Sasayaki enabled and disabled.
- VN Sasayaki cross-screen merge enabled and disabled.
- e-ink enabled and disabled.
- first entry and repeated open/close entry.
- the same book/chapter with a shorter or longer chapter.

## WebView Timing With CDP

For debuggable builds, Chrome DevTools Protocol is the fastest way to measure
Reader WebView timing and JavaScript hotspots.

```bash
SERIAL=<serial>
PACKAGE=moe.antimony.hoshi.debug
PID="$($ANDROID_HOME/platform-tools/adb -s "$SERIAL" shell pidof "$PACKAGE")"
$ANDROID_HOME/platform-tools/adb -s "$SERIAL" forward \
  tcp:9222 "localabstract:webview_devtools_remote_$PID"
curl http://127.0.0.1:9222/json
```

Attach to the `appassets.androidplatform.net` page target. For Reader ready:

- VN: wait for `window.hoshiReader.ensureReady()`.
- paginated/continuous: wait for `window.hoshiReader.didInitialize`, then verify
  restored progress with `window.hoshiReader.calculateProgress()`.
- blank-screen bugs: pair ready checks with screenshot or pixel evidence, since
  internal readiness may precede visible paint.

Collect `Performance.getMetrics` and, when JavaScript is suspected, a CDP CPU
profile around the focused flow. Report app-owned function names, self time,
and inclusive callers. Avoid treating framework/native frames as root cause
unless the app-owned caller is visible.

## Android-Side Profiling

Use Android-side tools when the WebView target appears late, CDP shows little
app-owned JavaScript CPU, or the user-visible delay is a scheduler, binder,
rendering, or lifecycle issue.

- Use logcat markers to verify ordering across Kotlin, repository, WebView, and
  bridge boundaries.
- Use Simpleperf for CPU-heavy Kotlin, Java, native, or framework execution.
- Use Perfetto for scheduler gaps, binder waits, main-thread stalls, frame
  timing, lock contention, or long render-thread work.
- Use `dumpsys gfxinfo` for quick frame/jank summaries, then Perfetto when root
  cause matters.
- Use memory snapshots or heap dumps for repeated-entry degradation, retained
  WebViews, retained popups, or object growth.

Do not infer a root cause from wall-clock timing alone. A slow flow with low
sampled CPU often means waiting, lifecycle retention, IO, binder, or scheduling,
not an expensive loop.

## Repeated-Entry Slowdowns

For bugs that get worse after leaving and re-entering Reader, measure resource
counts before and after each cycle:

- live WebView DevTools targets.
- app process and renderer process CPU.
- retained WebView/popup/controller objects when heap evidence is available.
- pending callbacks, bridge state, or weak-map bookkeeping that should clear on
  WebView release.

If old WebView targets accumulate, investigate lifecycle release first. Do not
optimize chapter parsing or Reader JavaScript until the old instances are
proven not to be executing.

## Bookshelf Cover Scrolling

Bookshelf cover profiling must distinguish expensive source-cover generation
from cheap display-thumbnail decoding. A warm return to a previously visited
book row may decode a bounded 256/512/768 px derivative if Coil's memory entry
was reclaimed; it must not decode the EPUB's original cover again while the
source fingerprint and derivative cache remain valid.

For a repeatable real-device check:

- record the local book count and whether the derivative cache is cold or warm;
- clear logcat only, never app data, immediately before the measured pass;
- count `HoshiCoverPipeline: source_decode` markers for original-cover work;
- scroll from the first to last book and back, then repeat the same round trip;
- require the second warm round trip to emit no source-decode markers;
- pair the marker count with `dumpsys gfxinfo` or Perfetto frame evidence when
  making a smoothness claim.

Source fingerprints include path, modification time, and length. Changing a
cover must create a new derivative key. Failed source decodes are suppressed for
the process lifetime so a corrupt cover cannot retry on every composition.

## Reader JavaScript Hotspots

Reader web assets are performance-sensitive because they run on the WebView
renderer thread and often manipulate large EPUB DOMs. Common scalable hotspots:

- block x text-node scans.
- cue x screen scans.
- repeated full-array `filter()` over chapter-sized arrays.
- full `TreeWalker` passes inside loops.
- cloning full chapter DOMs to render one page/screen.
- `querySelectorAll()` in repeated paths.
- `getBoundingClientRect()`, `Range.getClientRects()`, `scrollWidth`, or
  `scrollHeight` after DOM writes.

DOM layout reads after writes can force synchronous layout. Prefer cached source
indexes, monotonic cursors, binary search over sorted offsets, and cheap bounds
checks before expensive range geometry. Keep exact geometry checks only where
the cheap check cannot prove the result.

## Tests For Performance Fixes

Performance fixes need regression coverage that protects the scalable property,
not just the final output.

Prefer behavior or operation-count tests that use real Reader APIs, for example:

- a long chapter does not scan every text entry for every block.
- cue merging is near-linear in screens plus cues, not cue x screen.
- viewport text lookup does not rescan every chapter character per split screen.
- a cheap fit check avoids one precise layout measurement per screen.

Avoid source-string tests for ordinary JavaScript refactors. Source-shape tests
are only appropriate for manifest, resources, Gradle, permissions, providers,
or security/build declarations where structured behavior is not practical.

## Verification And Reporting

Before declaring a performance fix complete:

- rerun the focused unit/JS tests and any affected broader tests.
- rebuild and install a debug APK when validating on device.
- rerun the same timing script and controls used for the baseline.
- confirm temporary device changes such as profile settings, sidecar renames,
  adb forwards, or pushed files were restored or cleaned up.
- report artifact paths, run counts, before/after timings, device/build details,
  and the measured hotspot that changed.

When a performance fix changes user-visible behavior, update
`docs/CHANGELOG.md`. When it changes the accepted architecture or future
refactor direction, update `docs/ARCHITECTURE.md` or
`docs/ARCHITECTURE_REFACTORING.md` instead of adding task notes here.
