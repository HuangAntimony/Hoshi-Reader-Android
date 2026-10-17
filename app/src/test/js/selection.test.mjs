import assert from 'node:assert/strict';
import fs from 'node:fs';
import test from 'node:test';
import vm from 'node:vm';

const sharedSelectionUrl = new URL('../../main/assets/hoshi-web/shared/selection.js', import.meta.url);
const japaneseLanguageUrl = new URL('../../main/assets/hoshi-web/shared/language-ja.js', import.meta.url);
const japaneseSelectionUrl = new URL('../../main/assets/hoshi-web/shared/selection-ja.js', import.meta.url);
const englishSelectionUrl = new URL('../../main/assets/hoshi-web/shared/selection-en.js', import.meta.url);

function selectionSource() {
    return [
        japaneseLanguageUrl,
        japaneseSelectionUrl,
        englishSelectionUrl,
        new URL('../../main/assets/hoshi-web/reader/reader-dom-text.js', import.meta.url),
        sharedSelectionUrl,
    ].map((url) => fs.readFileSync(url, 'utf8')).join('\n');
}

function loadSelection(text) {
    let nativeSelection = null;
    const listeners = {};
    const textNode = {
        nodeType: 3,
        textContent: text,
        parentElement: null,
    };
    const paragraph = {
        nodeType: 1,
        children: [],
        textContent: text,
        closest(selector) {
            return selector.split(',').map((item) => item.trim()).includes('p') ? this : null;
        },
        querySelectorAll() {
            return [];
        },
    };
    textNode.parentElement = paragraph;

    const document = {
        body: paragraph,
        pointElement: null,
        createdRanges: [],
        createTreeWalker() {
            return treeWalker([textNode]);
        },
        createRange() {
            const range = {
                startContainer: null,
                startOffset: 0,
                endContainer: null,
                endOffset: 0,
                setStart(node, offset) {
                    this.startContainer = node;
                    this.startOffset = offset;
                },
                setEnd(node, offset) {
                    this.endContainer = node;
                    this.endOffset = offset;
                },
                collapse() {},
                getClientRects() {
                    return [];
                },
                getBoundingClientRect() {
                    return { x: 0, y: 0, width: 0, height: 0 };
                },
            };
            this.createdRanges.push(range);
            return range;
        },
        elementFromPoint() {
            return this.pointElement;
        },
        addEventListener(name, listener) { listeners[name] = listener; },
    };
    const window = {
        getComputedStyle() {
            return { writingMode: 'horizontal-tb' };
        },
    };

    vm.runInNewContext(selectionSource(), {
        CSS: {},
        innerWidth: 400,
        innerHeight: 800,
        document,
        getSelection() {
            return nativeSelection;
        },
        Node: { TEXT_NODE: 3 },
        NodeFilter: { SHOW_TEXT: 4, FILTER_ACCEPT: 1, FILTER_REJECT: 2 },
        window,
    });

    return {
        document,
        dispatchDocumentEvent(name, event) { listeners[name]?.(event); },
        changeNativeSelection(value) {
            nativeSelection = value;
            listeners.selectionchange();
        },
        selection: window.hoshiSelection,
        textNode,
        window,
    };
}

function loadSharedSelectionWithoutPolicy() {
    const document = {
        body: {},
        documentElement: {},
        addEventListener() {},
    };
    const window = {
        getComputedStyle() {
            return { writingMode: 'horizontal-tb' };
        },
    };

    vm.runInNewContext(fs.readFileSync(sharedSelectionUrl, 'utf8'), {
        document,
        getSelection() {
            return null;
        },
        Node: { TEXT_NODE: 3 },
        NodeFilter: { SHOW_TEXT: 4, FILTER_ACCEPT: 1, FILTER_REJECT: 2 },
        window,
    });

    return window.hoshiSelection;
}

function loadAdjacentExpressionTags() {
    const expressionElement = (text) => ({
        nodeType: 1,
        textContent: text,
        closest(selector) {
            return selector.split(',').map((item) => item.trim()).includes('.expr-tag') ? this : null;
        },
        querySelectorAll() {
            return [];
        },
    });
    const firstExpression = expressionElement('猫');
    const secondExpression = expressionElement('犬');
    const firstNode = { nodeType: 3, textContent: '猫', parentElement: firstExpression };
    const secondNode = { nodeType: 3, textContent: '犬', parentElement: secondExpression };
    const body = {
        nodeType: 1,
        textContent: '猫犬',
        querySelectorAll() {
            return [];
        },
    };
    const document = {
        body,
        documentElement: {},
        createTreeWalker(root) {
            if (root === firstExpression) return treeWalker([firstNode]);
            if (root === secondExpression) return treeWalker([secondNode]);
            return treeWalker([firstNode, secondNode]);
        },
        elementFromPoint() {
            return hitElement([]);
        },
        addEventListener() {},
    };
    const window = {
        getComputedStyle() {
            return { writingMode: 'horizontal-tb' };
        },
    };

    vm.runInNewContext(selectionSource(), {
        CSS: {},
        document,
        getSelection() {
            return null;
        },
        Node: { TEXT_NODE: 3 },
        NodeFilter: { SHOW_TEXT: 4, FILTER_ACCEPT: 1, FILTER_REJECT: 2 },
        window,
    });

    const selection = window.hoshiSelection;
    selection.clearSelection = () => {};
    selection.getCharacterAtPoint = () => ({ node: firstNode, offset: 0 });
    selection.getSelectionRect = () => ({ x: 0, y: 0, width: 1, height: 1 });
    selection.postTextSelected = () => {};
    window.scanNonJapaneseText = false;
    return selection;
}

function scanTextFromOffset(text, offset, configureOptions = {}) {
    const { document, selection, textNode, window } = loadSelection(text);
    document.pointElement = hitElement([]);
    selection.configure(configureOptions);
    selection.postTextSelected = () => {};
    selection.clearSelection = () => {};
    selection.getCharacterAtPoint = () => ({ node: textNode, offset });
    window.scanNonJapaneseText = false;
    return selection.selectText(1, 1, 80);
}

function hitElement(matches) {
    return {
        closest(selector) {
            const selectors = selector.split(',').map((item) => item.trim());
            return matches.some((match) => selectors.includes(match)) ? this : null;
        },
    };
}

function treeWalker(nodes) {
    let index = -1;
    return {
        get currentNode() {
            return nodes[index] ?? null;
        },
        set currentNode(node) {
            index = nodes.indexOf(node);
        },
        nextNode() {
            if (index + 1 >= nodes.length) return null;
            index += 1;
            return nodes[index];
        },
        previousNode() {
            if (index - 1 < 0) return null;
            index -= 1;
            return nodes[index];
        },
    };
}

function sentenceContext(text, selectedText) {
    const { selection, textNode } = loadSelection(text);
    return selection.getSentenceContext(textNode, text.indexOf(selectedText));
}

test('reader selection trims trailing unmatched quote brackets from mined sentence', () => {
    const context = sentenceContext(
        '「でもはいらないよ。とにかく明日は宿を探そう。だから、そんなに気を張らないでいいよ」',
        '気を張',
    );

    assert.equal(context.sentence, 'だから、そんなに気を張らないでいいよ');
    assert.equal(context.sentenceOffset, 'だから、そんなに'.length);
});

test('reader selection trims leading unmatched opening quote brackets from mined sentence', () => {
    const context = sentenceContext(
        '「でもはいらないよ。とにかく明日は宿を探そう。だから、そんなに気を張らないでいいよ」',
        'でも',
    );

    assert.equal(context.sentence, 'でもはいらないよ。');
    assert.equal(context.sentenceOffset, 0);
});

test('reader selection leaves ellipses and periods after the selected sentence', () => {
    const ellipsisContext = sentenceContext('選択。…次。', '選択');
    assert.equal(ellipsisContext.sentence, '選択。');
    assert.equal(ellipsisContext.sentenceOffset, 0);

    const periodContext = sentenceContext('選択！.次。', '選択');
    assert.equal(periodContext.sentence, '選択！');
    assert.equal(periodContext.sentenceOffset, 0);
});

test('recursive lookup scanning stays inside one expression tag', () => {
    const selection = loadAdjacentExpressionTags();

    assert.equal(selection.selectText(1, 1, 80), '猫');
});

test('shared selection posts popup payloads through the webkit bridge', () => {
    const { selection, window } = loadSelection('猫');
    let posted = null;
    window.webkit = {
        messageHandlers: {
            textSelected: {
                postMessage(payload) {
                    posted = payload;
                },
            },
        },
    };

    selection.postTextSelected({ text: '猫', sentence: '猫。' });

    assert.deepEqual(posted, { text: '猫', sentence: '猫。' });
});

test('shared selection posts reader payloads through the Android reader bridge', () => {
    const { selection, window } = loadSelection('猫');
    let posted = null;
    window.HoshiTextSelection = {
        postMessage(message) {
            posted = message;
        },
    };

    selection.configure({ bridge: 'android-reader' });
    selection.postTextSelected({ text: '猫', sentence: '猫。' });

    assert.equal(posted, JSON.stringify({ text: '猫', sentence: '猫。' }));
});

test('shared selection can preserve reader link and image tap tokens', () => {
    const { selection } = loadSelection('猫');

    selection.configure({ linkTapResult: 'link', imageTapResult: 'image' });

    assert.equal(selection.linkTapResult(), 'link');
    assert.equal(selection.imageTapResult(), 'image');
});

test('shared selection reads semantic text while keeping projected visible ranges', () => {
    const sourceText = '現在激しい抵抗を見せていた。';
    const { document, selection, textNode: sourceNode, window } = loadSelection(sourceText);
    const renderedNode = {
        nodeType: 3,
        textContent: '激',
        parentElement: null,
    };
    let projectedRanges = null;
    let posted = null;
    document.pointElement = hitElement([]);
    window.getSelection = () => null;
    selection.getCharacterAtPoint = () => ({ node: renderedNode, offset: 0 });
    selection.postTextSelected = (payload) => {
        posted = payload;
    };
    selection.configure({
        textProjection: {
            toSemanticHit(renderedHit) {
                assert.equal(renderedHit.node, renderedNode);
                return { node: sourceNode, offset: sourceText.indexOf('激') };
            },
            normalizedOffsetForHit() {
                return 314;
            },
            visibleRangesForSemanticRanges(ranges) {
                projectedRanges = ranges;
                return [{ node: renderedNode, start: 0, end: 1 }];
            },
        },
    });
    window.scanNonJapaneseText = false;

    const selected = selection.selectText(1, 1, 80);

    assert.equal(selected, '激しい抵抗を見せていた');
    assert.equal(posted.text, '激しい抵抗を見せていた');
    assert.equal(posted.sentence, sourceText);
    assert.equal(posted.sentenceOffset, sourceText.indexOf('激'));
    assert.equal(posted.normalizedOffset, 314);
    assert.equal(projectedRanges.length, 1);
    assert.equal(projectedRanges[0].node, sourceNode);
    assert.equal(selection.selection.ranges[0].node, renderedNode);
});

test('shared selection fails closed when a configured text projection cannot map the hit', () => {
    const { document, selection, textNode, window } = loadSelection('激');
    document.pointElement = hitElement([]);
    window.getSelection = () => null;
    selection.getCharacterAtPoint = () => ({ node: textNode, offset: 0 });
    selection.configure({
        textProjection: {
            toSemanticHit() {
                return null;
            },
        },
    });
    window.scanNonJapaneseText = false;

    assert.equal(selection.selectText(1, 1, 80), null);
    assert.equal(selection.selection, null);
});

test('english selection can start before the visible clone fragment and continue in source text', () => {
    const sourceText = 'A difficult resistance.';
    const { document, selection, textNode: sourceNode, window } = loadSelection(sourceText);
    const renderedNode = {
        nodeType: 3,
        textContent: 'A diff',
        parentElement: null,
    };
    document.pointElement = hitElement([]);
    window.getSelection = () => null;
    selection.getCharacterAtPoint = () => ({ node: renderedNode, offset: 5 });
    selection.configure({
        language: 'en',
        textProjection: {
            toSemanticHit() {
                return { node: sourceNode, offset: 5 };
            },
            normalizedOffsetForHit() {
                return 5;
            },
            visibleRangesForSemanticRanges() {
                return [{ node: renderedNode, start: 2, end: 6 }];
            },
        },
    });

    assert.equal(selection.selectText(1, 1, 80), 'difficult resistance');
    assert.equal(selection.selection.startOffset, 2);
    assert.equal(selection.selection.ranges[0].node, renderedNode);
});

test('shared selection treats missing language policy as a no-scan boundary', () => {
    const selection = loadSharedSelectionWithoutPolicy();

    assert.equal(selection.languagePolicy(), undefined);
    assert.doesNotThrow(() => selection.isScanBoundary('猫'));
    assert.equal(selection.isScanBoundary('猫'), true);
    assert.equal(selection.selectionStartForHit({ node: {}, offset: 3 }).offset, 3);
});

test('english reader selection ignores the Japanese non-Japanese scan toggle', () => {
    assert.equal(
        scanTextFromOffset('reading', 0, { language: 'en' }),
        'reading',
    );
});

test('english reader selection scans from the beginning of a tapped word', () => {
    assert.equal(
        scanTextFromOffset('reading', 3, { language: 'en' }),
        'reading',
    );
});

test('english reader selection keeps spaces while scanning for phrase lookups', () => {
    assert.equal(
        scanTextFromOffset('New York style pizza.', 0, { language: 'en' }),
        'New York style pizza',
    );
});

test('english reader selection does not include leading quotation punctuation', () => {
    assert.equal(
        scanTextFromOffset('"And finally, ...', 1, { language: 'en' }),
        'And finally',
    );
    assert.equal(
        scanTextFromOffset('“And finally, ...', 1, { language: 'en' }),
        'And finally',
    );
});

test('english reader selection stops at English sentence punctuation', () => {
    assert.equal(
        scanTextFromOffset('Hello! Next', 0, { language: 'en' }),
        'Hello',
    );
});

test('english reader selection keeps word-internal apostrophes', () => {
    assert.equal(
        scanTextFromOffset("can't stop.", 1, { language: 'en' }),
        "can't stop",
    );
});

test('japanese reader selection still honors the non-Japanese scan toggle', () => {
    assert.equal(
        scanTextFromOffset('reading', 0, { language: 'ja' }),
        null,
    );
});

test('japanese reader selection scans full names across middle dots', () => {
    assert.equal(
        scanTextFromOffset('アーサー・コナン・ドイル。次の文', 0, { language: 'ja' }),
        'アーサー・コナン・ドイル',
    );
});

test('shared selection scans supplementary-plane hits from the full character boundary', () => {
    assert.equal(
        scanTextFromOffset('𠮟り付けてはみても、不安', 1, { language: 'ja' }),
        '𠮟り付けてはみても',
    );
});

test('shared selection treats svg containers as reader taps while preserving svg image hits', () => {
    const { document, selection } = loadSelection('猫');
    let clearCount = 0;
    selection.configure({ imageTapResult: 'image' });
    selection.getCharacterAtPoint = () => null;
    selection.clearSelection = () => {
        clearCount += 1;
    };

    document.pointElement = hitElement(['svg']);
    assert.equal(selection.selectText(1, 1, 10), null);

    document.pointElement = hitElement(['image']);
    assert.equal(selection.selectText(1, 1, 10), 'image');

    document.pointElement = hitElement(['.blur-wrapper']);
    assert.equal(selection.selectText(1, 1, 10), 'image');
    assert.equal(clearCount, 1);
});

test('shared selection exposes one highlight range per selected character', () => {
    const { document, selection, textNode } = loadSelection('𠮟猫犬');
    selection.selection = {
        ranges: [{ node: textNode, start: 0, end: textNode.textContent.length }],
        text: textNode.textContent,
    };

    const ranges = selection.selectionCharacterRanges(3);

    assert.equal(ranges.length, 3);
    assert.equal(
        JSON.stringify(ranges.map((range) => [range.startOffset, range.endOffset])),
        JSON.stringify([[0, 2], [2, 3], [3, 4]]),
    );
    assert.equal(document.createdRanges.length, 3);
});

test('shared ruby geometry exposes merged inline rects for reader Sasayaki overlay', () => {
    const { window } = loadSelection('猫');
    const merged = window.hoshiRubyGeometry.mergeInlineRects([
        { x: 0, y: 0, width: 10, height: 12 },
        { x: 10, y: 0, width: 8, height: 12 },
    ]);

    assert.equal(typeof window.hoshiRubyGeometry.mergeInlineRects, 'function');
    assert.equal(merged.length, 1);
    assert.equal(merged[0].x, 0);
    assert.equal(merged[0].y, 0);
    assert.equal(merged[0].width, 18);
    assert.equal(merged[0].height, 12);
});

test('shared selection geometry uses the active reader writing direction when available', () => {
    const { selection, window } = loadSelection('猫');
    window.hoshiReader = { isVertical: () => true };

    assert.equal(window.hoshiRubyGeometry.isVertical(), true);
    assert.equal(selection.isVertical(), true);
});

test('shared ruby geometry keeps vertical adjacent columns split when ruby edges barely overlap', () => {
    const { window } = loadSelection('誰');
    window.getComputedStyle = () => ({ writingMode: 'vertical-rl' });

    const merged = window.hoshiRubyGeometry.mergeInlineRects([
        { x: 100, y: 120, width: 20, height: 80 },
        { x: 80, y: 0, width: 21, height: 160 },
    ]);

    assert.equal(merged.length, 2);
    assert.equal(merged[0].x, 100);
    assert.equal(merged[0].height, 80);
    assert.equal(merged[1].x, 80);
    assert.equal(merged[1].height, 160);
});

test('shared ruby geometry uses reader vertical direction for single-character ruby rects', () => {
    const { window } = loadSelection('花');
    window.hoshiReader = { isVertical: () => true };

    const rect = (x, y, width, height) => ({
        x,
        y,
        width,
        height,
        left: x,
        top: y,
        right: x + width,
        bottom: y + height,
    });
    const previousRt = { getClientRects: () => [rect(171, 434, 14, 21)] };
    const targetRt = { getClientRects: () => [rect(171, 455, 14, 21)] };
    const nextRt = { getClientRects: () => [rect(171, 476, 14, 21)] };
    const ruby = {
        querySelectorAll(selector) {
            return selector === 'rt' ? [previousRt, targetRt, nextRt] : [];
        },
    };
    const baseNode = {
        nodeType: 3,
        parentElement: {
            closest(selector) {
                return selector === 'ruby' ? ruby : null;
            },
        },
    };

    const rubyAware = window.hoshiRubyGeometry.rubyAwareRect(rect(148, 455, 30, 21), baseNode);

    assert.equal(rubyAware.x, 148);
    assert.equal(rubyAware.y, 455);
    assert.equal(rubyAware.width, 37);
    assert.equal(rubyAware.height, 21);
});

test('shared ruby geometry does not cascade a vertical single-character base rect into adjacent ruby text', () => {
    const { window } = loadSelection('花');
    window.getComputedStyle = () => ({ writingMode: 'vertical-rl' });

    const rect = (x, y, width, height) => ({
        x,
        y,
        width,
        height,
        left: x,
        top: y,
        right: x + width,
        bottom: y + height,
    });
    const previousRt = { getClientRects: () => [rect(171, 434, 14, 21)] };
    const targetRt = { getClientRects: () => [rect(171, 455, 14, 21)] };
    const nextRt = { getClientRects: () => [rect(171, 476, 14, 21)] };
    const ruby = {
        querySelectorAll(selector) {
            return selector === 'rt' ? [previousRt, targetRt, nextRt] : [];
        },
    };
    const baseNode = {
        nodeType: 3,
        parentElement: {
            closest(selector) {
                return selector === 'ruby' ? ruby : null;
            },
        },
    };

    const rubyAware = window.hoshiRubyGeometry.rubyAwareRect(rect(148, 455, 30, 21), baseNode);

    assert.equal(rubyAware.x, 148);
    assert.equal(rubyAware.y, 455);
    assert.equal(rubyAware.width, 37);
    assert.equal(rubyAware.height, 21);
});

test('shared selection clips vertical ruby annotation rects to partial base selections', () => {
    const { document, selection, textNode, window } = loadSelection('小型誘導弾');
    window.getComputedStyle = () => ({ writingMode: 'vertical-rl' });
    selection.configure({ rubyAwareRects: true });

    const rect = (x, y, width, height) => ({
        x,
        y,
        width,
        height,
        left: x,
        top: y,
        right: x + width,
        bottom: y + height,
    });
    const ruby = {
        querySelectorAll(selector) {
            return selector === 'rt' ? [{ getClientRects: () => [rect(130, 0, 10, 100)] }] : [];
        },
    };
    textNode.parentElement = {
        closest(selector) {
            return selector === 'ruby' ? ruby : null;
        },
    };
    document.createRange = () => ({
        startContainer: null,
        startOffset: 0,
        endOffset: 0,
        setStart(node, offset) {
            this.startContainer = node;
            this.startOffset = offset;
        },
        setEnd(_node, offset) {
            this.endOffset = offset;
        },
        getClientRects() {
            return [rect(100, this.startOffset * 20, 30, (this.endOffset - this.startOffset) * 20)];
        },
        getBoundingClientRect() {
            return rect(100, this.startOffset * 20, 30, (this.endOffset - this.startOffset) * 20);
        },
    });
    selection.selection = {
        ranges: [{ node: textNode, start: 2, end: 5 }],
        text: '誘導弾',
    };

    const rects = selection.selectionRects(3);

    assert.equal(
        JSON.stringify(rects),
        JSON.stringify([{ x: 100, y: 40, width: 40, height: 60 }]),
    );
});

test('shared selection keeps vertical lookup rects split when adjacent ruby-aware columns barely overlap', () => {
    const { selection, window } = loadSelection('信憑性がある');
    window.getComputedStyle = () => ({ writingMode: 'vertical-rl' });

    const rects = selection.unifyVerticalColumnRects([
        { x: 294.9444580078125, y: 730.4166870117188, width: 39.11110782623291, height: 51.70001220703125 },
        { x: 256.4444580078125, y: 4.472222328186035, width: 39.11110782623291, height: 22 },
        { x: 256.4444580078125, y: 26.47222328186035, width: 32, height: 22.000001907348633 },
        { x: 256.4444580078125, y: 48.472225189208984, width: 32, height: 21.999996185302734 },
        { x: 256.4444580078125, y: 70.47222137451172, width: 32, height: 22 },
    ]);

    assert.deepEqual(
        rects.map((rect) => [rect.x, rect.width]),
        [
            [294.9444580078125, 39.11110782623291],
            [256.4444580078125, 39.11110782623291],
            [256.4444580078125, 39.11110782623291],
            [256.4444580078125, 39.11110782623291],
            [256.4444580078125, 39.11110782623291],
        ],
    );
});


function rubyGroupFixture() {
    const ruby = () => {
        const classes = new Set(['furigana-hidden']);
        return {
            nodeType: 1, localName: 'ruby',
            classList: { add: (name) => classes.add(name), remove: (name) => classes.delete(name), contains: (name) => classes.has(name) },
            querySelector: () => ({}),
        };
    };
    const nodes = [ruby(), { nodeType: 3, nodeValue: ' \n\t' }, ruby(), ruby(), { nodeType: 3, nodeValue: '。' }, ruby()];
    nodes.forEach((node, index) => {
        node.previousSibling = nodes[index - 1] || null;
        node.nextSibling = nodes[index + 1] || null;
        if (node.localName === 'ruby') node.closest = (selector) => selector === 'ruby.furigana-hidden' && node.classList.contains('furigana-hidden') ? node : null;
    });
    return nodes;
}

test('hidden ruby tap reveals whitespace-adjacent group and consumes tap before lookup', () => {
    const { document, selection, window } = loadSelection('日本語。');
    window.getSelection = () => null;
    const nodes = rubyGroupFixture();
    document.pointElement = nodes[2];
    selection.selection = { startNode: {}, ranges: [] };
    selection.getCharacterAtPoint = () => { throw new Error('reveal tap must not perform lookup'); };
    assert.equal(selection.selectText(10, 10, 16), 'furigana');
    assert.equal(selection.selection, null);
    for (const index of [0, 2, 3]) assert.equal(nodes[index].classList.contains('furigana-hidden'), false);
    assert.equal(nodes[5].classList.contains('furigana-hidden'), true);
    selection.getCharacterAtPoint = () => null;
    assert.equal(selection.selectText(10, 10, 16), null);
});

test('Toggle setup marks only annotated ruby and other modes leave reveal markers absent', () => {
    const { selection } = loadSelection('日本語。');
    const nodes = rubyGroupFixture().filter((node) => node.localName === 'ruby');
    nodes.forEach((node) => node.classList.remove('furigana-hidden'));
    nodes[1].querySelector = () => null;
    const scope = { querySelectorAll: () => nodes };
    selection.setupFurigana('Toggle', scope);
    assert.equal(nodes[0].classList.contains('furigana-hidden'), true);
    assert.equal(nodes[1].classList.contains('furigana-hidden'), false);
    for (const mode of ['Off', 'Dimmed', 'Hidden']) {
        nodes.forEach((node) => node.classList.remove('furigana-hidden'));
        selection.setupFurigana(mode, scope);
        assert.equal(nodes.some((node) => node.classList.contains('furigana-hidden')), false);
    }
});


test('VN reveal also updates source ruby group so clones retain reveal on return', () => {
    const { document, selection, window } = loadSelection('日本語。');
    window.getSelection = () => null;
    const clones = rubyGroupFixture();
    const sources = rubyGroupFixture();
    selection.configure({ textProjection: {
        sourceRubyForRenderedRuby: (ruby) => sources[clones.indexOf(ruby)],
    } });
    document.pointElement = clones[2];
    assert.equal(selection.selectText(10, 10, 16), 'furigana');
    for (const index of [0, 2, 3]) {
        assert.equal(clones[index].classList.contains('furigana-hidden'), false);
        assert.equal(sources[index].classList.contains('furigana-hidden'), false);
    }
    assert.equal(sources[5].classList.contains('furigana-hidden'), true);
});

test('ruby reveal stops at styled siblings and non-ASCII whitespace as in iOS', () => {
    const { selection } = loadSelection('日本語。');
    for (const separator of [{ nodeType: 1, localName: 'span' }, { nodeType: 3, nodeValue: '\u00a0' }]) {
        const nodes = rubyGroupFixture();
        nodes[0].nextSibling = separator;
        separator.previousSibling = nodes[0];
        separator.nextSibling = nodes[2];
        nodes[2].previousSibling = separator;
        selection.revealFurigana(nodes[2]);
        assert.equal(nodes[0].classList.contains('furigana-hidden'), true);
        assert.equal(nodes[2].classList.contains('furigana-hidden'), false);
    }
});

test('native pronunciation selection uses full source sentence and excludes ruby annotations', () => {
    const { selection, textNode, window } = loadSelection('東京へ行きました。');
    const source = { node: textNode, offset: 0 };
    selection.configure({ textProjection: {
        toSemanticHit: () => source,
        normalizedOffsetForHit: () => 42,
    }});
    window.getSelection = () => ({ rangeCount: 1, getRangeAt: () => ({
        collapsed: false, startContainer: textNode, startOffset: 0,
        endContainer: textNode, endOffset: 2, commonAncestorContainer: textNode,
        comparePoint: () => 0, intersectsNode: node => node === textNode,
        getBoundingClientRect: () => ({ x: 0, y: 0, width: 10, height: 20 }),
    }) });
    const result = selection.nativeSelectionContext();
    assert.equal(result.text, '東京');
    assert.equal(result.sentence, '東京へ行きました。');
    assert.equal(result.normalizedOffset, 42);
});


test('native selection locks the reader before notifying Android, independent of menu visibility', () => {
    const { selection, window, changeNativeSelection } = loadSelection('本文');
    const events = [];
    selection.configure({ bridge: 'android-reader', nativeSelectionToken: 'chapter-a' });
    window.hoshiReader = { setNativeSelectionActive: active => events.push(['lock', active]) };
    window.HoshiNativeSelection = { postMessage: (active, token) => events.push(['android', active, token]) };
    changeNativeSelection({ isCollapsed: false });
    changeNativeSelection({ isCollapsed: false });
    changeNativeSelection({ isCollapsed: true });
    assert.deepEqual(events, [
        ['lock', true], ['android', true, 'chapter-a'],
        ['lock', false], ['android', false, 'chapter-a'],
    ]);
});

test('selection notifications preserve the WebKit bridge outside the Android reader', () => {
    const { window, changeNativeSelection } = loadSelection('本文');
    const events = [];
    window.webkit = { messageHandlers: { selectionState: { postMessage: active => events.push(active) } } };
    window.HoshiNativeSelection = { postMessage: () => assert.fail('Popup selection must not lock the reader') };
    changeNativeSelection({ isCollapsed: false });
    changeNativeSelection(null);
    assert.deepEqual(events, [true, false]);
});


test('native menu anchor includes only visible selection fragments without changing selection', () => {
    const { selection, window } = loadSelection('読書');
    let rects = [
        { left: 30, top: -200, right: 80, bottom: -100 },
        { left: 420, top: 50, right: 450, bottom: 60 },
        { left: 280, top: 590, right: 320, bottom: 640 },
        { left: 280, top: 640, right: 320, bottom: 830 },
    ];
    const native = {
        isCollapsed: false, rangeCount: 1, toString: () => '読書',
        getRangeAt: () => ({
            getClientRects: () => rects,
            cloneRange: () => ({
                collapse(start) { this.start = start; },
                getBoundingClientRect() { return this.start
                    ? {left:30,top:-200,right:80,bottom:-200}
                    : {left:280,top:830,right:320,bottom:830}; },
            }),
        }),
    };
    window.getSelection = () => native;
    assert.deepEqual(JSON.parse(JSON.stringify(selection.nativeSelectionMenuRect())),
        { left: 280, top: 590, right: 320, bottom: 800, viewportWidth: 400 });
    rects = rects.slice(0, 2);
    assert.equal(selection.nativeSelectionMenuRect(), null);
    native.isCollapsed = true;
    native.getRangeAt = () => assert.fail('Collapsed selection needs no geometry read');
    assert.equal(selection.nativeSelectionMenuRect(), null);
    native.isCollapsed = false;
    native.toString = () => '';
    assert.equal(selection.nativeSelectionMenuRect(), null);
});

test('native menu fallback uses current visible carets instead of intervening columns', () => {
    const {selection,window} = loadSelection('本文。');
    let endpoints = [{left:300,top:22,right:332,bottom:22},{left:220,top:44,right:252,bottom:44}];
    const range = {
        getClientRects:()=>[{left:220,top:0,right:332,bottom:748}],
        cloneRange:()=>({
            collapse(start) { this.start = start; },
            getBoundingClientRect() { return endpoints[this.start?0:1]; },
        }),
    };
    window.getSelection = () => ({isCollapsed:false,rangeCount:1,toString:()=> '本文。',getRangeAt:()=>range});
    const bounds = () => JSON.parse(JSON.stringify(selection.nativeSelectionMenuRect()));
    assert.deepEqual(bounds(),{left:220,top:22,right:332,bottom:45,viewportWidth:400},
        'A period in a new vertical column keeps the menu near both handles');
    endpoints = [{left:22,top:300,right:22,bottom:332},{left:44,top:220,right:44,bottom:252}];
    assert.deepEqual(bounds(),{left:22,top:220,right:45,bottom:332,viewportWidth:400},
        'Horizontal carets also form a nonempty anchor');
    endpoints = [{left:300,top:-22,right:332,bottom:-22},{left:220,top:44,right:252,bottom:44}];
    assert.deepEqual(bounds(),{left:220,top:44,right:252,bottom:45,viewportWidth:400},
        'Only visible carets contribute to the anchor');
    endpoints = [{left:0,top:0,right:0,bottom:0},{left:0,top:0,right:0,bottom:0}];
    assert.deepEqual(bounds(),{left:220,top:0,right:332,bottom:748,viewportWidth:400},
        'Element boundaries without caret geometry still use visible fragments');
});


test('native menu measurement is invalidated once by selection or scroll without reading layout', () => {
    const { selection, window, changeNativeSelection, dispatchDocumentEvent } = loadSelection('読書');
    const events = [];
    let reads = 0;
    const native = {
        isCollapsed: false, rangeCount: 1, toString: () => '読書',
        getRangeAt: () => ({
            getClientRects: () => { reads++; return [{ left: 20, top: 50, right: 40, bottom: 90 }]; },
            cloneRange: () => ({
                collapse() {},
                getBoundingClientRect() { reads++; return {left:0,top:0,right:0,bottom:0}; },
            }),
        }),
    };
    window.getSelection = () => native;
    selection.configure({ bridge: 'android-reader', nativeSelectionToken: 'chapter-a' });
    window.HoshiNativeSelection = {
        postMessage() {},
        invalidateMenuAnchor: (id, token) => events.push([id, token]),
    };
    selection.nativeSelectionMenuRect(1);
    const measuredReads = reads;
    changeNativeSelection(native);
    changeNativeSelection(native);
    dispatchDocumentEvent('scroll');
    assert.deepEqual(events, [[1, 'chapter-a']]);
    assert.equal(reads, measuredReads, 'Invalidation never reads layout');
    selection.nativeSelectionMenuRect(2);
    const remeasuredReads = reads;
    assert.ok(remeasuredReads > measuredReads, 'A new menu measures its current geometry');
    dispatchDocumentEvent('scroll');
    dispatchDocumentEvent('scroll');
    assert.deepEqual(events, [[1, 'chapter-a'], [2, 'chapter-a']]);
    assert.equal(reads, remeasuredReads);
});


test('invalid touch menu requests recover once at a visible selected character', () => {
    const { selection, window, document, dispatchDocumentEvent, changeNativeSelection } = loadSelection('読書');
    const calls = [];
    const invalidations = [];
    const textNode = { nodeType: 3 };
    const range = {
        getClientRects: () => [{left:20,top:40,right:40,bottom:80}],
        cloneRange: () => ({
            collapse(start) { this.start = start; },
            getBoundingClientRect() { return {left:20,right:40,top:this.start?40:80,bottom:this.start?40:80}; },
        }),
        comparePoint: (node) => node === textNode ? 0 : 1,
    };
    const native = { isCollapsed:false,rangeCount:1,toString:()=> '読書',getRangeAt:()=>range };
    window.getSelection = () => native;
    document.caretRangeFromPoint = () => ({startContainer:textNode,startOffset:0});
    selection.configure({bridge:'android-reader',nativeSelectionToken:'chapter'});
    window.HoshiNativeSelection = {
        postMessage(){}, recoverMenu:(id,token)=>calls.push([id,token]),
        invalidateMenuAnchor:(id,token)=>invalidations.push([id,token]),
    };
    const event = (overrides={}) => ({isTrusted:true,pointerType:'touch',button:0,clientX:30,clientY:-400,preventDefault(){this.prevented=true;},...overrides});
    dispatchDocumentEvent('contextmenu', event({clientY:50}));
    dispatchDocumentEvent('contextmenu', event({isTrusted:false}));
    dispatchDocumentEvent('contextmenu', event({button:2}));
    dispatchDocumentEvent('contextmenu', event({pointerType:'mouse',button:2}));
    assert.equal(calls.length,0,'Normal, script, and secondary-button menus are not replayed');
    // Chromium reports touch-handle adjustment as a mouse pointer with no changed button.
    const broken = event({pointerType:'mouse',button:-1});
    dispatchDocumentEvent('contextmenu',broken);
    assert.equal(broken.prevented,true);
    assert.equal(calls.length,1);
    assert.equal(calls[0][1],'chapter');
    assert.equal(selection.takeNativeMenuRecovery(calls[0][0]+1),null);
    assert.deepEqual(JSON.parse(JSON.stringify(selection.takeNativeMenuRecovery(calls[0][0], 42))),
        {x:30,y:60,viewportWidth:400});
    assert.equal(selection.takeNativeMenuRecovery(calls[0][0]),null,'A request can be consumed only once');
    const replayed = event({pointerType:'mouse',button:-1});
    dispatchDocumentEvent('contextmenu', replayed);
    assert.notEqual(replayed.prevented,true,'Let the recovery gesture open its native menu even if Chromium repeats bad coordinates');
    const replayedAgain = event({pointerType:'mouse',button:-1});
    dispatchDocumentEvent('contextmenu', replayedAgain);
    assert.notEqual(replayedAgain.prevented,true,'Repeated callbacks remain native until a new interaction or invalidation');
    assert.equal(calls.length,1,'A recovery gesture cannot recursively recover itself');
    changeNativeSelection(native);
    assert.deepEqual(invalidations, [[42, 'chapter']], 'Moving after consumption invalidates the pending native reply');
    dispatchDocumentEvent('contextmenu',event({pointerType:'',button:-1}));
    changeNativeSelection(native);
    assert.equal(selection.takeNativeMenuRecovery(calls.at(-1)[0]),null,'Moving the selection cancels recovery');
    dispatchDocumentEvent('contextmenu',event());
    dispatchDocumentEvent('scroll');
    assert.equal(selection.takeNativeMenuRecovery(calls.at(-1)[0]),null,'Scrolling cancels recovery');
    const precedingText = {nodeType:3};
    range.comparePoint = (node) => node === precedingText ? -1 : node === textNode ? 0 : 1;
    document.caretRangeFromPoint = (x) => ({startContainer:x === 100 ? precedingText : textNode,startOffset:0});
    const misplaced = event({clientX:100,clientY:50});
    dispatchDocumentEvent('contextmenu', misplaced);
    assert.equal(misplaced.prevented,true,'Visible coordinates outside the selected text also need recovery');
    assert.deepEqual(JSON.parse(JSON.stringify(selection.takeNativeMenuRecovery(calls.at(-1)[0], 43))),
        {x:30,y:60,viewportWidth:400});
});

for (const {name, target, rangePosition} of [
    {name:'unselected body text',target:{nodeType:3},rangePosition:1},
    {name:'ruby annotations',target:{nodeType:3,parentElement:{closest:()=>({})}},rangePosition:0},
]) {
    test(`native menu recovery rejects ${name}`, () => {
        const {selection,window,document,dispatchDocumentEvent} = loadSelection('読書');
        const textNode = {nodeType:3};
        const range = {
            getClientRects:()=>[{left:20,top:40,right:40,bottom:80}],
            comparePoint:node=>node===textNode?0:rangePosition,
            cloneRange:()=>({
                collapse(start) { this.start = start; },
                getBoundingClientRect() { return {left:20,right:40,top:this.start?40:80,bottom:this.start?40:80}; },
            }),
        };
        window.getSelection = () => ({isCollapsed:false,rangeCount:1,toString:()=> '読書',getRangeAt:()=>range});
        document.caretRangeFromPoint = () => ({startContainer:target,startOffset:0});
        selection.configure({bridge:'android-reader',nativeSelectionToken:'chapter'});
        const calls = [];
        window.HoshiNativeSelection = {recoverMenu:id=>calls.push(id)};
        const request = () => {
            const event = {isTrusted:true,button:-1,clientX:-1,clientY:-1,preventDefault(){this.prevented=true;}};
            dispatchDocumentEvent('contextmenu',event);
            return event;
        };
        assert.notEqual(request().prevented,true,'An invalid recovery target leaves the native event untouched');
        assert.equal(calls.length,0,'An invalid recovery target cannot start a replay');
        // A selected body target must still recover in the same fixture: the
        // negative result cannot come from a replay guard or another early exit.
        document.caretRangeFromPoint = () => ({startContainer:textNode,startOffset:0});
        assert.equal(request().prevented,true,'A selected body target allows recovery');
        assert.equal(calls.length,1);
    });
}

test('cancelled native replays cannot suppress a later recovery and stale cancellations are ignored', () => {
    const { selection, window, document, dispatchDocumentEvent } = loadSelection('読書');
    const node = {nodeType:3};
    const range = {
        getClientRects:()=>[{left:20,top:40,right:40,bottom:80}], comparePoint:()=>0,
        cloneRange:()=>({
            collapse(start) { this.start = start; },
            getBoundingClientRect() { return {left:20,right:40,top:this.start?40:80,bottom:this.start?40:80}; },
        }),
    };
    window.getSelection = () => ({isCollapsed:false,rangeCount:1,toString:()=> '読書',getRangeAt:()=>range});
    document.caretRangeFromPoint = () => ({startContainer:node,startOffset:0});
    selection.configure({bridge:'android-reader',nativeSelectionToken:'chapter'});
    const calls = [];
    window.HoshiNativeSelection = {recoverMenu:id=>calls.push(id)};
    const request = () => {
        const event = {isTrusted:true,pointerType:'mouse',button:-1,clientX:30,clientY:-400,
            preventDefault(){this.prevented=true;}};
        dispatchDocumentEvent('contextmenu',event);
        return event;
    };
    request();
    const cancelled = calls.at(-1);
    selection.takeNativeMenuRecovery(cancelled, 1);
    selection.cancelNativeMenuRecoveryReplay(cancelled, 'chapter');
    assert.equal(request().prevented,true,'A new recovery is allowed after the previous gesture is cancelled');
    const current = calls.at(-1);
    selection.takeNativeMenuRecovery(current, 2);
    selection.cancelNativeMenuRecoveryReplay(cancelled, 'chapter');
    selection.cancelNativeMenuRecoveryReplay(current, 'old-document');
    assert.notEqual(request().prevented,true,'Stale cancellation cannot re-enable recursion for the active replay');
});

test('menu recovery targets selected text away from both handles regardless of text-node fragmentation', () => {
    for (const vertical of [true, false]) for (const fragmented of [true, false]) {
        const { selection, window, document, dispatchDocumentEvent } = loadSelection('読書の時間です');
        const node = {nodeType:3};
        const rect = (from, to) => vertical
            ? {left:100,right:120,top:from,bottom:to}
            : {left:from,right:to,top:100,bottom:120};
        const range = {
            getClientRects: () => fragmented
                ? [rect(40,60),rect(60,80),rect(80,100),rect(100,120),rect(120,140)]
                : [rect(40,140)],
            comparePoint: candidate => candidate === node ? 0 : 1,
            cloneRange: () => ({
                collapse(start) { this.start = start; },
                getBoundingClientRect() { return rect(this.start?40:140,this.start?40:140); },
            }),
        };
        window.getSelection = () => ({isCollapsed:false,rangeCount:1,toString:()=> '読書の時間です',getRangeAt:()=>range});
        document.caretRangeFromPoint = (x,y) => {
            const along = vertical ? y : x, across = vertical ? x : y;
            return across > 100 && across < 120 && along > 40 && along < 140
                ? {startContainer:node,startOffset:2} : null;
        };
        selection.configure({bridge:'android-reader',nativeSelectionToken:'chapter'});
        let point;
        window.HoshiNativeSelection = {recoverMenu:id=>{point=selection.takeNativeMenuRecovery(id,1);}};
        dispatchDocumentEvent('contextmenu',{isTrusted:true,button:-1,pointerType:'mouse',clientX:-1,clientY:-1,preventDefault(){}});
        assert.ok(point, 'A visible selected body point is available');
        const along = vertical ? point.y : point.x;
        assert.ok(along >= 80 && along <= 100,
            `Recovery must reach the selection interior instead of pressing an endpoint handle: ${JSON.stringify({vertical,fragmented,point})}`);
    }
});
