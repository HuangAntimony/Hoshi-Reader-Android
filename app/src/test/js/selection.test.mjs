import assert from 'node:assert/strict';
import fs from 'node:fs';
import test from 'node:test';
import vm from 'node:vm';

const sharedSelectionUrl = new URL('../../main/assets/hoshi-web/shared/selection.js', import.meta.url);

function selectionSource() {
    return fs.readFileSync(sharedSelectionUrl, 'utf8');
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
        createTreeWalker() {
            return treeWalker([textNode]);
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
        selection: window.hoshiSelection,
        textNode,
        window,
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
