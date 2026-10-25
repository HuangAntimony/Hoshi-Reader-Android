import assert from 'node:assert/strict';
import fs from 'node:fs';
import test from 'node:test';
import vm from 'node:vm';

const readerMediaSemanticsUrl = new URL('../../main/assets/hoshi-web/reader/reader-media-semantics.js', import.meta.url);

class TestElement {
    constructor(tagName, attributes = {}) {
        this.nodeType = 1;
        this.tagName = tagName.toUpperCase();
        this.childNodes = [];
        this.parentNode = null;
        this.listeners = new Map();
        this.attributes = new Map();
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
        Object.entries(attributes).forEach(([key, value]) => this.setAttribute(key, value));
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

    get parentElement() {
        return this.parentNode?.nodeType === 1 ? this.parentNode : null;
    }

    get textContent() {
        return this.childNodes.map((child) => child.textContent ?? '').join('');
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
        const result = [];
        const visit = (node) => {
            if (selector === '*' && node !== this && node.nodeType === 1) result.push(node);
            if (selector === 'img' && node.tagName === 'IMG') result.push(node);
            if (selector === 'svg image' && node.tagName === 'IMAGE' && node.closest('svg')) result.push(node);
            node.childNodes?.forEach(visit);
        };
        visit(this);
        return result;
    }
}

class TestText {
    constructor(value) {
        this.nodeType = 3;
        this.nodeValue = value;
        this.parentNode = null;
    }

    get textContent() {
        return this.nodeValue;
    }
}

function image(attributes = {}) {
    const img = new TestElement('img', attributes);
    img.complete = true;
    img.naturalWidth = 320;
    img.naturalHeight = 240;
    return img;
}

function loadMediaSemanticsContext() {
    const window = {};
    const documentElement = new TestElement('html');
    const document = {
        baseURI: 'https://example.invalid/chapter.xhtml',
        documentElement,
        createElement(tagName) {
            if (tagName.toLowerCase() === 'canvas') {
                let source = null;
                return {
                    width: 0,
                    height: 0,
                    getContext() {
                        return {
                            drawImage(img) {
                                source = img;
                            },
                            getImageData() {
                                return { data: source?.testPixels ?? new Uint8ClampedArray() };
                            },
                        };
                    },
                };
            }
            const element = new TestElement(tagName);
            element.ownerDocument = document;
            return element;
        },
        createElementNS(namespaceURI, tagName) {
            const element = new TestElement(tagName);
            element.namespaceURI = namespaceURI;
            element.ownerDocument = document;
            return element;
        },
        getElementById(id) {
            let result = null;
            const visit = (node) => {
                if (result) return;
                if (node.getAttribute?.('id') === id) {
                    result = node;
                    return;
                }
                node.childNodes?.forEach(visit);
            };
            visit(documentElement);
            return result;
        },
    };
    vm.runInNewContext(fs.readFileSync(readerMediaSemanticsUrl, 'utf8'), { window, document, URL });
    return { document, media: window.hoshiReaderMediaSemantics };
}

function loadMediaSemantics() {
    return loadMediaSemanticsContext().media;
}

function clickEvent() {
    return {
        type: 'click',
        prevented: false,
        stopped: false,
        preventDefault() {
            this.prevented = true;
        },
        stopPropagation() {
            this.stopped = true;
        },
    };
}

test('shared media setup blurs, wraps, posts native image taps, and guards duplicate setup', () => {
    const media = loadMediaSemantics();
    const messages = [];
    const parent = new TestElement('p');
    const img = image();
    parent.appendChild(img);

    media.setupReaderImage(img, 'images/pic.jpg', {
        blurImages: true,
        wrap: true,
        imageBridge: { postMessage: (message) => messages.push(message) },
    });
    media.setupReaderImage(img, 'images/pic.jpg', {
        blurImages: true,
        wrap: true,
        imageBridge: { postMessage: (message) => messages.push(message) },
    });

    assert.equal(img.classList.contains('blurred'), true);
    assert.equal(parent.childNodes.length, 1);
    assert.equal(parent.childNodes[0].classList.contains('blur-wrapper'), true);
    assert.equal(parent.childNodes[0].childNodes[0], img);

    const revealClick = clickEvent();
    img.dispatchEvent(revealClick);
    assert.equal(revealClick.prevented, true);
    assert.equal(revealClick.stopped, true);
    assert.equal(img.classList.contains('blurred'), false);
    assert.deepEqual(messages, []);

    img.dispatchEvent(clickEvent());
    assert.deepEqual(messages, ['https://example.invalid/images/pic.jpg']);
});

test('shared media setup scans scoped images, svg images, gaiji, and src fallbacks', async () => {
    const media = loadMediaSemantics();
    const messages = [];
    const root = new TestElement('section');
    const large = image({ src: 'images/large.jpg' });
    large.src = '';
    large.currentSrc = '';
    const gaiji = image({ class: 'gaiji', src: 'images/gaiji.png' });
    const svg = new TestElement('svg', { id: 'plate', preserveAspectRatio: 'none' });
    const svgImage = new TestElement('image', { href: 'images/plate.jpg' });
    svgImage.href = { baseVal: 'images/plate.jpg' };
    svg.appendChild(svgImage);
    root.appendChild(large);
    root.appendChild(gaiji);
    root.appendChild(svg);

    await media.setupReaderImages(root, {
        blurImages: false,
        imageBridge: { postMessage: (message) => messages.push(message) },
    });

    assert.equal(large.classList.contains('block-img'), true);
    assert.equal(gaiji.classList.contains('block-img'), false);
    assert.equal(svg.getAttribute('preserveAspectRatio'), 'xMidYMid meet');

    large.dispatchEvent(clickEvent());
    svgImage.dispatchEvent(clickEvent());
    assert.deepEqual(messages, [
        'https://example.invalid/images/large.jpg',
        'https://example.invalid/images/plate.jpg',
    ]);
});

test('shared media setup installs one gaiji filter colored from the Reader text variable', async () => {
    const { document, media } = loadMediaSemanticsContext();
    const root = new TestElement('section');
    root.appendChild(image({ class: 'gaiji', src: 'images/gaiji.png' }));

    await media.setupReaderImages(root, { blurImages: false });
    await media.setupReaderImages(root, { blurImages: false });

    assert.equal(document.documentElement.childNodes.length, 1);
    const svg = document.documentElement.childNodes[0];
    assert.equal(svg.getAttribute('aria-hidden'), 'true');
    assert.equal(svg.getAttribute('width'), '0');
    assert.equal(svg.getAttribute('height'), '0');
    assert.equal(
        svg.getAttribute('style'),
        'position: absolute !important; width: 0 !important; height: 0 !important; overflow: hidden !important; pointer-events: none !important',
    );

    const filter = svg.childNodes[0];
    assert.equal(filter.getAttribute('id'), 'hoshi-gaiji-text-color-filter');
    assert.equal(filter.getAttribute('color-interpolation-filters'), 'sRGB');

    const inverseLuminance = filter.childNodes[0];
    assert.equal(inverseLuminance.tagName, 'FECOLORMATRIX');
    assert.equal(inverseLuminance.getAttribute('in'), 'SourceGraphic');
    assert.match(inverseLuminance.getAttribute('values'), /-0\.2126 -0\.7152 -0\.0722 0 1/);

    const sourceAlphaMask = filter.childNodes[1];
    assert.equal(sourceAlphaMask.tagName, 'FECOMPOSITE');
    assert.equal(sourceAlphaMask.getAttribute('in2'), 'SourceAlpha');
    assert.equal(sourceAlphaMask.getAttribute('operator'), 'in');

    const solidStrokeMask = filter.childNodes[2];
    assert.equal(solidStrokeMask.tagName, 'FECOMPONENTTRANSFER');
    assert.equal(solidStrokeMask.childNodes[0].getAttribute('slope'), '1.1');

    const textColor = filter.childNodes[3];
    assert.equal(textColor.tagName, 'FEFLOOD');
    assert.equal(textColor.getAttribute('style'), 'flood-color: var(--hoshi-text-color)');

    const coloredGlyph = filter.childNodes[4];
    assert.equal(coloredGlyph.tagName, 'FECOMPOSITE');
    assert.equal(coloredGlyph.getAttribute('in'), 'textColor');
    assert.equal(coloredGlyph.getAttribute('in2'), 'solidStrokeMask');
    assert.equal(coloredGlyph.getAttribute('operator'), 'in');
});

test('shared media setup keeps large gaiji-wide images inline', async () => {
    const media = loadMediaSemantics();
    const messages = [];
    const root = new TestElement('p');
    const gaijiWide = image({ class: 'gaiji-wide', src: 'images/kao1.jpg' });
    gaijiWide.naturalWidth = 303;
    gaijiWide.naturalHeight = 128;
    root.appendChild(gaijiWide);

    await media.setupReaderImages(root, {
        blurImages: false,
        imageBridge: { postMessage: (message) => messages.push(message) },
    });
    gaijiWide.dispatchEvent(clickEvent());

    assert.equal(gaijiWide.classList.contains('block-img'), false);
    assert.deepEqual(messages, []);
});

test('shared media setup treats every class token containing gaiji as gaiji', async () => {
    const media = loadMediaSemantics();
    const root = new TestElement('p');
    const gaijiVariant = image({ class: 'ornament publisher-GaIjI-tall', src: 'images/glyph.png' });
    gaijiVariant.naturalWidth = 128;
    gaijiVariant.naturalHeight = 512;
    root.appendChild(gaijiVariant);

    await media.setupReaderImages(root, { blurImages: false });

    assert.equal(gaijiVariant.classList.contains('block-img'), false);
    assert.equal(gaijiVariant.classList.contains('hoshi-text-color-image'), true);
});

test('shared media setup tints transparent monochrome images embedded in text only', async () => {
    const media = loadMediaSemantics();
    const root = new TestElement('section');
    const paragraph = new TestElement('p');
    const glyph = image({ class: 'width-1em', src: 'images/00030.png' });
    glyph.naturalWidth = 2;
    glyph.naturalHeight = 2;
    glyph.testPixels = new Uint8ClampedArray([
        0, 0, 0, 255,
        0, 0, 0, 0,
        64, 64, 64, 255,
        0, 0, 0, 0,
    ]);
    const colored = image({ class: 'width-1em', src: 'images/colored.png' });
    colored.naturalWidth = 2;
    colored.naturalHeight = 2;
    colored.testPixels = new Uint8ClampedArray([
        255, 0, 0, 255,
        0, 0, 0, 0,
        255, 0, 0, 255,
        0, 0, 0, 0,
    ]);
    const standalone = image({ src: 'images/standalone.png' });
    standalone.naturalWidth = 2;
    standalone.naturalHeight = 2;
    standalone.testPixels = glyph.testPixels;
    paragraph.appendChild(new TestText('前'));
    paragraph.appendChild(glyph);
    paragraph.appendChild(colored);
    paragraph.appendChild(new TestText('後'));
    root.appendChild(paragraph);
    root.appendChild(standalone);

    await media.setupReaderImages(root, { blurImages: false });

    assert.equal(glyph.classList.contains('hoshi-text-color-image'), true);
    assert.equal(colored.classList.contains('hoshi-text-color-image'), false);
    assert.equal(standalone.classList.contains('hoshi-text-color-image'), false);
});

test('shared media setup waits for pending images when requested', async () => {
    const media = loadMediaSemantics();
    const root = new TestElement('section');
    const pending = image({ src: 'images/pending.jpg' });
    pending.complete = false;
    root.appendChild(pending);
    let resolved = false;

    const promise = media.setupReaderImages(root, { waitForImages: true })
        .then(() => {
            resolved = true;
        });
    await Promise.resolve();
    assert.equal(resolved, false);

    pending.complete = true;
    pending.onload();
    await promise;

    assert.equal(resolved, true);
    assert.equal(pending.classList.contains('block-img'), true);
});

test('shared media setup replaces failed gaiji with alt text and preserves broken images without fallback text', async () => {
    const media = loadMediaSemantics();
    const root = new TestElement('section');
    const withAlt = image({ class: 'gaiji', src: 'images/sigma.png', alt: 'Σ' });
    const withMultipleCharacters = image({ class: 'gaiji-line', src: 'images/formula.png', alt: 'eiπ＋１＝０' });
    const withoutAlt = image({ class: 'gaiji-line', src: 'images/ornament.png', alt: '' });
    const regular = image({ src: 'images/illustration.png', alt: 'Illustration' });
    [withAlt, withMultipleCharacters, withoutAlt, regular].forEach((img) => {
        img.naturalWidth = 0;
        img.naturalHeight = 0;
        root.appendChild(img);
    });

    await media.setupReaderImages(root, { waitForImages: true });

    assert.equal(root.childNodes.length, 4);
    assert.equal(root.childNodes[0].tagName, 'SPAN');
    assert.equal(root.childNodes[0].classList.contains('hoshi-gaiji-fallback'), true);
    assert.equal(root.childNodes[0].classList.contains('hoshi-gaiji-fallback-single'), true);
    assert.equal(root.childNodes[0].getAttribute('data-hoshi-gaiji-alt'), 'Σ');
    assert.equal(root.childNodes[1].tagName, 'SPAN');
    assert.equal(root.childNodes[1].classList.contains('hoshi-gaiji-fallback'), true);
    assert.equal(root.childNodes[1].classList.contains('hoshi-gaiji-fallback-single'), false);
    assert.equal(root.childNodes[1].getAttribute('data-hoshi-gaiji-alt'), 'eiπ＋１＝０');
    assert.equal(root.childNodes[2], withoutAlt);
    assert.equal(root.childNodes[3], regular);
});

test('shared media setup handles gaiji failures after non-blocking setup', async () => {
    const media = loadMediaSemantics();
    const root = new TestElement('section');
    const pending = image({ class: 'gaiji', src: 'images/pending.png', alt: '冴' });
    const withoutAlt = image({ class: 'gaiji', src: 'images/broken.png', alt: '' });
    [pending, withoutAlt].forEach((img) => {
        img.complete = false;
        img.naturalWidth = 0;
        img.naturalHeight = 0;
    });
    root.appendChild(pending);
    root.appendChild(withoutAlt);

    await media.setupReaderImages(root, { waitForImages: false });
    pending.onerror();
    withoutAlt.onerror();

    assert.equal(root.childNodes.length, 2);
    assert.equal(root.childNodes[0].tagName, 'SPAN');
    assert.equal(root.childNodes[0].getAttribute('data-hoshi-gaiji-alt'), '冴');
    assert.equal(root.childNodes[1], withoutAlt);
});

test('image holds exclude only resources with at least three book references', () => {
    const { media, document } = loadMediaSemanticsContext();
    media.setImageReferenceCounts({ 'images/divider.jpg': 3, 'images/twice.jpg': 2 });
    document.baseURI = 'https://appassets.androidplatform.net/epub/Text/chapter.xhtml';
    for (const [src, expected] of [['../images/divider.jpg', false], ['../images/twice.jpg', true], ['../images/art.jpg', true]]) {
        const img = image({ src });
        img.ownerDocument = document;
        assert.equal(media.shouldHoldMedia(img), expected);
        assert.equal(img.getAttribute('src'), src);
    }
});

test('media wrappers do not reintroduce repeated-image holds and mixed artwork still holds', () => {
    const { media, document } = loadMediaSemanticsContext();
    document.baseURI = 'https://appassets.androidplatform.net/epub/chapter.xhtml';
    media.setImageReferenceCounts({ 'divider.jpg': 3 });
    for (const [wrapperTag, imageTag, sourceAttribute] of [['picture', 'img', 'src'], ['svg', 'image', 'xlink:href']]) {
        const wrapper = new TestElement(wrapperTag);
        const repeated = new TestElement(imageTag, { [sourceAttribute]: 'divider.jpg' });
        repeated.ownerDocument = document;
        wrapper.appendChild(repeated);
        assert.equal(media.shouldHoldMedia(wrapper), false);
        const ordinary = new TestElement(imageTag, { [sourceAttribute]: 'art.jpg' });
        ordinary.ownerDocument = document;
        wrapper.appendChild(ordinary);
        assert.equal(media.shouldHoldMedia(wrapper), true);
        wrapper.removeChild(ordinary);
        if (wrapperTag === 'svg') {
            wrapper.appendChild(new TestElement('path'));
            assert.equal(media.shouldHoldMedia(wrapper), true);
        }
    }
    const unknown = image({ src: 'unknown.jpg' });
    unknown.ownerDocument = document;
    assert.equal(media.shouldHoldMedia(unknown), true);
    media.setImageReferenceCounts(null);
    const repeated = image({ src: 'divider.jpg' });
    repeated.ownerDocument = document;
    assert.equal(media.shouldHoldMedia(repeated), true);
});

test('responsive images use the selected candidate count and retain unique artwork', () => {
    const { media, document } = loadMediaSemanticsContext();
    document.baseURI = 'https://appassets.androidplatform.net/epub/chapter.xhtml';
    media.setImageReferenceCounts({ 'fallback.jpg': 3, 'divider.webp': 3, 'art.webp': 1 });
    const img = image({ src: 'fallback.jpg' });
    img.ownerDocument = document;
    img.currentSrc = 'https://appassets.androidplatform.net/epub/divider.webp';
    assert.equal(media.shouldHoldMedia(img), false);
    img.currentSrc = 'https://appassets.androidplatform.net/epub/art.webp';
    assert.equal(media.shouldHoldMedia(img), true);
});
