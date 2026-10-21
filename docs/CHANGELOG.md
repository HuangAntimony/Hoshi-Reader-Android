# Changelog

All notable user-visible changes to Hoshi Reader Android are documented here.
The format follows a Keep a Changelog style, and release sections use Semantic Versioning.
Historical release notes before v1.3.0 live in [CHANGELOG_ARCHIVE.md](CHANGELOG_ARCHIVE.md).

## [Unreleased]

### Added

- Add Hoshi Google Drive sync alongside TTU, sharing books, reading position,
  highlights, shelves, reading sessions and Sasayaki playback with iOS. Cloud
  books download when opened; choose Delete Local to keep the cloud copy or
  Delete Everywhere to remove it across devices while retaining reading history.
  Book deletion actions stack vertically, and cloud icons sit inline on the first
  title line.
  Failed Google Drive sign-ins show their original error details in a dialog and
  Diagnostics; canceling sign-in stays quiet.
  Connecting through the Google account picker resets pending sync operations so
  books deleted before sign-in do not delete existing cloud copies.
  Devices without Google Play Services can connect through their browser.
  Android-generated Sasayaki matches include the image-list field required by iOS.
  Debug builds sync edits after 2 seconds and poll for cloud changes every 5 seconds.
  Bookshelf and Statistics refreshes are batched during sync and skipped while reading.
  Full syncs of large libraries are faster, a failed book no longer stops the
  rest of the sync, and Sync settings show a transfer queue with progress and
  per-book errors.
  Books sync eight at a time using far fewer Google Drive requests, and
  rate-limited or failed requests are retried.
  Background sync waits for a network connection and retries temporary failures,
  including sustained Drive rate limits; background launches do not start foreground polling.
  Renaming a shelf preserves its order and book memberships.
- Keep the latest 100 runtime errors in Diagnostics, including the operation,
  time, original error details and cause stack; retain them across restarts,
  and copy or export them with crash history.
  Show app/WebView information on the Diagnostics overview with separate entries
  for crash, error and process-exit records, opening full details from compact lists.
  Input validation, including unfinished regex replacement drafts, stays inline
  without entering runtime error history.
- Add Disable Button After Mining under Anki's Allow Duplicates: disable each term/format
  after successful mining until the popup is reopened.
- Read Japanese books aloud using installed Android TTS engines or Edge, Azure,
  Microsoft Translator, Google Cloud and Google Translate online sources, plus multiple named
  self-hosted OpenAI Compatible and VITS Simple API servers, with a separate
  TTS panel styled like Sasayaki, with compact playback controls and Voice,
  Replacements and Settings tabs. Source and Voice share a compact card with grouped,
  scrolling bottom selection panels. Voices retain their original names and show their
  online/offline requirement; installed third-party engines remain available. Source selection
  includes cloud credential configuration, with system TTS settings always available as
  a separate action above the Device engines group. Advanced settings label
  dictionary pronunciation settings as Word audio to distinguish them from narration,
  and give TTS its own speaking-person icon.
  Omit subtitles for Sasayaki and Book Cover Lock Screen. Add independent Hoshi
  playback speed from 0.5× to 3.0× alongside engine speech rate; it applies to narration
  and preview without re-synthesizing audio, shifting pitch, or resuming paused playback. Rate and pitch sliders
  keep their steps without dense tick marks or endpoint labels, and preview can be canceled or stopped from
  its theme-colored button fixed above the bottom system inset. Synthesis parameters appear
  directly in Voice without a separate heading or toggle; audio format is selected there
  rather than duplicated in Source configuration.
  Provider pause switches, audio format and sample rate use plain settings rows, while
  remaining parameter cards have tighter vertical padding; slider row heights stay unchanged.
  Configurable sources default to MP3, retaining saved WAV/MP3 selections.
  Each Source can optionally use separate narration and dialogue voices, remembered
  independently and selected from the existing voice picker. Paired Japanese quotes
  route dialogue across sentences and paragraphs; reading, replacement preview and
  Anki sentence audio share this behavior while keeping sentence navigation unchanged.
  Cloud credentials use a matching configuration sheet; connection testing and its
  success/error feedback stay visible in a fixed footer for cloud and self-hosted sources,
  without requiring another scroll after testing. Saved cloud API keys can be deleted
  immediately after confirmation, preserving other source settings. Saving or deleting
  another source's cloud credentials keeps the active voice list and preview unchanged.
  Azure regions use a compact dropdown of region codes with no default selection. Source errors
  stay beside the voice card instead of leaking into other tabs. Online voice lists remain
  available across panel openings and app restarts without automatic network refresh;
  the Voice picker provides a manual refresh action at the top for every source,
  retaining known voices during loading or refresh failure; slow self-hosted refreshes
  do not block reuse of the previous list.
  E-ink shows readable loading text instead of a solid black progress line.
  Add, edit and delete self-hosted servers from Source; saving a new server selects it
  immediately without starting playback, and each retains its model, voice,
  engine rate and WAV/MP3 format, with optional encrypted credentials and draft connection
  testing. Choose voices separately under Voice: VITS shows server-provided character names,
  while OpenAI Compatible can list manually configured voice IDs alongside voices discovered
  from the server. Changing available IDs keeps the chosen voice and playback intact.
  Editing another server or renaming the current one leaves playback running.
  OpenAI model IDs use one editable field with an integrated list button; returned
  models open a nearby menu for selection, including lists with a single result.
  TTS Settings offers a global Use Ruby readings switch, enabled by default; disabling it
  sends original base text to the engine while preserving custom replacements for reading,
  preview and Anki sentence audio.
  Read XHTML books containing self-closing script/style tags without losing their
  body text during TTS preparation.
  Book text is prepared by chapter when TTS is used, avoiding full-book TTS parsing
  when opening Reader. Edit shared reading behavior directly in either panel,
  preserving consecutive changes to different controls,
  with independent Japanese voice/rate controls, sentence navigation, background playback,
  and shared Reader highlighting, page following, lookup pause, and image holds.
  Control narration from lookup popups: replay the selected sentence once, toggle
  playback, or close the popup and continue from that sentence.
  Microsoft Translator needs no user API key, discovers regional Japanese voices,
  and refreshes its temporary authorization automatically.
  Linking an audiobook hides TTS and preserves Sasayaki's audiobook controls.
  Reopening or switching books after narration keeps playback idle until explicitly started.
  The shared Volume Keys Control Audiobook / TTS setting enables audiobook seeking and
  TTS sentence navigation after starting narration in the current book session; popup term
  navigation takes priority over narration, then page turns.
  Previous/next sentence navigation preserves paused playback instead of starting audio.
  Lookup pauses survive switching directly to another word without a brief playback
  restart, and closing the popup resumes correctly when the sentence ended during the pause.
  The Reader’s top-right playback button also controls TTS after manually starting narration
  in the current book session, like the bottom controls. It remains available while paused
  and shares the Show Audiobook / TTS Toggle preference with Sasayaki.
  Recognizes Samsung's Japanese voices and refreshes available voices when returning
  from system settings. Configure cloud credentials and test synthesis in the current
  panel. Adjust supported pitch, volume, Azure/Microsoft Translator speaking styles/roles/intensity and
  punctuation silence, cloud audio format/sample rate with compact dropdown rows
  anchored at the right-side control, Google device profiles, and
  extra playback pauses between sentences. Pause sliders use 10 ms increments, with
  a normal range of 0–1000 ms for provider silence and 0–2000 ms for extra sentence
  pauses. Changing extra
  sentence pause takes effect at the next boundary without stopping narration or
  regenerating cached audio. Independent setting edits preserve other saved preferences.
  MP3 sources retain MP3 for playback and Anki.
  Ready sentence audio plays without waiting for later network synthesis, including
  installed Android engines using separate narration/dialogue voices. Installed-engine
  voice lists are reused during playback and refreshed manually or when returning to the
  panel. Continuous playback keeps ongoing prefetch when moving to the next sentence. Configure 1–20
  upcoming sentences to preload in Voice (default 5); changes adjust
  buffering without interrupting narration or regenerating cached audio.
  Replayed sentence audio remains reusable while upcoming sentences are preloaded,
  including sentences with multiple audio fragments.
  While narration is paused, chapter navigation and returning to Reader keep the selected
  reading position instead of jumping back to the narrated sentence. TTS shares
  Sasayaki's image holds and chapter presentation, keeping images visible for the
  configured duration before continuing; manual sentence jumps skip intervening images.
- Customize TTS text replacements for one book or all books from text selection or
  Settings. Selected text has line breaks removed before filling Matching text, keeping
  the single-line field consistent with the actual rule. Replacement text accepts readings,
  other text and punctuation; leaving it empty
  deletes the matching text from speech. Fully deleted sentences are skipped during continuous
  playback without sending empty synthesis requests. Supported substitutions include
  dashes or ellipses replaced with commas/periods for engine-controlled pauses. Add an opt-in
  regular-expression switch to replacement creation/editing, with capture-group substitution,
  book/global scope and validation. Explicit literal entries and available ruby take priority
  over regex rules. Both modes show the original and actual TTS input before preview; rules
  created from Settings can be tested with editable sample text. The editor opens with
  its initial preview/validation content ready, avoiding an opening
  size jump, and retains the preview area while edits are recalculated. The compact editor
  groups the rule, scope and sentence preview, using matching body text sizes with distinct headings, labels and colors,
  a secondary Preview button and a primary Save action. Scope choices share the dialog
  background when unselected; preview recalculation keeps actions stable and queues Preview
  for the current draft instead of briefly disabling it. Empty results are clearly identified, remain saveable and disable Preview.
  Replacements affect only synthesized speech; original text, sentence navigation,
  highlighting and Anki sentence text stay unchanged.
  The selection menu offers a compact Replace action; its TTS replacement dialog
  defaults new entries to all books, with a theme-colored selected scope and stable centered labels,
  and a full-width theme-colored preview button, with
  a destructive Delete action aligned beside Cancel and Save when editing an entry.
  Replace also opens when a ruby-adjacent selection begins inside an annotation,
  using only selected body text and preserving native selection handles.
  Text-selection creation previews the full original sentence; both fields request
  Japanese keyboard input; the replacement field offers Done to dismiss the keyboard
  before saving.
  EPUB ruby remains the fallback reading.
  Image-backed characters with ruby are read without freezing or exhausting memory.
- Add the opt-in Anki `{sentence-audio}` handlebar for audiobook or TTS sentence audio;
  `{sasayaki-audio}` and existing field mappings keep their audiobook-only behavior.

- Add LINE Seed JP to the rounded recommended reader fonts, with Thin, Regular, Bold,
  and ExtraBold variants.

### Changed

- Replace BIZ UDPGothic with BIZ UDGothic in the recommended reader fonts.
- Edit individual reading sessions in Statistics. Session deletions persist across
  sync, and daily totals follow the selected reset time. If another device deletes
  the session being edited, its editor closes without restoring the deleted session.

### Fixed

- Keep full-width lookup popups independent of continuous reader padding in both
  vertical and horizontal writing.
- Restore continuous reading to the text line or column edge when reopening a
  book, keeping text and furigana visible without half-line clipping or position drift.
- Preserve specific error details and underlying causes across imports, settings,
  Reader, dictionaries, Anki, audio, TTS, Sasayaki, sync, backups, updates and
  statistics, alongside the localized operation or category description.
  Failed update checks retain each attempted URL, original response and
  rate-limit details instead of showing only the last source's error.
- Stop using a file-download proxy that redirects release API requests to its
  homepage; reject HTML responses before parsing update information as JSON.
- Keep Japanese names containing middle dots intact when scanning text for lookup.
- Clear the Dictionary tab's search when opening a book, preventing old word audio
  from replaying when the book's profile is activated or restored.
- Show saved settings before their controls appear, preventing switches and
  selections from briefly showing defaults when opening settings pages or panels.
- Keep dictionaries marked `Exclude` in Anki Advanced available for lookup;
  omit them from aggregate and first-definition fields while preserving explicit
  single-dictionary and selected-definition mappings.
- Apply the selected-glossary fallback only when the selected dictionary has no
  glossary, preserving empty glossary values.
- Keep the audio-source menu beside its speaker button and fully inside lookup
  popups when popup scale is above 1, including narrow popups.
- Restore the text-selection menu after handle drags in paginated paragraphs,
  with or without ruby, and keep it near visible text when WebView reports empty
  or offscreen menu coordinates. Keep Highlight and TTS Replace available after
  menu recovery on large-screen devices, and prevent repeated recovery from
  hiding the menu or a selection handle in reverse selections across paragraphs.
  Recover inside the selection away from its handles, including fragmented text
  starting immediately after ruby, and avoid pressing a handle again when
  recovering single-character vertical selections. Keep menus available on the
  first long press and after successive handle drags, without unexpectedly
  clearing the selected text. Keep recovered menus near the selection handles
  when the range includes a paragraph's final punctuation across columns.
- Create, recolor and remove highlights when native selections begin or end
  inside ruby annotations or at element boundaries, using only selected body text.
- Keep text selection from unexpectedly turning pages or chapters when the
  native selection menu disappears or is recreated.
- Reduce text-selection flicker in paginated reading by preventing selection
  autoscroll while dragging selection handles.
- Use Nakuru for Japanese audiobook transcription, with model and runtime downloads
  on first use for arm64 and 32-bit ARM devices. Update matched reading cues during
  transcription so reading and playback can continue without pausing transcription.
  Automatically retry interrupted component downloads and resume saved partial files,
  including after reopening the app. Download failures show the affected file, URL,
  received byte count and original cause in error details and Diagnostics.
  Automatically remove retired ReazonSpeech models and inference libraries to free
  storage while keeping existing transcripts, matches and audiobook files.
  Remove the separate component license buttons from About.

## [v1.4.0] - 2026-09-24

### Added

- Transcribe Japanese audiobooks on-device in Sasayaki. Download the model only when
  needed; pause/resume, continue with the panel closed or after switching apps, and
  save progress when leaving Reader. Reuse or clear transcripts, open the matching
  transcription tab, and choose Lightweight, Balanced (default), or Fast. Match speech
  to book text while reading without interrupting playback, with better recovery of
  short replies and recognition errors. Support M4A, show match coverage, import
  multiline subtitles, export partial or complete matches as SRT, and show localized
  error messages.
- Set lookup frequency sorting per profile: Auto, Ascending, Descending, or Disabled.
  Explicit ordering requires an enabled frequency dictionary.
- Preserve furigana in Reader highlights and Contents. Select an exact highlighted range
  to change its color or remove it in paginated, continuous, and VN modes.
- Optionally hide thumbnails in collapsed shelves; this setting is off by default.
- Add global Theme settings with system or manual light/dark palettes, separate custom
  light and dark reading colors, interface accents, and app-wide E-ink optimization.
  Native text menus, highlight palettes and all lookup popups follow the chosen App
  theme even when the system differs; switching preserves lookup contents and history.
  Lookup pages use a transparent background to show their containing App surface.
  Lookup action buttons use solid black/white when available and distinct gray
  when disabled, in both ordinary and E-ink modes.
  Dictionary colors and custom CSS now share iOS semantic variables and light/dark
  media-query behavior, including tables and monochrome images.
- Archive deleted-book reading statistics, restore them on reimport, and edit daily
  records. Deleting all records requires confirmation.
- Add calendar and all-time Statistics views with period comparisons, averages, goal
  history, and best-day summaries.
- Support Anki tag handlebars such as `{document-title}` and `{expression}`; whitespace
  in substituted values becomes underscores. New formats use the `hoshi` tag by default.
- Long-press a lookup result's audio button to choose among named local or remote
  recordings; use the selected recording for Anki cards too.
- Show source text above Dictionary and shared lookup results. Tap a character to look it
  up while retaining the full sentence for Anki. Back/Forward restores the selected
  occurrence, and source text size is adjustable from 12–48.
- Add Off, Dimmed, Toggle, and Hidden furigana modes. Toggle reveals adjacent ruby
  annotations together on the first tap.
- Add Show, Blur, and Hide bookshelf-cover privacy modes, with title and author artwork
  when a cover is hidden or unavailable.
- Warn that AnkiDroid can create cards directly in most setups and that a misconfigured
  AnkiConnect can prevent loading decks and note types or creating cards.
- Add term dictionary categories and category-aware Anki mappings, Kanji dictionary
  import and popup lookup, stroke-order font download, and complete pitch accents with
  H/L patterns and nasal/devoice markers.
- Add downloadable Japanese fonts with family, variant, and weight choices.
- Use volume keys to move between terms in the topmost lookup popup.
- Add `hoshi://search?text=...` links to open lookup results in a popup; use `mode=app`
  to open the Dictionary tab.
- Fix transcription setup for some playable M4B and Ogg Opus audiobooks.

### Changed

- Translate the Anki selected-glossary fallback label into Simplified Chinese.
- Use Sasayaki accent colors to highlight the current chapter in Reader Contents outside
  E-ink mode.
- Clearing Dictionary search by pull-down or clear button now focuses the field and
  opens the keyboard while preserving results and navigation history.
- Set JapanesePod101, LanguagePod101, and Jisho as the default word-audio sources.
  Sources can be enabled and reordered independently; custom sources remain unchanged.
  Play the first match immediately and show recordings as each source loads.
- Always export dictionary images to Anki as media files; remove the “Embed media” switch.
- Adjust Reader line spacing to match iOS more closely.
- Use low-memory imports for automatic dictionary updates; manual imports follow the
  Low Memory Usage Mode setting.
- Make book search treat spaces and punctuation literally, show full sentence context,
  and return up to 100 results. Mark a selected result in blue until page navigation.
- Include surrounding Japanese quotation marks and punctuation in Sasayaki highlights
  across all Reader modes.
- Apply Recent/Title sorting to Google Drive books. Recent uses reading or audiobook
  progress time, then bookdata last access.
- Keep display settings global across profiles and show the edited profile name in
  Appearance. Opening books or switching profiles no longer changes colors or E-ink mode.
- Use theme colors consistently across navigation, cards, settings, and Reader panels;
  make selections clearer in E-ink mode.
- Keep Statistics available and move its settings to the Stats tab. Preserve saved goals
  and sync preferences; keep Book Open and Page Turn autostart independent and off by
  default. Group settings into Autostart, Reset Time, Sync, and Archive.
- Simplify Statistics around daily goals and reading time, with a heatmap, period charts,
  comparisons, averages, and time-ranked books. Weekly totals and trends remain in the chart.

### Fixed

- Keep Sasayaki paused when replacing a lookup popup by tapping another word, avoiding
  brief audio playback or automatic page turns between lookups.
- Preserve Dictionary results and Back/Forward history when switching tabs, including
  after following a definition link and swiping back.
- Recognize existing AnkiDroid cards with equivalent Unicode spellings to prevent
  duplicate cards.
- Restore duplicate-note search in AnkiDroid 2.25 through its public browser link.
- Report failed dictionary imports by filename and reason, then continue the batch.
- Show a localized Reader message and Close action for missing or unreadable books.
- Recover Reader after a crash while preserving reading position, highlights, and
  Sasayaki cues.
- Avoid transient errors during automatic Google Drive refresh and keep cached books
  visible. Manual refresh still reports failures.
- Count Korean consistently in reading progress, search, and Sasayaki matches; ignore
  furigana fallback text in native counts and search.
- Confirm before resetting Dictionary custom CSS.
- Keep text and images visible in books with unusual publisher formatting.
- Restore book files and covers from iOS backups despite filename variations.
- Match Sasayaki subtitles immediately after selecting an SRT, including around chapter
  boundaries and large text gaps, without search tuning or a separate action.
- Import EPUB and TTU books with long, non-Latin titles without truncation.
- Render inline Japanese character images at the right size and color, and exclude them
  from image navigation.
- Show EPUB fallback text when an inline character image fails to load.
- Open EPUBs that previously remained on the Reader loading screen.
- Keep large lookup popups on-screen so their borders and scrollable content remain
  reachable.
- Keep the Dictionary `Frequency` label on one line on compact screens.
- Keep pitch accents aligned and dictionary labels readable when lookup entries wrap.

## [v1.3.3] - 2026-08-13

### Added

- Automatically center the current chapter when opening the Reader Contents or
  Sasayaki chapter list.
- Add per-source enable controls for imported local audio databases while
  preserving each source's configured priority.
- Add Ogg Opus audiobook import with embedded title, artist, cover, and chapter
  metadata in Sasayaki, load its artist without the platform-reader delay, and
  show MP3, M4B, and Opus audiobook duration before playback starts.
- Add a Reader Appearance swipe-threshold control for paginated and VN modes;
  setting it to zero disables swipe page turns while preserving hardware page
  keys.
- Add up to three named Anki card formats with independent icons, decks, note
  types, field mappings, tags, and duplicate states.
- Add confirmation before deleting Anki formats and an edit-screen action that
  duplicates a format and returns to the format list.
- Add Anki duplicate-note search buttons that appear only for matching notes,
  plus grouped per-format mining and search actions placed before audio.
- Add precise cloze-part handlebars, numeric pitch accent graph handlebars, and
  advanced glossary mapping options for Anki cards.

### Changed

- Increase the default lookup popup size to 500 × 500 dp and allow its
  height to be adjusted up to 1000 dp.
- Align the built-in Lapis, Kiku, and Senren field presets with iOS by no longer
  setting sentence-card marker fields.

### Fixed

- Allow importing supported files whose display names contain `#` or `?`,
  including EPUB volume numbers such as `Book #01.epub`.
- Base reduced-motion popup scrolling on the portion of the popup that is
  actually visible when its configured height exceeds the screen.
- Show Reader Contents, fragment jumps, and chapter labels for EPUBs whose
  navigation document is stored in a subdirectory.
- Prevent staggered multi-touch taps in paginated and VN Reader modes from
  being mistaken for a page-turn swipe.
- Keep Google Drive sync and TTU backup restore compatible with progress files
  whose book data IDs exceed Android's 32-bit integer range.
- Keep dictionary lookup available when tapping Sasayaki-highlighted text in VN
  Reader mode.

## [v1.3.2] - 2026-08-04

### Added

- Add the option to create a new shelf while moving one or more selected books.
- Add a Reader image gallery, true table-of-contents chapter ranges, and optional
  current-chapter progress in Reader chrome and statistics.
- Add a configurable daily statistics reset time and pause reading statistics
  while Reader sheets or fullscreen images are open.
- Add optional current-book cover publishing for the Android lock screen and a
  fixed PNG file used by compatible E-ink sleep-screen tools, plus direct
  integration with iReader’s built-in Book Cover screen saver on compatible
  domestic and Musnap overseas firmware using standard PNG output, with Fit,
  Fill, and Stretch scaling modes.

### Changed

- Expand Sasayaki delay adjustment to -4...4 seconds and playback speed to
  0.5...3x.
- Rename the Reader Go to panel to Contents, order its tabs as Chapters,
  Highlights, Gallery, and Search, and remove overscroll deformation from
  scrolling surfaces throughout the app.

### Fixed

- Keep Reader progress, search, and Sasayaki character offsets stable around
  numeric HTML entities, and keep lookup sentence expansion and recursive
  expression-tag scanning within the selected text boundary.
- Keep manual bookshelf sync from rebuilding the entire shelf, while refreshing
  imported reading progress in place.
- Keep large bookshelves smooth during repeated scrolling by reusing
  size-appropriate persistent cover thumbnails instead of decoding original
  covers again after they leave memory, while recovering from transient
  generation failures or damaged thumbnail-cache entries without hiding valid
  covers.
- Remember the selected Contents and Sasayaki tabs for the current Reader
  session, and keep Sasayaki on the current tab after importing an audiobook.
- Keep VN lookups and mined Anki sentences complete when a word or sentence
  continues onto a later screen.
- Keep Anki audio, book covers, Sasayaki clips, and dictionary media from
  overwriting different exported media by using content-specific filenames.
- Keep dictionary definitions in the configured dictionary order when an
  inflected lookup merges multiple deinflection candidates.
- Keep Sasayaki jumps to cues in the previous chapter from counting the target
  chapter in the current reading session when image holding is enabled.

## [v1.3.1] - 2026-07-11

### Added

- Add a Reader Appearance setting for top safe area height.

### Changed

- Improve dictionary lookup and import behavior by honoring Yomitan term scores
  and normalizing Japanese iteration marks, full-width numbers, and emphatic
  sequences.
- Raise Statistics daily goal limits to 200,000 characters and 12 hours.

### Fixed

- Keep the Statistics tab visible after enabling it and switching away from Settings.
- Refresh Statistics by-book covers when changing calendar ranges.
- Keep Reader lookup highlights from expanding to an entire ruby annotation when
  selecting a shorter word inside it.
- Keep VN vertical text from jumping to a new column immediately after a ruby
  annotation.
- Keep long-pressed Reader volume keys paging or seeking Sasayaki instead of
  falling back to system volume changes after the first press.

## [v1.3.0] - 2026-07-01

### Added

- Add a full-library Statistics tab with habit summaries, calendar range browsing, per-book distribution, daily and weekly goals, and an Advanced Statistics visibility switch.

### Changed

- Open the Reader Go to panel on Chapters by default, order its tabs as Chapters, Highlights, and Search, and focus the search field when Search is selected.

### Fixed

- Prefer exact expression-and-reading local audio matches before falling back to reading-only or expression-only entries.
- Read Sasayaki M4B title, author, and cover metadata from MP4 atoms when Android's platform metadata reader returns empty.
- Improve VN reader media screens, first-highlight display, vertical layout, punctuation wrapping, and lookup and Sasayaki highlight alignment.
- Keep VN and continuous vertical reader content aligned to the configured vertical padding instead of the bottom overlap area.
- Prevent reader lookups from crashing on words that begin with supplementary-plane kanji such as 𠮟.
- Keep Sasayaki image hold active while viewing fullscreen Reader images, and avoid repeated holds once the continuous Reader target image is already visible.
