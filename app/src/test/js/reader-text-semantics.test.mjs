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

test('Korean ranges match native normalization without changing raw counts or other scripts', () => {
    const semantics = loadTextSemantics();
    // Same input and expectation as ReaderTextFilterTest.
    const text = '가힣ㄱㆎ 한글 日本語 Aｚ9、! 𠮟🙂\uABFF\uD7A4\u3130\u318F\u1100\u1161';
    assert.equal(semantics.normalizeText(text), '가힣ㄱㆎ한글日本語Aｚ9𠮟');
    assert.equal(semantics.countChars(text), 13);
    assert.equal(semantics.countRawChars(text), 26);
    for (const char of '가힣ㄱㆎ') assert.equal(semantics.isMatchableChar(char), true);
    for (const char of '\uABFF\uD7A4\u3130\u318F\u1100\u1161') {
        assert.equal(semantics.isMatchableChar(char), false);
    }
});

test('reader text semantics normalizes matchable text while preserving raw counts', () => {
    const semantics = loadTextSemantics();

    assert.equal(semantics.normalizeText('一、二。A!'), '一二A');
    assert.equal(semantics.countChars('一、二。A!'), 3);
    assert.equal(semantics.countRawChars('一、二。A!'), 6);
    assert.equal(semantics.isMatchableChar('一'), true);
    assert.equal(semantics.isMatchableChar('、'), false);
});

test('reader text semantics classifies Japanese characters used for ruby-adjacent wrapping', () => {
    const semantics = loadTextSemantics();

    assert.equal(semantics.isJapaneseBreakCharacter('貴'), true);
    assert.equal(semantics.isJapaneseBreakCharacter('、'), true);
    assert.equal(semantics.isJapaneseBreakCharacter('A'), false);
    assert.equal(semantics.isJapaneseBreakCharacter(' '), false);
});

test('Sasayaki punctuation index has stable directional ownership without changing matching', () => {
    const semantics = loadTextSemantics();
    const cases = [
        ['……一――二。', ['……一――', '二。']],
        ['一。「……二」', ['一。', '「……二」']],
        ['一」……二', ['一」……', '二']],
        ['一……「二', ['一……', '「二']],
        ['一「」二', ['一', '二']],
        ['一。\n……二', ['一。', '……二']],
        ['一。　……二', ['一。', '……二']],
        ['一🙂……二', ['一', '二']],
        ['「一', ['「一']],
        ['一」', ['一」']],
        ['"一"', ['一']],
        ['\'一\'', ['一']],
        ['-一.', ['一']],
        ['ー。', ['ー。']],
    ];
    for (const [text, expected] of cases) {
        const chars = Array.from(text);
        const index = semantics.createSasayakiTextIndex(chars.map((text) => ({ text })));
        assert.deepEqual(expected.map((_, start) => {
            const range = index.range(start, 1);
            return chars.slice(range.start, range.end).join('');
        }), expected, text);
        assert.equal(semantics.countChars(text), expected.length, text);
        for (const [start, length] of [[0, 0], [-1, 1], [0, 100], [1.5, 1], [0, NaN]]) {
            assert.equal(index.range(start, length), null);
        }
    }
    assert.equal(semantics.createSasayakiTextIndex([{ text: '……。' }]).range(0, 1), null);
});

test('Sasayaki punctuation whitelist handles every specified character', () => {
    const semantics = loadTextSemantics();
    for (const char of '「『（〔［｛〈《【〖〘〚“‘｢([{') {
        const index = semantics.createSasayakiTextIndex([{ text: `一${char}二` }]);
        assert.equal(index.range(0, 1).end, 1, char);
        assert.equal(index.range(1, 1).start, 1, char);
    }
    for (const char of '」』）〕］｝〉》】〗〙〛”’｣)]}。、，．！？!?‼⁇⁈⁉､｡・･：；:;…‥—―–─〜～') {
        const index = semantics.createSasayakiTextIndex([{ text: `一${char}二` }]);
        assert.equal(index.range(0, 1).end, 2, char);
        assert.equal(index.range(1, 1).start, 2, char);
    }
});

test('search ranges include interior punctuation only and count supplementary characters once', () => {
    const semantics = loadTextSemantics();
    const entries = [{ text: '前「𠮟 ' }, { text: '、猫。」後' }];
    const range = semantics.searchRawRange(entries, 1, 2);
    assert.equal(range.start, 2);
    assert.equal(range.end, 6);
    for (const [start, length] of [[-1, 1], [1, 0], [1, -1], [0, 5], [1.5, 1]]) {
        assert.equal(semantics.searchRawRange(entries, start, length), null);
    }
});
