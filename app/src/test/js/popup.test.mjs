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
        querySelectorAll() {
            return [];
        },
    };
    const selectTextCalls = [];
    const tapOutsideMessages = [];
    const mineEntryMessages = [];
    const duplicateCheckMessages = [];
    const showNotesMessages = [];
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
            },
        },
        window,
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
        setDuplicateStates(value) { currentDuplicateStates = value; },
    };
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

    assert.equal(formats.length, 3);
    assert.deepEqual(container.children.map((button) => button.dataset.formatId), ['word', 'sentence', 'listening']);
    assert.equal(container.children[0].disabled, false);
    assert.equal(container.children[1].disabled, true);
    assert.match(container.children[1].style.properties.get('--button-icon-url'), /add_circle\.svg/);
    assert.match(container.children[2].style.properties.get('--button-icon-url'), /diamond\.svg/);
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

    await context.refreshAnkiDuplicateStates(0, container);

    const mineButtons = container.children.filter((button) => button.dataset.kind === 'mine');
    assert.deepEqual(mineButtons.map((button) => button.dataset.state), ['duplicate', 'default', 'duplicate']);
    assert.deepEqual(mineButtons.map((button) => button.disabled), [true, false, true]);
    assert.equal(container.children.filter((button) => button.dataset.kind === 'notes').length, 2);
    assert.match(mineButtons[2].style.properties.get('--button-icon-url'), /diamond_fill\.svg/);

    setup.setDuplicateStates({ word: false, sentence: false, listening: false });
    await context.refreshAnkiDuplicateStates(0, container);
    assert.equal(container.children.filter((button) => button.dataset.kind === 'notes').length, 0);
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
                pitchPositions: [],
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
                pitchPositions: [],
                transcriptions: ['/riːd/'],
            },
        ],
    });
    const nodes = descendants(tags);

    assert.equal(nodes.some((node) => node.textContent === '/riːd/'), true);
    assert.equal(nodes.some((node) => node.textContent === '//riːd//'), false);
});

test('popup builds Yomitan-compatible phonetic transcriptions Anki HTML', () => {
    const { context } = popupContext();

    const html = context.constructPhoneticTranscriptionsHtml([
        {
            dictionary: 'seth-oald-ipa',
            pitchPositions: [],
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
        [{ dictionary: 'seth-oald-ipa', pitchPositions: [], transcriptions: ['/riːd/'] }],
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
        { dictionary: 'A', pitchPositions: [0, 2] },
        { dictionary: 'B', pitchPositions: [2, 1] },
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
        { pitchPositions: [1] },
        { pitchPositions: [1] },
    ], 'ねこ');
    const single = context.constructPitchAccentGraphsHtml([{ pitchPositions: [1] }], 'ねこ');

    assert.equal((multiple.match(/<svg/g) || []).length, 2);
    assert.match(multiple, /^<ol>/);
    assert.match(single, /^<svg/);
    assert.doesNotMatch(single, /<ol>/);
});
