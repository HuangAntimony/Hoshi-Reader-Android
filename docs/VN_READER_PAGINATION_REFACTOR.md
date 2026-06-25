# VN Reader Pagination Refactor Design

## Context

Android currently has three WebView-backed reader modes: paginated, continuous,
and VN. Paginated and continuous share several reader semantics through common
assets such as `reader-sasayaki.js`, while VN is implemented as a separate
renderer in `reader-visual-novel.js`.

VN is functionally a special pagination mode: it turns the same chapter DOM into
screens, but its page boundaries are block or sentence based, and it can reveal
text progressively. Today it owns its own chapter source clone, text/range
indexes, screen construction, Sasayaki cue ranges, highlight mapping, progress
mapping, media-stop mapping, and image setup. That separation has caused parity
bugs where fixes in paginated/continuous do not automatically apply to VN, such
as Sasayaki punctuation split across text nodes. The same heterogeneity also
raises risk for VN-specific furigana loss and consecutive image rendering
issues.

The desired direction is to keep VN's unique pagination and reveal behavior,
but move shared reader semantics into mode-independent primitives. The first
stateful content stream and range-map runtime consumer should be VN only, so
paginated and continuous remain stable while VN is brought back toward the same
content model. Low-risk pure helpers may be reused by all reader modes when
they do not change page, scroll, restore, or range-mapping data flow.

## Goals

- Treat VN as a paginator over the same semantic chapter stream used by other
  reader modes, not as a separate chapter renderer.
- Preserve WebView-based reading, single-tap lookup, existing Reader bridge
  commands, and iOS-aligned visible behavior.
- Lower regression risk for paginated and continuous by leaving their
  page/scroll production runtime paths unchanged during the initial VN refactor.
- Extract shared pure JavaScript primitives for chapter text/media semantics,
  raw and matchable offsets, source-to-rendered range mapping, ruby-aware text
  handling, and Sasayaki/highlight range construction.
- Migrate VN in narrow behavior-protected slices, starting with the areas where
  VN has visible divergence: furigana preservation, consecutive media screens,
  Sasayaki cue ranges, highlights, and restore/progress anchors.

## Non-Goals

- Do not replace WebView reader rendering with native Compose or a non-WebView
  engine.
- Do not change paginated or continuous production behavior in the first VN
  refactor phases.
- Do not redesign VN settings, reveal controls, Reader chrome, Sasayaki
  playback, or lookup popup UI.
- Do not add a permanent runtime feature flag that keeps two VN implementations
  alive. Temporary dead code during a single slice is acceptable only if removed
  before the slice is committed.
- Do not record user-visible bug fixes in the changelog until an implementation
  slice actually changes app behavior.

## Options Considered

### Recommended: Shared Semantics, VN-Only Runtime Adoption First

Create shared pure reader-web modules for chapter semantics and range mapping.
Use paginated and continuous legacy behavior as a test oracle, but do not wire
those two modes to stateful content stream instances or the range map at
runtime. Migrate VN onto the shared modules slice by slice. After VN is covered,
paginated and continuous can reuse low-risk pure helpers from the shared modules
when tests prove the semantics are identical.

This gives VN the same logic foundation while keeping the two already-working
modes out of the initial blast radius. The cost is that some duplication remains
until the shared modules prove themselves in VN.

### Big-Bang Unification Across All Reader Modes

Move paginated, continuous, and VN to the new shared content model in one
refactor.

This reaches the cleanest final architecture faster, but it puts all reader
modes, restore behavior, page turns, lookup, highlights, images, and Sasayaki
cue rendering at risk in one change. It is not appropriate for the current
state.

### Patch VN In Place

Continue fixing VN bugs directly inside `reader-visual-novel.js`.

This is fastest for a single bug, but it keeps the separate text/range/media
logic that caused the current class of issues. It should only be used for urgent
small user-visible fixes, not as the long-term architecture.

## Target Architecture

The refactor should split reader-web semantics from mode-specific pagination:

```text
chapter DOM
  -> reader content stream
  -> shared range/map/media semantics
  -> mode paginator
  -> mode renderer and bridge commands
```

### Shared Reader Content Stream

Add a pure reader-web module, tentatively
`app/src/main/assets/hoshi-web/reader/reader-content-stream.js`, responsible for
walking a chapter DOM and producing a stable semantic stream.

The stream owns:

- source text entries with source node, source order, raw start/end offsets, and
  matchable start/end offsets.
- per-character items for sentence splitting and raw range mapping.
- structural IDs from `id` and `name` attributes for fragment restore.
- ignored-node filtering for `rt`, `rp`, `script`, and `style`.
- ruby-preserving source structure, so consumers can clone base text without
  dropping furigana.
- media units for standalone visual content such as `img`, `svg`, `image`,
  `picture`, `figure`, `video`, `canvas`, `table`, `iframe`, `object`, and
  `embed`.

The stream must not know whether the reader is paginated, continuous, or VN.
It should be testable under `node --test` without a WebView bridge.

### Shared Range Mapping

Add a pure range module, tentatively
`app/src/main/assets/hoshi-web/reader/reader-range-map.js`, responsible for
mapping chapter ranges to DOM ranges in a rendered root.

The range map owns:

- matchable character ranges for Sasayaki cues.
- raw character ranges for persisted highlights.
- punctuation inclusion inside an active matchable range, including punctuation
  split across text node boundaries.
- source-to-clone offset registration for rendered VN screens.
- ruby-aware geometry inputs for e-ink overlays.

Boundary policy should be explicit: punctuation inside a cue is included when
the current matchable cursor is strictly between cue start and cue end; leading
or trailing punctuation exactly outside the cue boundary remains excluded.

### VN Paginator

Keep VN paginator responsibilities as a bounded part of
`app/src/main/assets/hoshi-web/reader/reader-visual-novel.js` unless a later
split is justified by file size or ownership pressure. The paginator routines
consume the content stream and emit VN screen descriptors.

VN-specific responsibilities stay here:

- block-mode screen boundaries.
- sentence-mode boundaries, including `Intl.Segmenter` fallback behavior,
  Japanese sentence delimiters, and dialogue preservation.
- `sentencesPerScreen` grouping.
- Sasayaki cross-screen cue merge decisions.
- viewport fitting for oversized text screens.
- media-stop screens for standalone media.

Viewport fitting should split text screens only. It must not split ruby elements
or standalone media units. Consecutive media units should preserve source order,
stable IDs, and independent media-stop behavior without sharing mutable clone
state across screens.

### VN Renderer And Bridge

Keep `reader-visual-novel.js` as the VN runtime entry point during the refactor,
but reduce it to orchestration:

- initialize and detach the source chapter.
- create the VN stage and current screen.
- call the shared content stream and VN paginator routines.
- render one screen descriptor at a time.
- own reveal timers and reveal completion.
- forward bridge commands for pagination, restore, fragments, Sasayaki, and
  native selection.

As slices complete, `reader-visual-novel.js` should stop owning duplicate
implementations of text stream indexing, range construction, source clone
offsets, and media classification.

## Data Flow

1. VN initializes after fonts and source images are ready, matching the current
   readiness model.
2. The source DOM is detached into a source root.
3. The shared content stream indexes text, raw offsets, matchable offsets, IDs,
   ruby structure, and media units from the source root.
4. The VN paginator turns semantic units into screen descriptors.
5. The VN renderer renders only the current screen descriptor into the visible
   stage and registers clone offsets with the shared range map.
6. Lookup, highlights, Sasayaki inline highlights, and e-ink geometry ask the
   range map for ranges in the rendered screen.
7. Progress and fragment restore use the screen descriptors' semantic offsets
   and IDs, not ad hoc DOM scans.

Paginated and continuous keep their existing page/scroll runtime data flow
during the first phases. Their current behavior should be covered by
characterization tests and used to validate shared semantic decisions before any
future stateful runtime adoption.

## Migration Plan

### Phase 0: Characterize Existing Behavior

Add or extend JavaScript tests for the current reader assets before moving code.
The tests should cover:

- matchable versus raw offsets across normal text, punctuation, ruby, and gaiji.
- Sasayaki cue ranges with punctuation split across text nodes.
- persisted highlight raw ranges in VN rendered screens.
- fragment IDs inherited through nested block containers.
- block and sentence VN pagination with ruby content.
- consecutive standalone images and SVG image containers.
- media-stop discovery before/after Sasayaki cue navigation.
- progress restore into large text nodes and near chapter end.

These tests should construct their own DOM fixtures and must not depend on
ignored local `testdata/` files.

### Phase 1: Extract Shared Content Stream Without Runtime Adoption

Introduce the content stream module and test it directly. Keep all production
reader modes wired exactly as they are.

Test expectations should be derived from current paginated/continuous semantics
where those modes already behave correctly, especially ignored ruby annotation
text, matchable character counting, and source order.

### Phase 2: Move VN Text And Ruby Cloning To The Stream

Wire VN screen rendering to clone text and elements from the shared stream.
The first visible target is furigana preservation in both block and sentence VN
screens, including during reveal and after `completeCurrentReveal()`.

Paginated and continuous remain untouched at runtime.

### Phase 3: Move VN Media Unit Construction To The Stream

Use shared media units to build VN standalone media screens. Validate that cover
images, multiple consecutive illustrations, SVG image containers, and inline
gaiji images keep the same intended behavior:

- large standalone media creates media-stop screens.
- inline gaiji images stay inline.
- consecutive standalone media does not collapse, skip, duplicate, or inherit
  stale clone offsets.

### Phase 4: Move VN Sasayaki And Highlight Ranges To The Shared Range Map

Replace VN's private Sasayaki and highlight segment collectors with the shared
range map for the currently rendered screen.

This phase should preserve:

- inline Sasayaki highlighting in normal display modes.
- e-ink Sasayaki overlay geometry.
- cross-screen cue merge behavior.
- cue navigation that lands on the containing VN screen.
- highlight creation/removal using raw persisted offsets.

### Phase 5: Consolidate VN Progress And Restore Anchors

Move progress and fragment restore to the same screen descriptors produced by
the paginator. Remove duplicate source-node stats and offset maps from
`reader-visual-novel.js` once the shared stream owns them.

### Phase 6: Consider Optional Adoption By Paginated And Continuous

After VN is stable and covered, adopt only low-risk shared helpers in paginated
and continuous. The approved scope is pure text semantics: normalization,
matchable character counting, raw character counting, and matchable-character
checks can delegate to `reader-content-stream.js`.

Do not migrate paginated or continuous page metrics, scroll restore, DOM
rendering, Sasayaki range collection, or highlight range mapping to content
stream instances or the range map in this phase. Those mechanics are already
working and higher risk, so any future stateful adoption should get its own
design and implementation plan.

## Testing Strategy

Each phase should be test-driven with focused JavaScript tests first:

- `app/src/test/js/reader-visual-novel.test.mjs` for VN rendered behavior,
  reveal behavior, media screens, restore, highlights, and Sasayaki.
- `app/src/test/js/reader-paginated.test.mjs` and continuous fixtures for
  shared semantic parity when paginated/continuous are used as oracles.
- New focused tests for shared modules if they are introduced.

Before a code slice is considered complete, run:

```bash
node --test app/src/test/js/*.test.mjs
./gradlew test
./gradlew assembleDebug
```

Manual validation is required for user-visible VN behavior after runtime
migration slices. It should cover the Reader/VN items listed in
`docs/VALIDATION.md`: block and sentence screens, reveal speed 0/45/120,
blank-area click advance, lookup taps, links, images, restore, chapter
boundaries, vertical and horizontal writing, and Sasayaki cue behavior.

## Error Handling And Safety

- Do not silently fall back to old VN semantic logic in production after a
  migration slice. Silent fallback would hide mismatches and keep two runtime
  behaviors alive.
- If the shared stream cannot index a node shape, tests should force an explicit
  decision: ignore it, preserve it as media/structure, or add a semantic unit.
- Keep runtime changes narrow enough that a failed phase can be reverted without
  affecting paginated and continuous page/scroll mechanics.
- Preserve safe WebView resource loading. This refactor only changes reader
  asset logic and must not broaden file URL access.

## Exit Criteria

The VN refactor target is complete when:

- VN builds screens from shared content stream and range-map primitives.
- VN no longer has private implementations for chapter text offsets, raw
  offsets, matchable offsets, source clone offset registration, Sasayaki cue
  range collection, highlight raw range collection, or media classification.
- Furigana is preserved in VN block and sentence screens, including reveal.
- Consecutive standalone images render in order and remain navigable media
  stops.
- Sasayaki cue highlighting, e-ink overlays, lookup, persisted highlights,
  fragment restore, and progress restore remain covered by automated tests.
- Paginated and continuous production page/scroll paths are unchanged. They may
  reuse the shared content stream's pure text semantic helpers when covered by
  parity tests.
