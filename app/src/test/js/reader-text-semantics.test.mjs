import assert from 'node:assert/strict';
import fs from 'node:fs';
import test from 'node:test';
import vm from 'node:vm';

const readerTextSemanticsUrl = new URL('../../main/assets/hoshi-web/reader/reader-text-semantics.js', import.meta.url);

function loadTextSemantics() {
    const source = fs.readFileSync(readerTextSemanticsUrl, 'utf8');
    const window = {};
    vm.runInNewContext(source, { window });
    return window.hoshiReaderTextSemantics;
}

test('reader text semantics normalizes matchable text while preserving raw counts', () => {
    const semantics = loadTextSemantics();

    assert.equal(semantics.normalizeText('一、二。A!'), '一二A');
    assert.equal(semantics.countChars('一、二。A!'), 3);
    assert.equal(semantics.countRawChars('一、二。A!'), 6);
    assert.equal(semantics.isMatchableChar('一'), true);
    assert.equal(semantics.isMatchableChar('、'), false);
});
