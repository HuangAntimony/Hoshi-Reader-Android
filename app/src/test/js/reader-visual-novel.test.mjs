import assert from 'node:assert/strict';
import fs from 'node:fs';
import test from 'node:test';
import vm from 'node:vm';

const readerVisualNovelUrl = new URL('../../main/assets/hoshi-web/reader/reader-visual-novel.js', import.meta.url);
const readerTextSemanticsUrl = new URL('../../main/assets/hoshi-web/reader/reader-text-semantics.js', import.meta.url);
const readerMediaSemanticsUrl = new URL('../../main/assets/hoshi-web/reader/reader-media-semantics.js', import.meta.url);
const readerVnContentStreamUrl = new URL('../../main/assets/hoshi-web/reader/reader-vn-content-stream.js', import.meta.url);
const readerVnRangeMapUrl = new URL('../../main/assets/hoshi-web/reader/reader-vn-range-map.js', import.meta.url);
const readerHighlightsUrl = new URL('../../main/assets/hoshi-web/reader/highlights.js', import.meta.url);

function readerTextSemanticsSource() {
    return fs.readFileSync(readerTextSemanticsUrl, 'utf8');
}

function readerVnContentStreamSource() {
    return fs.readFileSync(readerVnContentStreamUrl, 'utf8');
}

function readerVnRangeMapSource() {
    return fs.readFileSync(readerVnRangeMapUrl, 'utf8');
}

function readerMediaSemanticsSource() {
    return fs.readFileSync(readerMediaSemanticsUrl, 'utf8');
}

function readerHighlightsSource() {
    return fs.readFileSync(readerHighlightsUrl, 'utf8');
}

function readerSource() {
    return fs.readFileSync(readerVisualNovelUrl, 'utf8')
        .replaceAll('__HOSHI_READER_TEXT_SEMANTICS_SCRIPT__', readerTextSemanticsSource())
        .replaceAll('__HOSHI_READER_MEDIA_SEMANTICS_SCRIPT__', readerMediaSemanticsSource())
        .replaceAll('__HOSHI_READER_VN_CONTENT_STREAM_SCRIPT__', readerVnContentStreamSource())
        .replaceAll('__HOSHI_READER_VN_RANGE_MAP_SCRIPT__', readerVnRangeMapSource())
        .replaceAll('__HOSHI_VISUAL_NOVEL_REVEAL_SPEED__', '0')
        .replaceAll('__HOSHI_VISUAL_NOVEL_SCREEN_MODE_LITERAL__', JSON.stringify('block'))
        .replaceAll('__HOSHI_VISUAL_NOVEL_SENTENCES_PER_SCREEN__', '1')
        .replaceAll('__HOSHI_VISUAL_NOVEL_PRESERVE_DIALOGUE__', 'false')
        .replaceAll('__HOSHI_VISUAL_NOVEL_MERGE_CROSS_SCREEN_SASAYAKI_CUES__', 'false')
        .replaceAll('__HOSHI_INITIAL_SASAYAKI_CUES_JSON__', 'null')
        .replaceAll('__HOSHI_INITIAL_PROGRESS__', '0')
        .replaceAll('__HOSHI_INITIAL_FRAGMENT_LITERAL__', 'null')
        .replaceAll('__HOSHI_INITIAL_HIGHLIGHTS_JSON__', 'null')
        .replaceAll('__HOSHI_HIGHLIGHTS_SCRIPT__', '')
        .replaceAll('__HOSHI_RESTORE_TOKEN_LITERAL__', JSON.stringify('restore-token'))
        .replaceAll('__HOSHI_BLUR_IMAGES__', 'false');
}

function configuredReaderSource(options = {}) {
    return fs.readFileSync(readerVisualNovelUrl, 'utf8')
        .replaceAll('__HOSHI_READER_TEXT_SEMANTICS_SCRIPT__', options.textSemanticsScript ?? readerTextSemanticsSource())
        .replaceAll('__HOSHI_READER_MEDIA_SEMANTICS_SCRIPT__', options.mediaSemanticsScript ?? readerMediaSemanticsSource())
        .replaceAll('__HOSHI_READER_VN_CONTENT_STREAM_SCRIPT__', options.contentStreamScript ?? readerVnContentStreamSource())
        .replaceAll('__HOSHI_READER_VN_RANGE_MAP_SCRIPT__', options.rangeMapScript ?? readerVnRangeMapSource())
        .replaceAll('__HOSHI_VISUAL_NOVEL_REVEAL_SPEED__', String(options.revealSpeed ?? 0))
        .replaceAll('__HOSHI_VISUAL_NOVEL_SCREEN_MODE_LITERAL__', JSON.stringify(options.mode ?? 'block'))
        .replaceAll('__HOSHI_VISUAL_NOVEL_SENTENCES_PER_SCREEN__', String(options.sentencesPerScreen ?? 1))
        .replaceAll('__HOSHI_VISUAL_NOVEL_PRESERVE_DIALOGUE__', String(options.preserveDialogue ?? false))
        .replaceAll(
            '__HOSHI_VISUAL_NOVEL_MERGE_CROSS_SCREEN_SASAYAKI_CUES__',
            String(options.mergeCrossScreenSasayakiCues ?? false),
        )
        .replaceAll(
            '__HOSHI_INITIAL_SASAYAKI_CUES_JSON__',
            options.initialSasayakiCues === undefined ? 'null' : JSON.stringify(options.initialSasayakiCues),
        )
        .replaceAll('__HOSHI_INITIAL_PROGRESS__', String(options.initialProgress ?? 0))
        .replaceAll('__HOSHI_INITIAL_FRAGMENT_LITERAL__', options.initialFragment === undefined ? 'null' : JSON.stringify(options.initialFragment))
        .replaceAll(
            '__HOSHI_INITIAL_HIGHLIGHTS_JSON__',
            options.initialHighlights === undefined ? 'null' : JSON.stringify(options.initialHighlights),
        )
        .replaceAll('__HOSHI_HIGHLIGHTS_SCRIPT__', options.highlightsScript ?? '')
        .replaceAll('__HOSHI_RESTORE_TOKEN_LITERAL__', JSON.stringify('restore-token'))
        .replaceAll('__HOSHI_BLUR_IMAGES__', String(options.blurImages ?? false));
}

class TestNode {
    constructor(nodeType) {
        this.nodeType = nodeType;
        this.parentNode = null;
    }

    get parentElement() {
        return this.parentNode?.nodeType === 1 ? this.parentNode : null;
    }

    get firstChild() {
        return this.childNodes?.[0] ?? null;
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

    remove() {
        this.parentNode?.removeChild(this);
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

    cloneNode() {
        return new TestText(this.nodeValue);
    }
}

class TestElement extends TestNode {
    constructor(tagName) {
        super(1);
        this.tagName = tagName.toUpperCase();
        this.childNodes = [];
        this.attributes = new Map();
        this.listeners = new Map();
        this.style = {
            values: new Map(),
            setProperty(name, value) {
                this.values.set(name, value);
            },
            getPropertyValue(name) {
                return this.values.get(name) ?? '';
            },
        };
        this.classList = {
            add: (...names) => {
                const classes = new Set(this.className.split(/\s+/).filter(Boolean));
                names.forEach((name) => classes.add(name));
                this.className = [...classes].join(' ');
            },
            remove: (...names) => {
                const removals = new Set(names);
                this.className = this.className
                    .split(/\s+/)
                    .filter((name) => name && !removals.has(name))
                    .join(' ');
            },
            contains: (name) => this.className.split(/\s+/).includes(name),
        };
    }

    get className() {
        return this.attributes.get('class') ?? '';
    }

    set className(value) {
        if (value) {
            this.attributes.set('class', value);
        } else {
            this.attributes.delete('class');
        }
    }

    get id() {
        return this.attributes.get('id') ?? '';
    }

    set id(value) {
        this.setAttribute('id', value);
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
        if (child.nodeType === 11) {
            [...child.childNodes].forEach((fragmentChild) => this.insertBefore(fragmentChild, before));
            child.childNodes = [];
            return child;
        }
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

    get firstChild() {
        return this.childNodes[0] ?? null;
    }

    replaceChildren(...children) {
        this.childNodes.forEach((child) => {
            child.parentNode = null;
        });
        this.childNodes = [];
        children.forEach((child) => this.appendChild(child));
    }

    addEventListener(type, listener) {
        const listeners = this.listeners.get(type) ?? [];
        listeners.push(listener);
        this.listeners.set(type, listeners);
    }

    dispatchEvent(event) {
        (this.listeners.get(event.type) ?? []).forEach((listener) => listener.call(this, event));
    }

    getAttribute(name) {
        return this.attributes.get(name) ?? null;
    }

    setAttribute(name, value) {
        this.attributes.set(name, String(value));
    }

    removeAttribute(name) {
        this.attributes.delete(name);
    }

    hasAttribute(name) {
        return this.attributes.has(name);
    }

    cloneNode(deep = false) {
        const clone = new TestElement(this.tagName);
        this.attributes.forEach((value, key) => clone.setAttribute(key, value));
        clone.complete = this.complete;
        clone.naturalWidth = this.naturalWidth;
        clone.naturalHeight = this.naturalHeight;
        clone.currentSrc = this.currentSrc;
        clone.src = this.src;
        if (deep) {
            this.childNodes.forEach((child) => clone.appendChild(child.cloneNode(true)));
        }
        return clone;
    }

    closest(selector) {
        const selectors = selector.split(',').map((item) => item.trim());
        let node = this;
        while (node) {
            if (node.nodeType === 1 && selectors.some((item) => matchesSelector(node, item))) {
                return node;
            }
            node = node.parentNode;
        }
        return null;
    }

    querySelector(selector) {
        return querySelectorAll(this, selector)[0] ?? null;
    }

    querySelectorAll(selector) {
        return querySelectorAll(this, selector);
    }

    getClientRects() {
        return [this.getBoundingClientRect()];
    }

    getBoundingClientRect() {
        const layout = this.ownerDocument?._vnLayout;
        const capacity = layout?.charactersPerScreen ?? 800;
        if (
            this.classList.contains('hoshi-vn-stage') ||
            this.classList.contains('hoshi-vn-screen') ||
            this.classList.contains('hoshi-vn-content')
        ) {
            if (!layout?.charactersPerScreen) {
                return { x: 0, y: 0, left: 0, right: 800, top: 0, bottom: 800, width: 800, height: 800 };
            }
            const extent = capacity * 24;
            const screenOverflow = this.classList.contains('hoshi-vn-screen') || this.classList.contains('hoshi-vn-stage')
                ? (layout?.screenInlineOverflowCharacters ?? 0) * 24
                : 0;
            if (layout?.writingMode && layout.writingMode.startsWith('vertical')) {
                return {
                    x: 0,
                    y: 0,
                    left: 0,
                    right: 24,
                    top: 0,
                    bottom: extent + screenOverflow,
                    width: 24,
                    height: extent + screenOverflow,
                };
            }
            return {
                x: 0,
                y: 0,
                left: 0,
                right: 24,
                top: 0,
                bottom: extent + screenOverflow,
                width: 24,
                height: extent + screenOverflow,
            };
        }
        return { x: 0, y: 0, left: 0, right: 24, top: 0, bottom: 24, width: 24, height: 24 };
    }

    get clientWidth() {
        return this.getBoundingClientRect().width;
    }

    get clientHeight() {
        return this.getBoundingClientRect().height;
    }

    get scrollWidth() {
        return this.clientWidth;
    }

    get scrollHeight() {
        const layout = this.ownerDocument?._vnLayout;
        const capacity = layout?.charactersPerScreen;
        if (!capacity || !this.classList.contains('hoshi-vn-content')) return this.clientHeight;
        return Math.max(this.clientHeight, this.textContent.length * 24);
    }

    get innerText() {
        return this.textContent;
    }

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

    cloneNode(deep = false) {
        const clone = new TestFragment();
        if (deep) {
            this.childNodes.forEach((child) => clone.appendChild(child.cloneNode(true)));
        }
        return clone;
    }

    get textContent() {
        return this.childNodes.map((child) => child.textContent).join('');
    }
}

class TestRange {
    constructor(document) {
        this.document = document;
        this.node = null;
        this.startNode = null;
        this.startOffset = 0;
        this.endNode = null;
        this.endOffset = 0;
        this.insertionParent = null;
        this.insertionIndex = null;
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
        const replacements = [];
        if (before) replacements.push(new TestText(before));
        if (after) replacements.push(new TestText(after));
        replacements.forEach((node) => {
            node.parentNode = parent;
        });
        parent.childNodes.splice(index, 1, ...replacements);
        textNode.parentNode = null;
        this.insertionParent = parent;
        this.insertionIndex = before ? index + 1 : index;
        if (selected) fragment.appendChild(new TestText(selected));
        return fragment;
    }

    insertNode(node) {
        if (!this.insertionParent || this.insertionIndex === null) return;
        node.parentNode?.removeChild(node);
        node.parentNode = this.insertionParent;
        this.insertionParent.childNodes.splice(this.insertionIndex, 0, node);
        this.insertionIndex += 1;
    }

    getClientRects() {
        const layoutRect = this.layoutRect();
        if (layoutRect) return [layoutRect];
        return this.startNode?.rects ?? this.node?.rects ?? [
            { x: 12, y: 24, left: 12, right: 32, top: 24, bottom: 48, width: 20, height: 24 },
        ];
    }

    layoutRect() {
        const layout = this.document?._vnLayout;
        const capacity = layout?.charactersPerScreen;
        const node = this.startNode?.nodeType === 3 ? this.startNode : this.node?.nodeType === 3 ? this.node : null;
        if (!capacity || !node) return null;
        const content = closestElement(node, '.hoshi-vn-content');
        if (!content) return null;
        const start = textOffsetWithin(content, node) + this.startOffset;
        const end = textOffsetWithin(content, this.endNode === node ? node : this.startNode) + this.endOffset;
        const safeEnd = Math.max(start, end);
        if (layout.writingMode && layout.writingMode.startsWith('vertical')) {
            return { x: 0, y: start * 24, left: 0, right: 24, top: start * 24, bottom: safeEnd * 24, width: 24, height: (safeEnd - start) * 24 };
        }
        return { x: 0, y: start * 24, left: 0, right: 24, top: start * 24, bottom: safeEnd * 24, width: 24, height: (safeEnd - start) * 24 };
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

function closestElement(node, selector) {
    let current = node?.parentElement;
    while (current) {
        if (matchesSelector(current, selector)) return current;
        current = current.parentElement;
    }
    return null;
}

function textOffsetWithin(root, target) {
    let offset = 0;
    let found = false;
    const visit = (node) => {
        if (found) return;
        if (node === target) {
            found = true;
            return;
        }
        if (node.nodeType === 3) {
            offset += node.textContent.length;
        }
        node.childNodes?.forEach(visit);
    };
    visit(root);
    return offset;
}

function matchesSelector(node, selector) {
    if (selector.startsWith('#')) {
        return node.id === selector.slice(1);
    }
    if (selector.startsWith('.')) {
        return node.classList.contains(selector.slice(1));
    }
    if (selector.startsWith('[') && selector.endsWith(']')) {
        return node.hasAttribute(selector.slice(1, -1));
    }
    return node.tagName === selector.toUpperCase();
}

function querySelectorAll(root, selector) {
    const svgImageSelector = selector.trim() === 'svg image';
    const selectors = selector.split(',').map((item) => item.trim());
    const result = [];
    const visit = (node) => {
        if (
            node.nodeType === 1 &&
            (
                (svgImageSelector && node.tagName === 'IMAGE' && closestElement(node, 'svg')) ||
                selectors.some((item) => matchesSelector(node, item))
            )
        ) {
            result.push(node);
        }
        node.childNodes?.forEach(visit);
    };
    visit(root);
    return result;
}

function collectTextNodes(root) {
    const result = [];
    const visit = (node) => {
        if (node.nodeType === 3) result.push(node);
        node.childNodes?.forEach(visit);
    };
    visit(root);
    return result;
}

function findElementById(root, id) {
    let result = null;
    const visit = (node) => {
        if (result) return;
        if (node.nodeType === 1 && node.id === id) {
            result = node;
            return;
        }
        node.childNodes?.forEach(visit);
    };
    visit(root);
    return result;
}

function assignOwnerDocument(node, document) {
    node.ownerDocument = document;
    node.childNodes?.forEach((child) => assignOwnerDocument(child, document));
}

function buildDocument(body, options = {}) {
    const head = new TestElement('head');
    const documentElement = new TestElement('html');
    documentElement.appendChild(head);
    documentElement.appendChild(body);
    const document = {
        body,
        head,
        documentElement,
        _vnLayout: {
            charactersPerScreen: options.charactersPerScreen,
            writingMode: options.vnWritingMode ?? 'horizontal-tb',
            screenInlineOverflowCharacters: options.screenInlineOverflowCharacters ?? 0,
        },
        fonts: { ready: Promise.resolve() },
        readyState: 'loading',
        baseURI: 'https://example.invalid/chapter.xhtml',
        createDocumentFragment() {
            return new TestFragment();
        },
        createTextNode(text) {
            const node = new TestText(text);
            node.ownerDocument = document;
            return node;
        },
        createElement(tagName) {
            const element = new TestElement(tagName);
            element.ownerDocument = document;
            return element;
        },
        createRange() {
            return new TestRange(document);
        },
        createTreeWalker(root, _whatToShow, filter) {
            const nodes = [];
            const visit = (node) => {
                if (node.nodeType === 3) {
                    if (!filter || filter.acceptNode(node) === 1) nodes.push(node);
                }
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
            return documentElement.querySelector(selector);
        },
        querySelectorAll(selector) {
            return documentElement.querySelectorAll(selector);
        },
        getElementById(id) {
            return findElementById(documentElement, id);
        },
        getElementsByName(name) {
            return querySelectorAll(documentElement, '[name]').filter((node) => node.getAttribute('name') === name);
        },
        addEventListener() {},
    };
    assignOwnerDocument(documentElement, document);
    return document;
}

function loadReader(body, options = {}) {
    const document = buildDocument(body, options);
    const restoreMessages = [];
    const imageMessages = [];
    const sasayakiHighlights = [];
    const timers = [];
    const imageBridge = {
        postMessage(message) {
            imageMessages.push(message);
        },
    };
    const window = {
        addEventListener() {},
        innerHeight: 800,
        innerWidth: 480,
        scrollX: 0,
        scrollY: 0,
        scrollTo() {},
        getComputedStyle(target) {
            const vnWritingMode = options.vnWritingMode ?? 'horizontal-tb';
            let writingMode = 'horizontal-tb';
            if (target === document.body) {
                writingMode = options.bodyWritingMode ?? 'vertical-rl';
            } else if (
                target.classList?.contains('hoshi-vn-stage') ||
                target.classList?.contains('hoshi-vn-screen') ||
                target.classList?.contains('hoshi-vn-content')
            ) {
                writingMode = vnWritingMode;
            }
            return {
                writingMode,
                getPropertyValue(name) {
                    return target.style?.getPropertyValue(name) ?? '';
                },
            };
        },
        HoshiReaderRestore: {
            postMessage(message) {
                restoreMessages.push(message);
            },
        },
        HoshiReaderImage: imageBridge,
        hoshiReaderPopupHost: {
            renderSasayakiHighlight(payload) {
                sasayakiHighlights.push(payload);
            },
            clearSasayakiHighlight() {
                sasayakiHighlights.push(null);
            },
        },
    };
    vm.runInNewContext(configuredReaderSource(options), {
        document,
        window,
        HoshiReaderImage: imageBridge,
        Node: { ELEMENT_NODE: 1, TEXT_NODE: 3, DOCUMENT_FRAGMENT_NODE: 11 },
        NodeFilter: { SHOW_TEXT: 4, FILTER_ACCEPT: 1, FILTER_REJECT: 2 },
        setTimeout(callback, delay) {
            timers.push({ callback, delay });
            return timers.length;
        },
        clearTimeout(id) {
            timers[id - 1] = null;
        },
        requestAnimationFrame(callback) {
            callback();
            return 0;
        },
        URL,
    });
    return { reader: window.hoshiReader, document, restoreMessages, timers, imageMessages, sasayakiHighlights, window };
}

async function initializeReader(body, options = {}) {
    const loaded = loadReader(body, options);
    await loaded.reader.initialize();
    await Promise.resolve();
    return loaded;
}

function p(text, attributes = {}) {
    const paragraph = new TestElement('p');
    Object.entries(attributes).forEach(([key, value]) => paragraph.setAttribute(key, value));
    paragraph.appendChild(new TestText(text));
    return paragraph;
}

function element(tagName, attributes = {}, children = []) {
    const node = new TestElement(tagName);
    Object.entries(attributes).forEach(([key, value]) => node.setAttribute(key, value));
    children.forEach((child) => node.appendChild(typeof child === 'string' ? new TestText(child) : child));
    return node;
}

function paragraphWith(...children) {
    return element('p', {}, children);
}

function rubyText(base, annotation) {
    return element('ruby', {}, [
        base,
        element('rt', {}, [annotation]),
    ]);
}

function image(src, attributes = {}) {
    const img = new TestElement('img');
    img.setAttribute('src', src);
    Object.entries(attributes).forEach(([key, value]) => img.setAttribute(key, value));
    img.src = src;
    img.currentSrc = src;
    img.complete = true;
    img.naturalWidth = 320;
    img.naturalHeight = 240;
    return img;
}

function svgImage(src, attributes = {}) {
    const svg = element('svg', attributes, [element('image', {}, [])]);
    const inner = svg.querySelector('image');
    inner.setAttribute('href', src);
    inner.href = { baseVal: src };
    return svg;
}

function imageBlock(src, attributes = {}) {
    const paragraph = new TestElement('p');
    Object.entries(attributes).forEach(([key, value]) => paragraph.setAttribute(key, value));
    paragraph.appendChild(image(src));
    return paragraph;
}

function bodyWith(...children) {
    const body = new TestElement('body');
    children.forEach((child) => body.appendChild(child));
    return body;
}

function currentScreen(reader) {
    return reader.stage.querySelector('.hoshi-vn-screen');
}

function sasayakiWrappers(reader) {
    return currentScreen(reader).querySelectorAll('.hoshi-sasayaki-cue');
}

test('visual novel reader asset defines the expected public surface', () => {
    const body = bodyWith(p('本文。'));
    const { reader } = loadReader(body);

    [
        'initialize',
        'paginate',
        'calculateProgress',
        'restoreProgress',
        'jumpToFragment',
        'buildNodeOffsets',
        'countChars',
        'countRawChars',
        'isMatchableChar',
        'applySasayakiCues',
        'highlightSasayakiCue',
        'sasayakiMediaStopsBeforeCue',
        'sasayakiMediaStopsToChapterEnd',
        'showSasayakiMediaStop',
        'clearSasayakiCue',
        'refreshSasayakiCuePresentation',
        'setNativeSelectionActive',
    ].forEach((name) => {
        assert.equal(typeof reader[name], 'function', name);
    });
    assert.equal(typeof reader.nodeStartOffsets.get, 'function');
    assert.equal(typeof reader.nodeStartRawOffsets.get, 'function');
});

test('visual novel reader requires the shared text semantics asset', async () => {
    const { reader } = loadReader(bodyWith(p('本文。')), { textSemanticsScript: '' });

    await assert.rejects(() => reader.initialize(), /hoshiReaderTextSemantics/);
});

test('visual novel reader requires the VN content stream asset', async () => {
    const { reader } = loadReader(bodyWith(p('本文。')), { contentStreamScript: '' });

    await assert.rejects(() => reader.initialize(), /hoshiReaderVnContentStream/);
});

test('visual novel reader requires the VN range map asset', async () => {
    const { reader } = loadReader(bodyWith(p('本文。')), { rangeMapScript: '' });

    await assert.rejects(() => reader.initialize(), /hoshiReaderVnRangeMap/);
});

test('visual novel reader uses shared text semantics', () => {
    const { reader, window } = loadReader(bodyWith(p('本文。')));
    const originalCountChars = window.hoshiReaderTextSemantics.countChars;
    let countCalls = 0;
    window.hoshiReaderTextSemantics.countChars = (text) => {
        countCalls += 1;
        return originalCountChars(text);
    };

    assert.equal(reader.countChars('一、二'), 2);
    assert.equal(countCalls, 1);
});

test('block mode renders one top-level block per screen without cloning the entire chapter', async () => {
    const body = bodyWith(p('第一段落。'), p('第二段落。'));
    const { reader } = await initializeReader(body, { mode: 'block', revealSpeed: 0 });

    assert.equal(currentScreen(reader).textContent, '第一段落。');

    assert.equal(reader.paginate('forward'), 'scrolled');
    assert.equal(currentScreen(reader).textContent, '第二段落。');
    assert.equal(currentScreen(reader).textContent.includes('第一段落。'), false);
});

test('block mode preserves ruby annotations while indexing only base text', async () => {
    const body = bodyWith(paragraphWith('夜', rubyText('星', 'ほし'), '。'));
    const { reader } = await initializeReader(body, { mode: 'block', revealSpeed: 0 });
    const screen = currentScreen(reader);
    const ruby = screen.querySelector('ruby');
    const rt = screen.querySelector('rt');

    assert.notEqual(ruby, null);
    assert.notEqual(rt, null);
    const rubyTextNodes = collectTextNodes(ruby);
    assert.equal(rt.textContent, 'ほし');
    assert.equal(reader.totalChapterChars, 2);
    assert.equal(reader.nodeStartOffsets.get(rubyTextNodes.find((node) => node.textContent === '星')), 1);
    assert.equal(reader.nodeStartOffsets.get(rubyTextNodes.find((node) => node.textContent === 'ほし')), undefined);
});

test('block mode builds source positions without scanning every text entry for every block', async () => {
    const paragraphs = Array.from({ length: 40 }, (_, index) => p(`段落${index}。`));
    const { reader } = loadReader(bodyWith(...paragraphs), {
        mode: 'block',
        revealSpeed: 0,
        charactersPerScreen: 1000,
    });
    const originalIsDescendantOf = reader.isDescendantOf.bind(reader);
    let descendantChecks = 0;
    reader.isDescendantOf = (node, root) => {
        descendantChecks += 1;
        return originalIsDescendantOf(node, root);
    };

    await reader.initialize();

    assert.equal(reader.baseScreens.length, paragraphs.length);
    assert.ok(
        descendantChecks <= paragraphs.length * 2,
        `expected source position lookup to avoid block x text-node scans, got ${descendantChecks}`,
    );
});

test('viewport text item lookup does not rescan every chapter character for every split screen', () => {
    const { reader } = loadReader(bodyWith(p('あ'.repeat(240))), {
        mode: 'block',
        revealSpeed: 0,
        charactersPerScreen: 12,
    });
    reader.detachChapterSource();
    reader.ensureStage();
    reader.buildSourceIndexes();
    reader.viewportFitTextItems = reader.buildTextItems();
    const allItems = reader.viewportFitTextItems;
    let fullArrayFilterScans = 0;
    allItems.filter = function(predicate) {
        fullArrayFilterScans += this.length;
        return Array.prototype.filter.call(this, predicate);
    };

    for (let index = 0; index < 24; index++) {
        const items = reader.textItemsForScreen({
            startRawCount: index * 10,
            endRawCount: index * 10 + 10,
        });
        assert.equal(items.length, 10);
    }

    assert.ok(
        fullArrayFilterScans <= allItems.length,
        `expected range lookup to avoid repeated full-array scans, got ${fullArrayFilterScans}`,
    );
});

test('viewport fitting skips precise range layout when scroll bounds already fit', () => {
    const { reader } = loadReader(bodyWith(p('seed')), {
        mode: 'block',
        revealSpeed: 0,
        charactersPerScreen: 1000,
    });
    reader.ensureStage();
    let preciseRangeChecks = 0;
    reader.renderedTextFitsBounds = () => {
        preciseRangeChecks += 1;
        return true;
    };
    const screens = Array.from({ length: 20 }, (_, index) => ({
        startCharCount: index,
        endCharCount: index + 1,
        startRawCount: index,
        endRawCount: index + 1,
        ids: new Set(),
        splittable: true,
        render: () => {
            const fragment = new TestFragment();
            fragment.appendChild(new TestText('短'));
            return fragment;
        },
    }));

    const fitted = reader.fitScreensToViewport(screens);

    assert.equal(fitted.length, screens.length);
    assert.equal(preciseRangeChecks, 0);
});

test('viewport fitting rejects vertical screens that overflow the content clipping box', () => {
    const { reader, document } = loadReader(bodyWith(p('seed')), {
        mode: 'block',
        revealSpeed: 0,
        vnWritingMode: 'vertical-rl',
    });
    const root = document.createElement('div');
    root.className = 'hoshi-vn-screen';
    const content = document.createElement('div');
    content.className = 'hoshi-vn-content';
    root.appendChild(content);
    Object.defineProperties(root, {
        clientWidth: { value: 384 },
        clientHeight: { value: 834 },
        scrollWidth: { value: 400 },
        scrollHeight: { value: 834 },
    });
    Object.defineProperties(content, {
        clientWidth: { value: 353 },
        clientHeight: { value: 768 },
        scrollWidth: { value: 385 },
        scrollHeight: { value: 768 },
    });
    let preciseRangeChecks = 0;
    reader.renderedTextFitsBounds = () => {
        preciseRangeChecks += 1;
        return false;
    };
    const screen = reader.screenDescriptor({
        startCharCount: 0,
        endCharCount: 4,
        startRawCount: 0,
        endRawCount: 4,
        splittable: true,
        render: () => {
            const fragment = new TestFragment();
            fragment.appendChild(new TestText('本文'));
            return fragment;
        },
    });

    assert.equal(reader.measureScreenFits(screen, { root, content }), false);
    assert.equal(preciseRangeChecks, 1);
});

test('block mode splits a chapter wrapper into child block screens', async () => {
    const wrapper = new TestElement('section');
    wrapper.setAttribute('id', 'chapter');
    wrapper.appendChild(p('第一段落。', { id: 'p1' }));
    wrapper.appendChild(p('第二段落。', { id: 'p2' }));
    wrapper.appendChild(p('第三段落。', { id: 'p3' }));
    const { reader } = await initializeReader(bodyWith(wrapper), { mode: 'block', revealSpeed: 0 });

    assert.equal(currentScreen(reader).textContent, '第一段落。');
    assert.equal(reader.screenIndexForFragment('chapter'), 0);
    assert.equal(reader.screenIndexForFragment('p2'), 1);

    assert.equal(reader.paginate('forward'), 'scrolled');
    assert.equal(currentScreen(reader).textContent, '第二段落。');
});

test('block mode splits oversized text blocks to keep every part reachable', async () => {
    const body = bodyWith(p('一二三四五六七八九十。', { id: 'long' }), p('次段落。'));
    const { reader } = await initializeReader(body, {
        mode: 'block',
        revealSpeed: 0,
        charactersPerScreen: 4,
    });

    assert.equal(currentScreen(reader).textContent, '一二三四');
    assert.equal(reader.calculateProgress(), 4 / reader.totalChapterChars);
    assert.equal(reader.screenIndexForFragment('long'), 0);

    assert.equal(reader.paginate('forward'), 'scrolled');
    assert.equal(currentScreen(reader).textContent, '五六七八');

    await reader.restoreProgress(6 / reader.totalChapterChars);
    assert.equal(currentScreen(reader).textContent, '五六七八');

    assert.equal(reader.paginate('forward'), 'scrolled');
    assert.equal(currentScreen(reader).textContent, '九十。');
    assert.equal(reader.paginate('forward'), 'scrolled');
    assert.equal(currentScreen(reader).textContent, '次段落。');
});

test('block mode splits oversized text blocks that contain inline images', async () => {
    const marker = image('images/marker.png', { id: 'marker' });
    marker.naturalWidth = 48;
    marker.naturalHeight = 48;
    const body = bodyWith(element('blockquote', { id: 'quote' }, ['一二', marker, '三四五六']));
    const { reader } = await initializeReader(body, {
        mode: 'block',
        revealSpeed: 0,
        charactersPerScreen: 4,
    });

    assert.equal(currentScreen(reader).textContent, '一二三四');
    assert.equal(currentScreen(reader).querySelector('#marker').getAttribute('src'), 'images/marker.png');
    assert.equal(reader.screenIndexForFragment('quote'), 0);

    assert.equal(reader.paginate('forward'), 'scrolled');
    assert.equal(currentScreen(reader).textContent, '五六');
    assert.equal(currentScreen(reader).querySelector('#marker'), null);
});

test('block mode keeps leading inline images with the first split text screen', async () => {
    const marker = image('images/marker.png', { id: 'marker' });
    marker.naturalWidth = 48;
    marker.naturalHeight = 48;
    const body = bodyWith(element('blockquote', { id: 'quote' }, [marker, '一二三四五六']));
    const { reader } = await initializeReader(body, {
        mode: 'block',
        revealSpeed: 0,
        charactersPerScreen: 4,
    });

    assert.equal(currentScreen(reader).textContent, '一二三四');
    assert.equal(currentScreen(reader).querySelector('#marker').getAttribute('src'), 'images/marker.png');

    assert.equal(reader.paginate('forward'), 'scrolled');
    assert.equal(currentScreen(reader).textContent, '五六');
    assert.equal(currentScreen(reader).querySelector('#marker'), null);
});

test('block mode fitting avoids source tree scans for ruby-only split blocks', async () => {
    const children = [];
    for (let i = 0; i < 80; i += 1) {
        children.push(rubyText('漢', 'かん'));
        if (i % 20 === 19) children.push('。');
    }
    const { reader } = loadReader(bodyWith(element('p', { id: 'long' }, children)), {
        mode: 'block',
        revealSpeed: 0,
        charactersPerScreen: 40,
    });
    let preorderLookups = 0;
    const originalSourcePreorderForNode = reader.sourcePreorderForNode;
    reader.sourcePreorderForNode = function(node) {
        preorderLookups += 1;
        return originalSourcePreorderForNode.call(this, node);
    };

    await reader.initialize();

    assert.equal(reader.screens.length, 6);
    assert.ok(
        preorderLookups < 12000,
        `expected bounded preorder lookups while fitting ruby-only blocks, got ${preorderLookups}`,
    );
});

test('viewport fitting keeps ruby roots atomic when splitting oversized VN screens', async () => {
    const body = bodyWith(paragraphWith('一', rubyText('二三', 'にさん'), '四五'));
    const { reader } = await initializeReader(body, {
        mode: 'block',
        revealSpeed: 0,
        charactersPerScreen: 2,
    });

    assert.equal(reader.totalChapterChars, 5);
    assert.equal(currentScreen(reader).textContent, '一');
    assert.equal(currentScreen(reader).querySelectorAll('ruby').length, 0);

    assert.equal(reader.paginate('forward'), 'scrolled');
    const rubyScreen = currentScreen(reader);
    const ruby = rubyScreen.querySelector('ruby');
    assert.equal(rubyScreen.querySelectorAll('ruby').length, 1);
    assert.deepEqual(collectTextNodes(ruby).map((node) => node.textContent), ['二三', 'にさん']);
    assert.equal(rubyScreen.querySelector('rt').textContent, 'にさん');
    assert.equal(reader.calculateProgress(), 3 / 5);

    assert.equal(reader.paginate('forward'), 'scrolled');
    assert.equal(currentScreen(reader).textContent, '四五');
    assert.equal(currentScreen(reader).querySelectorAll('ruby').length, 0);

    await reader.restoreProgress(2 / reader.totalChapterChars);
    assert.equal(currentScreen(reader).querySelectorAll('ruby').length, 1);
    assert.deepEqual(collectTextNodes(currentScreen(reader).querySelector('ruby')).map((node) => node.textContent), ['二三', 'にさん']);
});

test('block mode splits oversized vertical writing blocks against the VN content bounds', async () => {
    const body = bodyWith(p('一二三四五六'));
    const { reader } = await initializeReader(body, {
        mode: 'block',
        revealSpeed: 0,
        bodyWritingMode: 'vertical-rl',
        vnWritingMode: 'vertical-rl',
        charactersPerScreen: 3,
    });

    assert.equal(currentScreen(reader).textContent, '一二三');
    assert.equal(reader.paginate('forward'), 'scrolled');
    assert.equal(currentScreen(reader).textContent, '四五六');
});

test('block mode keeps trailing Japanese punctuation in the same vertical screen when it fits the visible bounds', async () => {
    const body = bodyWith(p('「小柳さんは、お父さんとお母さん、どっちが来てるの？」'));
    const { reader } = await initializeReader(body, {
        mode: 'block',
        revealSpeed: 0,
        bodyWritingMode: 'vertical-rl',
        vnWritingMode: 'vertical-rl',
        charactersPerScreen: 26,
        screenInlineOverflowCharacters: 1,
    });

    assert.equal(currentScreen(reader).textContent, '「小柳さんは、お父さんとお母さん、どっちが来てるの？」');
    assert.equal(reader.paginate('forward'), 'limit');
});

test('sentence mode splits an oversized sentence after applying sentence grouping', async () => {
    const body = bodyWith(p('一二三四五六'), p('次。'));
    const { reader } = await initializeReader(body, {
        mode: 'sentences',
        sentencesPerScreen: 1,
        revealSpeed: 0,
        charactersPerScreen: 3,
    });

    assert.equal(currentScreen(reader).textContent, '一二三');
    assert.equal(reader.paginate('forward'), 'scrolled');
    assert.equal(currentScreen(reader).textContent, '四五六');
    assert.equal(reader.paginate('forward'), 'scrolled');
    assert.equal(currentScreen(reader).textContent, '次。');
});

test('sentence mode groups sentences by configured count', async () => {
    const body = bodyWith(p('一。二！三？四。'));
    const { reader } = await initializeReader(body, {
        mode: 'sentences',
        sentencesPerScreen: 2,
        revealSpeed: 0,
    });

    assert.equal(currentScreen(reader).textContent, '一。二！');

    assert.equal(reader.paginate('forward'), 'scrolled');
    assert.equal(currentScreen(reader).textContent, '三？四。');
});

test('sentence mode preserves ruby annotations after reveal completion', async () => {
    const body = bodyWith(paragraphWith(rubyText('星', 'ほし'), '。次。'));
    const { reader } = await initializeReader(body, {
        mode: 'sentences',
        sentencesPerScreen: 1,
        revealSpeed: 10,
    });

    assert.equal(reader.totalChapterChars, 2);
    assert.equal(reader.calculateProgress(), 1 / 2);
    assert.equal(reader.paginate('forward'), 'revealed');
    assert.equal(currentScreen(reader).querySelector('rt').textContent, 'ほし');
    assert.equal(currentScreen(reader).querySelectorAll('[data-hoshi-visual-novel-unrevealed]').length, 0);

    assert.equal(reader.paginate('forward'), 'scrolled');
    assert.equal(currentScreen(reader).textContent, '次。');
});

test('sentence mode keeps media-only blocks as standalone visual novel screens', async () => {
    const body = bodyWith(p('一。'), imageBlock('images/cover.jpg', { id: 'cover' }), p('二。'));
    const { reader } = await initializeReader(body, {
        mode: 'sentences',
        sentencesPerScreen: 1,
        revealSpeed: 0,
    });

    assert.equal(currentScreen(reader).textContent, '一。');
    assert.equal(reader.paginate('forward'), 'scrolled');
    assert.equal(currentScreen(reader).querySelector('img').getAttribute('src'), 'images/cover.jpg');
    assert.equal(currentScreen(reader).querySelector('#cover').id, 'cover');
    assert.equal(reader.screenIndexForFragment('cover'), 1);
    assert.equal(reader.paginate('forward'), 'scrolled');
    assert.equal(currentScreen(reader).textContent, '二。');
});

test('block mode splits consecutive media in one media-only block into independent screens', async () => {
    const gallery = element('p', { id: 'gallery' }, [
        '\n  ',
        image('images/one.jpg', { id: 'one' }),
        '\n  ',
        image('images/two.jpg', { id: 'two' }),
        '\n',
    ]);
    const { reader } = await initializeReader(bodyWith(gallery), { mode: 'block', revealSpeed: 0 });

    assert.equal(currentScreen(reader).querySelectorAll('img').length, 1);
    assert.equal(currentScreen(reader).querySelector('#one').getAttribute('src'), 'images/one.jpg');
    assert.equal(reader.screenIndexForFragment('gallery'), 0);
    assert.equal(reader.screenIndexForFragment('one'), 0);
    assert.equal(reader.screenIndexForFragment('two'), 1);

    assert.equal(reader.paginate('forward'), 'scrolled');
    assert.equal(currentScreen(reader).querySelectorAll('img').length, 1);
    assert.equal(currentScreen(reader).querySelector('#two').getAttribute('src'), 'images/two.jpg');
    assert.equal(reader.paginate('forward'), 'limit');
});

test('block mode keeps nested media-only block wrappers without cloning sibling chapter text', async () => {
    const gallery = element('p', { id: 'gallery', class: 'gallery' }, [
        image('images/one.jpg', { id: 'one' }),
        image('images/two.jpg', { id: 'two' }),
    ]);
    const chapter = element('section', { id: 'chapter' }, [
        p('前。'),
        gallery,
        p('後。'),
    ]);
    const { reader } = await initializeReader(bodyWith(chapter), { mode: 'block', revealSpeed: 0 });

    assert.equal(currentScreen(reader).textContent, '前。');
    assert.equal(reader.paginate('forward'), 'scrolled');
    assert.equal(currentScreen(reader).textContent, '');
    assert.notEqual(currentScreen(reader).querySelector('.gallery'), null);
    assert.equal(currentScreen(reader).querySelectorAll('img').length, 1);
    assert.equal(currentScreen(reader).querySelector('#one').getAttribute('src'), 'images/one.jpg');
    assert.equal(currentScreen(reader).querySelector('#two'), null);
    assert.equal(reader.screenIndexForFragment('chapter'), 0);
    assert.equal(reader.screenIndexForFragment('gallery'), 1);

    assert.equal(reader.paginate('forward'), 'scrolled');
    assert.equal(currentScreen(reader).querySelector('#two').getAttribute('src'), 'images/two.jpg');
    assert.equal(currentScreen(reader).querySelector('#one'), null);
    assert.equal(reader.paginate('forward'), 'scrolled');
    assert.equal(currentScreen(reader).textContent, '後。');
});

test('sentence mode keeps consecutive media-only images ordered between text screens', async () => {
    const gallery = element('p', {}, [
        image('images/one.jpg', { id: 'one' }),
        image('images/two.jpg', { id: 'two' }),
    ]);
    const body = bodyWith(p('一。'), gallery, p('二。'));
    const { reader } = await initializeReader(body, {
        mode: 'sentences',
        sentencesPerScreen: 1,
        revealSpeed: 0,
    });

    assert.equal(currentScreen(reader).textContent, '一。');
    assert.equal(reader.paginate('forward'), 'scrolled');
    assert.equal(currentScreen(reader).querySelector('#one').getAttribute('src'), 'images/one.jpg');
    assert.equal(reader.paginate('forward'), 'scrolled');
    assert.equal(currentScreen(reader).querySelector('#two').getAttribute('src'), 'images/two.jpg');
    assert.equal(reader.paginate('forward'), 'scrolled');
    assert.equal(currentScreen(reader).textContent, '二。');
});

test('visual novel restore keeps distinct progress for consecutive media screens', async () => {
    const gallery = element('p', {}, [
        image('images/one.jpg', { id: 'one' }),
        image('images/two.jpg', { id: 'two' }),
    ]);
    const body = bodyWith(gallery, p('後。'));
    const { reader } = await initializeReader(body, {
        mode: 'sentences',
        sentencesPerScreen: 1,
        revealSpeed: 0,
    });

    assert.equal(currentScreen(reader).querySelector('#one').getAttribute('src'), 'images/one.jpg');
    const firstImageProgress = reader.calculateProgress();
    assert.equal(reader.paginate('forward'), 'scrolled');
    assert.equal(currentScreen(reader).querySelector('#two').getAttribute('src'), 'images/two.jpg');
    const secondImageProgress = reader.calculateProgress();
    assert.notEqual(secondImageProgress, firstImageProgress);

    assert.equal(reader.paginate('forward'), 'scrolled');
    assert.equal(currentScreen(reader).textContent, '後。');
    assert.equal(reader.paginate('backward'), 'scrolled');
    assert.equal(currentScreen(reader).querySelector('#two').getAttribute('src'), 'images/two.jpg');

    await reader.restoreProgress(secondImageProgress);
    assert.equal(currentScreen(reader).querySelector('#two').getAttribute('src'), 'images/two.jpg');
    await reader.restoreProgress(firstImageProgress);
    assert.equal(currentScreen(reader).querySelector('#one').getAttribute('src'), 'images/one.jpg');
});

test('visual novel restoreProgress one lands on the last screen in an image-only chapter', async () => {
    const gallery = element('div', { class: 'main' }, [
        element('div', { class: 'align-center' }, [
            element('p', {}, [image('images/one.jpg', { id: 'one' })]),
        ]),
        element('div', { class: 'align-center' }, [
            element('p', {}, [image('images/two.jpg', { id: 'two' })]),
        ]),
    ]);
    const { reader } = await initializeReader(bodyWith(gallery), {
        mode: 'block',
        revealSpeed: 0,
    });

    assert.equal(reader.totalChapterChars, 0);
    assert.equal(currentScreen(reader).querySelector('#one').getAttribute('src'), 'images/one.jpg');
    assert.equal(reader.calculateProgress(), 0);
    assert.equal(reader.paginate('forward'), 'scrolled');
    assert.equal(currentScreen(reader).querySelector('#two').getAttribute('src'), 'images/two.jpg');
    assert.equal(reader.calculateProgress(), 1);
    assert.equal(reader.screenIndexForProgress(reader.calculateProgress()), 1);

    await reader.restoreProgress(1);

    assert.equal(currentScreen(reader).querySelector('#two').getAttribute('src'), 'images/two.jpg');
});

test('sentence mode splits nested chapter wrapper text and consecutive media screens', async () => {
    const chapter = element('section', { id: 'chapter' }, [
        p('一。'),
        element('p', {}, [
            '\n',
            image('images/one.jpg', { id: 'one' }),
            '\n',
            image('images/two.jpg', { id: 'two' }),
            '\n',
        ]),
        p('二。'),
    ]);
    const { reader } = await initializeReader(bodyWith(chapter), {
        mode: 'sentences',
        sentencesPerScreen: 1,
        revealSpeed: 0,
    });

    assert.equal(currentScreen(reader).textContent, '一。');
    assert.equal(reader.paginate('forward'), 'scrolled');
    assert.equal(currentScreen(reader).querySelector('#one').getAttribute('src'), 'images/one.jpg');
    assert.equal(currentScreen(reader).textContent.trim(), '');
    assert.equal(reader.paginate('forward'), 'scrolled');
    assert.equal(currentScreen(reader).querySelector('#two').getAttribute('src'), 'images/two.jpg');
    assert.equal(currentScreen(reader).textContent.trim(), '');
    assert.equal(reader.paginate('forward'), 'scrolled');
    assert.equal(currentScreen(reader).textContent, '二。');
});

test('visual novel media stream keeps svg as media and gaiji inline', async () => {
    const body = bodyWith(
        paragraphWith('一', image('images/gaiji.png', { class: 'gaiji', id: 'gaiji' }), '二。'),
        element('p', { id: 'svg-block' }, [svgImage('images/plate.jpg', { id: 'plate' })]),
        p('三。'),
    );
    const { reader } = await initializeReader(body, { mode: 'block', revealSpeed: 0 });

    assert.equal(currentScreen(reader).textContent, '一二。');
    assert.equal(currentScreen(reader).querySelector('#gaiji').getAttribute('src'), 'images/gaiji.png');
    assert.equal(reader.paginate('forward'), 'scrolled');
    assert.equal(currentScreen(reader).querySelector('#plate').tagName, 'SVG');
    assert.equal(reader.screenIndexForFragment('svg-block'), 1);
    assert.equal(reader.paginate('forward'), 'scrolled');
    assert.equal(currentScreen(reader).textContent, '三。');
});

test('sentence mode can preserve Japanese dialogue bracket bubbles', async () => {
    const body = bodyWith(p('「一。二。」三。'));
    const { reader } = await initializeReader(body, {
        mode: 'sentence',
        sentencesPerScreen: 1,
        preserveDialogue: true,
        revealSpeed: 0,
    });

    assert.equal(currentScreen(reader).textContent, '「一。二。」');

    assert.equal(reader.paginate('forward'), 'scrolled');
    assert.equal(currentScreen(reader).textContent, '三。');
});

test('reveal speed zero renders the current screen fully immediately', async () => {
    const body = bodyWith(p('即時表示。'));
    const { reader } = await initializeReader(body, { revealSpeed: 0 });

    assert.equal(currentScreen(reader).textContent, '即時表示。');
    assert.equal(currentScreen(reader).querySelectorAll('[data-hoshi-visual-novel-unrevealed]').length, 0);
});

test('visual novel screen wraps current content in a stable content container', async () => {
    const body = bodyWith(p('中央に置く。'));
    const { reader } = await initializeReader(body, { revealSpeed: 0 });
    const screen = currentScreen(reader);

    assert.equal(screen.firstChild.classList.contains('hoshi-vn-content'), true);
    assert.equal(screen.firstChild.textContent, '中央に置く。');
});

test('forward pagination completes an unfinished reveal before changing screens', async () => {
    const body = bodyWith(p('隠れた文。'), p('次の文。'));
    const { reader } = await initializeReader(body, { revealSpeed: 10 });

    assert.equal(currentScreen(reader).querySelectorAll('[data-hoshi-visual-novel-unrevealed]').length > 0, true);
    assert.equal(reader.paginate('forward'), 'revealed');
    assert.equal(currentScreen(reader).textContent, '隠れた文。');
    assert.equal(currentScreen(reader).querySelectorAll('[data-hoshi-visual-novel-unrevealed]').length, 0);

    assert.equal(reader.paginate('forward'), 'scrolled');
    assert.equal(currentScreen(reader).textContent, '次の文。');
});

test('visual novel reveal speed progressively reveals characters faster at higher values', async () => {
    const slow = await initializeReader(bodyWith(p('一二三。')), { revealSpeed: 45 });
    const fast = await initializeReader(bodyWith(p('一二三。')), { revealSpeed: 120 });

    assert.equal(slow.timers[0].delay > fast.timers[0].delay, true);
    slow.timers[0].callback();

    const textNodes = collectTextNodes(currentScreen(slow.reader));
    assert.equal(textNodes[0].textContent, '一');
    assert.equal(textNodes[1].parentElement.hasAttribute('data-hoshi-visual-novel-unrevealed'), true);
    assert.equal(textNodes[1].textContent, '二三。');
});

test('visual novel reveal speed updates active reveal without reloading the screen', async () => {
    const { reader, timers } = await initializeReader(bodyWith(p('一二三。')), { revealSpeed: 10 });

    assert.equal(timers[0].delay, 100);

    reader.setRevealSpeed(50);

    assert.equal(reader.revealSpeed, 50);
    assert.equal(timers[0], null);
    assert.equal(timers[1].delay, 20);
    assert.equal(currentScreen(reader).textContent, '一二三。');
});

test('visual novel image setup preserves blur and native image tap behavior', async () => {
    const body = bodyWith(imageBlock('images/pic.jpg'));
    const { reader, imageMessages } = await initializeReader(body, {
        blurImages: true,
        revealSpeed: 0,
    });
    const img = currentScreen(reader).querySelector('img');

    assert.equal(img.classList.contains('block-img'), true);
    assert.equal(img.classList.contains('blurred'), true);
    assert.equal(img.parentElement.classList.contains('blur-wrapper'), true);

    const click = {
        type: 'click',
        preventDefault() {},
        stopPropagation() {},
    };
    img.dispatchEvent(click);
    assert.equal(img.classList.contains('blurred'), false);
    assert.equal(imageMessages.length, 0);

    img.dispatchEvent(click);
    assert.equal(JSON.stringify(imageMessages), JSON.stringify(['https://example.invalid/images/pic.jpg']));
});

test('visual novel SVG image setup preserves aspect ratio and native image tap behavior', async () => {
    const body = bodyWith(element('p', {}, [
        svgImage('images/plate.jpg', { id: 'plate', preserveAspectRatio: 'none' }),
    ]));
    const { reader, imageMessages } = await initializeReader(body, {
        mode: 'block',
        revealSpeed: 0,
    });
    const svg = currentScreen(reader).querySelector('#plate');
    const innerImage = svg.querySelector('image');

    assert.equal(svg.getAttribute('preserveAspectRatio'), 'xMidYMid meet');

    innerImage.dispatchEvent({
        type: 'click',
        preventDefault() {},
        stopPropagation() {},
    });

    assert.equal(JSON.stringify(imageMessages), JSON.stringify(['https://example.invalid/images/plate.jpg']));
});

test('forward and backward pagination report limits at chapter edges', async () => {
    const body = bodyWith(p('前。'), p('後。'));
    const { reader } = await initializeReader(body, { revealSpeed: 0 });

    assert.equal(reader.paginate('backward'), 'limit');
    assert.equal(reader.paginate('forward'), 'scrolled');
    assert.equal(reader.paginate('forward'), 'limit');
    assert.equal(reader.paginate('backward'), 'scrolled');
    assert.equal(reader.paginate('backward'), 'limit');
});

test('progress increases monotonically as visual novel screens advance', async () => {
    const body = bodyWith(p('一二。'), p('三四。'), p('五六。'));
    const { reader } = await initializeReader(body, { revealSpeed: 0 });

    const first = reader.calculateProgress();
    assert.equal(reader.paginate('forward'), 'scrolled');
    const second = reader.calculateProgress();
    assert.equal(reader.paginate('forward'), 'scrolled');
    const third = reader.calculateProgress();

    assert.equal(first > 0, true);
    assert.equal(second > first, true);
    assert.equal(third > second, true);
    assert.equal(third, 1);
});

test('restoreProgress lands on the first screen whose end count reaches the target and renders it fully', async () => {
    const body = bodyWith(p('一二。'), p('三四。'), p('五六。'));
    const { reader } = await initializeReader(body, { revealSpeed: 10 });

    await reader.restoreProgress(0.5);

    assert.equal(currentScreen(reader).textContent, '三四。');
    assert.equal(currentScreen(reader).querySelectorAll('[data-hoshi-visual-novel-unrevealed]').length, 0);
});

test('jumpToFragment lands on the screen containing a matching id and renders it fully', async () => {
    const body = bodyWith(p('序。', { id: 'intro' }), p('目的地。', { id: 'target' }));
    const { reader } = await initializeReader(body, { revealSpeed: 10 });

    assert.equal(await reader.jumpToFragment('target'), true);

    assert.equal(currentScreen(reader).textContent, '目的地。');
    assert.equal(currentScreen(reader).querySelectorAll('[data-hoshi-visual-novel-unrevealed]').length, 0);
});

test('jumpToFragment lands on a media-only visual novel screen', async () => {
    const body = bodyWith(p('序。'), imageBlock('images/cover.jpg', { id: 'cover' }), p('本文。'));
    const { reader } = await initializeReader(body, { revealSpeed: 10 });

    assert.equal(await reader.jumpToFragment('cover'), true);

    assert.equal(currentScreen(reader).querySelector('#cover').id, 'cover');
    assert.equal(currentScreen(reader).querySelector('img').getAttribute('src'), 'images/cover.jpg');
    assert.equal(currentScreen(reader).querySelectorAll('[data-hoshi-visual-novel-unrevealed]').length, 0);
});

test('initial fragment on the first visual novel screen renders fully', async () => {
    const body = bodyWith(p('序。', { id: 'intro' }), p('次。'));
    const { reader } = await initializeReader(body, {
        initialFragment: 'intro',
        revealSpeed: 10,
    });

    assert.equal(currentScreen(reader).textContent, '序。');
    assert.equal(currentScreen(reader).querySelectorAll('[data-hoshi-visual-novel-unrevealed]').length, 0);
});

test('initial highlights are applied after rendering the restored visual novel screen', async () => {
    const body = bodyWith(p('前。'), p('対象。'));
    const highlight = { id: 'h1', color: 'yellow', offset: 2, text: '対象' };
    const highlightsScript = `
        window.hoshiHighlights = {
            wrappers: new Map(),
            applyHighlights(highlights) {
                this.appliedText = window.hoshiReader.screen.textContent;
                this.applied = highlights;
            }
        };
    `;
    const { window } = await initializeReader(body, {
        initialProgress: 0.75,
        initialHighlights: [highlight],
        highlightsScript,
        revealSpeed: 10,
    });

    assert.equal(window.hoshiHighlights.appliedText, '対象。');
    assert.equal(JSON.stringify(window.hoshiHighlights.applied), JSON.stringify([highlight]));
});

test('visual novel highlight segments use chapter-level raw offsets on later screens', async () => {
    const body = bodyWith(p('あ、い'), p('うえ'));
    const highlightsScript = 'window.hoshiHighlights = { wrappers: new Map(), applyHighlights() {} };';
    const { reader } = await initializeReader(body, { revealSpeed: 0, highlightsScript });

    assert.equal(reader.paginate('forward'), 'scrolled');
    reader.patchHighlightsForVisualNovel();

    const segments = reader.highlightSegmentsForChapterRawRange(3, 2);
    assert.equal(segments.length, 1);
    assert.equal(segments[0].node.textContent, 'うえ');
    assert.equal(segments[0].start, 0);
    assert.equal(segments[0].end, 2);
});

test('visual novel persisted highlights wrap only the visible raw range on each screen', async () => {
    const highlight = { id: 'h1', color: 'yellow', offset: 2, text: 'いう' };
    const { reader, window } = await initializeReader(bodyWith(p('あ、い'), p('うえ')), {
        revealSpeed: 0,
        highlightsScript: readerHighlightsSource(),
        initialHighlights: [highlight],
    });

    assert.deepEqual(
        currentScreen(reader).querySelectorAll('.hoshi-highlight').map((node) => node.textContent),
        ['い'],
    );

    assert.equal(reader.paginate('forward'), 'scrolled');
    assert.deepEqual(
        currentScreen(reader).querySelectorAll('.hoshi-highlight').map((node) => node.textContent),
        ['う'],
    );
    assert.equal(window.hoshiHighlights.wrappers.get('h1').length, 1);
});

test('visible node offsets remain chapter-level after rendering later screens', async () => {
    const body = bodyWith(p('あ、い'), p('うえ'));
    const { reader } = await initializeReader(body, { revealSpeed: 0 });

    assert.equal(reader.paginate('forward'), 'scrolled');

    const textNodes = collectTextNodes(currentScreen(reader));
    assert.equal(textNodes.length, 1);
    assert.equal(textNodes[0].textContent, 'うえ');
    assert.equal(reader.nodeStartOffsets.get(textNodes[0]), 2);
    assert.equal(reader.nodeStartRawOffsets.get(textNodes[0]), 3);
});

test('unrevealed text is omitted from node offsets until reveal completes', async () => {
    const body = bodyWith(p('未表示。'));
    const { reader } = await initializeReader(body, { revealSpeed: 10 });
    const hiddenText = collectTextNodes(currentScreen(reader))
        .find((node) => node.parentElement?.hasAttribute('data-hoshi-visual-novel-unrevealed'));

    assert.equal(reader.nodeStartOffsets.get(hiddenText), undefined);
    assert.equal(reader.paginate('forward'), 'revealed');

    const revealedText = collectTextNodes(currentScreen(reader))[0];
    assert.equal(reader.nodeStartOffsets.get(revealedText), 0);
});

test('visual novel Sasayaki wraps and activates a cue on the current screen', async () => {
    const cue = { id: 'cue', start: 0, length: 4 };
    const { reader } = await initializeReader(bodyWith(p('蒸し暑い')), { revealSpeed: 0 });

    reader.applySasayakiCues([cue]);
    const result = reader.highlightSasayakiCue(cue, false);

    const wrappers = sasayakiWrappers(reader);
    assert.equal(result, null);
    assert.equal(wrappers.length, 1);
    assert.equal(wrappers[0].classList.contains('hoshi-sasayaki-active'), true);
    assert.equal(wrappers[0].textContent, '蒸し暑い');
    assert.equal(reader.cueWrappers.get('cue')[0], wrappers[0]);
    assert.equal(reader.nodeStartOffsets.get(collectTextNodes(wrappers[0])[0]), 0);
});

test('visual novel Sasayaki range map normalizes string cue offsets', async () => {
    const cue = { id: 'cue', start: '1', length: '2' };
    const { reader } = await initializeReader(bodyWith(p('一二三四。')), { revealSpeed: 0 });

    reader.applySasayakiCues([cue]);
    reader.highlightSasayakiCue(cue, false);

    assert.equal(sasayakiWrappers(reader).length, 1);
    assert.equal(sasayakiWrappers(reader)[0].textContent, '二三');
});

test('visual novel Sasayaki cue includes punctuation between text nodes inside the same cue', async () => {
    const cue = { id: 'cue', start: 0, length: 5 };
    const paragraph = new TestElement('p');
    paragraph.appendChild(new TestText('古都'));
    paragraph.appendChild(new TestText('。'));
    paragraph.appendChild(new TestText('３年生'));
    const { reader } = await initializeReader(bodyWith(paragraph), { revealSpeed: 0 });

    reader.applySasayakiCues([cue]);
    reader.highlightSasayakiCue(cue, false);

    assert.equal(sasayakiWrappers(reader).map((wrapper) => wrapper.textContent).join(''), '古都。３年生');
});

test('visual novel Sasayaki reveal jumps to a later screen and returns progress', async () => {
    const cue = { id: 'cue', start: 1, length: 2 };
    const { reader } = await initializeReader(
        bodyWith(p('一。'), p('二三。'), p('四。')),
        { revealSpeed: 10 },
    );

    reader.applySasayakiCues([cue]);
    const result = reader.highlightSasayakiCue(cue, true);

    assert.equal(result, 0.75);
    assert.equal(currentScreen(reader).textContent, '二三。');
    assert.equal(currentScreen(reader).querySelectorAll('[data-hoshi-visual-novel-unrevealed]').length, 0);
    assert.equal(sasayakiWrappers(reader)[0].textContent, '二三');
    assert.equal(sasayakiWrappers(reader)[0].classList.contains('hoshi-sasayaki-active'), true);
});

test('visual novel Sasayaki media stop plan includes every standalone image screen before target cue', async () => {
    const cue = { id: 'cue', start: 1, length: 2 };
    const { reader } = await initializeReader(
        bodyWith(
            p('一。'),
            imageBlock('images/first.jpg', { id: 'first-image' }),
            imageBlock('images/second.jpg', { id: 'second-image' }),
            p('二三。'),
        ),
        {
            mode: 'sentences',
            sentencesPerScreen: 1,
            revealSpeed: 0,
        },
    );

    reader.applySasayakiCues([cue]);
    const stops = reader.sasayakiMediaStopsBeforeCue(cue);

    assert.deepEqual(
        Array.from(stops, (stop) => stop.screenIndex),
        [1, 2],
    );
    const firstStopProgress = reader.showSasayakiMediaStop(stops[0]);
    assert.equal(currentScreen(reader).querySelector('img').getAttribute('src'), 'images/first.jpg');
    const secondStopProgress = reader.showSasayakiMediaStop(stops[1]);
    assert.equal(currentScreen(reader).querySelector('img').getAttribute('src'), 'images/second.jpg');
    assert.equal(firstStopProgress > 1 / 3, true);
    assert.equal(secondStopProgress > firstStopProgress, true);
    assert.equal(secondStopProgress < 1, true);

    await reader.restoreProgress(firstStopProgress);
    assert.equal(currentScreen(reader).querySelector('img').getAttribute('src'), 'images/first.jpg');
    await reader.restoreProgress(secondStopProgress);
    assert.equal(currentScreen(reader).querySelector('img').getAttribute('src'), 'images/second.jpg');
});

test('visual novel Sasayaki media stop plan includes current image screen before target cue', async () => {
    const cue = { id: 'cue', start: 0, length: 2 };
    const { reader } = await initializeReader(
        bodyWith(
            imageBlock('images/opening.jpg', { id: 'opening-image' }),
            p('二三。'),
        ),
        {
            mode: 'sentences',
            sentencesPerScreen: 1,
            revealSpeed: 0,
        },
    );

    reader.applySasayakiCues([cue]);
    const stops = reader.sasayakiMediaStopsBeforeCue(cue);

    assert.deepEqual(
        Array.from(stops, (stop) => stop.screenIndex),
        [0],
    );
    assert.equal(reader.showSasayakiMediaStop(stops[0]), 0);
    assert.equal(currentScreen(reader).querySelector('img').getAttribute('src'), 'images/opening.jpg');
});

test('visual novel Sasayaki chapter-end media stops include an image-only current screen', async () => {
    const { reader } = await initializeReader(
        bodyWith(imageBlock('images/cover.jpg', { id: 'cover' })),
        {
            mode: 'sentences',
            sentencesPerScreen: 1,
            revealSpeed: 0,
        },
    );

    const stops = reader.sasayakiMediaStopsToChapterEnd();

    assert.deepEqual(
        Array.from(stops, (stop) => stop.screenIndex),
        [0],
    );
});

test('visual novel Sasayaki reveal jumps to the visible split screen inside an oversized block', async () => {
    const cue = { id: 'cue', start: 8, length: 2 };
    const { reader } = await initializeReader(
        bodyWith(p('一二三四五六七八九十。'), p('次段落。')),
        {
            mode: 'block',
            revealSpeed: 10,
            charactersPerScreen: 4,
        },
    );

    reader.applySasayakiCues([cue]);
    const result = reader.highlightSasayakiCue(cue, true);

    assert.equal(result, 10 / reader.totalChapterChars);
    assert.equal(currentScreen(reader).textContent, '九十。');
    assert.equal(currentScreen(reader).querySelectorAll('[data-hoshi-visual-novel-unrevealed]').length, 0);
    assert.equal(sasayakiWrappers(reader)[0].textContent, '九十');
    assert.equal(sasayakiWrappers(reader)[0].classList.contains('hoshi-sasayaki-active'), true);
});

test('visual novel Sasayaki cue without reveal does not move to another screen', async () => {
    const cue = { id: 'cue', start: 1, length: 2 };
    const { reader } = await initializeReader(bodyWith(p('一。'), p('二三。')), { revealSpeed: 0 });

    reader.applySasayakiCues([cue]);
    const result = reader.highlightSasayakiCue(cue, false);

    assert.equal(result, null);
    assert.equal(currentScreen(reader).textContent, '一。');
    assert.equal(sasayakiWrappers(reader).length, 0);
    assert.equal(reader.activeCueId, 'cue');

    assert.equal(reader.paginate('forward'), 'scrolled');
    assert.equal(sasayakiWrappers(reader)[0].textContent, '二三');
    assert.equal(sasayakiWrappers(reader)[0].classList.contains('hoshi-sasayaki-active'), true);
});

test('visual novel Sasayaki e-ink mode renders overlay geometry instead of inline wrappers', async () => {
    const cue = { id: 'cue', start: 0, length: 4 };
    const { reader, sasayakiHighlights } = await initializeReader(bodyWith(p('蒸し暑い')), { revealSpeed: 0 });
    reader.isEInkMode = () => true;

    reader.applySasayakiCues([cue]);
    const result = reader.highlightSasayakiCue(cue, false);

    assert.equal(result, null);
    assert.equal(sasayakiWrappers(reader).length, 0);
    assert.equal((reader.cueGeometryRanges.get('cue') ?? []).length, 1);
    assert.equal(reader.activeCueId, 'cue');
    assert.equal(sasayakiHighlights.at(-1).rects.length, 1);
    assert.equal(sasayakiHighlights.at(-1).eInkMode, true);

    reader.clearSasayakiCue();
    assert.equal(sasayakiHighlights.at(-1), null);
});

test('visual novel Sasayaki e-ink overlay uses VN screen writing direction', async () => {
    const cue = { id: 'cue', start: 0, length: 4 };
    const { reader, document, sasayakiHighlights } = await initializeReader(bodyWith(p('蒸し暑い')), {
        bodyWritingMode: 'horizontal-tb',
        vnWritingMode: 'vertical-rl',
        revealSpeed: 0,
    });
    document.documentElement.style.setProperty('--hoshi-reader-vertical-writing', '1');
    reader.isEInkMode = () => true;

    reader.applySasayakiCues([cue]);
    reader.highlightSasayakiCue(cue, false);

    assert.equal(reader.isVertical(), true);
    assert.equal(sasayakiHighlights.at(-1).verticalWriting, true);
});

test('visual novel Sasayaki refresh switches between inline and e-ink presentation', async () => {
    const cue = { id: 'cue', start: 0, length: 4 };
    const { reader, sasayakiHighlights } = await initializeReader(bodyWith(p('蒸し暑い')), { revealSpeed: 0 });
    let eInkMode = false;
    reader.isEInkMode = () => eInkMode;

    reader.applySasayakiCues([cue]);
    reader.highlightSasayakiCue(cue, false);
    const wrapper = sasayakiWrappers(reader)[0];
    assert.equal(wrapper.classList.contains('hoshi-sasayaki-active'), true);

    eInkMode = true;
    reader.refreshSasayakiCuePresentation();
    assert.equal(wrapper.classList.contains('hoshi-sasayaki-active'), false);
    assert.equal(sasayakiHighlights.at(-1).rects.length, 1);

    eInkMode = false;
    reader.refreshSasayakiCuePresentation();
    assert.equal(wrapper.classList.contains('hoshi-sasayaki-active'), true);
    assert.equal(sasayakiHighlights.at(-1), null);
});

test('visual novel clear Sasayaki cue removes active state and preserves chapter offsets', async () => {
    const cue = { id: 'cue', start: 0, length: 4 };
    const { reader } = await initializeReader(bodyWith(p('蒸し暑い')), { revealSpeed: 0 });

    reader.applySasayakiCues([cue]);
    reader.highlightSasayakiCue(cue, false);
    reader.clearSasayakiCue();

    const wrapper = sasayakiWrappers(reader)[0];
    assert.equal(reader.activeCueId, null);
    assert.equal(wrapper.classList.contains('hoshi-sasayaki-active'), false);
    assert.equal(reader.nodeStartOffsets.get(collectTextNodes(wrapper)[0]), 0);
});

test('visual novel Sasayaki highlights only the visible part of a cross-screen cue', async () => {
    const cue = { id: 'cue', start: 1, length: 3 };
    const { reader } = await initializeReader(bodyWith(p('一二。'), p('三四。')), { revealSpeed: 0 });

    reader.applySasayakiCues([cue]);
    reader.highlightSasayakiCue(cue, false);

    assert.equal(sasayakiWrappers(reader).length, 1);
    assert.equal(sasayakiWrappers(reader)[0].textContent, '二。');

    assert.equal(reader.paginate('forward'), 'scrolled');
    assert.equal(sasayakiWrappers(reader).length, 1);
    assert.equal(sasayakiWrappers(reader)[0].textContent, '三四');
});

test('visual novel Sasayaki e-ink cross-screen cue uses only visible geometry', async () => {
    const cue = { id: 'cue', start: 1, length: 3 };
    const { reader } = await initializeReader(bodyWith(p('一二。'), p('三四。')), { revealSpeed: 0 });
    reader.isEInkMode = () => true;

    reader.applySasayakiCues([cue]);
    reader.highlightSasayakiCue(cue, false);
    let ranges = reader.cueGeometryRanges.get('cue') ?? [];
    assert.equal(ranges.length, 1);
    assert.equal(ranges[0].startNode.textContent, '一二。');
    assert.equal(ranges[0].startOffset, 1);
    assert.equal(ranges[0].endOffset, 3);

    assert.equal(reader.paginate('forward'), 'scrolled');
    ranges = reader.cueGeometryRanges.get('cue') ?? [];
    assert.equal(ranges.length, 1);
    assert.equal(ranges[0].startNode.textContent, '三四。');
    assert.equal(ranges[0].startOffset, 0);
    assert.equal(ranges[0].endOffset, 2);
});

test('visual novel Sasayaki merge setting combines block screens intersecting a cross-screen cue', async () => {
    const cue = { id: 'cue', start: 1, length: 3 };
    const { reader } = await initializeReader(
        bodyWith(p('一二。'), p('三四。'), p('五。')),
        {
            revealSpeed: 0,
            mergeCrossScreenSasayakiCues: true,
            initialSasayakiCues: [cue],
        },
    );

    assert.equal(currentScreen(reader).textContent, '一二。三四。');
    assert.equal(reader.paginate('forward'), 'scrolled');
    assert.equal(currentScreen(reader).textContent, '五。');

    await reader.restoreProgress(0);
    reader.highlightSasayakiCue(cue, false);
    assert.equal(sasayakiWrappers(reader).length, 2);
    assert.equal(sasayakiWrappers(reader).map((wrapper) => wrapper.textContent).join(''), '二。三四');
});

test('visual novel Sasayaki merge setting combines sentence screens intersecting a cross-screen cue', async () => {
    const cue = { id: 'cue', start: 1, length: 2 };
    const { reader } = await initializeReader(
        bodyWith(p('一。二。三。四。')),
        {
            mode: 'sentences',
            sentencesPerScreen: 1,
            revealSpeed: 0,
            mergeCrossScreenSasayakiCues: true,
            initialSasayakiCues: [cue],
        },
    );

    assert.equal(currentScreen(reader).textContent, '一。');
    assert.equal(reader.paginate('forward'), 'scrolled');
    assert.equal(currentScreen(reader).textContent, '二。三。');
    assert.equal(reader.paginate('forward'), 'scrolled');
    assert.equal(currentScreen(reader).textContent, '四。');
});

test('visual novel Sasayaki merged screens still split when text exceeds the viewport', async () => {
    const cue = { id: 'cue', start: 0, length: 8 };
    const { reader } = await initializeReader(
        bodyWith(p('一二三四五六。'), p('七八。')),
        {
            revealSpeed: 0,
            charactersPerScreen: 4,
            mergeCrossScreenSasayakiCues: true,
            initialSasayakiCues: [cue],
        },
    );

    assert.equal(currentScreen(reader).textContent, '一二三四');
    assert.equal(reader.paginate('forward'), 'scrolled');
    assert.equal(currentScreen(reader).textContent, '五六。七');
    assert.equal(reader.paginate('forward'), 'scrolled');
    assert.equal(currentScreen(reader).textContent, '八。');
});

test('visual novel Sasayaki merge finds cross-screen cue intervals without scanning every screen per cue', () => {
    const { reader } = loadReader(bodyWith(p('seed')), {
        mergeCrossScreenSasayakiCues: true,
    });
    const screens = Array.from({ length: 120 }, (_, index) => ({
        startCharCount: index * 2,
        endCharCount: index * 2 + 2,
        startRawCount: index * 2,
        endRawCount: index * 2 + 2,
        ids: new Set(),
        splittable: true,
        render: () => new TestFragment(),
    }));
    const cues = Array.from({ length: 50 }, (_, index) => ({
        id: `cue-${index}`,
        start: index * 4 + 1,
        length: 2,
    }));
    let intersectionChecks = 0;
    const intersects = reader.sasayakiCueIntersectsScreen.bind(reader);
    reader.sasayakiCueIntersectsScreen = (cue, screen) => {
        intersectionChecks += 1;
        return intersects(cue, screen);
    };
    reader.setSasayakiCueData(cues);

    const merged = reader.mergeSasayakiCrossScreenScreens(screens);

    assert.equal(merged.length, 70);
    assert.ok(
        intersectionChecks <= screens.length + cues.length * 3,
        `expected near-linear screen/cue checks, got ${intersectionChecks}`,
    );
});

test('visual novel Sasayaki completes reveal before highlighting the active cue', async () => {
    const cue = { id: 'cue', start: 0, length: 4 };
    const { reader } = await initializeReader(bodyWith(p('蒸し暑い')), { revealSpeed: 10 });

    reader.applySasayakiCues([cue]);
    reader.highlightSasayakiCue(cue, false);

    assert.equal(reader.revealComplete, true);
    assert.equal(currentScreen(reader).querySelectorAll('[data-hoshi-visual-novel-unrevealed]').length, 0);
    assert.equal(sasayakiWrappers(reader)[0].textContent, '蒸し暑い');
    assert.equal(sasayakiWrappers(reader)[0].classList.contains('hoshi-sasayaki-active'), true);
});
