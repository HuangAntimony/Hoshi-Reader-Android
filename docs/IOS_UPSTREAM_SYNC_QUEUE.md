# iOS Upstream Sync Queue

This document tracks open Android work after checking iOS upstream `develop`.

- Source: `reference/Hoshi-Reader-iOS`
- Baseline for this refresh: `42e7b81d441c164c3a446152f4bf2356135d1e82`
- Latest checked: `origin/develop` at `d24b2fa6e5fc559c7d4032b4db355eecdd669b29`
- Checked on: 2026-09-21
- This refresh advances the reference by 4 reachable commits. The baseline is
  an ancestor of the new tip; new behavior and the remaining popup slice were
  checked against current Android code.

## Current Queue

Only open work is listed below. After removing completed slices, renumber the
remaining slices consecutively from 1 and update all slice references.

### 1. Lookup popup two-column layout and dictionary CSS isolation

Status: pending Android sync.

Commits:

- `ed25036` - masonry layout and popup visual redesign.
- `8d1442e` - add Yomitan danger/success theme variables.
- `0a91398` - scope dictionary CSS to div wrappers.

Dependency/value reasoning:

- This is a shared popup asset/settings slice used by Reader, Dictionary tab,
  and Process Text. Land persistence and bootstrap values before JS/CSS layout.

iOS behavior to mirror:

- Dictionary settings add a Two-Column Layout toggle. Multi-dictionary glossary
  cards use masonry/two-column layout when enabled and keep one column otherwise.
- Popup cards, padding, theme accents, and definition image canvas sizing match
  the refreshed design. Dictionary styles target `:where(div)[data-dictionary]`
  so labels carrying the same dictionary name do not inherit glossary styles.

Android current gap:

- `DictionarySettings`/repository and `DictionaryView.kt` have no
  `twoColumnLayout` setting.
- `LookupPopupHtml.kt` injects compact glossary and pitch options but no two-
  column flag. `popup.js` has no masonry/ResizeObserver path, uses
  `maxCanvasSize = 128`, and `popup.css` lacks the refreshed cards and danger/
  success variables.
- The glossary selector in `popup.js` still targets every `[data-dictionary]`
  element. Height is already configurable to 1000 in `ReaderAppearanceView.kt`;
  the upstream height increase requires no remaining Android work.

Suggested slice:

- Add profile-aware setting persistence and bootstrap injection, port the final
  asset behavior and div-scoped dictionary styles while preserving Android
  bridge calls, with focused behavior tests.

Validation:

- Reader, Dictionary tab, recursive lookup, and Process Text with one/multiple
  dictionaries, collapsed sections, long glossaries, images, mining/audio
  buttons, dark/e-ink themes, reduced motion, and outside dismissal.
- Run `node --test app/src/test/js/*.test.mjs`, focused settings tests,
  localization tests, and lint.

### 2. Sasayaki on-device transcription and match coverage

Status: pending Android sync.

Commits:

- `d24b2fa` - local audio transcription, resumable transcript storage, alignment
  and character coverage; also changes the source filter used by SRT matching.

Dependency/value reasoning:

- Independent feature. Establish an Android-capable local transcription backend
  and timed-token contract before persistence, alignment and UI integration.
  Confirm platform capabilities against official Android/Google documentation
  during implementation; Apple Speech APIs are not portable.

iOS behavior to mirror:

- On supported iOS 26 devices, choose Subtitles or Transcription and an audio
  file (MP3/M4B/M4A). Japanese speech-model download, transcription progress,
  estimated remaining time and alignment state are shown. Pause or leaving the
  sheet cancels transcription and aligns retained tokens; deleting the active
  book requests cancellation. Only one transcription task runs at a time.
- `sasayaki_transcript.json` stores `through`, `duration` and tokens containing
  `text`, `start`, `end`. Progress checkpoints approximately every 15 seconds;
  a saved matching duration resumes from `through`. Complete transcripts can be
  realigned. Clearing transcription requires confirmation and keeps the match.
- Timed tokens align to normalized book text using anchors and bounded diff
  blocks, then sentence/segment boundaries produce chapter-relative matches.
  Timing budgets reject implausible spans and allow short-gap interpolation.
- Both SRT and transcription source building additionally skip paths containing
  `toc`, `caution` or `colophon` (case-insensitive). Coverage is summed match
  lengths divided by the book character count, rather than matched cue count.

Android current gap:

- `SasayakiSubtitleMatchSection.kt` only parses selected SRT files and invokes
  `SasayakiMatcher.match()`; there is no transcription selector, backend,
  model-download state, pause/resume flow or transcript-clear action.
- `SasayakiSidecarModels.kt` and `BookStorage.kt` expose match/playback data but
  no timed-token transcript model or transcript persistence API.
- `SasayakiMatcher.match()` filters linear/nav/guide-TOC chapters but lacks the
  new path exclusions and timed-token alignment/sentence segmentation.
- `SasayakiModels.matchRateText()` uses `matches.size / (matches.size + unmatched)`;
  it does not report character coverage. `ReaderSasayakiCues.chapterCuesJson()`
  already sorts by text offset, so that upstream portion is covered.

Suggested slice:

- Implement a repository-owned local transcription backend and compatible
  checkpoint storage, then alignment and state-driven localized UI through the
  existing Sasayaki boundaries. Keep the tested SRT coherent-start/recovery
  behavior while adding source filtering and character coverage. Android audio
  access must use the existing SAF/repository path rather than iOS bookmarks.

Validation:

- Generated timed-token fixtures: kana/width/case normalization, ruby, repeated
  text, punctuation/block boundaries, supplementary characters, chapter/image
  offsets, excluded paths, partial transcripts and short/implausible gaps.
- Check unavailable models/devices, download failure, cancellation, leave/reopen,
  restart/resume, duration mismatch, deletion, clear-with-match-retained and
  complete-transcript realignment. Recheck SRT multi-volume matching, coverage,
  Reader cue rendering, playback and Anki audio export without clearing app data.

### 3. Chinese Anki fallback label

Status: pending Android sync.

Commits:

- `8ccade5` - Simplified Chinese localization updates.

Dependency/value reasoning:

- Small independent localization correction; no dependency on transcription.

iOS behavior to mirror:

- The fallback label translates its descriptive suffix while retaining the
  literal `{selected-glossary}` handlebar.

Android current gap:

- `values-zh-rCN/strings.xml` still defines `anki_selected_glossary_fallback`
  as `{selected-glossary} Fallback`, identical to the English resource.
  Other inspected Anki, dictionary, Reader and statistics labels already have
  Chinese resources; different established terminology is not a missing feature.

Suggested slice:

- Translate the suffix, preserve the literal handlebar and resource parity,
  and align the existing Anki label validation guidance with the localized text.

Validation:

- Localization resource tests and lint; check Anki Advanced in both languages
  and verify that braces remain literal rather than interpreted markup.

## Open Commit Inventory

| Commit | Date | iOS summary | Android status |
| --- | --- | --- | --- |
| `ed25036`, `8d1442e`, `0a91398` | 2026-06-14 / 07-01 / 08-22 | Popup layout/themes and dictionary CSS isolation | Pending settings/assets and div-scoped styles |
| `d24b2fa` | 2026-09-20 | On-device transcription and matching updates | Pending transcript/backend/alignment/UI, source exclusions and character coverage (2); cue ordering covered |
| `8ccade5` | 2026-09-17 | Simplified Chinese localization | Pending Anki fallback-label suffix only (3); two-column copy belongs to (1) |

## Suggested Implementation Order

1. Popup layout/CSS isolation (1): shared lookup presentation and settings.
2. Chinese Anki fallback label (3): small independent correction.
3. Sasayaki transcription (2): backend/token contract first, then persistence,
   alignment and UI; no dependency on the other slices.

## Covered Or No Android Action

- `02ed801`: no demonstrated Android-visible gap. In paginated mode,
  `ReaderContentStyles.kt` uses border-box sizing: the font-size addition to
  page height is balanced by the same addition to bottom padding, so it does
  not itself establish an extra visible blank strip. `reader-paginated.js`
  uses the corresponding internal page height for page steps. Continuous mode
  uses actual viewport height and ordinary padding; VN uses the separate
  visible-height stage. The retained `hanging-punctuation` CSS declaration is
  not evidence of working Chromium punctuation hanging. Do not remove this
  coupled geometry solely to mirror the WKWebView change without demonstrating
  an Android behavior difference.

- `15aadf0`: `reader-paginated.js` already appends its trailing spacer
  unconditionally, including zero vertical padding. Its Android-specific zero
  physical width is intentional.
- `d24b2fa` (cue ordering): `ReaderSasayakiCues.chapterCuesJson()` already
  sorts chapter ranges by `start` before serializing them to the Reader.
- `8ccade5` (remaining localization): Android Chinese resources already cover
  the inspected statistics/archive, furigana, search, frequency sorting, Anki
  format and stroke-font controls. The iOS string-table routing fix has no
  Android analogue. New two-column help text belongs with slice 1; the remaining
  untranslated fallback-label suffix is tracked in slice 3.

- `bdf71a6`: shared Reader CSS no longer overrides WebKit line-box containment,
  matching upstream default line-box sizing. The obsolete JVM assertion that
  required the declaration is removed; Reader layout is checked in WebView.

- `7dd3f49` (automatic updates): `DictionaryUpdateService` always enables
  low-RAM import for `AutoUpdate`; manual imports and updates retain the user's
  setting without changing it. Existing mutation coordination remains shared.

- `222a72b`: JNI import results retain native error details. Batch failures
  preserve filename and localized reason for the existing error dialog while
  continuing later files; cancellation propagates and staging/rollback remain
  repository-owned. Successful imports publish changes even if later cancelled.

- `165992a`, `e849e36`: profile-scoped Auto/Ascending/Descending/Disabled lookup
  sorting and an enabled frequency-dictionary selector feed native options
  through the shared lookup paths. Dictionary updates preserve selected title
  references across profiles; unavailable selections retain native fallback
  semantics without replacing query sessions on settings changes.

- `bd21e24`, `8024df1` (stroke-order font attribution): excluded from Android
  sync at the project owner's request. Android retains its dynamic/custom/E-ink
  color choices; changing the fallback accent and adding the font attribution
  to About are not planned.

- `42e7b81`: iOS replaces custom SwiftUI Reader overlays with UIKit navigation
  and toolbar controls; the options menu and centered reading information
  already existed before this commit. Android `ReaderBottomChrome` and
  `ReaderMenuCard` already provide those controls, while `ReaderChrome.kt` owns
  focus visibility and content insets. No concrete Android behavior gap is
  established by this platform implementation change.

- `b7f09ca` (search portion): `ReaderSearchEngine` matches literal paragraph text
  case-insensitively with sentence/bracket-aware snippets and a 100-hit cap.
  Search jumps show a transient blue range after restore in all three modes;
  page navigation clears it without persisting a highlight record.

- `00f95c4`, `21971bb`: Reader highlights persist optional iOS-compatible
  `textFurigana`, shown in Contents with plain-text fallback. Exact chapter raw
  ranges recolor the existing ID or remove it when the same color is selected.
  All three modes share this behavior; VN reads text/readings from its source
  stream and rebuilds screen projections, including overlapping highlights.

- `53fdb72`: Reader route loading failures now resolve to one generic state.
  `ReaderOpenFailurePage` shows localized neutral error UI and closes through
  the same typed route callback as normal Reader chrome.

- `7d7321f`: Reader renderer termination recreates the WebView through a
  generation in `ReaderWebViewStateHolder`, restores the latest accepted
  position (or unfinished navigation target), reloads settings/highlights/cues,
  and resumes queued Sasayaki handling after restore. The dead view is removed
  and destroyed; its selection, popup and JS callbacks are invalidated.

- `4dae37c`: `DeviceCodeDriveAuthorizer` and `GoogleDriveClient` use 10-second
  connect/read timeouts. Sync-layer network failure classification lets
  `BookshelfViewModel` suppress offline, timeout and connection failures only
  during automatic refresh; explicit operations retain localized errors.

- `e6e2b4b`: `DriveSyncFiles.lastAccessMillis` projects the newest progress/audio
  timestamp, falling back to bookdata last access. Bookshelf ViewModel applies
  Recent/Title sorting to cached and refreshed remote entries without downloading
  books or changing local metadata.

- `d8c086d`, `93ba3be` (statistics): Stats is always available, with tab-local
  settings, archived deletion/reimport, folder-keyed daily editing and natural
  calendar/all-time overview plus historical goals. Active/archive dates merge
  by modification time using iOS-compatible sidecars and backup paths. Unset
  sync defaults on; saved daily goals, opt-outs and Reader display preferences
  remain, and Android keeps its two independent autostart settings and daily
  goal defaults. Weekly summaries use the reading-time chart instead of a separate goal.
  The current week opens by default; period paging and chart day/month drilldown drive
  continuously displayed reading-time and book sections. The daily goal heatmap
  is display-only and independent of chart selection.
- `703347a`, `b7f09ca` (shared ruby normalization only): native
  `ReaderTextFilter` and shared `reader-text-semantics.js` include Korean text;
  native visible text excludes `rt`/`rp` contents. Reader-facts version 3
  refreshes cached counts and TOC offsets. Existing Sasayaki matches are retained;
  affected books need the SRT selected again. The search portion
  of `b7f09ca` is also covered above.
- `7b9dda8`: both Anki backends resolve tag handlebars through the field resolver,
  replacing substitution whitespace with underscores. New/rebuilt formats use
  `hoshi`; saved tags retain their values.
- `baccc84`: Android popup audio now returns every ranked, named local
  candidate, exposes named enabled sources to the shared iframe, and provides a
  long-press menu whose selected URL is shared by playback and Anki mining.
  Popup replacement, redirects, Kanji lookup, and history restore clear the
  entry-scoped candidate state.
- `beb46ba`, `969b978`: Dictionary search now shows tappable source text with
  profile-scoped 12–48 sizing, matched spans, preserved redirect scroll, and
  original-sentence/UTF-16 Anki cloze context. The same root-only behavior also
  covers Android Process Text/shared lookups, including an empty initial match.
- `15d4a6e`, `23e0764`, `a4e16df`, `253a589`: Android now provides
  profile-scoped Off/Dimmed/Toggle/Hidden furigana modes, migrates legacy hide
  booleans, consumes Toggle reveal taps, reveals whitespace-adjacent ruby, and
  retains VN reveal state across screen rendering.
- `c6b29c8`, `1db2cd3`: Android now stores the first EPUB creator as optional
  compatible book metadata, renders deterministic title/author fallback artwork,
  and applies persisted Show/Blur/Hide privacy modes across local, remote,
  expanded, and collapsed bookshelf covers. Android 8-11 safely maps Blur to the
  hidden fallback because the platform blur effect starts on Android 12.
- `eb86431`, `c31c9d0`, `ff86caa`: the paragraph fragmenter and explicit font
  request are WKWebView-specific selection/layout workarounds. Android's
  paginated reader already locks Chromium WebView scrolling during native
  selection and awaits used fonts before restore; copying the DOM fragmenter
  would add offset, highlight, progress, and Sasayaki mapping risk without a
  reproduced Android behavior gap.
- `947898c`, `4a5cfde`, `a9a0747`: no Android product action. Android's
  `SasayakiCueAudioExporter` emits platform-supported AAC/ADTS clips that both
  Anki backends already consume; adding an MP3 encoder only to match an iOS
  filename is not justified. Media3 keeps previous/next cue navigation separate
  from Reader skip-by-seconds controls, and the supported `.srt`, `.mp3`, and
  `.m4b` import set intentionally excludes generic `.txt` and `.mp4` aliases.
- `9eff7dd`, `67fc9e8`, `3cd8294`: Android now persists term-dictionary
  categories, supports Kanji dictionaries, and renders numeric/H/L pitch plus
  1-based nasal/devoice popup indicators. The iOS Anki pitch SVG generator does
  not encode nasal/devoice indicators, so Android's exported SVGs require no
  additional platform action.
- `119fb5b`, `bd85c9b`, `c943171`, `395218a`, `8464a2c`, `f1bc74b`,
  `2c86ed6`, `47683d9`, `2702e31`: Android now has three independent Anki
  formats, show-notes routing for both backends, guards for invalid formats,
  precise cloze and pitch graph handlebars, and category-aware monolingual/
  bilingual definition variants resolved from persisted dictionary order.
- `d7fe3f2`: Android Sasayaki now exposes -4...4-second delay and 0.5...3x
  playback-speed sliders with the existing 0.05 step size.
- `4940ab7`, `6655ffd`, `3bff390`: Android now removes numeric HTML entities
  before shared matchable character counting, leaves trailing ellipses and
  periods outside the selected lookup sentence, and scopes recursive lookup to
  the active `.expr-tag`.
- `b928010`: Android now serializes original-cover derivative generation in
  `BookCoverThumbnailStore` and bounds Coil bitmap decoding in the shared
  process-wide image loader.
- `fd124d4`, `bcbef64`, `2e1c958`, `51cb994`: Android now persists the
  first-appearance Reader image inventory and TOC fragment offsets, uses one
  true TOC range for Contents/chrome/statistics, and opens Gallery items in the
  existing fullscreen viewer.
- `f403c99`, `b4e6edd`, `54fab15`: Android now persists a minute-level
  statistics reset time, uses the adjusted local date in Reader and the
  Statistics dashboard, and pauses tracking across Reader sheets and fullscreen
  images without losing the active tracking state.
- `24e356f`: orphaned Xcode project fix from the previous force-updated tip; no
  Android behavior.
- `e63cb91`, `f09664d`, `ff31274`, `262df07`, `a90a83f`, `ede061f`,
  `f5c62d8`, `6cfb7b8`, `b02da68`, `d175c93`, `9fdd19b`, `25e57c5`,
  `b43c690`, and `b41ed09`: iOS release/version metadata only.
- `98f0ef4`: merge-only history integration; its reachable behavior commits are
  classified individually above.
- `e833279`, `e7b08b8`, `1992872`, `c1e4e57`: intermediate hoshidicts bumps are
  superseded by the final dictionary behavior; Android already exposes Kanji,
  pitch and transcription data. Explicit frequency options are also integrated through the Kotlin/JNI bridge.
- `77a7eaa`, `19bd095`: iOS cleanup and unwrap removal do not define additional
  Android-visible behavior.
- `188284b`: iOS local-audio launch/actor initialization fix has no direct
  Android analogue; Android local audio is repository-backed and Media3-owned.
- `0f8a3ac`, `6a1ad82`, `c842f0a`: iOS safe-area capture/fullscreen inset
  mechanics are platform-specific. Android uses persisted top/bottom safe-area
  settings, WindowInsets, and a full-screen Compose image overlay.
- `b717c57`: iOS CSS Highlight object reuse is an implementation optimization;
  no distinct Android behavior was found.
- `5c33790`, `61a8c9d`: Android `TtuBookDataConverter.rewriteImages()` already
  resolves and normalizes relative chapter image paths before writing TTU data.
- `e1d4b3b`: `reader-media-semantics.js` already resolves promises for complete
  failed images and `onerror`, so failed images do not block Reader setup.
- `f54b55f`: Android `LocalAudioResolver` already ranks exact expression and
  reading matches first, with released behavior and tests.
- `489895d`: Android popup glossary rows already carry `data-dictionary`, and
  frequency/pitch labels use dedicated spans.
- `236ca72`: AnkiMobile callback notification timing is iOS-specific; Android
  uses AnkiDroid or AnkiConnect backends.
- `7617784`: Android shared Reader/VN tests and runtime already preserve
  cross-node Sasayaki punctuation highlighting.
- `be88af1`: Android restores previous-chapter Sasayaki cues to cue-relative
  progress through `readerProgressForCue()` and the dedicated previous-cue fix.
- `e69aee7`, `50169c0`: Android provides an always-visible, opt-in pinned Reader
  playback control row in the bottom safe area; it has no collapsible state, so
  the iOS floating control-bar expansion option needs no separate Android flag.
- `ede999c`: Android implements configurable Sasayaki image holds through shared
  reader media semantics and `ReaderSasayakiAutoPage`, including fullscreen and
  continuous-mode fixes.
- `e969056`, `83eb319`: Android cue display actions distinguish reveal requests
  from passive paused position updates and explicitly reveal the target when a
  playback/seek command resumes.
- `47b0bba`: `DictionaryLookupQueryService` serializes rebuilds and atomically
  swaps complete sessions under read/write locks, preventing stale concurrent
  rebuilds from replacing the active query.
- `89feebd`, `44f47c3`, `0da83dd`, `9f94c32`: iOS-native search field,
  autocorrection, and touch-tolerance implementation changes have no direct
  Compose/WebView parity action beyond Android's existing IME and configurable
  popup swipe handling.
- `16825e4`: Android's native EPUB parser and `EpubBookParser.toReaderBook()`
  already resolve nested EPUB3 nav/EPUB2 NCX paths; tracked generated fixtures
  in `EpubBookParserTest` cover both paths and fragments.
- `eced649`: `ReaderGoToSheet.kt` uses
  `rememberInitiallyCenteredLazyListState()` for the current chapter, including
  hiding content until centering completes and no ongoing selection-following.
- `dd5e7a2`: `KanjiStrokeOrderFontInstaller`, `DictionaryViewModel` and
  Dictionary UI already implement confirmed verified download/import and the
  installed-font disabled state, with installer tests.
- `c96acb3`: `SasayakiMatcher.selectCoherentAlignment()` ranks starting
  candidates and `selectRecoveryPlan()` handles consecutive local misses;
  `SasayakiMatcherTest` covers repeated text and multi-volume recovery.
- `63d96a1`: the SwiftUI geometry feedback/crash fix has no matching Android
  path. `StatisticsDistributionList.kt` uses Compose bounded row/bar sizing
  rather than a geometry observer feeding its own measured width.
- `7994b59`: CoreText continuation double-resume is iOS-specific; Android font
  downloads use cancellable repository coroutines and verified temporary files.
- `17ceb79`: iOS modal presenter rejection is platform-specific. Android Reader
  is a typed Navigation3 route with an explicit close path, not a separately
  presented reader window. Route load failures use that same close path.
- `f6b15bc`: iOS AppIntents are platform shortcuts, not a portable API.
  Android already routes Page Up/Down and enabled volume keys through
  `ReaderHardwareKeyNavigation`; no distinct Android external shortcut contract
  is introduced by this upstream commit.
- `7c50443`, `434ed70`, `aa1994f`: dependency revision metadata only. Android's
  vendored native library already supports frequency `LookupOptions`, IPA/
  transcriptions and importer error results, now exposed through Kotlin/JNI;
  no revision-only sync work remains.
- `7dd3f49` (query-release mechanics): Android's
  `DictionaryLookupQueryService.rebuild()` serializes complete replacement
  sessions and destroys the prior session after its atomic swap; the Swift
  bundle-release sequence is not an extra Android behavior requirement.
  Automatic low-RAM updates are also covered above.
- `93ba3be` (popup defaults): Android already defaults popup width/height to
  500/500 and permits height 1000, exceeding the iOS increase to 350/310.
  Statistics sync defaults on only when unset.
- `8024df1` (SwiftLAME attribution): Android does not ship SwiftLAME; no action.
  Downloadable stroke-order font attribution is excluded at the project owner's request.
- `efd89fc`, `e1b0854`: README/issue-template changes only.
- `0425880`, `c71a2a9`, `d76127d`, `f86eb95`, `d8e150d`, `8137e1e`:
  iOS version metadata only.
- `50aaa6f`: merge integration only; reachable behavior commits are classified
  individually above.
- `27510b0`: removal of old iOS storage migrations has no Android action;
  retain Android's own compatibility migrations.
