# VN Reader Shared Core Refactor Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use
> superpowers:subagent-driven-development (recommended) or
> superpowers:executing-plans to implement this plan task-by-task. Steps use
> checkbox (`- [ ]`) syntax for tracking.

**Goal:** Refactor VN as a special pagination mode over shared reader-web
semantics, so VN stops carrying parallel text, range, media, ruby, highlight,
and Sasayaki logic that the paginated and continuous modes do not use.

**Architecture:** Shared reader-web modules are only "shared" when at least two
reader modes consume their production API in the same phase, with a path to all
three modes when the behavior is mode-neutral. Mode-specific page metrics,
scrolling, screen boundaries, reveal timing, and bridge navigation remain in
the mode assets. The refactor must remove or replace old mode-local
implementations as shared code is introduced; moving VN-only logic into a new
file is not sufficient.

**Tech Stack:** WebView reader assets in
`app/src/main/assets/hoshi-web/reader`, Kotlin asset injection in
`app/src/main/java/moe/antimony/hoshi/features/reader`, JavaScript unit tests
under `app/src/test/js`, and JVM tests under `app/src/test/java`.

---

## Problem Statement

Android has three WebView-backed reader modes: paginated, continuous, and VN.
VN is not a separate reader engine; it is a paginator with different page
boundaries and optional progressive reveal. The long-term problem is that VN
has accumulated private copies of reader semantics that should be common:

- text normalization and matchable/raw character counting.
- ruby-aware text handling.
- source-to-rendered text offset mapping.
- Sasayaki cue range construction and punctuation inclusion.
- persisted highlight raw range mapping.
- media classification, image setup, and media-stop behavior.
- progress and restore anchors over chapter text.

The refactor target is not satisfied by creating a new file that only VN uses.
That just relocates the separate VN implementation. A shared module must replace
existing mode-local code in at least two production reader modes before it is
accepted as shared reader core.

## Shared-Core Admission Rules

Use these rules before adding, naming, or keeping a shared reader-web module.

- A file named as shared reader core, such as `reader-*-semantics.js`,
  `reader-*-range*.js`, or `reader-*-stream.js`, must have production callers
  from at least two reader modes in the same implementation phase.
- If a module is only consumed by VN, keep it inside
  `reader-visual-novel.js` or name it explicitly as VN-specific until a second
  mode consumes it.
- A sharing phase must delete or replace the old mode-local implementation it
  supersedes. Adding a generic wrapper while the original logic remains the real
  runtime path does not count.
- A shared API should expose mode-neutral operations. It must not know whether
  a caller is paginated, continuous, or VN unless the function name explicitly
  describes a mode adapter.
- Each phase must include characterization tests before behavior is moved and
  parity tests after both consumers are wired.
- Production line growth is a review signal. After any sharing phase, inspect
  `git diff --numstat main...HEAD`; if production additions are more than twice
  production deletions for that phase, either remove the displaced code in the
  same phase or document why the phase is a foundation-only exception.

Useful audit command:

```bash
git diff --numstat main...HEAD | awk '
BEGIN { prod_add=prod_del=test_add=test_del=doc_add=doc_del=0 }
{
  add=$1; del=$2; path=$3;
  if (path ~ /^docs\// || path == "AGENTS.md") { doc_add += add; doc_del += del }
  else if (path ~ /\/src\/test\//) { test_add += add; test_del += del }
  else { prod_add += add; prod_del += del }
}
END {
  printf("production\t%d\t%d\tnet %+d\n", prod_add, prod_del, prod_add-prod_del);
  printf("tests\t%d\t%d\tnet %+d\n", test_add, test_del, test_add-test_del);
  printf("docs\t%d\t%d\tnet %+d\n", doc_add, doc_del, doc_add-doc_del);
}'
```

## Mode Boundaries

These responsibilities should stay mode-specific unless a later design proves a
smaller safe boundary:

- paginated page metrics, columns, page snapping, and page-turn scroll math.
- continuous viewport scrolling and scroll restore.
- VN block/sentence screen boundaries, `sentencesPerScreen`, viewport fitting,
  reveal timers, blank-area advance, and current-screen rendering.
- navigation bridge behavior that converts a mode-local target into a visible
  page, scroll position, or VN screen.

These responsibilities are candidates for shared core because they describe
reader content rather than reader navigation:

- text normalization and character counting.
- DOM walker filtering rules for readable text.
- ruby normalization and ruby-preserving text extraction.
- media classification and image setup primitives.
- Sasayaki cue normalization, cue intersection, and punctuation policy.
- raw and matchable range indexing over rendered text.
- progress target math over semantic character counts.

## Target File Responsibilities

The exact file names may change during implementation, but each shared file must
meet the admission rules above.

- `app/src/main/assets/hoshi-web/reader/reader-text-semantics.js`:
  normalization, raw counting, matchable counting, and matchable character
  checks. Production consumers: paginated, continuous, and VN.
- `app/src/main/assets/hoshi-web/reader/reader-dom-text.js`:
  readable text walker filters, ruby text normalization, and adjacent text-node
  stabilization when those behaviors are identical. Production consumers:
  paginated and continuous first; VN only where screen rendering needs the same
  DOM operation.
- `app/src/main/assets/hoshi-web/reader/reader-media-semantics.js`:
  media tag classification, standalone-versus-inline decisions, and shared
  image setup primitives. Production consumers: paginated, continuous, and VN.
- `app/src/main/assets/hoshi-web/reader/reader-rendered-range-index.js`:
  generic raw/matchable range lookup over rendered text. It should accept a
  mode adapter for "which rendered root is visible" and "how source offsets map
  to rendered text". Production consumers: shared Sasayaki/highlight code and
  VN.
- `app/src/main/assets/hoshi-web/reader/reader-sasayaki-ranges.js`:
  cue normalization, cue intersection, punctuation inclusion, and conversion
  from cue offsets to rendered ranges. Production consumers:
  `reader-sasayaki.js` for paginated/continuous and VN's Sasayaki integration.
- `app/src/main/assets/hoshi-web/reader/reader-vn-content-stream.js` and
  `app/src/main/assets/hoshi-web/reader/reader-vn-range-map.js`:
  VN-specific runtime primitives. They are allowed only when their names and
  callers make the VN ownership explicit; do not describe them as shared reader
  core until a second mode consumes a mode-neutral API with tests.

## Data Flow

The desired final flow is:

```text
chapter DOM
  -> shared content/text/media semantics
  -> shared rendered range indexes
  -> mode-specific paginator or scroller
  -> mode-specific renderer and bridge commands
```

Paginated and continuous should not adopt VN screen descriptors. VN should not
adopt page/scroll runtime state. The shared layer sits below those mechanics and
answers content questions consistently for all modes.

## Implementation Phases

### Phase 0: Correct The Current Branch Shape

**Purpose:** Make the branch honest before adding more abstraction. Shared files
that only VN uses must either gain a second production consumer or be renamed as
VN-specific.

**Files:**

- Modify: `docs/VN_READER_PAGINATION_REFACTOR.md`
- Inspect: `app/src/main/assets/hoshi-web/reader/reader-text-semantics.js`
- Inspect: `app/src/main/assets/hoshi-web/reader/reader-vn-content-stream.js`
- Inspect: `app/src/main/assets/hoshi-web/reader/reader-vn-range-map.js`
- Inspect: `app/src/main/assets/hoshi-web/reader/reader-visual-novel.js`
- Inspect: `app/src/main/assets/hoshi-web/reader/reader-paginated.js`
- Inspect: `app/src/main/assets/hoshi-web/reader/reader-continuous.js`

- [ ] Run the production/test/doc diff audit command from
  `Shared-Core Admission Rules`.
- [ ] List every new shared-looking module and its production consumers:
  paginated, continuous, VN, or none.
- [ ] For every VN-only shared-looking module, choose one correction:
  make a second mode consume the mode-neutral part, split the mode-neutral part
  into a smaller shared module, or rename the remaining module as VN-specific.
- [ ] Do not proceed to later phases while a VN-only module is documented as
  shared reader core.
- [ ] Commit only the document and naming/splitting corrections for this phase.

Verification:

```bash
git diff --numstat main...HEAD
node --test app/src/test/js/*.test.mjs
```

### Phase 1: Make Text Semantics Truly Three-Mode

**Purpose:** Keep the lowest-risk sharing, but make it explicit and complete.
All three modes should call the same production API for text normalization,
raw counting, matchable counting, and matchable character checks.

**Files:**

- Modify: `app/src/main/assets/hoshi-web/reader/reader-paginated.js`
- Modify: `app/src/main/assets/hoshi-web/reader/reader-continuous.js`
- Modify: `app/src/main/assets/hoshi-web/reader/reader-visual-novel.js`
- Modify/Create:
  `app/src/main/assets/hoshi-web/reader/reader-text-semantics.js`
- Modify: `app/src/test/js/reader-paginated.test.mjs`
- Modify: `app/src/test/js/reader-visual-novel.test.mjs`
- Modify/Create: `app/src/test/js/reader-text-semantics.test.mjs`

- [ ] Add tests that call the shared text API directly for normal text,
  punctuation, whitespace, ruby markup, and gaiji-like inline images.
- [ ] Add mode tests proving paginated, continuous, and VN delegate to the same
  API instead of local copies.
- [ ] Remove local duplicate text semantic helpers from mode files, keeping only
  thin delegating methods when external bridge code expects those method names.
- [ ] Keep page/scroll/screen navigation unchanged.
- [ ] Commit this phase separately.

Verification:

```bash
node --test app/src/test/js/*.test.mjs
./gradlew :app:testDebugUnitTest --tests moe.antimony.hoshi.features.reader.ReaderPaginationScriptsTest
```

### Phase 2: Share DOM Text And Ruby Normalization Where Behavior Matches

**Purpose:** Extract duplicated paginated/continuous DOM text normalization
first, because those two modes already share the same live-DOM model. VN adopts
only the parts that are identical for rendered screen DOM.

**Files:**

- Modify/Create: `app/src/main/assets/hoshi-web/reader/reader-dom-text.js`
- Modify: `app/src/main/assets/hoshi-web/reader/reader-paginated.js`
- Modify: `app/src/main/assets/hoshi-web/reader/reader-continuous.js`
- Modify: `app/src/main/assets/hoshi-web/reader/reader-visual-novel.js`
  only if the rendered-screen behavior is identical.
- Modify: `app/src/test/js/reader-paginated.test.mjs`
- Modify: `app/src/test/js/reader-visual-novel.test.mjs`

- [ ] Characterize current paginated and continuous behavior for
  `normalizeRubyTextNodes`, `stabilizeRubyAdjacentTextNodes`, and readable text
  walker filtering.
- [ ] Extract the identical implementation into `reader-dom-text.js`.
- [ ] Replace paginated and continuous local copies with calls to the shared
  module in the same phase.
- [ ] Add a VN test for furigana preservation after render and reveal. Wire VN
  to the shared helper only if the same helper preserves VN behavior.
- [ ] Remove any displaced local implementation in the same commit.

Verification:

```bash
node --test app/src/test/js/*.test.mjs
git diff --numstat main...HEAD
```

### Phase 3: Share Media Classification And Image Setup

**Purpose:** Remove duplicate image/media setup while preserving mode-specific
navigation. This phase targets the visible bugs around VN consecutive images
without creating a VN-only media module.

**Files:**

- Modify/Create:
  `app/src/main/assets/hoshi-web/reader/reader-media-semantics.js`
- Modify: `app/src/main/assets/hoshi-web/reader/reader-paginated.js`
- Modify: `app/src/main/assets/hoshi-web/reader/reader-continuous.js`
- Modify: `app/src/main/assets/hoshi-web/reader/reader-visual-novel.js`
- Modify: `app/src/test/js/reader-paginated.test.mjs`
- Modify: `app/src/test/js/reader-visual-novel.test.mjs`
- Modify: `app/src/test/java/moe/antimony/hoshi/features/reader/ReaderPaginationScriptsTest.kt`

- [ ] Characterize current paginated/continuous image setup for plain `img`,
  SVG `image`, wrapped images, blur backgrounds, tap handling, and inline gaiji.
- [ ] Extract shared media tag classification and `setupReaderImage` behavior.
- [ ] Replace paginated, continuous, and VN local image setup code with the
  shared API.
- [ ] Keep mode-specific media-stop navigation in each mode.
- [ ] Add VN tests for consecutive standalone images and SVG image containers.
- [ ] Remove old duplicated local image setup functions.

Verification:

```bash
node --test app/src/test/js/*.test.mjs
./gradlew :app:testDebugUnitTest --tests moe.antimony.hoshi.features.reader.ReaderPaginationScriptsTest
```

### Phase 4: Share Sasayaki Cue Range Semantics

**Purpose:** Make the punctuation and cue-range rules common. The shared layer
should build cue ranges over an abstract rendered text index; it should not know
whether the visible target is a page, a scroll position, or a VN screen.

**Files:**

- Modify/Create:
  `app/src/main/assets/hoshi-web/reader/reader-sasayaki-ranges.js`
- Modify/Create:
  `app/src/main/assets/hoshi-web/reader/reader-rendered-range-index.js`
- Modify: `app/src/main/assets/hoshi-web/reader/reader-sasayaki.js`
- Modify: `app/src/main/assets/hoshi-web/reader/reader-visual-novel.js`
- Modify: `app/src/test/js/reader-paginated.test.mjs`
- Modify: `app/src/test/js/reader-visual-novel.test.mjs`

- [ ] Characterize existing paginated/continuous Sasayaki highlighting through
  `reader-sasayaki.js`.
- [ ] Characterize VN cue highlighting, including punctuation split across text
  nodes and cue ranges that cross VN screen boundaries.
- [ ] Extract cue normalization, cue start/end handling, intersection, and
  punctuation inclusion into `reader-sasayaki-ranges.js`.
- [ ] Make `reader-sasayaki.js` use the shared cue-range builder.
- [ ] Make VN use the same cue-range builder through its rendered-screen range
  adapter.
- [ ] Remove VN-private cue range collection that duplicates the shared rule.

Verification:

```bash
node --test app/src/test/js/*.test.mjs
```

### Phase 5: Share Raw Highlight Range Mapping

**Purpose:** Persisted highlights use raw chapter offsets. The raw-range lookup
policy should be shared, while each mode still decides how to display or scroll
to the resulting rendered ranges.

**Files:**

- Modify: `app/src/main/assets/hoshi-web/reader/highlights.js`
- Modify:
  `app/src/main/assets/hoshi-web/reader/reader-rendered-range-index.js`
- Modify: `app/src/main/assets/hoshi-web/reader/reader-visual-novel.js`
- Modify: `app/src/test/js/reader-paginated.test.mjs`
- Modify: `app/src/test/js/reader-visual-novel.test.mjs`

- [ ] Add tests for raw highlight ranges that start/end inside text nodes,
  across sibling text nodes, around ruby markup, and around punctuation.
- [ ] Extract raw-range-to-rendered-range lookup into the shared rendered range
  index.
- [ ] Make existing `highlights.js` use the shared raw range lookup for
  paginated/continuous.
- [ ] Make VN use the same lookup for the currently rendered screen.
- [ ] Remove VN-private raw highlight segment collectors.

Verification:

```bash
node --test app/src/test/js/*.test.mjs
```

### Phase 6: Share Progress Target Helpers, Not Navigation Mechanics

**Purpose:** Share the semantic math for "which chapter character position does
this progress mean" while preserving each mode's own visible landing behavior.

**Files:**

- Modify/Create:
  `app/src/main/assets/hoshi-web/reader/reader-progress-semantics.js`
- Modify: `app/src/main/assets/hoshi-web/reader/reader-paginated.js`
- Modify: `app/src/main/assets/hoshi-web/reader/reader-continuous.js`
- Modify: `app/src/main/assets/hoshi-web/reader/reader-visual-novel.js`
- Modify: `app/src/test/js/reader-paginated.test.mjs`
- Modify: `app/src/test/js/reader-visual-novel.test.mjs`

- [ ] Extract only mode-neutral helpers: clamping progress, converting progress
  to target character count, and finding the nearest text position for a target
  count.
- [ ] Keep paginated page snapping, continuous scroll landing, and VN screen
  selection in their existing mode files.
- [ ] Add tests for chapter start, near-zero progress, mid-node restore,
  near-end restore, and exact end restore.
- [ ] Remove duplicate target-count math where the shared helper replaces it.

Verification:

```bash
node --test app/src/test/js/*.test.mjs
```

### Phase 7: Final Reduction And Documentation Cleanup

**Purpose:** Close the loop by ensuring shared modules are actually shared and
VN no longer carries duplicate implementations for the extracted semantics.

**Files:**

- Modify: `app/src/main/assets/hoshi-web/reader/reader-visual-novel.js`
- Modify: `app/src/main/assets/hoshi-web/reader/reader-paginated.js`
- Modify: `app/src/main/assets/hoshi-web/reader/reader-continuous.js`
- Modify: `app/src/main/java/moe/antimony/hoshi/features/reader/ReaderPaginationScripts.kt`
- Modify: `app/src/main/java/moe/antimony/hoshi/features/reader/ReaderWebAssets.kt`
- Modify: `docs/ARCHITECTURE.md`
- Modify: `docs/ARCHITECTURE_REFACTORING.md`
- Modify: `AGENTS.md` only if the accepted shared-core rule must become an
  always-loaded repository rule.

- [ ] Run `rg` for removed duplicate function names such as
  `collectSasayakiCueRanges`, `highlightSegmentsForChapterRawRange`,
  `setupReaderImage`, `normalizeRubyTextNodes`, and `stabilizeRubyAdjacentTextNodes`.
- [ ] For each remaining mode-local implementation, classify it as
  mode-specific navigation/rendering or move it into shared core.
- [ ] Run the production/test/doc diff audit and check that production growth is
  explained by real new behavior or offset by deletion of displaced code.
- [ ] Update `docs/ARCHITECTURE.md` only for current architecture facts that are
  true after the implementation.
- [ ] Remove completed future-target text from
  `docs/ARCHITECTURE_REFACTORING.md` or rewrite it to describe only remaining
  durable gaps.
- [ ] Do not add a changelog entry for pure refactor work. Add one only for
  user-visible fixes that ship as behavior changes.

Verification:

```bash
node --test app/src/test/js/*.test.mjs
./gradlew test
./gradlew assembleDebug
git diff --check
git status --short
```

## Testing Strategy

Prefer behavior tests over source-string assertions.

- Shared modules get direct `node --test` coverage with DOM fixtures created in
  the test file.
- Every shared module must have at least one test proving two production modes
  consume the same API.
- VN tests must cover furigana preservation, reveal behavior, consecutive media
  screens, Sasayaki punctuation, cross-screen cues, raw highlights, lookup
  ranges, fragment restore, and progress restore.
- Paginated/continuous tests must cover parity for any helper they adopt.
- Kotlin tests should verify asset injection order and bridge command assembly
  only where behavior cannot be tested directly in JavaScript.

Required commands before claiming the refactor complete:

```bash
node --test app/src/test/js/*.test.mjs
./gradlew test
./gradlew assembleDebug
```

Manual validation is required for user-visible reader behavior after runtime
migration slices. Follow `docs/VALIDATION.md` and cover paginated, continuous,
and VN modes in vertical and horizontal writing; VN block and sentence screens;
reveal speed 0/45/120; blank-area click advance; lookup taps; links; images;
restore; chapter boundaries; and Sasayaki cue behavior.

## Exit Criteria

The refactor is complete only when all of these are true:

- Every shared reader-web module has production consumers from at least two
  modes; mode-neutral modules have consumers from all three modes.
- VN no longer has private copies of text semantics, ruby/text normalization
  that matches other modes, media classification/image setup, Sasayaki cue range
  policy, raw highlight range mapping, or progress target math.
- Paginated and continuous keep their page/scroll navigation behavior, but they
  consume shared content semantics where the behavior is mode-neutral.
- VN keeps only genuine VN responsibilities: block/sentence pagination,
  viewport fitting, reveal, screen rendering, and screen-based navigation.
- The final production diff shows displaced mode-local code being removed, not
  only new abstraction being added.
- Automated tests and required Gradle commands pass.
- Architecture docs describe the final current state without completed phase
  history or temporary handoff notes.
