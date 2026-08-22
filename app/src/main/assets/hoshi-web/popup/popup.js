//
//  popup.js
//  Hoshi Reader
//
//  Copyright © 2026 Manhhao.
//  Copyright © 2023-2025 Yomitan Authors.
//  Copyright © 2021-2022 Yomichan Authors.
//  SPDX-License-Identifier: GPL-3.0-or-later
//

const KANJI_RANGE = '\u4E00-\u9FFF\u3400-\u4DBF\uF900-\uFAFF\u3005';
const KANJI_PATTERN = new RegExp(`[${KANJI_RANGE}]`);
const KANJI_SEGMENT_PATTERN = new RegExp(`[${KANJI_RANGE}]+|[^${KANJI_RANGE}]+`, 'g');
const KANA_PATTERN = /[\u3040-\u30FF\uFF66-\uFF9F]/;
const DEFAULT_HARMONIC_RANK = '9999999';
const SMALL_KANA_SET = new Set('ぁぃぅぇぉゃゅょゎァィゥェォャュョヮ');
const NUMERIC_TAG = /^\d+$/;
// this might not cover every tag
const POS_TAGS = new Set(['n', 'adj-i', 'adj-na', 'adj-no', 'v1', 'vk', 'vs', 'vs-i', 'vs-s', 'vz', 'vi', 'vt']);
let audioUrls = {};
let lastSelection = '';
let currentDictionaryMedia = null;
let selectedDictionaries = {};
let dictionaryMediaObserver = null;
let renderGeneration = 0;
let kanjiRedirectRequestId = 0;
let hostEntrySetVersion = 0;
let activeEntrySetVersion = 0;

window.createPopupGeometry = function({
    documentRef = document,
    windowRef = window,
    computedStyle = getComputedStyle,
} = {}) {
    function scrollRoot() {
        return documentRef.scrollingElement || documentRef.documentElement || documentRef.body;
    }

    function scrollTop() {
        const rootTop = Number(scrollRoot()?.scrollTop);
        if (Number.isFinite(rootTop)) return rootTop;
        const windowTop = Number(windowRef.scrollY);
        return Number.isFinite(windowTop) ? windowTop : 0;
    }

    function setScrollTop(value) {
        const top = Number.isFinite(value) ? value : 0;
        const root = scrollRoot();
        if (root) root.scrollTop = top;
        windowRef.scrollTo?.(0, top);
    }

    function viewportHeight() {
        const candidates = [
            documentRef.documentElement?.clientHeight,
            windowRef.innerHeight,
            scrollRoot()?.clientHeight,
        ];
        return candidates.find(value => Number.isFinite(value) && value > 0) || 0;
    }

    function scrollByViewport(direction, scale = 1) {
        const root = scrollRoot();
        if (!root) return;
        const height = viewportHeight();
        const maxScrollTop = Math.max(0, (root.scrollHeight || 0) - height);
        const target = Math.max(0, Math.min(
            maxScrollTop,
            scrollTop() + height * scale * direction,
        ));
        setScrollTop(target);
    }

    function elementDocumentTop(element) {
        const top = Number(element?.getBoundingClientRect?.().top);
        return Number.isFinite(top) ? scrollTop() + top : Number.NaN;
    }

    function scrollElementToTop(element) {
        element?.scrollIntoView?.({
            block: 'start',
            inline: 'nearest',
            behavior: 'instant',
        });
    }

    function bridgeRectScale() {
        const zoom = Number.parseFloat(computedStyle(documentRef.documentElement).zoom);
        if (!Number.isFinite(zoom) || zoom === 1) return 1;

        const probe = documentRef.createElement('div');
        probe.style = 'position:absolute;width:100px;visibility:hidden;';
        const parent = documentRef.documentElement || documentRef.body;
        if (!parent?.appendChild) return 1;

        parent.appendChild(probe);
        const width = probe.getBoundingClientRect().width;
        probe.remove();
        if (!Number.isFinite(width) || width <= 0) return 1;

        const scale = 100 * zoom / width;
        return Number.isFinite(scale) && scale > 0 ? scale : 1;
    }

    function selectionCoordinates(clientX, clientY) {
        const scale = bridgeRectScale();
        const scrollX = windowRef.scrollX || 0;
        const scrollY = windowRef.scrollY || 0;
        return {
            rectX: (clientX + scrollX) / scale - scrollX,
            rectY: (clientY + scrollY) / scale - scrollY,
        };
    }

    function bridgeSelectionRect(rect) {
        const scale = bridgeRectScale();
        const scrollX = windowRef.scrollX || 0;
        const scrollY = windowRef.scrollY || 0;
        return {
            x: (rect.x + scrollX) * scale - scrollX,
            y: (rect.y + scrollY) * scale - scrollY,
            width: rect.width * scale,
            height: rect.height * scale,
        };
    }

    return Object.freeze({
        bridgeRectScale,
        bridgeSelectionRect,
        elementDocumentTop,
        scrollByViewport,
        scrollElementToTop,
        scrollTop,
        selectionCoordinates,
        setScrollTop,
        viewportHeight,
    });
};

const popupGeometry = window.createPopupGeometry();
window.hoshiPopupGeometry = popupGeometry;
window.getButtonRectScale = () => popupGeometry.bridgeRectScale();

window.createPopupTermNavigator = function({ entryCount, entries, scrollTop, scrollTo }) {
    const topTolerance = 1;
    let pendingIndex = null;

    function sortedEntries() {
        return entries()
            .filter(entry => Number.isInteger(entry.index) && Number.isFinite(entry.top))
            .sort((a, b) => a.index - b.index);
    }

    function currentEntry(availableEntries, currentScrollTop) {
        if (!availableEntries.length) return null;
        let current = availableEntries[0];
        for (const entry of availableEntries) {
            if (entry.top > currentScrollTop + topTolerance) break;
            current = entry;
        }
        return current;
    }

    function fulfillPending() {
        if (pendingIndex === null) return false;
        const target = sortedEntries().find(entry => entry.index === pendingIndex);
        if (!target) return false;
        pendingIndex = null;
        scrollTo(target);
        return true;
    }

    return {
        navigate(direction) {
            if (direction !== 'previous' && direction !== 'next') return;
            const count = entryCount();
            if (!Number.isInteger(count) || count <= 0) return;
            const availableEntries = sortedEntries();
            const currentScrollTop = scrollTop();
            const current = currentEntry(availableEntries, currentScrollTop);
            const baseIndex = pendingIndex ?? current?.index ?? 0;
            let targetIndex;
            let returnsToCurrentHeader = false;
            if (direction === 'next') {
                targetIndex = Math.min(count - 1, baseIndex + 1);
            } else if (
                pendingIndex === null &&
                current &&
                currentScrollTop > current.top + topTolerance
            ) {
                targetIndex = current.index;
                returnsToCurrentHeader = true;
            } else {
                targetIndex = Math.max(0, baseIndex - 1);
            }
            if (pendingIndex === null && targetIndex === baseIndex && !returnsToCurrentHeader) return;
            pendingIndex = targetIndex;
            fulfillPending();
        },
        entryRendered() {
            fulfillPending();
        },
        userScrolled() {
            pendingIndex = null;
        },
        reset() {
            pendingIndex = null;
        },
    };
};

window.installPopupTermNavigationInput = function(navigator, target = document) {
    const cancelPending = () => navigator.userScrolled();
    target.addEventListener('pointerdown', cancelPending, { passive: true });
    target.addEventListener('touchstart', cancelPending, { passive: true });
    target.addEventListener('wheel', cancelPending, { passive: true });
};

const popupTermNavigator = window.createPopupTermNavigator({
    entryCount: () => window.entryCount || 0,
    entries: () => {
        return [...document.querySelectorAll('.entry[data-entry-index]')].map(entry => ({
            index: Number(entry.dataset.entryIndex),
            top: popupGeometry.elementDocumentTop(entry),
            element: entry,
        }));
    },
    scrollTop: popupGeometry.scrollTop,
    scrollTo: entry => popupGeometry.scrollElementToTop(entry.element),
});
window.installPopupTermNavigationInput(popupTermNavigator);

window.navigatePopupTerm = direction => popupTermNavigator.navigate(direction);

function getPopupSelectionText() {
    return window.hoshiSelection?.selection?.text || window.getSelection()?.toString() || '';
}

function el(tag, props = {}, children = []) {
    const element = document.createElement(tag);
    for (const [key, value] of Object.entries(props)) {
        if (key in element) {
            element[key] = value;
        } else {
            element.setAttribute(key, value);
        }
    }

    if (children.length) {
        element.append(...children);
    }

    return element;
}

function wrapKanji(text) {
    const nodes = [];
    for (const character of text || '') {
        if (KANJI_PATTERN.test(character)) {
            nodes.push(el('span', { className: 'kanji-char', textContent: character }));
        } else {
            nodes.push(document.createTextNode(character));
        }
    }
    return nodes;
}

function toHiragana(text) {
    return text.replace(/[\u30A1-\u30F6]/g, ch => String.fromCharCode(ch.charCodeAt(0) - 0x60));
}

function toKebabCase(str) {
    return str.replace(/([A-Z])/g, (_, c, i) => (i ? '-' : '') + c.toLowerCase());
}

// https://github.com/yomidevs/yomitan/blob/c0abb9e98a15aeb6b6f8f6e2d91fe5e54240b54a/ext/js/language/ja/japanese.js#L332
function isStringPartiallyJapanese(str) {
    if (!str) {
        return false;
    }
    const isCodePointJapanese = window.hoshiLanguageUtilities?.ja?.isCodePointJapanese;
    for (const c of str) {
        if (isCodePointJapanese?.(c.codePointAt(0))) {
            return true;
        }
    }
    return false;
}

// https://github.com/yomidevs/yomitan/blob/c0abb9e98a15aeb6b6f8f6e2d91fe5e54240b54a/ext/js/language/zh/chinese.js#L54
function isStringPartiallyChinese(text) {
    if (!text) {
        return false;
    }
    return KANJI_PATTERN.test(text) || /[\u3100-\u312F\u31A0-\u31BF]/.test(text);
}

// https://github.com/yomidevs/yomitan/blob/c0abb9e98a15aeb6b6f8f6e2d91fe5e54240b54a/ext/js/language/text-utilities.js#L28
function getLanguageFromText(text, language) {
    const partiallyJapanese = isStringPartiallyJapanese(text);
    const partiallyChinese = isStringPartiallyChinese(text);
    if (!['zh', 'yue'].includes(language ?? '')) {
        if (partiallyJapanese) {
            return 'ja';
        }
        if (partiallyChinese) {
            return 'zh';
        }
    }
    return language ?? null;
}

function openExternalLink(url) {
    webkit.messageHandlers.openLink.postMessage(url);
}

function showDescription(element) {
    const description = element.getAttribute('data-description');
    if (!description) {
        return;
    }
    const overlay = document.querySelector('.overlay');
    document.querySelector('.overlay-content').textContent = description;
    overlay.style.display = 'block';
}

function closeOverlay() {
    document.querySelector('.overlay').style.display = 'none';
}

// https://github.com/yomidevs/yomitan/blob/c24d4c9b39ceec1b5fd133df774c41972e9ebbdc/ext/js/language/ja/japanese.js#L171
function createFuriganaSegment(text, reading) {
    return {text, reading};
}

// https://github.com/yomidevs/yomitan/blob/c24d4c9b39ceec1b5fd133df774c41972e9ebbdc/ext/js/language/ja/japanese.js#L242
function getFuriganaKanaSegments(text, reading) {
    const textLength = text.length;
    const newSegments = [];
    let start = 0;
    let state = (reading[0] === text[0]);
    for (let i = 1; i < textLength; ++i) {
        const newState = (reading[i] === text[i]);
        if (state === newState) { continue; }
        newSegments.push(createFuriganaSegment(text.substring(start, i), state ? '' : reading.substring(start, i)));
        state = newState;
        start = i;
    }
    newSegments.push(createFuriganaSegment(text.substring(start, textLength), state ? '' : reading.substring(start, textLength)));
    return newSegments;
}

// https://github.com/yomidevs/yomitan/blob/c24d4c9b39ceec1b5fd133df774c41972e9ebbdc/ext/js/language/ja/japanese.js#L182
function segmentizeFurigana(reading, readingNormalized, groups, groupsStart) {
    const groupCount = groups.length - groupsStart;
    if (groupCount <= 0) {
        return reading.length === 0 ? [] : null;
    }

    const group = groups[groupsStart];
    const {isKana, text} = group;
    const textLength = text.length;
    if (isKana) {
        const {textNormalized} = group;
        if (textNormalized !== null && readingNormalized.startsWith(textNormalized)) {
            const segments = segmentizeFurigana(
                                                reading.substring(textLength),
                                                readingNormalized.substring(textLength),
                                                groups,
                                                groupsStart + 1,
                                                );
            if (segments !== null) {
                if (reading.startsWith(text)) {
                    segments.unshift(createFuriganaSegment(text, ''));
                } else {
                    segments.unshift(...getFuriganaKanaSegments(text, reading));
                }
                return segments;
            }
        }
        return null;
    } else {
        let result = null;
        for (let i = reading.length; i >= textLength; --i) {
            const segments = segmentizeFurigana(
                                                reading.substring(i),
                                                readingNormalized.substring(i),
                                                groups,
                                                groupsStart + 1,
                                                );
            if (segments !== null) {
                if (result !== null) {
                    // More than one way to segmentize the tail; mark as ambiguous
                    return null;
                }
                const segmentReading = reading.substring(0, i);
                segments.unshift(createFuriganaSegment(text, segmentReading));
                result = segments;
            }
            // There is only one way to segmentize the last non-kana group
            if (groupCount === 1) {
                break;
            }
        }
        return result;
    }
}

function segmentFurigana(expression, reading) {
    if (!reading || reading === expression) {
        return [[expression, '']];
    }

    const groups = [];
    const segmentMatches = expression.match(KANJI_SEGMENT_PATTERN) || [];
    for (const text of segmentMatches) {
        const isKana = !KANJI_PATTERN.test(text[0]);
        const textNormalized = isKana ? toHiragana(text) : null;
        groups.push({isKana, text, textNormalized});
    }

    const readingNormalized = toHiragana(reading);
    const segments = segmentizeFurigana(reading, readingNormalized, groups, 0);

    if (segments !== null) {
        return segments.map(seg => [seg.text, seg.reading]);
    }

    return [[expression, reading]];
}

function buildFuriganaEl(parent, expression, reading) {
    const segments = segmentFurigana(expression, reading);
    for (const [text, furigana] of segments) {
        if (furigana) {
            const ruby = el('ruby', {}, wrapKanji(text));
            ruby.appendChild(el('rt', { textContent: furigana }));
            parent.appendChild(ruby);
        } else {
            parent.append(...wrapKanji(text));
        }
    }
    return segments.length === 1 && segments[0][1];
}

function constructFuriganaPlain(expression, reading) {
    let result = '';
    for (const [text, furigana] of segmentFurigana(expression, reading)) {
        if (furigana) {
            result += `${text}[${furigana}]`;
        } else {
            // space to separate from next furigana segment, not sure if this is the correct solution
            result += `${text} `;
        }
    }
    return result;
}

// !AI SLOP! function to preprocess css
function constructDictCss(css, dictName) {
    if (!css) {
        return '';
    }
    const prefix = `.yomitan-glossary [data-dictionary="${dictName}"]`;
    const parts = [];
    let i = 0;
    while (i < css.length) {
        while (i < css.length && /\s/.test(css[i])) {
            parts.push(css[i++]);
        }
        if (css.slice(i, i + 2) === '/*') {
            const end = css.indexOf('*/', i + 2);
            if (end === -1) break;
            parts.push(css.slice(i, end + 2));
            i = end + 2;
            continue;
        }
        const bracePos = css.indexOf('{', i);
        if (bracePos === -1) break;
        const selectorPart = css.slice(i, bracePos);
        const selectors = selectorPart.split(',').map(s => {
            const trimmed = s.trim();
            if (!trimmed) return '';
            if (trimmed.startsWith('&')) {
                return s;
            }
            return `${prefix} ${trimmed}`;
        });
        parts.push(selectors.join(', '), ' {');
        i = bracePos + 1;
        let depth = 1;
        let blockStart = i;
        while (i < css.length && depth > 0) {
            if (css[i] === '{') depth++;
            else if (css[i] === '}') depth--;
            i++;
        }
        const blockContent = css.slice(blockStart, i - 1);
        if (blockContent.includes('{')) {
            let pos = 0;
            let properties = '';
            let nestedRules = '';
            while (pos < blockContent.length) {
                while (pos < blockContent.length && /\s/.test(blockContent[pos])) {
                    pos++;
                }
                if (pos >= blockContent.length) break;
                let nextSemi = blockContent.indexOf(';', pos);
                let nextBrace = blockContent.indexOf('{', pos);
                if (nextBrace !== -1 && (nextSemi === -1 || nextBrace < nextSemi)) {
                    let nestedDepth = 1;
                    let nestedEnd = nextBrace + 1;
                    while (nestedEnd < blockContent.length && nestedDepth > 0) {
                        if (blockContent[nestedEnd] === '{') nestedDepth++;
                        else if (blockContent[nestedEnd] === '}') nestedDepth--;
                        nestedEnd++;
                    }
                    nestedRules += blockContent.slice(pos, nestedEnd);
                    pos = nestedEnd;
                } else if (nextSemi !== -1) {
                    properties += blockContent.slice(pos, nextSemi + 1);
                    pos = nextSemi + 1;
                } else {
                    properties += blockContent.slice(pos);
                    break;
                }
            }
            parts.push(properties);
            if (nestedRules) {
                parts.push(constructDictCss(nestedRules, dictName));
            }
        } else {
            parts.push(blockContent);
        }
        parts.push('}');
    }
    return parts.join('');
}

function applyTableStyles(html) {
    const tableStyle = 'table-layout:auto;border-collapse:collapse;';
    const cellStyle = 'border-style:solid;padding:0.25em;vertical-align:top;border-width:1px;border-color:currentColor;';
    const thStyle = 'font-weight:bold;' + cellStyle;

    return html
    .replace(/<table(?=[>\s])/g, `<table style="${tableStyle}"`)
    .replace(/<th(?=[>\s])/g, `<th style="${thStyle}"`)
    .replace(/<td(?=[>\s])/g, `<td style="${cellStyle}"`);
}

function escapeHtml(value) {
    return String(value)
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;')
    .replace(/'/g, '&#39;');
}

function applyImageStyles(node, imageContainer, aspectRatioSizer, imageBackground, image, filename, appearance, useEmUnits) {
    // .gloss-image-link
    node.style.cssText += 'display:inline-block;position:relative;line-height:1;max-width:100%;';
    // .gloss-image-container
    imageContainer.style.cssText += `display:inline-block;white-space:nowrap;max-width:100%;max-height:100vh;position:relative;vertical-align:top;line-height:0;overflow:hidden;font-size:${useEmUnits ? '1em' : '1px'};`;
    // .gloss-image-link[data-has-aspect-ratio=true] .gloss-image-sizer
    aspectRatioSizer.style.cssText += 'display:inline-block;width:0;vertical-align:top;font-size:0;';
    // .gloss-image-link[data-has-aspect-ratio=true] .gloss-image
    image.style.cssText += 'display:inline-block;vertical-align:top;object-fit:contain;border:none;outline:none;position:absolute;left:0;top:0;width:100%;height:100%;';
    // .gloss-image-background, set image url directly
    if (appearance === 'monochrome') {
        imageBackground.style.cssText += `--image:url("${filename}");position:absolute;left:0;top:0;width:100%;height:100%;-webkit-mask-repeat:no-repeat;-webkit-mask-position:center center;-webkit-mask-mode:alpha;-webkit-mask-size:contain;-webkit-mask-image:var(--image);mask-repeat:no-repeat;mask-position:center center;mask-mode:alpha;mask-size:contain;mask-image:var(--image);background-color:currentColor;`;
        image.style.opacity = '0';
    }
}

function getMediaFilename(dictionary, path) {
    const key = `${dictionary}\n${path}`;
    if (!currentDictionaryMedia.has(key)) {
        const extension = path.split('.').pop();
        currentDictionaryMedia.set(key, {
            dictionary,
            path,
            filename: `hoshi_dict_${currentDictionaryMedia.size}.${extension}`,
        });
    }
    return currentDictionaryMedia.get(key).filename;
}

function getDictionaryMediaUrl(dictionary, path) {
    if (window.dictionaryMediaRequestEndpoint) {
        return `${window.dictionaryMediaRequestEndpoint}?dictionary=${encodeURIComponent(dictionary)}&path=${encodeURIComponent(path)}`;
    }
    return `image://?dictionary=${encodeURIComponent(dictionary)}&path=${encodeURIComponent(path)}`;
}

function observeDictionaryMedia(target, load) {
    if (typeof IntersectionObserver !== 'function') {
        load();
        return;
    }
    if (!dictionaryMediaObserver) {
        dictionaryMediaObserver = new IntersectionObserver((entries, observer) => {
            for (const entry of entries) {
                if (!entry.isIntersecting) continue;
                observer.unobserve(entry.target);
                const fn = entry.target._loadDictionaryMedia;
                delete entry.target._loadDictionaryMedia;
                fn?.();
            }
        }, { root: null, rootMargin: '200px' });
    }
    target._loadDictionaryMedia = load;
    dictionaryMediaObserver.observe(target);
}

function resetDictionaryMediaObserver() {
    dictionaryMediaObserver?.disconnect();
    dictionaryMediaObserver = null;
}

function observePendingDictionaryMedia(root) {
    root.querySelectorAll?.('.gloss-image-container').forEach(target => {
        const fn = target._loadDictionaryMedia;
        if (fn) {
            observeDictionaryMedia(target, fn);
        }
    });
}

function applyDictionaryImageContainerFixes(imageContainer) {
    if (window.disablePopupImageViewportMaxHeight) {
        imageContainer.style.maxHeight = 'none';
    }
}

function setStructuredContentElementStyle(element, style) {
    for (const [property, value] of Object.entries(style)) {
        if ((property === 'marginTop' || property === 'marginLeft' || property === 'marginRight' || property === 'marginBottom') && typeof value === 'number') {
            element.style[property] = `${value}em`;
        } else {
            element.style[property] = value;
        }
    }
}

const COMPACT_GLOSSARIES_ANKI = `.yomitan-glossary ul[data-sc-content="glossary"] > li:not(:first-child)::before, .yomitan-glossary .glossary-list > li:not(:first-child)::before { white-space: pre-wrap; content: " | "; display: inline; color: rgb(119, 119, 119); }
.yomitan-glossary ul[data-sc-content="glossary"] > li, .yomitan-glossary .glossary-list > li { display: inline; }
.yomitan-glossary ul[data-sc-content="glossary"], .yomitan-glossary .glossary-list { display: inline; list-style: none; padding-left: 0px; }`;

// the following two should roughly match the glossary format of yomitan and keep compatibility with notetypes like lapis
// 23.01.2026: this still has some differences
// 24.01.2026: should be a bit closer now
// 25.01.2026: fixed jmdict
// 19.02.2026: fixed jmdict legacy
// 24.03.2026: fixed compact glossaries for jmdict legacy
function constructSingleGlossaryHtml(entryIndex) {
    if (!window.lookupEntries || entryIndex >= window.lookupEntries.length) {
        return {};
    }

    const entry = window.lookupEntries[entryIndex];
    const glossaries = {};

    let lastDict = null;
    let currentGlossary = '';
    let prevTags = null;
    const flush = () => {
        if (!lastDict) {
            return;
        }

        let html = `<div style="text-align: left;" class="yomitan-glossary"><ol>${currentGlossary}</ol>`;
        const css = window.dictionaryStyles?.[lastDict] ?? '';
        if (css) {
            const scopedCss = constructDictCss(css, lastDict);
            const formatted = scopedCss
            .replace(/\s+/g, ' ')
            .replace(/\s*\{\s*/g, ' { ')
            .replace(/\s*\}\s*/g, ' }\n')
            .replace(/;\s*/g, '; ')
            .trim();
            html += `<style>${formatted}</style>`;
        }
        if (window.compactGlossariesAnki) {
            html += `<style>${COMPACT_GLOSSARIES_ANKI}</style>`;
        }
        html += `</div>`;

        glossaries[lastDict] = html;
        currentGlossary = '';
    };

    entry.glossaries.forEach(g => {
        const dictName = g.dictionary;
        const dictChanged = lastDict !== dictName;
        if (dictChanged) {
            flush();
            lastDict = dictName;
            prevTags = null;
        }

        const tempDiv = document.createElement('div');
        try {
            renderStructuredContent(tempDiv, JSON.parse(g.content), null, dictName, true);
        } catch {
            renderStructuredContent(tempDiv, g.content, null, dictName, true);
        }

        const parsedTags = parseTags(g.definitionTags).filter(tag => !NUMERIC_TAG.test(tag));
        const posTags = [...new Set(parsedTags.filter(isPartOfSpeech))].sort();
        const currentTags = JSON.stringify(posTags);
        const filteredTags = parsedTags.filter(tag => !isPartOfSpeech(tag) || !(prevTags !== null && prevTags === currentTags));
        const tags = filteredTags.length > 0 ? filteredTags.join(', ') : '';
        const content = applyTableStyles(tempDiv.innerHTML);
        let label = '';
        if (dictChanged) {
            label = tags ? `(${tags}, ${dictName})` : `(${dictName})`;
        } else {
            label = tags ? `(${tags})` : '';
        }
        currentGlossary += `<li data-dictionary="${dictName}"><i>${label}</i> <span>${content}</span></li>`
        prevTags = currentTags;
    });

    flush();
    return glossaries;
}

function constructGlossaryHtml(entryIndex) {
    if (!window.lookupEntries || entryIndex >= window.lookupEntries.length) {
        return null;
    }

    const entry = window.lookupEntries[entryIndex];
    let glossaryItems = '';
    const styles = {};
    let lastDict = '';
    let prevTags = null;
    let index = 0;

    entry.glossaries.forEach(g => {
        const dictName = g.dictionary;

        const tempDiv = document.createElement('div');
        try {
            renderStructuredContent(tempDiv, JSON.parse(g.content), null, dictName, true);
        } catch {
            renderStructuredContent(tempDiv, g.content, null, dictName, true);
        }

        index++;
        let label = '';
        const parsedTags = parseTags(g.definitionTags).filter(tag => !NUMERIC_TAG.test(tag));
        const posTags = [...new Set(parsedTags.filter(isPartOfSpeech))].sort();
        const currentTags = JSON.stringify(posTags);
        const filteredTags = parsedTags.filter(tag => !isPartOfSpeech(tag) || !(prevTags !== null && prevTags === currentTags));
        const tags = filteredTags.length > 0 ? filteredTags.join(', ') : '';
        if (dictName !== lastDict) {
            index = 1;
            lastDict = dictName;
            label = tags ? `(${index}, ${tags}, ${dictName})` : `(${index}, ${dictName})`
        }
        else {
            label = tags ? `(${index}, ${tags})` : `(${index})`
        }

        glossaryItems += `<li data-dictionary="${dictName}"><i>${label}</i> <span>${applyTableStyles(tempDiv.innerHTML)}</span></li>`;
        prevTags = currentTags;

        const css = window.dictionaryStyles?.[dictName];
        if (css && !styles[dictName]) {
            styles[dictName] = css;
        }
    });

    let result = '<div style="text-align: left;" class="yomitan-glossary"><ol>';
    result += glossaryItems;
    result += '</ol>';

    for (const [dictName, css] of Object.entries(styles)) {
        const scopedCss = constructDictCss(css, dictName);
        const formatted = scopedCss
        .replace(/\s+/g, ' ')
        .replace(/\s*\{\s*/g, ' { ')
        .replace(/\s*\}\s*/g, ' }\n')
        .replace(/;\s*/g, '; ')
        .trim();
        result += `<style>${formatted}</style>`;
    }
    if (window.compactGlossariesAnki) {
        result += `<style>${COMPACT_GLOSSARIES_ANKI}</style>`;
    }
    result += '</div>';
    return result;
}

function constructFrequencyHtml(frequencies) {
    if (!frequencies || frequencies.length === 0) {
        return '';
    }

    let result = '<ul style="text-align: left;">';
    frequencies.forEach(freqGroup => {
        if (!freqGroup?.frequencies?.length) {
            return;
        }
        const dictName = freqGroup.dictionary || '';
        freqGroup.frequencies.forEach(freq => {
            result += `<li>${dictName}: ${freq.displayValue || freq.value}</li>`;
        });
    });
    result += '</ul>';
    return result;
}

function constructPitchPositionHtml(pitches) {
    if (!pitches?.length) {
        return '';
    }

    let result = '<ol>';
    pitches.forEach(pitchGroup => {
        (pitchGroup.pitches || []).forEach(accent => {
            const downsteps = typeof accent.position === 'string'
                ? getDownstepPositions(accent.position)
                : accent.position;
            result += `<li><span style="display:inline;"><span>[</span><span>${downsteps}</span><span>]</span></span></li>`;
        });
    });
    result += '</ol>';
    return result;
}

function constructPitchCategories(pitches, reading, rules) {
    if (!pitches?.length) {
        return '';
    }

    const verbOrAdj = isVerbOrAdjective(rules);
    const categories = [];
    pitches.forEach(pitchGroup => {
        (pitchGroup.pitches || []).forEach(accent => {
            const category = getPitchCategory(reading, accent.position, verbOrAdj);
            if (category && !categories.includes(category)) {
                categories.push(category);
            }
        });
    });
    return categories.join(',');
}

function constructPhoneticTranscriptionsHtml(pitches) {
    if (!pitches?.length) {
        return '';
    }

    const items = [];
    pitches.forEach(pitchGroup => {
        pitchGroup.transcriptions?.forEach(transcription => {
            if (!transcription) return;
            items.push(`<li class="pronunciation" data-pronunciation-type="phonetic-transcription">${escapeHtml(transcription)}</li>`);
        });
    });

    if (!items.length) {
        return '';
    }
    return `<ul>${items.join('')}</ul>`;
}

function constructPitchAccentGraphsHtml(pitches, reading, firstOnly = false) {
    const positions = [];
    const seen = new Set();
    const morae = getKanaMorae(reading || '');
    (pitches || []).forEach((group) => (group?.pitches || []).forEach((accent) => {
        const position = accent?.position;
        if (typeof position !== 'string' && (!Number.isInteger(position) || position < 0)) return;
        if (typeof position === 'string' && !/^[HL]+$/.test(position)) return;
        const pattern = pitchPattern(position, morae.length);
        if (window.deduplicatePitchAccents && seen.has(pattern)) return;
        seen.add(pattern);
        positions.push(position);
    }));
    const selected = firstOnly ? positions.slice(0, 1) : positions;
    if (!morae.length || !selected.length) return '';
    const graphs = selected.map((downstep) => createPronunciationGraphHtml(morae, downstep));
    if (graphs.length === 1) return graphs[0];
    return `<ol>${graphs.map((graph) => `<li>${graph}</li>`).join('')}</ol>`;
}

function createPronunciationGraphHtml(morae, downstep) {
    const points = [];
    const dots = [];
    morae.forEach((_, index) => {
        const high = isMoraPitchHigh(index, downstep);
        const highNext = isMoraPitchHigh(index + 1, downstep);
        const x = index * 50 + 25;
        const y = high ? 25 : 75;
        points.push(`${x} ${y}`);
        dots.push(high && !highNext
            ? `<circle style="fill:none;stroke-width:5;stroke:currentColor;" cx="${x}" cy="${y}" r="15"/><circle style="fill:currentColor;" cx="${x}" cy="${y}" r="5"/>`
            : `<circle style="stroke-width:5;fill:currentColor;stroke:currentColor;" cx="${x}" cy="${y}" r="15"/>`);
    });
    const tailX = morae.length * 50 + 25;
    const tailY = isMoraPitchHigh(morae.length, downstep) ? 25 : 75;
    const last = points[points.length - 1];
    return `<svg xmlns="http://www.w3.org/2000/svg" style="display:inline-block;vertical-align:middle;height:1.5em;" focusable="false" viewBox="0 0 ${50 * (morae.length + 1)} 100" data-downstep="${downstep}"><path style="fill:none;stroke-width:5;stroke:currentColor;" d="M${points.join(' L')}"/><path style="fill:none;stroke-width:5;stroke:currentColor;stroke-dasharray:5 5;" d="M${last} L${tailX} ${tailY}"/>${dots.join('')}<path style="fill:none;stroke-width:5;stroke:currentColor;" d="M0 13 L15 -13 L-15 -13 Z" transform="translate(${tailX},${tailY})"/></svg>`;
}

// https://github.com/yomidevs/yomitan/blob/d810b2f0842536d24ab82b6cd75d00841710e57b/ext/js/display/structured-content-generator.js#L64
function createDefinitionImage(data, dictionary, exporting = false) {
    const {
        path,
        width = 100,
        height = 100,
        preferredWidth,
        preferredHeight,
        title,
        pixelated,
        imageRendering,
        appearance,
        background,
        collapsed,
        collapsible,
        verticalAlign,
        border,
        borderRadius,
        sizeUnits,
        data: nodeData,
    } = data;

    const hasPreferredWidth = (typeof preferredWidth === 'number');
    const hasPreferredHeight = (typeof preferredHeight === 'number');
    const hasDimensions = (hasPreferredWidth || hasPreferredHeight || typeof data.width === 'number' || typeof data.height === 'number');
    const invAspectRatio = (
                            hasPreferredWidth && hasPreferredHeight ?
                            preferredHeight / preferredWidth :
                            height / width
                            );
    const usedWidth = (
                       hasPreferredWidth ?
                       preferredWidth :
                       (hasPreferredHeight ? preferredHeight / invAspectRatio : width)
                       );

    const node = document.createElement(exporting ? 'span' : 'a');
    node.classList.add('gloss-image-link');
    if (!exporting) {
        node.target = '_blank';
        node.rel = 'noreferrer noopener';
    }

    const imageContainer = document.createElement('span');
    imageContainer.classList.add('gloss-image-container');
    node.appendChild(imageContainer);

    const aspectRatioSizer = document.createElement('span');
    aspectRatioSizer.classList.add('gloss-image-sizer');
    imageContainer.appendChild(aspectRatioSizer);

    const imageBackground = document.createElement('span');
    imageBackground.classList.add('gloss-image-background');
    imageContainer.appendChild(imageBackground);

    const overlay = document.createElement('span');
    overlay.classList.add('gloss-image-container-overlay');
    imageContainer.appendChild(overlay);

    node.dataset.path = path;
    node.dataset.dictionary = dictionary;
    node.dataset.hasAspectRatio = 'true';
    node.dataset.imageRendering = typeof imageRendering === 'string' ? imageRendering : (pixelated ? 'pixelated' : 'auto');
    node.dataset.appearance = typeof appearance === 'string' ? appearance : 'auto';
    node.dataset.background = typeof background === 'boolean' ? `${background}` : 'true';
    node.dataset.collapsed = typeof collapsed === 'boolean' ? `${collapsed}` : 'false';
    node.dataset.collapsible = typeof collapsible === 'boolean' ? `${collapsible}` : 'true';
    if (typeof verticalAlign === 'string') {
        node.dataset.verticalAlign = verticalAlign;
    }
    if (typeof sizeUnits === 'string') {
        node.dataset.sizeUnits = sizeUnits;
    }

    aspectRatioSizer.style.paddingTop = `${invAspectRatio * 100}%`;

    if (typeof border === 'string') { imageContainer.style.border = border; }
    if (typeof borderRadius === 'string') { imageContainer.style.borderRadius = borderRadius; }
    imageContainer.style.width = `${usedWidth}em`;
    applyDictionaryImageContainerFixes(imageContainer);
    if (typeof title === 'string') {
        imageContainer.title = title;
    }

    if (!exporting) {
        const imageUrl = getDictionaryMediaUrl(dictionary, path);
        if (shouldRenderDefinitionImageToCanvas(path, appearance, usedWidth, invAspectRatio)) {
            const canvas = createDefinitionImageCanvas(imageUrl, nodeData?.alt || title || '', (canvas, sourceImage) => {
                renderDefinitionImageToCanvas(canvas, sourceImage, usedWidth, invAspectRatio, appearance);
            });
            imageContainer.appendChild(canvas);
            observeDictionaryMedia(imageContainer, () => canvas.loadDictionaryMedia?.());
        } else {
            const img = document.createElement('img');
            img.classList.add('gloss-image');
            img.alt = nodeData?.alt || title || '';
            if (!hasDimensions) {
                img.addEventListener('load', () => {
                    const imageWidth = Math.min(img.naturalWidth, window.innerWidth - 20);
                    imageContainer.style.width = `${imageWidth}px`;
                    aspectRatioSizer.style.paddingTop = `${(img.naturalHeight / img.naturalWidth) * 100}%`;
                    applyDictionaryImageContainerFixes(imageContainer);
                }, {once: true});
            } else if (!hasPreferredWidth && !hasPreferredHeight && sizeUnits === 'em') {
                img.addEventListener('load', () => {
                    const aspectRatio = img.naturalHeight / img.naturalWidth;
                    const widthEm = typeof data.width === 'number' ? data.width : data.height / aspectRatio;
                    imageContainer.style.width = `${widthEm}em`;
                    aspectRatioSizer.style.paddingTop = `${aspectRatio * 100}%`;
                    applyDictionaryImageContainerFixes(imageContainer);
                }, {once: true});
            }
            imageContainer.appendChild(img);
            observeDictionaryMedia(imageContainer, () => {
                img.src = imageUrl;
            });
        }
    } else {
        const alt = nodeData?.alt || title || '';
        const filename = (window.useAnkiConnect || window.embedMedia) ? getMediaFilename(dictionary, path) : null;
        const image = document.createElement(filename ? 'img' : 'span');
        image.classList.add('gloss-image');
        if (filename) {
            image.alt = alt;
            image.src = filename;
            if (sizeUnits === 'em') {
                const emSize = 14;
                const scaleFactor = 2 * window.devicePixelRatio;
                image.width = usedWidth * emSize * scaleFactor;
            } else {
                image.width = usedWidth;
            }
            image.height = image.width * invAspectRatio;
            applyImageStyles(node, imageContainer, aspectRatioSizer, imageBackground, image, filename, appearance, sizeUnits === 'em');
        } else {
            image.textContent = alt;
        }
        imageContainer.appendChild(image);
    }
    return node;
}

// ai slop
function shouldRenderDefinitionImageToCanvas(path, appearance, usedWidth, invAspectRatio) {
    return /\.svg$/i.test(path) && appearance === 'monochrome' && usedWidth <= 4 && (usedWidth * invAspectRatio) <= 4;
}

function createDefinitionImageCanvas(imageUrl, alt, onLoad) {
    const canvas = document.createElement('canvas');
    canvas.classList.add('gloss-image');
    canvas.setAttribute('role', 'img');
    canvas.setAttribute('aria-label', alt);

    const sourceImage = new Image();
    sourceImage.addEventListener('load', () => {
        onLoad(canvas, sourceImage);
    }, {once: true});
    canvas.loadDictionaryMedia = () => {
        sourceImage.src = imageUrl;
    };

    return canvas;
}

function renderDefinitionImageToCanvas(canvas, image, usedWidth, invAspectRatio, appearance) {
    const emSize = Number.parseFloat(getComputedStyle(document.documentElement).fontSize);
    const scaleFactor = Math.ceil(window.devicePixelRatio * 2);
    const pixelWidth = Math.round(usedWidth * emSize * scaleFactor);
    const pixelHeight = Math.round(usedWidth * emSize * invAspectRatio * scaleFactor);
    const maxCanvasSize = 128;
    const scale = Math.min(
                           1,
                           maxCanvasSize / Math.max(pixelWidth, pixelHeight),
                           Math.sqrt((maxCanvasSize * maxCanvasSize) / (pixelWidth * pixelHeight))
                           );

    canvas.style.width = '100%';
    canvas.style.height = '100%';
    canvas.width = Math.round(pixelWidth * scale);
    canvas.height = Math.round(pixelHeight * scale);

    const context = canvas.getContext('2d');
    if (!context) {
        return;
    }

    context.clearRect(0, 0, canvas.width, canvas.height);
    context.drawImage(image, 0, 0, canvas.width, canvas.height);

    if (appearance === 'monochrome') {
        context.globalCompositeOperation = 'source-in';
        context.fillStyle = window.matchMedia?.('(prefers-color-scheme: dark)')?.matches ? '#ffffff' : '#000000';
        context.fillRect(0, 0, canvas.width, canvas.height);
        context.globalCompositeOperation = 'source-over';
    }
}

// https://github.com/yomidevs/yomitan/blob/c0abb9e98a15aeb6b6f8f6e2d91fe5e54240b54a/ext/js/data/anki-note-data-creator.js#L177-L221
function getFrequencyHarmonicRank(frequencies) {
    if (!frequencies || frequencies.length === 0) {
        return DEFAULT_HARMONIC_RANK;
    }

    const values = [];
    const seenDictionaries = new Set();
    frequencies.forEach(freqGroup => {
        const dictionary = freqGroup?.dictionary;
        if (dictionary && seenDictionaries.has(dictionary)) {
            return;
        }
        if (dictionary) {
            seenDictionaries.add(dictionary);
        }

        const firstFreq = freqGroup?.frequencies?.[0];
        if (!firstFreq) {
            return;
        }

        const displayValue = firstFreq.displayValue;
        if (displayValue != null) {
            const match = String(displayValue).match(/^\d+/);
            if (match) {
                const parsed = Number.parseInt(match[0], 10);
                if (parsed > 0) {
                    values.push(parsed);
                    return;
                }
            }
        }

        const val = firstFreq.value;
        if (val && val > 0) {
            values.push(val);
        }
    });

    if (values.length === 0) {
        return DEFAULT_HARMONIC_RANK;
    }

    const sumOfReciprocals = values.reduce((sum, val) => sum + (1 / val), 0);
    return String(Math.floor(values.length / sumOfReciprocals));
}

async function mineEntry(expression, reading, frequencies, pitches, rules, matched, entryIndex, popupSelectionText, formatId = null) {
    const idx = entryIndex || 0;
    const furiganaPlain = constructFuriganaPlain(expression, reading);
    currentDictionaryMedia = new Map();
    const glossary = constructGlossaryHtml(idx);
    const freqHarmonicRank = getFrequencyHarmonicRank(frequencies);
    const frequenciesHtml = constructFrequencyHtml(frequencies);
    const singleGlossaries = constructSingleGlossaryHtml(idx);
    const dictionaryMedia = currentDictionaryMedia;
    currentDictionaryMedia = null;
    const glossaryFirst = Object.values(singleGlossaries)[0] || '';
    const pitchPositions = constructPitchPositionHtml(pitches);
    const pitchCategories = constructPitchCategories(pitches, reading, rules);
    const phoneticTranscriptions = constructPhoneticTranscriptionsHtml(pitches);
    const pitchAccentGraphs = constructPitchAccentGraphsHtml(pitches, reading || expression);

    if (!audioUrls[idx] && window.audioSources?.length && window.needsAudio) {
        audioUrls[idx] = await fetchAudioUrl(expression, reading || expression);
    }

    const audio = audioUrls[idx] || '';

    const payload = {
        expression,
        reading,
        matched,
        furiganaPlain,
        frequenciesHtml,
        freqHarmonicRank,
        glossary,
        glossaryFirst,
        singleGlossaries: JSON.stringify(singleGlossaries),
        pitchPositions,
        pitchCategories,
        pitchAccentGraphs,
        phoneticTranscriptions,
        popupSelectionText,
        audio,
        selectedDictionary: selectedDictionaries[idx]?.name || '',
        dictionaryMedia: JSON.stringify([...dictionaryMedia.values()])
    };
    return await webkit.messageHandlers.mineEntry.postMessage({ formatId, payload });
}

function renderStructuredContent(parent, node, language = null, dictName = null, exporting = false) {
    if (typeof node === 'string') {
        node.split(/\r?\n/).forEach((line, i) => {
            if (i > 0) {
                parent.appendChild(document.createElement('br'));
            }
            if (line) {
                if (!language && !parent.hasAttribute('lang')) {
                    const detected = getLanguageFromText(line, language);
                    if (detected) {
                        parent.setAttribute('lang', detected);
                    }
                }
                parent.appendChild(document.createTextNode(line));
            }
        });
        return;
    }

    if (Array.isArray(node)) {
        const isStringArray = node.every(item => typeof item === 'string');
        const insideSpan = parent.tagName === 'SPAN';
        if (isStringArray && node.length > 1 && !insideSpan) {
            const ul = document.createElement('ul');
            ul.classList.add('glossary-list');
            node.forEach(child => {
                const li = document.createElement('li');
                li.appendChild(document.createTextNode(child));
                ul.appendChild(li);
            });
            parent.appendChild(ul);
            return;
        }

        const items = node.map(item =>
                               item?.type === 'structured-content' ? item.content : item
                               );
        const isLinkArray = items.every(item => item?.tag === 'a');
        if (isLinkArray && node.length > 1) {
            const ul = document.createElement('ul');
            ul.classList.add('glossary-list');
            node.forEach(child => {
                const li = document.createElement('li');
                renderStructuredContent(li, child, language, dictName, exporting);
                ul.appendChild(li);
            });
            parent.appendChild(ul);
            return;
        }

        node.forEach(child => renderStructuredContent(parent, child, language, dictName, exporting));
        return;
    }

    if (!node || typeof node !== 'object') {
        return;
    }

    if (node.type === 'structured-content') {
        const container = document.createElement('span');
        container.classList.add('structured-content');
        parent.appendChild(container);
        renderStructuredContent(container, node.content, language, dictName, exporting);
        return;
    }

    if (node.tag === 'img') {
        parent.appendChild(createDefinitionImage(node, dictName, exporting));
        return;
    }

    const tagName = node.tag || 'span';
    const element = document.createElement(tagName);
    element.classList.add(`gloss-sc-${tagName}`);
    let nextLanguage = language;

    if (node.href) {
        element.setAttribute('href', node.href);
        const isExternal = /^https?:\/\//i.test(node.href);
        element.onclick = async (e) => {
            e.preventDefault();
            e.stopPropagation();
            if (isExternal) {
                openExternalLink(node.href);
            } else {
                const i = node.href.indexOf('?');
                const query = i < 0 ? null : new URLSearchParams(node.href.slice(i + 1)).get('query');
                const count = query ? await webkit.messageHandlers.lookupRedirect.postMessage(query) : 0;
                if (count > 0) {
                    redirect(count);
                }
            }
        };
    }

    if (node.title) {
        element.setAttribute('title', node.title);
    }

    if (node.lang) {
        element.setAttribute('lang', node.lang);
        nextLanguage = node.lang;
    }

    if (node.data) {
        // this is necessary to fix formatting in dicts like daijisen
        for (const [k, v] of Object.entries(node.data)) {
            const isCJK = /^[\u3000-\u9FFF\uF900-\uFAFF]/.test(k);
            element.setAttribute(`data-sc${isCJK ? '' : '-'}${toKebabCase(k)}`, v);
        }
    }

    if (node.style) {
        setStructuredContentElementStyle(element, node.style);
    }

    if (node.content) {
        renderStructuredContent(element, node.content, nextLanguage, dictName, exporting);
    }

    if (node.colSpan) {
        element.setAttribute('colspan', node.colSpan);
    }

    if (node.rowSpan) {
        element.setAttribute('rowspan', node.rowSpan);
    }

    if (tagName === 'table') {
        const container = document.createElement('div');
        container.classList.add('gloss-sc-table-container');
        container.appendChild(element);
        parent.appendChild(container);
        return;
    }

    parent.appendChild(element);
}

function isPartOfSpeech(tag) {
    return POS_TAGS.has(tag) || tag.startsWith('v5');
}

function parseTags(raw) {
    return (raw || '').split(' ').filter(Boolean);
}

function createGlossaryTags(tags, className = 'glossary-tags') {
    if (!tags?.length) {
        return null;
    }
    return el('div', { className }, tags.map(tag => el('span', { className: 'glossary-tag', textContent: tag })));
}

function createDeinflectionTag(tag) {
    return el('span', {
        className: 'deinflection-tag',
        textContent: tag.name,
        'data-description': tag.description,
        onclick() {
            showDescription(this);
        }
    });
}

function createFrequencyGroup(freqGroup) {
    const values = freqGroup.frequencies.map(f => f.displayValue || f.value).join(', ');
    return el('span', { className: 'frequency-group', 'data-details': freqGroup.dictionary }, [
        el('span', { className: 'frequency-dict-label', textContent: freqGroup.dictionary }),
        el('span', { className: 'frequency-values', textContent: values })
    ]);
}

function createHarmonicFrequencyTag(frequencies) {
    const rank = getFrequencyHarmonicRank(frequencies);
    return el('span', { className: 'frequency-group harmonic-frequency' }, [
        el('span', { className: 'frequency-dict-label', textContent: 'Average' }),
        el('span', { className: 'frequency-values', textContent: rank })
    ]);
}

// https://github.com/yomidevs/yomitan/blob/c24d4c9b39ceec1b5fd133df774c41972e9ebbdc/ext/js/language/ja/japanese.js#L350
function isMoraPitchHigh(moraIndex, pitchAccentValue) {
    if (typeof pitchAccentValue === 'string') {
        return pitchAccentValue[moraIndex] === 'H';
    }
    switch (pitchAccentValue) {
        case 0: return (moraIndex > 0);
        case 1: return (moraIndex < 1);
        default: return (moraIndex > 0 && moraIndex < pitchAccentValue);
    }
}

function getDownstepPositions(pitchString) {
    const downsteps = [];
    for (let index = 1; index < pitchString.length; index++) {
        if (pitchString[index - 1] === 'H' && pitchString[index] === 'L') {
            downsteps.push(index);
        }
    }
    if (!downsteps.length) {
        downsteps.push(pitchString.startsWith('L') ? 0 : -1);
    }
    return downsteps;
}

function pitchPattern(position, moraCount) {
    if (typeof position === 'string') return position;
    let pattern = '';
    for (let index = 0; index <= moraCount; index++) {
        pattern += isMoraPitchHigh(index, position) ? 'H' : 'L';
    }
    return pattern;
}

// https://github.com/yomidevs/yomitan/blob/c0c3702963c22e0f39fdd2f03deef6b15558a7f5/ext/js/language/ja/japanese.js#L138
const DIACRITIC_MAPPING = (() => {
    const kana = 'うゔ-かが-きぎ-くぐ-けげ-こご-さざ-しじ-すず-せぜ-そぞ-ただ-ちぢ-つづ-てで-とど-はばぱひびぴふぶぷへべぺほぼぽワヷ-ヰヸ-ウヴ-ヱヹ-ヲヺ-カガ-キギ-クグ-ケゲ-コゴ-サザ-シジ-スズ-セゼ-ソゾ-タダ-チヂ-ツヅ-テデ-トド-ハバパヒビピフブプヘベペホボポ';
    const mapping = new Map();
    for (let i = 0; i < kana.length; i += 3) {
        const character = kana[i];
        const dakuten = kana[i + 1];
        const handakuten = kana[i + 2];
        mapping.set(dakuten, { character, type: 'dakuten' });
        if (handakuten !== '-') {
            mapping.set(handakuten, { character, type: 'handakuten' });
        }
    }
    return mapping;
})();

// https://github.com/yomidevs/yomitan/blob/c0c3702963c22e0f39fdd2f03deef6b15558a7f5/ext/js/language/ja/japanese.js#L573
function getKanaDiacriticInfo(character) {
    const info = DIACRITIC_MAPPING.get(character);
    return typeof info !== 'undefined' ? { character: info.character, type: info.type } : null;
}

// https://github.com/yomidevs/yomitan/blob/c24d4c9b39ceec1b5fd133df774c41972e9ebbdc/ext/js/language/ja/japanese.js#L406
function getKanaMorae(text) {
    const morae = [];
    let i;
    for (const c of text) {
        if (SMALL_KANA_SET.has(c) && (i = morae.length) > 0) {
            morae[i - 1] += c;
        } else {
            morae.push(c);
        }
    }
    return morae;
}

// this might be unreliable
function isVerbOrAdjective(rules) {
    return rules?.some(tag => tag.startsWith('v') || tag.startsWith('adj-i')) ?? false;
}

// https://github.com/yomidevs/yomitan/blob/c24d4c9b39ceec1b5fd133df774c41972e9ebbdc/ext/js/language/ja/japanese.js#L366
function getPitchCategory(reading, pitchAccentValue, verbOrAdjective = false) {
    const downstep = typeof pitchAccentValue === 'string'
        ? getDownstepPositions(pitchAccentValue)[0]
        : pitchAccentValue;
    if (downstep === 0) {
        return 'heiban';
    }
    if (verbOrAdjective) {
        return downstep > 0 ? 'kifuku' : null;
    }
    if (downstep === 1) {
        return 'atamadaka';
    }
    if (downstep > 1) {
        const moraCount = getKanaMorae(reading).length;
        return downstep >= moraCount ? 'odaka' : 'nakadaka';
    }
    return null;
}

// https://github.com/yomidevs/yomitan/blob/c24d4c9b39ceec1b5fd133df774c41972e9ebbdc/ext/js/display/pronunciation-generator.js#L38
function createPitchHtml(reading, pitchValue, nasalPositions = [], devoicePositions = []) {
    const morae = getKanaMorae(reading);
    const nasalSet = new Set(nasalPositions);
    const devoiceSet = new Set(devoicePositions);
    const container = el('span', { className: 'pronunciation-text' });

    for (let i = 0; i < morae.length; i++) {
        const mora = morae[i];
        const isHigh = isMoraPitchHigh(i, pitchValue);
        const isHighNext = isMoraPitchHigh(i + 1, pitchValue);

        const moraSpan = el('span', {
            className: 'pronunciation-mora',
            'data-pitch': isHigh ? 'high' : 'low',
            'data-pitch-next': isHighNext ? 'high' : 'low'
        });

        if (nasalSet.has(i + 1)) {
            moraSpan.dataset.nasal = 'true';
            const characterInfo = getKanaDiacriticInfo(mora[0]);
            if (characterInfo !== null) {
                moraSpan.dataset.originalText = mora;
            }
            const group = el('span', { className: 'pronunciation-character-group' }, [
                el('span', { textContent: characterInfo !== null ? characterInfo.character : mora[0] }),
                el('span', { className: 'pronunciation-nasal-diacritic', textContent: '\u309a' }),
                el('span', { className: 'pronunciation-nasal-indicator' }),
            ]);
            moraSpan.appendChild(group);
            if (mora.length > 1) {
                moraSpan.appendChild(document.createTextNode(mora.slice(1)));
            }
        } else {
            moraSpan.appendChild(document.createTextNode(mora));
        }

        if (devoiceSet.has(i + 1)) {
            moraSpan.dataset.devoice = 'true';
            moraSpan.appendChild(el('span', { className: 'pronunciation-devoice-indicator' }));
        }

        moraSpan.appendChild(el('span', { className: 'pronunciation-mora-line' }));
        container.appendChild(moraSpan);
    }

    return container;
}

function createPitchGroup(pitchData, reading) {
    const container = el('div', { className: 'pitch-group', 'data-details': pitchData.dictionary });
    container.appendChild(el('span', { className: 'pitch-dict-label', textContent: pitchData.dictionary }));

    const list = el('ul', { className: 'pitch-entries' });
    pitchData.pitches?.forEach((accent) => {
        const li = el('li');
        const downsteps = typeof accent.position === 'string'
            ? getDownstepPositions(accent.position)
            : accent.position;
        li.appendChild(createPitchHtml(reading, accent.position, accent.nasal, accent.devoice));
        li.appendChild(document.createTextNode(` [${downsteps}]`));
        list.appendChild(li);
    });
    container.appendChild(list);

    return container;
}

function createTranscriptionGroup(transcriptionData) {
    const container = el('div', { className: 'transcription-group', 'data-details': transcriptionData.dictionary });
    container.appendChild(el('span', { className: 'pitch-dict-label', textContent: transcriptionData.dictionary }));

    const list = el('ul', { className: 'pitch-entries transcription-entries' });
    transcriptionData.transcriptions?.forEach((transcription) => {
        if (!transcription) return;
        const li = el('li');
        li.appendChild(el('span', { className: 'transcription-text', textContent: transcription }));
        list.appendChild(li);
    });
    container.appendChild(list);

    return container;
}

function createTags(entry) {
    const { deinflectionTraceRows, frequencies, pitches, reading, expression } = entry;
    const traceRows = (deinflectionTraceRows || []).filter(row => row?.length);
    const hasDeinflection = traceRows.length;
    const hasFrequencies = frequencies?.length;
    const pitchGroups = (pitches || []).filter(pitch => pitch?.pitches?.length);
    const transcriptionGroups = (pitches || []).filter(pitch => pitch?.transcriptions?.length);
    const hasPitches = pitchGroups.length;
    const hasTranscriptions = transcriptionGroups.length;

    if (!hasDeinflection && !hasFrequencies && !hasPitches && !hasTranscriptions && !window.showExpressionTags) {
        return null;
    }

    const container = el('div', { className: 'entry-tags' });

    if (window.showExpressionTags) {
        const exprRow = el('div', { className: 'tag-row expr-tag-row' });
        exprRow.appendChild(el('span', { className: 'expr-tag', textContent: expression }));
        if (reading && reading !== expression) {
            exprRow.appendChild(el('span', { className: 'expr-tag', textContent: reading }));
        }
        container.appendChild(exprRow);
    }

    if (hasDeinflection) {
        traceRows.forEach(row => {
            const deinflectionDiv = el('div', { className: 'tag-row' });
            row.forEach(tag => deinflectionDiv.appendChild(createDeinflectionTag(tag)));
            container.appendChild(deinflectionDiv);
        });
    }

    if (hasFrequencies) {
        if (window.harmonicFrequency) {
            const normalRow = el('div', { className: 'tag-row', style: 'display:none' });
            frequencies.forEach(freq => normalRow.appendChild(createFrequencyGroup(freq)));

            const harmonicRow = el('div', { className: 'tag-row' });
            harmonicRow.appendChild(createHarmonicFrequencyTag(frequencies));

            const toggle = () => {
                const swap = harmonicRow.style.display !== 'none';
                harmonicRow.style.display = swap ? 'none' : '';
                normalRow.style.display = swap ? '' : 'none';
            };

            normalRow.addEventListener('click', toggle);
            harmonicRow.addEventListener('click', toggle);
            container.appendChild(harmonicRow);
            container.appendChild(normalRow);
        } else {
            const freqContainer = el('div', { className: 'tag-row' });
            frequencies.forEach(freq => freqContainer.appendChild(createFrequencyGroup(freq)));
            container.appendChild(freqContainer);
        }
    }

    if (hasPitches) {
        const pitchContainer = el('div', { className: 'pitch-list' });
        if (window.deduplicatePitchAccents) {
            const seen = new Set();
            pitchGroups.forEach(pitch => {
                const moraCount = getKanaMorae(reading || '').length;
                const unique = pitch.pitches.filter(accent => {
                    const pattern = pitchPattern(accent.position, moraCount);
                    if (seen.has(pattern)) return false;
                    seen.add(pattern);
                    return true;
                });
                if (unique.length > 0) {
                    pitchContainer.appendChild(createPitchGroup({ dictionary: pitch.dictionary, pitches: unique }, reading));
                }
            });
        } else {
            pitchGroups.forEach(pitch => pitchContainer.appendChild(createPitchGroup(pitch, reading)));
        }
        container.appendChild(pitchContainer);
    }

    if (hasTranscriptions) {
        const transcriptionContainer = el('div', { className: 'pitch-list transcription-list' });
        transcriptionGroups.forEach(transcription => transcriptionContainer.appendChild(createTranscriptionGroup(transcription)));
        container.appendChild(transcriptionContainer);
    }

    return container;
}

async function fetchAudioUrl(expression, reading) {
    const templates = window.audioSources;
    if (!templates?.length) return null;

    for (const template of templates) {
        const url = template
        .replace('{term}', encodeURIComponent(expression))
        .replace('{reading}', encodeURIComponent(reading));
        try {
            const audioRequestUrl = window.audioRequestEndpoint
                ? `${window.audioRequestEndpoint}?url=${encodeURIComponent(url)}`
                : `audio://?url=${encodeURIComponent(url)}`;
            const response = await fetch(audioRequestUrl);
            const data = await response.json();
            if (data.type === 'audioSourceList' && data.audioSources?.[0]?.url) {
                return data.audioSources[0].url;
            }
        } catch {}
    }
    return null;
}

function playWordAudio(audioUrl) {
    const playHandler = window.webkit?.messageHandlers?.playWordAudio;
    if (!playHandler) {
        return false;
    }

    try {
        playHandler.postMessage({
            url: audioUrl,
            mode: window.audioPlaybackMode || 'interrupt'
        });
        return true;
    } catch {
        return false;
    }
}

function createButtonSlot(kind, entryIndex, enabled = true, formatId = null, formatIcon = 'square') {
    const slot = el('button', {
        className: 'button-slot',
        'data-kind': kind,
        'data-entry-index': entryIndex,
        'data-enabled': String(enabled),
        'data-format-id': formatId || '',
        'data-format-icon': formatIcon
    });
    slot.type = 'button';
    slot.setAttribute('aria-label', kind === 'audio' ? 'Play audio' : kind === 'notes' ? 'Show Anki notes' : 'Add to Anki');
    slot.addEventListener('click', (event) => {
        event.preventDefault();
        event.stopPropagation();
        if (slot.dataset.enabled === 'false') { return; }
        if (kind === 'audio') {
            playEntryAudio(entryIndex);
        } else if (kind === 'mine') {
            const parent = slot.parentElement;
            const buttonsContainer = parent?.className === 'anki-format-actions'
                ? parent.parentElement
                : parent;
            mineEntryAtIndex(entryIndex, formatId, buttonsContainer);
        } else if (kind === 'notes') {
            showNotesAtIndex(entryIndex, formatId);
        }
    });
    slot.appendChild(el('span', { className: 'button-slot-icon' }));
    applyButtonSlotVisualState(slot);
    return slot;
}

function getButtonSlot(kind, entryIndex, formatId = null) {
    const formatSelector = formatId ? `[data-format-id="${formatId}"]` : '';
    return document.querySelector(`.button-slot[data-kind="${kind}"][data-entry-index="${entryIndex}"]${formatSelector}`);
}

function updateButtonSlot(slot, changes) {
    if (!slot || !slot.isConnected) { return; }
    if ('state' in changes) { slot.dataset.state = changes.state; }
    if ('enabled' in changes) { slot.dataset.enabled = String(changes.enabled); }
    applyButtonSlotVisualState(slot);
}

function setButtonSlotHidden(slot, hidden) {
    if (!slot) { return; }
    slot.hidden = hidden;
    slot.style.display = hidden ? 'none' : '';
}

function applyButtonSlotVisualState(slot) {
    if (!slot) { return; }
    const kind = slot.dataset.kind;
    const state = slot.dataset.state || 'default';
    const enabled = slot.dataset.enabled !== 'false';
    const formatIcon = (slot.dataset.formatIcon || 'square').replace('-small', '');
    const iconName = kind === 'audio'
        ? (state === 'error' ? 'volume_off' : 'volume_up')
        : kind === 'notes' ? 'search'
        : formatIcon === 'circle' ? (state === 'duplicate' ? 'check_circle' : 'add_circle')
        : formatIcon === 'diamond' ? (state === 'duplicate' ? 'diamond_fill' : 'diamond')
        : (state === 'duplicate' ? 'check_box' : 'add_box');
    slot.disabled = !enabled;
    slot.classList?.toggle?.('button-slot-small', slot.dataset.formatIcon?.endsWith('-small'));
    slot.style.setProperty('--button-icon-url', `url("https://appassets.androidplatform.net/popup/icons/${iconName}.svg")`);
}

async function playEntryAudio(entryIndex) {
    const entry = window.lookupEntries?.[entryIndex];
    if (!entry) { return; }
    const audioSlot = getButtonSlot('audio', entryIndex);

    if (!audioUrls[entryIndex]) {
        audioUrls[entryIndex] = await fetchAudioUrl(entry.expression, entry.reading);
    }
    if (!audioUrls[entryIndex] || !playWordAudio(audioUrls[entryIndex])) {
        updateButtonSlot(audioSlot, { state: 'error' });
        setTimeout(() => updateButtonSlot(audioSlot, { state: 'default' }), 1500);
    }
}

function duplicateValuesForEntry(entry) {
    return {
        '{expression}': entry?.expression || '',
        '{reading}': entry?.reading || '',
        '{furigana-plain}': constructFuriganaPlain(entry?.expression || '', entry?.reading || ''),
        '{popup-selection-text}': getPopupSelectionText(),
        '{sentence}': entry?.matched || entry?.expression || ''
    };
}

function duplicateStateForFormat(states, formatId) {
    return states && Object.prototype.hasOwnProperty.call(states, formatId)
        ? Boolean(states[formatId])
        : null;
}

async function mineEntryAtIndex(entryIndex, formatId, buttonsContainer = null) {
    const entry = window.lookupEntries?.[entryIndex];
    if (!entry) { return; }
    const { expression, reading, frequencies, pitches, rules, matched } = entry;
    const formats = Array.isArray(window.ankiFormats) ? window.ankiFormats : [];
    const mineSlot = buttonSlotInContainer(buttonsContainer, 'mine', entryIndex, formatId);

    lastSelection = getPopupSelectionText();
    formats.forEach((format) => updateButtonSlot(
        buttonSlotInContainer(buttonsContainer, 'mine', entryIndex, format.id),
        { enabled: false },
    ));

    const mined = await mineEntry(expression, reading, frequencies, pitches, rules, matched, entryIndex, lastSelection, formatId);
    if (!mined) {
        updateButtonSlot(mineSlot, { state: 'error', enabled: false });
        return;
    }
    const checkDuplicate = () => refreshAnkiDuplicateStates(entryIndex, buttonsContainer);

    if (window.useAnkiConnect) {
        await checkDuplicate();
    } else {
        setTimeout(checkDuplicate, 1000);
    }
}

async function showNotesAtIndex(entryIndex, formatId) {
    const entry = window.lookupEntries?.[entryIndex];
    if (!entry || !formatId) return false;
    return await webkit.messageHandlers.showNotes.postMessage({
        formatId,
        values: duplicateValuesForEntry(entry)
    });
}

function appendAnkiFormatButtons(container, entryIndex) {
    const formats = Array.isArray(window.ankiFormats) ? window.ankiFormats : [];
    formats.forEach((format, index) => {
        const placement = index === 0 ? 'leading' : 'above';
        const actions = el('span', {
            className: 'anki-format-actions',
            'data-format-id': format.id,
            'data-notes-placement': placement,
        });
        const mineButton = createButtonSlot(
            'mine',
            entryIndex,
            Boolean(window.ankiBackendAvailable && format.isValid),
            format.id,
            format.icon,
        );
        const notesButton = createButtonSlot('notes', entryIndex, true, format.id, format.icon);
        setButtonSlotHidden(notesButton, true);
        if (placement === 'leading') {
            actions.appendChild(notesButton);
            actions.appendChild(mineButton);
        } else {
            notesButton.dataset.placement = 'above';
            actions.appendChild(mineButton);
            actions.appendChild(notesButton);
        }
        container.appendChild(actions);
    });
    return formats;
}

function buttonSlotInContainer(container, kind, entryIndex, formatId) {
    const pending = Array.from(container?.children || []);
    let local = null;
    while (pending.length && !local) {
        const child = pending.shift();
        if (
            child?.dataset?.kind === kind &&
            child.dataset.entryIndex === String(entryIndex) &&
            child.dataset.formatId === (formatId || '')
        ) {
            local = child;
        } else {
            pending.push(...Array.from(child?.children || []));
        }
    }
    return container ? (local || null) : getButtonSlot(kind, entryIndex, formatId);
}

function syncShowNotesButton(container, entryIndex, format, visible) {
    const existing = buttonSlotInContainer(container, 'notes', entryIndex, format.id);
    setButtonSlotHidden(existing, !visible);
}

async function refreshAnkiDuplicateStates(entryIndex, buttonsContainer) {
    const entry = window.lookupEntries?.[entryIndex];
    const formats = Array.isArray(window.ankiFormats) ? window.ankiFormats : [];
    if (!entry) return false;
    let states;
    try {
        states = await webkit.messageHandlers.duplicateCheck.postMessage(duplicateValuesForEntry(entry));
    } catch {
        states = null;
    }
    formats.forEach((format) => {
        const mineSlot = buttonSlotInContainer(buttonsContainer, 'mine', entryIndex, format.id);
        const isDuplicate = duplicateStateForFormat(states, format.id);
        if (isDuplicate === null) {
            updateButtonSlot(mineSlot, { state: 'error', enabled: false });
            syncShowNotesButton(buttonsContainer, entryIndex, format, false);
            return;
        }
        updateButtonSlot(mineSlot, {
            state: isDuplicate ? 'duplicate' : 'default',
            enabled: Boolean(
                window.ankiBackendAvailable &&
                format.isValid &&
                !(isDuplicate && !window.allowDupes)
            ),
        });
        syncShowNotesButton(
            buttonsContainer,
            entryIndex,
            format,
            Boolean(isDuplicate && !window.disableShowNotes),
        );
    });
    return states !== null;
}

function createEntryHeader(entry, idx) {
    const { expression, reading } = entry;
    const header = el('div', { className: 'entry-header' });

    const expressionSpan = el('span', { className: 'expression' });
    let needsScroll = false;
    if (reading && reading !== expression) {
        needsScroll = buildFuriganaEl(expressionSpan, expression, reading);
    } else {
        expressionSpan.append(...wrapKanji(expression));
    }
    if (needsScroll) {
        const expressionScroll = el('div', { className: 'expression-scroll' });
        expressionScroll.appendChild(expressionSpan);
        header.appendChild(expressionScroll);
    } else {
        header.appendChild(expressionSpan);
    }

    const buttonsContainer = el('div', { className: 'header-buttons' });

    appendAnkiFormatButtons(buttonsContainer, idx);
    refreshAnkiDuplicateStates(idx, buttonsContainer);

    if (window.audioSources?.length) {
        buttonsContainer.appendChild(createButtonSlot('audio', idx));
    }

    header.appendChild(buttonsContainer);

    return header;
}

function createGlossarySection(dictName, contents, isFirst, entryIdx) {
    const details = el('details', { className: 'glossary-group' });
    const collapsed = window.collapseMode === 'Collapse All'
        || (window.collapseMode === 'Custom' && window.collapsedDictionaries.includes(dictName));
    details.open = !collapsed || (window.expandFirstDictionary && isFirst);

    const summary = el('summary', { className: 'dict-label' });
    summary.appendChild(el('span', { className: 'dict-name', textContent: dictName }));
    let timer = null, longPressed = false;
    const toggleSelection = () => {
        longPressed = true;
        const selected = selectedDictionaries[entryIdx];
        selected?.label.classList.remove('selected');
        if (selected?.name === dictName) {
            delete selectedDictionaries[entryIdx];
        } else {
            selectedDictionaries[entryIdx] = { name: dictName, label: summary };
            summary.classList.add('selected');
        }
    };
    summary.addEventListener('pointerdown', () => {
        longPressed = false;
        timer = setTimeout(toggleSelection, 400);
    });
    const cancel = () => { clearTimeout(timer); };
    summary.addEventListener('pointerup', cancel);
    summary.addEventListener('pointercancel', cancel);
    summary.addEventListener('click', (e) => {
        e.preventDefault();
        if (longPressed) {
            return;
        }
        details.open = !details.open;
    });
    details.appendChild(summary);

    const dictWrapper = document.createElement('div');
    dictWrapper.setAttribute('data-dictionary', dictName);

    const dictStyle = window.dictionaryStyles?.[dictName] ?? '';
    dictWrapper.appendChild(el('style', {
        textContent: `
            [data-dictionary="${dictName}"] {
                ${dictStyle}
                color: var(--text-color) !important;
            }
        `.trim()
    }));
    window.hoshiPopupPrewarmFonts?.();

    const termTags = [...new Set(parseTags(contents[0]?.termTags))];
    const renderContent = (parent, content) => {
        try {
            renderStructuredContent(parent, JSON.parse(content), null, dictName);
        } catch {
            renderStructuredContent(parent, content, null, dictName);
        }
    };

    const termTagsRow = createGlossaryTags(termTags);
    if (termTagsRow) {
        dictWrapper.appendChild(termTagsRow);
    }

    if (contents.length > 1) {
        const ol = el('ol');
        let prevTags = null;
        contents.forEach((item) => {
            const li = el('li');
            const parsedTags = parseTags(item.definitionTags).filter(tag => !NUMERIC_TAG.test(tag));
            const posTags = [...new Set(parsedTags.filter(isPartOfSpeech))].sort();
            const currentTags = JSON.stringify(posTags);
            const filteredTags = parsedTags.filter(tag => !isPartOfSpeech(tag) || !(prevTags !== null && prevTags === currentTags));
            const tags = createGlossaryTags(filteredTags);
            if (tags) {
                li.appendChild(tags);
            }
            const content = el('div', { className: 'glossary-content' });
            renderContent(content, item.content);
            li.appendChild(content);
            ol.appendChild(li);
            prevTags = currentTags;
        });
        dictWrapper.appendChild(ol);
    } else {
        contents.forEach((item) => {
            const wrapper = el('div');
            const tags = createGlossaryTags(parseTags(item.definitionTags).filter(tag => !NUMERIC_TAG.test(tag)));
            if (tags) {
                wrapper.appendChild(tags);
            }
            const content = el('div', { className: 'glossary-content' });
            renderContent(content, item.content);
            wrapper.appendChild(content);
            dictWrapper.appendChild(wrapper);
        });
    }

    details.appendChild(dictWrapper);
    return details;
}

const backStack = [];
const forwardStack = [];
let pendingHistoryRestore = null;

function replaceHostEntrySet() {
    hostEntrySetVersion++;
    activeEntrySetVersion = hostEntrySetVersion;
}

window.resetPopupResults = function() {
    renderGeneration++;
    replaceHostEntrySet();
    popupTermNavigator.reset();
    flushPendingHistoryRestore();
    backStack.length = 0;
    forwardStack.length = 0;
    pendingHistoryRestore = null;
    window.lookupEntries = undefined;
    window.entryCount = 0;
    audioUrls = {};
    selectedDictionaries = {};
    resetDictionaryMediaObserver();
    document.getElementById('entries-container')?.replaceChildren();
    popupGeometry.setScrollTop(0);
};

function appendPendingHistoryRestore(flush = false) {
    const pending = pendingHistoryRestore;
    if (!pending) {
        return;
    }
    const count = flush ? pending.nodes.length : Math.min(2, pending.nodes.length);
    const chunk = pending.nodes.splice(0, count);
    if (chunk.length) {
        pending.container.append(...chunk);
        observePendingDictionaryMedia(pending.container);
        popupTermNavigator.entryRendered();
    }
    if (!pending.nodes.length) {
        pendingHistoryRestore = null;
        return;
    }
    if (!flush) {
        setTimeout(() => appendPendingHistoryRestore(), 16);
    }
}

function flushPendingHistoryRestore() {
    appendPendingHistoryRestore(true);
}

function redirect(count) {
    popupTermNavigator.reset();
    flushPendingHistoryRestore();
    resetDictionaryMediaObserver();
    backStack.push(snapshot());
    forwardStack.length = 0;
    replaceHostEntrySet();
    window.lookupEntries = undefined;
    window.entryCount = count;
    audioUrls = {};
    selectedDictionaries = {};
    document.getElementById('entries-container').innerHTML = '';
    window.renderPopup();
    requestAnimationFrame(() => {
        popupGeometry.setScrollTop(0);
        requestAnimationFrame(() => {
            popupGeometry.setScrollTop(0);
        });
    });
}

function buildKanjiEntry(data) {
    const entry = el('div', { className: 'entry kanji-entry' });
    const header = el('div', { className: 'entry-header' });
    header.appendChild(el('span', { className: 'kanji', textContent: data.character }));
    entry.appendChild(header);

    (data.entries || []).forEach((kanjiDictionary) => {
        const details = el('details', { className: 'glossary-group', open: true });
        const summary = el('summary', { className: 'dict-label' });
        summary.appendChild(el('span', { className: 'dict-name', textContent: kanjiDictionary.dictName }));
        details.appendChild(summary);

        const dictionary = el('div', { 'data-dictionary': kanjiDictionary.dictName });
        const content = el('div', { className: 'glossary-content' });
        if (kanjiDictionary.onyomi) {
            content.appendChild(el('div', {}, [
                el('span', { className: 'kanji-reading-label', textContent: '音' }),
                document.createTextNode(kanjiDictionary.onyomi),
            ]));
        }
        if (kanjiDictionary.kunyomi) {
            content.appendChild(el('div', {}, [
                el('span', { className: 'kanji-reading-label', textContent: '訓' }),
                document.createTextNode(kanjiDictionary.kunyomi),
            ]));
        }
        if (kanjiDictionary.meanings?.length) {
            if (kanjiDictionary.onyomi || kanjiDictionary.kunyomi) {
                content.appendChild(el('hr', { className: 'kanji-separator' }));
            }
            content.appendChild(el('ul', {}, kanjiDictionary.meanings.map((meaning) =>
                el('li', { textContent: meaning })
            )));
        }
        dictionary.appendChild(content);
        details.appendChild(dictionary);
        entry.appendChild(details);
    });
    return entry;
}

function redirectKanji(data) {
    popupTermNavigator.reset();
    flushPendingHistoryRestore();
    resetDictionaryMediaObserver();
    renderGeneration++;
    backStack.push(snapshot());
    forwardStack.length = 0;
    window.lookupEntries = undefined;
    window.entryCount = 0;
    audioUrls = {};
    selectedDictionaries = {};
    const container = document.getElementById('entries-container');
    container.replaceChildren(buildKanjiEntry(data));
    applyHoshiPopupThemeOverrides(container);
    requestAnimationFrame(() => popupGeometry.setScrollTop(0));
}

window.replacePopupResults = function(count, initialEntries) {
    closeOverlay();
    popupTermNavigator.reset();
    flushPendingHistoryRestore();
    renderGeneration++;
    replaceHostEntrySet();
    backStack.length = 0;
    forwardStack.length = 0;
    window.lookupEntries = Array.isArray(initialEntries) && initialEntries.length ? initialEntries : undefined;
    window.entryCount = count;
    audioUrls = {};
    selectedDictionaries = {};
    resetDictionaryMediaObserver();
    const container = document.getElementById('entries-container');
    if (container) {
        container.innerHTML = '';
    }
    window.hoshiPopupObserveContentReady?.();
    window.renderPopup();
    requestAnimationFrame(() => {
        popupGeometry.setScrollTop(0);
    });
};

function snapshot() {
    flushPendingHistoryRestore();
    const container = document.getElementById('entries-container');
    return {
        nodes: [...container.childNodes],
        scrollTop: popupGeometry.scrollTop(),
        lookupEntries: window.lookupEntries?.slice(),
        entryCount: window.entryCount,
        entrySetVersion: activeEntrySetVersion,
    };
}

function renderedEntryCount(nodes) {
    return nodes.filter((node) => node?.dataset?.entryIndex !== undefined).length;
}

function hasRenderedEntry(container, index) {
    return [...container.childNodes].some((node) => node?.dataset?.entryIndex === String(index));
}

function restore(snapshot) {
    renderGeneration++;
    popupTermNavigator.reset();
    flushPendingHistoryRestore();
    const container = document.getElementById('entries-container');
    const nodes = [...snapshot.nodes];
    activeEntrySetVersion = snapshot.entrySetVersion;
    const shouldResumeRender = snapshot.entrySetVersion === hostEntrySetVersion
        && snapshot.entryCount > renderedEntryCount(nodes);
    const shouldDeferOffscreenNodes = !shouldResumeRender && snapshot.scrollTop === 0 && nodes.length > 6;
    if (shouldDeferOffscreenNodes) {
        container.replaceChildren(...nodes.splice(0, 4));
        observePendingDictionaryMedia(container);
        pendingHistoryRestore = { container, nodes };
        setTimeout(() => appendPendingHistoryRestore(), 50);
    } else {
        container.replaceChildren(...nodes);
        observePendingDictionaryMedia(container);
    }
    window.lookupEntries = snapshot.lookupEntries;
    window.entryCount = snapshot.entryCount;
    audioUrls = {};
    selectedDictionaries = {};
    applyHoshiPopupThemeOverrides(container);
    requestAnimationFrame(() => {
        popupGeometry.setScrollTop(snapshot.scrollTop);
    });
    if (shouldResumeRender) {
        window.renderPopup();
    }
}

function navigate(origin, destination) {
    if (!origin.length) {
        return;
    }
    destination.push(snapshot());
    restore(origin.pop());
}

window.navigateBack = () => navigate(backStack, forwardStack);
window.navigateForward = () => navigate(forwardStack, backStack);

function applyHoshiPopupThemeOverrides(root = document) {
    const colorScheme = document.documentElement.dataset.hoshiColorScheme;
    const buttonColor = colorScheme === 'dark' ? 'rgba(235, 235, 245, 0.92)' : 'rgba(60, 60, 67, 0.86)';
    const tableHeaderBackgroundColor = colorScheme === 'dark' ? '#333333' : '#eeeeee';
    const tableHeaderTextColor = colorScheme === 'dark' ? '#ffffff' : '#000000';
    root.querySelectorAll('button.button-slot').forEach(button => {
        button.style.setProperty('color', buttonColor, 'important');
    });
    root.querySelectorAll('table[data-sc-content="formsTable"] th, table[data-sc-content="formsTable"] .gloss-sc-th').forEach(header => {
        header.style.setProperty('background-color', tableHeaderBackgroundColor, 'important');
        header.style.setProperty('color', tableHeaderTextColor, 'important');
        header.querySelectorAll('*').forEach(child => {
            child.style.setProperty('color', 'inherit', 'important');
        });
    });
}

function popupEventTarget(event) {
    return event.target?.nodeType === Node.TEXT_NODE ? event.target.parentElement : event.target;
}

function isPopupInteractiveTapTarget(target) {
    if (target?.closest('summary, a, button, .button-slot, .deinflection-tag, .frequency-group, .pitch-group, .overlay, .overlay-close, .overlay-content')) {
        return true;
    }
    const tagRow = target?.closest('.tag-row');
    return Boolean(tagRow && !tagRow.closest('.expr-tag-row'));
}

function handlePopupTap(target, clientX, clientY) {
    const kanjiTarget = target?.closest('.kanji-char');
    if (kanjiTarget) {
        const requestId = ++kanjiRedirectRequestId;
        const generation = renderGeneration;
        Promise.resolve(webkit.messageHandlers.kanjiRedirect.postMessage(kanjiTarget.textContent))
            .then((data) => {
                if (!data || requestId !== kanjiRedirectRequestId || generation !== renderGeneration) return;
                redirectKanji(data);
                webkit.messageHandlers.kanjiRedirectCommitted?.postMessage(null);
            });
        return true;
    }
    if (isPopupInteractiveTapTarget(target)) {
        return false;
    }
    if (!target?.closest('.glossary-content') && !target?.closest('.expr-tag')) {
        webkit.messageHandlers.tapOutside.postMessage(null);
        return true;
    }
    const { rectX, rectY } = popupGeometry.selectionCoordinates(clientX, clientY);
    const selected = window.hoshiSelection?.selectText(clientX, clientY, window.scanLength, rectX, rectY);
    if (!selected) {
        webkit.messageHandlers.tapOutside.postMessage(null);
    }
    return true;
}

function installPopupTapHandlers(container) {
    if (container.clickAttached) {
        return;
    }
    container.clickAttached = true;
    let touchStart = null;
    let handledTouchTap = null;
    const tapSlop = 10;
    const duplicateClickSlop = 6;
    const duplicateClickWindowMs = 700;

    container.addEventListener('touchstart', (event) => {
        if (event.touches.length !== 1) {
            touchStart = null;
            return;
        }
        const touch = event.touches[0];
        touchStart = {
            x: touch.clientX,
            y: touch.clientY,
        };
    }, { passive: true });

    container.addEventListener('touchend', (event) => {
        if (!touchStart || event.changedTouches.length !== 1) {
            touchStart = null;
            return;
        }
        const touch = event.changedTouches[0];
        const dx = touch.clientX - touchStart.x;
        const dy = touch.clientY - touchStart.y;
        touchStart = null;
        if (Math.abs(dx) > tapSlop || Math.abs(dy) > tapSlop) {
            return;
        }
        const target = popupEventTarget(event);
        const handled = handlePopupTap(target, touch.clientX, touch.clientY);
        if (!handled) {
            return;
        }
        handledTouchTap = {
            x: touch.clientX,
            y: touch.clientY,
            time: Date.now(),
        };
        if (event.cancelable) {
            event.preventDefault();
        }
    });

    container.addEventListener('click', (event) => {
        if (handledTouchTap) {
            const dx = event.clientX - handledTouchTap.x;
            const dy = event.clientY - handledTouchTap.y;
            const ageMs = Date.now() - handledTouchTap.time;
            if (
                ageMs >= 0 &&
                ageMs <= duplicateClickWindowMs &&
                Math.abs(dx) <= duplicateClickSlop &&
                Math.abs(dy) <= duplicateClickSlop
            ) {
                event.preventDefault();
                return;
            }
        }
        handlePopupTap(popupEventTarget(event), event.clientX, event.clientY);
    });
}

function installPopupDocumentTapHandlers() {
    installPopupTapHandlers(document);
}

installPopupDocumentTapHandlers();

window.renderPopup = function() {
    const container = document.getElementById('entries-container');
    if (!window.entryCount) {
        return;
    }
    const generation = ++renderGeneration;

    (async () => {
        for (let idx = 0; idx < window.entryCount; idx++) {
            if (hasRenderedEntry(container, idx)) continue;
            const entry = window.lookupEntries?.[idx] ?? await webkit.messageHandlers.getEntry.postMessage(idx);
            if (generation !== renderGeneration) return;
            if (!entry) continue;

            window.lookupEntries ??= [];
            window.lookupEntries[idx] = entry;

            if (idx > 0) {
                container.appendChild(document.createElement('hr'));
            }

            const entryDiv = el('div', {
                className: 'entry',
                'data-entry-index': idx,
            });
            entryDiv.appendChild(createEntryHeader(entry, idx));

            if (window.audioEnableAutoplay && window.audioSources?.length && idx === 0) {
                playEntryAudio(idx);
            }

            const tags = createTags(entry);
            if (tags) {
                entryDiv.appendChild(tags);
            }

            container.appendChild(entryDiv);
            popupTermNavigator.entryRendered();
            await new Promise(r => requestAnimationFrame(r));
            if (generation !== renderGeneration) return;

            const grouped = {};
            entry.glossaries.forEach(g => {
                (grouped[g.dictionary] ??= []).push({
                    content: g.content,
                    definitionTags: g.definitionTags,
                    termTags: g.termTags
                });
            });

            const dictNames = Object.keys(grouped);
            for (let dictIdx = 0; dictIdx < dictNames.length; dictIdx++) {
                entryDiv.appendChild(createGlossarySection(dictNames[dictIdx], grouped[dictNames[dictIdx]], dictIdx === 0, idx));
                applyHoshiPopupThemeOverrides(entryDiv);
                await new Promise(r => requestAnimationFrame(r));
                if (generation !== renderGeneration) return;
            }
        }
        if (generation !== renderGeneration) return;

        container.querySelectorAll('.glossary-content ruby').forEach(ruby => {
            ruby.childNodes.forEach(node => {
                if (node.nodeType === Node.TEXT_NODE && node.textContent.trim()) {
                    const span = document.createElement('span');
                    span.textContent = node.textContent;
                    node.replaceWith(span);
                }
            });
        });
        applyHoshiPopupThemeOverrides(container);
    })();

    if (window.compactGlossaries && !document.getElementById('popup-compact-glossaries')) {
        const glossaryStyle = document.createElement('style');
        glossaryStyle.id = 'popup-compact-glossaries';
        glossaryStyle.textContent = `
            ul[data-sc-content="glossary"],
            ol[data-sc-content="glossary"],
            .glossary-list {
                list-style: none;
                padding-left: 0;
                margin: 0;
            }
            ul[data-sc-content="glossary"] > li,
            ol[data-sc-content="glossary"] > li,
            .glossary-list > li {
                display: inline;
            }
            ul[data-sc-content="glossary"] > li:not(:last-child)::after,
            ol[data-sc-content="glossary"] > li:not(:last-child)::after,
            .glossary-list > li:not(:last-child)::after {
                content: " | ";
                opacity: 0.6;
            }
        `;
        document.body.appendChild(glossaryStyle);
    }

    if (window.compactPitchAccents && !document.getElementById('popup-compact-pitch-accents')) {
        const pitchStyle = document.createElement('style');
        pitchStyle.id = 'popup-compact-pitch-accents';
        pitchStyle.textContent = `
            .pitch-entries, .pitch-entries > li { display: inline; }
            .pitch-entries > li { white-space: nowrap; }
            .pitch-entries > li:not(:last-child)::after { content: " | "; opacity: 0.6; white-space: normal; }
            .pitch-dict-label { margin-right: 4px; }
        `;
        document.body.appendChild(pitchStyle);
    }

    if (window.customCSS && !document.getElementById('popup-custom-css')) {
        const customStyle = document.createElement('style');
        customStyle.id = 'popup-custom-css';
        customStyle.textContent = window.customCSS;
        document.head.appendChild(customStyle);
        window.hoshiPopupPrewarmFonts?.();
    }
};

document.addEventListener('scroll', () => {
    webkit.messageHandlers.popupScrolled.postMessage(null);
}, { passive: true });
