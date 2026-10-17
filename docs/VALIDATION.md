# Validation Guide

This file contains durable validation policy for Hoshi Reader Android. Update it
only when validation requirements, test data, or stable manual QA flows change.

## Required Commands

Before claiming implementation complete, run:

```bash
./gradlew test
./gradlew assembleDebug
```

Run lint when changing resources, manifest, UI, packaging, or release-facing
build behavior:

```bash
./gradlew lint
```

For transcription runtime packaging or binding transforms, also run
`./gradlew -p buildSrc test` and `python3 tools/build-transcription-audio.py --check`.
When changing the FFmpeg decoder or its CMake configuration, build all component
ABIs with `python3 tools/build-transcription-audio.py --ndk <NDK-29.0.14206865>
--cmake <CMake-3.31.6>`. Commit the generated
`app/src/main/assets/transcription-audio-runtime.json` with those sources.
Before shipping the APK, publish `build/transcription-audio/distribution` using
`tools/publish-transcription-components.py --catalog <catalog> --directory <distribution>
--notice app/src/main/res/raw/ffmpeg_license.txt --extra <generated-source-archive>
--target <remote-commit>`. Include the generated source archive so the component
can be rebuilt independently of the APK.
This publishes an immutable component prerelease and never replaces existing
files. Use `--remote-check --catalog <catalog>` to verify public download hashes.

Run a release build when changing `minSdk`, `targetSdk`, `compileSdk`, ABI
filters, signing, native packaging, or other release packaging behavior:

```bash
./gradlew :app:assembleRelease
```

For `minSdk` changes, validate both the newly admitted Android version when
available and an existing supported Android 9+ device path. Preserve app data
for existing-user checks, compare release APK structure when packaging output
changes, and keep older-version compatibility fixes guarded so Android 9+
behavior stays on the existing path.

For a single JVM unit test, do not use `./gradlew test --tests ...`; the
`:app:test` aggregate task does not support filtering. Use:

```bash
./gradlew :app:testDebugUnitTest --tests fully.qualified.TestClassName
./gradlew :app:testDebugUnitTest --tests fully.qualified.TestClassName.testMethodName
```

MockWebServer WebSocket fixtures must reply to peer closure in `onClosing` with
`webSocket.close(code, reason)`. Otherwise server shutdown can wait indefinitely
for the close handshake and fail the test during teardown. Do not suppress shutdown
exceptions or treat a successful rerun as a fix.

JVM tests that replace `Dispatchers.Main` must cancel and join the ViewModel
scope's `Job` before calling `Dispatchers.resetMain()`. Calling `cancel()` alone
can leave IO-flow completion callbacks targeting the removed main dispatcher,
causing uncaught exceptions in a later, unrelated test.

For localization changes, run:

```bash
./gradlew :app:testDebugUnitTest --tests moe.antimony.hoshi.LocalizationResourceTest
```

When separate translation contexts intentionally share an English string value,
add a `comment` explaining the context to every duplicate entry, including the
existing entry, and mirror those comments in Chinese resources.
`DuplicateCrowdInStrings` still fails if only the new entry has a comment.

For reader web asset changes, run the focused JavaScript tests:

```bash
node --test app/src/test/js/*.test.mjs
```

## Nakuru

- Install a debug APK with `adb install -r`, select Nakuru in Resources >
  Transcription and start a transcription once so the app downloads and verifies
  its resources. The device tests below use those files and fail instead of
  downloading.
- `NakuruDeviceTest` accepts `nakuruClip` (an app-readable 16 kHz PCM16 mono WAV)
  and `nakuruReference` (JSON array of desktop Nakuru timed tokens). Invoke it
  directly with `adb shell am instrument -w -r -e class
  moe.antimony.hoshi.features.sasayaki.transcription.NakuruDeviceTest ...
  moe.antimony.hoshi.debug.test/androidx.test.runner.AndroidJUnitRunner` and require
  `OK (2 tests)`. It compares all three presets against the supplied reference,
  then tests cancellation/resume without touching book sidecars. Outputs remain
  in app cache as `nakuru-result-<preset>.json`. Use both short and multi-segment
  excerpts; fixture equality is not a claim about whole-book accuracy or identical
  platform decoding of compressed audio.
- Run `TranscriptionBenchmarkDeviceTest#benchmarkExcerpt` explicitly with `clip`,
  `preset` (1/2/3), and `engine` (`nakuru` or `reazon`). It requires an excerpt
  of at most 300 seconds, checks valid timed tokens, and records end-to-end
  backend timing and output in app cache. It does not establish recognition
  accuracy.
- `NakuruTranscriptAlignerTest` compares the aligner with expected results from
  desktop's aligner on synthetic fixtures; do not change expectations merely to
  make a failing test pass.
- In Resources > Transcription, select Nakuru, start/pause/resume, then realign a
  completed transcript. The selected engine locks once a transcript exists;
  clearing it permits a change. ReazonSpeech must still work in the same
  process, and its existing incremental matching remains unchanged. Test a real
  EPUB plus compressed audiobook separately, preserving reading and playback state.

## Sasayaki Subtitle Export

- From Resources, export the current match using both Subtitles and Transcription.
  Save through the system document picker, cancel and retry, and open the system
  share sheet without selecting a recipient. Verify UTF-8 text, cue times and
  multiline text survive export/import. Only matched cues are exported.
- During transcription, export a partial match without pausing or changing Reader
  or playback position; later matches must not alter the already prepared file.
  `SasayakiSrtTest` covers both-source rematching and timestamp rounding;
  `SasayakiSubtitleExportTest` covers readable shared URIs, snapshot persistence,
  picker cancellation, and save failure recovery using test-owned cache files.

## Device And Emulator Safety

- Preserve app data by default.
- Device UI tests resolve control labels from the target app's localized resources;
  do not assume English or change the user's device/app language for a fixture.
- `am instrument` can exit with shell status 0 despite failing tests. Require its
  final `OK (N tests)` summary; inspect `FAILURES`, `INSTRUMENTATION_FAILED`, and
  test stack traces before reporting a device run as passed.
- Do not run `connectedDebugAndroidTest`, `connectedAndroidTest`,
  `installDebugAndroidTest`, or any connected instrumentation task that clears,
  reinstalls, or uninstalls app data unless the user explicitly permits a
  disposable device.
- EPUB import must use DocumentsUI or an equivalent authorized `content://` URI.
  Do not use `file:///sdcard/...` or shell-created unauthorized content URIs.
- Command-line setup may push samples to emulator Downloads before selecting
  them through DocumentsUI.
- Use a clean disposable emulator only when the target explicitly requires first
  launch, empty-library, duplicate-import, migration, or corrupted-data coverage.

## Error Diagnostics

- For changed failure paths, assert that the visible error retains the localized
  operation/category summary and a specific exception message plus nested cause.
  Test blank-message fallback to the exception type and preserve backend/native
  error codes when those are the available diagnostic.
- Batch failures must identify each failed file/object and its reason while
  continuing independent items. Dismiss/retry must still work, and cancellation
  must not publish an error. Preserve explicitly intended automatic offline
  suppression and successful optional/cache fallbacks.
- Live form validation, including unfinished regex patterns and replacement
  templates, must retain inline original diagnostics without adding runtime
  history. Completing the draft must clear validation and restore preview;
  genuine service or storage failures must still reach the visible state and history.
- On-device error QA uses existing dialogs, snackbars or retry states. Check a
  malformed EPUB import and one nested IO/service failure; details must remain
  readable instead of being replaced by a generic message or only logged.
- Open Diagnostics immediately after a runtime failure, then reopen after an
  app restart: the operation, time and original cause/stack must remain. The
  overview must show single-line app/WebView metadata values with separate
  crash, error and process-exit entries (including independent empty states);
  each list has one scroll surface and opens selectable full details on tap.
  Return from each list and confirm its navigation state. Save and open Share
  without selecting a recipient from the overview and any record list; exported
  text must include
  runtime errors and existing crash/process records. Repeated dialog
  recomposition must not add duplicates. Test a source-only platform/service
  error without inventing an
  exception, plus malformed runtime-history storage with automatic recovery,
  retained original parse failure and unaffected crash diagnostics. Cancel a
  diagnostics snapshot at
  the drain/write boundary and verify all pending original events still persist,
  while caller cancellation continues to propagate.

## Test Data

- EPUB: `testdata/test.epub`, `testdata/test2.epub`.
- Stored ZIP data descriptor and Kadokawa-like spine regression: generated by
  `EpubBookParserTest`; do not depend on ignored local `testdata/` files.
- Dictionaries: `testdata/JMdict_english.zip`, `testdata/MK3.zip`,
  `testdata/freq.zip`, `testdata/pitch.zip`; use a Yomitan Kanji archive and a
  mixed term-meta/Kanji archive when validating Kanji or complete pitch import.
- Font: `testdata/KleeOne-SemiBold.ttf`.
- Sasayaki: `testdata/test.srt`, `testdata/test.m4b`,
  `testdata/opus_test.opus`. Unit tests generate tracked M4B and Ogg Opus
  fixtures instead of depending on these ignored local files.

## Reader And Lookup

- Dictionary tab history: search A, follow a definition link to B, swipe back to
  A, switch to Settings and return. A and its scroll/expanded definitions must
  remain visible; forward must still reach B and back must return to A. Also
  switch tabs while on B, repeat after a child lookup, and start a fresh search
  to verify old history is cleared. `DictionarySearchSessionTest` checks WebView
  session retention with in-memory pages without changing installed app data.
  Record the Settings-to-Dictionary transition and inspect the first visible
  frames for blank results. Check external lookup followed by Settings does not
  show a keyboard on Settings; repeated Dictionary selection still focuses search.
  `RetainedTabContentTest` checks that inactive content remains attached, has a
  CREATED lifecycle, and cannot consume another tab's Back action.

- Opening a book ends the Dictionary tab's current search. With word-audio
  autoplay enabled, search a word, follow a definition link, open a child popup,
  then open a book whose profile has different popup scale/gesture settings.
  Opening and closing Reader must not replay the old word; returning to Dictionary
  must show an empty input, no results/popups, and no Back/Forward history. A new
  search and manual audio playback must still work. Repeat with local audio when
  already imported and with an available remote source; preserve installed data.
  A failed book load must leave the search intact, and ordinary tab switches must
  continue retaining it. `ReaderRouteProfileActivationTest` checks reset-before-
  activation ordering; `DictionarySearchViewModelTest` checks late result/failure
  rejection; `DictionarySearchReaderResetTest` uses real popup assets and in-memory
  audio responses to check inactive reset and profile-setting changes without
  modifying installed app data.

- Book search: verify literal spaces/punctuation, case-insensitive matches,
  paragraph/`br` boundaries, ruby exclusion, complete bracketed sentence
  snippets, 100-result limit, and supplementary-plane characters before/inside
  matches. In paginated, continuous and VN modes with horizontal/vertical
  writing, jump within/across chapters and to the current position, then
  search again rapidly; only the latest restored target should receive a blue
  mark. Check actual painting, not just Range geometry. Page navigation and
  other jumps clear the mark; jump history must not recreate it. Search marks
  never appear in saved Highlights or sidecars. Punctuation-only queries remain
  searchable but a zero-normalized-length result paints no mark.
  `ReaderSearchHighlightWebViewTest` uses in-memory content to verify blue
  pixels and clearing in all six mode/writing-direction combinations, without
  opening books or changing preferences. Standalone reader WebView fixtures must
  inject `readerViewportCssLayout(...).cssVariables()` using the measured view
  dimensions and wait for `HoshiReaderRestore`; loading only the shell script
  can leave VN with a zero-height clipped viewport. Build instrumentation
  separately and use `adb install -r` plus the explicit class runner; do not use connected
  Gradle tasks on a device with user data.

- In paginated, continuous, and VN modes, use the native selection color menu
  on plain text and ruby split across styled nodes. Contents should show base
  text with parenthesized readings, while old highlights without readings still
  show plain text. Select exactly the same raw range to recolor without adding
  a record, then choose its current color to remove it. Partial overlaps and
  repeated text at different positions remain independent. Include whitespace,
  punctuation and supplementary characters before/inside the range; verify
  Contents jumps, chapter changes, restart and sync preserve location/readings.
  In VN, also complete progressive reveal, leave/return to rebuild the screen,
  and check both overlapping highlights still paint their full visible ranges.
  Selecting only a visible part of a cross-screen highlight creates a separate
  range rather than editing the complete stored range. Preserve device data.

- While selecting text, dismiss/recreate the native menu and drag selection
  handles into ruby annotations, then choose Highlight and a color. The body text
  must be marked without annotations; selecting the same range must recolor/remove
  the original mark. `ReaderNativeHighlightWebViewTest` covers picker range retention,
  annotation/element endpoints and forward/backward selections in all six reader
  mode/writing-direction combinations, using isolated HTML with no sidecar writes.
  Run it using the data-preserving explicit instrumentation procedure above.

- While selecting text, dismiss/recreate the native menu and drag selection
  handles near ruby and page edges. Paginated/VN navigation must remain blocked
  until the selection clears, without crossing a chapter boundary; continuous
  mode must not treat handle drags as reader gestures. After deselection, normal
  navigation must resume. `ReaderNativeSelectionStateWebViewTest` uses generated
  vertical paginated content and a real ActionMode to check surviving selection,
  logical page retention, blocked navigation versus chapter boundaries,
  and stale document notification rejection. It also checks empty/offscreen
  native menu bounds against the visible DOM selection, preserves valid native
  bounds, and discards fallback coordinates after selection movement or scroll
  within the same ActionMode. On device, long-press ruby-adjacent base text and
  confirm the menu stays beside the selected text; in continuous mode, scroll
  the selection out of view and verify no stale toolbar remains pinned. Run it
  with the data-preserving explicit instrumentation procedure above.
  `ReaderSelectionViewportWebViewTest` also checks native handle drags with
  narration cues prepared, menu recreation after dragging plain text in a single
  paragraph spanning pages, and
  immediate scroll prevention, visible/logical position retention, and resumed
  paging in both writing directions. Recovery holds must exceed WebView's sum of
  tap timeout and long-press timeout; merely adding 100 ms to Android's long-press
  timeout can release exactly at recognition and intermittently clear the range
  as a tap. Verify the visible menu reappears after
  repeated drags in both plain and ruby-adjacent text, preserves the dragged
  range, and copies/highlights that range. Repeat successive drags in paginated
  and continuous modes. Existing ActionMode or selected-text
  state alone does not prove menu visibility. On Android 14+ with a `sw600dp+`
  window, compare normal long-press and handle-drag recovery: both visible menus
  must retain the localized Highlight and TTS Replace actions. WebView's mouse
  dropdown is a separate menu path and does not validate touch ActionMode recovery.
  Current WebView reports a touch-handle adjustment as `pointerType="mouse"`,
  `button=-1`. Actual touch/pen long presses keep their native menu path, even
  when the caret probe disagrees with the selected range; do not inject a second
  DOWN while that press is still in progress.
  `initialLongPressKeepsItsNativeMenuWhenCaretProbeDisagrees` verifies the native
  touch menu survives that disagreement. Exercise an actual native handle drag
  as well as an injected recovery request.
  `recoveredMenuPreservesExactSelectionAndVisibleAppActions` checks the real
  touch menu and unchanged native endpoints in both writing directions;
  `reversedCrossParagraphRecoverySettlesAndKeepsBothHandlesUsable` repeats an
  invalid native anchor for a reverse selection across paragraphs, waits past
  two long-press deadlines, and drags both handles. It also emits
  a duplicate unchanged `selectionchange` after the menu request and before its
  recovery point is consumed; the recovery must still run exactly once. Check
  the same notification during replay, and confirm real endpoint/direction
  changes still cancel stale requests. Create handle fixtures with
  native long-press and drag gestures; DOM-only selections do not establish their
  visibility. Fixture touchscreen events must specify `TOOL_TYPE_FINGER` as well
  as `SOURCE_TOUCHSCREEN`; an unknown tool produces an empty DOM `pointerType`
  and does not exercise a real finger long press. A briefly visible menu is insufficient: recovery must settle without
  repeated gestures or a hidden handle.
  `draggingStartToRubyBoundaryRecoversThroughSelectedTextInsteadOfHandle` drags
  a native start handle to a single-character text node immediately after ruby.
  Check that recovery reaches content as a touch context-menu event rather than
  another handle adjustment, preserves both endpoints, and keeps Highlight visible.
  Include this boundary in real-book checks; a recovery point at the first selected
  glyph may overlap the native start handle even though it hits selected DOM text.
  `shortSelectionsRecoverThroughContentAfterDraggingEitherHandle` checks single-
  and two-character native selections after dragging either handle, including
  smaller fonts. Verify recovery reaches content as a touch context-menu event
  and preserves anchor/focus direction as well as the selected text. A menu can
  reappear even after recovery accidentally grabs a handle, so visibility alone
  does not validate this case.
  In real books, include the final period of a paragraph that wraps into another
  column, and check menu coordinates beyond the recovery long-press deadline.
  Verify the menu stays near the handles instead of jumping to the bottom of the
  intervening columns. Check endpoint-based DOM fallback in both
  writing directions, one endpoint offscreen, and element boundaries without
  caret geometry; fragments remain the fallback when no visible caret exists.
  `pendingMenuRecoveryStopsWhenSelectionOrDocumentChanges` checks cancellation
  before the long-press deadline; `menuRecoveryReplyCannotOutliveNewTouch` blocks
  the JS reply while a new touch arrives and rejects a late recovery gesture.
  Remove fixture WebViews from their parent before calling `destroy()`.
  Record the tested WebView version, Android API level and smallest-width bucket.
  Repeat real-book ruby handle drags and
  inspect recordings frame by frame for unrelated columns becoming selected;
  final scroll position alone cannot validate this. During gesture acceptance,
  do not poll DOM geometry or suspend threads with JDWP method tracing: both
  change timing and can mask the bug or stall the UI. If range events are needed,
  record DOM endpoints/Range text in memory without layout reads, then retrieve
  them after the gesture. Also run an uninstrumented recording.

### Theme

Preserve app data and cover all four tabs, every settings category, native Reader
panels, dialogs, menus, and Process Text lookup:

- Both switching modes show Light / Sepia / Custom Light and Dark / Warm Dark /
  Custom Dark. Automatic mode has one selection per group; manual mode has one
  selection across all six options. Change system night mode while foregrounded
  and backgrounded: only automatic mode changes the active group. Editing the
  inactive automatic slot must not change the current theme.
  Use identical custom colors, and backgrounds opposite to their group's brightness:
  Custom Light must use a light native interface and black-on-white dictionary
  popups; Custom Dark must use a dark interface and white-on-black popups. Check
  Reader, Dictionary, recursive lookup and Process Text, preserving reading
  colors, position, selection and lookup history when switching groups.
- Disabling automatic switching keeps the currently displayed palette. Re-enabling
  uses both saved slots, including edits made in manual mode. Each of the two
  slots retains its custom colors after preset changes. Canceling any editor
  keeps the previous settings, including alpha.
  Change palettes/accents while scrolled to the bottom, including delayed and
  failed saves: swatches, preview, text brightness, row positions and scroll offset
  must remain stable while saving; only confirmed settings change the theme.
  `DisplaySettingsViewTest` covers the one-versus-two palette selections and
  layout preservation during a suspended save using in-memory settings, without
  writing the installed app's preferences.
- Compare Theme with other grouped settings, including its Reader
  panel: palette, accent and E-ink rows use the same 16dp inset dividers; E-ink
  keeps the enclosing group outline continuous.
  Open Theme from the Reader's bottom-right menu: the title scrolls away with
  the settings and returns when scrolled to the top, while the drag handle stays
  available. The full Theme settings page keeps its top app bar fixed.
- Reading Settings selected segments form rounded capsules with a visible neutral
  track around every edge and no vertical separators. Check text orientation,
  furigana mode, reading mode, VN screen content and progress position in light/dark themes
  with system/manual accents, and inverse selection in both E-ink modes.
  Selecting another option must move the highlight without changing row size or
  reducing the original clickable area. `ReaderAppearanceSelectionTest` checks
  rendered fills, text and the visible track above selections for all five
  controls, using temporary font storage and in-memory reading preferences.
- System dynamic colors on Android 12+, the fixed fallback on older Android,
  all eight accent seeds and extreme custom seeds. Reader background/text retain
  their own colors while controls and native containers use the accent scheme.
  Across all four tabs, check that bottom/side navigation has a consistent tint
  and that the status-bar inset matches its page. Dictionary search must retain
  this continuity both with the keyboard open and after showing results; its
  field remains distinguishable, with an explicit field outline in E-ink and no
  extra separator below the search header, above the bottom tabs, or beside the
  navigation rail. Verify both portrait bottom tabs and landscape side navigation
  on a device; they use separate shell layouts.
  Compare strongly tinted system palettes and manual blue/red/green accents:
  page, navigation, nested controls and overlays should keep a subtle neutral
  tint and distinct tonal levels. Group dividers should remain visible without
  dominating white cards; input outlines and active accents retain their contrast.
  Repeat in dark mode and confirm E-ink still uses full black/white boundaries.
- Light and dark E-ink: compare every tonal container with ordinary mode and
  verify actual outlines on groups, nested controls, filled buttons, segmented
  tracks/selections, popups and panels. Lazy groups need a
  continuous closed outline with one row, multiple rows and during scrolling.
  E-ink optimization sits directly below automatic switching. Enabling it hides
  palette/accent choices and shows an explanation; automatic switching stays available. With
  automatic switching off, only the light/dark choice appears below the switches.
  Its selection survives restart without changing any stored palette or accent.
  With automatic switching on, E-ink follows the system even if a custom palette
  has the opposite brightness. Disabling automatic switching keeps the displayed
  brightness; disabling E-ink restores the normal palette/accent choices and colors.
- Upgrade from the v1.3.3 release settings format for every theme, including Sepia
  inversion and alpha custom colors. Do not use intermediate development schemas
  as upgrade baselines.
  Start with a global profile and a different last-book profile, with automatic
  book opening enabled: migration must use the global profile before opening the
  book. Reopen, change language/profile, and create/copy profiles; colors and E-ink
  must stay global. The custom editor has no legacy-palette import list.
  Missing, empty, truncated or invalid profile settings must fall back to Reader
  DataStore, then legacy SharedPreferences, then defaults, so both Activity hosts
  can finish loading. Preserve source files. Failed migration writes must remain
  unmarked and retry successfully; after migration, restart must preserve the user's
  new global settings without reading or reapplying old profile colors.
- Test narrow Chinese layouts and enlarged fonts, empty/loading/error/disabled
  states, cold launch, restart, foreground/background, and opening both Reader
  settings panels. Reading Settings identifies the effective profile. Changing
  display settings preserves reading position, selection, and an open lookup.
- Check initial loading/migration failures and retriable persistence failures:
  no default-theme content flash, no lost confirmed setting, and localized errors.
  Rapidly change two independent reading settings during delayed storage and
  confirm that both survive; repeated stepper taps must accumulate every increment.
  MainActivity and Process Text must agree on theme.

Automated display regressions live in `features/display`, Reader settings/host
tests, Reader display-update tests, and `ui/theme/HoshiSurfaceRolesTest`.
Device inspection remains necessary for outlines and transitions; passing JVM
tests does not establish visual acceptance.

### Reading And Lookup Flows

Reader work should compare against
`reference/Hoshi-Reader-iOS/Features/Reader/ReaderWebView/ReaderWebView.swift`
and the matching JS/CSS before adding Android-specific behavior.

When manually validating reader and lookup flows, do not assume app launch opens
the reader. Cold start lands on Bookshelf; tap a book cover to enter Reader
unless the test explicitly restores an existing Reader route. Do not long-press
reader text for lookup. Hoshi lookup opens from a single tap on reader text; long
press is for native selection/highlight-style flows when those are under test.

Validate lookup deep links with:

```bash
adb shell "am start -a android.intent.action.VIEW -d 'hoshi://search?text=%E7%8C%AB'"
adb shell "am start -a android.intent.action.VIEW -d 'hoshi://search?text=%E7%8C%AB&mode=app'"
```

The first link opens the default popup overlay and the second opens the
Dictionary tab. Cover cold start, an existing foreground task, two consecutive
identical links, URL-encoded Japanese text, and missing or blank `text`; empty
text must open a cleared, focused Dictionary search instead of an empty popup.

Validate Dictionary pull-to-clear after a normal search and after an app-mode
lookup deep link. At the top of the results, pull past the trigger threshold:
only the search field should clear, focus, and show the keyboard. Results,
recursive popups, and back/forward history must remain usable. Repeat with an
empty field and after hiding the keyboard; type a new query and confirm the
old results remain until search is submitted. A pull below the threshold must
not clear the field or results. Explicit blank external lookups must still
reset the search as described above.
Also tap the search field's clear button after a normal search and an app-mode
lookup deep link, with the keyboard hidden: the field must clear, gain focus,
and show the keyboard while preserving results and navigation history.

Manual reader validation should cover:

- Renderer termination in paginated, continuous and VN modes: after moving
  beyond the saved bookmark (including a continuous scroll before its idle
  save), terminate the renderer and verify the loading frame restores the latest
  accepted position, settings, highlights and Sasayaki cue. Repeat during
  restore and after an internal fragment jump. Open lookup and native selection
  before termination; neither may survive or reappear from a stale callback.
  Check paused and playing Sasayaki, including an image auto-page hold, then
  verify navigation and Close during/after recovery. Trigger renderer exit only
  on the intended test WebView; do not force-stop the app or clear its data.

- cover image pages and multi-image illustration pages.
- `ReaderViewportWebViewTest` uses generated content without changing books or
  preferences to check vertical page height/padding, forward traversal to the
  last text and end-position restore at zero/nonzero padding and normal/large
  fonts. Run it via the explicit instrumentation runner with data-preserving
  APK installation; never use connected Gradle tasks on an existing device.
- paginated, continuous, and VN modes in vertical and horizontal writing.
  Check default and enlarged line heights with ruby and inline images: glyphs,
  readings, and images must remain visible without overlapping adjacent lines;
  page/scroll progress and restoring the same passage must remain stable.
- In continuous mode, align a line/column at the leading viewport edge, exit to
  Bookshelf and reopen the book several times. The first text must remain fully
  visible, including ruby annotations, without half-line clipping or drift.
  Check horizontal and vertical writing with normal/large fonts, minimum/default
  line heights and split inline spans. Recalculate and save progress after every
  restore so small exposed slivers cannot accumulate backward drift.
  `ReaderViewportWebViewTest` covers this loop with generated content in both directions.
- VN block and sentence screens, reveal speed 0/45/120, blank-area click
  advance, text lookup taps, links, images, restore, and chapter boundaries.
- VN cross-screen lookup with a word split at the current-screen boundary:
  verify the lookup text continues into later screens, the mined sentence
  includes the complete source sentence, `sentenceOffset`/Sasayaki alignment
  stays chapter-relative, and the underline/popup anchor uses only the visible
  current-screen intersection. Repeat for block and sentence modes, large-font
  viewport splitting, horizontal and vertical writing, ruby and supplementary
  characters, and both incomplete and completed reveal.
- long text page turns, chapter-list jumps into mid-book chapters, and bookmark
  restore.
- paginated and VN swipe thresholds below, at, and above the default value;
  verify zero disables both normal and fast swipe page turns while Page Up/Down
  and enabled volume-key turns still work. With a nonzero threshold, press at
  one position, add a second finger elsewhere, then release the first finger;
  the pointer handoff must not turn the page or suppress the next single-finger
  tap/lookup.
- Contents with nested and percent-encoded TOC fragments that share one XHTML
  file: verify distinct row positions/current selection, current-chapter
  centering without first flashing the default list position when the chapter
  tab opens, true chapter progress and time remaining, jump/restore behavior,
  and Gallery first-appearance order. After scrolling away, current-row updates
  must not reposition the open list; switching away from Chapters and back must
  center the then-current row once again.
  Include duplicate, gaiji, SVG, missing, and absent images; Gallery items must
  open the existing fullscreen copy/save/share viewer. Verify the tabs appear as
  Chapters, Highlights, Gallery, Search and none of their scrolling content
  stretches or glows past either edge.
- furigana Off/Dimmed/Toggle/Hidden in paginated, continuous, and VN modes,
  with horizontal/vertical writing and XHTML lowercase ruby nodes. Toggle first
  taps reveal adjacent ruby separated only by ASCII whitespace without lookup or
  page advance; punctuation or styled sibling elements stop the group. Second
  taps perform lookup. Verify highlights, Sasayaki, progress/restore, VN return
  to a revealed screen, profile switching/restart, and legacy boolean migration.
- forward and backward chapter boundaries, including reverse landing at the
  previous chapter end.
- page progress monotonicity, per-page progress updates, and restore landing
  inside large text nodes.
- lookup popup open/dismiss, recursive lookup, parent-scroll child dismissal,
  duplicate state, redirect history, audio/autoplay errors, Anki actions,
  Sasayaki controls, dictionary media images, outside tap dismissal, and
  absence of invisible touch blockers after dismissal.
- volume-key popup term navigation in paginated, continuous, and VN modes:
  verify the root or topmost recursive popup takes priority over enabled page
  turns and Sasayaki/TTS seeking, default and reversed directions reach adjacent
  term headers aligned exactly to the popup top without wrapping; repeat at
  popup scales 0.8, 1.0, 1.5, and 2.0. Verify long-press repeats work while
  asynchronously rendered terms appear, manual scrolling resumes from the
  visible term, and closing the popup restores the previously configured
  volume-key behavior.
- The shared Volume Keys Control Audiobook / TTS switch defaults off and retains the
  existing Sasayaki preference. Enable it with page turns and popup-term navigation:
  before starting book narration (including after preview-only playback), volume keys retain
  their existing page/system behavior. After explicit TTS playback, they navigate previous/next
  sentences while playing or paused, below topmost popup terms and above page turns. Check
  both directions, reversal, long-press repeat and key-up consumption. Closing/reopening or
  switching books resets eligibility; rotation retains the current session. Audiobook association
  leaves its existing Sasayaki priority intact. Pause, jump across a chapter boundary, and verify
  the sentence is revealed without audio; explicit resume starts the selected sentence. Passive
  paused chapter navigation must still stay where the user browses. Check sheet, reader toolbar
  and headset/notification previous/next preserve pause, while explicit replay/start-at can play.
- E-ink selection marks, horizontal and vertical lookup underlines, reduced
  motion popup scrolling, and slow-device first-visible-content behavior.
- reader chrome behavior: focus mode, transient system bars, Android Back
  revealing chrome before closing, bottom progress band, title/back-button
  settings, compact bottom buttons, and progress indicators hidden from the text
  area when configured. With whole-book and chapter progress both enabled,
  verify the top bubble stays on one line and the transient bottom bubble shows
  two complete lines.
- statistics reset time at 00:00 and a non-midnight minute boundary, including
  both sides of the boundary, app restart, a system time-zone change, and the
  Statistics dashboard using the same adjusted Today date.
- active statistics tracking while opening and dismissing Appearance, Contents,
  Statistics, Sasayaki, and fullscreen images. Modal time and character jumps
  must not be counted; overlapping surfaces and lifecycle stop/start must not
  resume tracking until every visible surface is closed.
- current-book cover publishing when enabled: opening two different books
  updates only the lock-screen wallpaper in sequence; fixed-file export keeps
  the same SAF document URI while replacing its PNG contents; missing covers,
  revoked URI grants, and device wallpaper restrictions leave Reader usable and
  report a localized failure. On compatible E-ink hardware, select the exported
  file in the vendor sleep-screen tool and confirm the next suspend rereads it.
  Also switch books rapidly while a wallpaper update is in flight and verify
  the newest cover wins. With portrait, landscape, very wide, and very tall
  covers, verify Fit shows the full cover with white padding, Fill preserves
  proportions while center-cropping, and Stretch fills the screen without
  preserving proportions; repeat in portrait, landscape, and split-screen. On
  compatible iReader firmware, first open any book in iReader’s built-in
  reader, then select the system Book Cover screen saver and tap Apply. Enable
  Hoshi’s iReader integration and open two books. Confirm
  `/data/zhangyue/logo/book` contains one newly named `.png` after each open and
  that the visible sleep screen changes without reapplying it in system
  settings. Repeat once with SELinux enforcing when the firmware supports it;
  vendor directory permissions or policy denial must leave Reader usable and
  report a localized failure.

For reader pagination bugs, inspect WebView metrics such as `scrollTop`,
`scrollHeight`, and `clientHeight`. If a page can still scroll but native
navigation changes chapter, compare native gesture boundaries with JS boundary
checks. For blank trailing pages, check small fractional overflow from images,
spacers, column gap, or CSS sizing.

For performance investigation workflow, see `docs/PERFORMANCE.md`.

## Bookshelf, Import, And Backup

Validate relevant bookshelf/import changes with:

- top-level Books, Dictionary, and Settings tab switching, confirming Navigation3
  back-stack behavior and selected tab state remain stable.
- Settings detail open/return behavior, confirming returning from a detail route
  lands back on Settings without changing top-level tab state.
- multi-EPUB DocumentsUI import and recursive EPUB folder import.
- EPUB and TTU bookdata imports whose metadata title exceeds 250 UTF-8 bytes,
  including Japanese and emoji: confirm the full title remains visible, the
  Reader opens, reimport deduplicates, Android TTU export/restore succeeds, and
  neither successful nor failed imports add persistent `ImportTemp` residue.
- external EPUB open/import requests, confirming the request is consumed once
  and routes to Reader after import succeeds.
- shelf-name entry, including user shelves named Reading alongside the virtual
  Reading Shelf.
- E-ink multi-select markers, confirming unselected books show empty circles and
  selected books show check marks.
- bookshelf covers for local and Google Drive books in expanded and collapsed
  shelves: verify missing covers show deterministic title artwork with an
  optional author, legacy metadata without an author remains readable, decode
  failures use the same fallback, and progress/read/multi-select overlays stay
  intact. Verify Show/Blur/Hide persist across restart in dark and E-ink themes;
  Hide must not reveal the real cover, Blur must visibly blur on Android 12+
  and safely show fallback artwork on Android 8-11.
- Manage Shelves: “Hide thumbnails when collapsed” defaults off and persists
  across restart. Enable it and confirm local, Reading, and Google Drive collapsed
  sections show only one title/count/arrow row, with no preview placeholder.
  Verify long shelf names stay on one line, headers still expand/collapse, and
  disabling restores previews without changing expansion state. Check all three
  cover modes, expanded cards, and Google Drive selection-mode restrictions.
- Google Drive bookshelf Recent/Title sorting: compare books with newer reading
  progress, newer audiobook progress, only bookdata last access, and malformed or
  missing timestamps. Recent places unknown times last; Title uses natural title
  order. Switch sort while refresh is delayed/offline, then refresh and import a
  remote book; remaining entries must retain the selected ordering.
- dark and E-ink editable text fields, confirming visible cursors and horizontal
  scrolling for long values.
- Android-created `Books` and `Dictionaries` `.hoshi` archives restored by iOS
  when backup compatibility changes.
- Android-created profile-aware `Dictionaries` `.hoshi` archives restored by
  iOS/older Android as the default Japanese single-profile dictionary config,
  and restored by current Android with all profile-scoped dictionary
  config/settings intact.
- Books context-menu EPUB export and Android-created TTU bookdata backup import
  by iOS/ッツ where possible.
- iOS/ッツ TTU bookdata backups imported into Android, confirming reader open,
  cover, progress, and statistics.
- bookshelf-to-reader open latency and loading frames when reader route or EPUB
  parsing behavior changes.
- Reader open/close behavior from bookshelf, confirming close returns to the
  previous bookshelf state and Android Back exits through the same close path as
  the Reader chrome back action.
- bookmark restoration and bookshelf progress refresh after returning from
  Reader.

## Statistics

Preserve existing app data when validating statistics:

- After upgrading with the former statistics and tab switches disabled, Stats
  is still visible. Its upper-right Settings button opens the tab-local settings
  route; returning from settings or a book editor preserves dashboard period,
  selected chart bucket, heatmap scroll and page scroll. Advanced has no Statistics row.
- Statistics settings groups both autostart switches together with the Reader
  access hint below, then reset time in its own group. Sync has a heading and
  Merge/Replace explanation below its two rows; Archive has a heading, a clear
  action and a book-count footer. Verify Sync is absent when global sync is off,
  Archive is absent when empty, and clear still requires confirmation. Check
  English/Chinese narrow layouts, light/dark and E-ink borders.
- Unset statistics sync defaults on; an explicit opt-out remains off. Both
  autostart options default off and work independently. Opening Stats does not
  start tracking, and existing daily goals and Reader display preferences stay
  unchanged. Check manual start/stop, modal pause, foreground/background and
  close-save paths alongside the Reader matrix above.
- Delete one book and a selected batch with active dates, no activity and an
  undecodable cover. Active history remains visible with an archive marker;
  required archive write/read failures keep the affected book and show an error.
  The distribution marks archived books with a small trash icon; their detail
  page has the same book title and Days section as local books, without an extra
  Archived label. Dashboard and book-page titles keep the same size and weight
  during navigation and loading; long book titles remain on one ellipsized line.
  Restore the same normalized folder via EPUB, Drive, TTU and Books `.hoshi`;
  newer dates win, equal dates keep the specified first input, and interrupted
  restore duplicates are counted once. Include NFC/NFD-equivalent paths and
  iOS archives with omitted cover metadata or long folder names.
- Open a distribution row from a short period and verify that the editor shows
  all dates in ascending order. Verify grouped row dividers, date and character
  count on the left, duration and chevron on the right, and matching chevrons in
  the book distribution. Check half-minute rounding, character/hour/minute
  editing, cancel, zero removal, single-day deletion from inside the edit sheet
  (no delete action on the date list), confirmed delete-all,
  last-archive cleanup and confirmed archive clearing. Failures retain input;
  return/resume refreshes aggregates. Merge may restore remote deleted records;
  deleting locally does not claim to clear the remote copy.
  In light/dark E-ink, the date rows have one continuous rounded outer outline,
  inset internal dividers, and an outlined delete-all button. Check one record,
  several records, and scrolling a long list; normal themes keep filled groups.
- The dashboard contains Daily Goal (gauge, history and display-only heatmap),
  Reading Time, and Books, with uniform headings and grouped cards. There is no
  separate calendar, heatmap date selection, yearly dropdown, Day segment,
  This Week card or weekly target editor. Upgrades preserve daily goals and data.
- Tap the daily goal to open the anchored value wheel without moving the page.
  It starts at the saved value, snaps after scrolling, and supports tapping a
  nearby value. Switching Characters/Duration preserves each saved target;
  outside tap and Back dismiss it. Cover both ends of each range, reopen,
  E-ink contrast and persistence failure/retry. In light and dark E-ink modes,
  both goal types have a visible fixed center outline enclosing the snapped
  value; normal mode retains its filled selection band.
- Check the current Week as the initial reading-time period, including while
  loading; returning from settings or a book editor preserves a manual selection.
  Week/Month/Year/All switching
  lives in Reading Time; each mode starts at the current period. Swipe the chart
  to browse earlier natural periods, stopping at the first activity and today;
  All does not page. Check cross-year weeks, leap February, locale week starts,
  empty buckets and incomplete periods. Only selected/adjacent chart pages are
  prepared even with decades of sparse history.
- Reading-time axes follow the calendar: Week shows narrow weekday names,
  Month marks locale week starts (April 2026 is 5/12/19/26 for Sunday-first
  locales), Year marks every two months, and All starts at the earliest month
  with three-month intervals, thinned for long histories. Dashed vertical grid
  lines align with tick dates; bars and tap targets share the date scale,
  including leap February and unequal month lengths. Bars have flat baselines.
  Verify the average line, 0/top-hour labels, complete rounded comparison with
  clearly distinguishable up/down arrows, and Characters Read / Reading Speed
  rows in English, Chinese, narrow layouts and enlarged fonts. Long comparisons may move below the value,
  but must not truncate. E-ink fills all bars without a single-bucket selection;
  with a selection, only that bar stays filled and the others become hollow.
  The unselected headline explicitly labels Week/Month as Daily Average and
  Year/All as Monthly Average in both languages. Selecting a bar shows that
  day's or month's total without an average label; clearing restores the label.
- The heatmap shows all history and initially scrolls to the latest dates.
  Reading intensity grows with character counts across all history; today
  has a subtle outline and the Less/More legend matches. Taps have no action. Changing chart mode,
  page or bucket must not move or recolor the heatmap. Manual heatmap scrolling
  and settings/editor round trips preserve its position.
- Heatmap weekday labels remain complete and centered on their corresponding
  rows in English and Chinese, including larger system fonts and E-ink mode.
  Their text height must not be clipped to the smaller heatmap cell height;
  labels and cells follow the same locale-specific first day of the week.
- Fast vertical dashboard swipes retain the fixed cards rather than recreating
  their charts on re-entry; use the Statistics flow in `docs/PERFORMANCE.md` for
  frame and trace comparisons. Heatmap horizontal position, chart selection,
  expanded book count and vertical position survive settings/editor round trips.
- Reading-time bars, summary rows and book rows display together. Within
  week/month a bar selects a day; within year/all it selects a month. The
  full chart remains while the headline, summary and books follow that bucket.
  Re-tapping the bar or the close button clears it; paging or changing mode
  also clears it. Settings/editor round trips and refresh preserve selection.
  Check empty buckets, the first partial month and future-bucket rejection.
  Book bars rank by time independently of goal type, five rows show initially,
  and each row shows grouped character counts and duration for the current
  period or selected bucket above its time bar. Check local and archived books,
  English/Chinese, large counts and larger fonts: metadata wraps when needed
  without clipping or squeezing the bar, and the whole row still opens the editor.
  Show More is left-aligned with the card content, has the same inset divider
  above it as the book rows, and expands the list. Once all books are visible,
  both the button and its divider disappear.
  No Books section appears for an empty result.
- In light and dark E-ink modes, initial display and clearing selection fill all
  reading-time bars, matching the full-period headline, summary and book list.
  Selecting one bar keeps it filled and makes all other bars hollow, matching
  the selected bucket's statistics. Re-tapping the bar, closing selection,
  paging or changing period mode restores all filled bars. Check switching
  between bars, short/narrow bars, and normal
  color mode retaining its existing color-based selection.
  Period and goal-type selectors have outlined tracks; the selected segment
  has a solid fill with inverse text in both light and dark E-ink.
- Compare average duration and prior-period percentages with missing reading
  days/months, including prior average zero. Goal history spans active and
  archived books regardless of selected period, recalculates after goal changes,
  and picks the earliest equal longest streak or best day.
- Exercise the daily-goal semicircle and its compact history metrics with both
  goal types, zero/partial/over-goal values, Chinese narrow screens, large values,
  the day-edit keyboard and E-ink contrast. Check that all section headings share
  the same style and inset, that compact chart/summary/book rows stay legible,
  and that history metric values and their detail rows align across both columns.
  Archive JPEG decoding/scaling needs a device check in addition
  to generated-file JVM tests.

## Dictionary, Audio, And Anki

Validate relevant dictionary/audio changes with:

- recommended dictionary downloads for JMdict, JMnedict, Jiten, and Jitendex.
- lookup frequency sorting in Auto/Ascending/Descending/Disabled modes across
  Reader, Dictionary, recursive popups and Process Text; explicit dictionary
  selection, equal/missing frequencies, disable/delete/reorder, update renames,
  profile switching and restart. Scan controls, frequency sort order and the
  conditional dictionary selector must share one Lookup card: dividers inside,
  rounded corners only on the outer group. `DictionaryFrequencyNativeTest` exercises the
  JNI bridge using generated dictionaries in a unique cache directory. Build
  with `./gradlew assembleDebug :app:assembleDebugAndroidTest`, install both
  APKs using `adb install -r`, and run only this class with `adb shell am
  instrument -w -e class moe.antimony.hoshi.dictionary.DictionaryFrequencyNativeTest
  moe.antimony.hoshi.debug.test/androidx.test.runner.AndroidJUnitRunner`;
  do not use connected Gradle tasks that reinstall or clear app data.
- manual multi-dictionary import with invalid/unreadable archives, confirming
  later archives still import and the error dialog lists each filename and
  reason in English/Chinese. Check all-failed and mixed batches, Unicode names,
  empty native diagnostics, cancellation and preservation of installed data.
  `DictionaryImportNativeTest` uses generated cache fixtures to verify native
  errors, including Unicode paths; run it with the same data-preserving
  instrumentation procedure as `DictionaryFrequencyNativeTest`.
- Low Memory Usage Mode with a large Yomitan archive. Automatic dictionary
  updates must use low-memory import even with this setting off; manual imports
  and updates must follow the setting, and automatic updates must not change it.
- dictionary row long-press deletion, keeping the left reorder handle dedicated
  to dragging.
- term/frequency/pitch/Kanji import, enable, reorder, delete, and update behavior
  across profile switches and dictionary backup restore. Categorize term
  dictionaries as none/monolingual/bilingual/exclude and confirm excluded data
  remains installed while disappearing immediately from lookup.
- stroke-order font download visibility: confirm it is absent without Kanji
  dictionaries, appears after importing one, shows the 17 MB confirmation,
  automatically imports a verified `KanjiStrokeOrders` font, and remains visible
  but disabled afterward. Confirm a failed or interrupted download leaves no
  partial font and restores the enabled action, then verify numbered strokes in
  Reader, Dictionary, and Process Text Kanji popups.
- local-first audio latency: with an enabled local source before slow or
  unreachable remote sources, default playback/autoplay and mining must use
  the local recording without requesting later sources. Opening the full
  recording menu may load remote candidates, but must not block concurrent
  default playback. Verify empty earlier sources still fall through in order.
  Long-press with delayed sources: the menu must appear with localized loading
  rows immediately, start all enabled sources concurrently, and make each
  completed source selectable in configured order. Select a later source while
  an earlier source is pending and verify playback/mining share its URL, even
  if autoplay or mining began before that selection. Concurrent default mining
  must not cancel a requested playback. Close
  or reset the popup during loading; late results must not reopen its menu.
  With definitions already scrolled, swipe inside the recording menu at its
  top/bottom and with too few rows to overflow: definitions must stay still,
  and the menu must not stretch or glow from overscroll.
  With overflowing rows, internal menu scrolling must still work; after
  dismissal, definitions must scroll normally again. In a short bottom popup,
  open a long recording list: it must fit above or below the audio button
  without covering it, including as delayed candidates arrive. Repeat with
  the trigger near the bottom and popup scales 0.8, 1, 1.6, and 2. In narrow
  popups, verify the entire menu border and wrapped source names remain visible.
  Geometry fixtures must model Chromium's zoomed `getBoundingClientRect()`
  dimensions separately from unzoomed `offsetWidth`/`offsetHeight`.
- default remote word audio: new settings list JapanesePod101, LanguagePod101,
  then Jisho. Upgrade from an enabled and a disabled old built-in proxy source;
  verify in-place expansion, retained custom/local sources and ordering, and
  persistence after restart. Existing custom entries using the old proxy URL
  must remain custom entries. Disable/reorder the new sources and verify
  playback and the candidate menu in Reader (all modes), Dictionary, Process
  Text, and recursive popups. Check kanji with reading and kana-only queries,
  a missing JapanesePod101 recording falling through to later sources, and all
  sources unavailable. Select a Jisho/LanguagePod101 candidate and verify Anki
  exports that recording; JapanesePod101's `audiomp3.php` URL must export as MP3,
  never as PHP or the known placeholder recording. Use deterministic HTTP/HTML
  fixtures in JVM tests; live service availability is a separate manual check.
  `AnkiRemoteAudioDeviceTest` is an opt-in live test for all three sources and
  actual AnkiDroid MP3 export. It requires initialized AnkiDroid with Hoshi's
  existing database-access grant, uses in-memory settings, and deletes only
  its three uniquely named notes in `finally`. Exported content-addressed media
  can remain in Anki's media collection. AnkiDroid may append filename suffixes;
  validate saved sound references and extensions rather than exact filenames.
  Build/install the test APK with the
  same data-preserving commands as `AnkiTagsDeviceTest` below, then run:

  ```bash
  adb shell am instrument -w -e class moe.antimony.hoshi.features.anki.AnkiRemoteAudioDeviceTest -e ankiRemoteAudioSmoke true moe.antimony.hoshi.debug.test/androidx.test.runner.AndroidJUnitRunner
  ```
- local audio database source ordering and per-source enable controls with
  imported MP3 and Opus `android.db` files. Disable the highest-priority source
  and confirm lookup playback and Anki audio export use the next enabled source;
  disable every source and confirm both return no local audio; then re-enable
  and reorder sources and confirm the preserved priority takes effect. In
  Reader, Dictionary, Process Text, and recursive popups, long-press the audio
  button and verify all local/remote candidates are named in source order,
  duplicate names are numbered, the current choice is marked, and an empty or
  failing source produces the disabled no-audio state. Select a non-default
  candidate and confirm immediate playback and both Anki backends use that same
  recording; autoplay, redirects, Kanji lookup, Back/Forward, and new popup
  results must reset entry-scoped candidate state. Repeat in light, dark, and
  light/dark E-ink themes at popup scales 0.8, 1, and 2.
- Sasayaki MP3, M4B, and Opus imports from local or seekable SAF sources before pressing Play: confirm the total
  duration is already visible; M4B/Opus title, artist, cover, and chapters load
  together without a delayed artist row; playback preparation may subsequently
  correct the displayed duration without resetting the current position.
- deinflection explanations for conjugated lookups such as `食べた`.
- popup theme contrast for deinflection explanations and JMdict forms tables.
- Dictionary tab and Process Text iframe popup cold paths after reader popup
  changes.
- Source-text lookup in Dictionary and external `PROCESS_TEXT`, `SEND`, and
  `TRANSLATE` entry points: use long/multiline text, leading punctuation with no
  initial match, repeated words, and supplementary characters before the tapped
  word. Verify the complete source stays visible; an empty Process Text root
  remains open and tapping a later word finds results. Confirm successful taps
  mark the matched span and preserve result scroll, while failed taps preserve
  results, selection, mining context, and history. Exercise back/forward,
  including `猫と猫` and `𠮟猫と猫`: tap the first then second occurrence and
  verify Back/Forward restores both the marked occurrence and mined cloze
  position. Test popup scales 0.8/1/2 with delayed result rendering while the
  source is partially scrolled; the visual scroll position must survive the
  temporarily empty entries container.
  Also exercise recursive children, Kanji, audio, outside dismissal, and swipe
  dismissal.
  Repeat with source sizes 12/22/48, light/dark/E-ink themes and profile switching;
  Reader and child popups must not gain source text. Mine through AnkiDroid and
  AnkiConnect and verify the full original sentence and exact repeated-word
  cloze position, including UTF-16 offsets after supplementary characters and
  a successful non-suffix glossary redirect followed by a failed source tap.
- Android AnkiConnect and AnkiDroid flows when Anki behavior changes, including
  duplicate checks, media references, add-note, and sync behavior.
- Anki tag templates through both backends: mix literal tags with
  `{document-title}` and `{expression}`; substituted spaces, tabs, newlines,
  non-breaking spaces, and full-width spaces become underscores without
  joining separate literal tags. Check missing title/unknown handlebars,
  independent format tags, `hoshi` on new/rebuilt formats, and preservation of
  saved empty/custom tags after duplication, restart, profile switch, and fetch.
  Before diagnosing AnkiDroid availability, confirm the installed package with
  `adb shell pm list packages anki`; the standard package is `com.ichi2.anki`.
  The opt-in `AnkiTagsDeviceTest` requires initialized AnkiDroid and an existing
  database-access grant for Hoshi Debug. It uses an existing deck/model, creates
  one uniquely tagged note, reads its saved tags, and deletes only that note in
  `finally`; it uses in-memory settings. Run it without connected Gradle tasks
  that reinstall or clear app data:

  ```bash
  ./gradlew assembleDebug :app:assembleDebugAndroidTest
  adb install -r app/build/outputs/apk/debug/app-debug.apk
  adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
  adb shell am instrument -w -e class moe.antimony.hoshi.features.anki.AnkiTagsDeviceTest -e ankiTagSmoke true moe.antimony.hoshi.debug.test/androidx.test.runner.AndroidJUnitRunner
  ```

- Reader, Dictionary tab, and Process Text popups with one, two, and three Anki
  formats. Confirm button order and icon size, independent duplicate state,
  disabled formats whose first model field is unmapped, and safe failure after
  deleting a format or disconnecting AnkiConnect.
- Anki Disable Button After Mining defaults off and appears only below
  enabled Allow Duplicates. Check profile persistence and that toggling Allow
  Duplicates off/on retains the safeguard preference. With both on, Reader,
  Dictionary and Process Text popups immediately show a disabled completed icon
  after each term/format succeeds, including terms already present in Anki.
  Rapid taps and late duplicate checks must not create another note or re-enable
  that button; failures remain retryable even when duplicate lookup is also
  unavailable. Other terms/formats remain independent.
  History back/forward retains the lock, even if creation finishes while that
  result is detached. Changing glossary selection, selected text or audio does
  not unlock it. Fresh results, child popups and closing/reopening the popup
  have independent state; late callbacks must not change replacement results.
  Repeat with AnkiDroid and AnkiConnect, and confirm existing behavior with the
  safeguard off.
- Reader, Dictionary tab, and Process Text popup Kanji redirects: tap individual
  Kanji in furigana and plain term headers, verify readings/meanings and
  back/forward restoration, and confirm one touch does not issue a duplicate
  click query. Repeat with numeric and H/L pitch data, 1-based nasal/devoice
  markers, cross-dictionary effective-pattern deduplication, compact pitch,
  dark mode, and light/dark E-ink mode.
- Anki format add/edit/duplicate/delete behavior across app restart and profile
  switches: a duplicate receives a new stable ID and automatic name while
  copying the source icon, deck, model, mappings, and tags, then returns to the
  format list; all values persist, a fourth format cannot be added or duplicated,
  and the last format cannot be deleted. Confirming a deck/model fetch resets
  every format mapping while retaining IDs, names, icons, and tags.
- Duplicate-note search through AnkiConnect `guiBrowse` and the AnkiDroid card
  browser deep link for collection, deck, and deck-root scopes, including the
  all-models option and the global hide-search-button setting. Confirm the
  search button is absent when duplicate checking reports no matching note.
  Include AnkiDroid 2.25+, whose `CardBrowser` activity is not exported: launch
  through the public `anki://x-callback-url/browser?search=...` link, scoped to
  the detected package. Do not directly launch `CardBrowser` or rely on the
  internal `search_query` / `all_decks` extras. The public link retains
  AnkiDroid's browser deck selection: with an unrelated deck selected, switch
  its browser to All Decks and confirm Hoshi's original query still finds the
  expected notes. Test Japanese, spaces, quotes, plus signs, and ampersands in
  the query so URL encoding cannot change the search or add URL parameters.
  The opt-in `AnkiBrowserDeviceTest` exercises the production API from Hoshi's
  UID against installed, initialized AnkiDroid without creating or editing
  cards. Build `assembleDebug assembleDebugAndroidTest`, install both APKs with
  `adb install -r`, then run only that class with
  `adb shell am instrument -w -r -e class moe.antimony.hoshi.features.anki.AnkiBrowserDeviceTest -e ankiBrowserSmoke true moe.antimony.hoshi.debug.test/androidx.test.runner.AndroidJUnitRunner`.
  It opens the browser with a no-match `nid:0` query; preserve all app data.
  With an existing `あり難い` note, verify the `あり難い` dictionary result also
  shows the duplicate icon and disables mining when duplicates are disallowed.
  AnkiDroid first-field checksums must follow Anki’s NFC normalization before
  HTML stripping; do not use NFKC, which would also fold full-width/half-width forms.
- Exact cloze output for repeated matches, incorrect stored offsets, and
  supplementary-plane characters; selected-glossary fallback `None`,
  `{glossary-first}`, monolingual, bilingual, and both category fallback
  directions; all ordinary/brief/no-dictionary monolingual and bilingual
  handlebars against persisted dictionary order, excluded dictionaries,
  profile switches, and version-suffixed titles. Validate numeric and H/L pitch
  graph single/multiple/deduplicated output and
  `{pitch-accent-graphs-first}`. Popup pitch text includes 1-based nasal/devoice
  markers; exported Anki pitch SVGs follow iOS and do not encode those markers.
- Set an enabled term dictionary to `Exclude` in Anki Advanced. Reader,
  Dictionary tab, Process Text and child lookups must still show its definitions.
  Mine `{glossary}` and its brief/no-dictionary variants: they must omit that
  dictionary, as must `{glossary-first}` and category fallback mappings. Explicit
  `{single-glossary-Dictionary}` and manually selected `{selected-glossary}`
  mappings, including their variants, must still export it. Repeat with all
  dictionaries excluded, after changing categories, after restart and after
  switching profiles. Only disabling a dictionary removes it from lookup.
- Anki Advanced keeps `{selected-glossary} Fallback` visible as a literal
  handlebar label, accepts both custom text and every preset fallback option,
  and separates general settings, fallback editing, and the grouped term
  dictionary category list in iOS order. Confirm all four category labels,
  especially `Monolingual`, remain fully visible without abbreviation.
- Cross-format audio, Sasayaki audio, cover, and dictionary-media mining through
  both backends. Recheck after restart and profile switching without clearing
  app data.

When adb text input for Japanese is unreliable, inspect input methods before
assuming Japanese cannot be entered:

```bash
$ANDROID_HOME/platform-tools/adb -s emulator-5554 shell ime list -s
$ANDROID_HOME/platform-tools/adb -s emulator-5554 shell dumpsys input_method | rg -n "mCurrentSubtype|Subtype|RotationList" -C 2
```

With Japanese Gboard QWERTY, romaji input can test `食べる` / `たべる`:

```bash
$ANDROID_HOME/platform-tools/adb -s emulator-5554 shell input tap <search_x> <search_y>
$ANDROID_HOME/platform-tools/adb -s emulator-5554 shell input text taberu
$ANDROID_HOME/platform-tools/adb -s emulator-5554 shell input keyevent 66
```

If automation is blocked, allow manual input and continue from the current
emulator state without clearing data or reimporting.

## Settings, Themes, And Localization

Validate relevant settings/theme changes with:

- New or changed persisted settings sources must have delayed-first-emission
  behavior tests: no controls before every required source emits, actual stored
  values on first composition, localized read failure and retry, and ordinary
  updates without resetting to defaults. Profile-scoped owners also cover profile
  changes with delayed old-profile completions. Test flow/state behavior rather
  than scanning production source. `SettingsReadinessTest` exercises the common
  Compose entry point, re-entry, source replacement, retry and loaded-null content;
  `TtsPanelStateTest` also delays the actual panel's preference store. These use
  in-memory stores and can run with the data-preserving APK install/instrumentation
  procedure, without resetting the installed app.
- For profile-owned storage changes, pause reads and saves across dispatcher
  boundaries, switch profiles, then resume: one operation must keep its original
  target for reading, missing-file initialization and writing. The newly active
  profile must not receive the previous profile's settings.
- Save non-default switches/selections, then enter, leave and re-enter Anki (including
  format, advanced and connection pages), Dictionary and its editors, Display,
  Statistics, Reader Appearance/Behavior, Word audio, Sasayaki, TTS, Sync, Wallpaper
  and About. Inspect the first visible controls: they must already match stored
  values, with no off-to-on or selection jump caused by loading. Reader sheets
  follow the same rule. Loading/failure leaves Back available; retry loads real
  values. Static menus remain immediately usable, and About with no update record
  shows its empty state rather than waiting indefinitely.
- Switch between profiles with different Anki, Dictionary and reading settings;
  no previous-profile values or late request results may become editable in the
  new profile. Editing an already-loaded value must retain the controls, dialogs
  and current draft rather than restarting the initial loading screen.
- Compose scrolling surfaces across Bookshelf, Contents, Statistics, Settings,
  and modal sheets do not stretch or glow past either edge; normal scrolling,
  flinging, pull-to-refresh, and sheet dragging remain available.
- settings controls update immediately and route changes avoid fade transitions
  on E-ink displays.
- dark-theme cold start does not show a light `No Books` frame before bookshelf
  loads.
- build variant labels keep release as `Hoshi Reader` and debug as
  `Hoshi Debug` on English and Simplified Chinese devices.
- reader/dictionary theme combinations in Light, Sepia Light, Dark, Sepia Dark,
  Custom, System, and E-ink modes, including status/navigation icon contrast,
  lookup taps, popup display, reader color updates without unnecessary WebView
  reload, and cursor visibility.
- Reader Appearance fonts: cancel and retry an offline recommended-font
  download without changing the active font; verify one Noto variable download
  enables every advertised weight while static families download variants
  independently. Verify family and named variant use separate selectors and a
  default-variant download shows progress directly below the selected family.
  On an OEM build without Noto, verify the two platform choices remain labeled
  as generic system serif/sans-serif. Restart and switch profiles to confirm
  per-family variant memory. Import two TTF/OTF variants of one family, replace
  an existing family/weight/style slot, confirm its old basename selection and
  dictionary CSS alias still resolve, delete the family, and verify WOFF/WOFF2
  remain standalone while TTC is not offered. Recheck paginated, continuous,
  and VN rendering in horizontal/vertical writing plus Reader, Dictionary, and
  Process Text lookup popups.
- App Language on Android 13+: the Advanced settings Language card appears,
  Follow system clears the app locale, and Android 12 or lower follows the
  system language without showing the card.
- `docs/TRANSLATING.md` remains aligned with supported locale resource
  directories.

## Sync, Updates, And Sasayaki

Validate relevant sync/update/Sasayaki changes with:

- Google Drive Device Code connect/sign-out, clear cached Drive folders/covers,
  remote-only bookshelf list, pull-to-refresh, import, long-press delete,
  transient network behavior, manual import/export result dialogs,
  reader-open import-only behavior, auto-export timing, close/background flush,
  statistics merge/replace, and Sasayaki last-position sync. For automatic
  bookshelf refresh, test offline/DNS failure, stalled OAuth token and Drive
  list responses, and connection reset: cached entries remain visible without
  an error dialog. Repeat via manual refresh/import/export and confirm a
  localized error. HTTP authorization/server errors and TLS failures must stay
  visible even during automatic refresh. OAuth and Drive connect/read waits
  use 10-second timeouts (not a total-operation deadline).
- GitHub update prompts, skip-version, manual checks, completed-download
  prompts, user-triggered install, same-version APK cleanup, and split APK
  updates on arm64-v8a and armeabi-v7a targets.
  Cover API fallback with a local HTTP server: a redirect to a `200 text/html`
  homepage must retain both request/final URLs, status, content type and body
  without entering the JSON parser; `403` responses must retain the original
  body and rate-limit/retry headers. When all candidates fail, the visible
  diagnostic and saved stack must retain every URL and nested original cause.
  Cancellation must propagate without trying another source. Verify a normal
  JSON release response and an on-device manual check against GitHub.
- Sasayaki defaults, reader toggle visibility, auto-scroll, auto-pause on
  lookup, subtitle cue matching that starts immediately after selecting a valid
  SRT without a separate Match action, lookup taps on active cue text in normal
  and E-ink VN rendering, skip controls across reader/sheet/system media controls,
  volume-key seek, safe-area playback controls, and e-ink cue/lookup overlays
  when those areas change. For subtitle matcher changes, include a
  combined-volume EPUB with separate per-volume SRT files: both volumes should
  align automatically, repeated text in the other volume must not capture the
  starting cue sequence, unique cues immediately before the selected starting
  sequence should be recovered while repeated prefix text remains unmatched,
  and a large local text gap should recover only from a coherent later cue
  sequence.
- Sasayaki highlight rendering changes: on a real WebView, compare unhighlighted
  text and active cues in horizontal/vertical writing with ruby and publisher
  `text-emphasis`. Verify complete glyph edges, ruby, and emphasis marks as well
  as stable text positions. CSS Highlight API availability and unchanged Range
  geometry alone do not establish correct painting. In VN, repeat the first cue
  pass, revisit cues on the same screen, then leave and return to rebuild the
  screen; check both normal and E-ink modes.
- Sasayaki transcription: import MP3/M4B/M4A or Opus through the audiobook card
  and select Transcription; there must be no second audio picker or permanent
  model-download notice. Starting with missing or invalid models or runtime files asks to download
  the missing size before any network request; cancelling or leaving Reader
  dismisses the request without writing an empty transcript or changing existing
  progress. Cached models plus runtime files and realignment must not prompt.
  Verify a model-only cache requests just the runtime, and fully cached resources
  work offline. Runtime files must be read-only and SHA-256 verified before native
  loading; corrupt or cancelled downloads must not publish a library. Check release
  APKs contain no FFmpeg/ONNX/sherpa native libraries, and component release assets
  match the URLs, byte sizes and hashes embedded in `transcription-runtime.json`. Display processed/total
  audio time only once, both while running and paused; resuming must retain it
  during preparation so the controls do not jump as the source is loaded. Check model
  download/progress, pause, close/reopen the sheet, and background/foreground the
  app. Closing the sheet must keep transcription progressing while reading;
  reopening must show that same task with Pause enabled. Match coverage and usable
  cues must grow while transcription is still running, without requiring Pause.
  Check first-batch publication and throttled updates after further batches,
  including slow matching (no queue of obsolete snapshots), silence (no redundant
  rematching), a partial sentence extended by later speech, and a previously
  unmatched gap gaining a right-hand anchor. Live updates must preserve the
  playback position, current page, VN typewriter reveal, lookup popup, and image
  hold/automatic resume. Completion must agree with a fresh full alignment.
  The opt-in `SasayakiIncrementalMatchDeviceTest` accepts `-e matchBookRoot <root>`;
  it reads an existing book/transcript without writing sidecars and places timing
  and allocation results in `cache/incremental-match-benchmark/report.json`.
  Verify Lightweight/Balanced/Fast defaults to Balanced and persists across restart.
  Preset selection is locked while running; pausing, changing preset and resuming
  must retain committed progress, and closing/reopening the sheet keeps the choice.
  With cached models, `SasayakiTranscriptionDeviceTest` compares all three presets'
  native token text/order/timestamps on an explicit scratch `realClip`, and checks
  cancellation in Fast followed by resume in Lightweight. Pure pipeline tests cover
  out-of-order completion, bounded work, silence ordering and failure/cancellation.
  After pausing, Resume
  must work without changing tabs to refresh the audio source. Backgrounding
  the app must not actively pause the task; while its process can execute,
  completed batches continue advancing and returning shows the latest progress.
  A pending download confirmation must remain unconfirmed through a background/
  foreground cycle. Removing the Reader route saves and pauses, including when
  waiting for download approval; reopening offers Resume. Configuration recreation
  must not be treated as leaving the route. Background execution is best-effort,
  with no foreground service or WorkManager guarantee; after process reclamation,
  starting again resumes the saved checkpoint;
  incomplete speech segments must neither vanish nor duplicate. Changing the
  source or duration starts a new transcript. Complete transcripts offer
  realignment; confirming Clear Transcription keeps the existing match. Verify
  another book cannot start concurrent transcription, and deleting the active
  book cannot recreate its files. Check download/decoder failures use localized
  errors and leave usable checkpoints; retry with cached models while offline
  and confirm no downloading label appears. Keep the screen awake while the
  Reader transcription task is active, including with its sheet closed.
  Compare highlighted passages and timing with the book/audio, especially
  introductions, repeated text, ruby, silence, omitted sentences and audio ends.
  Reproduce ASR omissions with PCM from the production decoder, preserving its
  equal-weight channel mixing and absolute sample clock; generic FFmpeg mono
  conversion can change the input gain. Confirm the original token text and
  timestamps on the target device before comparing segment boundaries or padding;
  desktop quantized inference can differ even with identical PCM and model files.
  Inspect energy scores and actual recognition input ranges to distinguish
  skipped speech from words omitted by recognition.
  Check quiet openings, changes in background level, expressive loud dialogue,
  short replies with syllable gaps, and final words just after a 20-second hard
  cut. Segment rejection must not discard a short continuation of accepted speech.
  Around silence-separated ASR segments, verify the next word's first character
  survives even if its model timestamp falls inside leading context. Compare
  continuous and checkpoint-resumed output for missing or duplicated prefixes;
  keep hard-cut speech context. Existing transcripts may recover short word
  fragments between real anchors; short entirely omitted interior cues can share
  a neighboring highlight but must not receive fabricated independent word times.
  Partial-word repair must not bridge token-free long silence. Explicitly recognized words after a
  long pause must retain their token times. Check kana/kanji rewrites around a
  local exact phrase and punctuation, a missing whole reply beside a recognized
  prefix, ambiguous readings on either side of a sentence boundary, and a partial
  ruby reading error sharing one base character. Repaired syllables must preserve
  the exact syllables' timing; deleted letters inside a spoken word must not allocate
  a neighbor's token to an independent omitted cue. A single short cue enclosed by reliable
  anchors and complete speech tokens should retain the book's wording even when ASR
  mishears it; check rewritten short replies, numeric values, contracted phrases,
  and `%`/`％` output. Contextual cross-cue allocation must not split a recognized
  neighboring word to invent a missing interjection. Long estimated token intervals must
  retain recognized words, sentence endings, and supported rewrites without a fixed
  duration cap. A short suffix attached to the next sentence's exact anchor must stay
  with the recognized earlier sentence when its local context supports that ownership;
  let a wholly omitted interior sentence share a neighbor rather than take that suffix
  as its own cue. Check one- and two-character
  endings, a fully or partially recognized middle sentence, and competing endings
  including ruby readings. Check omitted questions split by commas, missing sentences
  beside long first/last tokens, and short cries/replies with ordinary token durations,
  both with and without an inter-cue pause. Include mixed gaps containing a missing
  sentence-ending character, a whole omitted reply and/or a missing next-word opening:
  restore edge fragments only to their own cues before assigning the whole reply.
  Check short neighboring cues with contiguous recognized context; context must stop
  at missing text or a long audio pause, and inferred text must not become new evidence.
  Grouping must preserve the opposite cue's timing,
  keep text/time ranges non-overlapping, update IDs/offsets and unmatched counts, stop
  at real intervening matches and never propagate across chapter boundaries or book ends.
  Sparse sentences must retain supported fragments; dense cues must also split around
  token-free gaps across long silence. Distinguish unmatched ASR wording from silence,
  including overlapping same-frame multi-character tokens. Incremental matching must
  agree with full alignment.
  Character coverage and subtitle cue match rate are different metrics; neither
  alone verifies transcript accuracy. Exclude toc/caution/colophon source paths
  for both SRT and transcription, retaining existing SRT multi-volume behavior.
  `SasayakiTranscriptionReaderTest` checks asynchronous audio-source readiness,
  download confirmation/cancellation, a single progress display, closing/reopening
  controls, completion during reading and background/foreground continuation
  using isolated cache fixtures. `SasayakiAudioDecoderDeviceTest` checks native
  stereo downmixing, anti-alias filtering, absolute seek phase, MP3/Opus gapless beginnings,
  AAC decoding, consistent first-audio-track selection, bounded descriptor slices,
  and failure cleanup using generated
  WAV and tracked synthetic compressed tones. Run it after native audio changes;
  all fixtures live in cache and do not change books or preferences. Native
  decoder tests require the verified runtime cache. To provision it explicitly,
  run `SasayakiRuntimeDeviceTest#installRuntimeFromPublishedUrls` with
  `-e downloadRuntime true`; this downloads only the pinned components and
  verifies subsequent preparation emits no download prompt/progress. The separate
  `verifiedPrivateLibrariesLoadAndCachedFilesWorkOffline` method uses isolated
  cache storage and accepts `-e runtimePayloads <scratch-directory>` for local
  payloads or `-e downloadRuntime true` for public download verification.
  `SasayakiTranscriptionDeviceTest` also checks the Kotlin decoder boundary; its optional
  native ASR test needs verified models and runtime files in the app's no-backup directories
  and an explicit `-e realClip` scratch audio path; otherwise it is skipped and
  never downloads models. Build the test APK separately, install with `adb
  install -r`, and run the explicit class with `am instrument`. Remove only
  the scratch audio afterwards. Also pause within the final 1.5 seconds: an
  Android checkpoint must still offer Resume until all audio is processed.
- Sasayaki linked and copied Ogg Opus playback with `testdata/opus_test.opus`.
  Confirm its title and artist metadata, all 22 `CHAPTERnnn` chapter entries,
  current-chapter centering without first flashing the default list position
  when the chapter tab opens, chapter seeking, and EPUB-cover fallback because
  this sample has no embedded cover. Validate embedded Opus cover display with
  a separate OpusTags
  `METADATA_BLOCK_PICTURE` fixture when cover parsing changes. Scroll away and
  let playback cross a chapter boundary without allowing selection updates to
  reposition the list; switching away from Chapters and back must center the
  new current chapter. If no chapter is current when Chapters opens, later
  playback updates must not trigger an automatic scroll.
- Sasayaki media-session notification return behavior, confirming it restores
  the existing app task instead of creating a duplicate task.
- Sasayaki paused task-removal behavior, confirming that pausing playback and
  then removing the app from recent tasks clears the Sasayaki notification and
  leaves no active media session or foreground service.
- Sasayaki long-running background playback on a physical device. Check the
  package app-op before triage. The normal path should use Media3's
  MediaSession APIs and Android's `mediaPlayback` foreground-service type.
  Sasayaki may customize notification rendering only through Media3's
  `MediaNotification.Provider`; Media3 must own foreground-service start/stop
  for the same MediaSession-backed notification id while audio is playing or
  buffering. Paused task-removal cleanup should clear the service player before
  calling Media3's `pauseAllPlayersAndStopSelf()`. Do not add a second fallback
  notification path, private notification action receiver, or direct
  NotificationManager cancellation path around Media3. A detached transport
  notification without a foreground service can leave the process in
  cached/background-restricted state and make playback stop while the
  notification remains visible:

  ```bash
  adb shell appops get <package-name> | rg 'RUN_ANY_IN_BACKGROUND|START_FOREGROUND|WAKE_LOCK'
  adb shell dumpsys activity services <package-name> | rg 'SasayakiPlaybackService|foreground|startRequested|allowStartForeground'
  adb shell dumpsys notification --noredact | rg 'hoshi_sasayaki_playback|android.mediaSession|NotificationRecord'
  ```

  `RUN_ANY_IN_BACKGROUND: ignore` or `deny`, or
  `ActivityManager.isBackgroundRestricted() == true`, means the device or user
  is intentionally restricting background execution. Android's documented
  behavior for that state is aggressive background restriction; at minimum,
  foreground services cannot be started unless an app activity is in the
  foreground. Do not classify background playback failure in that state as a
  Media3 lifecycle regression until the same flow is reproduced with the app
  allowed to run in the background. Record both restriction states in manual QA
  notes.

## Japanese TTS

- `TtsRepositoryTest` verifies active/inactive voices, source rates, dialogue
  choices, credentials and WAV/MP3 selection through edit/reload. Deferred independent
  setting edits must preserve newer credentials and behavior settings. Switching sources
  retains the existing shared rate behavior for installed/built-in engines and independent
  rates for custom servers.
- Change extra sentence pause while narration is playing: keep the current sentence and
  cached audio, and apply the new delay at the next sentence boundary. It must not pause,
  restart synthesis or resume previously paused playback. Controller/audio repository
  tests cover this separately from provider-side synthesis silence parameters.

- Narration/dialogue routing: run `TtsDialogueTest`, `TtsCustomDialogueTest`,
  `TtsRepositoryTest`, `TtsAudioRepositoryTest`, `TtsPlaybackControllerTest` and
  `TtsExportTest` in the JVM suite. Cover paired/nested/unmatched Japanese quotes,
  cross-sentence/paragraph dialogue and chapter reset; ruby and sentence-end image
  glyphs, symbols/emoji and replacements across role boundaries; source-anchored lookup
  with repeated text; first activation after choosing a voice while disabled, retained
  dialogue after reactivation and per-Source restart restoration; ordered request-local
  voices, identical-voice coalescing, invalid voices, partial synthesis cleanup/retry,
  cache separation and inactive-source edits/late callbacks. Compatible exports keep
  MP3/WAV; incompatible formats use M4A, while corrupt input still fails and cancellation
  deletes partial exports. Prefetch/cache counts remain sentences, not voice fragments.
  On a connected device, explicitly run `TtsDialogueUiTest` and
  `TtsDialogueExportDeviceTest` using the APK-preserving installation procedure below.
  The UI fixture covers ordinary/e-ink selection, refresh, initial/re-enabled routing
  and the fixed Preview footer. Local PCM fixtures verify the real AAC codec with
  different sample rates/channel counts and full sentence duration, without credentials.
  Also check installed-engine dual voices and reading/replacement preview/Anki in
  paginated, continuous and VN modes, paused navigation, images and background controls.
  Build success alone does not establish device UI or codec validation.
- Online-source regressions: run `OnlineTtsProtocolTest`, `OnlineTtsTransportTest`,
  `OnlineTtsEngineTest`, `MicrosoftTranslatorSessionTest` and `TtsMp3Test` with the JVM
  suite. Cover literal SSML/JSON,
  UTF-8/surrogate-safe chunking, credential headers, provider errors, cancellation,
  complete WebSocket turns, partial-file cleanup, MP3 frame concatenation, supported
  styles/roles and synthesis parameters. Translator coverage includes signing vectors,
  concurrent/expiry token refresh, bounded authentication retry, malformed responses
  and canceled authorization. Keep live network calls out of JVM tests.
- With a connected non-disposable device, build the test APK with
  `:app:assembleDebugAndroidTest`, install APKs via `adb install -r`, and explicitly run
  `OnlineTtsDeviceTest` through `am instrument`. It uses public Japanese samples,
  test-owned cache and encrypted test values, without changing saved settings. Require
  the final `OK` summary. Verify Edge, Google Translate and Microsoft Translator MP3
  exports with MediaExtractor,
  including multi-part sentences; verify source-bound Keystore credential round trips.
  Translator tests also verify an advertised speaking style with pitch/rate/punctuation
  controls, 16 kHz PCM and 48 kHz MP3 exports, without reading saved Azure credentials.
  Run `TtsContinuousPlaybackDeviceTest` for high-rate Edge playback through the real
  controller, audio repository and Media3 decoder. It uses public sentences, an isolated
  player/cache and in-memory preferences, and must complete every sentence without
  changing the installed user's source, credentials or book data.
- Azure/Google Cloud live verification requires the user's configured account. Check
  missing/invalid credentials, 429/quota and offline errors, draft test/cancel/save,
  Japanese catalogs, WAV/MP3 exports, and supported parameters. Never claim mocked
  response coverage as a successful live account test. Reopening configuration must
  keep the saved key masked; leaving it empty preserves it. An unavailable
  Keystore key must report a configuration error.
  Azure's region dropdown starts at Choose a region and never preselects the first item.
  Verify compact rows displaying only region codes, scrolling to the end of the list, valid
  saved selection on reopen. Save/Test
  require a supported selection; changing it clears previous verification. Cancel leaves
  the saved region unchanged. `TtsProviderConfigurationTest` and `TtsPanelStateTest`
  cover UI drafts and ViewModel validation with isolated preferences.
  Delete key appears only for saved cloud credentials and requires confirmation. Cancel
  preserves the key; confirm deletes it immediately without Save, retaining region and
  other preferences and clearing the input/test feedback. A write failure retains the key
  and draft and permits retry. Subsequent blank Save/Test must report missing credentials
  rather than restoring or using the removed key. `TtsProviderConfigurationTest`,
  `TtsPanelStateTest` and `TtsRepositoryTest` cover these paths with isolated settings.
- Compare default/modified pitch and volume in preview and Anki. Azure/Translator style/role choices
  must come from the selected voice, reset when changing voices, and include only valid
  styles. Test exact leading/trailing/punctuation silence, including zero vs voice default.
  Verify normal provider pause sliders span 0–1000 ms and extra sentence delay spans
  0–2000 ms, both allowing 300 → 310 ms. Run
  `TtsPauseControlsTest` explicitly with `am instrument`; it uses isolated Compose
  controls and in-memory preferences without network requests or saved-device changes.
  Google Translate's engine rate offers normal/slow only. Unsupported pitch controls must be hidden.
- Hoshi playback speed is independent of engine rate, spans 0.5–3.0× in 0.05 steps,
  and defaults to 1×. Change it during narration, preview,
  preparation and pause: ready audio and pending prefetch remain reusable, preview
  keeps playing, and paused audio does not resume. Reopen settings/recreate the service
  player to verify persistence; test Google Translate's two engine rates independently.
  Verify pitch and Anki audio remain unchanged. `TtsPlaybackRateDeviceTest` checks real
  local PCM duration at both endpoints, chunked sentences, attachment and paused state
  without network or saved-device changes; run it explicitly through `am instrument`.
  `TtsRepositoryTest`, `TtsPlaybackControllerTest` and `TtsAudioRepositoryTest` cover
  normalization, persistence, pending work, cache reuse and unchanged export synthesis.
- Extra sentence delay remains independent of playback speed.
  Verify extra sentence delay waits only during continuous playback, is cancelled by
  stop/next/book/source changes, and resumes at the next sentence if paused in the gap;
  this application delay must not be inserted into Anki exports.
- In Voice, verify Preload sentences supports 1–20 and defaults to 5
  for new settings or settings without a saved count; preserve an explicitly saved count. During playback, increase the count to fill the larger window
  and decrease it to cancel excess pending synthesis. The current sentence must keep
  playing and completed audio must remain reusable. Repeat while paused, at book end,
  after reopening settings and with short sentences. Restore the original count.
  `TtsPlaybackControllerTest`, `TtsRepositoryTest` and `TtsAudioRepositoryTest` cover
  bounds, persistence, moving windows and cache reuse; keep export/source configuration
  behavior independent of prefetch depth.

- Explicitly run `TtsVoiceSettingsTest`
  and `TtsProviderConfigurationTest`, plus `TtsPanelStateTest`, using `am instrument`. Their isolated UI/callback
  fixtures cover long lists, raw IDs, third-party engines, cloud actions, preview cancellation
  and credential drafts without saved-device changes, real keys or network calls.
  `TtsPanelStateTest` also verifies error isolation and installed-engine preview playback,
  stop and natural completion with public Japanese text in test-owned cache. Pass
  `-e ttsEngine <installed-offline-engine-package>` to isolate this test from a network-backed
  system default; on the Samsung validation device use `com.samsung.SMT`. This must not
  change system preferred-engine settings.
- Online catalog reuse: open/close the panel, switch away/back to a source, and restart
  the app after its first successful list load. It must reuse the saved voice list without
  network requests, clearing the list, or flashing loading state; there is no expiry refresh.
  Region/key changes must use the new configuration, failed/corrupt loads must be retryable,
  and explicit error retry must fetch again. Catalog files must contain no API key or bearer
  token. `OnlineTtsEngineTest` covers every source, concurrent requests, engine recreation,
  config changes and force retry with fixture responses. `TtsVoiceCatalogCacheTest`
  covers same-configuration request coalescing, independent source requests, cancelled/
  failed refresh, memory eviction, optional empty lists and persisted catalog reload.
  During a slow self-hosted refresh, cached catalog reads must return immediately rather
  than wait for the network; `CustomTtsEngineTest` checks this against a local test server.
  `TtsPanelStateTest` checks warm
  panel lifecycle/state without network. Keep installed-engine resume refresh so new
  Japanese data remains discoverable. In e-ink, check the Voice tab and voice picker show
  Loading voices text while loading; other themes retain the animated progress bar.
- In focus mode, the existing top-right playback button controls Sasayaki for a loaded
  audiobook and TTS for books without an associated audiobook. On a fresh Reader session,
  the TTS button and bottom playback controls must remain hidden, including after preview
  alone. Start TTS manually from its panel or a lookup popup: both controls become available
  and remain visible when paused. Closing/reopening or switching books hides them again.
  Start TTS from the middle of a chapter; first playback must begin at the displayed position.
  Pause, browse elsewhere, and resume: the existing narrated sentence resumes. Check play/pause icon updates,
  cancellation while preparing, and lookup auto-pause: pressing the pause icon cancels
  auto-resume when the popup closes. Toggle Show Audiobook / TTS Toggle in Appearance:
  it hides either source’s button. This preference remains editable with Sasayaki disabled,
  and disabling Sasayaki must not hide or disable TTS. Associated unavailable audiobooks
  never show a TTS fallback button. Playback failure opens the existing TTS error panel.
  `ReaderTopPlaybackControlTest` covers source callbacks and shared visibility preferences.
- Check Reader TTS in the shared 70% bottom panel: only sentence controls remain above
  Voice/Replacements/Settings, without a cover, book title or sentence preview.
  Confirm Source and Voice have explicit labels in one compact card and open separate lazy bottom lists,
  without search fields. Sources group all installed engines, online services and key-based
  cloud services; unknown saved sources/voices remain visible and selectable under raw IDs.
  Cloud rows alone offer credential actions; tapping an action must not select that source.
  Only installed sources show the system TTS settings action. Check list scrolling, selected
  checkmarks and dismissal/return. Connection status is supporting text with the Voice label,
  determined by the selected voice’s catalog network requirement. Verify online and offline
  voices within the same installed engine update that status; unknown saved voices have
  no inferred status. Voice names retain their original identifiers in the card and picker,
  and each catalog voice row identifies its network requirement.
  Check engine rate/playback speed/pitch sliders retain current-value labels without endpoint
  labels or dense ticks, keep existing steps/ranges, and save on
  release. Pitch zero uses `0 st`/`0 Hz`. The theme-colored Preview button stays visible outside
  the scroll area, above system navigation, in both the reader sheet and standalone Settings,
  including compact panels and e-ink. Scroll between Source and the last synthesis parameter
  and confirm its position and tap target remain fixed. It must show preparing/stop states
  and cancel or stop its own preview; reading controls still operate the book session.
  Test credential draft/cancel/save with blank-key retention, loading, verified/error states,
  and cancellation on dismiss. Source errors stay beside the voice card and cannot leak into
  Replacements/Settings; failed shared behavior writes still report an error in Settings.
  Compare light/dark, sepia and e-ink layouts, dictionary add/edit/cancel.
  Edit shared behavior and colors
  in Settings and confirm Sasayaki reflects them, and vice versa; change two different
  controls without waiting for recomposition and confirm both values persist after reopening.
  Repeat with Reader appearance and audiobook sync settings; restore original values.
  TTS must not expose seconds-based navigation or change audiobook-only preferences.
  Check long source/voice names wrap without crowding the card and remain complete in
  selection lists; check empty/long dictionaries, e-ink outlines, and standalone TTS
  Settings. Compare Sasayaki's tabs, resource cards and sliders after shared UI changes.
  In each source's Voice picker, use the fixed top Refresh voices action: it must bypass
  the catalog cache, keep the picker open and the selected ID unchanged, disable repeated
  refresh taps during loading, and retain old choices after failure. Confirm a successful
  refresh includes newly advertised voices and survives reopening without another request.
  Check manual OpenAI IDs remain available during refresh and e-ink uses loading text.
  Parameters appear directly without a separate heading or toggle. With Azure, Microsoft Translator or
  Google Cloud selected, check Audio format and Sample rate: short labels/values fit on one line at normal font size,
  while dropdowns open from the right-side arrow. Short values must leave remaining
  width to the label; long values must stay bounded without pushing the arrow out.
  Provider pause switches use plain rows matching Settings, retaining default/custom
  supporting text and showing their unchanged sliders when enabled. Remaining parameter
  cards have compact padding; Audio format/Sample rate appear as plain rows. Check long labels and wrapped supporting text without
  overlap, and confirm Sasayaki/shared behavior rows retain their layout.
- `TtsBookRepositoryTest` covers XHTML self-closing script/style tags in both
  inline and cached chapter sources: body text, ruby, image boundaries and sentence
  offsets must survive, while ordinary HTML retains malformed-markup recovery. It also
  covers shared chapter reuse, LRU eviction and separate reopened-book identities.
  Opening Reader must not prepare all book text or start audio. Open TTS in a middle chapter,
  start there, and navigate/continue across empty or non-reading chapters: previous/next
  availability, full-chapter dialogue/ruby, image holds and pause state must remain correct.
  `TtsPlaybackControllerTest` covers cross-chapter prefetch limits, paused navigation,
  image holds, deleted sentences and cancelled chapter reads after switching books;
  `TtsSessionPlayerTest` covers media commands at chapter boundaries.
- In TTS Settings, Use Ruby readings defaults on for new and existing preferences.
  Toggle it off with a ruby sample such as 今日 annotated キヨウ: playback, full-sentence
  preview and Anki sentence audio must send 今日 to the engine. Re-enable to use キヨウ;
  custom replacement 今日 → きょう must win in either state, retaining book/global scope
  precedence. Source text, ruby visibility, lookup and highlight offsets stay unchanged.
  The flag is global across books/sources and survives panel/app reopening; shared Sasayaki
  settings must remain unchanged. During pending synthesis, toggling it pauses/invalidates
  preparation so obsolete callbacks cannot resume; resume uses the new setting/cache key.
  `TtsTextTest`, `TtsRepositoryTest`, `TtsAudioRepositoryTest` and `TtsPlaybackControllerTest`
  cover these boundaries; `TtsPanelStateTest` covers the actual Settings switch and reopening.
- Long-press book text and choose Replace beside Highlight: prefill the selected original and
  empty replacement text, and preview the full source sentence including ruby and VN
  text outside the screen. Select across paragraphs containing LF/CRLF line breaks: Matching
  text must contain the complete selection with line breaks removed, without hiding later
  lines in its single-line field; the saved rule must use that same text. Matching remains
  sentence-based, so a rule spanning sentences does not replace text across those boundaries.
  Include a selection starting inside ruby annotation and ending
  in the following base text (for example, only `々` in `瑞々`): Replace must open and
  exclude annotations. `ReaderNativeSelectionContextWebViewTest` exercises real DOM
  ranges and VN source projection in both writing directions, forward/backward selections,
  text/element end boundaries, annotation-only ranges and emoji, checking unchanged native
  endpoints. Run it with the data-preserving explicit instrumentation procedure above.
  Check the dialog title identifies TTS, the equal-width
  scope selector beside its label, compact rule/regex row, and full-width themed secondary
  preview button (including its busy state),
  in both languages and light/dark/e-ink themes. Cancel without saving. With a multilingual IME, start in another input
  language and focus each original/replacement field: both should request Japanese.
  Repeat from the TTS replacement editor: existing entries show a theme-danger-colored
  Delete action on the same row as Cancel/Save; new entries show only Cancel/Save.
  Check Dictionary search still requests the
  selected content language and retains its Search action; no system keyboard preference
  should be changed. IMEs without locale-hint support may retain their current language.

- Create literal punctuation rules such as `――` → `、` and `……` → `。` in both
  book/global scopes. Preview, playback and `{sentence-audio}` must use the same rules;
  source text, sentence boundaries and highlighting must stay unchanged. Check
  longest-match/book precedence, editing/deleting, exact
  punctuation matching (different dash characters stay distinct), non-kana and multiline
  replacements, empty-match validation and duplicate conflicts. Leave replacement text empty
  in literal and regex modes: save/reopen the deletion rule, verify the list identifies deletion
  and the preview shows the actual result. If the whole sentence disappears, Save remains
  enabled and Preview is disabled with an empty-result message. Continuous playback skips
  consecutive deleted sentences, preserving image holds and lookup pause/cancellation; single
  sentence playback and the end of a fully deleted book stop without errors. Anki exports
  of an empty result report no readable text and never create empty audio files. Replacement text is plain text,
  never executable SSML, and inserted replacements are not recursively matched.
  Reopen the app with existing pronunciation entries: they must load unchanged; saving
  a replacement or changing synthesis settings must preserve old rules and scopes.
- In replacement creation/editing, toggle regex for `([\p{Han}])々` → `$1$1`:
  `時々` becomes `時時`, while an explicit `人々` → `ひとびと` and EPUB ruby keep priority.
  With ruby disabled, the same base text becomes eligible for regex. Verify original/actual
  TTS input for both modes, full-source selection context and editable Settings sample text.
  Test invalid patterns/group references, zero-width matches, supplementary kanji, overlapping
  patterns, book/global scope, existing entry editing and mode persistence after reopening.
  The Preview button must not play an obsolete draft while text updates; its configuration
  snapshot must match the displayed result. During scope changes, Preview and Save stay
  visually enabled when the previous result was valid. Click Preview before calculation
  completes: it must play the new result once ready; another edit cancels the queued click.
  Save during calculation must validate/persist the current scope and text. Check unselected
  scope options blend with the dialog surface in light/dark/e-ink themes, and section title,
  small before/after labels, subdued original and stronger output color remain visually distinct;
  original and output body text use the same size, including editable sample text. Check normal/e-ink dialog scrolling and footer.
  With a long sentence preview and the software keyboard open, the replacement field's
  Done action dismisses the keyboard without saving; then Save persists the draft.
  Open new and existing replacements from Reader and Settings: the first visible dialog
  must include its initial validation/preview content, without an empty intermediate
  layout that changes window size. Hold preview computation pending while editing:
  keep the dialog/result area visible and never play the old result while the current result is pending.
  `TtsWordEditorTest` controls the preview dispatcher to check both transitions.
  `TtsRegexReplacementTest`, `TtsRepositoryTest`, `TtsAudioRepositoryTest` and the non-destructive
  `TtsWordEditorTest` cover matching, persistence, preview/export/cache and Compose controls.
- Run `TtsTextTest`, `TtsRepositoryTest`, `TtsBookRepositoryTest`, `TtsPlaybackControllerTest`,
  `TtsAudioRepositoryTest`, `TtsExportTest`, and `AnkiSentenceAudioTest` with the JVM suite. They cover source
  ruby/offsets, file-backed chapters, literal scope precedence, long input splitting,
  pending/paused replacement edits, stale callbacks, audiobook exclusion, cancellation
  cleanup and the independent Anki handlebars. JS selection tests include full VN source
  context rather than screen-clone text.
  Hold a later synthesis pending while requesting already cached audio: it must return
  immediately. `AndroidTtsEngineTest` repeats this through the real router/audio repository
  with narration/dialogue routing and a controlled system-engine synthesis queue. It also
  covers per-engine/automatic catalog isolation, coalesced cold discovery, forced refresh
  and cached reads during failed/cancelled/pending refreshes. Installed-engine catalogs
  must remain memory-only; ordinary playback/preview/export reuse them while panel resume
  and manual Refresh voices force discovery. Concurrent misses for one sentence share
  synthesis; clearing requires a fresh file. Advancing playback must retain pending
  prefetch and extend its horizon.
  Fill the 32-sentence cache, replay an old multipart sentence and prefetch 20 new
  sentences: all replay files must remain readable and a further replay must reuse them
  without synthesis. Evict the least recently used sentence instead. This is covered by
  `TtsAudioRepositoryTest`.
- With a Japanese engine installed, build `assembleDebugAndroidTest`, install both
  APKs using `adb install -r`, and run only the explicit classes
  `moe.antimony.hoshi.features.tts.TtsEngineDeviceTest` (including punctuation replacement
  playback/export using isolated rules/cache) and
  `moe.antimony.hoshi.features.tts.TtsSessionPlayerTest`, plus
  `moe.antimony.hoshi.features.reader.ReaderTtsHighlightWebViewTest` via `am instrument`.
  These discover installed voices and synthesize into test-owned cache, without changing
  engine preferences or library data. Require `OK (7 tests)` (or the current test count).
  Discovery tests query every installed engine reporting Japanese support and synthesize
  each of its offline Japanese voices. `TtsLocaleTest` covers both `ja` and Samsung's
  `jpn` locales. After installing voice data in system settings, return to an already-open
  TTS panel and verify that its voice choices/error state refresh without reopening it.
  The engine tests include image-backed ruby through the playback repository invoked
  from Main. `TtsTextTest` covers zero-width insertions (including shared/end positions),
  invalid ranges and sentence export: every source cursor step must advance or finish,
  and glyph readings must not alter Reader offsets. Include a chapter with image-backed
  ruby in continuous playback checks so the configured prefetch path is exercised.
  The Reader fixture verifies actual sentence highlight pixels across all six
  mode/writing-direction combinations using an explicit saturated highlight color;
  do not apply opaque-color thresholds to the default translucent highlight.
- In each paginated/continuous/VN mode and writing direction, play from the current
  position and selected sentence; check full source ruby, highlight position, chapter
  transitions, page following, image holds and lookup pause/resume. Change voice/rate
  while preparing and verify playback pauses. Edit readings during playback, while
  paused and while preparing; replay/next must use the new reading. Check overlapping
  entries, book/global scope, duplicate errors, persistence and context preview.
  Pause narration and navigate within the chapter, then use Contents to select both
  earlier and later chapters. Reader must retain the manual position after chapter
  restoration and after background/foreground. Returning to the narrated chapter may
  restore its highlight without jumping to it or completing a VN reveal. Resuming
  narration follows its retained sentence only when auto-follow is enabled.
  `ReaderTtsPresentationTest` covers paused/playing, same/different chapters,
  auto-follow, preparation/restore races and obsolete sentence/stop states.
  `ReaderPlaybackPresentationTest` covers ordered tail/intermediate/target chapter
  image holds, direct jumps, zero-duration holds and pausing during a hold. Both
  TTS and Sasayaki must use this shared traversal. During natural TTS playback,
  bring Reader back to foreground while an image is held: the image must remain
  visible for its configured duration; opening it fullscreen must defer continuation
  until dismissal. Manual next/previous/replay must skip intervening media.
  `ReaderTtsHighlightWebViewTest` exercises actual image pixels followed by sentence
  highlight pixels across all six mode/direction combinations, including a simulated
  restore callback while TTS is preparing.
- Open a source lookup popup with and without TTS already playing. Verify replay stops
  after its selected sentence, toggle reflects playback and cancels pending lookup
  auto-resume, and play-forward closes the popup and continues from that source sentence.
  Nested dictionary popups must not acquire a book playback anchor; linked audiobooks
  keep their original controls. `ReaderTtsPopupTest` and the reader popup JS tests cover
  source visibility and command routing; controller tests cover replay/forward boundaries.
  For both Sasayaki and TTS, tap another book word while lookup auto-pause is active:
  the old popup must be replaced without any intervening audio or auto-page. Dismissing
  the final popup or tapping a lookup miss resumes once; nested popup dismissal and
  selecting a replacement word retain the pending resume. Explicit pause cancels it.
  For TTS, also pause at sentence completion, during the configured sentence delay and
  during next-sentence synthesis. Closing lookup advances from finished audio; the last
  sentence and single-sentence replay stay stopped. `TtsPlaybackControllerTest` and
  `ReaderWebViewStateHolderTest` cover these states. `TtsLookupPauseDeviceTest` forces
  lookup pause before the real Media3 completion batch using local PCM and isolated
  preferences, without network requests or changing the user's sources/library.
- Test Home/lock screen, notification/headset previous/next sentence and pause/resume,
  interruptions, task removal while preparing, and explicit Reader exit. After TTS has
  created a media session, leave and reopen a book and switch to another book before
  starting playback. Source parsing must keep the empty media session idle with
  `isLoading=false`; only preparation of an explicitly started sentence is media
  buffering. `TtsSessionPlayerTest` covers this transition without changing library data. The session
  must expose no time seek commands. Check no engine, missing Japanese data, removed
  selected engine, offline network voice, timeout and retry without clearing app data.
- Associate/remove an audiobook: TTS stops, its sheet closes and Reader entry hides;
  removal restores the entry without autoplay. A missing associated file must stay in
  Sasayaki recovery. TTS Settings/dictionary stay accessible. Sasayaki's enable switch
  must not disable TTS; behavior colors/auto-follow/image holds/pause are shared.
- In Anki, verify old/default mappings never synthesize TTS. Only a selected template
  containing `{sentence-audio}` prepares audio; no match for an associated audiobook
  reports failure without TTS fallback. Referencing both audio handles for the same cue
  uploads once. Cancel a popup during synthesis, retry errors, and check no temporary
  export survives cancellation. Create test cards only with explicit user authorization.

## Self-hosted TTS

- Add multiple named OpenAI Compatible and VITS Simple API sources from Source. Save
  without requiring a network connection; saving a new source must select it immediately
  and prepare its voice choices without starting playback. Editing an unselected source
  must retain the current selection; failed saves/cancel must not switch sources.
  Test an unsaved draft, then cancel. Saved keys
  stay blank, and Cancel/close cancels requests. Test ordinary and e-ink presentation.
  In both custom and cloud configuration sheets, connection testing, its success/error
  result and Save/Cancel must remain visible outside the scrolling form. Complete a
  delayed success/failure after scrolling and verify no second scroll is needed, the
  test button stays in place, and editing connection fields clears stale feedback.
  E-ink uses a static test indicator; canceling still terminates the pending test.
- OpenAI bases include `/v1` (or the server's API prefix); VITS bases are server roots.
  In the Model ID field, the trailing list action opens a nearby menu for one or more
  models without changing the entered ID; selecting updates that same editable field.
  Empty/failed lists retain manual input and the entered ID. Verify loading disables
  repeated requests, canceling the editor cancels discovery, and e-ink uses a static icon.
  Verify reverse-proxy prefixes, optional keys, manual OpenAI model/voice IDs when lists
  are absent, VITS Japanese speaker names and compound model/speaker identifiers.
- Switch servers and restore each model/voice/rate/format. Rename the selected server or
  edit/delete another while narrating without interruption. Editing active synthesis
  configuration pauses; deleting the active server selects system default without playing.
- `CustomTtsDeviceTest#selfHostedVitsCatalogPlaybackCacheAndAnkiExport` accepts explicit
  instrumentation argument `vitsUrl` to opt into a caller-provided service. It reads speakers,
  synthesizes public samples as WAV/MP3, exercises isolated Media3 playback and sentence
  export, and uses only test-owned cache and settings. No model/configuration mutation,
  private book text, application preferences, cloud keys or application data clearing.
  `selfHostedOpenAiCatalogRefreshPlaybackCacheAndAnkiExport` accepts `openAiUrl` and
  `openAiModel` for the equivalent OpenAI flow. Both explicitly refresh the catalog and
  verify cached choices before exercising WAV/MP3 playback, reuse and sentence export.
- VITS requests must send `Content-Type: application/json` without an appended charset.
  `CustomTtsEngineTest` exercises the server's strict JSON/form distinction, optional
  OpenAI discovery, prefix/auth/rate mapping, invalid audio and cancellation cleanup.
- Keep OpenAI manual Preview/Play usable while optional voice discovery is pending. A
  temporary network/catalog error must not persist an empty voice list; test successful
  recovery, while unsupported discovery endpoints remain usable without repeated loading.
- Configure available OpenAI voice IDs in Source, one per line, then choose under Voice.
  Check persistence and de-duplication with discovered IDs, including a pending/failed
  optional list request. Editing available IDs keeps the chosen voice and ongoing audio;
  VITS voices use server-provided character names and compound model/speaker identifiers.
- Select self-hosted WAV/MP3 only under Voice; Source configuration contains
  no duplicate format control. Changing format preserves the selected voice and other
  server profiles, and switching sources restores each server's format.
- Cloud/self-hosted sources default to MP3. Select WAV and MP3 and verify both choices
  survive an unrelated settings edit and repository recreation. Installed system synthesis
  continues to use WAV. `TtsRepositoryTest` covers these persistence boundaries.
