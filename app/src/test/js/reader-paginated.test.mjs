import assert from 'node:assert/strict';
import fs from 'node:fs';
import test from 'node:test';
import vm from 'node:vm';

const readerPaginatedUrl = new URL('../../main/assets/hoshi-web/reader/reader-paginated.js', import.meta.url);
const readerContinuousUrl = new URL('../../main/assets/hoshi-web/reader/reader-continuous.js', import.meta.url);

function readerSource(url) {
    return fs.readFileSync(url, 'utf8')
        .replace('__HOSHI_HIGHLIGHTS_SCRIPT__', '');
}

class TestNode {
    constructor(nodeType) {
        this.nodeType = nodeType;
        this.parentNode = null;
    }

    get parentElement() {
        return this.parentNode?.nodeType === 1 ? this.parentNode : null;
    }

    get previousSibling() {
        if (!this.parentNode) return null;
        const index = this.parentNode.childNodes.indexOf(this);
        return index > 0 ? this.parentNode.childNodes[index - 1] : null;
    }

    get nextSibling() {
        if (!this.parentNode) return null;
        const index = this.parentNode.childNodes.indexOf(this);
        return index >= 0 && index + 1 < this.parentNode.childNodes.length
            ? this.parentNode.childNodes[index + 1]
            : null;
    }

    get firstChild() {
        return this.childNodes?.[0] ?? null;
    }

    replaceWith(node) {
        if (!this.parentNode) return;
        const parent = this.parentNode;
        const index = parent.childNodes.indexOf(this);
        if (index < 0) return;
        parent.childNodes.splice(index, 1);
        this.parentNode = null;
        const replacements = node.nodeType === 11 ? [...node.childNodes] : [node];
        replacements.forEach((child, offset) => {
            child.parentNode = parent;
            parent.childNodes.splice(index + offset, 0, child);
        });
        if (node.nodeType === 11) node.childNodes = [];
    }
}

class TestText extends TestNode {
    constructor(value) {
        super(3);
        this.nodeValue = value;
    }

    get textContent() {
        return this.nodeValue;
    }

    set textContent(value) {
        this.nodeValue = value;
    }
}

class TestElement extends TestNode {
    constructor(tagName) {
        super(1);
        this.tagName = tagName.toUpperCase();
        this.childNodes = [];
    }

    appendChild(child) {
        child.parentNode?.removeChild(child);
        child.parentNode = this;
        this.childNodes.push(child);
        return child;
    }

    insertBefore(child, before) {
        child.parentNode?.removeChild(child);
        child.parentNode = this;
        const index = before ? this.childNodes.indexOf(before) : -1;
        if (index < 0) {
            this.childNodes.push(child);
        } else {
            this.childNodes.splice(index, 0, child);
        }
        return child;
    }

    removeChild(child) {
        const index = this.childNodes.indexOf(child);
        if (index >= 0) {
            this.childNodes.splice(index, 1);
            child.parentNode = null;
        }
        return child;
    }

    normalize() {
        const normalized = [];
        this.childNodes.forEach((child) => {
            if (child.nodeType === 1) child.normalize();
            const previous = normalized[normalized.length - 1];
            if (child.nodeType === 3 && previous?.nodeType === 3) {
                previous.nodeValue += child.nodeValue;
                child.parentNode = null;
            } else {
                normalized.push(child);
            }
        });
        this.childNodes = normalized;
    }

    closest(selector) {
        const selectors = selector.split(',').map((item) => item.trim().toUpperCase());
        let node = this;
        while (node) {
            if (node.nodeType === 1 && selectors.includes(node.tagName)) return node;
            node = node.parentNode;
        }
        return null;
    }

    get textContent() {
        return this.childNodes.map((child) => child.textContent).join('');
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

function queryRuby(root) {
    const result = [];
    const visit = (node) => {
        if (node.nodeType === 1 && node.tagName === 'RUBY') result.push(node);
        if (node.childNodes) node.childNodes.forEach(visit);
    };
    visit(root);
    return result;
}

function loadReader(body, sourceUrl = readerPaginatedUrl) {
    const document = {
        body,
        readyState: 'loading',
        createDocumentFragment() {
            return new TestFragment();
        },
        createTextNode(text) {
            return new TestText(text);
        },
        querySelectorAll(selector) {
            return selector === 'ruby' ? queryRuby(body) : [];
        },
        addEventListener() {},
    };
    const window = {
        addEventListener() {},
        getComputedStyle() {
            return { writingMode: 'vertical-rl', getPropertyValue: () => '' };
        },
    };
    vm.runInNewContext(readerSource(sourceUrl), {
        CSS: { highlights: { delete() {}, set() {} } },
        document,
        Highlight: class {},
        Node: { ELEMENT_NODE: 1, TEXT_NODE: 3 },
        NodeFilter: { SHOW_TEXT: 4, FILTER_ACCEPT: 1, FILTER_REJECT: 2 },
        window,
    });
    return window.hoshiReader;
}

function rubyParagraph() {
    const paragraph = new TestElement('p');
    const ruby = new TestElement('ruby');
    ruby.appendChild(new TestText('歩'));
    const rt = new TestElement('rt');
    rt.appendChild(new TestText('あゆむ'));
    ruby.appendChild(rt);

    paragraph.appendChild(new TestText('進藤'));
    paragraph.appendChild(ruby);
    paragraph.appendChild(new TestText('。'));
    paragraph.appendChild(new TestText('そ'));
    paragraph.appendChild(new TestText('れ'));
    return { paragraph, ruby };
}

function textRunAfter(node) {
    const values = [];
    let next = node.nextSibling;
    while (next?.nodeType === 3) {
        values.push(next.nodeValue);
        next = next.nextSibling;
    }
    return values;
}

test('paginated reader re-stabilizes ruby-adjacent text after unwrap normalizes siblings', () => {
    const { paragraph, ruby } = rubyParagraph();
    const wrapper = new TestElement('span');
    wrapper.appendChild(new TestText('追加'));
    paragraph.appendChild(wrapper);
    const reader = loadReader(paragraph);

    reader.unwrap([wrapper]);

    assert.deepEqual(textRunAfter(ruby).slice(0, 4), ['。', 'そ', 'れ', '追']);
});

test('continuous reader stabilizes vertical ruby-adjacent text like paginated reader', () => {
    const { paragraph, ruby } = rubyParagraph();
    paragraph.normalize();
    const reader = loadReader(paragraph, readerContinuousUrl);

    assert.equal(typeof reader.stabilizeRubyAdjacentTextNodes, 'function');
    reader.stabilizeRubyAdjacentTextNodes();

    assert.deepEqual(textRunAfter(ruby).slice(0, 3), ['。', 'そ', 'れ']);
});

test('continuous reader re-stabilizes ruby-adjacent text after unwrap normalizes siblings', () => {
    const { paragraph, ruby } = rubyParagraph();
    const wrapper = new TestElement('span');
    wrapper.appendChild(new TestText('追加'));
    paragraph.appendChild(wrapper);
    const reader = loadReader(paragraph, readerContinuousUrl);

    reader.unwrap([wrapper]);

    assert.deepEqual(textRunAfter(ruby).slice(0, 4), ['。', 'そ', 'れ', '追']);
});
