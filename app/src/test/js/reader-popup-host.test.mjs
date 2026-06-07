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
        this.eventListeners = new Map();
        this.style = {
            setProperty: (name, value) => {
                this.style[name] = value;
            },
        };
        this.contentWindow = tagName === 'iframe' ? { postMessage() {} } : null;
        this.src = '';
        this.clientRects = [];
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

    addEventListener(type, listener) {
        const listeners = this.eventListeners.get(type) ?? [];
        listeners.push(listener);
        this.eventListeners.set(type, listeners);
    }

    dispatchEvent(type) {
        (this.eventListeners.get(type) ?? []).forEach((listener) => listener());
    }

    getClientRects() {
        return this.clientRects;
    }

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

    closest(selector) {
        const classNames = Array.from(selector.matchAll(/\.([A-Za-z0-9_-]+)/g), (match) => match[1]);
        let element = this;
        while (element) {
            if (classNames.some((className) => element.className.split(' ').includes(className))) {
                return element;
            }
            element = element.parentNode;
        }
        return null;
    }
}

function popupHost(options = {}) {
    const root = new FakeElement('html');
    const body = new FakeElement('body');
    root.appendChild(body);
    const messageListeners = [];
    const windowListeners = new Map();
    const document = {
        documentElement: root,
        body,
        createElement: (tagName) => new FakeElement(tagName),
        getElementById: (id) => findById(root, id),
        querySelectorAll: (selector) => root.querySelectorAll(selector),
    };
    const window = {
        devicePixelRatio: 1,
        innerWidth: 100,
        innerHeight: 100,
        getComputedStyle: (element) => ({
            writingMode: element.style.writingMode ?? '',
            getPropertyValue(name) {
                return element.style[name] ?? '';
            },
        }),
        addEventListener(type, listener) {
            if (type === 'message') {
                messageListeners.push(listener);
                return;
            }
            const listeners = windowListeners.get(type) ?? [];
            listeners.push(listener);
            windowListeners.set(type, listeners);
        },
        __hoshiReaderPopupHostDismissTopPopupOnOutsideTap: options.dismissTopPopupOnOutsideTap === true,
    };
    const script = fs.readFileSync(
        new URL('../../main/assets/hoshi-web/popup/reader-popup-host.js', import.meta.url),
        'utf8',
    );
    vm.runInNewContext(script, { console, document, Map, Set, WeakMap, window });
    return {
        document,
        window,
        host: window.hoshiReaderPopupHost,
        dispatchMessage: (data) => {
            messageListeners.forEach((listener) => listener({
                origin: 'https://appassets.androidplatform.net',
                data,
                source: {},
            }));
        },
        dispatchWindowEvent: (type, event) => {
            (windowListeners.get(type) ?? []).forEach((listener) => listener(event));
        },
    };
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
            iframeUrl: 'https://appassets.androidplatform.net/popup/iframe.html',
        }],
    });
    const shell = document.getElementById('hoshi-reader-popup-layer').children[0];
    return {
        actionBar: shell.querySelector('.hoshi-reader-popup-action-bar'),
        sasayakiBar: shell.querySelector('.hoshi-reader-popup-sasayaki-bar'),
    };
}

function rootPopupPayload() {
    return {
        id: 'root',
        frame: { left: 0, top: 0, width: 300, height: 250 },
        actionBarVisible: false,
        sasayakiVisible: false,
        sasayakiIsPlaying: false,
        sasayakiWasPaused: false,
        backCount: 0,
        forwardCount: 0,
        clearSelectionSignal: 0,
        iframeUrl: 'https://appassets.androidplatform.net/popup/iframe.html',
    };
}

function renderRootHighlightScene(rootHighlight, setupDocument = null) {
    const { document, host, dispatchMessage } = popupHost();
    setupDocument?.(document);
    host.renderStack({
        popups: [rootPopupPayload()],
        rootHighlight,
    });
    dispatchMessage({
        source: 'hoshi-popup-iframe',
        name: 'contentReady',
        popupId: 'root',
    });
    const layer = document.getElementById('hoshi-reader-popup-layer');
    return {
        document,
        host,
        layer,
        selectionHighlights: layer.querySelector('.hoshi-reader-selection-highlight-layer')?.children ?? [],
        sasayakiHighlights: layer.querySelector('.hoshi-reader-sasayaki-highlight-layer')?.children ?? [],
    };
}

function renderRootHighlight(rootHighlight, setupDocument = null) {
    return renderRootHighlightScene(rootHighlight, setupDocument).selectionHighlights;
}

function highlightEdge(highlight, edgeName) {
    return highlight.children.find((child) =>
        child.className.split(' ').includes(`hoshi-reader-selection-highlight-edge-${edgeName}`),
    );
}

function physicalPixels(styleValue, ratio) {
    return Math.round(parseFloat(styleValue) * ratio);
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

test('public navigation helpers forward messages to the active iframe', () => {
    const scene = popupHost();
    const iframeMessages = [];
    const nativeMessages = [];
    scene.window.HoshiReaderPopup = {
        postMessage(message) {
            nativeMessages.push(JSON.parse(message));
        },
    };
    scene.host.renderStack({
        popups: [{
            ...rootPopupPayload(),
            backCount: 1,
            forwardCount: 1,
        }],
    });
    const shell = scene.document.getElementById('hoshi-reader-popup-layer').children[0];
    const iframe = shell.querySelector('.hoshi-reader-popup-iframe');
    iframe.contentWindow.postMessage = (message) => iframeMessages.push(message);

    scene.host.navigateBack('root');
    scene.host.navigateForward('root');

    assert.deepEqual(
        iframeMessages.map((message) => JSON.parse(JSON.stringify(message))),
        [{ type: 'navigateBack' }, { type: 'navigateForward' }],
    );
    assert.deepEqual(nativeMessages, [
        { name: 'navigateBack', popupId: 'root' },
        { name: 'navigateForward', popupId: 'root' },
    ]);
});

test('outside pointer dismissal is opt-in for process text iframe host', () => {
    const scene = popupHost();
    const nativeMessages = [];
    scene.window.HoshiReaderPopup = {
        postMessage(message) {
            nativeMessages.push(JSON.parse(message));
        },
    };
    scene.host.renderStack({
        popups: [rootPopupPayload()],
    });

    scene.dispatchWindowEvent('pointerdown', { target: scene.document.body });

    assert.deepEqual(nativeMessages, []);
});

test('process text iframe host outside pointer dismisses the top popup', () => {
    const scene = popupHost({ dismissTopPopupOnOutsideTap: true });
    const nativeMessages = [];
    scene.window.HoshiReaderPopup = {
        postMessage(message) {
            nativeMessages.push(JSON.parse(message));
        },
    };
    scene.host.renderStack({
        popups: [
            rootPopupPayload(),
            {
                ...rootPopupPayload(),
                id: 'child',
                frame: { left: 20, top: 20, width: 200, height: 180 },
            },
        ],
    });

    scene.dispatchWindowEvent('pointerdown', { target: scene.document.body });

    assert.deepEqual(nativeMessages, [
        { name: 'swipeDismiss', popupId: 'child' },
    ]);
});

test('active iframe rerenders when same popup id receives new entry payload', () => {
    const scene = popupHost();
    scene.host.renderStack({
        popups: [{
            ...rootPopupPayload(),
            entriesCount: 1,
            initialEntryJson: '{"expression":"食べる"}',
        }],
    });
    const shell = scene.document.getElementById('hoshi-reader-popup-layer').children[0];
    const iframe = shell.querySelector('.hoshi-reader-popup-iframe');
    const iframeMessages = [];
    iframe.contentWindow.postMessage = (message) => iframeMessages.push(message);
    iframe.dispatchEvent('load');

    scene.host.renderStack({
        popups: [{
            ...rootPopupPayload(),
            entriesCount: 1,
            initialEntryJson: '{"expression":"飲む"}',
        }],
    });

    assert.deepEqual(
        iframeMessages
            .filter((message) => message.type === 'renderPopup')
            .map((message) => JSON.parse(JSON.stringify(message))),
        [
            {
                type: 'renderPopup',
                popupId: 'root',
                entriesCount: 1,
                initialEntryJson: '{"expression":"食べる"}',
            },
            {
                type: 'renderPopup',
                popupId: 'root',
                entriesCount: 1,
                initialEntryJson: '{"expression":"飲む"}',
            },
        ],
    );
});

test('active iframe rerenders when content key changes behind same first entry and count', () => {
    const scene = popupHost();
    scene.host.renderStack({
        popups: [{
            ...rootPopupPayload(),
            entriesCount: 2,
            initialEntryJson: '{"expression":"猫"}',
            contentKey: '猫/犬',
        }],
    });
    const shell = scene.document.getElementById('hoshi-reader-popup-layer').children[0];
    const iframe = shell.querySelector('.hoshi-reader-popup-iframe');
    const iframeMessages = [];
    iframe.contentWindow.postMessage = (message) => iframeMessages.push(message);
    iframe.dispatchEvent('load');

    scene.host.renderStack({
        popups: [{
            ...rootPopupPayload(),
            entriesCount: 2,
            initialEntryJson: '{"expression":"猫"}',
            contentKey: '猫/鳥',
        }],
    });

    assert.deepEqual(
        iframeMessages
            .filter((message) => message.type === 'renderPopup')
            .map((message) => JSON.parse(JSON.stringify(message))),
        [
            {
                type: 'renderPopup',
                popupId: 'root',
                entriesCount: 2,
                initialEntryJson: '{"expression":"猫"}',
            },
            {
                type: 'renderPopup',
                popupId: 'root',
                entriesCount: 2,
                initialEntryJson: '{"expression":"猫"}',
            },
        ],
    );
});

test('history updates do not replace iframe content handled by popup redirect', () => {
    const scene = popupHost();
    scene.host.renderStack({
        popups: [{
            ...rootPopupPayload(),
            entriesCount: 1,
            initialEntryJson: '{"expression":"食べる"}',
        }],
    });
    const shell = scene.document.getElementById('hoshi-reader-popup-layer').children[0];
    const iframe = shell.querySelector('.hoshi-reader-popup-iframe');
    const iframeMessages = [];
    iframe.contentWindow.postMessage = (message) => iframeMessages.push(message);
    iframe.dispatchEvent('load');

    scene.host.renderStack({
        popups: [{
            ...rootPopupPayload(),
            backCount: 1,
            entriesCount: 1,
            initialEntryJson: '{"expression":"飲む"}',
        }],
    });

    assert.deepEqual(
        iframeMessages
            .filter((message) => message.type === 'renderPopup')
            .map((message) => JSON.parse(JSON.stringify(message))),
        [
            {
                type: 'renderPopup',
                popupId: 'root',
                entriesCount: 1,
                initialEntryJson: '{"expression":"食べる"}',
            },
        ],
    );
});

test('reader highlight updates can omit initial entry without replacing iframe content', () => {
    const scene = popupHost();
    scene.host.renderStack({
        popups: [{
            ...rootPopupPayload(),
            entriesCount: 1,
            initialEntryJson: '{"expression":"読む"}',
        }],
        rootHighlight: {
            popupId: 'root',
            pending: true,
            rects: [],
        },
    });
    const shell = scene.document.getElementById('hoshi-reader-popup-layer').children[0];
    const iframe = shell.querySelector('.hoshi-reader-popup-iframe');
    const iframeMessages = [];
    iframe.contentWindow.postMessage = (message) => iframeMessages.push(message);
    iframe.dispatchEvent('load');

    scene.host.renderStack({
        popups: [{
            ...rootPopupPayload(),
            entriesCount: 1,
            initialEntryJson: null,
        }],
        rootHighlight: {
            popupId: 'root',
            pending: false,
            rects: [{ x: 10, y: 20, width: 30, height: 12 }],
        },
    });

    assert.deepEqual(
        iframeMessages
            .filter((message) => message.type === 'renderPopup')
            .map((message) => JSON.parse(JSON.stringify(message))),
        [
            {
                type: 'renderPopup',
                popupId: 'root',
                entriesCount: 1,
                initialEntryJson: '{"expression":"読む"}',
            },
        ],
    );
});

test('non e-ink root lookup highlight keeps the filled selection rectangle', () => {
    const highlights = renderRootHighlight({
        popupId: 'root',
        pending: false,
        eInkMode: false,
        darkMode: false,
        verticalWriting: false,
        rects: [{ x: 12, y: 24, width: 30, height: 16 }],
    });

    assert.equal(highlights.length, 1);
    assert.equal(highlights[0].style.background, 'rgba(160, 160, 160, 0.32)');
    assert.equal(highlights[0].style.left, '12px');
    assert.equal(highlights[0].style.top, '24px');
    assert.equal(highlights[0].style.width, '30px');
    assert.equal(highlights[0].style.height, '16px');
    assert.equal(highlightEdge(highlights[0], 'top'), undefined);
});

test('e-ink root lookup highlight draws a transparent box using the theme line color', () => {
    const highlights = renderRootHighlight({
        popupId: 'root',
        pending: false,
        eInkMode: true,
        darkMode: true,
        verticalWriting: false,
        rects: [{ x: 12, y: 24, width: 30, height: 16 }],
    });

    assert.equal(highlights.length, 1);
    assert.equal(highlights[0].style.background, 'transparent');
    assert.equal(highlights[0].style.left, '12px');
    assert.equal(highlights[0].style.top, '24px');
    assert.equal(highlightEdge(highlights[0], 'top').style.background, '#fff');
    assert.equal(highlightEdge(highlights[0], 'right').style.background, '#fff');
    assert.equal(highlightEdge(highlights[0], 'bottom').style.top, '14.5px');
    assert.equal(highlightEdge(highlights[0], 'bottom').style.height, '1.5px');
    assert.equal(highlightEdge(highlights[0], 'left').style.background, '#fff');
});

test('e-ink root lookup box snaps fractional edges to matching physical pixel widths', () => {
    const scene = popupHost();
    scene.window.devicePixelRatio = 2.625;
    scene.host.renderStack({
        popups: [rootPopupPayload()],
        rootHighlight: {
            popupId: 'root',
            pending: false,
            eInkMode: true,
            darkMode: false,
            verticalWriting: true,
            rects: [{ x: 34.2, y: 100.1, width: 83.1, height: 225.2 }],
        },
    });
    scene.dispatchMessage({
        source: 'hoshi-popup-iframe',
        name: 'contentReady',
        popupId: 'root',
    });

    const layer = scene.document.getElementById('hoshi-reader-popup-layer');
    const highlight = layer.querySelector('.hoshi-reader-selection-highlight-rect');
    const leftEdge = highlightEdge(highlight, 'left');
    const rightEdge = highlightEdge(highlight, 'right');
    const ratio = scene.window.devicePixelRatio;

    assert.equal(physicalPixels(leftEdge.style.width, ratio), 4);
    assert.equal(physicalPixels(rightEdge.style.width, ratio), 4);
    assert.equal(physicalPixels(leftEdge.style.width, ratio), physicalPixels(rightEdge.style.width, ratio));
    assert.equal(
        physicalPixels(rightEdge.style.left, ratio) + physicalPixels(rightEdge.style.width, ratio),
        physicalPixels(highlight.style.width, ratio),
    );
});

test('horizontal e-ink root lookup highlight opens the box at a line split', () => {
    const highlights = renderRootHighlight({
        popupId: 'root',
        pending: false,
        eInkMode: true,
        darkMode: false,
        verticalWriting: false,
        rects: [
            { x: 90, y: 24, width: 10, height: 16 },
            { x: 0, y: 50, width: 12, height: 16 },
        ],
    });

    assert.equal(highlights.length, 2);
    assert.equal(highlightEdge(highlights[0], 'right'), undefined);
    assert.notEqual(highlightEdge(highlights[0], 'left'), undefined);
    assert.equal(highlightEdge(highlights[1], 'left'), undefined);
    assert.notEqual(highlightEdge(highlights[1], 'right'), undefined);
});

test('vertical e-ink root lookup highlight opens the box at a page split', () => {
    const highlights = renderRootHighlight({
        popupId: 'root',
        pending: false,
        eInkMode: true,
        darkMode: false,
        verticalWriting: true,
        rects: [
            { x: 40, y: 90, width: 12, height: 10 },
            { x: 40, y: 0, width: 12, height: 8 },
        ],
    });

    assert.equal(highlights.length, 2);
    assert.equal(highlightEdge(highlights[0], 'bottom'), undefined);
    assert.notEqual(highlightEdge(highlights[0], 'top'), undefined);
    assert.equal(highlightEdge(highlights[1], 'top'), undefined);
    assert.notEqual(highlightEdge(highlights[1], 'bottom'), undefined);
    assert.equal(highlightEdge(highlights[0], 'right').style.left, '10.5px');
});

test('vertical e-ink root lookup highlight merges adjacent ruby text segments into one box', () => {
    const highlights = renderRootHighlight({
        popupId: 'root',
        pending: false,
        eInkMode: true,
        darkMode: false,
        verticalWriting: true,
        rects: [
            { x: 40, y: 50, width: 12, height: 30 },
            { x: 40, y: 80, width: 12, height: 30 },
        ],
    });

    assert.equal(highlights.length, 1);
    assert.equal(highlights[0].style.left, '40px');
    assert.equal(highlights[0].style.top, '50px');
    assert.equal(highlights[0].style.width, '12px');
    assert.equal(highlights[0].style.height, '60px');
    assert.notEqual(highlightEdge(highlights[0], 'top'), undefined);
    assert.notEqual(highlightEdge(highlights[0], 'bottom'), undefined);
});

test('horizontal e-ink root lookup highlight merges adjacent ruby text segments into one box', () => {
    const highlights = renderRootHighlight({
        popupId: 'root',
        pending: false,
        eInkMode: true,
        darkMode: false,
        verticalWriting: false,
        rects: [
            { x: 20, y: 24, width: 18, height: 16 },
            { x: 38, y: 24, width: 12, height: 16 },
        ],
    });

    assert.equal(highlights.length, 1);
    assert.equal(highlights[0].style.left, '20px');
    assert.equal(highlights[0].style.top, '24px');
    assert.equal(highlights[0].style.width, '30px');
    assert.equal(highlights[0].style.height, '16px');
    assert.notEqual(highlightEdge(highlights[0], 'left'), undefined);
    assert.notEqual(highlightEdge(highlights[0], 'right'), undefined);
});

test('horizontal e-ink root lookup highlight expands adjacent segments to the ruby-aware line height', () => {
    const highlights = renderRootHighlight({
        popupId: 'root',
        pending: false,
        eInkMode: true,
        darkMode: false,
        verticalWriting: false,
        rects: [
            { x: 20, y: 20, width: 18, height: 16 },
            { x: 38, y: 20, width: 12, height: 24 },
            { x: 50, y: 20, width: 12, height: 16 },
        ],
    });

    assert.equal(highlights.length, 1);
    assert.equal(highlights[0].style.left, '20px');
    assert.equal(highlights[0].style.top, '20px');
    assert.equal(highlights[0].style.width, '42px');
    assert.equal(highlights[0].style.height, '24px');
    assert.equal(highlightEdge(highlights[0], 'bottom').style.top, '22.5px');
});

test('vertical e-ink sasayaki and root lookup highlight use matching line coordinates', () => {
    const scene = renderRootHighlightScene({
        popupId: 'root',
        pending: false,
        eInkMode: true,
        darkMode: false,
        verticalWriting: true,
        rects: [
            { x: 40, y: 60, width: 12, height: 28 },
        ],
    }, (document) => {
        document.documentElement.style['--hoshi-reader-eink-mode'] = '1';
        document.documentElement.style['--hoshi-reader-vertical-writing'] = '1';
        document.documentElement.style['--hoshi-eink-line-color'] = '#000';
    });

    scene.host.renderSasayakiHighlight({
        eInkMode: true,
        verticalWriting: true,
        rects: [{ x: 40, y: 60, width: 12, height: 28 }],
    });
    const sasayakiHighlights = scene.layer.querySelector('.hoshi-reader-sasayaki-highlight-layer').children;

    assert.equal(scene.selectionHighlights.length, 1);
    assert.equal(sasayakiHighlights.length, 1);
    assert.equal(highlightEdge(scene.selectionHighlights[0], 'right').style.left, '10.5px');
    assert.equal(sasayakiHighlights[0].style.left, '50.5px');
    assert.equal(sasayakiHighlights[0].style.top, '60px');
    assert.equal(sasayakiHighlights[0].style.width, '1.5px');
    assert.equal(sasayakiHighlights[0].style.height, '28px');
});

test('vertical e-ink sasayaki line follows the outer ruby-aware edge for the whole column', () => {
    const scene = popupHost();
    scene.document.documentElement.style['--hoshi-reader-eink-mode'] = '1';
    scene.document.documentElement.style['--hoshi-reader-vertical-writing'] = '1';

    scene.host.renderSasayakiHighlight({
        eInkMode: true,
        verticalWriting: true,
        rects: [
            { x: 40, y: 60, width: 12, height: 10 },
            { x: 34, y: 70, width: 18, height: 10 },
            { x: 40, y: 80, width: 12, height: 10 },
        ],
    });

    const layer = scene.document.getElementById('hoshi-reader-popup-layer');
    const sasayakiHighlights = layer.querySelector('.hoshi-reader-sasayaki-highlight-layer').children;
    assert.equal(sasayakiHighlights.length, 1);
    assert.equal(sasayakiHighlights[0].style.left, '50.5px');
    assert.equal(sasayakiHighlights[0].style.top, '60px');
    assert.equal(sasayakiHighlights[0].style.width, '1.5px');
    assert.equal(sasayakiHighlights[0].style.height, '30px');
});

test('horizontal e-ink sasayaki draws below the ruby-aware rect and clears explicitly', () => {
    const scene = renderRootHighlightScene({
        popupId: 'root',
        pending: false,
        eInkMode: true,
        darkMode: false,
        verticalWriting: false,
        rects: [],
    }, (document) => {
        document.documentElement.style['--hoshi-reader-eink-mode'] = '1';
        document.documentElement.style['--hoshi-reader-vertical-writing'] = '0';
        document.documentElement.style['--hoshi-eink-line-color'] = '#000';
    });

    scene.host.renderSasayakiHighlight({
        eInkMode: true,
        verticalWriting: false,
        rects: [{ x: 12, y: 20, width: 40, height: 18 }],
    });
    let sasayakiHighlights = scene.layer.querySelector('.hoshi-reader-sasayaki-highlight-layer').children;
    assert.equal(sasayakiHighlights.length, 1);
    assert.equal(sasayakiHighlights[0].style.left, '12px');
    assert.equal(sasayakiHighlights[0].style.top, '36.5px');
    assert.equal(sasayakiHighlights[0].style.width, '40px');
    assert.equal(sasayakiHighlights[0].style.height, '1.5px');

    scene.host.clearSasayakiHighlight();
    sasayakiHighlights = scene.layer.querySelector('.hoshi-reader-sasayaki-highlight-layer').children;
    assert.equal(sasayakiHighlights.length, 0);
});

test('horizontal e-ink sasayaki line follows the ruby-aware height for the whole line', () => {
    const scene = popupHost();
    scene.document.documentElement.style['--hoshi-reader-eink-mode'] = '1';
    scene.document.documentElement.style['--hoshi-reader-vertical-writing'] = '0';

    scene.host.renderSasayakiHighlight({
        eInkMode: true,
        verticalWriting: false,
        rects: [
            { x: 12, y: 20, width: 10, height: 16 },
            { x: 22, y: 20, width: 12, height: 24 },
            { x: 34, y: 20, width: 10, height: 16 },
        ],
    });

    const layer = scene.document.getElementById('hoshi-reader-popup-layer');
    const sasayakiHighlights = layer.querySelector('.hoshi-reader-sasayaki-highlight-layer').children;
    assert.equal(sasayakiHighlights.length, 1);
    assert.equal(sasayakiHighlights[0].style.left, '12px');
    assert.equal(sasayakiHighlights[0].style.top, '42.5px');
    assert.equal(sasayakiHighlights[0].style.width, '32px');
    assert.equal(sasayakiHighlights[0].style.height, '1.5px');
});
