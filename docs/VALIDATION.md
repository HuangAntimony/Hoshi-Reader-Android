# Agent Validation and Automation

Use the checks that match the change. Test code and scripts own fixture parameters
and assertions. See the [maintenance rules](../AGENTS.md#validation-维护规则) before
adding to this guide.

## Choose and run checks

Run focused behavior tests while working, then the applicable completion checks
from the repository root.

| Change | Completion checks |
| --- | --- |
| Documentation only | `git diff --check`. Verify paths, anchors and commands against their owners. Preserve release headings and issue links when editing changelog. |
| App code, data or architecture implementation | `./gradlew test` and `./gradlew assembleDebug` |
| Reader/popup/selection web assets | Also `node --test app/src/test/js/*.test.mjs`. Use real WebView evidence for painting, geometry and native gestures. |
| Resources, manifest, UI wiring, packaging or framework integration | Also `./gradlew lint` |
| Visible strings | Also `./gradlew :app:testDebugUnitTest --tests moe.antimony.hoshi.LocalizationResourceTest` |
| SDK/ABI/signing/native packaging or release behavior | Also `./gradlew :app:assembleRelease`. Inspect affected APK libraries, version, signature and build commit. |
| Release workflows or publisher tools | `actionlint`, `python3 -m pip install -r tools/tests/requirements.txt`, then `python3 -m unittest discover -s tools/tests -p 'test_*.py'` |
| Transcription runtime packaging | `python3 tools/build-transcription-audio.py --check` and the [component checks](#transcription-components) below |

Use the variant task for JVM test filtering, for example
`./gradlew :app:testDebugUnitTest --tests moe.antimony.hoshi.features.reader.ReaderPaginationScriptsTest`.
Find tests with `rg --files app/src/test/java app/src/test/js app/src/androidTest/java tools/tests`.
Build inputs and CI checks are in [app/build.gradle.kts](../app/build.gradle.kts)
and [android-ci.yml](../.github/workflows/android-ci.yml).

## Test and fixture pitfalls

- Assert behavior, state and operation counts. Parse manifest, resource and build
  declarations when checking their structure.
- CI uses generated or tracked fixtures. Check availability before using optional
  local samples under ignored `testdata/`.
- If Kotlin/FIR lint reports a missing generated release Hilt source during a
  combined run, finish `./gradlew :app:assembleRelease` first, then run
  `./gradlew lint` separately. Hilt regeneration can invalidate lint's source snapshot.
- Tests that replace `Dispatchers.Main` must cancel and join the ViewModel scope
  before `resetMain()`. Late callbacks can fail unrelated tests.
- MockWebServer WebSockets must answer `onClosing` with `webSocket.close(code, reason)`
  for teardown. Bind server and client DNS to IPv4 loopback when IPv6 fallback
  could mask an intended connection failure.
- Duplicate English strings with different contexts need matching `comment`
  attributes on every duplicate in both language resource files.
- For [error paths](ARCHITECTURE.md#errors-and-diagnostics), inject test-owned
  failures with nested causes and observe both visible state and diagnostic events.
  Check that cancellation and form validation leave runtime history intact.

## Device execution without data loss

- Select the intended serial and package. Preserve installed data and preferences
  and use only test-owned files. Any exception requires explicit authorization
  and a disposable device.
- Reserve `connectedDebugAndroidTest`, `connectedAndroidTest`, `installDebugAndroidTest`
  and other reinstall or cleanup tasks for authorized disposable devices. Build
  APKs separately, install with `adb install -r`, and stop on an app ID or signature mismatch.
- Review setup and teardown and run only the intended class or method. Use scratch
  stores, media and test credentials. Use DocumentsUI or an authorized `content://`
  URI for SAF imports, with copies of samples under Downloads if needed.
- Keep the device unlocked and the screen on for UI/focus tests. Resolve labels
  in the current app language.

Example using the generated Reader viewport fixture. Choose the serial with
`adb devices -l`.

```bash
./gradlew assembleDebug assembleDebugAndroidTest
hoshi_serial='REPLACE_WITH_DEVICE_SERIAL'
adb -s "$hoshi_serial" install -r app/build/outputs/apk/debug/app-debug.apk
adb -s "$hoshi_serial" install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb -s "$hoshi_serial" shell am instrument -w -r \
  -e class moe.antimony.hoshi.features.reader.ReaderViewportWebViewTest \
  moe.antimony.hoshi.debug.test/androidx.test.runner.AndroidJUnitRunner
```

Read the instrumentation output through the final `OK (N tests)` or failure
summary. The shell status can be zero on assertion failure. For opt-in tests,
confirm the requested test actually ran. Report the device/API and WebView
versions, results, artifacts and checks left unrun in the current task.

## WebView and UI automation pitfalls

- `uiautomator dump` can leave stale XML after a null root. Use a fresh destination
  and verify the dump output, then check a current screenshot or Compose state.
- Use measured `readerViewportCssLayout(...).cssVariables()` in standalone Reader
  fixtures and wait for `HoshiReaderRestore`. Keep WebView creation, use and
  destruction on Main, including after repository I/O. Detach before destroying.
- A visual-state callback means the DOM is ready to draw. Check the expected
  chapter or image in a bounded screenshot loop to confirm compositor paint.
- Use one input stream per gesture. End CDP touch injection with `touchEnd` or
  `touchCancel` before disconnecting or using native input. An active injection
  can suppress native input, and Touch Events can stay in the parent document
  when a second pointer starts in an iframe. Use native events for native claims.
- Selection handles need real touchscreen long presses and drags with
  `SOURCE_TOUCHSCREEN` and `TOOL_TYPE_FINGER`. Allow tap and long-press timeouts
  plus a release margin. Collect endpoints after the gesture to avoid layout reads
  or method tracing masking the failure.
- Check short and shrinking E-ink sheets. An outer Material 3 `ModalBottomSheet`
  border can paint at the unshifted height on the scrim.
- Give the debug caller window focus for browser OAuth tests. Keep the
  external-process browser fixture framework-only and use isolated tokens.

### Narration highlight glyph integrity

Pause on the same TTS/Sasayaki sentence and compare highlighted and plain
screenshots after paint. Match font, size, direction and position, then restore
the original highlight. Inspect whole glyphs and ruby, including strokes outside
nominal character boxes. CSS Custom Highlights clipped vertical glyphs with
unchanged DOM and Range geometry, so text and color assertions alone miss this
regression. Follow the [renderer restrictions](ARCHITECTURE.md#prohibited-narration-highlighting-changes).

## Performance baseline and diagnosis

Capture a baseline before editing. Repeat the same fixture and device conditions,
including thermal state, build type, cache warmth, book position, viewport, font,
layout and playback state. Save raw samples with build SHA, WebView version and
start/end markers. Keep traces and recordings outside Git. Collect comparison
timings without intrusive tracing and use separate traces for attribution.

For Reader opening, time tap → WebView target → restore → visible paint separately.
Inspect source frame timestamps for variable-frame-rate recordings, since fixed-rate
resampling can miss the first painted frame. A device recording measures software
frames. A physical E-ink refresh requires separate evidence.

For CDP, use debug or enable **Diagnostics → WebView debugging** in release before
reproducing. Use the intended package and PID.

```bash
hoshi_serial='REPLACE_WITH_DEVICE_SERIAL'
hoshi_package=moe.antimony.hoshi.debug
hoshi_pid="$(adb -s "$hoshi_serial" shell pidof "$hoshi_package" | tr -d '\r')"
adb -s "$hoshi_serial" forward tcp:9222 "localabstract:webview_devtools_remote_$hoshi_pid"
curl --fail http://127.0.0.1:9222/json
```

Confirm the target through `location.href` or `document.baseURI`. A Dictionary
iframe host can appear as `about:blank` in discovery. Wait for restore before
checking progress. Remove the task's forward with
`adb -s "$hoshi_serial" forward --remove tcp:9222` and restore task-owned settings.

| Signal | Evidence to collect |
| --- | --- |
| Slow native start or jank | Stage markers and repeatable `dumpsys gfxinfo` samples. Use Perfetto or Simpleperf separately to locate waits or CPU work. |
| WebView slows after repeated entry | Count live DevTools targets and inspect retained callbacks and heap. |
| Reader layout or cue jank | Separate geometry, cue updates and paint. Include first-use cues, since Blink can retain paint boxes after highlighting. Check exact offsets and geometry before timing layout changes. |
| Slow Bookshelf | Time shelf chrome → books → covers on the same signed build. `am start -W` measures Activity display. Separate `HoshiCoverPipeline: source_decode` from warm thumbnail hits. |
| Slow transcription | Measure the same compressed input and resume point. Decode, inference and saving can overlap, so time the whole pipeline as well as individual stages. Check token/timestamp parity, memory and read-ahead. |
| TTS continuity | Check ordered units and player events with `TtsQueuePlaybackDeviceTest`. Compare live provider throughput separately. PCM duration and player callbacks alone cannot establish acoustic gaps. |

### Reader pagination comparisons

Use `git show` and `adb shell run-as` to stage the baseline revision's
`reader-paginated.js` in the debug app's cache. `ReaderPagePerformanceTest` uses
`cache/reader-page-performance-baseline/reader-paginated.js` with
`-e pagePerformance true`. `ReaderPageThroughputTest` uses
`cache/reader-page-throughput/baseline.js` with `-e pageThroughput true`.
Read fixture options in each test. Use isolated book and font copies when
reproducing a reported layout.

Measure foreground response during active measurement separately from idle
whole-book throughput. Compare exact per-spine page starts. For native scheduler
or resource changes, capture references on the old APK and compare on the new
one with identical layout inputs. A JS swap in one APK isolates the web runtime.
Check per-code-point geometry when changing text splitting and run those scans
separately from timing.

## Specialized fixture setup

Use the [data-preserving procedure](#device-execution-without-data-loss) for these
device fixtures. Read the owning tests for method names, arguments and assertions.

### Local word-audio SQLite

```bash
cmake -S tools/tests/local_audio_native -B /tmp/hoshi-local-audio-host -DCMAKE_BUILD_TYPE=Release
cmake --build /tmp/hoshi-local-audio-host -j 4
ctest --test-dir /tmp/hoshi-local-audio-host --output-on-failure
```

Host timings describe the native backend. For real SAF checks, select a uniquely
named copy of a Japanese audio database through the debug-only
`LocalAudioValidationActivity`. Stop the process after selection to discard
temporary grants. Run `LocalAudioSafDeviceTest` methods individually in prepare,
reopen/release, then revoked-access order, stopping the process between phases.
Release only the grant recorded by the fixture. Run `LocalAudioPerformanceDeviceTest`
before grant revocation if needed, with space for a temporary database copy.
Keep the sample and binding separate from the installed source.

### Google Drive

Copy [secrets.properties.example](../secrets.properties.example) to ignored
`secrets.properties` for debug and release client IDs. Environment values take
precedence. Verify BuildConfig and merged redirects for auth changes, including
empty environment values. Use Android client registrations matching package and
signature, with Custom URI scheme enabled. Release CI requires a release client ID.

Run the relevant loopback server and map its port with
`adb -s "$hoshi_serial" reverse tcp:PORT tcp:PORT`. Both servers use generated data.

- [Drive transfer fixture](../tools/drive-transfer-fixture.py) uses port 18765.
  Pass `-e driveFixtureUrl http://127.0.0.1:18765` to `DriveTransferDeviceTest`.
  Run `prepareInterruptedUpload`, POST to `/allow-resume`, then run
  `resumeAfterProcessRestartAndDownload` in a separate invocation. Check
  `/stats` for a query of the existing session followed by a resumed byte range.
- [Drive metadata fixture](../tools/drive-metadata-isolation-fixture.py) uses port
  18766. Pass `-e driveIsolationUrl http://127.0.0.1:18766` to
  `DriveMetadataIsolationDeviceTest`. Restart the server for each run and allow
  time for the real 60-second read timeout and retries.

Stop the server and remove only its reverse mapping after testing.

### Transcription components

After decoder or CMake changes, build supported ABIs with CI's pinned NDK/CMake
and update `app/src/main/assets/transcription-audio-runtime.json` with the source
change. Use [build-transcription-audio.py](../tools/build-transcription-audio.py)
for build options and
[publish-transcription-components.py](../tools/publish-transcription-components.py)
for publication. Publish the binaries, license and generated source archive in
an immutable component prerelease before shipping its catalog. Verify deployed
hashes with `--remote-check`. Nakuru device tests require verified resources
installed in advance.

### External services

Use explicit test endpoints and credentials for live TTS. VITS fixtures require
`Content-Type: application/json` without a charset. Use mocks for repeatable
protocol failures. `AnkiKuma3SmokeTest` requires an initialized kuma3 installation
and database permission. It creates a tagged note in the installed collection,
so use a test collection.
