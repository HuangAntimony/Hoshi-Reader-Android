import assert from 'node:assert/strict';
import fs from 'node:fs';
import test from 'node:test';
import vm from 'node:vm';

function paragraphRuntime({ vertical = false, supported = true } = {}) {
    const frames = new Map();
    const highlights = new Map();
    const ranges = [];
    const listeners = new Map();
    let next = 0;
    const nodes = [{ textContent: '甲𠮷乙丙', length: 5 }];
    const reader = {
        isVertical: () => vertical, pageWidth: 400, pageHeight: 600,
        getScrollContext: () => ({ vertical, pageSize: vertical ? 600 : 400 }),
        getPagePosition: () => 0,
        get paginationMetrics() { return { textRanges: nodes.map(node => ({
            node, first: vertical ? (node.rect?.top ?? 0) : (node.rect?.left ?? 0),
        })) }; },
        createWalker: () => { let i = 0; return { nextNode: () => nodes[i++] }; },
        getRect: target => target.rect || { left: 0, top: 0, width: 20, height: 20 },
        cueWrappers: new Map(), cueGeometryRanges: new Map(),
    };
    const document = { querySelectorAll: () => [], addEventListener: (name, callback) => listeners.set(name, callback), createRange: () => {
        const range = {
            selectNodeContents(node) { this.rect = node.rect; },
            setStart(node, offset) { this.startNode = node; this.start = offset; },
            setEnd(node, offset) { this.endNode = node; this.end = offset; },
            getClientRects() { return [this.startNode.characterRects[this.start]]; },
        };
        ranges.push(range); return range;
    } };
    const context = {
        window: { hoshiReader: reader }, document,
        Node: { TEXT_NODE: 3 }, NodeFilter: { SHOW_TEXT: 4, SHOW_ELEMENT: 1 },
        CSS: supported ? { highlights } : {},
        Highlight: class { constructor(range) { this.range = range; } clear() {} },
        requestAnimationFrame: callback => { frames.set(++next, callback); return next; },
        cancelAnimationFrame: id => frames.delete(id), performance: { now: () => 0 },
    };
    vm.runInNewContext(fs.readFileSync(new URL('../../main/assets/hoshi-web/reader/reader-paragraph.js', import.meta.url), 'utf8'), context);
    return { paragraph: context.window.hoshiParagraph, window: context.window, document, reader, nodes, frames, ranges, highlights, listeners };
}

test('paragraph splitting leaves a hanging full-width space on the previous page', () => {
    for (const vertical of [false, true]) for (const shift of [0, -0.125, 0.125]) {
        const { paragraph, document, reader, nodes } = paragraphRuntime({ vertical });
        const { pageSize } = reader.getScrollContext();
        const rect = (start, end) => vertical
            ? { top: start, bottom: end, height: end - start }
            : { left: start, right: end, width: end - start };
        const node = { nodeType: 3, textContent: '甲　乙丙', characterRects: [
            rect(pageSize - 59, pageSize - 20),
            rect(pageSize - 20, pageSize + 19),
            rect(pageSize + shift, pageSize + shift + 39),
            rect(pageSize + shift + 39, pageSize + shift + 78),
        ] };
        nodes.splice(0, nodes.length, node);
        document.createTreeWalker = reader.createWalker;
        const block = { getBoundingClientRect: () => rect(0, 2 * pageSize) };

        const points = paragraph.splitPoints(block, pageSize, 2 * pageSize, vertical);
        assert.equal(points.length, 1, `vertical=${vertical}, shift=${shift}`);
        assert.equal(points[0].node, node);
        assert.equal(points[0].offset, 2);
    }
});

test('hidden text after a page boundary does not hide an earlier split candidate', () => {
    for (const vertical of [false, true]) for (const tail of [true, false]) {
        const { paragraph, document, reader, nodes } = paragraphRuntime({ vertical });
        const rect = start => vertical ? { top:start, bottom:start+5, height:5 }
            : { left:start, right:start+5, width:5 };
        nodes.splice(0, nodes.length,
            { nodeType:3, textContent:'甲', rects:[rect(90)] },
            { nodeType:3, textContent:'乙', rects:[rect(100)] },
            { nodeType:3, textContent:'隠𠮷隠', rects:[] },
            ...(tail ? [{ nodeType:3, textContent:'丙', rects:[rect(110)] }] : []));
        document.createTreeWalker = reader.createWalker;
        document.createRange = () => ({
            setStart(node) { this.node = node; }, setEnd() {},
            getClientRects() { return this.node.rects; },
        });
        const block = { getBoundingClientRect: () => vertical ? { top:0, height:200 } : { left:0, width:200 } };
        const points = paragraph.splitPoints(block, 100, 0, vertical);
        assert.equal(points.length, 1);
        assert.equal(points[0].node, nodes[1]);
        assert.equal(points[0].offset, 0);
    }
});

test('paragraph cuts search UTF-16 boundaries without enumerating every character', () => {
    for (const vertical of [false, true]) {
        const { paragraph, document, reader, nodes } = paragraphRuntime({ vertical });
        const { pageSize } = reader.getScrollContext();
        const text = '甲𠮷乙'.repeat(4000);
        let textReads = 0, queries = 0;
        const node = { nodeType: 3, get textContent() { textReads++; return text; } };
        nodes.splice(0, nodes.length, node);
        document.createTreeWalker = reader.createWalker;
        const rect = (start, end) => vertical ? { top:start, bottom:end, height:end-start }
            : { left:start, right:end, width:end-start };
        document.createRange = () => ({
            setStart(_, offset) { this.offset = offset; assert.notEqual(offset % 4, 2); },
            setEnd(_, offset) { assert.notEqual(offset % 4, 2); },
            getClientRects() {
                queries++;
                const page = this.offset < 4000 ? 0 : this.offset < 12000 ? 1 : 2;
                return [rect(page * pageSize + 10, page * pageSize + 30)];
            },
        });
        const block = { getBoundingClientRect: () => rect(0, 3 * pageSize) };
        const points = paragraph.splitPoints(block, pageSize, 0, vertical);
        assert.deepEqual(Array.from(points, point => point.offset), [4000, 12000]);
        assert.ok(queries < 40, 'query only the two binary-searched boundaries');
        assert.ok(textReads < 100, 'do not walk all code points to construct a position array');
    }
});

test('the existing split scan records ruby spanning both sides of a page boundary', () => {
    for (const vertical of [false, true]) for (const first of [false, true]) {
        const { paragraph, document, reader, nodes } = paragraphRuntime({ vertical });
        const { pageSize } = reader.getScrollContext();
        const rect = (start, end) => vertical
            ? { top: start, bottom: end, height: end - start }
            : { left: start, right: end, width: end - start };
        const ruby = { localName: 'ruby', nodeType: 1, rects: [
            rect(pageSize - 20, pageSize), rect(pageSize, pageSize + 40),
        ] };
        const before = { nodeType: 3, textContent: '前', characterRects: [rect(0, 20)] };
        nodes.splice(0, nodes.length, ...(first ? [] : [before]), ruby);
        document.createTreeWalker = reader.createWalker;
        document.createRange = () => ({
            setStart(node, offset) { this.rects = [node.characterRects[offset]]; },
            setEnd() {}, selectNode(node) { this.rects = node.rects; },
            getClientRects() { return this.rects; },
        });
        const block = { getBoundingClientRect: () => rect(0, 2 * pageSize) };
        const crossed = new Set();
        const points = paragraph.splitPoints(block, pageSize, 3 * pageSize, vertical, crossed);
        assert.equal(points.length, 0, 'a split inside ruby needs a new layout first');
        assert.deepEqual([...crossed], [block], `vertical=${vertical}, first=${first}`);

        // An aligned ruby can start a fraction of a pixel before the boundary.
        ruby.rects = [rect(pageSize - 0.125, pageSize + 20), rect(pageSize + 20, pageSize + 40)];
        crossed.clear();
        paragraph.splitPoints(block, pageSize, 3 * pageSize, vertical, crossed);
        assert.equal(crossed.size, 0, 'rounding must not turn a single-page ruby into a crossing');
    }
});

test('paragraph reveal advances by elapsed time at code point boundaries without writing text', () => {
    const { paragraph, nodes, frames, highlights } = paragraphRuntime();
    const text = nodes[0].textContent;
    Object.defineProperty(nodes[0], 'textContent', { get: () => text, set() { throw new Error('animation mutated text'); } });
    paragraph.animateText(25);
    const range = highlights.get('hoshi-animation').range;
    assert.equal(range.start, 1);
    frames.get(paragraph.animationFrame)(0);
    frames.get(paragraph.animationFrame)(40);
    assert.equal(range.start, 3);
    assert.equal(paragraph.finishTextAnimation(), true);
    assert.equal(paragraph.finishTextAnimation(), false);
    assert.equal(highlights.size, 0);
});

test('reveal starts from its first frame timestamp even when it predates setup time', () => {
    const { paragraph, frames, highlights } = paragraphRuntime();
    paragraph.animateText(25);
    const range = highlights.get('hoshi-animation').range;
    assert.doesNotThrow(() => frames.get(paragraph.animationFrame)(-100));
    assert.equal(range.start, 1, 'the first frame must not hide the already revealed character');
    frames.get(paragraph.animationFrame)(-60);
    assert.equal(range.start, 3);
    frames.get(paragraph.animationFrame)(500);
    assert.equal(paragraph.animationFrame, null);
    assert.equal(highlights.size, 0);
});

test('reveal does not invalidate its Range again until another character is due', () => {
    const { paragraph, frames, highlights } = paragraphRuntime();
    paragraph.animateText(35);
    const range = highlights.get('hoshi-animation').range;
    const setStart = range.setStart;
    let writes = 0;
    range.setStart = function(...args) { writes++; return setStart.apply(this, args); };
    for (const time of [0, 8, 16, 24]) frames.get(paragraph.animationFrame)(time);
    assert.equal(writes, 0, 'high-refresh screens must not repaint unchanged reveal boundaries');
    for (const time of [29, 33, 40, 50]) frames.get(paragraph.animationFrame)(time);
    assert.equal(writes, 1);
    assert.equal(range.start, 3, 'the supplementary character remains intact');
    frames.get(paragraph.animationFrame)(58);
    assert.equal(writes, 2);
    assert.equal(range.start, 4);
});

test('missing Custom Highlight API leaves text visible', () => {
    const { paragraph, frames } = paragraphRuntime({ supported: false });
    paragraph.animateText(35);
    assert.equal(frames.size, 0);
});

test('subpixel page boundaries reveal from the first current-page node and exclude the next page', () => {
    for (const vertical of [false, true]) for (const shift of [-0.156, -0.022, 0, 0.156]) {
        for (const withRuby of [false, true]) {
            const { paragraph, nodes, highlights } = paragraphRuntime({ vertical });
            const pageSize = vertical ? 600 : 400;
            const node = (text, position) => ({ textContent: text, length: text.length,
                rect: { left: position + shift, top: position + shift } });
            const first = node('　始まりの文章。', 0);
            const last = withRuby ? node('本文の続き。', 120) : first;
            nodes.splice(0, nodes.length, node('前の段落。', -pageSize), first,
                ...(withRuby ? [node('漢字', 100), last] : []), node('次の段落。', pageSize));
            paragraph.animateText(25);
            const range = highlights.get('hoshi-animation')?.range;
            assert.equal(range?.startNode, first, `first text must animate: vertical=${vertical}, shift=${shift}, ruby=${withRuby}`);
            assert.equal(range.start, 1);
            assert.equal(range.endNode, last, 'reveal must end on the current page');
            assert.equal(range.end, last.length);
        }
    }
});

test('page cues at subpixel boundaries belong to exactly one page on either axis', () => {
    for (const vertical of [false, true]) for (const shift of [-0.156, -0.022, 0, 0.156]) {
        const { paragraph, reader } = paragraphRuntime({ vertical });
        const pageSize = vertical ? 600 : 400;
        for (const [id, position] of [['previous', -pageSize], ['current', 0], ['next', pageSize]]) {
            const target = { rect: { left: position + shift, top: position + shift } };
            reader.cueWrappers.set(id, [target]);
            reader.cueGeometryRanges.set(`${id}-eink`, [target]);
        }
        assert.deepEqual(Array.from(paragraph.pageSasayakiCues()), ['current', 'current-eink']);
    }
});

test('page cue collection uses start geometry on the Android pagination axis', () => {
    for (const vertical of [false, true]) {
        const { paragraph, reader } = paragraphRuntime({ vertical });
        let contextReads = 0;
        reader.getScrollContext = () => { contextReads++; return { vertical, pageSize: vertical ? 600 : 400 }; };
        reader.cueWrappers.set('normal', [{ rect: { left: 20, top: 20 } }]);
        reader.cueGeometryRanges.set('eink', [{ rect: { left: 30, top: 30 } }]);
        reader.cueGeometryRanges.set('previous', [{ rect: { left: -1, top: -1 } }]);
        reader.cueGeometryRanges.set('next', [{ rect: { left: 400, top: 600 } }]);
        assert.deepEqual(Array.from(paragraph.pageSasayakiCues()), ['normal', 'eink']);
        assert.equal(contextReads, 1, 'page cue queries share one viewport/style read');
    }
});

test('disabling animation cancels its frame and removes hidden text immediately', () => {
    const { paragraph, frames, highlights } = paragraphRuntime();
    paragraph.animateText(35);
    paragraph.configureAnimation(false, 35);
    assert.equal(frames.size, 0);
    assert.equal(highlights.size, 0);
});


test('one animated tap is consumed for both native selection and DOM image/link handlers in either order', () => {
    for (const domFirst of [false, true]) {
        const { paragraph, listeners } = paragraphRuntime();
        paragraph.enabled = true;
        paragraph.installTapHandler();
        listeners.get('pointerdown')();
        paragraph.animateText(25);
        let prevented = 0;
        const event = { preventDefault() { prevented++; }, stopImmediatePropagation() {} };
        if (domFirst) listeners.get('click')(event);
        assert.equal(paragraph.consumeAnimationTap(), true);
        if (!domFirst) listeners.get('click')(event);
        assert.equal(prevented, 1);
        listeners.get('pointerdown')();
        assert.equal(paragraph.consumeAnimationTap(), false);
        listeners.get('click')(event);
        assert.equal(prevented, 1);
    }
});

test('a delayed DOM click from blank-tap advance cannot finish the new page reveal', () => {
    for (const domFirst of [false, true]) {
        const { paragraph, window, listeners, highlights } = paragraphRuntime();
        paragraph.enabled = true;
        paragraph.installTapHandler();
        window.hoshiSelection = { lastTapWasText: false, selectText: () => null };
        let prevented = 0;
        const event = { preventDefault() { prevented++; }, stopImmediatePropagation() {} };

        listeners.get('pointerdown')();
        if (domFirst) listeners.get('click')(event);
        assert.equal(paragraph.selectText(1, 1, 20), null, 'the blank tap can advance');
        paragraph.animateText(35); // Native navigation starts the next page's reveal.
        if (!domFirst) listeners.get('click')(event);
        assert.equal(highlights.size, 1, 'the advancing tap must leave the new reveal running');
        assert.equal(prevented, 0);

        listeners.get('pointerdown')();
        if (domFirst) listeners.get('click')(event);
        assert.equal(paragraph.selectText(1, 1, 20), 'animation', 'a new tap completes reveal without advancing');
        if (!domFirst) listeners.get('click')(event);
        assert.equal(highlights.size, 0);
        assert.equal(prevented, 1, 'the reveal-completing tap must still block link/image actions');
    }
});


test('text deselection and unscannable text never become a blank-area advance', () => {
    const { paragraph, window } = paragraphRuntime();
    paragraph.enabled = true;
    window.hoshiSelection = { lastTapWasText: true, selectText: () => null };
    assert.equal(paragraph.selectText(1, 1, 20), 'text');
    window.hoshiSelection.lastTapWasText = false;
    assert.equal(paragraph.selectText(1, 1, 20), null);
});
