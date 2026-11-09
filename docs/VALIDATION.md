# Agent Validation and Automation

Reusable commands, measurement methods and verified pitfalls for agent sessions.
Read the sections relevant to the change; test code holds individual cases.

## Choose and run checks

Run focused behavior tests while working, then the applicable completion checks.
All commands below run from the repository root.

| Change | Completion checks |
| --- | --- |
| Documentation only | `git diff --check`; verify referenced paths/anchors and commands against their owners; preserve release headings and issue links when editing changelog |
| App code, data or architecture implementation | `./gradlew test` and `./gradlew assembleDebug` |
| Reader/popup/selection web assets | Also `node --test app/src/test/js/*.test.mjs`; use real WebView evidence for painting, geometry or native gestures |
| Resources, manifest, UI wiring, packaging, framework integration | Also `./gradlew lint` |
| Visible strings | Also `./gradlew :app:testDebugUnitTest --tests moe.antimony.hoshi.LocalizationResourceTest` |
| SDK/ABI/signing/native packaging or release behavior | Also `./gradlew :app:assembleRelease`; inspect affected APK libraries, version, signature and build commit |
| Release workflows or publisher tools | `actionlint`; `python3 -m pip install -r tools/tests/requirements.txt`; `python3 -m unittest discover -s tools/tests -p 'test_*.py'` |
| Transcription runtime packaging | `python3 tools/build-transcription-audio.py --check`; component build/publication procedure below |

JVM filtering uses the variant task, not the `test` aggregate:

```bash
./gradlew :app:testDebugUnitTest --tests moe.antimony.hoshi.features.reader.ReaderPaginationScriptsTest
```

Locate relevant tests with:

```bash
rg --files app/src/test/java app/src/test/js app/src/androidTest/java tools/tests
rg -n 'restoreProgress|selection|Sasayaki' app/src/test/js
```

Kotlin tests generally mirror production packages under `app/src/test/java`;
real Android/WebView tests live under `app/src/androidTest/java`. JS tests execute
the actual reader assets with generated DOM fixtures. CI configuration and build
prerequisites are in `.github/workflows/android-ci.yml` and `app/build.gradle.kts`.
Rendering and latency claims require device evidence.

## Test and fixture pitfalls

- Test code through API, behavior, state-flow and operation-count assertions;
  test manifest/resources/Gradle/provider declarations through parsed structure.
- CI fixtures are constructed by tests or use tracked assets. Ignored `testdata/`
  may contain optional local EPUB, dictionary, font, SRT, M4B and Opus samples;
  inspect availability first.
- If combined release compilation and debug lint fail with a missing generated
  release Hilt Java source inside Kotlin/FIR analysis, finish
  `./gradlew :app:assembleRelease` and then run `./gradlew lint` separately.
  Concurrent Hilt regeneration can invalidate lint's source snapshot; do not
  suppress the detector or treat that analyzer crash as a passing lint run.
- JVM tests replacing `Dispatchers.Main` must cancel **and join** the ViewModel
  scope before `resetMain()`, or late callbacks can fail unrelated tests.
- MockWebServer WebSocket fixtures must answer `onClosing` with
  `webSocket.close(code, reason)`; otherwise shutdown can hang or fail teardown.
- For MockWebServer disconnect tests, bind the server and client DNS to IPv4
  loopback so `localhost` IPv6 fallback cannot mask the intended failure.
- Duplicate English strings with intentionally different contexts need matching
  `comment` attributes on every duplicate in both language resource files.
- Settings state tests use delayed first emissions, read failure/retry and stale
  profile responses, asserting that controls appear after confirmed loading.
- For [error-path contracts](ARCHITECTURE.md#errors-and-diagnostics), inject
  test-owned failures with specific messages and nested causes, and observe both
  visible state and diagnostic events. Assert that cancellation and form validation
  leave runtime history unchanged. Adapter/store examples are `ui/ErrorDetailsTest`
  and `features/diagnostics/NonfatalDiagnosticsTest` in the JVM test tree.

## Device execution without data loss

- Select the intended serial/package explicitly. Preserve installed app data;
  never uninstall, clear data, reset preferences or alter user files for a test.
  A disposable-device exception requires explicit user authorization.
- Do not use `connectedDebugAndroidTest`, `connectedAndroidTest`,
  `installDebugAndroidTest` or other tasks that may reinstall/clear/uninstall app
  data on an existing device. Build APKs separately, install with `adb install -r`,
  then run only a reviewed class/method whose fixtures are isolated.
- Read the chosen test's setup/teardown first. Use scratch stores/media and
  explicitly supplied test endpoints and credentials.
- Unlock the device and keep the screen on for UI/focus tests. Resolve labels
  from resources in the user's current language.
- SAF imports go through DocumentsUI or an authorized `content://` URI. Samples
  may be pushed to Downloads, then selected through DocumentsUI to obtain a grant.

Example for the generated-content-only viewport test; replace the serial after
checking `adb devices -l`. Installation must use the same application ID/signing
identity as the existing debug app; stop on a mismatch instead of uninstalling.

```bash
./gradlew assembleDebug assembleDebugAndroidTest
hoshi_serial='REPLACE_WITH_DEVICE_SERIAL'
adb -s "$hoshi_serial" install -r app/build/outputs/apk/debug/app-debug.apk
adb -s "$hoshi_serial" install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb -s "$hoshi_serial" shell am instrument -w -r \
  -e class moe.antimony.hoshi.features.reader.ReaderViewportWebViewTest \
  moe.antimony.hoshi.debug.test/androidx.test.runner.AndroidJUnitRunner
```

`am instrument` may return shell status 0 on test failure. Require the final
`OK (N tests)` and inspect `FAILURES`, `INSTRUMENTATION_FAILED` and stack traces.
Report tested API/WebView versions, commands, results, artifact paths and any
unexecuted checks in the current task.

Anki client checks: `AnkiClientProviderTest` uses in-memory providers and
`AnkiClientSelectionTest` uses an in-memory selection; neither changes a user's
Anki collection or saved Hoshi settings. `AnkiKuma3SmokeTest` requires an installed,
initialized kuma3 release and its database permission granted to the Hoshi test
target. Opt in explicitly with `-e kuma3Smoke true` alongside its class name in
the instrumentation command. It uses isolated Hoshi settings, creates a uniquely
tagged note, checks fetching/mining/duplicates/browsing, saves `cache/kuma3-smoke.png`
under the target app, and deletes only its tagged note in `finally`; it does not
sign in or synchronize.

### Reader page measurements

`ReaderPageMeasurementWebViewTest` uses generated multi-spine text/image books
and scratch font/book/cache repositories. It compares attached hidden measurement
with actual visible page turns in both writing directions and paragraph modes,
and checks Unicode/space paths, in-flight cancellation, cache reopen plus font,
viewport and content invalidation. The font fixture also verifies one font request
across consecutive chapters and that a new measurement uses a replaced font file
at the same path; its scratch copy uses device Roboto and Droid Sans Mono. Run this
class through the data-preserving instrumentation procedure above. Its
`HoshiPageCounts` log reports uncached fixture measurement and JS/native page-turn
round trips, not painting latency or cold-storage performance. Keep all WebView
creation, use and destruction explicitly on Main, including after repository IO;
Compose test continuation interceptors do not establish Android Looper affinity.
The leading-image fixture checks text labels and chapter-boundary progress against
physical page turns in all four paginated layouts. To verify persistence beyond
one process, run only `diskCacheSurvivesProcessRestartWithoutStartingMeasurement`
twice: first with `-e pageCacheRestart capture -e pageCacheRun RUN_ID`, then with
`-e pageCacheRestart verify -e pageCacheRun RUN_ID` using the same alphanumeric or
hyphenated ID. Verification requires a different PID, unchanged cache timestamp
and no hidden WebView while visible restore remains pending and foreground activity
blocks measurement. This opt-in
case uses only `cache/page-cache-restart-RUN_ID` and removes it after verification.
Repeated idle callbacks in hidden WebViews can dominate total counting time.
Measure both whole-book throughput and foreground responsiveness after changing
batch scheduling; page-turn latency alone is insufficient. Keep the native
activity gate on all continuation paths, including compatibility fallbacks.

`ReaderPagePerformanceTest` is an opt-in paired comparison of the paginated web
runtime before and after page-count support. Copy the baseline revision's
`reader/reader-paginated.js` into the debug app's
`cache/reader-page-performance-baseline/reader-paginated.js` using `git show` and
`adb shell run-as`, then run this class with `-e pagePerformance true`. The default
fixture has six generated 800-paragraph spines; `-e paragraphs N` and
`-e rounds N` adjust its size and repetition count. Use `-e vertical true/false`,
`-e paragraph true/false` and `-e variants baseline,disabled,ready,measuring`
to focus a follow-up comparison. The first round is warmup;
subsequent rounds rotate baseline, reporting disabled, page reporting ready and
concurrent uncached measurement. The measuring case uses the production activity
scheduler, allows cold work to start before chapter navigation, then reports
foreground restore and page-turn activity. Restore cancels and destroys unfinished
measurement before loading visible content; page turns pause/resume the same
hidden document. The load timer includes cancellation cost. Pair it with
`ReaderPageMeasurementWebViewTest.foregroundActivityPausesAnAttachedMeasurementAndResumesIt`
to verify that pausing retains and eventually completes the attached document.
With `-e variants measuring -e rounds 1 -e idleCompletion true`, additionally
interrupt three observed geometry batches, assert no more batches execute during
the foreground quiet period, and wait for all six spines to complete; the report
includes interruption response times and completion time including those pauses,
plus hidden JS phase timings to distinguish scans from native document layout.
All cases use the same current APK and other
web assets, so this isolates the changed pagination runtime and measurement
contention, not bookshelf routing, complete Compose recomposition or cache IO.

The test writes `cache/reader-page-performance.json` with chapter load-to-restore,
load-to-visual-state, geometry builds before restore, per-turn JS/native response
and visual-state latency, frame gaps and whether a measurement job was active
(including a deliberately paused job). A visual-state callback means the DOM is ready for drawing, not that physical display
refresh has completed. Compare repeated pairs and the active-measurement subset;
idle turns after measurement finishes can hide stalls in overall medians.
The visible fixture uses `evaluateReaderSetupScript` and delivers the ready cue
snapshot after visual restore, matching `ReaderChapterWebView`; `postRestoreCueMs`
reports this handoff separately. Direct script evaluation alone misses redundant
post-restore cue installation and its text-index/geometry invalidation.
For a reported book, `-e bookFixture true -e fontFixture true` uses the isolated
EPUB/Klee One files described below; `-e openingSpine N -e navigationSpine N`
select long text chapters with at least 26 reachable pages. Match `-e fontSize N`,
`-e horizontalPadding N` and `-e viewportHeight N` as well as writing/paragraph
mode. This never navigates the user's live book or writes its progress.
`-e progress 0.5` restores both fixture chapters midway through their text to
exercise character-target lookup separately from the chapter-start fast path;
the report records `initialProgress` for every sample. Keep the same progress
and enough remaining pages for the 25-turn comparison in both versions.
`cache/reader-page-index-performance.json` separately measures native page-index
lookup scaling. Preserve the raw reports with the task's baseline SHA and tested
WebView version; do not treat the earlier single-layout timing log as an A/B test.

`ReaderPageThroughputTest` measures uninterrupted whole-book completion separately
from foreground latency. Put the comparison revision's `reader-paginated.js` at
`cache/reader-page-throughput/baseline.js`, then run with `-e pageThroughput true`.
It uses six generated spines by default. `-e bookFixture true` reads only an isolated
EPUB copy at `cache/reader-page-throughput/book.epub`; extraction uses a unique
scratch directory removed after the test. Never point this fixture at the library
or write its metadata, bookmarks or settings. Keep personal EPUBs out of Git.
`-e rounds N`, `-e paragraph true/false`, `-e vertical true/false` and `-e fontSize N`
control the comparison. `-e variants current,fallback` exercises `MessageChannel`
with the scheduler API disabled in the test-owned hidden document.
`cache/reader-page-throughput-results.json` records total/per-spine elapsed time,
continuation wait time, DOM preparation/fragmentation, metric work and font request
counts; the test requires every page-start offset
to match across variants. Rotate repeated runs and include any compatibility path
before claiming improved cold-count performance.
`ReaderPageMeasurementWebViewTest` also compares batched text geometry and page
starts against individual Range reads in the same real document, before checking
each visible page turn. Keep this oracle when changing geometry batching; equal
whole-book page totals alone do not prove that node bounds or page offsets agree.
Changes to text-node splitting also require per-code-point rectangle comparisons:
identical page starts can hide changed line breaks within a page. Run those
diagnostic scans separately from timing comparisons, since their geometry reads
distort cold-count latency.
When native scheduling/resource loading changes, capture offsets with the old APK
using `-e variants current -e captureReference true`, then run the new APK with
`-e variants current -e compareReference true` and identical fixture/layout arguments.
`cache/reader-page-throughput/reference-pages.json` holds the exact per-spine arrays;
keep the matching book fingerprint, settings, viewport and APK revision with the
task evidence, and recapture when any input changes. Comparing only JS variants
in the new APK does not exercise the previous native implementation.

For a reported slow book, match the actual font, weight, padding and viewport;
publisher-font results do not represent a downloaded-font profile. The optional
`-e fontFixture true` case reads test-owned `KleeOne-Regular.ttf` and
`KleeOne-SemiBold.ttf` copies from the same fixture directory and selects weight 600.
`-e horizontalPadding N` and `-e viewportHeight N` match the reader's CSS dimensions.
The `unpaced` variant disables foreground quiet periods and JS yields **only in the
isolated test**, bounding the potential gain from removing scheduling safeguards.
It is not a usable reading policy or evidence that foreground responsiveness is safe.
Each sample uses distinct font URLs to avoid accidental cross-variant renderer-cache
reuse. Compare all page starts and report the actual profile/viewport alongside timing.
Long batches of WebView tests can heat the device enough to change both native
layout and JS timings. Record `adb shell dumpsys thermalservice` around throughput
comparisons; let the device return to the same thermal status before comparing
runs, and retain throttled samples separately rather than attributing their slowdown
to a code change. Keep the device unlocked during instrumentation: cooling with
the screen off can activate the keyguard and stall attached WebView/Compose work;
discard that run and resume only after the user unlocks the device.

### Dictionary language fixtures

`DictionaryLanguagesNativeTest` imports generated dictionaries into JUnit temporary
folders and destroys every native session. It covers Chinese radical/pinyin handling,
Korean POS-filtered deinflection and Japanese/English regressions without installed
user data. The test uses no Android APIs and can also run with JUnitCore against a
host-built bridge JNI library; this verifies native behavior, not Android packaging
or rendering. `ProfileLanguagesViewTest` uses a scratch profile repository, and
`ContentLanguageSelectionWebViewTest` checks all four languages in generated reader
documents and real popup iframes using the external asset route. Run these classes
explicitly using the data-preserving installation procedure above.

For a paired Japanese lookup performance check, run
`JapaneseLookupPerformanceTest` and `ContentLanguageSelectionWebViewTest` with
`-e lookupPerformance true`. Before the WebView comparison, copy the baseline
revision's `language-ja.js`, `selection-ja.js` and `selection.js` into the debug app's
`cache/lookup-performance-baseline/` using `adb shell run-as`; these are test-owned
files, not installed dictionaries or settings. The WebView fixture alternates baseline
and current scripts in actual iframes, including supplementary kanji and both scan
toggle values. The native fixture imports a generated 10,005-entry dictionary and
compares the former forced-rebuild query sequence with session reuse. It checks
results and counts native session/style calls; all native handles are released.
Both fixtures write `lookup-performance-{web,native}.json` to the debug app's cache.
Compare paired samples rather than single runs. These measure DOM selection and
repository lookup separately, not end-to-end popup painting latency.

### Local word-audio SQLite fixtures

`LocalAudioBindingTest` exercises Copy/Link publication, migration, retained-copy
cleanup, failed/cancelled replacement and generation checks through an injected
storage boundary. `LocalAudioSettingsViewModelTest` checks state and recoverable
errors. `LocalAudioImportEntryDeviceTest` renders the real controls with a scratch
ViewModel and tests import visibility across valid copies/links, removal, link
failure/recovery and source-setting failures. It saves the displayed states as
`cache/local-audio-entry-*.png` without loading installed settings.
The standalone C++ fixture uses the same pinned SQLite as Android:

```bash
cmake -S tools/tests/local_audio_native -B /tmp/hoshi-local-audio-host -DCMAKE_BUILD_TYPE=Release
cmake --build /tmp/hoshi-local-audio-host -j 4
ctest --test-dir /tmp/hoshi-local-audio-host --output-on-failure
# Optional ignored, read-only real corpus; prints parity, I/O and timing evidence:
/tmp/hoshi-local-audio-host/local_audio_native_test testdata/android.db
```

It covers descriptor ownership, Unicode/nulls, OR semantics, blobs, prepared
statement reuse, bounded slices and offsets above 4 GiB, short reads, rejected
pipes/WAL/schema and source mutation. Host timings do not establish Android/SAF
latency. Run `LocalAudioNativeDeviceTest` explicitly on device after the
`install -r` procedure above; it creates only synthetic cache fixtures.

For an optional real-database comparison, first copy a test-owned sample into a
unique device Downloads filename and launch debug-only
`moe.antimony.hoshi.features.audio.LocalAudioValidationActivity`. Select that file
through DocumentsUI; the Activity persists only the fixture grant and URI under
`cache/local-audio-validation/`. Stop the target process after selection to discard
temporary grants. Run these methods explicitly, each with
`-e localAudioRealDatabase true`; never point the fixture at a production binding:

1. `LocalAudioPerformanceDeviceTest#compareFrameworkAndFdQueriesAndVerifyRealCopyLinkModes`
2. `LocalAudioSafDeviceTest#prepareLinkForProcessRestart`
3. `LocalAudioSafDeviceTest#reopenLinkAndReleaseFixtureGrant`
4. `LocalAudioSafDeviceTest#revokedDocumentCannotReopenAfterProcessRestart`

Use the full `moe.antimony.hoshi.features.audio.` class prefix. Stop the target
process between the three SAF phases; they assert different PIDs. The benchmark
requires enough free space for one temporary full-size copy and cleans it in
`finally`. It tests actual Copy/Link switching in a scratch repository and compares
framework SQLite opens, fresh FD sessions and a reused FD session with alternating
query order and equal rows/blob bytes. Results and counters go to
`cache/local-audio-validation/performance.json`. These are warm filesystem-cache
measurements, not cold-storage latency or end-to-end playback. The SAF phases
preserve a scratch binding across process death, release only the exact grant
newly acquired by the fixture, verify access denial, and remove scratch state.
After revocation, launch the same Activity with `--ez verifyOwnership true` and
select the fixture document again. This time the repository itself takes the
persistent grant from the temporary picker result and releases it on unlink;
`ownership-result.json` must report `passed`, with read access true after Link and
false after unlink. An already persisted grant is skipped, never released.
The Activity also accepts `--ez showImportOptions true` to render the real
Copy/Link chooser without opening a picker or changing installed audio settings;
all three callbacks only close the fixture. Use it for dialog layout inspection.
Android may leave an empty journal after framework fixture creation; synthetic
fixtures explicitly use `journal_mode=DELETE` and check the directory before JNI
opens it, so their no-sidecar assertion measures only the FD backend.
Remove only the uploaded test-owned document after all phases; leave production
settings, audio bindings, source documents and unrelated URI grants untouched.

### App language fixtures

`AppLanguageDeviceTest` and `AppLanguageNotificationDeviceTest` render language
settings in a debug-only Activity and restore the original app language and
notification-channel metadata.

### Diagnostic history fixtures

`DiagnosticsDeviceTest` uses synthetic history and independently encoded native
tombstone bytes to check crash/process-exit categories, native stack details and
export contents. It preserves separate local/system crash records without
writing installed history or deliberately crashing a process; its screenshot is
saved to target cache `diagnostics-fixture.png`.

## Drive metadata timeout isolation

Run `python3 tools/drive-metadata-isolation-fixture.py` on the host and
`adb -s "$hoshi_serial" reverse tcp:18766 tcp:18766`, then explicitly run
`moe.antimony.hoshi.features.sync.DriveMetadataIsolationDeviceTest` with
`-e driveIsolationUrl http://127.0.0.1:18766`. Build/install debug and test APKs
using the data-preserving procedure above. Restart the fixture server for each run.
The opt-in test owns scratch stores/settings and generated files (one 60 MiB,
seven 24 MiB); no production credentials or library files are used. It waits for
all eight server sessions to receive payload bytes before arming a real 60-second
metadata read timeout, then drops subsequent metadata responses to exhaust retries.
Assertions require all eight payloads to advance and remain active after failure,
then finish and publish references after recovery. Expect roughly two minutes.
Stop the fixture and remove only this reverse mapping after testing.

## WebView and UI automation pitfalls

- `uiautomator dump` can report `null root node` while a recreated Compose screen
  is visible, and leave a previous XML dump untouched. Use a fresh destination,
  check dump output as well as exit status, and confirm state with Compose tests
  or a current screenshot; an old popup tree is not evidence of failed navigation.

- CDP discovery can label the Dictionary's `loadDataWithBaseURL` host as
  `about:blank` even while its iframe is rendered. Inspect the intended app
  process's targets and confirm `location.href` or `document.baseURI` in the page;
  filtering only `/json` URLs for `appassets.androidplatform.net` can miss it.

- An active CDP-injected touch sequence can suppress native touchscreen input.
  Use one input stream for controlled multi-pointer tests and always finish it
  with `touchEnd` or `touchCancel` before native input or disconnecting the CDP
  session. Disconnecting mid-gesture can leave native input blocked after the
  renderer touch is cleared. Prefer `LookupPopupGestureWebViewTest`'s isolated
  native-event fixture for popup gesture regressions. To test cross-document
  touch routing, start a parent touch, add and move an iframe touch with
  `touchMove`, then end the sequence. The iframe pointer can be non-primary while
  its Touch Events stay in the parent document. Run real-device gestures as a
  separate check; do not infer native behavior from suppressed input.

- `BookshelfImportProgressTest` renders the real shelf with a scratch `SyncStorage`
  and generated books, without accessing the installed library or Drive account.
  It checks standard/E-ink import progress across the real reload timer and saves
  screenshots under the debug app's `cache/import-progress-evidence/` directory.

- Material 3 `ModalBottomSheet` applies its caller modifier before the internal
  sheet offset; an outer `border` can remain on the scrim at the unshifted height.
  Check short and shrinking E-ink sheets, not only full-height lists.

- Cold launch normally lands on Bookshelf; open a book before testing Reader.
  Single-tap text opens lookup; long press tests native selection/highlights.
- Standalone reader fixtures must inject measured
  `readerViewportCssLayout(...).cssVariables()` and wait for `HoshiReaderRestore`.
  Shell injection alone can leave generated content with an incorrect viewport.
- Picture fixtures must also wait for visible pixels after the WebView visual-state
  callback. Correct image bounds and a ready bridge can precede compositor paint;
  an immediate `WebView.draw` or screenshot may still be blank. Use a bounded
  screenshot check against the current image before accepting painting or centering
  evidence; a nonblank screenshot can still contain the previous chapter.
- Use screenshots/recordings for painting, menu visibility and handle movement.
  CSS Custom Highlights can clip vertical glyph edges despite a correct DOM Range;
  follow the [narration-highlighting prohibition](ARCHITECTURE.md#prohibited-narration-highlighting-changes)
  and the glyph comparison below.
- `ReaderParagraphWebViewTest` uses generated documents to check paragraph-only
  sentence splitting, long-block fragments, ruby/offset integrity and reveal in
  both writing directions, including late-chapter pages with zero page padding.
  Centering checks cover both axes and writing directions, unequal explicit lines,
  natural and explicit wrapping, short continuations, images and page padding.
  Spacing paragraphs must not add blank pages or displace fragment-anchor jumps;
  publisher ornaments, media and borders must remain visible. Long-page reveal
  bounds must match the actual first and last characters on each page.
  Native page-turn tests require the progress calculation to finish before the first
  reveal frame and publish the same progress to display and bookmark callbacks.
  With narration cues installed and layout measured, subsequent page turns must
  reuse text bounds without walking or measuring the whole chapter again;
  progress must still measure a text node that crosses the viewport boundary.
  Paragraph E-ink switches must reload at the displayed character position with
  cue text and saved highlights intact in both directions. Appearance refresh
  must not perform bulk DOM wrapping on the old document.
  Blank-tap advance must keep the new reveal running when the DOM click arrives
  after native navigation; a separate tap must still finish it without advancing.
  Fractional WebView scroll coordinates must keep the first text node and cue on
  the current page and exclude the next page. It freezes only the reveal clock after restore and
  compares live DOM/geometry plus actual ruby pixels before/after reveal. Gaiji
  images and generated fallbacks must follow the text boundary, retain geometry,
  and become visible on completion, cancellation or page turn; other pages stay
  untouched. Evidence is written under the target app's `cache/paragraph-evidence/`.
  Narration fixtures install both Sasayaki and TTS chapter cues before restore,
  then switch cues and follow across pages while reveal runs: the same animation
  must continue to completion without replacing text nodes. Cross-chapter restore
  still animates. On the real reader, verify that opening a book with TTS enabled
  but never started installs no TTS wrappers; first playback installs the chapter
  once, and subsequent cue updates preserve node identity. Compare highlighted
  and plain glyph silhouettes using the procedure below.
  It never accesses
  books or saved settings. `SasayakiPagePlaybackDeviceTest` uses a scratch audio
  directory and silent WAV with the real Media3 controller; it detaches Reader
  callbacks to check page-end stopping, overlap, delay, backward/empty pages and
  disabling without accessing an application service session. On the real reader,
  verify that Next Cue crossing a page refreshes its stop boundary while preserving
  playback state and position, in both normal and E-ink modes; playback must stop
  at the destination page end without another manual page turn. Switching between
  ordinary pagination and paragraph mode during playback must preserve following,
  including when the restored page has no cues or lies outside the current audio
  range. Use the Contents sheet to jump to another chapter and then turn into its
  text pages; verify that new-page cues replace the previous range. An explicit
  turn to a cue-less page must still pause. From that page,
  Play and Next Cue must resume following and establish the destination page stop.
  After a page-end pause, wait beyond several playback ticks and confirm that no
  next-page cue is dispatched; Play must then continue and stop at the next
  followed page end.
  Keep restored-page animation and backward-page behavior
  distinct.
- Native-handle fixtures need real long-press/drag events with both
  `SOURCE_TOUCHSCREEN` and `TOOL_TYPE_FINGER`. Recovery presses need tap timeout plus
  long-press timeout and a release margin; observe after that deadline.
- Geometry polling and JDWP method tracing can mask or stall gesture bugs.
  Record endpoints/text in memory without layout reads, collect after the gesture,
  and retain an uninstrumented recording as the control.
- Remove fixture WebViews from their parent before destroying them. Count retained
  DevTools targets across repeated entry/exit before attributing slowdown to parsing.
- Browser OAuth activity tests use the debug caller fixture and require window
  focus. External-process browser fixtures use framework-only Java to run without
  the target App's dependencies. Test tokens/stores remain isolated from login.

### Narration highlight glyph integrity

- On SM-G9860 / Android 13 / WebView 155.0.8059.43, CSS Custom Highlight narration
  coloring visibly clipped left-side strokes of vertical glyphs including `駆`,
  `私` and `追`. Removing only the highlight restored the complete glyphs;
  restoring it reproduced the clipping. DOM, Range rectangles and scroll position
  remained identical. This is a paint regression, not evidence of missing text or
  a pagination error.
- When checking TTS/Sasayaki rendering, pause on the same sentence and compare
  highlighted and unhighlighted screenshots with identical font, size, writing
  direction and position. Inspect whole glyph silhouettes and ruby, including
  edges extending beyond nominal character boxes. Wait for compositor paint and
  restore the original highlighting after inspection.
- Checking only cue strings, DOM identity, geometry or colored-pixel counts misses
  this failure. A test that sees a blue background does not prove that its kanji
  remain intact. Keep per-run screenshots outside the repository; renderer changes
  still require the explicit approval specified in ARCHITECTURE.

## Performance baseline and diagnosis

Measure before changing code. Keep the same device/build, book/chapter/position,
profile, mode, writing direction, theme, playback state and cold/warm condition.
Record explicit start/end markers, repeated run count, outliers and artifacts in
a run-specific temp directory. User-reported device latency needs evidence on
that device class; debug/emulator results describe only their tested conditions.

For Reader opening, separate `tap -> WebView target -> restore/ready -> visible
paint`. The first interval covers native routing, EPUB work and WebView creation;
the second covers web setup/layout/restore. A ready flag does not prove paint.
Compare relevant controls, such as first/repeated entry, short/long chapters,
Sasayaki on/off and paragraph mode on/off.

For a debug build, or a release with Diagnostics → WebView debugging enabled,
locate its WebView through CDP. The release toggle is off by default and remembers
the chosen value. Enable it before investigating an intermittent Reader problem
so opening Diagnostics does not replace the failing reading session. Toggling
debugging itself does not reload WebViews. Use the intended package in the command:

```bash
hoshi_serial='REPLACE_WITH_DEVICE_SERIAL'
hoshi_package=moe.antimony.hoshi.debug
hoshi_pid="$(adb -s "$hoshi_serial" shell pidof "$hoshi_package" | tr -d '\r')"
adb -s "$hoshi_serial" forward tcp:9222 "localabstract:webview_devtools_remote_$hoshi_pid"
curl --fail http://127.0.0.1:9222/json
```

Select the intended `appassets.androidplatform.net` target. Both runtimes expose
`didInitialize`; wait for the restore callback before claiming the document is ready.
Check `calculateProgress()` after restore and pair it with screenshots for blank
or incorrect content. Collect `Performance.getMetrics` and a focused CDP CPU
profile; identify app-owned callers and self/inclusive time.
Measure latency again without tracing: detailed DevTools timeline events can
inflate native-to-JavaScript callback costs, especially filtered TreeWalker
scans. Use separate CPU samples for attribution and compare complete pagination
metrics and raw/matchable node offsets when optimizing chapter traversal.
For initialization ordering changes, run `ReaderDocumentPreparationWebViewTest`
with and without the document-start hook, plus paragraph/image geometry checks.
Use a separate timeline trace to distinguish full `Layout` events from style
updates while the root is hidden. A chapter without fragment breaks should not
need another full layout just to detect that there are no breaks; chapters with
real continuation fragments can legitimately need another layout after wrapping.
For paragraph/Sasayaki page-turn jank, measure `animateText`, `calculateProgress`
and `pageSasayakiCues` separately on the same pages with the same installed cues.
Pair those timings with frame gaps and native-swipe recordings; faster animation
setup alone does not establish smooth playback or correct visible text.
Include previously unhighlighted cues: Blink retains an inline paint box after
its first background highlight, so replaying only warmed cues can hide an initial
full-chapter layout. Trace first activation separately and compare glyph pixels
and text bounds when changing cue layout preparation.
For sparse or variable-frame-rate device recordings, inspect decoded source
frames with their presentation timestamps. Resampling to a fixed frame rate can
omit the short final frame that first shows the page; confirm visible-paint timing
against the original frames, not only an `fps`-filtered contact sheet. Software
recordings do not measure an E-ink panel's physical refresh completion.

| Signal | Next measurement |
| --- | --- |
| Late WebView target, low JS CPU | Kotlin/repository log markers; Perfetto for scheduler, binder, locks and main/render-thread waits |
| CPU-heavy Kotlin/native path | Simpleperf sampling; distinguish app callers from framework frames |
| Jank | `dumpsys gfxinfo` for repeated identical swipes; Perfetto separately for diagnosis so profiling overhead does not contaminate frame comparisons |
| Worse after repeated entry | Live WebView targets, process CPU, heap retention and pending callbacks before/after each cycle |
| Reader JS scales badly | Look for nested block×text/cue×screen scans and layout reads after DOM writes; protect improvements with operation-count tests |
| Bookshelf cover jank | Separate original `HoshiCoverPipeline: source_decode` from cached thumbnail decode; a second unchanged warm round trip should not decode originals |
| Blank bookshelf at startup | Compare the same signed release and library over repeated force-stop launches; use recording frame timestamps for shelf chrome → first books → covers. `am start -W` measures Activity display, not bookshelf data readiness. Measure repository stages and concurrent sync preparation separately on an isolated sidecar copy; do not compare debug/JIT timings directly with release latency. |
| Statistics scrolling | Keep period/book expansion/heatmap/font state fixed; distinguish viewport drawing from repeated offscreen card recomposition |
| Transcription slowness | Same compressed input and resume position; measure decoding, model load, inference and saving/alignment, plus thermal state, RSS/PSS and CPU/wall time |

Transcription stages can overlap; do not sum them or call total-minus-inference
time “decoder time.” Predecoded PCM benchmarks omit real pipeline costs. Extra
threads need full-pipeline evidence plus token/timestamp parity and bounded
read-ahead. Low sampled CPU alone does not locate a waiting bottleneck.

After a change, rerun the same baseline/control and relevant behavior tests.
Report before/after measurements and scope of the claim. Remove temporary adb
forwards (`adb -s "$hoshi_serial" forward --remove tcp:9222`) and restore only
task-owned device changes; keep logs/recordings outside the repository.

`BookshelfLargeLibraryTest` generates 600 isolated book sidecars and private
settings/profile stores, then measures entry discovery, reading states, covers,
missing sync-record preparation and an existing-record preparation concurrent
with loading. Run its explicit class with the data-preserving instrumentation
procedure above; collect `HoshiShelfPerf` logcat markers. It exercises the real
settings/statistics/storage owners without accessing the installed library,
account or network. Fixture generation is excluded, filesystem caches are warm,
and these repository timings do not establish release Activity cold-start time.
`BookshelfReaderReturnTest` renders a generated 600-entry shelf in ordinary and
E-ink modes, waits on a real scratch bookmark save, and checks visible progress
and recent order without a second whole-library load. Screenshots are written
to target cache `reader-return-evidence/`; its `HoshiShelfReturn` timing starts
when the save is released and includes test polling/render assertions, not the
full Reader navigation or a physical E-ink panel refresh.

For large publisher CSS, run `ReaderCssPerformanceDeviceTest` with the explicit
instrumentation procedure above. It checks generated stylesheet output and a
generous latency budget without opening an Activity or accessing user data.
Run this on Android: host JVM timings do not expose the repeated whole-input ICU
initialization caused by Kotlin's transforming `Regex.replace` match iteration.

`ReaderDocumentPreparationWebViewTest` exercises document-start layout deferral
and its no-hook fallback with generated HTML/XHTML, all three modes and both
writing directions. It checks hidden initial geometry, restored text/progress and
painted image pixels without using books or preferences. The deferred case needs
`DOCUMENT_START_SCRIPT` support; the fallback runs independently.

## Specialized build and service setup

### Google Drive browser authentication

Copy `secrets.properties.example` to ignored `secrets.properties`; set
`HOSHI_GOOGLE_CLIENT_ID_DEBUG` / `HOSHI_GOOGLE_CLIENT_ID_RELEASE`. Environment
values override the file. Android clients use package/signing registration in
the same Google Cloud project as iOS, with Custom URI scheme enabled; no client
secret is used. Release CI requires its release ID; ordinary unit/lint CI does
not. Auth changes must check generated BuildConfig and merged redirect schemes,
including empty environment values. Protocol and activity tests live under
`features/sync` in the JVM/device test trees.

### Drive payload transfer fixtures

`DriveFileTransferTest` covers resumable protocol recovery and persistence with
MockWebServer. `BookTransferSchedulerTest` and `SyncTransferTrackerTest` cover
concurrency and monotonic-clock effective speed measurement without real network timing.
`SyncQueueViewTest` renders generated queue state without accessing the installed
library or Drive account. It checks settings and queue loading cues, directional
summaries, ETA labels, grouping, error details and scroll retention through row
changes and restore. It also checks the full-page E-ink background and static
loading cue as the queue shrinks. Screenshots are in target cache as
`sync-queue-fixture.png` and `sync-queue-page-*.png`.

For device restart recovery, build/install the debug and androidTest APKs under
the signature/data-protection rules above. Start
`python3 tools/drive-transfer-fixture.py`, then use
`adb -s "$hoshi_serial" reverse tcp:18765 tcp:18765`. Run these methods separately
with `am instrument -w -r -e class <class>#<method> -e driveFixtureUrl
http://127.0.0.1:18765 moe.antimony.hoshi.debug.test/androidx.test.runner.AndroidJUnitRunner`:

1. `moe.antimony.hoshi.features.sync.DriveTransferDeviceTest#prepareInterruptedUpload`
   waits for a confirmed chunk and a deliberately dropped subsequent connection.
2. Call `curl -X POST http://127.0.0.1:18765/allow-resume`, then run
   `DriveTransferDeviceTest#resumeAfterProcessRestartAndDownload` with the same
   full class package. It checks a different PID, resumes the saved session,
   compares downloaded SHA-256, and removes only its generated cache fixture.

The loopback server throttles payload reads/writes and exposes `/stats` to verify
that the new process queried the saved session and resumed at the acknowledged
byte instead of creating another upload. Require `OK (1 test)` for each method;
Android instrumentation's final code alone does not distinguish assertion
failures. Remove the task-owned reverse with
`adb -s "$hoshi_serial" reverse --remove tcp:18765` and stop the server afterward.
For a controlled before/after comparison, start the fixture with
`--benchmark --response-delay 4` and run
`DriveTransferDeviceTest#measureUploadThroughputAndConfirmationSpeed` on the old
and new debug APKs with the same test APK and loopback argument. The method
reports elapsed seconds and zero-speed samples while awaiting acknowledgement;
`-e expectEffectiveSpeed true` asserts no such zero samples for the new build.
It uploads the same generated 12 MiB file each time; `/stats` reports payload
request counts and ranges. Compare total duration, not the UI's estimated speed.

These tests do not establish real Google account throughput or proxy behavior.

### Transcription components and benchmarks

- `tools/build-transcription-audio.py --help` owns build options. After decoder or
  CMake changes, build supported ABIs using the pinned NDK/CMake from CI and commit
  `app/src/main/assets/transcription-audio-runtime.json` with the source change.
- `tools/publish-transcription-components.py --help` owns publication options.
  Publish binaries, license notice and generated source archive to an immutable
  component prerelease before shipping its catalog; use `--remote-check` to verify
  deployed hashes. Existing component releases are immutable.
- Nakuru device tests require already verified resources; they do not download
  them. `NakuruDeviceTest` takes `nakuruClip` (app-readable 16 kHz PCM16 mono WAV).
  `TranscriptionBenchmarkDeviceTest#benchmarkExcerpt` takes `clip` (at most 300 s)
  and `preset` (1/2/3). Read their assertions/output handling before running;
  timing and valid tokens do not establish recognition accuracy.
- Runtime download/permission failure injection uses scratch resources.

### TTS playback continuity

`TtsQueuePlaybackDeviceTest` uses generated PCM, isolated Media3 players and
in-memory preferences. Run it with the data-preserving instrumentation procedure
above. It compares the former per-unit reset sequence with the production queue,
including quote/chapter boundaries, multiple audio fragments and deliberately slow
Reader presentation. Require complete ordered units, one initial prepare and no
intermediate idle/buffering events for the ready queue. Logs use `HoshiTtsQueue`.
Elapsed duration is secondary evidence; repeat on the same device and subtract
the known PCM duration only as aggregate overhead, not a measured acoustic gap.
`onAudioPositionAdvancing` supplies a wall-clock timestamp; translate it to the
monotonic clock before comparing elapsed time. This fixture isolates app transport
cost and does not measure provider silence, synthesis/network stalls or every codec.
Pair it with `TtsImageHoldDeviceTest`, `TtsLookupPauseDeviceTest` and real WebView
highlight tests when changing presentation or cancellation boundaries.

`TtsConcurrencyBenchmarkDeviceTest#compareMicrosoftTranslatorConcurrency` is an
opt-in live provider comparison: add `-e ttsConcurrencyBenchmark true` to the
explicit instrumentation command. It uses eight public Japanese fragments by default; add
`-e ttsBenchmarkProfile mixed` for 24 short/medium/long fragments. Both use shared
warm authorization/catalogs, isolated engines and a unique temporary cache directory. The 1/2/3/4 then 4/3/2/1 order helps expose time/order variation;
`HoshiTtsConcurrency` reports batch time, individual request time, audio duration
and failures. `estimatedWait2xMs` models ordered playback from the first ready item
at 2× speed; it excludes decoder/Reader costs and is not an acoustic measurement.
It stops on failure rather than amplifying service errors with benchmark retries.
This measures provider throughput, not acoustic gaps or the production scheduler;
pair it with `TtsContinuousPlaybackDeviceTest#microsoftTranslatorPlaybackCompletesWithTwentyPrefetchedUnits`
for real playback, and deterministic controller/repository tests for concurrency
limits, ordered publication, cancellation and injected failures. Do not infer a
universal optimum or service rate limit from one device/network session.

`TtsEmptyAudioDeviceTest#translatorEmptyNarrationPreservesDialoguePlaybackReplayAndExport`
is an opt-in live Translator regression: pass `-e ttsEmptyAudioLive true` with the
data-preserving instrumentation procedure. It uses in-memory voice settings,
a unique scratch directory and an isolated player, verifies empty narration
plus audible dialogue, replay, following speech and the shared preview/Anki
export file, and never adds a card. Logs use `HoshiTtsEmptyAudio`.

### External TTS and release automation

- `CustomTtsDeviceTest` opts into supplied `vitsUrl` or `openAiUrl`/`openAiModel`
  endpoints using public samples and test-owned stores. Read required arguments
  in the test. VITS expects exactly
  `Content-Type: application/json`, without a charset suffix.
- Mocked protocols cover repeatable provider failures; live network tests need
  explicit endpoints/credentials and cannot be treated as deterministic CI.
- Publisher fixtures in `tools/tests` cover stale commits, draft/corrupt uploads
  and owned-preview cleanup. `.github/actionlint.yaml` documents the narrow
  actionlint exception for GitHub's `queue: max`; structured tests still cover it.

## What belongs here

Document ownership and update rules live in [AGENTS.md](../AGENTS.md#文档维护规则).
