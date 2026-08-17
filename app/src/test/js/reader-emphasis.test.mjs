import assert from 'node:assert/strict';
import fs from 'node:fs';
import test from 'node:test';
import vm from 'node:vm';

const readerEmphasisUrl = new URL('../../main/assets/hoshi-web/reader/reader-emphasis.js', import.meta.url);

class TestNode {
    constructor(nodeType) {
        this.nodeType = nodeType;
        this.parentNode = null;
    }

    get parentElement() {
        return this.parentNode?.nodeType === 1 ? this.parentNode : null;
    }
}

class TestText extends TestNode {
    constructor(value) {
        super(3);
        this.textContent = value;
    }
}

class TestFragment extends TestNode {
    constructor() {
        super(11);
        this.childNodes = [];
    }

    appendChild(child) {
        child.parentNode = this;
        this.childNodes.push(child);
        return child;
    }
}

class TestElement extends TestNode {
    constructor(tagName) {
        super(1);
        this.tagName = tagName.toUpperCase();
        this.childNodes = [];
        this.className = '';
        this.style = {};
    }

    get textContent() {
        return this.childNodes.map((child) => child.textContent).join('');
    }

    set textContent(value) {
        this.childNodes = [];
        this.appendChild(new TestText(value));
    }

    appendChild(child) {
        child.parentNode = this;
        this.childNodes.push(child);
        return child;
    }

    replaceChild(replacement, node) {
        const index = this.childNodes.indexOf(node);
        const replacements = replacement.nodeType === 11 ? [...replacement.childNodes] : [replacement];
        replacements.forEach((child) => {
            child.parentNode = this;
        });
        this.childNodes.splice(index, 1, ...replacements);
        node.parentNode = null;
        return node;
    }

    closest(selector) {
        const tags = selector.split(',').map((item) => item.trim().toUpperCase());
        let node = this;
        while (node) {
            if (node.nodeType === 1 && tags.includes(node.tagName)) return node;
            node = node.parentNode;
        }
        return null;
    }

    querySelectorAll(selector) {
        assert.equal(selector, '*');
        const result = [];
        const visit = (node) => {
            if (node.nodeType !== 1) return;
            result.push(node);
            node.childNodes.forEach(visit);
        };
        this.childNodes.forEach(visit);
        return result;
    }
}

function element(tagName, children = [], emphasisStyle = null) {
    const node = new TestElement(tagName);
    if (emphasisStyle) node.emphasisStyle = emphasisStyle;
    children.forEach((child) => {
        node.appendChild(typeof child === 'string' ? new TestText(child) : child);
    });
    return node;
}

function loadEmphasis(body) {
    const document = {
        body,
        createElement: (tagName) => new TestElement(tagName),
        createTextNode: (value) => new TestText(value),
        createDocumentFragment: () => new TestFragment(),
    };
    const window = {
        document,
        getComputedStyle: (node) => ({
            webkitTextEmphasisStyle: node?.emphasisStyle ?? '',
            textEmphasisStyle: node?.emphasisStyle ?? '',
        }),
    };
    body.ownerDocument = document;
    vm.runInNewContext(fs.readFileSync(readerEmphasisUrl, 'utf8'), {
        window,
        document,
        Node: { ELEMENT_NODE: 1, TEXT_NODE: 3 },
    });
    return window.hoshiReaderEmphasis;
}

function rubyMarks(node) {
    const marks = [];
    const visit = (child) => {
        if (child.nodeType !== 1) return;
        if (child.tagName === 'RUBY' && child.className === 'hoshi-emphasis') {
            marks.push([child.childNodes[0].textContent, child.childNodes[1].textContent]);
            return;
        }
        child.childNodes.forEach(visit);
    };
    node.childNodes.forEach(visit);
    return marks;
}

test('emphasized text becomes per-character ruby marks and drops the original declaration', () => {
    const emphasized = element('em', ['本気'], 'filled sesame');
    const body = element('body', [element('p', ['これは', emphasized, 'だ'])]);
    const emphasis = loadEmphasis(body);

    emphasis.normalizeTextEmphasis(body, { vertical: true });

    assert.deepEqual(rubyMarks(body), [['本', '﹅'], ['気', '﹅']]);
    assert.equal(emphasized.style.webkitTextEmphasis, 'none');
    assert.equal(emphasized.style.textEmphasis, 'none');
    assert.equal(body.textContent, 'これは本﹅気﹅だ');
});

test('shape, fill, custom string, and writing-mode defaults select the mark', () => {
    const emphasis = loadEmphasis(element('body'));

    assert.equal(emphasis.emphasisMark('filled sesame', true), '﹅');
    assert.equal(emphasis.emphasisMark('open sesame', true), '﹆');
    assert.equal(emphasis.emphasisMark('filled double-circle', true), '◉');
    assert.equal(emphasis.emphasisMark('open dot', false), '◦');
    assert.equal(emphasis.emphasisMark('filled triangle', false), '▲');
    assert.equal(emphasis.emphasisMark('"※"', true), '※');
    assert.equal(emphasis.emphasisMark('filled', true), '﹅');
    assert.equal(emphasis.emphasisMark('filled', false), '●');
    assert.equal(emphasis.emphasisMark('none', true), null);
    assert.equal(emphasis.emphasisMark('', true), null);
});

test('existing ruby, whitespace, and inherited emphasis are left alone', () => {
    const ruby = element('ruby', [element('span', ['漢']), element('rt', ['かん'])]);
    const inner = element('span', ['気'], 'filled sesame');
    const outer = element('em', ['本 ', inner, ruby], 'filled sesame');
    const body = element('body', [outer]);
    const emphasis = loadEmphasis(body);

    emphasis.normalizeTextEmphasis(body, { vertical: true });

    assert.deepEqual(rubyMarks(body), [['本', '﹅'], ['気', '﹅']]);
    assert.equal(ruby.childNodes.length, 2);
    assert.equal(body.textContent, '本﹅ 気﹅漢かん');
});

test('script and style text inside an emphasized subtree is not marked', () => {
    const body = element('body', [
        element('div', ['字', element('style', ['p { color: red; }'])], 'filled circle'),
    ]);
    const emphasis = loadEmphasis(body);

    emphasis.normalizeTextEmphasis(body, { vertical: false });

    assert.deepEqual(rubyMarks(body), [['字', '●']]);
    assert.equal(body.textContent, '字●p { color: red; }');
});
