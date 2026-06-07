import assert from 'node:assert/strict';
import fs from 'node:fs';
import test from 'node:test';
import vm from 'node:vm';

const readerPaginatedUrl = new URL('../../main/assets/hoshi-web/reader/reader-paginated.js', import.meta.url);
const readerContinuousUrl = new URL('../../main/assets/hoshi-web/reader/reader-continuous.js', import.meta.url);

function readerSource(url) {
    return fs.readFileSync(url, 'utf8')
        .replace('__HOSHI_HIGHLIGHTS_SCRIPT__', '')
        .replaceAll('__HOSHI_RESTORE_TOKEN_LITERAL__', JSON.stringify('restore-token'))
        .replaceAll('__HOSHI_BOTTOM_OVERLAP_PX__', '0')
        .replaceAll('__HOSHI_VERTICAL_PADDING_BLOCK_RATIO__', '0')
        .replaceAll('__HOSHI_VERTICAL_PADDING_GAP_RATIO__', '0')
        .replaceAll('__HOSHI_IMAGE_WIDTH_VIEWPORT_RATIO__', '1')
        .replaceAll('__HOSHI_IMAGE_WIDTH_REDUCTION_PX__', '0')
        .replaceAll('__HOSHI_BLUR_IMAGES__', 'false')
        .replaceAll('__HOSHI_TRAILING_SPACER_HEIGHT_LITERAL__', JSON.stringify('0px'))
        .replaceAll('__HOSHI_TRAILING_SPACER_WIDTH_LITERAL__', JSON.stringify('0px'))
        .replaceAll('__HOSHI_RESTORE_SCRIPTS__', '');
}

function testStyle() {
    const values = new Map();
    return {
        setProperty(name, value) {
            values.set(name, value);
        },
        getPropertyValue(name) {
            return values.get(name) ?? '';
        },
    };
}

class TestNode {
    constructor(nodeType) {
        this.nodeType = nodeType;
        this.parentNode = null;
    }

    get parentElement() {
        return this.parentNode?.nodeType === 1 ? this.parentNode : null;
    }

    get previousSibling() {
        if (!this.parentNode) return null;
        const index = this.parentNode.childNodes.indexOf(this);
        return index > 0 ? this.parentNode.childNodes[index - 1] : null;
    }

    get nextSibling() {
        if (!this.parentNode) return null;
        const index = this.parentNode.childNodes.indexOf(this);
        return index >= 0 && index + 1 < this.parentNode.childNodes.length
            ? this.parentNode.childNodes[index + 1]
            : null;
    }

    get firstChild() {
        return this.childNodes?.[0] ?? null;
    }

    replaceWith(node) {
        if (!this.parentNode) return;
        const parent = this.parentNode;
        const index = parent.childNodes.indexOf(this);
        if (index < 0) return;
        parent.childNodes.splice(index, 1);
        this.parentNode = null;
        const replacements = node.nodeType === 11 ? [...node.childNodes] : [node];
        replacements.forEach((child, offset) => {
            child.parentNode = parent;
            parent.childNodes.splice(index + offset, 0, child);
        });
        if (node.nodeType === 11) node.childNodes = [];
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

    set textContent(value) {
        this.nodeValue = value;
    }
}

class TestElement extends TestNode {
    constructor(tagName) {
        super(1);
        this.tagName = tagName.toUpperCase();
        this.childNodes = [];
        this.style = testStyle();
        this.classList = {
            add() {},
            remove() {},
            contains() {
                return false;
            },
        };
    }

    appendChild(child) {
        child.parentNode?.removeChild(child);
        child.parentNode = this;
        this.childNodes.push(child);
        return child;
    }

    insertBefore(child, before) {
        child.parentNode?.removeChild(child);
        child.parentNode = this;
        const index = before ? this.childNodes.indexOf(before) : -1;
        if (index < 0) {
            this.childNodes.push(child);
        } else {
            this.childNodes.splice(index, 0, child);
        }
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

    remove() {
        this.parentNode?.removeChild(this);
    }

    addEventListener() {}

    getAttribute() {
        return null;
    }

    setAttribute() {}

    normalize() {
        const normalized = [];
        this.childNodes.forEach((child) => {
            if (child.nodeType === 1) child.normalize();
            const previous = normalized[normalized.length - 1];
            if (child.nodeType === 3 && previous?.nodeType === 3) {
                previous.nodeValue += child.nodeValue;
                child.parentNode = null;
            } else {
                normalized.push(child);
            }
        });
        this.childNodes = normalized;
    }

    closest(selector) {
        const selectors = selector.split(',').map((item) => item.trim().toUpperCase());
        let node = this;
        while (node) {
            if (node.nodeType === 1 && selectors.includes(node.tagName)) return node;
            node = node.parentNode;
        }
        return null;
    }

    querySelectorAll(selector) {
        if (selector !== 'ruby') return [];
        return queryRuby(this);
    }

    get textContent() {
        return this.childNodes.map((child) => child.textContent).join('');
    }
}

class TestFragment extends TestNode {
    constructor() {
        super(11);
        this.childNodes = [];
    }

    appendChild(child) {
        child.parentNode = this;
        this.childNodes.push(child);
        return child;
    }
}

class TestRange {
    constructor() {
        this.node = null;
    }

    selectNodeContents(node) {
        this.node = node;
    }

    setStart(node) {
        this.node = node;
    }

    setEnd() {}

    getClientRects() {
        return this.node?.rects ?? [];
    }

    getBoundingClientRect() {
        const rects = this.getClientRects();
        if (!rects.length) {
            return { left: 0, right: 0, top: 0, bottom: 0, width: 0, height: 0 };
        }
        const left = Math.min(...rects.map((rect) => rect.left));
        const right = Math.max(...rects.map((rect) => rect.right));
        const top = Math.min(...rects.map((rect) => rect.top));
        const bottom = Math.max(...rects.map((rect) => rect.bottom));
        return { left, right, top, bottom, width: right - left, height: bottom - top };
    }

    detach() {}
}

function testRect(top, bottom, left = 0, right = 20) {
    return { left, right, top, bottom, width: right - left, height: bottom - top };
}

function queryRuby(root) {
    const result = [];
    const visit = (node) => {
        if (node.nodeType === 1 && node.tagName === 'RUBY') result.push(node);
        if (node.childNodes) node.childNodes.forEach(visit);
    };
    visit(root);
    return result;
}

function loadReader(body, sourceUrl = readerPaginatedUrl, options = {}) {
    const head = new TestElement('head');
    const documentElement = new TestElement('html');
    documentElement.appendChild(head);
    documentElement.appendChild(body);
    const documentHead = Object.hasOwn(options, 'documentHead') ? options.documentHead : head;
    const document = {
        body,
        head: documentHead,
        documentElement,
        fonts: { ready: Promise.resolve() },
        readyState: 'loading',
        createDocumentFragment() {
            return new TestFragment();
        },
        createTextNode(text) {
            return new TestText(text);
        },
        createElement(tagName) {
            return new TestElement(tagName);
        },
        createRange() {
            return new TestRange();
        },
        createTreeWalker(root) {
            const nodes = [];
            const visit = (node) => {
                if (node.nodeType === 3) nodes.push(node);
                node.childNodes?.forEach(visit);
            };
            visit(root);
            let index = 0;
            return {
                nextNode() {
                    return nodes[index++] ?? null;
                },
            };
        },
        querySelector(selector) {
            if (selector !== 'meta[name="viewport"]') return null;
            return head.childNodes.find((node) => node.tagName === 'META' && node.name === 'viewport') ?? null;
        },
        querySelectorAll(selector) {
            return selector === 'ruby' ? queryRuby(body) : [];
        },
        getElementsByTagName(tagName) {
            return tagName.toLowerCase() === 'head' ? [head] : [];
        },
        addEventListener() {},
    };
    const window = {
        addEventListener() {},
        innerHeight: 800,
        innerWidth: 480,
        scrollX: 0,
        scrollY: 0,
        scrollTo() {},
        getComputedStyle() {
            return { writingMode: 'vertical-rl', getPropertyValue: () => '' };
        },
    };
    vm.runInNewContext(readerSource(sourceUrl), {
        CSS: { highlights: { delete() {}, set() {} } },
        document,
        Highlight: class {},
        Node: { ELEMENT_NODE: 1, TEXT_NODE: 3 },
        NodeFilter: { SHOW_TEXT: 4, FILTER_ACCEPT: 1, FILTER_REJECT: 2 },
        window,
    });
    return { reader: window.hoshiReader, document, head };
}

function rubyParagraph() {
    const paragraph = new TestElement('p');
    const ruby = new TestElement('ruby');
    ruby.appendChild(new TestText('歩'));
    const rt = new TestElement('rt');
    rt.appendChild(new TestText('あゆむ'));
    ruby.appendChild(rt);

    paragraph.appendChild(new TestText('進藤'));
    paragraph.appendChild(ruby);
    paragraph.appendChild(new TestText('。'));
    paragraph.appendChild(new TestText('そ'));
    paragraph.appendChild(new TestText('れ'));
    return { paragraph, ruby };
}

function rubyParagraphWithWhitespaceTextNodes() {
    const { paragraph, ruby } = rubyParagraph();
    ruby.insertBefore(new TestText('\n  '), ruby.firstChild);
    ruby.appendChild(new TestText(' \n'));
    return { paragraph, ruby };
}

function textRunAfter(node) {
    const values = [];
    let next = node.nextSibling;
    while (next?.nodeType === 3) {
        values.push(next.nodeValue);
        next = next.nextSibling;
    }
    return values;
}

function assertRubyTextNodesAreNormalized(sourceUrl) {
    const { paragraph, ruby } = rubyParagraphWithWhitespaceTextNodes();
    const { reader } = loadReader(paragraph, sourceUrl);

    assert.equal(typeof reader.normalizeReaderText, 'function');
    reader.normalizeReaderText(paragraph);

    assert.deepEqual(
        ruby.childNodes.map((node) => node.nodeType === 3 ? `text:${node.nodeValue}` : node.tagName),
        ['SPAN', 'RT'],
    );
    assert.equal(ruby.childNodes[0].textContent, '歩');
    assert.equal(ruby.textContent, '歩あゆむ');
}

test('paginated reader re-stabilizes ruby-adjacent text after unwrap normalizes siblings', () => {
    const { paragraph, ruby } = rubyParagraph();
    const wrapper = new TestElement('span');
    wrapper.appendChild(new TestText('追加'));
    paragraph.appendChild(wrapper);
    const { reader } = loadReader(paragraph);

    reader.unwrap([wrapper]);

    assert.deepEqual(textRunAfter(ruby).slice(0, 4), ['。', 'そ', 'れ', '追']);
});

test('paginated reader-specific text normalization keeps ruby-adjacent text stable', () => {
    const { paragraph, ruby } = rubyParagraph();
    paragraph.normalize();
    const { reader } = loadReader(paragraph);

    assert.equal(typeof reader.normalizeReaderText, 'function');
    reader.normalizeReaderText(paragraph);

    assert.deepEqual(textRunAfter(ruby).slice(0, 3), ['。', 'そ', 'れ']);
});

test('paginated reader removes ruby whitespace text nodes and wraps base text nodes', () => {
    assertRubyTextNodesAreNormalized(readerPaginatedUrl);
});

test('paginated restoreProgress at chapter start avoids eager pagination metrics', async () => {
    const body = new TestElement('body');
    const { reader } = loadReader(body);
    const scrolls = [];
    let builtMetrics = 0;
    let restored = 0;

    reader.getScrollContext = () => ({
        vertical: true,
        scrollEl: body,
        pageSize: 800,
        maxScroll: 4000,
    });
    reader.setPagePosition = (_context, position) => {
        scrolls.push(position);
        return position;
    };
    reader.registerSnapScroll = () => {};
    reader.refreshSasayakiCuePresentation = () => {};
    reader.notifyRestoreComplete = () => {
        restored += 1;
    };
    reader.buildPaginationMetrics = () => {
        builtMetrics += 1;
        return { minScroll: 800, maxScroll: 3200, totalChars: 1, progressStops: [] };
    };

    await reader.restoreProgress(0);

    assert.deepEqual(scrolls, [0]);
    assert.equal(restored, 1);
    assert.equal(builtMetrics, 0);
});

test('paginated content metrics include final partial page when real text reaches it', () => {
    const body = new TestElement('body');
    body.scrollTop = 0;
    body.scrollHeight = 4250;
    const intro = new TestText('始');
    intro.rects = [testRect(0, 40)];
    const tail = new TestText('終');
    tail.rects = [testRect(4000, 4100)];
    body.appendChild(intro);
    body.appendChild(tail);
    const { reader } = loadReader(body);
    reader.pageHeight = 800;
    reader.pageWidth = 480;

    const metrics = reader.buildPaginationMetrics();

    assert.equal(metrics.maxScroll, 3450);
});

test('paginated forward navigation reaches final partial page', () => {
    const body = new TestElement('body');
    body.scrollTop = 3200;
    const { reader } = loadReader(body);
    reader.getScrollContext = () => ({
        vertical: true,
        scrollEl: body,
        pageSize: 800,
        maxScroll: 3450,
    });
    reader.paginationMetrics = { minScroll: 0, maxScroll: 3450, totalChars: 1, progressStops: [] };
    reader.refreshSasayakiCuePresentation = () => {};

    const result = reader.paginate('forward');

    assert.equal(result, 'scrolled');
    assert.equal(body.scrollTop, 3450);
});

test('paginated backward navigation leaves final partial page by one page', () => {
    const body = new TestElement('body');
    body.scrollTop = 3450;
    const { reader } = loadReader(body);
    reader.getScrollContext = () => ({
        vertical: true,
        scrollEl: body,
        pageSize: 800,
        maxScroll: 3450,
    });
    reader.paginationMetrics = { minScroll: 0, maxScroll: 3450, totalChars: 1, progressStops: [] };
    reader.refreshSasayakiCuePresentation = () => {};

    const result = reader.paginate('backward');

    assert.equal(result, 'scrolled');
    assert.equal(body.scrollTop, 3200);
});

test('continuous reader stabilizes vertical ruby-adjacent text like paginated reader', () => {
    const { paragraph, ruby } = rubyParagraph();
    paragraph.normalize();
    const { reader } = loadReader(paragraph, readerContinuousUrl);

    assert.equal(typeof reader.stabilizeRubyAdjacentTextNodes, 'function');
    reader.stabilizeRubyAdjacentTextNodes();

    assert.deepEqual(textRunAfter(ruby).slice(0, 3), ['。', 'そ', 'れ']);
});

test('continuous reader-specific text normalization keeps ruby-adjacent text stable', () => {
    const { paragraph, ruby } = rubyParagraph();
    paragraph.normalize();
    const { reader } = loadReader(paragraph, readerContinuousUrl);

    assert.equal(typeof reader.normalizeReaderText, 'function');
    reader.normalizeReaderText(paragraph);

    assert.deepEqual(textRunAfter(ruby).slice(0, 3), ['。', 'そ', 'れ']);
});

test('continuous reader removes ruby whitespace text nodes and wraps base text nodes', () => {
    assertRubyTextNodesAreNormalized(readerContinuousUrl);
});

test('continuous reader re-stabilizes ruby-adjacent text after unwrap normalizes siblings', () => {
    const { paragraph, ruby } = rubyParagraph();
    const wrapper = new TestElement('span');
    wrapper.appendChild(new TestText('追加'));
    paragraph.appendChild(wrapper);
    const { reader } = loadReader(paragraph, readerContinuousUrl);

    reader.unwrap([wrapper]);

    assert.deepEqual(textRunAfter(ruby).slice(0, 4), ['。', 'そ', 'れ', '追']);
});

test('reader initialization requires XHTML document.head like iOS', () => {
    for (const sourceUrl of [readerPaginatedUrl, readerContinuousUrl]) {
        const body = new TestElement('body');
        const { reader, document } = loadReader(body, sourceUrl, { documentHead: null });

        assert.equal(document.head, null);

        assert.throws(() => reader.initialize(), /appendChild/);
    }
});
