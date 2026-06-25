import assert from 'node:assert/strict';
import fs from 'node:fs';
import test from 'node:test';
import vm from 'node:vm';

const readerContentStreamUrl = new URL('../../main/assets/hoshi-web/reader/reader-content-stream.js', import.meta.url);

class TestNode {
    constructor(nodeType) {
        this.nodeType = nodeType;
        this.parentNode = null;
    }

    get parentElement() {
        return this.parentNode?.nodeType === 1 ? this.parentNode : null;
    }
}

class TestText extends TestNode {
    constructor(value) {
        super(3);
        this.nodeValue = value;
    }

    get textContent() {
        return this.nodeValue;
    }
}

class TestElement extends TestNode {
    constructor(tagName, attributes = {}) {
        super(1);
        this.tagName = tagName.toUpperCase();
        this.childNodes = [];
        this.attributes = new Map();
        Object.entries(attributes).forEach(([key, value]) => this.setAttribute(key, value));
    }

    appendChild(child) {
        child.parentNode?.removeChild(child);
        child.parentNode = this;
        this.childNodes.push(child);
        return child;
    }

    removeChild(child) {
        const index = this.childNodes.indexOf(child);
        if (index >= 0) {
            this.childNodes.splice(index, 1);
            child.parentNode = null;
        }
        return child;
    }

    getAttribute(name) {
        return this.attributes.get(name) ?? null;
    }

    setAttribute(name, value) {
        this.attributes.set(name, String(value));
    }

    get textContent() {
        return this.childNodes.map((child) => child.textContent).join('');
    }
}

function el(tagName, attributes = {}, children = []) {
    const node = new TestElement(tagName, attributes);
    children.forEach((child) => node.appendChild(typeof child === 'string' ? text(child) : child));
    return node;
}

function text(value) {
    return new TestText(value);
}

function loadContentStreamModule() {
    const source = fs.readFileSync(readerContentStreamUrl, 'utf8');
    const window = {};
    vm.runInNewContext(source, { window });
    return window.hoshiReaderContentStream;
}

function plain(value) {
    return JSON.parse(JSON.stringify(value));
}

test('content stream indexes raw and matchable chapter offsets while ignoring ruby annotations', () => {
    const base = text('古');
    const punctuation = text('。');
    const tail = text('都');
    const ruby = el('ruby', {}, [
        base,
        el('rt', {}, ['ふる']),
    ]);
    const paragraph = el('p', { id: 'line' }, [
        '始',
        ruby,
        punctuation,
        el('span', { name: 'tail' }, [tail]),
        el('script', {}, ['無視']),
    ]);
    const stream = loadContentStreamModule().create(paragraph);

    assert.equal(stream.totalMatchableChars, 3);
    assert.equal(stream.totalRawChars, 4);
    assert.deepEqual(
        plain(
            stream.textEntries.map((entry) => ({
                text: entry.text,
                startChar: entry.startChar,
                endChar: entry.endChar,
                startRaw: entry.startRaw,
                endRaw: entry.endRaw,
            })),
        ),
        [
            { text: '始', startChar: 0, endChar: 1, startRaw: 0, endRaw: 1 },
            { text: '古', startChar: 1, endChar: 2, startRaw: 1, endRaw: 2 },
            { text: '。', startChar: 2, endChar: 2, startRaw: 2, endRaw: 3 },
            { text: '都', startChar: 2, endChar: 3, startRaw: 3, endRaw: 4 },
        ],
    );

    assert.deepEqual(
        plain(
            stream.textItems().map((item) => ({
                char: item.char,
                chapterCharStart: item.chapterCharStart,
                chapterCharEnd: item.chapterCharEnd,
                chapterRawStart: item.chapterRawStart,
                chapterRawEnd: item.chapterRawEnd,
            })),
        ),
        [
            { char: '始', chapterCharStart: 0, chapterCharEnd: 1, chapterRawStart: 0, chapterRawEnd: 1 },
            { char: '古', chapterCharStart: 1, chapterCharEnd: 2, chapterRawStart: 1, chapterRawEnd: 2 },
            { char: '。', chapterCharStart: 2, chapterCharEnd: 2, chapterRawStart: 2, chapterRawEnd: 3 },
            { char: '都', chapterCharStart: 2, chapterCharEnd: 3, chapterRawStart: 3, chapterRawEnd: 4 },
        ],
    );

    assert.deepEqual([...stream.idsForTextNode(tail)].sort(), ['line', 'tail']);
    assert.equal(stream.rubyRootForTextNode(base), ruby);
    assert.equal(stream.rubyRootForTextNode(tail), null);
    assert.equal(stream.rubyRoots().length, 1);
    assert.equal(stream.rubyRoots()[0], ruby);
    assert.equal(stream.textItems().find((item) => item.char === '古').rubyRoot, ruby);
    assert.deepEqual(plain(stream.statsForNode(paragraph)), {
        hasText: true,
        startChar: 0,
        endChar: 3,
        startRaw: 0,
        endRaw: 4,
    });
});

test('content stream records standalone media units in source order', () => {
    const cover = el('img', { id: 'cover', src: 'cover.jpg' });
    const svgImage = el('image', { href: 'plate.jpg' });
    const svg = el('svg', { id: 'plate' }, [svgImage]);
    const root = el('section', {}, [
        el('p', {}, ['前']),
        cover,
        svg,
        el('p', {}, ['後']),
    ]);

    const stream = loadContentStreamModule().create(root);

    assert.deepEqual(
        plain(
            stream.mediaUnits().map((unit) => ({
                tagName: unit.tagName,
                mediaTagName: unit.mediaTagName,
                renderRootTagName: unit.renderRootTagName,
                sourceOrder: unit.sourceOrder,
                preorder: unit.preorder,
                startChar: unit.startChar,
                endChar: unit.endChar,
                startRaw: unit.startRaw,
                endRaw: unit.endRaw,
                ids: [...unit.ids].sort(),
            })),
        ),
        [
            {
                tagName: 'img',
                mediaTagName: 'img',
                renderRootTagName: 'img',
                sourceOrder: 1,
                preorder: 3,
                startChar: 1,
                endChar: 1,
                startRaw: 1,
                endRaw: 1,
                ids: ['cover'],
            },
            {
                tagName: 'svg',
                mediaTagName: 'svg',
                renderRootTagName: 'svg',
                sourceOrder: 2,
                preorder: 4,
                startChar: 1,
                endChar: 1,
                startRaw: 1,
                endRaw: 1,
                ids: ['plate'],
            },
        ],
    );
});

test('content stream keeps figure media as the render root', () => {
    const image = el('img', { id: 'inner', src: 'plate.jpg' });
    const figure = el('figure', { id: 'fig' }, [image]);
    const root = el('section', {}, [
        el('p', {}, ['前']),
        figure,
        el('p', {}, ['後']),
    ]);

    const stream = loadContentStreamModule().create(root);
    const units = stream.mediaUnits();

    assert.equal(units.length, 1);
    assert.equal(units[0].mediaNode, figure);
    assert.equal(units[0].renderRoot, figure);
    assert.deepEqual(
        plain({
            tagName: units[0].tagName,
            mediaTagName: units[0].mediaTagName,
            renderRootTagName: units[0].renderRootTagName,
            sourceOrder: units[0].sourceOrder,
            preorder: units[0].preorder,
            startChar: units[0].startChar,
            endChar: units[0].endChar,
            startRaw: units[0].startRaw,
            endRaw: units[0].endRaw,
            ids: [...units[0].ids].sort(),
        }),
        {
            tagName: 'figure',
            mediaTagName: 'figure',
            renderRootTagName: 'figure',
            sourceOrder: 1,
            preorder: 3,
            startChar: 1,
            endChar: 1,
            startRaw: 1,
            endRaw: 1,
            ids: ['fig', 'inner'],
        },
    );
});

test('content stream treats gaiji images as inline glyphs for media and offset indexing', () => {
    const gaiji = el('img', { class: 'gaiji', alt: '外字' });
    const paragraph = el('p', {}, ['前', gaiji, '後']);

    const stream = loadContentStreamModule().create(paragraph);

    assert.equal(stream.containsStandaloneMedia(paragraph), false);
    assert.equal(stream.mediaUnits().length, 0);
    assert.deepEqual(
        plain(
            stream.textEntries.map((entry) => ({
                text: entry.text,
                startChar: entry.startChar,
                endChar: entry.endChar,
                startRaw: entry.startRaw,
                endRaw: entry.endRaw,
            })),
        ),
        [
            { text: '前', startChar: 0, endChar: 1, startRaw: 0, endRaw: 1 },
            { text: '後', startChar: 1, endChar: 2, startRaw: 1, endRaw: 2 },
        ],
    );
});
