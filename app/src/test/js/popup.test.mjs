import assert from 'node:assert/strict';
import fs from 'node:fs';
import test from 'node:test';
import vm from 'node:vm';

const popupSourceUrl = new URL('../../main/assets/hoshi-web/popup/popup.js', import.meta.url);
const japaneseLanguageUrl = new URL('../../main/assets/hoshi-web/shared/language-ja.js', import.meta.url);
const japaneseSelectionUrl = new URL('../../main/assets/hoshi-web/shared/selection-ja.js', import.meta.url);
const sharedSelectionUrl = new URL('../../main/assets/hoshi-web/shared/selection.js', import.meta.url);

class FakeContainer {
    constructor() {
        this.listeners = new Map();
        this.clickAttached = false;
    }

    addEventListener(type, listener) {
        const listeners = this.listeners.get(type) ?? [];
        listeners.push(listener);
        this.listeners.set(type, listeners);
    }

    dispatch(type, event) {
        (this.listeners.get(type) ?? []).forEach((listener) => listener(event));
    }
}

class FakeElement {
    constructor(matches = [], tagName = 'div') {
        this.attributes = new Map();
        this.children = [];
        this.className = '';
        this.childProbeWidth = undefined;
        this.dataset = {};
        this.matches = new Set(matches);
        this.nodeType = 1;
        this.isConnected = true;
        this.parentElement = null;
        this.probeWidth = 100;
        this.textContent = '';
        this.style = {
            properties: new Map(),
            setProperty(name, value) {
                this.properties.set(name, value);
            },
        };
        this.tagName = tagName.toUpperCase();
    }

    setAttribute(name, value) {
        const stringValue = String(value);
        this.attributes.set(name, stringValue);
        if (name.startsWith('data-')) {
            const dataKey = name.slice(5).replace(/-([a-z])/g, (_, letter) => letter.toUpperCase());
            this.dataset[dataKey] = stringValue;
        }
    }

    getAttribute(name) {
        return this.attributes.get(name) ?? null;
    }

    appendChild(child) {
        child.parentElement = this;
        if (this.childProbeWidth !== undefined) {
            child.probeWidth = this.childProbeWidth;
        }
        this.children.push(child);
        return child;
    }

    append(...children) {
        children.forEach((child) => this.appendChild(child));
    }

    replaceChildren(...children) {
        this.children = [];
        this.append(...children);
    }

    get childNodes() {
        return this.children;
    }

    set innerHTML(value) {
        if (value === '') this.children = [];
    }

    querySelectorAll() {
        return [];
    }

    addEventListener(type, listener) {
        const listeners = this.listeners?.get(type) ?? [];
        listeners.push(listener);
        this.listeners ??= new Map();
        this.listeners.set(type, listeners);
    }

    getBoundingClientRect() {
        return {
            x: 0,
            y: 0,
            left: 0,
            top: 0,
            right: this.probeWidth,
            bottom: 0,
            width: this.probeWidth,
            height: 0,
        };
    }

    closest(selector) {
        const selectors = selector.split(',').map((item) => item.trim());
        return selectors.some((item) => this.matches.has(item) || item === this.tagName.toLowerCase()) ? this : null;
    }

    remove() {
        if (this.parentElement?.children) {
            this.parentElement.children = this.parentElement.children.filter((child) => child !== this);
        }
        this.isConnected = false;
    }
}

function popupContext({
    loadJapaneseLanguageAsset = false,
    loadSelectionAssets = false,
    htmlZoom = '1',
    htmlProbeWidth = 100,
    bodyProbeWidth = 100,
    duplicateStates = {},
    kanjiResult = null,
    getEntry = null,
} = {}) {
    const documentElement = new FakeElement();
    documentElement.childProbeWidth = htmlProbeWidth;
    const body = new FakeContainer();
    body.children = [];
    body.appendChild = function(element) {
        element.parentElement = body;
        element.probeWidth = bodyProbeWidth;
        body.children.push(element);
        return element;
    };
    const documentListeners = new Map();
    const entriesContainer = new FakeElement();
    const overlay = new FakeElement();
    const document = {
        body,
        documentElement,
        addEventListener(type, listener) {
            const listeners = documentListeners.get(type) ?? [];
            listeners.push(listener);
            documentListeners.set(type, listeners);
        },
        dispatch(type, event) {
            (documentListeners.get(type) ?? []).forEach((listener) => listener(event));
        },
        createElement(tagName) {
            return new FakeElement([], tagName);
        },
        createTextNode(text) {
            return { nodeType: 3, textContent: text, parentElement: null };
        },
        getElementById(id) {
            return id === 'entries-container' ? entriesContainer : null;
        },
        scrollingElement: { scrollTop: 0, scrollHeight: 0, clientHeight: 0 },
        querySelectorAll() {
            return [];
        },
        querySelector(selector) {
            return selector === '.overlay' ? overlay : null;
        },
    };
    const selectTextCalls = [];
    const tapOutsideMessages = [];
    const mineEntryMessages = [];
    const duplicateCheckMessages = [];
    const showNotesMessages = [];
    const kanjiRedirectMessages = [];
    const kanjiRedirectCommittedMessages = [];
    let currentDuplicateStates = duplicateStates;
    const window = {
        scrollX: 0,
        scrollY: 0,
        scanLength: 24,
        addEventListener() {},
        getSelection() { return { toString: () => '' }; },
        hoshiSelection: {
            selectText(...args) {
                selectTextCalls.push(args);
                return '位置';
            },
        },
    };
    const context = {
        console,
        document,
        getComputedStyle(target) {
            return { zoom: target === documentElement ? htmlZoom : '1' };
        },
        Node: { TEXT_NODE: 3 },
        webkit: {
            messageHandlers: {
                tapOutside: {
                    postMessage(message) {
                        tapOutsideMessages.push(message);
                    },
                },
                popupScrolled: {
                    postMessage() {},
                },
                mineEntry: {
                    postMessage(message) {
                        mineEntryMessages.push(message);
                        return true;
                    },
                },
                duplicateCheck: {
                    postMessage(message) {
                        duplicateCheckMessages.push(message);
                        return currentDuplicateStates;
                    },
                },
                showNotes: {
                    postMessage(message) {
                        showNotesMessages.push(message);
                        return true;
                    },
                },
                kanjiRedirect: {
                    postMessage(message) {
                        kanjiRedirectMessages.push(message);
                        return typeof kanjiResult === 'function' ? kanjiResult(message) : kanjiResult;
                    },
                },
                kanjiRedirectCommitted: {
                    postMessage(message) {
                        kanjiRedirectCommittedMessages.push(message);
                    },
                },
                getEntry: {
                    postMessage(index) {
                        return getEntry?.(index) ?? null;
                    },
                },
            },
        },
        window,
        requestAnimationFrame(callback) {
            callback();
            return 1;
        },
    };
    if (loadJapaneseLanguageAsset) {
        vm.runInNewContext(fs.readFileSync(japaneseLanguageUrl, 'utf8'), context);
    }
    if (loadSelectionAssets) {
        vm.runInNewContext(fs.readFileSync(japaneseSelectionUrl, 'utf8'), context);
        vm.runInNewContext(fs.readFileSync(sharedSelectionUrl, 'utf8'), context);
    }
    vm.runInNewContext(fs.readFileSync(popupSourceUrl, 'utf8'), context);
    return {
        context,
        body,
        document,
        selectTextCalls,
        tapOutsideMessages,
        mineEntryMessages,
        duplicateCheckMessages,
        showNotesMessages,
        kanjiRedirectMessages,
        kanjiRedirectCommittedMessages,
        entriesContainer,
        setDuplicateStates(value) { currentDuplicateStates = value; },
    };
}

async function flushAsyncWork(turns = 8) {
    for (let turn = 0; turn < turns; turn++) {
        await Promise.resolve();
    }
}

function touchEvent(target, x, y, cancelable = false) {
    return {
        target,
        touches: [{ clientX: x, clientY: y }],
        changedTouches: [{ clientX: x, clientY: y }],
        cancelable,
        defaultPrevented: false,
        preventDefault() {
            this.defaultPrevented = true;
        },
    };
}

function clickEvent(target, x, y) {
    return {
        target,
        clientX: x,
        clientY: y,
        defaultPrevented: false,
        preventDefault() {
            this.defaultPrevented = true;
        },
    };
}

function descendants(element) {
    const out = [];
    for (const child of element.children ?? []) {
        out.push(child);
        out.push(...descendants(child));
    }
    return out;
}

test('popup touch tap selects text even when WebView suppresses the follow-up click', () => {
    const { context, selectTextCalls, tapOutsideMessages } = popupContext();
    const container = new FakeContainer();
    const target = new FakeElement(['.glossary-content']);

    context.installPopupTapHandlers(container);
    container.dispatch('touchstart', touchEvent(target, 48, 148));
    const end = touchEvent(target, 48, 148, true);
    container.dispatch('touchend', end);

    assert.equal(selectTextCalls.length, 1);
    assert.deepEqual(selectTextCalls[0], [48, 148, 24, 48, 148]);
    assert.equal(tapOutsideMessages.length, 0);
    assert.equal(end.defaultPrevented, true);
});

test('popup tap coordinates ignore user body zoom when popup scale is active', () => {
    const { context, selectTextCalls } = popupContext({
        htmlZoom: '0.95',
        htmlProbeWidth: 95,
        bodyProbeWidth: 104.5,
    });
    const container = new FakeContainer();
    const target = new FakeElement(['.glossary-content']);

    context.installPopupTapHandlers(container);
    container.dispatch('click', clickEvent(target, 45.35555648803711, 233.93334197998047));

    assert.equal(selectTextCalls.length, 1);
    assert.deepEqual(
        selectTextCalls[0],
        [45.35555648803711, 233.93334197998047, 24, 45.35555648803711, 233.93334197998047],
    );
});

test('popup geometry keeps scaled visual positions in the scroll coordinate space', () => {
    const { context, document } = popupContext({ htmlZoom: '1.5' });
    document.scrollingElement = { scrollTop: 615 };
    context.window.scrollX = 10;
    context.window.scrollY = 20;
    const entry = new FakeElement();
    entry.offsetTop = 615;
    entry.getBoundingClientRect = () => ({ top: 307 });
    const scrollCalls = [];
    entry.scrollIntoView = (options) => scrollCalls.push(options);

    assert.equal(context.window.hoshiPopupGeometry.elementDocumentTop(entry), 922);
    context.window.hoshiPopupGeometry.scrollElementToTop(entry);
    assert.equal(scrollCalls.length, 1);
    assert.equal(scrollCalls[0].block, 'start');
    assert.equal(scrollCalls[0].inline, 'nearest');
    assert.equal(scrollCalls[0].behavior, 'instant');
    assert.deepEqual(
        JSON.parse(JSON.stringify(context.window.hoshiPopupGeometry.bridgeSelectionRect({
            x: 100,
            y: 200,
            width: 40,
            height: 20,
        }))),
        { x: 155, y: 310, width: 60, height: 30 },
    );
});

test('popup touch tap suppresses the duplicate click generated for the same tap', () => {
    const { context, selectTextCalls } = popupContext();
    const container = new FakeContainer();
    const target = new FakeElement(['.glossary-content']);

    context.installPopupTapHandlers(container);
    container.dispatch('touchstart', touchEvent(target, 48, 148));
    container.dispatch('touchend', touchEvent(target, 48, 148, true));
    const duplicateClick = clickEvent(target, 49, 149);
    container.dispatch('click', duplicateClick);

    assert.equal(selectTextCalls.length, 1);
    assert.equal(duplicateClick.defaultPrevented, true);
});

test('popup touch tap lets interactive controls keep their click behavior', () => {
    const { context, selectTextCalls, tapOutsideMessages } = popupContext();
    const container = new FakeContainer();
    const target = new FakeElement(['summary']);

    context.installPopupTapHandlers(container);
    container.dispatch('touchstart', touchEvent(target, 48, 148));
    const end = touchEvent(target, 48, 148, true);
    container.dispatch('touchend', end);
    const click = clickEvent(target, 48, 148);
    container.dispatch('click', click);

    assert.equal(selectTextCalls.length, 0);
    assert.equal(tapOutsideMessages.length, 0);
    assert.equal(end.defaultPrevented, false);
    assert.equal(click.defaultPrevented, false);
});

test('popup click still selects text when there was no touch fallback', () => {
    const { context, selectTextCalls } = popupContext();
    const container = new FakeContainer();
    const target = new FakeElement(['.glossary-content']);

    context.installPopupTapHandlers(container);
    container.dispatch('click', clickEvent(target, 48, 148));

    assert.equal(selectTextCalls.length, 1);
});

test('popup content blank area click posts tapOutside through the document handler', () => {
    const { document, tapOutsideMessages } = popupContext();
    const target = new FakeElement();

    document.dispatch('click', clickEvent(target, 48, 480));

    assert.deepEqual(tapOutsideMessages, [null]);
});

test('popup viewport blank area click posts tapOutside when it misses body content', () => {
    const { document, tapOutsideMessages } = popupContext();

    document.dispatch('click', clickEvent(document.documentElement, 48, 640));

    assert.deepEqual(tapOutsideMessages, [null]);
});

test('popup action controls remain DOM buttons even if a legacy native button flag is present', () => {
    const { context } = popupContext();

    context.window.nativePopupButtons = true;
    const audioSlot = context.createButtonSlot('audio', 0);
    const mineSlot = context.createButtonSlot('mine', 1, false);
    const circleSlot = context.createButtonSlot('mine', 2, true, 'format-circle', 'circle-small');

    assert.equal(audioSlot.tagName, 'BUTTON');
    assert.equal(audioSlot.type, 'button');
    assert.equal(audioSlot.getAttribute('aria-label'), 'Play audio');
    assert.equal(audioSlot.children.length, 1);
    assert.equal(audioSlot.children[0].className, 'button-slot-icon');
    assert.equal(mineSlot.tagName, 'BUTTON');
    assert.equal(mineSlot.disabled, true);
    assert.equal(circleSlot.dataset.formatId, 'format-circle');
    assert.match(circleSlot.style.properties.get('--button-icon-url'), /add_circle\.svg/);
});

test('popup renders ordered format buttons with independent icon and disabled state', () => {
    const { context } = popupContext();
    context.window.ankiBackendAvailable = true;
    context.window.ankiFormats = [
        { id: 'word', icon: 'square', isValid: true },
        { id: 'sentence', icon: 'circle-small', isValid: false },
        { id: 'listening', icon: 'diamond', isValid: true },
    ];
    const container = new FakeElement();

    const formats = context.appendAnkiFormatButtons(container, 4);
    const mineButtons = descendants(container).filter((button) => button.dataset.kind === 'mine');

    assert.equal(formats.length, 3);
    assert.deepEqual(container.children.map((button) => button.dataset.formatId), ['word', 'sentence', 'listening']);
    assert.equal(mineButtons[0].disabled, false);
    assert.equal(mineButtons[1].disabled, true);
    assert.match(mineButtons[1].style.properties.get('--button-icon-url'), /add_circle\.svg/);
    assert.match(mineButtons[2].style.properties.get('--button-icon-url'), /diamond\.svg/);
});

test('popup places Anki formats before audio and keeps each notes action with its format', async () => {
    const { context } = popupContext({
        duplicateStates: { word: true, sentence: true },
    });
    context.window.audioSources = ['https://example.com/audio'];
    context.window.ankiBackendAvailable = true;
    context.window.allowDupes = false;
    context.window.disableShowNotes = false;
    context.window.ankiFormats = [
        { id: 'word', icon: 'square', isValid: true },
        { id: 'sentence', icon: 'circle', isValid: true },
    ];
    context.window.lookupEntries = [{ expression: '猫', reading: '猫' }];

    const header = context.createEntryHeader({ expression: '猫', reading: '猫' }, 0);
    await Promise.resolve();
    const buttons = header.children.find((child) => child.className === 'header-buttons');

    assert.deepEqual(
        buttons.children.map((child) => child.dataset.formatId || child.dataset.kind),
        ['word', 'sentence', 'audio'],
    );
    const [wordActions, sentenceActions, audioButton] = buttons.children;
    assert.equal(wordActions.className, 'anki-format-actions');
    assert.deepEqual(wordActions.children.map((child) => child.dataset.kind), ['notes', 'mine']);
    assert.equal(wordActions.children[0].hidden, false);
    assert.equal(sentenceActions.className, 'anki-format-actions');
    assert.deepEqual(sentenceActions.children.map((child) => child.dataset.kind), ['mine', 'notes']);
    assert.equal(sentenceActions.children[1].dataset.placement, 'above');
    assert.equal(sentenceActions.children[1].hidden, false);
    assert.equal(audioButton.dataset.kind, 'audio');
});

test('duplicate refresh updates every format and creates or removes show-notes buttons', async () => {
    const setup = popupContext({
        duplicateStates: { word: true, sentence: false, listening: true },
    });
    const { context } = setup;
    context.window.lookupEntries = [{ expression: '猫', reading: 'ねこ', matched: '猫' }];
    context.window.ankiBackendAvailable = true;
    context.window.allowDupes = false;
    context.window.disableShowNotes = false;
    context.window.ankiFormats = [
        { id: 'word', icon: 'square', isValid: true },
        { id: 'sentence', icon: 'circle', isValid: true },
        { id: 'listening', icon: 'diamond', isValid: true },
    ];
    const container = new FakeElement();
    context.appendAnkiFormatButtons(container, 0);
    const notesButtons = descendants(container).filter((button) => button.dataset.kind === 'notes');

    assert.deepEqual(notesButtons.map((button) => button.style.display), ['none', 'none', 'none']);

    await context.refreshAnkiDuplicateStates(0, container);

    const mineButtons = descendants(container).filter((button) => button.dataset.kind === 'mine');
    assert.deepEqual(mineButtons.map((button) => button.dataset.state), ['duplicate', 'default', 'duplicate']);
    assert.deepEqual(mineButtons.map((button) => button.disabled), [true, false, true]);
    assert.equal(descendants(container).filter((button) => button.dataset.kind === 'notes' && !button.hidden).length, 2);
    assert.deepEqual(notesButtons.map((button) => button.style.display), ['', 'none', '']);
    assert.match(mineButtons[2].style.properties.get('--button-icon-url'), /diamond_fill\.svg/);

    setup.setDuplicateStates({ word: false, sentence: false, listening: false });
    await context.refreshAnkiDuplicateStates(0, container);
    assert.equal(descendants(container).filter((button) => button.dataset.kind === 'notes' && !button.hidden).length, 0);
    assert.deepEqual(notesButtons.map((button) => button.style.display), ['none', 'none', 'none']);
    assert.deepEqual(mineButtons.map((button) => button.disabled), [false, false, false]);
});

test('popup language detection works with split selection policy assets', () => {
    const { context } = popupContext({
        loadJapaneseLanguageAsset: true,
        loadSelectionAssets: true,
    });

    assert.doesNotThrow(() => context.getLanguageFromText('plain English glossary', 'en'));
    assert.equal(context.getLanguageFromText('plain English glossary', 'en'), 'en');
    assert.equal(context.getLanguageFromText('猫 glossary', 'en'), 'ja');
});

test('popup language detection does not depend on the selection object', () => {
    const { context } = popupContext({ loadJapaneseLanguageAsset: true });
    delete context.window.hoshiSelection;

    assert.equal(context.getLanguageFromText('猫 glossary', 'en'), 'ja');
});

test('popup renders each deinflection trace candidate as its own tag row', () => {
    const { context } = popupContext();

    const tags = context.createTags({
        expression: '食べる',
        reading: 'たべる',
        deinflectionTraceRows: [
            [
                { name: 'polite', description: 'Polite form' },
                { name: 'past', description: 'Past tense' },
            ],
            [
                { name: 'redirect', description: 'Dictionary redirect' },
            ],
        ],
        frequencies: [],
        pitches: [],
    });

    assert.ok(tags);
    const rows = tags.children.filter((node) => String(node.className).split(' ').includes('tag-row'));
    assert.equal(rows.length, 2);
    assert.deepEqual(rows[0].children.map((node) => node.textContent), ['polite', 'past']);
    assert.deepEqual(rows[1].children.map((node) => node.textContent), ['redirect']);
});

test('popup transcription entries do not render as Japanese pitch accents', () => {
    const { context } = popupContext();

    const tags = context.createTags({
        expression: 'read',
        reading: 'read',
        deinflectionTraceRows: [],
        frequencies: [],
        pitches: [
            {
                dictionary: 'English',
                pitches: [],
                transcriptions: ['/riːd/', '/rɛd/'],
            },
        ],
    });
    const nodes = descendants(tags);

    assert.ok(tags);
    assert.equal(nodes.some((node) => String(node.className).split(' ').includes('transcription-list')), true);
    assert.equal(nodes.some((node) => String(node.className).split(' ').includes('pitch-group')), false);
    assert.equal(nodes.some((node) => node.textContent === '/riːd/'), true);
});

test('popup preserves IPA dictionary transcription delimiters', () => {
    const { context } = popupContext();

    const tags = context.createTags({
        expression: 'read',
        reading: 'read',
        deinflectionTraceRows: [],
        frequencies: [],
        pitches: [
            {
                dictionary: 'seth-oald-ipa',
                pitches: [],
                transcriptions: ['/riːd/'],
            },
        ],
    });
    const nodes = descendants(tags);

    assert.equal(nodes.some((node) => node.textContent === '/riːd/'), true);
    assert.equal(nodes.some((node) => node.textContent === '//riːd//'), false);
});

function popupTermNavigator(entryCount = 3) {
    const { context, document } = popupContext();
    const entries = [];
    const scrollTargets = [];
    let scrollTop = 0;
    const navigator = context.window.createPopupTermNavigator({
        entryCount: () => entryCount,
        entries: () => entries,
        scrollTop: () => scrollTop,
        scrollTo: (entry) => {
            scrollTop = entry.top;
            scrollTargets.push(entry.top);
        },
    });
    return {
        entries,
        context,
        document,
        navigator,
        scrollTargets,
        setScrollTop(value) { scrollTop = value; },
    };
}

test('popup term navigation moves between rendered entry headers without wrapping', () => {
    const setup = popupTermNavigator();
    setup.entries.push(
        { index: 0, top: 0 },
        { index: 1, top: 120 },
        { index: 2, top: 300 },
    );

    setup.navigator.navigate('next');
    setup.navigator.navigate('next');
    setup.navigator.navigate('next');
    setup.navigator.navigate('previous');

    assert.deepEqual(setup.scrollTargets, [120, 300, 120]);
});

test('popup previous term first returns to the current entry header after manual scrolling', () => {
    const setup = popupTermNavigator();
    setup.entries.push(
        { index: 0, top: 0 },
        { index: 1, top: 120 },
        { index: 2, top: 300 },
    );
    setup.setScrollTop(180);

    setup.navigator.navigate('previous');
    setup.navigator.navigate('previous');

    assert.deepEqual(setup.scrollTargets, [120, 0]);
});

test('popup term navigation queues repeated moves until the target entry renders', () => {
    const setup = popupTermNavigator();
    setup.entries.push({ index: 0, top: 0 });

    setup.navigator.navigate('next');
    setup.navigator.navigate('next');
    setup.entries.push({ index: 1, top: 120 });
    setup.navigator.entryRendered();
    setup.entries.push({ index: 2, top: 300 });
    setup.navigator.entryRendered();

    assert.deepEqual(setup.scrollTargets, [300]);
});

test('a delayed programmatic scroll does not cancel a newer pending term move', () => {
    const setup = popupTermNavigator();
    setup.entries.push(
        { index: 0, top: 0 },
        { index: 1, top: 120 },
    );
    setup.context.window.installPopupTermNavigationInput(setup.navigator, setup.document);

    setup.navigator.navigate('next');
    setup.navigator.navigate('next');
    setup.document.dispatch('scroll', {});
    setup.entries.push({ index: 2, top: 300 });
    setup.navigator.entryRendered();

    assert.deepEqual(setup.scrollTargets, [120, 300]);
});

test('manual scrolling and reset cancel pending popup term navigation', () => {
    const setup = popupTermNavigator();
    setup.entries.push({ index: 0, top: 0 });
    setup.context.window.installPopupTermNavigationInput(setup.navigator, setup.document);

    setup.navigator.navigate('next');
    setup.document.dispatch('pointerdown', {});
    setup.entries.push({ index: 1, top: 120 });
    setup.navigator.entryRendered();
    setup.setScrollTop(120);
    setup.navigator.navigate('next');
    setup.navigator.reset();
    setup.entries.push({ index: 2, top: 300 });
    setup.navigator.entryRendered();

    assert.deepEqual(setup.scrollTargets, []);
});

test('popup builds Yomitan-compatible phonetic transcriptions Anki HTML', () => {
    const { context } = popupContext();

    const html = context.constructPhoneticTranscriptionsHtml([
        {
            dictionary: 'seth-oald-ipa',
            pitches: [],
            transcriptions: ['/riːd/', '/rɛd/'],
        },
    ]);

    assert.equal(
        html,
        '<ul><li class="pronunciation" data-pronunciation-type="phonetic-transcription">/riːd/</li><li class="pronunciation" data-pronunciation-type="phonetic-transcription">/rɛd/</li></ul>',
    );
});

test('mineEntry posts phonetic transcriptions for Anki handlebar rendering', async () => {
    const { context, mineEntryMessages } = popupContext();
    context.window.lookupEntries = [{ glossaries: [] }];

    await context.mineEntry(
        'read',
        'read',
        [],
        [{ dictionary: 'seth-oald-ipa', pitches: [], transcriptions: ['/riːd/'] }],
        [],
        'read',
        0,
        'read',
        'format-a',
    );

    assert.equal(mineEntryMessages.length, 1);
    assert.equal(mineEntryMessages[0].formatId, 'format-a');
    assert.equal(
        mineEntryMessages[0].payload.phoneticTranscriptions,
        '<ul><li class="pronunciation" data-pronunciation-type="phonetic-transcription">/riːd/</li></ul>',
    );
});

test('show-notes and duplicate payloads use stable format ids and handlebar values', async () => {
    const { context, showNotesMessages } = popupContext();
    context.window.lookupEntries = [{ expression: '猫', reading: 'ねこ', matched: '猫' }];

    const values = context.duplicateValuesForEntry(context.window.lookupEntries[0]);
    const shown = await context.showNotesAtIndex(0, 'format-cat');

    assert.equal(values['{expression}'], '猫');
    assert.equal(values['{reading}'], 'ねこ');
    assert.equal(context.duplicateStateForFormat({ 'format-cat': true }, 'format-cat'), true);
    assert.equal(context.duplicateStateForFormat({}, 'deleted-format'), null);
    assert.equal(shown, true);
    assert.deepEqual(JSON.parse(JSON.stringify(showNotesMessages[0])), {
        formatId: 'format-cat',
        values: JSON.parse(JSON.stringify(values)),
    });
});

test('pitch graph handlebars receive deduplicated SVGs and first graph selection', () => {
    const { context } = popupContext();
    context.window.deduplicatePitchAccents = true;
    const pitches = [
        { dictionary: 'A', pitches: [{ position: 0 }, { position: 2 }] },
        { dictionary: 'B', pitches: [{ position: 2 }, { position: 1 }] },
    ];

    const all = context.constructPitchAccentGraphsHtml(pitches, 'ねこ');
    const first = context.constructPitchAccentGraphsHtml(pitches, 'ねこ', true);

    assert.equal((all.match(/<svg/g) || []).length, 3);
    assert.equal((first.match(/<svg/g) || []).length, 1);
    assert.match(first, /data-downstep="0"/);
    assert.match(all, /stroke-dasharray:5 5/);
});

test('pitch graph output keeps duplicates when disabled and omits list for one graph', () => {
    const { context } = popupContext();
    context.window.deduplicatePitchAccents = false;

    const multiple = context.constructPitchAccentGraphsHtml([
        { pitches: [{ position: 1 }] },
        { pitches: [{ position: 1 }] },
    ], 'ねこ');
    const single = context.constructPitchAccentGraphsHtml([{ pitches: [{ position: 1 }] }], 'ねこ');

    assert.equal((multiple.match(/<svg/g) || []).length, 2);
    assert.match(multiple, /^<ol>/);
    assert.match(single, /^<svg/);
    assert.doesNotMatch(single, /<ol>/);
});

test('complete pitch renders string patterns with 1-based nasal and devoice mora markers', () => {
    const { context } = popupContext();
    const group = context.createPitchGroup({
        dictionary: 'Accent',
        pitches: [{ position: 'LHL', nasal: [1], devoice: [2] }],
    }, 'ねこ');
    const morae = descendants(group).filter((node) => node.className === 'pronunciation-mora');

    assert.equal(morae.length, 2);
    assert.equal(morae[0].dataset.pitch, 'low');
    assert.equal(morae[0].dataset.nasal, 'true');
    assert.equal(morae[1].dataset.pitch, 'high');
    assert.equal(morae[1].dataset.devoice, 'true');
    assert.match(context.constructPitchPositionHtml([{ pitches: [{ position: 'LHL' }] }]), />2</);
});

test('nasal pitch renders the base kana for voiced and semi-voiced morae', () => {
    const { context } = popupContext();
    const cases = [
        { reading: 'かぎ', nasalPosition: 2, expected: 'き' },
        { reading: 'ぱく', nasalPosition: 1, expected: 'は' },
    ];

    for (const { reading, nasalPosition, expected } of cases) {
        const group = context.createPitchGroup({
            dictionary: 'NHK+',
            pitches: [{ position: 'LHL', nasal: [nasalPosition], devoice: [] }],
        }, reading);
        const nasalMora = descendants(group)
            .filter((node) => node.className === 'pronunciation-mora')
            .find((node) => node.dataset.nasal === 'true');
        const characterGroup = descendants(nasalMora)
            .find((node) => node.className === 'pronunciation-character-group');

        assert.equal(characterGroup.children[0].textContent, expected);
    }
});

test('nasal pitch keeps the small kana tail outside the marked character group', () => {
    const { context } = popupContext();
    const group = context.createPitchGroup({
        dictionary: 'NHK+',
        pitches: [{ position: 'HLL', nasal: [1], devoice: [] }],
    }, 'ぎゃく');
    const nasalMora = descendants(group)
        .filter((node) => node.className === 'pronunciation-mora')
        .find((node) => node.dataset.nasal === 'true');
    const characterGroup = descendants(nasalMora)
        .find((node) => node.className === 'pronunciation-character-group');

    assert.equal(characterGroup.children[0].textContent, 'き');
    assert.equal(nasalMora.children[1].textContent, 'ゃ');
});

test('kanji touch redirect renders in place and suppresses its duplicate click', async () => {
    const result = {
        character: '星',
        entries: [{ dictName: 'KANJIDIC', onyomi: 'セイ', kunyomi: 'ほし', meanings: ['star'] }],
    };
    const setup = popupContext({ kanjiResult: result });
    const container = new FakeContainer();
    const target = new FakeElement(['.kanji-char']);
    target.textContent = '星';
    setup.context.installPopupTapHandlers(container);

    container.dispatch('touchstart', touchEvent(target, 20, 30));
    container.dispatch('touchend', touchEvent(target, 20, 30, true));
    const duplicateClick = clickEvent(target, 20, 30);
    container.dispatch('click', duplicateClick);
    await Promise.resolve();

    assert.deepEqual(setup.kanjiRedirectMessages, ['星']);
    assert.equal(setup.kanjiRedirectCommittedMessages.length, 1);
    assert.equal(duplicateClick.defaultPrevented, true);
    assert.equal(setup.selectTextCalls.length, 0);
    assert.equal(setup.entriesContainer.children[0].className, 'entry kanji-entry');

    setup.context.window.navigateBack();
    assert.equal(setup.entriesContainer.children.length, 0);
    setup.context.window.navigateForward();
    assert.equal(setup.entriesContainer.children[0].className, 'entry kanji-entry');
});

test('kanji history resumes entries that were still loading when redirect started', async () => {
    let resolveSecondEntry;
    const secondEntry = new Promise((resolve) => { resolveSecondEntry = resolve; });
    const entries = [
        { expression: '星空', reading: 'ほしぞら', glossaries: [] },
        { expression: '星', reading: 'ほし', glossaries: [] },
    ];
    const setup = popupContext({
        kanjiResult: {
            character: '星',
            entries: [{ dictName: 'KANJIDIC', onyomi: 'セイ', kunyomi: 'ほし', meanings: ['star'] }],
        },
        getEntry(index) {
            return index === 0 ? entries[0] : secondEntry;
        },
    });
    setup.context.window.entryCount = entries.length;
    setup.context.window.renderPopup();
    await flushAsyncWork();
    assert.equal(setup.entriesContainer.children.filter((node) => node.dataset?.entryIndex !== undefined).length, 1);

    const target = new FakeElement(['.kanji-char']);
    target.textContent = '星';
    setup.context.handlePopupTap(target, 10, 10);
    await flushAsyncWork();
    setup.context.window.navigateBack();
    resolveSecondEntry(entries[1]);
    await flushAsyncWork(16);

    assert.equal(setup.entriesContainer.children.filter((node) => node.dataset?.entryIndex !== undefined).length, 2);
});

test('term redirect history never resumes old DOM from the replacement host result set', async () => {
    let resolveOldSecondEntry;
    const oldSecondEntry = new Promise((resolve) => { resolveOldSecondEntry = resolve; });
    const oldEntries = [
        { expression: '古い一', reading: '', glossaries: [] },
        { expression: '古い二', reading: '', glossaries: [] },
    ];
    const newEntries = [
        { expression: '新しい一', reading: '', glossaries: [] },
        { expression: '新しい二', reading: '', glossaries: [] },
    ];
    let hostEntries = oldEntries;
    const setup = popupContext({
        getEntry(index) {
            if (hostEntries === oldEntries && index === 1) return oldSecondEntry;
            return hostEntries[index];
        },
    });
    setup.context.window.entryCount = oldEntries.length;
    setup.context.window.renderPopup();
    await flushAsyncWork();
    assert.equal(setup.entriesContainer.children.filter((node) => node.dataset?.entryIndex !== undefined).length, 1);

    hostEntries = newEntries;
    setup.context.redirect(newEntries.length);
    await flushAsyncWork(16);
    setup.context.window.navigateBack();
    await flushAsyncWork(16);

    assert.equal(setup.entriesContainer.children.filter((node) => node.dataset?.entryIndex !== undefined).length, 1);
    resolveOldSecondEntry(oldEntries[1]);
    await flushAsyncWork();
    assert.equal(setup.entriesContainer.children.filter((node) => node.dataset?.entryIndex !== undefined).length, 1);
});

test('only the latest Kanji response may replace popup state or commit native history', async () => {
    const resolvers = new Map();
    const setup = popupContext({
        kanjiResult(kanji) {
            return new Promise((resolve) => resolvers.set(kanji, resolve));
        },
    });
    const star = new FakeElement(['.kanji-char']);
    star.textContent = '星';
    const sun = new FakeElement(['.kanji-char']);
    sun.textContent = '日';

    setup.context.handlePopupTap(star, 10, 10);
    setup.context.handlePopupTap(sun, 10, 10);
    resolvers.get('日')({
        character: '日',
        entries: [{ dictName: 'KANJIDIC', onyomi: 'ニチ', kunyomi: 'ひ', meanings: ['sun'] }],
    });
    await flushAsyncWork();
    resolvers.get('星')({
        character: '星',
        entries: [{ dictName: 'KANJIDIC', onyomi: 'セイ', kunyomi: 'ほし', meanings: ['star'] }],
    });
    await flushAsyncWork();

    const renderedCharacter = descendants(setup.entriesContainer.children[0])
        .find((node) => node.className === 'kanji')?.textContent;
    assert.equal(renderedCharacter, '日');
    assert.equal(setup.kanjiRedirectCommittedMessages.length, 1);

    const late = new FakeElement(['.kanji-char']);
    late.textContent = '月';
    setup.context.handlePopupTap(late, 10, 10);
    setup.context.window.replacePopupResults(0, []);
    resolvers.get('月')({
        character: '月',
        entries: [{ dictName: 'KANJIDIC', onyomi: 'ゲツ', kunyomi: 'つき', meanings: ['moon'] }],
    });
    await flushAsyncWork();

    assert.equal(setup.entriesContainer.children.length, 0);
    assert.equal(setup.kanjiRedirectCommittedMessages.length, 1);
});
