import assert from 'node:assert/strict';
import fs from 'node:fs';
import test from 'node:test';
import vm from 'node:vm';

const projectionUrl = new URL(
    '../../main/assets/hoshi-web/reader/reader-vn-selection-projection.js',
    import.meta.url,
);

function loadProjectionFactory() {
    const window = {};
    vm.runInNewContext(fs.readFileSync(projectionUrl, 'utf8'), {
        window,
    });
    return window.hoshiReaderVnSelectionProjection;
}

test('VN selection projection maps clone hits to source semantics and ranges back to visible geometry', () => {
    const renderedNode = { nodeValue: '激' };
    const sourceNode = { nodeValue: '激しい抵抗を見せていた。' };
    const visibleNode = { nodeValue: '激' };
    const calls = [];
    const reader = {
        contentStream: {
            sourcePositionForRawOffset(rawOffset) {
                calls.push(['sourcePositionForRawOffset', rawOffset]);
                return { node: sourceNode, offset: 0 };
            },
            rawOffsetForSourcePosition(node, offset) {
                calls.push(['rawOffsetForSourcePosition', node, offset]);
                return offset;
            },
            matchableOffsetForSourcePosition(node, offset) {
                calls.push(['matchableOffsetForSourcePosition', node, offset]);
                return 41 + offset;
            },
        },
        rangeMap: {
            chapterPositionForClone(node, offset) {
                calls.push(['chapterPositionForClone', node, offset]);
                return { matchableOffset: 41, rawOffset: 52 };
            },
            collectRawSegments(offset, length) {
                calls.push(['collectRawSegments', offset, length]);
                return [{ node: visibleNode, start: 0, end: 1 }];
            },
        },
    };

    const projection = loadProjectionFactory().create(reader);

    assert.deepEqual(
        projection.toSemanticHit({ node: renderedNode, offset: 0 }),
        { node: sourceNode, offset: 0 },
    );
    assert.equal(
        projection.normalizedOffsetForHit({ node: sourceNode, offset: 2 }),
        43,
    );
    assert.deepEqual(
        Array.from(projection.visibleRangesForSemanticRanges([
            { node: sourceNode, start: 0, end: 11 },
        ])),
        [{ node: visibleNode, start: 0, end: 1 }],
    );
    assert.deepEqual(calls, [
        ['chapterPositionForClone', renderedNode, 0],
        ['sourcePositionForRawOffset', 52],
        ['matchableOffsetForSourcePosition', sourceNode, 2],
        ['rawOffsetForSourcePosition', sourceNode, 0],
        ['rawOffsetForSourcePosition', sourceNode, 11],
        ['collectRawSegments', 0, 11],
    ]);
});

test('VN selection projection fails closed when a required mapping is absent', () => {
    const reader = {
        contentStream: {
            sourcePositionForRawOffset() {
                return null;
            },
            rawOffsetForSourcePosition() {
                return null;
            },
            matchableOffsetForSourcePosition() {
                return null;
            },
        },
        rangeMap: {
            chapterPositionForClone() {
                return null;
            },
            collectRawSegments() {
                throw new Error('must not collect without semantic offsets');
            },
        },
    };
    const projection = loadProjectionFactory().create(reader);

    assert.equal(projection.toSemanticHit({ node: {}, offset: 0 }), null);
    assert.equal(projection.normalizedOffsetForHit({ node: {}, offset: 0 }), null);
    assert.equal(
        projection.visibleRangesForSemanticRanges([{ node: {}, start: 0, end: 1 }]).length,
        0,
    );
});
