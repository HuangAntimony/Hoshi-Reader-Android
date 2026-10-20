// Text-only DOM range operations for the reader fixtures. Real WebView tests
// cover native ranges at ruby annotations, element boundaries and empty nodes.
function textOffset(root, node, offset) {
    let result = node.nodeType === 3 ? offset
        : node.childNodes.slice(0, offset).reduce((sum, child) => sum + child.textContent.length, 0);
    while (node !== root) {
        const parent = node.parentNode;
        if (!parent) throw new Error('Range point is outside its root');
        for (const sibling of parent.childNodes) {
            if (sibling === node) break;
            result += sibling.textContent.length;
        }
        node = parent;
    }
    return result;
}

export class TextRangeFixture {
    get commonAncestorContainer() {
        const ancestors = new Set();
        for (let node = this.startContainer; node; node = node.parentNode) ancestors.add(node);
        for (let node = this.endContainer; node; node = node.parentNode) {
            if (ancestors.has(node)) return node;
        }
        throw new Error('Range endpoints have no common ancestor');
    }

    comparePoint(node, offset) {
        const root = this.commonAncestorContainer;
        const point = textOffset(root, node, offset);
        if (point < textOffset(root, this.startContainer, this.startOffset)) return -1;
        if (point > textOffset(root, this.endContainer, this.endOffset)) return 1;
        return 0;
    }

    intersectsNode(node) {
        const root = this.commonAncestorContainer;
        return textOffset(root, node, 0) < textOffset(root, this.endContainer, this.endOffset) &&
            textOffset(root, node, node.textContent.length) > textOffset(root, this.startContainer, this.startOffset);
    }
}
