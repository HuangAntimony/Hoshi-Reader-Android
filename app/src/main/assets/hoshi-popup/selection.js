//
//  selection.js
//  Hoshi Reader
//
//  Copyright © 2026 Manhhao.
//  SPDX-License-Identifier: GPL-3.0-or-later
//

// https://github.com/yomidevs/yomitan/blob/ddbe4a2c0bf778583b38962d4b0b85442dfa8f6a/ext/js/language/CJK-util.js#L19
const CJK_UNIFIED_IDEOGRAPHS_RANGE = [0x4e00, 0x9fff];
const CJK_UNIFIED_IDEOGRAPHS_EXTENSION_A_RANGE = [0x3400, 0x4dbf];
const CJK_UNIFIED_IDEOGRAPHS_EXTENSION_B_RANGE = [0x20000, 0x2a6df];
const CJK_UNIFIED_IDEOGRAPHS_EXTENSION_C_RANGE = [0x2a700, 0x2b73f];
const CJK_UNIFIED_IDEOGRAPHS_EXTENSION_D_RANGE = [0x2b740, 0x2b81f];
const CJK_UNIFIED_IDEOGRAPHS_EXTENSION_E_RANGE = [0x2b820, 0x2ceaf];
const CJK_UNIFIED_IDEOGRAPHS_EXTENSION_F_RANGE = [0x2ceb0, 0x2ebef];
const CJK_UNIFIED_IDEOGRAPHS_EXTENSION_G_RANGE = [0x30000, 0x3134f];
const CJK_UNIFIED_IDEOGRAPHS_EXTENSION_H_RANGE = [0x31350, 0x323af];
const CJK_UNIFIED_IDEOGRAPHS_EXTENSION_I_RANGE = [0x2ebf0, 0x2ee5f];
const CJK_COMPATIBILITY_IDEOGRAPHS_RANGE = [0xf900, 0xfaff];
const CJK_COMPATIBILITY_IDEOGRAPHS_SUPPLEMENT_RANGE = [0x2f800, 0x2fa1f];
const CJK_IDEOGRAPH_RANGES = [
    CJK_UNIFIED_IDEOGRAPHS_RANGE,
    CJK_UNIFIED_IDEOGRAPHS_EXTENSION_A_RANGE,
    CJK_UNIFIED_IDEOGRAPHS_EXTENSION_B_RANGE,
    CJK_UNIFIED_IDEOGRAPHS_EXTENSION_C_RANGE,
    CJK_UNIFIED_IDEOGRAPHS_EXTENSION_D_RANGE,
    CJK_UNIFIED_IDEOGRAPHS_EXTENSION_E_RANGE,
    CJK_UNIFIED_IDEOGRAPHS_EXTENSION_F_RANGE,
    CJK_UNIFIED_IDEOGRAPHS_EXTENSION_G_RANGE,
    CJK_UNIFIED_IDEOGRAPHS_EXTENSION_H_RANGE,
    CJK_UNIFIED_IDEOGRAPHS_EXTENSION_I_RANGE,
    CJK_COMPATIBILITY_IDEOGRAPHS_RANGE,
    CJK_COMPATIBILITY_IDEOGRAPHS_SUPPLEMENT_RANGE,
];

// https://github.com/yomidevs/yomitan/blob/ddbe4a2c0bf778583b38962d4b0b85442dfa8f6a/ext/js/language/CJK-util.js#L60
const FULLWIDTH_CHARACTER_RANGES = [
    [0xff10, 0xff19],
    [0xff21, 0xff3a],
    [0xff41, 0xff5a],
    [0xff01, 0xff0f],
    [0xff1a, 0xff1f],
    [0xff3b, 0xff3f],
    [0xff5b, 0xff60],
    [0xffe0, 0xffee],
];

// https://github.com/yomidevs/yomitan/blob/ddbe4a2c0bf778583b38962d4b0b85442dfa8f6a/ext/js/language/ja/japanese.js#L44
const JAPANESE_RANGES = [
    [0x3040, 0x309f],
    [0x30a0, 0x30ff],
    ...CJK_IDEOGRAPH_RANGES,
    [0xff66, 0xff9f],
    [0x30fb, 0x30fc],
    [0xff61, 0xff65],
    [0x3000, 0x303f],
    ...FULLWIDTH_CHARACTER_RANGES,
];

window.hoshiSelection = {
    selection: null,
    scanDelimiters: '。、！？…‥「」『』（）()【】〈〉《》〔〕｛｝{}［］[]・：；:;，,.─\n\r',
    sentenceDelimiters: '。！？.!?\n\r',
    trailingSentenceChars: '。、！？…‥」』）)】〉》〕｝}］]',
    brackets: {'「':'」', '『': '』', '（':'）', '(':')', '【':'】', '〈':'〉', '《':'》', '〔':'〕', '｛':'｝', '{':'}', '［':'］', '[':']'},

    isVertical() {
        return window.getComputedStyle(document.body).writingMode === "vertical-rl";
    },

    // https://github.com/yomidevs/yomitan/blob/ddbe4a2c0bf778583b38962d4b0b85442dfa8f6a/ext/js/language/ja/japanese.js#L307
    isCodePointJapanese(codePoint) {
        return JAPANESE_RANGES.some(([start, end]) => codePoint >= start && codePoint <= end);
    },
    
    isScanBoundary(char) {
        return /^[\s\u3000]$/.test(char) ||
            this.scanDelimiters.includes(char) ||
            (window.scanNonJapaneseText === false && !this.isCodePointJapanese(char.codePointAt(0)));
    },
    
    isFurigana(node) {
        const el = node.nodeType === Node.TEXT_NODE ? node.parentElement : node;
        return !!el?.closest('rt, rp');
    },
    
    findParagraph(node) {
        let el = node.nodeType === Node.TEXT_NODE ? node.parentElement : node;
        return el?.closest('p, .glossary-content') || null;
    },
    
    createWalker(rootNode) {
        const root = rootNode || document.body;
        
        return document.createTreeWalker(root, NodeFilter.SHOW_TEXT, {
            acceptNode: (n) => this.isFurigana(n) ? NodeFilter.FILTER_REJECT : NodeFilter.FILTER_ACCEPT
        });
    },
    
    inCharRange(charRange, x, y) {
        const rects = charRange.getClientRects();
        if (rects.length) {
            for (const rect of rects) {
                if (x >= rect.left && x <= rect.right && y >= rect.top && y <= rect.bottom) {
                    return true;
                }
            }
            return false;
        }
        const rect = charRange.getBoundingClientRect();
        return x >= rect.left && x <= rect.right && y >= rect.top && y <= rect.bottom;
    },
    
    getCaretRange(x, y) {
        if (document.caretPositionFromPoint) {
            const pos = document.caretPositionFromPoint(x, y);
            if (!pos) {
                return null;
            }
            
            const range = document.createRange();
            range.setStart(pos.offsetNode, pos.offset);
            range.collapse(true);
            return range;
        } else {
            const element = document.elementFromPoint(x, y);
            if (!element) {
                return null;
            }
            
            const container = element.closest('p, div, span, ruby, a') || document.body;
            const walker = this.createWalker(container);
            
            const range = document.createRange();
            let node;
            while (node = walker.nextNode()) {
                for (let i = 0; i < node.textContent.length; i++) {
                    range.setStart(node, i);
                    range.setEnd(node, i + 1);
                    if (this.inCharRange(range, x, y)) {
                        range.collapse(true);
                        return range;
                    }
                }
            }
            return document.caretRangeFromPoint(x, y);
        }
    },
    
    getCharacterAtPoint(x, y) {
        const range = this.getCaretRange(x, y);
        if (!range) {
            return null;
        }
        
        const node = range.startContainer;
        if (node.nodeType !== Node.TEXT_NODE) {
            return null;
        }
        
        if (this.isFurigana(node)) {
            return null;
        }
        
        const text = node.textContent;
        const caret = range.startOffset;
        
        for (const offset of [caret, caret - 1, caret + 1]) {
            if (offset < 0 || offset >= text.length) {
                continue;
            }
            
            const charRange = document.createRange();
            charRange.setStart(node, offset);
            charRange.setEnd(node, offset + 1);
            if (this.inCharRange(charRange, x, y)) {
                if (this.isScanBoundary(text[offset])) {
                    return null;
                }
                return { node, offset };
            }
        }
        
        return null;
    },
    
    getSentenceContext(startNode, startOffset) {
        const container = this.findParagraph(startNode) || document.body;
        const walker = this.createWalker(container);
        
        walker.currentNode = startNode;
        const partsBefore = [];
        let node = startNode;
        let limit = startOffset;
        
        while (node) {
            const text = node.textContent;
            let foundStart = false;
            for (let i = limit - 1; i >= 0; i--) {
                if (this.sentenceDelimiters.includes(text[i])) {
                    partsBefore.push(text.slice(i + 1, limit));
                    foundStart = true;
                    break;
                }
            }
            
            if (foundStart) {
                break;
            }
            
            partsBefore.push(text.slice(0, limit));
            node = walker.previousNode();
            if (node) limit = node.textContent.length;
        }
        
        walker.currentNode = startNode;
        const partsAfter = [];
        node = startNode;
        let start = startOffset;
        
        while (node) {
            const text = node.textContent;
            let foundEnd = false;
            
            for (let i = start; i < text.length; i++) {
                if (this.sentenceDelimiters.includes(text[i])) {
                    let end = i + 1;
                    
                    while (end < text.length) {
                        if (!this.trailingSentenceChars.includes(text[end])) break;
                        end += 1;
                    }
                    partsAfter.push(text.slice(start, end));
                    foundEnd = true;
                    break;
                }
            }
            
            if (foundEnd) {
                break;
            }
            
            partsAfter.push(text.slice(start));
            
            node = walker.nextNode();
            start = 0;
        }
        
        const beforeText = partsBefore.reverse().join('');
        const rawSentence = beforeText + partsAfter.join('');
        const leadingTrim = rawSentence.length - rawSentence.trimStart().length;
        let selectedOffset = Math.max(0, beforeText.length - leadingTrim);
        let sentence = rawSentence.trim();

        const closeBrackets = new Set(Object.values(this.brackets));
        const openBrackets = new Set(Object.keys(this.brackets));
        let stack = [];
        let unmatchedClose = [];
        
        for (let i = 0; i < sentence.length; i++) {
            const ch = sentence[i];
            if (openBrackets.has(ch)) {
                stack.push(ch);
            } else if (closeBrackets.has(ch)) {
                if (stack.length > 0 && this.brackets[stack[stack.length-1]] === ch) {
                    stack.pop();
                } else {
                    unmatchedClose.push(ch);
                }
            }
        }

        let startSlice = 0;
        while (stack.length > 0 && startSlice < sentence.length - 1) {
            // Stack consists of unmatched open brackets arranged from start to end
            if (stack[0] === sentence[startSlice]) {
                stack.shift();
            } else break;
            startSlice++;
        }

        let endSlice = sentence.length - 1;
        let endIdx = sentence.length - 1;
        while (unmatchedClose.length > 0 && endIdx > startSlice) {
            if (unmatchedClose[unmatchedClose.length - 1] === sentence[endIdx]) {     
                unmatchedClose.pop();
                endSlice = endIdx - 1;
            // sentenceDelimiters used as trailingSentenceDelimiters as it does not have any overlap with brackets
            } else if (!this.sentenceDelimiters.includes(sentence[endIdx])) break;
            endIdx--;
        }
        const sliced = sentence.slice(startSlice, endSlice + 1);
        const slicedLeadingTrim = sliced.length - sliced.trimStart().length;
        selectedOffset = Math.max(0, selectedOffset - startSlice - slicedLeadingTrim);
        return {
            sentence: sliced.trim(),
            sentenceOffset: selectedOffset,
        };
    },

    getSentence(startNode, startOffset) {
        return this.getSentenceContext(startNode, startOffset).sentence;
    },
    
    selectText(x, y, maxLength) {
        if (document.elementFromPoint(x, y)?.closest('a')) {
            return null;
        }
        const hit = this.getCharacterAtPoint(x, y);
        
        if (!hit) {
            this.clearSelection();
            return null;
        }
        
        if (this.selection &&
            hit.node === this.selection.startNode &&
            hit.offset === this.selection.startOffset) {
            this.clearSelection();
            return null;
        }
        
        this.clearSelection();
        
        const container = this.findParagraph(hit.node) || document.body;
        const walker = this.createWalker(container);
        
        let text = '';
        let node = hit.node;
        let offset = hit.offset;
        let ranges = [];
        
        walker.currentNode = node;
        while (text.length < maxLength && node) {
            const content = node.textContent;
            const start = offset;
            
            while (offset < content.length && text.length < maxLength) {
                const char = content[offset];
                if (this.isScanBoundary(char)) {
                    break;
                }
                text += char;
                offset++;
            }
            
            if (offset > start) {
                ranges.push({ node, start, end: offset });
            }
            
            if (offset < content.length || text.length >= maxLength) {
                break;
            }
            
            node = walker.nextNode();
            offset = 0;
        }
        
        if (!text) {
            return null;
        }
        
        this.selection = {
            startNode: hit.node,
            startOffset: hit.offset,
            ranges,
            text
        };
        
        const sentenceContext = this.getSentenceContext(hit.node, hit.offset);
        const normalizedOffset = window.hoshiReader ? this.getNormalizedOffset(hit.node, hit.offset) : null;
        webkit.messageHandlers.textSelected.postMessage({
            text,
            sentence: sentenceContext.sentence,
            rect: this.getSelectionRect(x, y),
            normalizedOffset,
            sentenceOffset: sentenceContext.sentenceOffset
        });
        
        return text;
    },
    
    getSelectionRect(x, y) {
        if (!this.selection?.ranges.length) {
            return null;
        }
        
        const first = this.selection.ranges[0];
        const range = document.createRange();
        range.setStart(first.node, first.start);
        range.setEnd(first.node, first.start + 1);
        
        const rects = Array.from(range.getClientRects());
        const rect = rects.find(rect => x >= rect.left && x <= rect.right && y >= rect.top && y <= rect.bottom) ?? range.getBoundingClientRect();
        return { x: rect.x, y: rect.y, width: rect.width, height: rect.height };
    },
    
    highlightSelection(charCount) {
        if (!this.selection?.ranges.length) {
            return;
        }
        
        const highlights = [];
        let remaining = charCount;
        
        for (const r of this.selection.ranges) {
            if (remaining <= 0) {
                break;
            }
            
            let end = r.start;
            while (end < r.end && remaining > 0) {
                const char = String.fromCodePoint(r.node.textContent.codePointAt(end));
                end += char.length;
                remaining--;
            }
            
            const range = document.createRange();
            range.setStart(r.node, r.start);
            range.setEnd(r.node, end);
            highlights.push(range);
        }
        
        CSS.highlights?.set('hoshi-selection', new Highlight(...highlights));
    },
    
    getNormalizedOffset(targetNode, offset) {
        let count = window.hoshiReader.nodeStartOffsets.get(targetNode) ?? 0;
        const text = targetNode.textContent;
        for (let i = 0; i < offset;) {
            const char = String.fromCodePoint(text.codePointAt(i));
            if (window.hoshiReader.isMatchableChar(char)) {
                count++;
            }
            i += char.length;
        }
        return count;
    },
    
    clearSelection() {
        window.getSelection()?.removeAllRanges();
        CSS.highlights?.get('hoshi-selection')?.clear();
        this.selection = null;
    }
};

let lastHasSelection = false;
document.addEventListener('selectionchange', () => {
    const s = getSelection();
    const hasSelection = !!s && !s.isCollapsed;
    if (hasSelection === lastHasSelection) return;
    lastHasSelection = hasSelection;
    try { window.webkit?.messageHandlers?.selectionState?.postMessage(hasSelection); } catch {}
});
