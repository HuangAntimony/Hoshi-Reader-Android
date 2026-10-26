import assert from 'node:assert/strict';
import fs from 'node:fs';
import test from 'node:test';
import vm from 'node:vm';

const source = fs.readFileSync(new URL('../../main/assets/hoshi-web/reader/reader-document-preparation.js', import.meta.url), 'utf8');

function fixture({ attached = true, path = '/epub/chapter.xhtml', frame = false, display = '' } = {}) {
    const values = new Map(display ? [['display', [display, 'important']]] : []);
    const root = { style: {
        getPropertyValue: name => values.get(name)?.[0] ?? '',
        getPropertyPriority: name => values.get(name)?.[1] ?? '',
        setProperty: (name, value, priority) => values.set(name, [value, priority]),
        removeProperty: name => values.delete(name),
    } };
    const document = { documentElement: attached ? root : null };
    let callback;
    let observing = false;
    const window = { document, location: { pathname: path } };
    window.top = frame ? {} : window;
    const context = vm.createContext({ window, document, MutationObserver: class {
        constructor(fn) { callback = fn; }
        observe() { observing = true; }
        disconnect() { observing = false; }
    } });
    vm.runInContext(source, context);
    return { window, root, values, context, get observing() { return observing; }, attach() {
        document.documentElement = root;
        if (observing) callback();
    } };
}

test('defers initial layout and restores publisher display exactly once', () => {
    for (const display of ['', 'block']) {
        const f = fixture({ display });
        assert.equal(f.root.style.getPropertyValue('display'), 'none');
        assert.equal(f.root.style.getPropertyPriority('display'), 'important');
        f.window.hoshiReaderDocumentPreparation.resume();
        assert.equal(f.root.style.getPropertyValue('display'), display);
        assert.equal(f.root.style.getPropertyPriority('display'), display ? 'important' : '');
        f.root.style.setProperty('display', 'flex', '');
        f.window.hoshiReaderDocumentPreparation.resume();
        assert.equal(f.root.style.getPropertyValue('display'), 'flex');
        assert.equal(f.observing, false);
    }
});

test('waits for the XML or HTML root and disconnects after attaching', () => {
    const f = fixture({ attached: false });
    assert.equal(f.observing, true);
    f.attach();
    assert.equal(f.root.style.getPropertyValue('display'), 'none');
    assert.equal(f.observing, false);
    f.window.hoshiReaderDocumentPreparation.resume();
    assert.equal(f.root.style.getPropertyValue('display'), '');
});

test('resume before a root exists cannot hide a later root', () => {
    const f = fixture({ attached: false });
    f.window.hoshiReaderDocumentPreparation.resume();
    f.attach();
    assert.equal(f.observing, false);
    assert.equal(f.root.style.getPropertyValue('display'), '');
});

test('leaves popup documents and subframes alone', () => {
    for (const options of [{ path: '/popup/popup.html' }, { frame: true }]) {
        const f = fixture(options);
        assert.equal(f.window.hoshiReaderDocumentPreparation, undefined);
        assert.equal(f.root.style.getPropertyValue('display'), '');
        assert.equal(f.observing, false);
    }
});

test('duplicate installation does not save the temporary display as publisher style', () => {
    const f = fixture();
    vm.runInContext(source, f.context);
    f.window.hoshiReaderDocumentPreparation.resume();
    assert.equal(f.root.style.getPropertyValue('display'), '');
});
