import assert from 'node:assert/strict';
import fs from 'node:fs';
import test from 'node:test';
import vm from 'node:vm';

const readerTextSemanticsUrl = new URL('../../main/assets/hoshi-web/reader/reader-text-semantics.js', import.meta.url);
const readerVnRangeMapUrl = new URL('../../main/assets/hoshi-web/reader/reader-vn-range-map.js', import.meta.url);

function loadRangeMap() {
    const window = {};
    vm.runInNewContext(
        [
            fs.readFileSync(readerTextSemanticsUrl, 'utf8'),
            fs.readFileSync(readerVnRangeMapUrl, 'utf8'),
        ].join('\n'),
        { window },
    );
    const semantics = window.hoshiReaderTextSemantics;
    const reader = {
        countChars: semantics.countChars.bind(semantics),
        countRawChars: semantics.countRawChars.bind(semantics),
    };
    return window.hoshiReaderVnRangeMap.create(reader);
}

test('VN range map converts clone UTF-16 positions to chapter offsets', () => {
    const rangeMap = loadRangeMap();
    const clone = { textContent: '𠮟激' };
    rangeMap.registerCloneTextOffset(clone, 10, 12);

    assert.deepEqual(
        JSON.parse(JSON.stringify(rangeMap.chapterPositionForClone(clone, 2))),
        { matchableOffset: 11, rawOffset: 13 },
    );
    assert.equal(rangeMap.chapterPositionForClone({ textContent: '外' }, 0), null);
});
