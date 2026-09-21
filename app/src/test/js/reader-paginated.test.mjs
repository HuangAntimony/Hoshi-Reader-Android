import assert from 'node:assert/strict';
import fs from 'node:fs';
import test from 'node:test';
import vm from 'node:vm';

const readerPaginatedUrl = new URL('../../main/assets/hoshi-web/reader/reader-paginated.js', import.meta.url);
const readerContinuousUrl = new URL('../../main/assets/hoshi-web/reader/reader-continuous.js', import.meta.url);
const readerViewportUrl = new URL('../../main/assets/hoshi-web/reader/reader-viewport.js', import.meta.url);
const readerSasayakiUrl = new URL('../../main/assets/hoshi-web/reader/reader-sasayaki.js', import.meta.url);
const readerTextSemanticsUrl = new URL('../../main/assets/hoshi-web/reader/reader-text-semantics.js', import.meta.url);
const readerDomTextUrl = new URL('../../main/assets/hoshi-web/reader/reader-dom-text.js', import.meta.url);
const readerMediaSemanticsUrl = new URL('../../main/assets/hoshi-web/reader/reader-media-semantics.js', import.meta.url);

function readerTextSemanticsSource() {
    return fs.readFileSync(readerTextSemanticsUrl, 'utf8');
}

function readerDomTextSource() {
    return fs.readFileSync(readerDomTextUrl, 'utf8');
}

function readerMediaSemanticsSource() {
    return fs.readFileSync(readerMediaSemanticsUrl, 'utf8');
}

function readerSource(url, options = {}) {
    const readerSasayaki = fs.readFileSync(readerSasayakiUrl, 'utf8');
    return fs.readFileSync(url, 'utf8')
        .replace('__HOSHI_HIGHLIGHTS_SCRIPT__', options.highlightsScript ?? '')
        .replace('__HOSHI_READER_VIEWPORT_SCRIPT__', fs.readFileSync(readerViewportUrl, 'utf8'))
        .replace('__HOSHI_READER_SASAYAKI_SCRIPT__', readerSasayaki)
        .replace('__HOSHI_READER_TEXT_SEMANTICS_SCRIPT__', options.textSemanticsScript ?? readerTextSemanticsSource())
        .replace('__HOSHI_READER_DOM_TEXT_SCRIPT__', options.domTextScript ?? readerDomTextSource())
        .replace('__HOSHI_READER_MEDIA_SEMANTICS_SCRIPT__', options.mediaSemanticsScript ?? readerMediaSemanticsSource())
        .replace(
            '__HOSHI_READER_LAYOUT_SEMANTICS_SCRIPT__',
            options.layoutSemanticsScript ?? 'window.hoshiReaderLayoutSemantics = { sanitizeInlineBlocks: function() {} };',
        )
        .replaceAll('__HOSHI_RESTORE_TOKEN_LITERAL__', JSON.stringify('restore-token'))
        .replaceAll('__HOSHI_VERTICAL_PADDING_BLOCK_RATIO__', '0')
        .replaceAll('__HOSHI_VERTICAL_PADDING_GAP_RATIO__', '0')
        .replaceAll('__HOSHI_IMAGE_WIDTH_VIEWPORT_RATIO__', '1')
        .replaceAll('__HOSHI_IMAGE_HEIGHT_VIEWPORT_RATIO__', '1')
        .replaceAll('__HOSHI_IMAGE_WIDTH_REDUCTION_PX__', '0')
        .replaceAll('__HOSHI_BLUR_IMAGES__', 'false')
        .replaceAll('__HOSHI_TRAILING_SPACER_HEIGHT_LITERAL__', JSON.stringify('0px'))
        .replaceAll('__HOSHI_TRAILING_SPACER_WIDTH_LITERAL__', JSON.stringify('0px'))
        .replaceAll('__HOSHI_RESTORE_SCRIPTS__', options.restoreScripts ?? '');
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
        this.className = '';
        this.style = testStyle();
        this.classList = {
            add: (...names) => {
                const classes = new Set(this.className.split(/\s+/).filter(Boolean));
                names.forEach((name) => classes.add(name));
                this.className = [...classes].join(' ');
            },
            remove: (...names) => {
                const remove = new Set(names);
                this.className = this.className
                    .split(/\s+/)
                    .filter((name) => name && !remove.has(name))
                    .join(' ');
            },
            contains: (name) => {
                return this.className.split(/\s+/).includes(name);
            },
        };
    }

    appendChild(child) {
        if (child.nodeType === 11) {
            [...child.childNodes].forEach((fragmentChild) => this.appendChild(fragmentChild));
            child.childNodes = [];
            return child;
        }
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

    scrollIntoView(options) {
        this.scrollIntoViewCalls = this.scrollIntoViewCalls ?? [];
        this.scrollIntoViewCalls.push(options ?? {});
    }

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

    matches(selector) {
        return selector.split(',').some((item) => item.trim().toUpperCase() === this.tagName);
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
        if (selector === 'ruby') return queryRuby(this);
        const tagSelector = queryTagSelectorList(selector);
        if (tagSelector) return queryByTags(this, tagSelector);
        return [];
    }

    getClientRects() {
        return [this.getBoundingClientRect()];
    }

    getBoundingClientRect() {
        return this.rect ?? { left: 0, right: 0, top: 0, bottom: 0, width: 0, height: 0 };
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

    removeChild(child) {
        const index = this.childNodes.indexOf(child);
        if (index >= 0) {
            this.childNodes.splice(index, 1);
            child.parentNode = null;
        }
        return child;
    }
}

class TestRange {
    constructor() {
        this.node = null;
        this.startNode = null;
        this.startOffset = 0;
        this.endNode = null;
        this.endOffset = 0;
        this.insertionParent = null;
        this.insertionIndex = null;
    }

    get startContainer() { return this.startNode; }
    get endContainer() { return this.endNode; }
    get collapsed() { return this.startNode === this.endNode && this.startOffset === this.endOffset; }

    cloneRange() {
        const range = new TestRange();
        range.setStart(this.startNode, this.startOffset);
        range.setEnd(this.endNode, this.endOffset);
        return range;
    }

    selectNodeContents(node) {
        this.node = node;
        this.startNode = node;
        this.startOffset = 0;
        this.endNode = node;
        this.endOffset = node.textContent?.length ?? 0;
    }

    setStart(node, offset = 0) {
        this.node = node;
        this.startNode = node;
        this.startOffset = offset;
    }

    setEnd(node, offset = node.textContent?.length ?? 0) {
        this.endNode = node;
        this.endOffset = offset;
    }

    extractContents() {
        const fragment = new TestFragment();
        if (this.startNode !== this.endNode || this.startNode?.nodeType !== 3) return fragment;
        const textNode = this.startNode;
        const parent = textNode.parentNode;
        if (!parent) return fragment;
        const index = parent.childNodes.indexOf(textNode);
        if (index < 0) return fragment;
        const value = textNode.nodeValue;
        const before = value.slice(0, this.startOffset);
        const selected = value.slice(this.startOffset, this.endOffset);
        const after = value.slice(this.endOffset);
        // DOM Range extraction retains the original start text node. Earlier
        // cue ranges can still reference its prefix when wrapping in reverse.
        textNode.nodeValue = before;
        const replacements = [textNode];
        if (after) replacements.push(new TestText(after));
        replacements.forEach((node) => {
            node.parentNode = parent;
        });
        parent.childNodes.splice(index, 1, ...replacements);
        this.insertionParent = parent;
        this.insertionIndex = index + 1;
        if (selected) fragment.appendChild(new TestText(selected));
        return fragment;
    }

    insertNode(node) {
        if (this.insertionParent && this.insertionIndex !== null) {
            node.parentNode?.removeChild(node);
            node.parentNode = this.insertionParent;
            this.insertionParent.childNodes.splice(this.insertionIndex, 0, node);
            this.insertionIndex += 1;
            return;
        }
        if (this.startNode?.nodeType !== 3) return;
        const textNode = this.startNode;
        const parent = textNode.parentNode;
        if (!parent) return;
        const index = parent.childNodes.indexOf(textNode);
        if (index < 0) return;
        const value = textNode.nodeValue;
        const before = value.slice(0, this.startOffset);
        const after = value.slice(this.startOffset);
        const replacements = [];
        if (before) replacements.push(new TestText(before));
        replacements.push(node);
        if (after) replacements.push(new TestText(after));
        replacements.forEach((replacement) => {
            replacement.parentNode = parent;
        });
        parent.childNodes.splice(index, 1, ...replacements);
        textNode.parentNode = null;
    }

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

function queryByTag(root, tagName) {
    return queryByTags(root, [tagName]);
}

function queryByTags(root, tagNames) {
    const result = [];
    const normalizedTags = new Set(tagNames.map((tagName) => tagName.toUpperCase()));
    const visit = (node) => {
        if (node.nodeType === 1 && normalizedTags.has(node.tagName)) result.push(node);
        if (node.childNodes) node.childNodes.forEach(visit);
    };
    visit(root);
    return result;
}

function queryTagSelectorList(selector) {
    const tags = selector.split(',').map((item) => item.trim()).filter(Boolean);
    if (!tags.length) return null;
    return tags.every((tag) => /^[a-z]+$/i.test(tag)) ? tags : null;
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
        fonts: { ready: options.fontsReady ?? Promise.resolve() },
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
        createTreeWalker(root, whatToShow, filter) {
            const nodes = [];
            const visit = (node) => {
                if (node.nodeType === 3 && (!filter || filter.acceptNode(node) === 1)) nodes.push(node);
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
            if (selector === 'ruby') return queryRuby(body);
            const tagSelector = queryTagSelectorList(selector);
            if (tagSelector) return queryByTags(body, tagSelector);
            return [];
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
        scrollTo(leftOrOptions, top) {
            if (typeof leftOrOptions === 'object') {
                if (Number.isFinite(leftOrOptions.left)) this.scrollX = leftOrOptions.left;
                if (Number.isFinite(leftOrOptions.top)) this.scrollY = leftOrOptions.top;
                return;
            }
            if (Number.isFinite(leftOrOptions)) this.scrollX = leftOrOptions;
            if (Number.isFinite(top)) this.scrollY = top;
        },
        scrollBy(delta) {
            if (Number.isFinite(delta?.left)) this.scrollX += delta.left;
            if (Number.isFinite(delta?.top)) this.scrollY += delta.top;
        },
        getComputedStyle() {
            return { writingMode: options.writingMode ?? 'vertical-rl', getPropertyValue: () => '' };
        },
    };
    if (options.restoreMessages) {
        window.HoshiReaderRestore = {
            postMessage(message) {
                options.restoreMessages.push(message);
            },
        };
    }
    document.scrollingElement = documentElement;
    const css = options.css ?? { highlights: { delete() {}, set() {} } };
    vm.runInNewContext(readerSource(sourceUrl, options), {
        CSS: css,
        Highlight: Set,
        document,
        Node: { ELEMENT_NODE: 1, TEXT_NODE: 3 },
        NodeFilter: { SHOW_TEXT: 4, FILTER_ACCEPT: 1, FILTER_REJECT: 2 },
        setTimeout(callback) {
            callback();
            return 0;
        },
        requestAnimationFrame(callback) {
            callback();
            return 0;
        },
        window,
    });
    return { reader: window.hoshiReader, document, head, window };
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

for (const sourceUrl of [readerPaginatedUrl, readerContinuousUrl]) {
    test(`${sourceUrl.pathname.split('/').pop()} counts Korean and skips ruby fallback in offsets and cues`, () => {
        const body = new TestElement('body');
        body.appendChild(new TestText('𠮟가、'));
        const ruby = new TestElement('ruby');
        const base = new TestText('한글');
        ruby.appendChild(base);
        for (const [tag, text] of [['rp', 'fallback'], ['rt', 'reading'], ['rp', '주석']]) {
            const annotation = new TestElement(tag);
            annotation.appendChild(new TestText(text));
            ruby.appendChild(annotation);
        }
        body.appendChild(ruby);
        const tail = new TestText(' ㄱㆎA');
        body.appendChild(tail);
        const { reader } = loadReader(body, sourceUrl);
        reader.buildNodeOffsets();

        assert.equal(reader.nodeStartOffsets.get(base), 2);
        assert.equal(reader.nodeStartRawOffsets.get(base), 3);
        assert.equal(reader.nodeStartOffsets.get(tail), 4);
        assert.equal(reader.nodeStartRawOffsets.get(tail), 5);
        assert.equal(reader.textOffsetForCharCount(body.firstChild, 1), 2);

        reader.isEInkMode = () => false;
        reader.applySasayakiCues([{ id: 'korean', start: 2, length: 2 }]);
        assert.equal(reader.cueWrappers.get('korean').map((wrapper) => wrapper.textContent).join(''), '한글');
    });
}

function rubyParagraphWithWhitespaceTextNodes() {
    const { paragraph, ruby } = rubyParagraph();
    ruby.insertBefore(new TestText('\n  '), ruby.firstChild);
    ruby.appendChild(new TestText(' \n'));
    return { paragraph, ruby };
}

function imgAt(top, bottom, left = 0, right = 20) {
    const img = new TestElement('img');
    img.rect = testRect(top, bottom, left, right);
    return img;
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

test('paged and continuous readers use shared text semantics', () => {
    [readerPaginatedUrl, readerContinuousUrl].forEach((sourceUrl) => {
        const body = new TestElement('body');
        body.appendChild(new TestText('本文。'));
        const { reader, window } = loadReader(body, sourceUrl);
        const originalCountChars = window.hoshiReaderTextSemantics.countChars;
        let countCalls = 0;
        window.hoshiReaderTextSemantics.countChars = (text) => {
            countCalls += 1;
            return originalCountChars(text);
        };

        assert.equal(reader.countChars('一、二'), 2);
        assert.equal(countCalls, 1);
    });
});

test('paged and continuous readers use shared DOM text normalization', () => {
    [readerPaginatedUrl, readerContinuousUrl].forEach((sourceUrl) => {
        const body = new TestElement('body');
        const { paragraph, ruby } = rubyParagraphWithWhitespaceTextNodes();
        body.appendChild(paragraph);

        const { reader, window } = loadReader(body, sourceUrl);
        reader.isVertical = () => true;
        const originalNormalizeReaderText = window.hoshiReaderDomText.normalizeReaderText;
        let normalizeCalls = 0;
        window.hoshiReaderDomText.normalizeReaderText = (context, parent) => {
            normalizeCalls += 1;
            return originalNormalizeReaderText(context, parent);
        };

        reader.normalizeReaderText(paragraph);

        assert.equal(normalizeCalls, 1);
        assert.equal(paragraph.textContent, '進藤歩あゆむ。それ');
        assert.ok(textRunAfter(ruby).includes('。'));
    });
});

test('paged and continuous readers use shared media setup', () => {
    [readerPaginatedUrl, readerContinuousUrl].forEach((sourceUrl) => {
        const body = new TestElement('body');
        const mediaSemanticsScript = `
          window.__mediaSetupCalls = [];
          window.hoshiReaderMediaSemantics = {
            setupReaderImages: function(scope, options) {
              window.__mediaSetupCalls.push({
                documentScope: scope === document,
                blurImages: options.blurImages,
                hasImageBridge: options.imageBridge === window.HoshiReaderImage,
                waitForImages: options.waitForImages
              });
              return Promise.resolve();
            }
          };
        `;
        const { reader, window } = loadReader(body, sourceUrl, { mediaSemanticsScript });
        window.HoshiReaderImage = { postMessage() {} };

        reader.initialize();

        assert.equal(window.__mediaSetupCalls.length, 1);
        assert.equal(window.__mediaSetupCalls[0].documentScope, true);
        assert.equal(window.__mediaSetupCalls[0].blurImages, false);
        assert.equal(window.__mediaSetupCalls[0].hasImageBridge, true);
        assert.equal(window.__mediaSetupCalls[0].waitForImages, true);
    });
});

test('paged and continuous readers use actual viewport height', () => {
    const paginatedBody = new TestElement('body');
    const paginated = loadReader(paginatedBody, readerPaginatedUrl);
    paginated.reader.initialize();

    assert.equal(paginated.document.documentElement.style.getPropertyValue('--page-height'), '800px');
    assert.equal(paginated.document.documentElement.style.getPropertyValue('--hoshi-reader-visible-height'), '800px');

    const continuousBody = new TestElement('body');
    const continuous = loadReader(continuousBody, readerContinuousUrl);
    continuous.reader.initialize();

    assert.equal(continuous.document.documentElement.style.getPropertyValue('--hoshi-continuous-height'), '800px');
    assert.equal(continuous.document.documentElement.style.getPropertyValue('--hoshi-reader-visible-height'), '800px');
    assert.equal(continuous.document.documentElement.style.getPropertyValue('--page-height'), '');
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

test('reader initialization waits for fonts and images before sanitizing layout, offsets, and restore scripts', async () => {
    for (const sourceUrl of [readerPaginatedUrl, readerContinuousUrl]) {
        const body = new TestElement('body');
        body.appendChild(new TestText('本文'));
        const events = [];
        let resolveFonts;
        let resolveImages;
        const fontsReady = new Promise((resolve) => {
            resolveFonts = resolve;
        });
        const mediaSemanticsScript = `
          window.hoshiReaderMediaSemantics = {
            setupReaderImages: function() {
              window.__events.push('setup');
              return new Promise(function(resolve) {
                window.__resolveImages = resolve;
              });
            }
          };
        `;
        const layoutSemanticsScript = `
          window.hoshiReaderLayoutSemantics = {
            sanitizeInlineBlocks: function(scope, vertical) {
              window.__events.push(scope === document && vertical ? 'sanitize-vertical' : 'sanitize-horizontal');
            }
          };
        `;
        const restoreScripts = "window.__events.push('restore'); window.hoshiReader.restoreProgress(0);";
        const { reader, window } = loadReader(body, sourceUrl, {
            mediaSemanticsScript,
            layoutSemanticsScript,
            fontsReady,
            restoreScripts,
            restoreMessages: [],
        });
        window.__events = events;
        reader.buildNodeOffsets = () => {
            events.push('offsets');
        };

        reader.initialize();
        resolveImages = window.__resolveImages;
        await Promise.resolve();

        assert.deepEqual(events, ['setup']);

        resolveImages();
        for (let i = 0; i < 3; i += 1) {
            await Promise.resolve();
        }
        assert.deepEqual(events, ['setup']);

        resolveFonts();
        for (let i = 0; i < 10; i += 1) {
            await Promise.resolve();
        }

        assert.deepEqual(events.slice(0, 4), ['setup', 'sanitize-vertical', 'offsets', 'restore']);
    }
});

for (const [firstText, secondText] of [['一二', '三四五'], ['가힣', 'ㄱㆎ한']]) {
    test(`paginated restoreProgress lands on the page containing ${secondText}`, async () => {
        const body = new TestElement('body');
        body.scrollTop = 0;
        body.scrollHeight = 3_200;
        const first = new TestText(firstText);
        first.rects = [testRect(0, 40)];
        const punctuation = new TestText('。');
        punctuation.rects = [testRect(780, 800)];
        const second = new TestText(secondText);
        second.rects = [testRect(1_620, 1_660)];
        body.appendChild(first);
        body.appendChild(punctuation);
        body.appendChild(second);
        const restoreMessages = [];
        const { reader } = loadReader(body, readerPaginatedUrl, { restoreMessages });
        reader.pageHeight = 800;
        reader.pageWidth = 480;
        reader.registerSnapScroll = (position) => {
            reader.snapPosition = position;
        };
        reader.refreshSasayakiCuePresentation = () => {};

        await reader.restoreProgress(0.6);
        for (let i = 0; i < 5; i += 1) {
            await Promise.resolve();
        }

        assert.equal(body.scrollTop, 1_600);
        assert.equal(reader.snapPosition, 1_600);
        assert.deepEqual(restoreMessages, ['restore-token']);
    });
}

test('continuous Korean restore lands inside the target text after a supplementary character', async () => {
    const body = new TestElement('body');
    const first = new TestElement('p');
    first.appendChild(new TestText('𠮟가'));
    const second = new TestElement('p');
    second.appendChild(new TestText('ㄱㆎ한'));
    body.appendChild(first);
    body.appendChild(second);
    const { reader, document } = loadReader(body, readerContinuousUrl, { writingMode: 'horizontal-tb' });
    const createElement = document.createElement;
    const landings = [];
    document.createElement = (tag) => {
        const element = createElement(tag);
        element.scrollIntoView = () => {
            landings.push({ parent: element.parentNode, precedingText: element.previousSibling?.textContent });
        };
        return element;
    };

    await reader.restoreProgress(0.6);

    assert.deepEqual(landings, [{ parent: second, precedingText: 'ㄱ' }]);
    assert.equal(second.textContent, 'ㄱㆎ한');
});

test('continuous restoreProgress zero resets every WebView scroll surface', async () => {
    const body = new TestElement('body');
    body.scrollTop = 11;
    body.scrollLeft = -22;
    const restoreMessages = [];
    const { reader, document, window } = loadReader(body, readerContinuousUrl, {
        restoreMessages,
        writingMode: 'horizontal-tb',
    });
    window.scrollX = 33;
    window.scrollY = 44;
    document.documentElement.scrollTop = 55;
    document.documentElement.scrollLeft = 66;

    await reader.restoreProgress(0);
    for (let i = 0; i < 5; i += 1) {
        await Promise.resolve();
    }

    assert.equal(window.scrollX, 0);
    assert.equal(window.scrollY, 0);
    assert.equal(document.documentElement.scrollTop, 0);
    assert.equal(document.documentElement.scrollLeft, 0);
    assert.equal(body.scrollTop, 0);
    assert.equal(body.scrollLeft, 0);
    assert.deepEqual(restoreMessages, ['restore-token']);
});

test('continuous restoreProgress one lands on the last text block end', async () => {
    const body = new TestElement('body');
    const first = new TestElement('p');
    first.appendChild(new TestText('前'));
    const last = new TestElement('p');
    last.appendChild(new TestText('終'));
    body.appendChild(first);
    body.appendChild(last);
    const restoreMessages = [];
    const { reader } = loadReader(body, readerContinuousUrl, {
        restoreMessages,
        writingMode: 'horizontal-tb',
    });

    await reader.restoreProgress(1);
    for (let i = 0; i < 5; i += 1) {
        await Promise.resolve();
    }

    assert.equal(last.scrollIntoViewCalls.length, 1);
    assert.equal(last.scrollIntoViewCalls[0].block, 'end');
    assert.equal(last.scrollIntoViewCalls[0].inline, 'nearest');
    assert.equal(last.scrollIntoViewCalls[0].behavior, 'instant');
    assert.equal(first.scrollIntoViewCalls, undefined);
    assert.deepEqual(restoreMessages, ['restore-token']);
});

for (const [beforeText, visibleText] of [['古都', '３年生'], ['가힣', 'ㄱㆎ한']]) {
    test(`paged and continuous progress counts ${beforeText} before the viewport`, () => {
        for (const sourceUrl of [readerPaginatedUrl, readerContinuousUrl]) {
            const body = new TestElement('body');
            const before = new TestText(beforeText);
            before.rects = [testRect(-60, -20)];
            const punctuation = new TestText('。');
            punctuation.rects = [testRect(20, 40)];
            const visible = new TestText(visibleText);
            visible.rects = [testRect(20, 40)];
            body.appendChild(before);
            body.appendChild(punctuation);
            body.appendChild(visible);
            const { reader, document } = loadReader(body, sourceUrl, {
                writingMode: sourceUrl === readerContinuousUrl ? 'horizontal-tb' : 'vertical-rl',
            });
            reader.pageHeight = 800;
            reader.pageWidth = 480;
            document.documentElement.scrollTop = 0;

            assert.equal(reader.calculateProgress(), 2 / 5);
        }
    });
}

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

test('reader initialization completes when an image has already failed loading', async () => {
    for (const sourceUrl of [readerPaginatedUrl, readerContinuousUrl]) {
        const body = new TestElement('body');
        const image = new TestElement('img');
        image.complete = true;
        image.naturalWidth = 0;
        image.naturalHeight = 0;
        body.appendChild(image);
        const { reader } = loadReader(body, sourceUrl);
        let offsetsBuilt = 0;
        reader.buildNodeOffsets = () => {
            offsetsBuilt += 1;
        };

        reader.initialize();
        for (let i = 0; i < 10; i += 1) {
            await Promise.resolve();
        }

        assert.equal(offsetsBuilt, 1);
    }
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

test('Sasayaki highlight applies active DOM state without CSS Highlight API', () => {
    for (const sourceUrl of [readerPaginatedUrl, readerContinuousUrl]) {
        const body = new TestElement('body');
        body.appendChild(new TestText('蒸し暑い'));
        let cssHighlightSetCount = 0;
        const css = {
            highlights: {
                delete() {},
                set() {
                    cssHighlightSetCount += 1;
                },
            },
        };
        const { reader } = loadReader(body, sourceUrl, { css });
        reader.isEInkMode = () => false;

        reader.applySasayakiCues([{ id: 'cue', start: 0, length: 4 }]);
        reader.highlightSasayakiCue('cue', false);

        const wrappers = body.childNodes.filter((node) => node.nodeType === 1);
        const wrapper = wrappers[0];
        assert.equal(wrappers.length, 1);
        assert.equal(wrapper.nodeType, 1);
        assert.equal(wrapper.classList.contains('hoshi-sasayaki-cue'), true);
        assert.equal(wrapper.classList.contains('hoshi-sasayaki-active'), true);
        assert.equal(wrapper.textContent, '蒸し暑い');
        assert.equal(cssHighlightSetCount, 0);
    }
});

test('non e-ink Sasayaki chapter load keeps iOS-style wrappers without e-ink geometry', () => {
    for (const sourceUrl of [readerPaginatedUrl, readerContinuousUrl]) {
        const body = new TestElement('body');
        body.appendChild(new TestText('蒸し暑い'));
        const { reader } = loadReader(body, sourceUrl);
        reader.isEInkMode = () => false;

        reader.applySasayakiCues([{ id: 'cue', start: 0, length: 4 }]);

        const wrappers = reader.cueWrappers.get('cue') ?? [];
        assert.equal(wrappers.length, 1);
        assert.equal(wrappers[0].classList.contains('hoshi-sasayaki-cue'), true);
        assert.equal(wrappers[0].textContent, '蒸し暑い');
        assert.equal(reader.cueGeometryRanges.size, 0);
    }
});

test('Sasayaki cue includes punctuation between text nodes inside the same cue', () => {
    for (const sourceUrl of [readerPaginatedUrl, readerContinuousUrl]) {
        const body = new TestElement('body');
        body.appendChild(new TestText('古都'));
        body.appendChild(new TestText('。'));
        body.appendChild(new TestText('３年生'));
        const { reader } = loadReader(body, sourceUrl);
        reader.isEInkMode = () => false;

        reader.applySasayakiCues([{ id: 'cue', start: 0, length: 5 }]);

        const wrappers = reader.cueWrappers.get('cue') ?? [];
        assert.equal(wrappers.map((wrapper) => wrapper.textContent).join(''), '古都。３年生');
    }
});

test('e-ink Sasayaki chapter load keeps geometry available for id-only highlights', () => {
    for (const sourceUrl of [readerPaginatedUrl, readerContinuousUrl]) {
        const body = new TestElement('body');
        body.appendChild(new TestText('蒸し暑い'));
        const { reader } = loadReader(body, sourceUrl);
        let overlayRendered = 0;
        reader.isEInkMode = () => true;
        reader.renderSasayakiOverlay = () => {
            overlayRendered += 1;
        };

        reader.applySasayakiCues([{ id: 'cue', start: 0, length: 4 }]);
        const result = reader.highlightSasayakiCue('cue', false);

        assert.equal(result, null);
        assert.equal(reader.cueWrappers.size, 0);
        assert.equal((reader.cueGeometryRanges.get('cue') ?? []).length, 1);
        assert.equal(reader.activeCueId, 'cue');
        assert.equal(overlayRendered, 2);
    }
});

test('paginated Sasayaki media stop plan lists every image page before target cue', () => {
    const body = new TestElement('body');
    body.scrollHeight = 4_000;
    body.scrollWidth = 480;
    body.scrollTop = 0;
    body.scrollLeft = 0;
    body.appendChild(new TestText('一'));
    body.appendChild(imgAt(900, 1_300));
    body.appendChild(imgAt(1_720, 2_100));
    const target = new TestText('二三');
    target.rects = [testRect(2_500, 2_530)];
    body.appendChild(target);
    const { reader } = loadReader(body, readerPaginatedUrl);
    reader.pageHeight = 800;

    const stops = reader.sasayakiMediaStopsBeforeCue({ id: 'cue', start: 1, length: 2 });

    assert.deepEqual(
        Array.from(stops, (stop) => stop.scroll),
        [800, 1_600],
    );
});

test('paginated Sasayaki media stop plan ignores wide inline gaiji', () => {
    const body = new TestElement('body');
    body.scrollHeight = 2_400;
    body.scrollWidth = 480;
    body.scrollTop = 0;
    body.scrollLeft = 0;
    body.appendChild(new TestText('一'));
    const gaijiWide = imgAt(900, 1_000);
    gaijiWide.classList.add('gaiji-wide');
    body.appendChild(gaijiWide);
    const target = new TestText('二三');
    target.rects = [testRect(1_700, 1_730)];
    body.appendChild(target);
    const { reader } = loadReader(body, readerPaginatedUrl);
    reader.pageHeight = 800;

    const stops = reader.sasayakiMediaStopsBeforeCue({ id: 'cue', start: 1, length: 2 });

    assert.deepEqual(Array.from(stops), []);
});

test('paginated Sasayaki media stop plan ignores every class token containing gaiji', () => {
    const body = new TestElement('body');
    body.scrollHeight = 2_400;
    body.scrollWidth = 480;
    body.scrollTop = 0;
    body.scrollLeft = 0;
    body.appendChild(new TestText('一'));
    const gaijiVariant = imgAt(900, 1_000);
    gaijiVariant.classList.add('publisher-GaIjI-tall');
    body.appendChild(gaijiVariant);
    const target = new TestText('二三');
    target.rects = [testRect(1_700, 1_730)];
    body.appendChild(target);
    const { reader } = loadReader(body, readerPaginatedUrl);
    reader.pageHeight = 800;

    const stops = reader.sasayakiMediaStopsBeforeCue({ id: 'cue', start: 1, length: 2 });

    assert.deepEqual(Array.from(stops), []);
});

test('paginated Sasayaki media stop plan includes the current image page before target cue', () => {
    const body = new TestElement('body');
    body.scrollHeight = 2_400;
    body.scrollWidth = 480;
    body.scrollTop = 0;
    body.scrollLeft = 0;
    body.appendChild(imgAt(120, 620));
    const target = new TestText('二三');
    target.rects = [testRect(900, 930)];
    body.appendChild(target);
    const { reader } = loadReader(body, readerPaginatedUrl);
    reader.pageHeight = 800;

    const stops = reader.sasayakiMediaStopsBeforeCue({ id: 'cue', start: 0, length: 2 });

    assert.deepEqual(
        Array.from(stops, (stop) => stop.scroll),
        [0],
    );
});

test('paginated Sasayaki media stop command moves to the image page and reports progress', () => {
    const body = new TestElement('body');
    body.scrollHeight = 2_400;
    body.scrollWidth = 480;
    body.scrollTop = 0;
    body.scrollLeft = 0;
    const first = new TestText('一');
    first.rects = [testRect(0, 20)];
    body.appendChild(first);
    body.appendChild(imgAt(900, 1_200));
    const second = new TestText('二');
    second.rects = [testRect(1_700, 1_720)];
    body.appendChild(second);
    const { reader } = loadReader(body, readerPaginatedUrl);
    reader.pageHeight = 800;

    const progress = reader.showSasayakiMediaStop({ scroll: 800 });

    assert.equal(body.scrollTop, 800);
    assert.equal(typeof progress, 'number');
});

test('continuous Sasayaki media stop plan uses scroll targets for the current image before cue', () => {
    const body = new TestElement('body');
    body.scrollHeight = 2_400;
    body.scrollWidth = 480;
    body.appendChild(imgAt(0, 500));
    const target = new TestText('二三');
    target.rects = [testRect(900, 930)];
    body.appendChild(target);
    const { reader, document } = loadReader(body, readerContinuousUrl, { writingMode: 'horizontal-tb' });
    document.documentElement.scrollTop = 0;

    reader.applySasayakiCues([{ id: 'cue', start: 0, length: 2 }]);
    reader.cueWrappers.get('cue')[0].rect = testRect(900, 930);
    const stops = reader.sasayakiMediaStopsBeforeCue({ id: 'cue', start: 0, length: 2 });

    assert.deepEqual(
        Array.from(stops, (stop) => stop.scroll),
        [0],
    );
    assert.equal(reader.showSasayakiMediaStop(stops[0]), reader.calculateProgress());
    assert.equal(document.documentElement.scrollTop, 0);
});

test('continuous Sasayaki media stop plan skips a visible image when the target cue is already visible', () => {
    const body = new TestElement('body');
    body.appendChild(imgAt(0, 800, 320, 480));
    body.appendChild(new TestText('二三'));
    const { reader, document } = loadReader(body, readerContinuousUrl, { writingMode: 'vertical-rl' });
    document.documentElement.scrollLeft = 0.35;

    reader.applySasayakiCues([{ id: 'cue', start: 0, length: 2 }]);
    reader.cueWrappers.get('cue')[0].rect = testRect(120, 150, 160, 190);
    const stops = reader.sasayakiMediaStopsBeforeCue({ id: 'cue', start: 0, length: 2 });

    assert.deepEqual(Array.from(stops), []);
});

test('continuous vertical Sasayaki chapter-end media stop plan follows forward scroll direction', () => {
    const body = new TestElement('body');
    body.appendChild(imgAt(0, 500, 120, 420));
    body.appendChild(imgAt(0, 500, -520, -220));
    const { reader, document } = loadReader(body, readerContinuousUrl, { writingMode: 'vertical-rl' });
    document.documentElement.scrollWidth = 3_000;
    document.documentElement.scrollLeft = -480;

    const stops = reader.sasayakiMediaStopsToChapterEnd();

    assert.deepEqual(
        Array.from(stops, (stop) => stop.scroll),
        [-540, -1_180],
    );
});

test('paginated Sasayaki media stop chapter-end plan includes an image-only first page', () => {
    const body = new TestElement('body');
    body.scrollHeight = 800;
    body.scrollWidth = 480;
    body.scrollTop = 0;
    body.scrollLeft = 0;
    body.appendChild(imgAt(120, 620));
    const { reader } = loadReader(body, readerPaginatedUrl);
    reader.pageHeight = 800;

    const stops = reader.sasayakiMediaStopsToChapterEnd();

    assert.deepEqual(
        Array.from(stops, (stop) => stop.scroll),
        [0],
    );
});

test('active non e-ink Sasayaki cue can refresh into e-ink overlay', () => {
    for (const sourceUrl of [readerPaginatedUrl, readerContinuousUrl]) {
        const body = new TestElement('body');
        body.appendChild(new TestText('蒸し暑い'));
        const { reader } = loadReader(body, sourceUrl);
        let eInkMode = false;
        let overlayRendered = 0;
        reader.isEInkMode = () => eInkMode;
        reader.renderSasayakiOverlay = () => {
            overlayRendered += 1;
        };

        reader.applySasayakiCues([{ id: 'cue', start: 0, length: 4 }]);
        reader.highlightSasayakiCue('cue', false);
        eInkMode = true;
        reader.refreshSasayakiCuePresentation();

        assert.equal(reader.activeCueId, 'cue');
        assert.equal((reader.cueGeometryRanges.get('cue') ?? []).length, 1);
        assert.equal(overlayRendered, 1);
    }
});

test('reader initialization requires XHTML document.head like iOS', () => {
    for (const sourceUrl of [readerPaginatedUrl, readerContinuousUrl]) {
        const body = new TestElement('body');
        const { reader, document } = loadReader(body, sourceUrl, { documentHead: null });

        assert.equal(document.head, null);

        assert.throws(() => reader.initialize(), /appendChild/);
    }
});

test('Sasayaki assigns Japanese boundary punctuation identically for batch and single cues', () => {
    const cases = [
        ['「こんにちは。」', [5], ['「こんにちは。」']],
        ['「そうか。わかった。」', [3, 4], ['「そうか。', 'わかった。」']],
        ['そうか……わかった。', [3, 4], ['そうか……', 'わかった。']],
        ['「……本当！？」', [2], ['「……本当！？」']],
        ['彼は言った。「行こう」', [5, 3], ['彼は言った。', '「行こう」']],
        ['『「本当？」』', [2], ['『「本当？」』']],
        ['前文。　「次の文。」', [2, 3], ['前文。', '「次の文。」']],
        ['𠮟った――「え？」', [3, 1], ['𠮟った――', '「え？」']],
    ];
    for (const sourceUrl of [readerPaginatedUrl, readerContinuousUrl]) {
        for (const [text, lengths, expected] of cases) {
            const body = new TestElement('body');
            // Every character lives in a separate inline node.
            for (const char of text) {
                const span = new TestElement('span');
                span.appendChild(new TestText(char));
                body.appendChild(span);
            }
            const { reader } = loadReader(body, sourceUrl);
            let start = 0;
            const cues = lengths.map((length, id) => {
                const cue = { id: String(id), start, length };
                start += length;
                return cue;
            });
            const texts = (results) => Array.from(results, ({ ranges }) =>
                ranges.map(({ node, start, end }) => node.textContent.slice(start, end)).join(''));
            assert.deepEqual(texts(reader.collectSasayakiCueRanges(cues)), expected, text);
            for (let i = 0; i < cues.length; i++) {
                assert.deepEqual(texts(reader.collectSasayakiCueRanges([cues[i]])), [expected[i]], text);
            }
        }
    }
});

test('Sasayaki boundary expansion stops at paragraphs, breaks, media, whitespace and unknown symbols', () => {
    for (const sourceUrl of [readerPaginatedUrl, readerContinuousUrl]) {
        for (const barrier of ['p', 'br', 'img', ' ', '🙂', '.']) {
            const body = new TestElement('body');
            body.appendChild(new TestText('前'));
            if (barrier === 'p') {
                const paragraph = new TestElement('p');
                paragraph.appendChild(new TestText('……次。'));
                body.appendChild(paragraph);
            } else {
                body.appendChild(['br', 'img'].includes(barrier) ? new TestElement(barrier) : new TestText(barrier));
                body.appendChild(new TestText('……次。'));
            }
            const { reader } = loadReader(body, sourceUrl);
            const result = reader.collectSasayakiCueRanges([{ id: 'first', start: 0, length: 1 }, { id: 'next', start: 1, length: 1 }]);
            const texts = Array.from(result, ({ ranges }) => ranges.map(({ node, start, end }) => node.textContent.slice(start, end)).join(''));
            assert.deepEqual(texts, ['前', ['🙂', '.'].includes(barrier) ? '次。' : '……次。'], barrier);
            assert.equal(reader.collectSasayakiCueRanges([{ id: 'empty', start: 0, length: 0 }])[0].ranges.length, 0);
        }
    }
});

test('Sasayaki punctuation uses the same bounds in wrappers and e-ink geometry after reapplying cues', () => {
    for (const sourceUrl of [readerPaginatedUrl, readerContinuousUrl]) {
        for (const eInk of [false, true]) {
            const body = new TestElement('body');
            body.appendChild(new TestText('「一。」『二！？』'));
            const { reader } = loadReader(body, sourceUrl);
            reader.isEInkMode = () => eInk;
            const cues = [{ id: 'a', start: 0, length: 1 }, { id: 'b', start: 1, length: 1 }];
            for (let iteration = 0; iteration < 2; iteration++) {
                reader.applySasayakiCues(cues);
                const texts = cues.map(({ id }) => eInk
                    ? Array.from(reader.cueGeometryRanges.get(id), (r) => r.startNode.textContent.slice(r.startOffset, r.endOffset)).join('')
                    : Array.from(reader.cueWrappers.get(id), (node) => node.textContent).join(''));
                assert.deepEqual(texts, ['「一。」', '『二！？』']);
            }
        }
    }
});

test('Sasayaki DOM punctuation indexing preserves the existing walker character offsets', () => {
    for (const sourceUrl of [readerPaginatedUrl, readerContinuousUrl]) {
        const body = new TestElement('body');
        body.appendChild(new TestText('一'));
        const script = new TestElement('script');
        script.appendChild(new TestText('ignored'));
        body.appendChild(script);
        body.appendChild(new TestText('「二。」'));
        const { reader } = loadReader(body, sourceUrl);
        reader.buildNodeOffsets();
        const cue = { id: 'cue', start: reader.nodeStartOffsets.get(body.childNodes[2]), length: 1 };
        const [{ ranges }] = reader.collectSasayakiCueRanges([cue]);
        assert.equal(ranges.map(({ node, start, end }) => node.textContent.slice(start, end)).join(''), '「二。」');
    }
});

test('Sasayaki includes ruby base punctuation without reading annotation text or layout', () => {
    for (const sourceUrl of [readerPaginatedUrl, readerContinuousUrl]) {
        const body = new TestElement('body');
        body.appendChild(new TestText('「'));
        const ruby = new TestElement('ruby');
        ruby.appendChild(new TestText('𠮟'));
        const rt = new TestElement('rt');
        const styledAnnotation = new TestElement('div');
        styledAnnotation.appendChild(new TestText('しか'));
        rt.appendChild(styledAnnotation);
        ruby.appendChild(rt);
        body.appendChild(ruby);
        body.appendChild(new TestText('。」'));
        const { reader } = loadReader(body, sourceUrl);
        const [{ ranges }] = reader.collectSasayakiCueRanges([{ id: 'a', start: 0, length: 1 }]);
        assert.equal(ranges.map(({ node, start, end }) => node.textContent.slice(start, end)).join(''), '「𠮟。」');
    }
});


for (const sourceUrl of [readerPaginatedUrl, readerContinuousUrl]) {
    test(`${sourceUrl.pathname.split('/').pop()} persists ruby and edits exact raw ranges through pending selection`, () => {
        const body = new TestElement('body');
        body.appendChild(new TestText('あ 、𠮟'));
        const ruby = new TestElement('ruby');
        const base = new TestElement('span');
        const bold = new TestElement('b'); bold.appendChild(new TestText('東'));
        base.appendChild(bold); base.appendChild(new TestText('京'));
        ruby.appendChild(base);
        const rp = new TestElement('rp'); rp.appendChild(new TestText('(')); ruby.appendChild(rp);
        const rt = new TestElement('rt'); rt.appendChild(new TestText('とうきょう')); ruby.appendChild(rt);
        body.appendChild(ruby);
        body.appendChild(new TestText('、東京'));
        const { reader, window, document } = loadReader(body, sourceUrl, {
            highlightsScript: fs.readFileSync(new URL('../../main/assets/hoshi-web/reader/highlights.js', import.meta.url), 'utf8'),
        });
        reader.buildNodeOffsets();
        const highlights = window.hoshiHighlights;
        const select = (offset, length) => {
            const segments = highlights.collectSegments(offset, length);
            const range = document.createRange();
            range.setStart(segments[0].node, segments[0].start);
            range.setEnd(segments.at(-1).node, segments.at(-1).end);
            window.getSelection = () => ({ rangeCount: 1, getRangeAt: () => range, removeAllRanges() {} });
        };
        select(4, 2);
        assert.equal(highlights.prepareHighlightSelection(), true);
        window.getSelection = () => null;
        const created = highlights.createHighlight('yellow', 'ruby');
        assert.equal(created.start, 2);
        assert.equal(created.offset, 4);
        assert.equal(created.text, '東京');
        assert.equal(created.textFurigana, '東京(とうきょう)');
        select(3, 3);
        const overlap = highlights.createHighlight('blue', 'overlap');
        assert.equal(overlap.action, 'created');
        assert.equal(overlap.text, '𠮟東京');
        assert.equal(overlap.textFurigana, '𠮟東京(とうきょう)');
        select(7, 2);
        assert.equal(highlights.createHighlight('pink', 'repeated').textFurigana, null);
        assert.equal(highlights.highlights.size, 3);
        select(4, 2);
        assert.equal(highlights.createHighlight('green', 'unused').action, 'recolored');
        assert.equal(highlights.highlights.get('ruby').color, 'green');
        assert.equal(highlights.highlights.size, 3);
        select(4, 2);
        assert.equal(highlights.createHighlight('green', 'unused').action, 'removed');
        assert.equal(highlights.highlights.size, 2);
        assert.equal(highlights.textForRange(0, 9).text, 'あ 、𠮟東京、東京');
        assert.equal(highlights.textForRange(4, 2).textFurigana, '東京(とうきょう)');
        // Reloading persisted data must also rebuild exact-range identity.
        const restored = loadReader(new TestElement('body'), sourceUrl, {
            highlightsScript: fs.readFileSync(new URL('../../main/assets/hoshi-web/reader/highlights.js', import.meta.url), 'utf8'),
        });
        restored.document.body.appendChild(new TestText('あ 、𠮟東京、東京'));
        restored.window.hoshiHighlights.applyHighlights([{ id: 'saved', color: 'blue', offset: 3, text: '𠮟東京' }]);
        assert.equal(restored.window.hoshiHighlights.findHighlight(3, 3), 'saved');
        assert.equal(restored.window.hoshiHighlights.findHighlight(3, 2), null);
    });
}

for (const sourceUrl of [readerPaginatedUrl, readerContinuousUrl]) {
    test(`${sourceUrl.pathname.split('/').pop()} search highlight uses normalized offsets and stays transient`, () => {
        const body = new TestElement('body');
        body.appendChild(new TestText('前「𠮟 、'));
        const ruby = new TestElement('ruby');
        ruby.appendChild(new TestText('猫'));
        const rt = new TestElement('rt'); rt.appendChild(new TestText('ねこ')); ruby.appendChild(rt);
        body.appendChild(ruby);
        body.appendChild(new TestText('。」後'));
        const registry = new Map();
        const { reader, window } = loadReader(body, sourceUrl, {
            css: { highlights: registry },
            highlightsScript: fs.readFileSync(new URL('../../main/assets/hoshi-web/reader/highlights.js', import.meta.url), 'utf8'),
        });
        reader.buildNodeOffsets();
        const highlights = window.hoshiHighlights;
        highlights.showSearchHighlight(1, 2);
        const text = () => Array.from(registry.get('hoshi-search') ?? [], range =>
            range.startContainer.textContent.slice(range.startOffset, range.endOffset)).join('');
        assert.equal(text(), '𠮟 、猫');
        assert.equal(highlights.highlights.size, 0);
        highlights.showSearchHighlight(3, 1);
        assert.equal(text(), '後');
        highlights.showSearchHighlight(2, 0);
        assert.equal(text(), '');
        highlights.showSearchHighlight(1, 2);
        highlights.clearSearchHighlight();
        assert.equal(text(), '');
        highlights.showSearchHighlight(1, 2);
        reader.paginate('forward');
        assert.equal(text(), '');
        highlights.showSearchHighlight(1, 2);
        reader.highlightSasayakiCue({ id: 'cue', start: 3, length: 1 }, false);
        assert.equal(text(), '𠮟 、猫');
        reader.highlightSasayakiCue({ id: 'cue', start: 3, length: 1 }, true);
        assert.equal(text(), '');
    });
}
