//
//  selection.js
//  Hoshi Reader
//
//  Copyright © 2026 Manhhao.
//  Copyright © 2026 Antimony.
//  SPDX-License-Identifier: GPL-3.0-or-later
//

window.hoshiRubyGeometry = window.hoshiRubyGeometry || {
    isVertical() {
        const reader = window.hoshiReader;
        if (reader && typeof reader.isVertical === 'function') {
            try {
                return !!reader.isVertical();
            } catch {}
        }
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

    rangesSubstantiallyOverlap(aStart, aEnd, bStart, bEnd) {
        const overlap = this.rangeOverlapAmount(aStart, aEnd, bStart, bEnd);
        const shorter = Math.min(aEnd - aStart, bEnd - bStart);
        return shorter > 0 && overlap > shorter / 2;
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

    clippedRubyRectForBase(baseRect, rubyRect) {
        const ruby = this.rectWithBounds(rubyRect);
        if (this.isVertical()) {
            const top = Math.max(baseRect.top, ruby.top);
            const bottom = Math.min(baseRect.bottom, ruby.bottom);
            if (bottom <= top) return null;
            return {
                x: ruby.left,
                y: top,
                width: ruby.right - ruby.left,
                height: bottom - top,
                left: ruby.left,
                top,
                right: ruby.right,
                bottom,
            };
        }
        const left = Math.max(baseRect.left, ruby.left);
        const right = Math.min(baseRect.right, ruby.right);
        if (right <= left) return null;
        return {
            x: left,
            y: ruby.top,
            width: right - left,
            height: ruby.bottom - ruby.top,
            left,
            top: ruby.top,
            right,
            bottom: ruby.bottom,
        };
    },

    rubyAwareRect(rect, node) {
        const rubyRects = this.rubyTextRects(node);
        if (!rubyRects.length) return this.rectObject(rect);
        const base = this.rectWithBounds(rect);
        let result = base;
        rubyRects.forEach((rubyRect) => {
            if (this.rubyRectMatchesBase(base, rubyRect)) {
                const clipped = this.clippedRubyRectForBase(base, rubyRect);
                if (clipped) {
                    result = this.unionRect(result, clipped);
                }
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
            return this.rangesSubstantiallyOverlap(a.x, a.x + a.width, b.x, b.x + b.width) &&
                b.y <= a.y + a.height + tolerance &&
                b.y + b.height >= a.y - tolerance;
        }
        return this.rangesSubstantiallyOverlap(a.y, a.y + a.height, b.y, b.y + b.height) &&
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
        language: 'ja',
        linkTapResult: null,
        imageTapResult: null,
        rubyAwareRects: false,
        scaleRects: true,
        textProjection: null,
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
        return window.hoshiRubyGeometry?.isVertical?.() ??
            (window.getComputedStyle(document.body).writingMode === "vertical-rl");
    },

    languagePolicy() {
        const configured = String(
            this.options.language ||
            document.documentElement?.dataset?.hoshiContentLanguage ||
            'ja'
        );
        const normalized = configured.toLowerCase().split('-')[0];
        const policies = window.hoshiSelectionLanguagePolicies || {};
        return policies[normalized] || policies[configured] || policies.default;
    },

    isScanBoundary(char) {
        const policy = this.languagePolicy();
        if (!policy) return true;
        return policy.isScanBoundary.call(policy, char, this);
    },

    isScanBoundaryAt(text, offset) {
        const policy = this.languagePolicy();
        if (!policy) return true;
        if (policy.isScanBoundaryAt) return policy.isScanBoundaryAt.call(policy, text, offset, this);
        return policy.isScanBoundary.call(policy, this.codePointAt(text, offset), this);
    },

    isHitBoundary(char) {
        const policy = this.languagePolicy();
        if (!policy) return true;
        const isBoundary = policy.isHitBoundary || policy.isScanBoundary;
        return isBoundary.call(policy, char, this);
    },

    isHitBoundaryAt(text, offset) {
        const policy = this.languagePolicy();
        if (!policy) return true;
        if (policy.isHitBoundaryAt) return policy.isHitBoundaryAt.call(policy, text, offset, this);
        const isBoundary = policy.isHitBoundary || policy.isScanBoundary;
        return isBoundary.call(policy, this.codePointAt(text, offset), this);
    },

    isWordStartBoundary(char) {
        const policy = this.languagePolicy();
        if (!policy) return true;
        const isBoundary = policy.isWordStartBoundary || policy.isHitBoundary || policy.isScanBoundary;
        return isBoundary.call(policy, char, this);
    },

    isWordStartBoundaryAt(text, offset) {
        const policy = this.languagePolicy();
        if (!policy) return true;
        if (policy.isWordStartBoundaryAt) return policy.isWordStartBoundaryAt.call(policy, text, offset, this);
        const isBoundary = policy.isWordStartBoundary || policy.isHitBoundary || policy.isScanBoundary;
        return isBoundary.call(policy, this.codePointAt(text, offset), this);
    },

    selectionStartForHit(hit) {
        const policy = this.languagePolicy();
        if (!policy) return hit;
        const normalizedHit = {
            ...hit,
            offset: this.codePointStartOffset(hit.node?.textContent || '', hit.offset),
        };
        return policy.selectionStartForHit ? policy.selectionStartForHit(normalizedHit, this) : normalizedHit;
    },

    findWordStart(hit) {
        const text = hit.node.textContent;
        let offset = this.codePointStartOffset(text, hit.offset);
        while (offset > 0) {
            const previous = this.previousCodePointStartOffset(text, offset);
            if (this.isWordStartBoundaryAt(text, previous)) {
                break;
            }
            offset = previous;
        }
        return { node: hit.node, offset };
    },

    codePointStartOffset(text, offset) {
        if (offset > 0 &&
            offset < text.length &&
            text.charCodeAt(offset) >= 0xDC00 &&
            text.charCodeAt(offset) <= 0xDFFF &&
            text.charCodeAt(offset - 1) >= 0xD800 &&
            text.charCodeAt(offset - 1) <= 0xDBFF) {
            return offset - 1;
        }
        return Math.max(0, Math.min(offset, text.length));
    },

    nextCodePointOffset(text, offset) {
        const start = this.codePointStartOffset(text, offset);
        if (start >= text.length) return text.length;
        const codePoint = text.codePointAt(start);
        if (codePoint === undefined) return text.length;
        return start + String.fromCodePoint(codePoint).length;
    },

    previousCodePointStartOffset(text, offset) {
        if (offset <= 0) return 0;
        return this.codePointStartOffset(text, offset - 1);
    },

    codePointAt(text, offset) {
        const start = this.codePointStartOffset(text, offset);
        const codePoint = text.codePointAt(start);
        return codePoint === undefined ? '' : String.fromCodePoint(codePoint);
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
                for (let i = 0; i < node.textContent.length;) {
                    const end = this.nextCodePointOffset(node.textContent, i);
                    range.setStart(node, i);
                    range.setEnd(node, end);
                    if (this.inCharRange(range, rectX, rectY)) {
                        range.collapse(true);
                        return range;
                    }
                    i = end;
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

            const start = this.codePointStartOffset(text, offset);
            const end = this.nextCodePointOffset(text, start);
            const charRange = document.createRange();
            charRange.setStart(node, start);
            charRange.setEnd(node, end);
            if (this.inCharRange(charRange, rectX, rectY)) {
                if (this.isHitBoundaryAt(text, start)) {
                    return null;
                }
                return { node, offset: start };
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
        const rawHit = this.getCharacterAtPoint(x, y, rectX, rectY);

        if (!rawHit) {
            this.clearSelection();
            return null;
        }
        const projection = this.options.textProjection;
        const semanticHit = projection
            ? projection.toSemanticHit?.(rawHit)
            : rawHit;
        if (!semanticHit) {
            this.clearSelection();
            return null;
        }
        const hit = this.selectionStartForHit(semanticHit);

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
                const char = this.codePointAt(content, offset);
                if (this.isScanBoundaryAt(content, offset)) {
                    break;
                }
                text += char;
                offset = this.nextCodePointOffset(content, offset);
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
        const visibleRanges = projection
            ? projection.visibleRangesForSemanticRanges?.(ranges)
            : ranges;
        if (!visibleRanges?.length) {
            return null;
        }

        this.selection = {
            startNode: hit.node,
            startOffset: hit.offset,
            ranges: visibleRanges,
            semanticRanges: ranges,
            text
        };

        const sentenceContext = this.getSentenceContext(hit.node, hit.offset);
        const normalizedOffset = projection
            ? projection.normalizedOffsetForHit?.(hit) ?? null
            : window.hoshiReader
                ? this.getNormalizedOffset(hit.node, hit.offset)
                : null;
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
        const end = this.nextCodePointOffset(first.node.textContent, first.start);
        const range = document.createRange();
        range.setStart(first.node, first.start);
        range.setEnd(first.node, end);

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
                if (window.hoshiRubyGeometry.rangesSubstantiallyOverlap(left, right, candidate.left, candidate.right)) {
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
        const targetOffset = this.codePointStartOffset(text, offset);
        for (let i = 0; i < targetOffset;) {
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
