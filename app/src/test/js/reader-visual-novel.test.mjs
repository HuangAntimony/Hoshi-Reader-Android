import assert from 'node:assert/strict';
import fs from 'node:fs';
import test from 'node:test';
import vm from 'node:vm';

const readerVisualNovelUrl = new URL('../../main/assets/hoshi-web/reader/reader-visual-novel.js', import.meta.url);

function readerSource() {
    return fs.readFileSync(readerVisualNovelUrl, 'utf8')
        .replaceAll('__HOSHI_VISUAL_NOVEL_REVEAL_SPEED__', '0')
        .replaceAll('__HOSHI_VISUAL_NOVEL_SCREEN_MODE_LITERAL__', JSON.stringify('block'))
        .replaceAll('__HOSHI_VISUAL_NOVEL_SENTENCES_PER_SCREEN__', '1')
        .replaceAll('__HOSHI_VISUAL_NOVEL_PRESERVE_DIALOGUE__', 'false')
        .replaceAll('__HOSHI_INITIAL_PROGRESS__', '0')
        .replaceAll('__HOSHI_INITIAL_FRAGMENT_LITERAL__', 'null')
        .replaceAll('__HOSHI_INITIAL_HIGHLIGHTS_JSON__', 'null')
        .replaceAll('__HOSHI_HIGHLIGHTS_SCRIPT__', '')
        .replaceAll('__HOSHI_RESTORE_TOKEN_LITERAL__', JSON.stringify('restore-token'))
        .replaceAll('__HOSHI_BLUR_IMAGES__', 'false');
}

function configuredReaderSource(options = {}) {
    return fs.readFileSync(readerVisualNovelUrl, 'utf8')
        .replaceAll('__HOSHI_VISUAL_NOVEL_REVEAL_SPEED__', String(options.revealSpeed ?? 0))
        .replaceAll('__HOSHI_VISUAL_NOVEL_SCREEN_MODE_LITERAL__', JSON.stringify(options.mode ?? 'block'))
        .replaceAll('__HOSHI_VISUAL_NOVEL_SENTENCES_PER_SCREEN__', String(options.sentencesPerScreen ?? 1))
        .replaceAll('__HOSHI_VISUAL_NOVEL_PRESERVE_DIALOGUE__', String(options.preserveDialogue ?? false))
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
    const selectors = selector.split(',').map((item) => item.trim());
    const result = [];
    const visit = (node) => {
        if (node.nodeType === 1 && selectors.some((item) => matchesSelector(node, item))) {
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

function buildDocument(body) {
    const head = new TestElement('head');
    const documentElement = new TestElement('html');
    documentElement.appendChild(head);
    documentElement.appendChild(body);
    return {
        body,
        head,
        documentElement,
        fonts: { ready: Promise.resolve() },
        readyState: 'loading',
        baseURI: 'https://example.invalid/chapter.xhtml',
        createDocumentFragment() {
            return new TestFragment();
        },
        createTextNode(text) {
            return new TestText(text);
        },
        createElement(tagName) {
            return new TestElement(tagName);
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
}

function loadReader(body, options = {}) {
    const document = buildDocument(body);
    const restoreMessages = [];
    const imageMessages = [];
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
            return {
                writingMode: target === document.body ? 'vertical-rl' : 'horizontal-tb',
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
    return { reader: window.hoshiReader, document, restoreMessages, timers, imageMessages, window };
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
        'clearSasayakiCue',
        'refreshSasayakiCuePresentation',
        'setNativeSelectionActive',
    ].forEach((name) => {
        assert.equal(typeof reader[name], 'function', name);
    });
    assert.equal(typeof reader.nodeStartOffsets.get, 'function');
    assert.equal(typeof reader.nodeStartRawOffsets.get, 'function');
});

test('block mode renders one top-level block per screen without cloning the entire chapter', async () => {
    const body = bodyWith(p('第一段落。'), p('第二段落。'));
    const { reader } = await initializeReader(body, { mode: 'block', revealSpeed: 0 });

    assert.equal(currentScreen(reader).textContent, '第一段落。');

    assert.equal(reader.paginate('forward'), 'scrolled');
    assert.equal(currentScreen(reader).textContent, '第二段落。');
    assert.equal(currentScreen(reader).textContent.includes('第一段落。'), false);
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
