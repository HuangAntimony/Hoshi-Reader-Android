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
            if (selector === 'img' && node.tagName === 'IMG') result.push(node);
            if (selector === 'svg image' && node.tagName === 'IMAGE' && node.closest('svg')) result.push(node);
            node.childNodes?.forEach(visit);
        };
        visit(this);
        return result;
    }
}

function image(attributes = {}) {
    const img = new TestElement('img', attributes);
    img.complete = true;
    img.naturalWidth = 320;
    img.naturalHeight = 240;
    return img;
}

function loadMediaSemantics() {
    const window = {};
    const document = {
        baseURI: 'https://example.invalid/chapter.xhtml',
        createElement(tagName) {
            return new TestElement(tagName);
        },
    };
    vm.runInNewContext(fs.readFileSync(readerMediaSemanticsUrl, 'utf8'), { window, document, URL });
    return window.hoshiReaderMediaSemantics;
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
