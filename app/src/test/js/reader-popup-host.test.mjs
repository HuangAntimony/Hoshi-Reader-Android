import assert from 'node:assert/strict';
import fs from 'node:fs';
import test from 'node:test';
import vm from 'node:vm';

class FakeElement {
    constructor(tagName) {
        this.tagName = tagName.toUpperCase();
        this.children = [];
        this.className = '';
        this.dataset = {};
        this.attributes = new Map();
        this.style = {
            setProperty: (name, value) => {
                this.style[name] = value;
            },
        };
        this.contentWindow = tagName === 'iframe' ? { postMessage() {} } : null;
        this.src = '';
    }

    appendChild(child) {
        this.children.push(child);
        child.parentNode = this;
        return child;
    }

    insertBefore(child, before) {
        const index = this.children.indexOf(before);
        if (index < 0) return this.appendChild(child);
        this.children.splice(index, 0, child);
        child.parentNode = this;
        return child;
    }

    setAttribute(name, value) {
        this.attributes.set(name, value);
    }

    addEventListener() {}

    remove() {
        if (!this.parentNode) return;
        this.parentNode.children = this.parentNode.children.filter((child) => child !== this);
    }

    replaceChildren(...children) {
        this.children = children;
        children.forEach((child) => {
            child.parentNode = this;
        });
    }

    querySelector(selector) {
        return this.querySelectorAll(selector)[0] ?? null;
    }

    querySelectorAll(selector) {
        const className = selector.startsWith('.') ? selector.slice(1) : null;
        const matches = [];
        const visit = (element) => {
            if (className && element.className.split(' ').includes(className)) {
                matches.push(element);
            }
            element.children.forEach(visit);
        };
        this.children.forEach(visit);
        return matches;
    }
}

function popupHost() {
    const root = new FakeElement('html');
    const document = {
        documentElement: root,
        createElement: (tagName) => new FakeElement(tagName),
        getElementById: (id) => findById(root, id),
    };
    const window = {
        addEventListener() {},
    };
    const script = fs.readFileSync(
        new URL('../../main/assets/hoshi-popup/reader-popup-host.js', import.meta.url),
        'utf8',
    );
    vm.runInNewContext(script, { console, document, Map, Set, WeakMap, window });
    return { document, host: window.hoshiReaderPopupHost };
}

function findById(element, id) {
    if (element.id === id) return element;
    for (const child of element.children) {
        const result = findById(child, id);
        if (result) return result;
    }
    return null;
}

function renderControls() {
    const { document, host } = popupHost();
    host.renderStack({
        popups: [{
            id: 'root',
            frame: { left: 0, top: 0, width: 300, height: 250 },
            actionBarVisible: true,
            sasayakiVisible: true,
            sasayakiIsPlaying: false,
            sasayakiWasPaused: false,
            backCount: 1,
            forwardCount: 1,
            clearSelectionSignal: 0,
            iframeUrl: 'https://hoshi.local/popup/iframe.html',
        }],
    });
    const shell = document.getElementById('hoshi-reader-popup-layer').children[0];
    return {
        actionBar: shell.querySelector('.hoshi-reader-popup-action-bar'),
        sasayakiBar: shell.querySelector('.hoshi-reader-popup-sasayaki-bar'),
    };
}

test('navigation controls keep back and forward together at the leading edge', () => {
    const { actionBar } = renderControls();

    assert.equal(actionBar.children[0].attributes.get('aria-label'), 'Back');
    assert.equal(actionBar.children[1].attributes.get('aria-label'), 'Forward');
    assert.equal(actionBar.children[2].className, 'hoshi-reader-popup-flex-spacer');
    assert.equal(actionBar.children[3].attributes.get('aria-label'), 'Close');
});

test('sasayaki controls receive the larger control icon treatment', () => {
    const { sasayakiBar } = renderControls();

    assert.equal(sasayakiBar.children.length, 3);
    sasayakiBar.children.forEach((control) => {
        assert.match(control.className, /hoshi-reader-popup-sasayaki-control/);
    });
});
