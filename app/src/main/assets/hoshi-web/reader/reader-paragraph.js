//
//  paragraph.js
//  Hoshi Reader
//
//  Copyright © 2026 Manhhao.
//  SPDX-License-Identifier: GPL-3.0-or-later
//

window.hoshiParagraph = {
    animationFrame: null,
    animationGaiji: [],
    enabled: false,
    textAnimation: false,
    textSpeed: 35,

    // Native tap selection and DOM link/image clicks can arrive in either order.
    // Resolve once per gesture, including false: a late DOM click must not finish
    // the new page's reveal after native blank-tap navigation has already run.
    installTapHandler() {
        document.addEventListener('pointerdown', () => { this.consumedAnimationTap = null; }, true);
        document.addEventListener('click', event => {
            if (!this.enabled || window.hoshiReader.nativeSelectionActive) return;
            if (this.consumeAnimationTap()) {
                event.preventDefault();
                event.stopImmediatePropagation();
            }
        }, true);
    },

    consumeAnimationTap() {
        if (!this.enabled || window.hoshiReader.nativeSelectionActive) return false;
        if (this.consumedAnimationTap == null) {
            this.consumedAnimationTap = this.finishTextAnimation();
        }
        return this.consumedAnimationTap;
    },

    selectText(x, y, maxLength) {
        if (this.consumeAnimationTap()) return 'animation';
        const selection = window.hoshiSelection;
        const result = selection.selectText(x, y, maxLength);
        // Deselecting a word or tapping unscannable text is still a text interaction.
        return result == null && selection.lastTapWasText ? 'text' : result;
    },

    configureAnimation(enabled, speed) {
        this.textAnimation = enabled;
        this.textSpeed = speed;
        if (!enabled) this.finishTextAnimation();
    },

    animatePage() {
        if (this.enabled && this.textAnimation) this.animateText(this.textSpeed);
    },

    splitSentences(maxSentencesPerPage, splitDialogue) {
        const { brackets } = window.hoshiSelection;
        const openBrackets = Object.keys(brackets);
        const closeBrackets = Object.values(brackets);
        const sentenceDelimiters = '。！？!?';
        const range = document.createRange();

        for (const paragraph of document.querySelectorAll('p')) {
            const walker = window.hoshiReader.createWalker(paragraph);
            const points = [];
            let sentences = 0;
            let depth = 0;
            let ended = false;
            let node;
            let previous;

            while (node = walker.nextNode()) {
                const text = node.textContent;
                for (let i = 0; i < text.length; i++) {
                    const char = text[i];
                    if (ended && char.trim() && !sentenceDelimiters.includes(char) && !closeBrackets.includes(char)) {
                        ended = false;
                        if (++sentences % maxSentencesPerPage === 0) {
                            points.push(i ? { node, offset: i } : { node: previous, offset: previous.length });
                        }
                    }
                    if (openBrackets.includes(char)) {
                        depth++;
                    } else if (depth && closeBrackets.includes(char)) {
                        depth--;
                        ended = false;
                    } else if (sentenceDelimiters.includes(char) && (splitDialogue || !depth)) {
                        ended = true;
                    }
                }
                previous = node;
            }

            for (const point of points.reverse()) {
                range.setStart(point.node, point.offset);
                range.setEndAfter(paragraph.lastChild);

                const sentence = paragraph.cloneNode(false);
                sentence.classList.add('hoshi-sentence');
                sentence.appendChild(range.extractContents());
                paragraph.after(sentence);
            }
        }
    },

    layoutParagraphs(maxSentencesPerPage, splitDialogue) {
        if (maxSentencesPerPage > 0) {
            this.splitSentences(maxSentencesPerPage, splitDialogue);
        }

        for (const paragraph of document.querySelectorAll('p')) {
            if (paragraph.textContent.trim() || paragraph.querySelector('img, svg')) {
                paragraph.classList.add('hoshi-paragraph');
                continue;
            }
            // Collapse spacing-only paragraphs, retaining their anchors in flow.
            // Keep other publisher content in ordinary column layout.
            const hasContent = paragraph.querySelector(':not(br):not(a):not(span)')
                || [paragraph, ...paragraph.querySelectorAll('*')].some(element => {
                    const style = getComputedStyle(element);
                    return style.backgroundImage !== 'none'
                        || !['transparent', 'rgba(0, 0, 0, 0)'].includes(style.backgroundColor)
                        || ['Top', 'Right', 'Bottom', 'Left'].some(side => parseFloat(style[`border${side}Width`]) > 0)
                        || ['::before', '::after'].some(pseudo => {
                            const content = getComputedStyle(element, pseudo).content;
                            return content && !['none', 'normal', '""', "''"].includes(content);
                        });
                });
            if (!hasContent) paragraph.classList.add('hoshi-paragraph-empty');
        }

        const reader = window.hoshiReader;
        const vertical = reader.isVertical();
        const style = window.getComputedStyle(document.body);
        // One page-sized block box lets CSS center short paragraphs and every
        // continuation identically, without measuring their text or adding padding.
        const available = vertical
        ? reader.pageWidth - parseFloat(style.paddingLeft) - parseFloat(style.paddingRight)
        : reader.pageHeight - parseFloat(style.paddingTop) - parseFloat(style.paddingBottom);
        document.body.style.setProperty('--hoshi-paragraph-block-size', `${available}px`);
    },

    isOnPage(rect, context = window.hoshiReader.getScrollContext()) {
        // WebView can round an aligned scroll to a fractional CSS pixel. Snap both
        // page boundaries equally so page-start text stays here, not on the prior page.
        const position = Math.round(context.vertical ? rect.top : rect.left);
        return position >= 0 && position < context.pageSize;
    },

    pageSasayakiCues() {
        const reader = window.hoshiReader;
        const context = reader.getScrollContext();
        const ids = [];
        const starts = new Map();
        reader.cueWrappers.forEach((wrappers, id) => { if (wrappers[0]) starts.set(id, wrappers[0]); });
        reader.cueGeometryRanges.forEach((ranges, id) => { if (!starts.has(id) && ranges[0]) starts.set(id, ranges[0]); });
        starts.forEach((target, id) => {
            if (this.isOnPage(reader.getRect(target), context)) ids.push(id);
        });
        return ids;
    },

    animateText(speed) {
        this.finishTextAnimation();
        if (!globalThis.CSS?.highlights || typeof Highlight === 'undefined') return;
        const reader = window.hoshiReader;
        const context = reader.getScrollContext();
        const scroll = reader.getPagePosition(context);
        const metrics = reader.paginationMetrics || reader.buildPaginationMetrics();
        const chars = [];
        let last = null;

        for (const { node, first } of metrics.textRanges) {
            const position = Math.round(first - scroll);
            if (position < 0 || position >= context.pageSize) {
                continue;
            }
            let offset = 0;
            for (const char of node.textContent) {
                chars.push({ node, offset });
                offset += char.length;
            }
            last = node;
        }

        if (chars.length < 2) {
            return;
        }

        // Highlights hide text, not image pixels or generated fallback content.
        // Reveal gaiji at the existing text boundary, keeping their layout space.
        for (const element of document.querySelectorAll('img[class*="gaiji" i], .hoshi-gaiji-fallback')) {
            if (!this.isOnPage(reader.getRect(element), context)) continue;
            const next = chars.findIndex(({ node }) =>
                element.compareDocumentPosition(node) & Node.DOCUMENT_POSITION_FOLLOWING);
            const revealAt = next < 0 ? chars.length : next;
            if (revealAt <= 1) continue;
            element.classList.add('hoshi-animation-hidden');
            this.animationGaiji.push({ element, revealAt });
        }

        const hidden = document.createRange();
        hidden.setStart(chars[1].node, chars[1].offset);
        hidden.setEnd(last, last.length);
        CSS.highlights.set('hoshi-animation', new Highlight(hidden));

        // Frame timestamps can predate performance.now() at setup after a busy
        // turn. Use the first frame as the origin of the same animation clock.
        let startTime = null;
        let revealed = 1;
        const tick = (now) => {
            if (startTime === null) startTime = now;
            const count = Math.floor((now - startTime) * speed / 1000) + 1;
            if (count >= chars.length) {
                this.finishTextAnimation();
                return;
            }
            // Multiple display frames can fall between two characters.
            if (count !== revealed) {
                hidden.setStart(chars[count].node, chars[count].offset);
                revealed = count;
                while (this.animationGaiji.length && this.animationGaiji[0].revealAt <= count) {
                    this.animationGaiji.shift().element.classList.remove('hoshi-animation-hidden');
                }
            }
            this.animationFrame = requestAnimationFrame(tick);
        };
        this.animationFrame = requestAnimationFrame(tick);
    },

    finishTextAnimation() {
        const active = this.animationFrame !== null;
        if (active) cancelAnimationFrame(this.animationFrame);
        this.animationFrame = null;
        for (const { element } of this.animationGaiji) element.classList.remove('hoshi-animation-hidden');
        this.animationGaiji = [];
        globalThis.CSS?.highlights?.get?.('hoshi-animation')?.clear();
        globalThis.CSS?.highlights?.delete?.('hoshi-animation');
        return active;
    },
    positionRects(range, position, vertical) {
        if (position.node.nodeType !== Node.TEXT_NODE) {
            range.selectNode(position.node);
        } else {
            range.setStart(position.node, position.offset);
            range.setEnd(position.node, position.offset + String.fromCodePoint(position.node.textContent.codePointAt(position.offset)).length);
        }

        return [...range.getClientRects()].filter(rect => vertical ? rect.height : rect.width);
    },

    splitPoints(paragraph, pageSize, currentScroll, vertical, crossedRubyParagraphs) {
        const rect = paragraph.getBoundingClientRect();
        const start = (vertical ? rect.top : rect.left) + currentScroll;
        const first = Math.floor(start / pageSize);
        const last = Math.floor((start + (vertical ? rect.height : rect.width) - 1) / pageSize);
        const points = [];
        if (last === first) {
            return points;
        }

        const walker = document.createTreeWalker(paragraph, NodeFilter.SHOW_TEXT | NodeFilter.SHOW_ELEMENT, {
            acceptNode: (n) => n.parentElement.closest('ruby') ? NodeFilter.FILTER_REJECT
            : n.nodeType === Node.TEXT_NODE || !n.firstChild || n.localName === 'ruby' ? NodeFilter.FILTER_ACCEPT
            : NodeFilter.FILTER_SKIP
        });
        const positions = [];
        let node;

        while (node = walker.nextNode()) {
            if (node.nodeType !== Node.TEXT_NODE) {
                positions.push({ node });
                continue;
            }
            for (let i = 0; i < node.textContent.length;) {
                positions.push({ node, offset: i });
                i += String.fromCodePoint(node.textContent.codePointAt(i)).length;
            }
        }

        const range = document.createRange();
        for (let column = first + 1; column <= last; column++) {
            const limit = column * pageSize;
            let low = 0;
            let high = positions.length - 1;
            let hit = -1;

            while (low <= high) {
                let mid = (low + high) >> 1;
                let rects = this.positionRects(range, positions[mid], vertical);
                while (!rects.length && ++mid <= high) {
                    rects = this.positionRects(range, positions[mid], vertical);
                }

                // A hanging character can extend past the page without starting on it.
                const isText = rects.length > 0 && positions[mid].node.nodeType === Node.TEXT_NODE;
                const reachesPage = isText
                    ? Math.round((vertical ? rects[0].top : rects[0].left) + currentScroll) >= limit
                    : Math.max(...rects.map(rect => vertical ? rect.bottom : rect.right)) + currentScroll > limit;
                if (reachesPage) {
                    hit = mid;
                    high = mid - 1;
                } else {
                    low = mid + 1;
                }
            }

            if (hit >= 0) {
                const position = positions[hit];
                if (position.node.nodeType === Node.TEXT_NODE) {
                    if (hit > 0) points.push(position);
                    continue;
                }
                const rects = this.positionRects(range, position, vertical);
                const rect = rects[0];
                if (crossedRubyParagraphs && position.node.localName === 'ruby' && rect
                    && Math.round((vertical ? rect.top : rect.left) + currentScroll) < limit
                    && rects.some(r => Math.round((vertical ? r.top : r.left) + currentScroll) >= limit)) {
                    crossedRubyParagraphs.add(paragraph);
                }
                if (hit > 0 && rect && (vertical ? rect.top : rect.left) + currentScroll >= limit) {
                    points.push(position);
                }
            }
        }

        return points;
    },

    rubySplitTargets(paragraphs, pageSize, vertical) {
        const sources = [...paragraphs].map(paragraph => {
            const style = window.getComputedStyle(paragraph);
            return { paragraph, inlineLength: Math.min(pageSize, parseFloat(style.inlineSize) || pageSize),
                justified: style.textAlign === 'justify' };
        });
        // A separate fragmentation context shares the loaded fonts without dirtying
        // the chapter's text layout. Its viewport and columns inherit from body.
        const probe = document.createElement('div');
        for (const attr of document.body.attributes) {
            if (!attr.namespaceURI && attr.name !== 'id' && attr.name !== 'style') {
                probe.setAttribute(attr.name, attr.value);
            }
        }
        probe.style.cssText = 'all:inherit!important;position:fixed!important;top:0!important;left:0!important;'
            + 'right:auto!important;bottom:auto!important;contain:strict!important;visibility:hidden!important;'
            + 'opacity:0!important;pointer-events:none!important';
        try {
            const nodeMap = new WeakMap();
            const items = sources.map(source => {
                let host = probe;
                const ancestors = [];
                for (let node = source.paragraph.parentElement; node && node !== document.body; node = node.parentElement) {
                    ancestors.push(node);
                }
                for (const ancestor of ancestors.reverse()) {
                    const clone = ancestor.cloneNode(false);
                    host.appendChild(clone);
                    host = clone;
                }
                const clone = source.paragraph.cloneNode(true);
                host.appendChild(clone);
                const originals = [source.paragraph], copies = [clone];
                for (let i = 0; i < originals.length; i++) {
                    nodeMap.set(copies[i], originals[i]);
                    originals.push(...originals[i].childNodes);
                    copies.push(...copies[i].childNodes);
                }
                const rubies = [...clone.querySelectorAll('ruby')].map(ruby => ({
                    ruby, style: ruby.getAttribute('style'), inlineLength: source.inlineLength,
                }));
                // Protect all ruby in a hit paragraph: moving one can move a later cut.
                for (const { ruby } of rubies) ruby.style.setProperty('white-space', 'nowrap', 'important');
                return { ...source, clone, rubies };
            });
            document.body.appendChild(probe);

            const rejected = new Set();
            for (const item of items) for (const candidate of item.rubies) {
                const rects = [...candidate.ruby.getClientRects()].filter(r => vertical ? r.height : r.width);
                const lines = rects.map(r => vertical ? r.left : r.top);
                const starts = rects.map(r => vertical ? r.top : r.left);
                const ends = rects.map(r => vertical ? r.bottom : r.right);
                if (!rects.length || Math.max(...lines) - Math.min(...lines) > 0.5
                    || Math.max(...ends) - Math.min(...starts) > candidate.inlineLength + 0.5) {
                    rejected.add(candidate);
                }
            }
            // Restore oversized ruby in one write batch before remeasuring cuts.
            for (const { ruby, style } of rejected) {
                if (style === null) ruby.removeAttribute('style');
                else ruby.setAttribute('style', style);
            }
            return items.map(({ paragraph, clone, justified, rubies }) => ({
                paragraph, justified,
                points: this.splitPoints(clone, pageSize, 0, vertical)
                    .map(point => ({ ...point, node: nodeMap.get(point.node) })),
                rubies: rubies.filter(ruby => !rejected.has(ruby)).map(({ ruby }) => nodeMap.get(ruby)),
            }));
        } finally {
            probe.remove();
        }
    },

    fragmentBlocks() {
        const reader = window.hoshiReader;
        const context = reader.getScrollContext();
        const { vertical, pageSize } = context;
        const currentScroll = reader.getPagePosition(context);
        let targets = [];
        const crossedRubyParagraphs = new Set();

        for (const paragraph of document.body.querySelectorAll('p')) {
            const points = this.splitPoints(paragraph, pageSize, currentScroll, vertical, crossedRubyParagraphs);
            if (points.length) {
                const justified = window.getComputedStyle(paragraph).textAlign === 'justify';
                targets.push({ paragraph, points, justified });
            }
        }

        if (crossedRubyParagraphs.size) {
            const repaired = this.rubySplitTargets(crossedRubyParagraphs, pageSize, vertical);
            targets = targets.filter(target => !crossedRubyParagraphs.has(target.paragraph));
            targets.push(...repaired.filter(target => target.points.length));
            // No geometry reads after touching originals: protection and fragmentation
            // share the final write batch, followed by the reader's existing layout.
            for (const { rubies } of repaired) {
                for (const ruby of rubies) ruby.style.setProperty('white-space', 'nowrap', 'important');
            }
        }

        const range = document.createRange();
        for (const { paragraph, points, justified } of targets) {
            const firstFragment = document.createElement('span');
            firstFragment.className = 'hoshi-fragment';
            while (paragraph.firstChild) {
                firstFragment.appendChild(paragraph.firstChild);
            }
            paragraph.appendChild(firstFragment);

            const fragments = [firstFragment];
            for (let i = points.length - 1; i >= 0; i--) {
                if (points[i].offset > 0) {
                    range.setStart(points[i].node, points[i].offset);
                } else {
                    let start = points[i].node;
                    while (start.parentNode !== firstFragment && start.parentNode.firstChild === start) {
                        start = start.parentNode;
                    }
                    range.setStartBefore(start);
                }
                range.setEnd(firstFragment, firstFragment.childNodes.length);

                const fragment = document.createElement('span');
                fragment.className = 'hoshi-fragment';
                fragment.appendChild(range.extractContents());
                firstFragment.after(fragment);
                fragments.splice(1, 0, fragment);
            }

            if (justified) {
                fragments.slice(0, -1).forEach(f => f.style.setProperty('text-align-last', 'justify', 'important'));
            }
        }
    },

};
