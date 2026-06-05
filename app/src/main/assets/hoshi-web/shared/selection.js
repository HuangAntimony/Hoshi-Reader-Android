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

window.hoshiRubyGeometry = window.hoshiRubyGeometry || {
    isVertical() {
        return window.getComputedStyle(document.body).writingMode === "vertical-rl";
    },

    rectObject(rect) {
        const x = rect.x !== undefined ? rect.x : rect.left;
        const y = rect.y !== undefined ? rect.y : rect.top;
        const width = rect.width !== undefined ? rect.width : rect.right - rect.left;
        const height = rect.height !== undefined ? rect.height : rect.bottom - rect.top;
        return { x, y, width, height };
    },

    rectWithBounds(rect) {
        const object = this.rectObject(rect);
        return {
            x: object.x,
            y: object.y,
            width: object.width,
            height: object.height,
            left: rect.left !== undefined ? rect.left : object.x,
            top: rect.top !== undefined ? rect.top : object.y,
            right: rect.right !== undefined ? rect.right : object.x + object.width,
            bottom: rect.bottom !== undefined ? rect.bottom : object.y + object.height,
        };
    },

    unionRect(a, b) {
        const left = Math.min(a.left, b.left);
        const top = Math.min(a.top, b.top);
        const right = Math.max(a.right, b.right);
        const bottom = Math.max(a.bottom, b.bottom);
        return { x: left, y: top, width: right - left, height: bottom - top, left, top, right, bottom };
    },

    rangesOverlap(aStart, aEnd, bStart, bEnd, tolerance) {
        return bStart <= aEnd + tolerance && bEnd >= aStart - tolerance;
    },

    rangeOverlapAmount(aStart, aEnd, bStart, bEnd) {
        return Math.min(aEnd, bEnd) - Math.max(aStart, bStart);
    },

    rubyForNode(node) {
        const el = node?.nodeType === Node.TEXT_NODE ? node.parentElement : node;
        return el?.closest ? el.closest('ruby') : null;
    },

    rubyTextRects(node) {
        const ruby = this.rubyForNode(node);
        if (!ruby) return [];
        const rects = [];
        ruby.querySelectorAll('rt').forEach((rt) => {
            Array.from(rt.getClientRects()).forEach((rect) => rects.push(rect));
        });
        return rects;
    },

    rubyRectMatchesBase(baseRect, rubyRect) {
        const ruby = this.rectWithBounds(rubyRect);
        const minimumOverlap = 1;
        if (this.isVertical()) {
            return this.rangeOverlapAmount(baseRect.top, baseRect.bottom, ruby.top, ruby.bottom) > minimumOverlap;
        }
        return this.rangeOverlapAmount(baseRect.left, baseRect.right, ruby.left, ruby.right) > minimumOverlap;
    },

    rubyAwareRect(rect, node) {
        const rubyRects = this.rubyTextRects(node);
        if (!rubyRects.length) return this.rectObject(rect);
        const base = this.rectWithBounds(rect);
        let result = base;
        rubyRects.forEach((rubyRect) => {
            if (this.rubyRectMatchesBase(base, rubyRect)) {
                result = this.unionRect(result, this.rectWithBounds(rubyRect));
            }
        });
        return this.rectObject(result);
    },

    rectsForRange(range) {
        let rects = Array.from(range.getClientRects());
        if (!rects.length) {
            const fallback = range.getBoundingClientRect();
            if (fallback?.width > 0 && fallback?.height > 0) rects = [fallback];
        }
        return rects
            .map((rect) => this.rubyAwareRect(rect, range.startContainer))
            .filter((rect) => rect.width > 0 && rect.height > 0);
    },

    inlineRectsTouch(a, b) {
        const tolerance = 0.5;
        if (this.isVertical()) {
            return this.rangesOverlap(a.x, a.x + a.width, b.x, b.x + b.width, tolerance) &&
                b.y <= a.y + a.height + tolerance &&
                b.y + b.height >= a.y - tolerance;
        }
        return this.rangesOverlap(a.y, a.y + a.height, b.y, b.y + b.height, tolerance) &&
            b.x <= a.x + a.width + tolerance &&
            b.x + b.width >= a.x - tolerance;
    },

    mergeInlineRects(rects) {
        const merged = [];
        rects.forEach((rect) => {
            const current = { x: rect.x, y: rect.y, width: rect.width, height: rect.height };
            const previous = merged[merged.length - 1];
            if (previous && this.inlineRectsTouch(previous, current)) {
                const left = Math.min(previous.x, current.x);
                const top = Math.min(previous.y, current.y);
                const right = Math.max(previous.x + previous.width, current.x + current.width);
                const bottom = Math.max(previous.y + previous.height, current.y + current.height);
                previous.x = left;
                previous.y = top;
                previous.width = right - left;
                previous.height = bottom - top;
            } else {
                merged.push(current);
            }
        });
        return merged;
    },
};

window.hoshiSelection = {
    selection: null,
    options: {
        bridge: 'webkit',
        linkTapResult: null,
        imageTapResult: null,
        rubyAwareRects: false,
        scaleRects: true,
    },
    scanDelimiters: '。、！？…‥「」『』（）()【】〈〉《》〔〕｛｝{}［］[]・：；:;，,.─\n\r',
    sentenceDelimiters: '。！？.!?\n\r',
    trailingSentenceChars: '。、！？…‥」』）)】〉》〕｝}］]',
    brackets: {'「':'」', '『': '』', '（':'）', '(':')', '【':'】', '〈':'〉', '《':'》', '〔':'〕', '｛':'｝', '{':'}', '［':'］', '[':']'},

    configure(options = {}) {
        this.options = { ...this.options, ...options };
    },

    postTextSelected(selection) {
        if (this.options.bridge === 'android-reader') {
            const bridge = window.HoshiTextSelection || globalThis.HoshiTextSelection;
            bridge?.postMessage?.(JSON.stringify(selection));
            return;
        }
        window.webkit?.messageHandlers?.textSelected?.postMessage(selection);
    },

    linkTapResult() {
        return this.options.linkTapResult;
    },

    imageTapResult() {
        return this.options.imageTapResult;
    },

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

    getCaretRange(x, y, rectX = x, rectY = y) {
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
                    if (this.inCharRange(range, rectX, rectY)) {
                        range.collapse(true);
                        return range;
                    }
                }
            }
            return document.caretRangeFromPoint(x, y);
        }
    },

    getCharacterAtPoint(x, y, rectX = x, rectY = y) {
        const range = this.getCaretRange(x, y, rectX, rectY);
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
            if (this.inCharRange(charRange, rectX, rectY)) {
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

    selectText(x, y, maxLength, rectX = x, rectY = y) {
        const hitElement = document.elementFromPoint(x, y);
        if (hitElement?.closest('a')) {
            return this.linkTapResult();
        }
        if (hitElement?.closest('img, image, .blur-wrapper')) {
            return this.imageTapResult();
        }
        const hit = this.getCharacterAtPoint(x, y, rectX, rectY);

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
        this.postTextSelected({
            text,
            sentence: sentenceContext.sentence,
            rect: this.getSelectionRect(rectX, rectY),
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

        const rects = this.rectsForRange(range);
        const rect = rects.find(rect => x >= rect.x && x <= rect.x + rect.width && y >= rect.y && y <= rect.y + rect.height) ??
            rects[0] ??
            window.hoshiRubyGeometry.rectObject(range.getBoundingClientRect());
        return this.selectionRectForBridge(rect);
    },

    selectionRects(charCount) {
        if (!this.selection?.ranges.length) {
            return [];
        }

        const rects = [];
        for (const range of this.selectionCharacterRanges(charCount)) {
            this.rectsForRange(range).forEach((rect) => rects.push(rect));
        }

        const merged = this.mergeSelectionRects(rects);
        return this.options.rubyAwareRects ? this.unifyVerticalColumnRects(merged) : merged;
    },

    rectsForRange(range) {
        if (this.options.rubyAwareRects) {
            return window.hoshiRubyGeometry.rectsForRange(range);
        }
        const rects = Array.from(range.getClientRects());
        if (!rects.length) {
            const fallback = range.getBoundingClientRect();
            if (fallback?.width > 0 && fallback?.height > 0) rects.push(fallback);
        }
        return rects
            .map((rect) => window.hoshiRubyGeometry.rectObject(rect))
            .filter((rect) => rect.width > 0 && rect.height > 0);
    },

    selectionRectForBridge(rect) {
        if (!this.options.scaleRects) {
            return rect;
        }
        const scale = window.getButtonRectScale?.() ?? 1;
        const scrollX = window.scrollX || 0;
        const scrollY = window.scrollY || 0;
        return {
            x: (rect.x + scrollX) * scale - scrollX,
            y: (rect.y + scrollY) * scale - scrollY,
            width: rect.width * scale,
            height: rect.height * scale
        };
    },

    highlightSelection(charCount) {
        if (!this.selection?.ranges.length) {
            return;
        }

        const highlights = this.selectionCharacterRanges(charCount);
        CSS.highlights?.set('hoshi-selection', new Highlight(...highlights));
    },

    selectionCharacterRanges(charCount) {
        if (!this.selection?.ranges.length) {
            return [];
        }

        const ranges = [];
        let remaining = charCount;

        for (const r of this.selection.ranges) {
            if (remaining <= 0) {
                break;
            }

            let start = r.start;
            let end = start;
            while (end < r.end && remaining > 0) {
                const char = String.fromCodePoint(r.node.textContent.codePointAt(end));
                end += char.length;
                remaining--;

                const range = document.createRange();
                range.setStart(r.node, start);
                range.setEnd(r.node, end);
                ranges.push(range);
                start = end;
            }
        }

        return ranges;
    },

    mergeSelectionRects(rects) {
        const merged = [];

        for (const rect of rects) {
            const current = { x: rect.x, y: rect.y, width: rect.width, height: rect.height };
            const previous = merged[merged.length - 1];
            if (previous && this.selectionRectsTouch(previous, current)) {
                const left = Math.min(previous.x, current.x);
                const top = Math.min(previous.y, current.y);
                const right = Math.max(previous.x + previous.width, current.x + current.width);
                const bottom = Math.max(previous.y + previous.height, current.y + current.height);
                previous.x = left;
                previous.y = top;
                previous.width = right - left;
                previous.height = bottom - top;
            } else {
                merged.push(current);
            }
        }

        return merged;
    },

    selectionRectsTouch(a, b) {
        if (this.options.rubyAwareRects) {
            return window.hoshiRubyGeometry.inlineRectsTouch(a, b);
        }
        const tolerance = 0.5;
        if (this.isVertical()) {
            return Math.abs(a.x - b.x) <= tolerance &&
                Math.abs(a.width - b.width) <= tolerance &&
                b.y <= a.y + a.height + tolerance &&
                b.y + b.height >= a.y - tolerance;
        }
        return Math.abs(a.y - b.y) <= tolerance &&
            Math.abs(a.height - b.height) <= tolerance &&
            b.x <= a.x + a.width + tolerance &&
            b.x + b.width >= a.x - tolerance;
    },

    unifyVerticalColumnRects(rects) {
        if (!this.isVertical() || !rects.length) return rects;
        const groups = [];
        const groupForIndex = new Array(rects.length);
        rects.forEach((rect, index) => {
            const left = rect.x;
            const right = rect.x + rect.width;
            let group = null;
            for (const candidate of groups) {
                if (left < candidate.right && right > candidate.left) {
                    group = candidate;
                    break;
                }
            }
            if (group) {
                group.left = Math.min(group.left, left);
                group.right = Math.max(group.right, right);
            } else {
                group = { left, right };
                groups.push(group);
            }
            groupForIndex[index] = group;
        });
        return rects.map((rect, index) => {
            const group = groupForIndex[index];
            return { x: group.left, y: rect.y, width: group.right - group.left, height: rect.height };
        });
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
