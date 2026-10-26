import assert from 'node:assert/strict';
import fs from 'node:fs';
import test from 'node:test';
import vm from 'node:vm';

const layoutSemanticsUrl = new URL(
    '../../main/assets/hoshi-web/reader/reader-layout-semantics.js',
    import.meta.url,
);

class TestStyle {
    constructor() {
        this.values = new Map();
        this.priorities = new Map();
    }

    setProperty(name, value, priority = '') {
        this.values.set(name, String(value));
        this.priorities.set(name, priority);
    }

    getPropertyValue(name) {
        return this.values.get(name) ?? '';
    }

    getPropertyPriority(name) {
        return this.priorities.get(name) ?? '';
    }
}

class TestElement {
    constructor(tagName, rect = {}) {
        this.tagName = tagName.toUpperCase();
        this.childNodes = [];
        this.textContent = '';
        this.parentNode = null;
        this.style = new TestStyle();
        this.attributes = new Map();
        this.rect = {
            width: rect.width ?? 0,
            height: rect.height ?? 0,
        };
        this.computed = {
            display: 'block',
            paddingLeft: '0px',
            paddingRight: '0px',
            paddingTop: '0px',
            paddingBottom: '0px',
        };
    }

    appendChild(child) {
        child.parentNode = this;
        this.childNodes.push(child);
        return child;
    }

    get children() {
        return this.childNodes.filter((child) => child.tagName);
    }

    getAttribute(name) {
        return this.attributes.get(name) ?? null;
    }

    setAttribute(name, value) {
        this.attributes.set(name, String(value));
    }

    hasAttribute(name) {
        return this.attributes.has(name);
    }

    querySelector(selector) {
        return this.querySelectorAll(selector)[0] ?? null;
    }

    querySelectorAll(selector) {
        const result = [];
        const visit = (node) => {
            node.childNodes.forEach((child) => {
                if (selector === 'p' && child.tagName === 'P') result.push(child);
                if (selector === '*') result.push(child);
                if (selector === 'img, svg' && ['IMG', 'SVG'].includes(child.tagName)) result.push(child);
                if (selector === 'span:empty' && child.tagName === 'SPAN' && child.childNodes.length === 0) {
                    result.push(child);
                }
                visit(child);
            });
        };
        visit(this);
        return result;
    }

    getBoundingClientRect() {
        return this.rect;
    }
}

function loadLayoutSemantics(elements, body) {
    const document = {
        body,
        querySelectorAll(selector) {
            if (selector === 'p') return elements.filter((element) => element.tagName === 'P');
            if (selector === 'div, span') {
                return elements.filter((element) => element.tagName === 'DIV' || element.tagName === 'SPAN');
            }
            if (selector === 'span:empty') {
                return elements.filter((element) => element.tagName === 'SPAN' && element.childNodes.length === 0);
            }
            return [];
        },
    };
    const window = {
        document,
        getComputedStyle(element, pseudoElement) {
            if (pseudoElement === '::before') return element.beforeComputed ?? { content: 'none' };
            if (pseudoElement === '::after') return element.afterComputed ?? { content: 'none' };
            return {
                ...element.computed,
                display: element.style.getPropertyValue('display') || element.computed.display,
            };
        },
    };
    const source = fs.existsSync(layoutSemanticsUrl)
        ? fs.readFileSync(layoutSemanticsUrl, 'utf8')
        : '';
    vm.runInNewContext(source, { document, window });
    return { document, layout: window.hoshiReaderLayoutSemantics };
}

test('layout sanitizer converts oversized paragraph wrappers and removes their empty inline struts', () => {
    const body = new TestElement('body');
    body.clientWidth = 384;
    body.clientHeight = 832;
    body.computed.paddingBottom = '31px';

    const parent = new TestElement('section');
    const publisherWrapper = new TestElement('div', { width: 870, height: 801 });
    publisherWrapper.computed.display = 'inline-block';
    publisherWrapper.appendChild(new TestElement('p'));
    const emptyStrut = new TestElement('span', { width: 20, height: 801 });
    emptyStrut.computed.display = 'inline-block';
    const fittingWrapper = new TestElement('div', { width: 200, height: 400 });
    fittingWrapper.computed.display = 'inline-block';
    fittingWrapper.appendChild(new TestElement('p'));
    parent.appendChild(publisherWrapper);
    parent.appendChild(emptyStrut);
    parent.appendChild(fittingWrapper);

    const { document, layout } = loadLayoutSemantics(
        [publisherWrapper, emptyStrut, fittingWrapper],
        body,
    );
    assert.equal(typeof layout?.sanitizeInlineBlocks, 'function');

    layout.sanitizeInlineBlocks(document, true);

    assert.equal(publisherWrapper.style.getPropertyValue('display'), 'block');
    assert.equal(publisherWrapper.style.getPropertyPriority('display'), 'important');
    assert.equal(emptyStrut.style.getPropertyValue('display'), 'none');
    assert.equal(emptyStrut.style.getPropertyPriority('display'), 'important');
    assert.equal(fittingWrapper.style.getPropertyValue('display'), '');
});

test('layout sanitizer clamps empty inline struts to the horizontal block extent', () => {
    const body = new TestElement('body');
    body.clientWidth = 600;
    body.clientHeight = 400;
    body.computed.paddingLeft = '20px';
    body.computed.paddingRight = '20px';
    body.computed.paddingTop = '10px';
    body.computed.paddingBottom = '10px';

    const oversizedStrut = new TestElement('span', { width: 40, height: 500 });
    oversizedStrut.computed.display = 'inline-block';
    const fittingStrut = new TestElement('span', { width: 40, height: 300 });
    fittingStrut.computed.display = 'inline-block';

    const { document, layout } = loadLayoutSemantics([oversizedStrut, fittingStrut], body);
    assert.equal(typeof layout?.sanitizeInlineBlocks, 'function');

    layout.sanitizeInlineBlocks(document, false);

    assert.equal(oversizedStrut.style.getPropertyValue('height'), '380px');
    assert.equal(oversizedStrut.style.getPropertyPriority('height'), 'important');
    assert.equal(fittingStrut.style.getPropertyValue('height'), '');
});

test('layout sanitizer preserves semantic anchors, publisher art, and nested empty spans', () => {
    const body = new TestElement('body');
    body.clientWidth = 384;
    body.clientHeight = 832;

    const parent = new TestElement('section');
    const publisherWrapper = new TestElement('div', { width: 870, height: 800 });
    publisherWrapper.computed.display = 'inline-block';
    publisherWrapper.appendChild(new TestElement('p'));

    const anchor = new TestElement('span', { width: 1, height: 1 });
    anchor.computed.display = 'inline-block';
    anchor.setAttribute('id', 'page-12');
    const art = new TestElement('span', { width: 24, height: 24 });
    art.computed.display = 'inline-block';
    art.computed.backgroundImage = 'url(ornament.svg)';
    const generatedArt = new TestElement('span', { width: 24, height: 24 });
    generatedArt.computed.display = 'inline-block';
    generatedArt.beforeComputed = { content: '"◆"' };
    const nested = new TestElement('div');
    const nestedEmpty = new TestElement('span', { width: 8, height: 8 });
    nestedEmpty.computed.display = 'inline-block';
    nested.appendChild(nestedEmpty);

    parent.appendChild(publisherWrapper);
    parent.appendChild(anchor);
    parent.appendChild(art);
    parent.appendChild(generatedArt);
    parent.appendChild(nested);

    const { document, layout } = loadLayoutSemantics(
        [publisherWrapper, anchor, art, generatedArt, nested, nestedEmpty],
        body,
    );
    assert.equal(typeof layout?.sanitizeInlineBlocks, 'function');

    layout.sanitizeInlineBlocks(document, true);

    assert.equal(publisherWrapper.style.getPropertyValue('display'), 'block');
    assert.equal(anchor.style.getPropertyValue('display'), '');
    assert.equal(art.style.getPropertyValue('display'), '');
    assert.equal(generatedArt.style.getPropertyValue('display'), '');
    assert.equal(nestedEmpty.style.getPropertyValue('display'), '');
});

function imageParagraph({ vertical = true, size = 371, margin = 27, text = '', gaiji = false, generated = false, classified = true } = {}) {
    const body = new TestElement('body');
    body.clientWidth = 384;
    body.clientHeight = 789;
    body.computed.paddingLeft = '6px';
    body.computed.paddingRight = '6px';
    const paragraph = new TestElement('p');
    paragraph.textContent = text;
    paragraph.setAttribute('id', 'picture-anchor');
    paragraph.computed.marginLeft = paragraph.computed.marginRight = vertical ? `${margin}px` : '0px';
    paragraph.computed.marginTop = paragraph.computed.marginBottom = vertical ? '0px' : `${margin}px`;
    const link = paragraph.appendChild(new TestElement('a'));
    link.setAttribute('href', 'image.jpg');
    if (generated) link.beforeComputed = { content: '"Caption"' };
    const image = link.appendChild(new TestElement('img', vertical ? { width: size, height: 186 } : { width: 300, height: size }));
    image.setAttribute('class', gaiji ? 'gaiji' : classified ? 'block-img' : '');
    image.naturalWidth = 2048;
    image.naturalHeight = 1028;
    const { document, layout } = loadLayoutSemantics([paragraph, link, image], body);
    layout.sanitizeInlineBlocks(document, vertical);
    return { paragraph, image, link };
}

test('overflowing image-only paragraphs lose only their block-axis margins without replacing nodes', () => {
    for (const vertical of [true, false]) {
        const { paragraph, image, link } = imageParagraph({ vertical, size: vertical ? 371 : 780 });
        for (const edge of vertical ? ['left', 'right'] : ['top', 'bottom']) {
            assert.equal(paragraph.style.getPropertyValue(`margin-${edge}`), '0px');
            assert.equal(paragraph.style.getPropertyPriority(`margin-${edge}`), 'important');
        }
        assert.equal(paragraph.getAttribute('id'), 'picture-anchor');
        assert.equal(link.childNodes[0], image);
        assert.equal(link.getAttribute('href'), 'image.jpg');
    }
});

test('image paragraphs preserve fitting margins, mixed text, gaiji, and generated captions', () => {
    for (const options of [{ size: 250 }, { text: 'Caption' }, { gaiji: true }, { generated: true }]) {
        const { paragraph } = imageParagraph(options);
        assert.equal(paragraph.style.getPropertyValue('margin-left'), '');
        assert.equal(paragraph.style.getPropertyValue('margin-right'), '');
    }
});

test('VN source images are sanitized before the displayed clone receives its media class', () => {
    const { paragraph } = imageParagraph({ classified: false });
    assert.equal(paragraph.style.getPropertyValue('margin-left'), '0px');
});

test('image overflow without positive block margins preserves the publisher margins', () => {
    for (const margin of [0, -10]) {
        const { paragraph } = imageParagraph({ size: 450, margin });
        assert.equal(paragraph.style.getPropertyValue('margin-left'), '');
        assert.equal(paragraph.style.getPropertyValue('margin-right'), '');
    }
});

test('layout sanitizer uses the displayed VN screen content bounds when given an element scope', () => {
    const body = new TestElement('body');
    body.clientWidth = body.clientHeight = 1000;
    const screen = new TestElement('div');
    screen.clientWidth = 384;
    screen.clientHeight = 789;
    screen.computed.paddingLeft = screen.computed.paddingRight = '6px';
    const paragraph = screen.appendChild(new TestElement('p'));
    paragraph.computed.marginLeft = paragraph.computed.marginRight = '27px';
    const image = paragraph.appendChild(new TestElement('img', { width: 371, height: 186 }));
    image.setAttribute('class', 'block-img');
    const { layout } = loadLayoutSemantics([screen, paragraph, image], body);
    layout.sanitizeInlineBlocks(screen, true);
    assert.equal(paragraph.style.getPropertyValue('margin-left'), '0px');
    assert.equal(paragraph.style.getPropertyValue('margin-right'), '0px');
});

test('a compact image-only inline wrapper uses the full line for centering and drops its empty strut', () => {
    const body = new TestElement('body');
    body.clientWidth = 384;
    body.clientHeight = 812;
    const parent = new TestElement('section');
    const strut = parent.appendChild(new TestElement('span'));
    strut.computed.display = 'inline-block';
    const wrapper = parent.appendChild(new TestElement('div', { width: 371, height: 523 }));
    wrapper.computed.display = 'inline-block';
    const paragraph = wrapper.appendChild(new TestElement('p'));
    paragraph.setAttribute('id', 'cover');
    const imageBox = paragraph.appendChild(new TestElement('span', { width: 371, height: 481 }));
    imageBox.computed.display = 'inline-block';
    const image = imageBox.appendChild(new TestElement('img', { width: 371, height: 523 }));
    image.setAttribute('class', 'block-img');
    const { document, layout } = loadLayoutSemantics([strut, wrapper, paragraph, imageBox, image], body);
    layout.sanitizeInlineBlocks(document, true);
    assert.equal(wrapper.style.getPropertyValue('display'), 'block');
    assert.equal(strut.style.getPropertyValue('display'), 'none');
    assert.equal(paragraph.getAttribute('id'), 'cover');
    assert.equal(imageBox.style.getPropertyValue('height'), 'auto');
    assert.equal(imageBox.childNodes[0], image);
});
