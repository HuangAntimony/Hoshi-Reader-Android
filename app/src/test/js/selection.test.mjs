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
        sharedSelectionUrl,
    ].map((url) => fs.readFileSync(url, 'utf8')).join('\n');
}

function loadSelection(text) {
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

    return {
        document,
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
