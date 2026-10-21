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
- JVM tests replacing `Dispatchers.Main` must cancel **and join** the ViewModel
  scope before `resetMain()`, or late callbacks can fail unrelated tests.
- MockWebServer WebSocket fixtures must answer `onClosing` with
  `webSocket.close(code, reason)`; otherwise shutdown can hang or fail teardown.
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

## WebView and UI automation pitfalls

- Cold launch normally lands on Bookshelf; open a book before testing Reader.
  Single-tap text opens lookup; long press tests native selection/highlights.
- Standalone reader fixtures must inject measured
  `readerViewportCssLayout(...).cssVariables()` and wait for `HoshiReaderRestore`.
  Shell injection alone can leave VN with a zero-height viewport.
- Use screenshots/recordings for painting, menu visibility and handle movement.
  CSS Custom Highlights can clip vertical glyph edges despite a correct DOM Range.
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
Sasayaki on/off and VN cue merging on/off.

For a debuggable process, locate its WebView through CDP:

```bash
hoshi_serial='REPLACE_WITH_DEVICE_SERIAL'
hoshi_package=moe.antimony.hoshi.debug
hoshi_pid="$(adb -s "$hoshi_serial" shell pidof "$hoshi_package" | tr -d '\r')"
adb -s "$hoshi_serial" forward tcp:9222 "localabstract:webview_devtools_remote_$hoshi_pid"
curl --fail http://127.0.0.1:9222/json
```

Select the intended `appassets.androidplatform.net` target. VN readiness is
`window.hoshiReader.ensureReady()`; paginated/continuous expose `didInitialize`.
Check `calculateProgress()` after restore and pair it with screenshots for blank
or incorrect content. Collect `Performance.getMetrics` and a focused CDP CPU
profile; identify app-owned callers and self/inclusive time.

| Signal | Next measurement |
| --- | --- |
| Late WebView target, low JS CPU | Kotlin/repository log markers; Perfetto for scheduler, binder, locks and main/render-thread waits |
| CPU-heavy Kotlin/native path | Simpleperf sampling; distinguish app callers from framework frames |
| Jank | `dumpsys gfxinfo` for repeated identical swipes; Perfetto separately for diagnosis so profiling overhead does not contaminate frame comparisons |
| Worse after repeated entry | Live WebView targets, process CPU, heap retention and pending callbacks before/after each cycle |
| Reader JS scales badly | Look for nested block×text/cue×screen scans and layout reads after DOM writes; protect improvements with operation-count tests |
| Bookshelf cover jank | Separate original `HoshiCoverPipeline: source_decode` from cached thumbnail decode; a second unchanged warm round trip should not decode originals |
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
`SyncQueueViewTest` renders isolated upload/download rows and checks that active
row reordering/completion preserves the queue head or the user's manually scrolled
position without accessing the book repository or account; its screenshot is in target cache
`sync-queue-fixture.png`.

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
