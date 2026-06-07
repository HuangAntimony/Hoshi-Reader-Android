(function() {
    const ORIGIN = 'https://appassets.androidplatform.net';
    const LAYER_ID = 'hoshi-reader-popup-layer';
    const ACTION_BAR_HEIGHT = 37;
    const SASAYAKI_BAR_HEIGHT = 37;
    const HIGHLIGHT_LINE_SIZE = 1.5;
    const HIGHLIGHT_INLINE_MERGE_TOLERANCE = 1;
    const frames = new Map();
    const frameSources = new WeakMap();
    let idleRootRecord = null;
    let rootHighlight = null;
    let sasayakiHighlight = null;

    function ensureLayer() {
        let layer = document.getElementById(LAYER_ID);
        if (layer) return layer;
        layer = document.createElement('div');
        layer.id = LAYER_ID;
        layer.style.cssText = [
            'position:fixed',
            'inset:0',
            'z-index:2147483640',
            'pointer-events:none',
            'contain:layout style paint',
            'writing-mode:horizontal-tb',
            'direction:ltr',
            'text-orientation:mixed'
        ].join(';');
        document.documentElement.appendChild(layer);
        return layer;
    }

    function ensureHighlightLayer() {
        const layer = ensureLayer();
        let highlightLayer = layer.querySelector('.hoshi-reader-selection-highlight-layer');
        if (highlightLayer) return highlightLayer;
        highlightLayer = document.createElement('div');
        highlightLayer.className = 'hoshi-reader-selection-highlight-layer';
        layer.insertBefore(highlightLayer, layer.firstChild);
        return highlightLayer;
    }

    function ensureSasayakiHighlightLayer() {
        const layer = ensureLayer();
        let highlightLayer = layer.querySelector('.hoshi-reader-sasayaki-highlight-layer');
        if (highlightLayer) return highlightLayer;
        highlightLayer = document.createElement('div');
        highlightLayer.className = 'hoshi-reader-sasayaki-highlight-layer';
        layer.insertBefore(highlightLayer, layer.firstChild);
        return highlightLayer;
    }

    function postNative(message) {
        if (!window.HoshiReaderPopup?.postMessage) return;
        window.HoshiReaderPopup.postMessage(JSON.stringify(message));
    }

    function icon(name) {
        return `https://appassets.androidplatform.net/popup/icons/${name}.svg`;
    }

    function frameContentTop(payload) {
        return (payload.actionBarVisible ? ACTION_BAR_HEIGHT : 0) + (payload.sasayakiVisible ? SASAYAKI_BAR_HEIGHT : 0);
    }

    function button(iconName, enabled, action, label, className = '') {
        const item = document.createElement('button');
        item.type = 'button';
        item.className = ['hoshi-reader-popup-control', className].filter(Boolean).join(' ');
        item.disabled = !enabled;
        item.setAttribute('aria-label', label);
        item.style.setProperty('--icon-url', `url("${icon(iconName)}")`);
        item.addEventListener('click', (event) => {
            event.preventDefault();
            event.stopPropagation();
            if (!item.disabled) action();
        });
        return item;
    }

    function spacer() {
        const item = document.createElement('span');
        item.className = 'hoshi-reader-popup-flex-spacer';
        item.setAttribute('aria-hidden', 'true');
        return item;
    }

    function buildBar(className, buttons) {
        const bar = document.createElement('div');
        bar.className = className;
        buttons.forEach(item => bar.appendChild(item));
        return bar;
    }

    function renderControls(shell, payload, iframe) {
        shell.querySelectorAll('.hoshi-reader-popup-bar').forEach(node => node.remove());
        if (payload.actionBarVisible) {
            shell.insertBefore(
                buildBar('hoshi-reader-popup-bar hoshi-reader-popup-action-bar', [
                    button('arrow_back', payload.backCount > 0, () => navigateBack(payload.id), 'Back'),
                    button('arrow_forward', payload.forwardCount > 0, () => navigateForward(payload.id), 'Forward'),
                    spacer(),
                    button('close', true, () => postNative({ name: 'swipeDismiss', popupId: payload.id }), 'Close')
                ]),
                iframe,
            );
        }
        if (payload.sasayakiVisible) {
            shell.insertBefore(
                buildBar('hoshi-reader-popup-bar hoshi-reader-popup-sasayaki-bar', [
                    button('replay', true, () => postNative({ name: 'sasayakiReplayCue', popupId: payload.id }), 'Replay cue', 'hoshi-reader-popup-sasayaki-control'),
                    button((payload.sasayakiIsPlaying || payload.sasayakiWasPaused) ? 'pause' : 'play_arrow', true, () => postNative({ name: 'sasayakiTogglePlayback', popupId: payload.id }), 'Play or pause', 'hoshi-reader-popup-sasayaki-control'),
                    button('start', true, () => postNative({ name: 'sasayakiPlayForward', popupId: payload.id }), 'Play from cue', 'hoshi-reader-popup-sasayaki-control')
                ]),
                iframe,
            );
        }
    }

    function applyShellStyle(shell, payload) {
        const frame = payload.frame;
        shell.style.left = `${frame.left}px`;
        shell.style.top = `${frame.top}px`;
        shell.style.width = `${frame.width}px`;
        shell.style.height = `${frame.height}px`;
        shell.dataset.popupId = payload.id;
        shell.dataset.darkMode = String(!!payload.darkMode);
        shell.dataset.eInkMode = String(!!payload.eInkMode);
    }

    function iframeRenderMessage(payload) {
        return {
            type: 'renderPopup',
            popupId: payload.id,
            entriesCount: payload.entriesCount || 0,
            initialEntryJson: payload.initialEntryJson || null
        };
    }

    function renderIframe(record) {
        record.iframe.contentWindow?.postMessage(iframeRenderMessage(record.payload), ORIGIN);
    }

    function historyCount(payload, name) {
        return payload?.[name] || 0;
    }

    function shouldReplaceIframeContent(previous, next) {
        if (!previous) return true;
        if (previous.id !== next.id) return true;
        const nextKeepsHistory =
            historyCount(next, 'backCount') > 0 ||
            historyCount(next, 'forwardCount') > 0;
        if (nextKeepsHistory) return false;
        const previousHadHistory =
            historyCount(previous, 'backCount') > 0 ||
            historyCount(previous, 'forwardCount') > 0;
        const hasNextInitialEntry = (next.initialEntryJson || null) !== null;
        if (!hasNextInitialEntry) return false;
        if (previousHadHistory) return true;
        const previousContentKey = previous.contentKey || null;
        const nextContentKey = next.contentKey || null;
        if (previousContentKey !== null || nextContentKey !== null) {
            return previousContentKey !== nextContentKey;
        }
        return (previous.entriesCount || 0) !== (next.entriesCount || 0) ||
            (previous.initialEntryJson || null) !== (next.initialEntryJson || null);
    }

    function setContentReady(record, ready) {
        record.contentReady = ready;
        record.shell.dataset.contentReady = String(ready);
        syncRootReveal();
    }

    function setRevealReady(record, ready) {
        record.revealReady = ready;
        record.shell.dataset.revealReady = String(ready);
    }

    function resetIframe(record) {
        record.iframe.contentWindow?.postMessage({ type: 'resetPopup' }, ORIGIN);
    }

    function createRecord(payload = null) {
        const shell = document.createElement('div');
        shell.className = 'hoshi-reader-popup-shell';
        shell.dataset.contentReady = 'false';
        const iframe = document.createElement('iframe');
        iframe.className = 'hoshi-reader-popup-iframe';
        iframe.setAttribute('sandbox', 'allow-scripts allow-same-origin');
        shell.dataset.revealReady = 'false';
        const record = { shell, iframe, payload, contentReady: false, revealReady: false, loaded: false, root: false };
        iframe.addEventListener('load', () => {
            record.loaded = true;
            if (record.payload) {
                frameSources.set(iframe.contentWindow, record.payload.id);
                renderIframe(record);
            }
        });
        shell.appendChild(iframe);
        return record;
    }

    function preloadIdleRootFrame(iframeUrl) {
        window.__hoshiReaderPopupIframeUrl = iframeUrl;
        if (!iframeUrl || frames.size > 0) return;
        const layer = ensureLayer();
        if (!idleRootRecord) {
            idleRootRecord = createRecord();
            layer.appendChild(idleRootRecord.shell);
        }
        setContentReady(idleRootRecord, false);
        resetIframe(idleRootRecord);
        idleRootRecord.payload = null;
        idleRootRecord.root = false;
        setRevealReady(idleRootRecord, false);
        if (idleRootRecord.iframe.src !== iframeUrl) {
            idleRootRecord.loaded = false;
            idleRootRecord.iframe.src = iframeUrl;
        }
    }

    function activateRecord(payload, isRoot) {
        let record = frames.get(payload.id);
        let needsRender = false;
        if (!record) {
            if (isRoot && idleRootRecord) {
                record = idleRootRecord;
                idleRootRecord = null;
                needsRender = true;
            } else {
                record = createRecord(payload);
                ensureLayer().appendChild(record.shell);
                needsRender = true;
            }
            frames.set(payload.id, record);
        }
        if (shouldReplaceIframeContent(record.payload, payload)) {
            needsRender = true;
        }
        record.root = isRoot;
        record.payload = payload;
        if (needsRender) {
            setContentReady(record, false);
            setRevealReady(record, false);
            resetIframe(record);
            if (record.iframe.contentWindow) {
                frameSources.set(record.iframe.contentWindow, payload.id);
            }
        }
        return { record, needsRender };
    }

    function renderPayload(payload, index) {
        const isRoot = index === 0;
        const { record, needsRender } = activateRecord(payload, isRoot);

        if (record.clearSelectionSignal !== undefined && record.clearSelectionSignal !== payload.clearSelectionSignal) {
            record.iframe.contentWindow?.postMessage({ type: 'clearSelection' }, ORIGIN);
        }
        record.clearSelectionSignal = payload.clearSelectionSignal;
        applyShellStyle(record.shell, payload);
        renderControls(record.shell, payload, record.iframe);
        record.iframe.style.top = `${frameContentTop(payload)}px`;
        record.iframe.style.height = `calc(100% - ${frameContentTop(payload)}px)`;
        syncRootReveal();
        if (record.iframe.src !== payload.iframeUrl) {
            setContentReady(record, false);
            setRevealReady(record, false);
            resetIframe(record);
            record.loaded = false;
            record.iframe.src = payload.iframeUrl;
        } else if (needsRender && record.loaded) {
            renderIframe(record);
        }
    }

    function parkRootRecord(record) {
        setContentReady(record, false);
        resetIframe(record);
        record.payload = null;
        record.clearSelectionSignal = undefined;
        record.root = false;
        record.shell.dataset.popupId = '';
        setRevealReady(record, false);
        record.shell.querySelectorAll('.hoshi-reader-popup-bar').forEach(node => node.remove());
        record.iframe.style.top = '0px';
        record.iframe.style.height = '100%';
        idleRootRecord = record;
    }

    function removeMissing(activeIds) {
        for (const [id, record] of frames.entries()) {
            if (activeIds.has(id)) continue;
            frames.delete(id);
            if (record.root && !idleRootRecord) {
                parkRootRecord(record);
            } else {
                resetIframe(record);
                record.shell.remove();
            }
        }
        cleanupLayerIfIdle();
    }

    function activeRootRecord() {
        const first = frames.values().next();
        if (first.done) return null;
        const record = first.value;
        return record?.root ? record : null;
    }

    function rootHighlightBlocksReveal(record) {
        if (!record?.root || !record.payload) return false;
        return !!rootHighlight && rootHighlight.popupId === record.payload.id && rootHighlight.pending === true;
    }

    function fullBoxEdges() {
        return { top: true, right: true, bottom: true, left: true };
    }

    function readerCssVariable(name) {
        return window.getComputedStyle?.(document.documentElement)?.getPropertyValue(name)?.trim() ?? '';
    }

    function readerEInkMode() {
        return readerCssVariable('--hoshi-reader-eink-mode') === '1';
    }

    function readerVerticalWriting() {
        const bodyWritingMode = window.getComputedStyle?.(document.body)?.writingMode ?? '';
        if (bodyWritingMode.startsWith('vertical')) return true;
        if (bodyWritingMode.startsWith('horizontal')) return false;
        const rootWritingMode = window.getComputedStyle?.(document.documentElement)?.writingMode ?? '';
        if (rootWritingMode.startsWith('vertical')) return true;
        if (rootWritingMode.startsWith('horizontal')) return false;
        return readerCssVariable('--hoshi-reader-vertical-writing') === '1';
    }

    function readerEInkLineColor(darkMode = false) {
        return readerCssVariable('--hoshi-eink-line-color') || (darkMode ? '#fff' : '#000');
    }

    function hasSasayakiHighlight() {
        return !!sasayakiHighlight &&
            sasayakiHighlight.eInkMode !== false &&
            Array.isArray(sasayakiHighlight.rects) &&
            sasayakiHighlight.rects.some(rect => rect && rect.width > 0 && rect.height > 0);
    }

    function cleanupLayerIfIdle() {
        const layer = document.getElementById(LAYER_ID);
        if (layer && frames.size === 0 && !idleRootRecord && !hasSasayakiHighlight()) {
            layer.remove();
        }
    }

    function appendAbsoluteLine(layer, className, left, top, width, height, color) {
        if (width <= 0 || height <= 0) return;
        const line = document.createElement('div');
        line.className = className;
        line.style.position = 'absolute';
        line.style.background = color;
        line.style.left = `${left}px`;
        line.style.top = `${top}px`;
        line.style.width = `${width}px`;
        line.style.height = `${height}px`;
        layer.appendChild(line);
    }

    function devicePixelRatio() {
        return Math.max(1, window.devicePixelRatio || 1);
    }

    function snapCssPixel(value) {
        const ratio = devicePixelRatio();
        return Math.round(value * ratio) / ratio;
    }

    function highlightLineSize() {
        return HIGHLIGHT_LINE_SIZE;
    }

    function snapHighlightRect(rect) {
        const left = snapCssPixel(rect.x);
        const top = snapCssPixel(rect.y);
        const right = snapCssPixel(rect.x + rect.width);
        const bottom = snapCssPixel(rect.y + rect.height);
        return {
            x: left,
            y: top,
            width: Math.max(0, right - left),
            height: Math.max(0, bottom - top),
        };
    }

    function renderSasayakiHighlight(payload) {
        sasayakiHighlight = payload || null;
        const existingLayer = document.getElementById(LAYER_ID)?.querySelector('.hoshi-reader-sasayaki-highlight-layer');
        if (!hasSasayakiHighlight() || !readerEInkMode()) {
            existingLayer?.replaceChildren();
            cleanupLayerIfIdle();
            return;
        }
        const layer = ensureSasayakiHighlightLayer();
        const verticalWriting = sasayakiHighlight.verticalWriting ?? readerVerticalWriting();
        const color = readerEInkLineColor(!!sasayakiHighlight.darkMode);
        const rects = highlightBoxRects(
            sasayakiHighlight.rects.filter(rect => rect && rect.width > 0 && rect.height > 0),
            verticalWriting,
        );
        layer.replaceChildren();
        rects.forEach((rect) => {
            const snapped = snapHighlightRect(rect);
            const lineSize = highlightLineSize();
            if (verticalWriting) {
                appendAbsoluteLine(
                    layer,
                    'hoshi-reader-sasayaki-highlight-line',
                    snapped.x + Math.max(0, snapped.width - lineSize),
                    snapped.y,
                    lineSize,
                    snapped.height,
                    color,
                );
            } else {
                appendAbsoluteLine(
                    layer,
                    'hoshi-reader-sasayaki-highlight-line',
                    snapped.x,
                    snapped.y + Math.max(0, snapped.height - lineSize),
                    snapped.width,
                    lineSize,
                    color,
                );
            }
        });
    }

    function clearSasayakiHighlight() {
        sasayakiHighlight = null;
        document.getElementById(LAYER_ID)
            ?.querySelector('.hoshi-reader-sasayaki-highlight-layer')
            ?.replaceChildren();
        cleanupLayerIfIdle();
    }

    function rectRangesOverlap(aStart, aEnd, bStart, bEnd, tolerance) {
        return bStart <= aEnd + tolerance && bEnd >= aStart - tolerance;
    }

    function highlightRectsInlineAdjacent(a, b, verticalWriting) {
        const tolerance = HIGHLIGHT_INLINE_MERGE_TOLERANCE;
        if (verticalWriting) {
            const sameColumn = rectRangesOverlap(a.x, a.x + a.width, b.x, b.x + b.width, tolerance);
            const touches = rectRangesOverlap(a.y, a.y + a.height, b.y, b.y + b.height, tolerance);
            return sameColumn && touches;
        }
        const sameLine = rectRangesOverlap(a.y, a.y + a.height, b.y, b.y + b.height, tolerance);
        const touches = rectRangesOverlap(a.x, a.x + a.width, b.x, b.x + b.width, tolerance);
        return sameLine && touches;
    }

    function highlightUnionRect(a, b) {
        const left = Math.min(a.x, b.x);
        const top = Math.min(a.y, b.y);
        const right = Math.max(a.x + a.width, b.x + b.width);
        const bottom = Math.max(a.y + a.height, b.y + b.height);
        return { x: left, y: top, width: right - left, height: bottom - top };
    }

    function highlightBoxRects(rects, verticalWriting) {
        const merged = [];
        for (const rect of rects) {
            const previous = merged[merged.length - 1];
            if (previous && highlightRectsInlineAdjacent(previous, rect, verticalWriting)) {
                merged[merged.length - 1] = highlightUnionRect(previous, rect);
            } else {
                merged.push({ x: rect.x, y: rect.y, width: rect.width, height: rect.height });
            }
        }
        return merged;
    }

    function rootHighlightBoxRects(rects) {
        if (!rootHighlight?.eInkMode) return rects;
        return highlightBoxRects(rects, rootHighlight.verticalWriting);
    }

    function rootHighlightRectsSplit(first, second) {
        const tolerance = 8;
        if (rootHighlight.verticalWriting) {
            const sameWidth = Math.abs(first.width - second.width) <= tolerance;
            const wrapsToNextLine = first.y > second.y + tolerance;
            const touchesPageEdge = first.y + first.height >= window.innerHeight - tolerance ||
                second.y <= tolerance;
            return sameWidth && (wrapsToNextLine || touchesPageEdge);
        }
        const sameHeight = Math.abs(first.height - second.height) <= tolerance;
        const wrapsToNextLine = first.y + first.height <= second.y + tolerance &&
            first.x > second.x + tolerance;
        const touchesPageEdge = first.x + first.width >= window.innerWidth - tolerance ||
            second.x <= tolerance;
        return sameHeight && (wrapsToNextLine || touchesPageEdge);
    }

    function rootHighlightBoxEdges(rects) {
        const edges = rects.map(fullBoxEdges);
        for (let index = 0; index < rects.length - 1; index++) {
            if (!rootHighlightRectsSplit(rects[index], rects[index + 1])) continue;
            if (rootHighlight.verticalWriting) {
                edges[index].bottom = false;
                edges[index + 1].top = false;
            } else {
                edges[index].right = false;
                edges[index + 1].left = false;
            }
        }
        return edges;
    }

    function appendRootHighlightEdge(item, className, left, top, width, height, color) {
        if (width <= 0 || height <= 0) return;
        const edge = document.createElement('div');
        edge.className = `hoshi-reader-selection-highlight-edge ${className}`;
        edge.style.position = 'absolute';
        edge.style.background = color;
        edge.style.left = `${left}px`;
        edge.style.top = `${top}px`;
        edge.style.width = `${width}px`;
        edge.style.height = `${height}px`;
        item.appendChild(edge);
    }

    function applyRootHighlightBox(item, rect, color, edges) {
        const snapped = snapHighlightRect(rect);
        const lineSize = highlightLineSize();
        item.style.left = `${snapped.x}px`;
        item.style.top = `${snapped.y}px`;
        item.style.width = `${snapped.width}px`;
        item.style.height = `${snapped.height}px`;
        item.style.background = 'transparent';
        const bottomLineTop = Math.max(0, snapped.height - lineSize);
        const bottomLineBottom = bottomLineTop + lineSize;
        const rightLineLeft = Math.max(0, snapped.width - lineSize);
        const rightLineRight = rightLineLeft + lineSize;
        const inlineEnd = snapped.width;
        const blockEnd = snapped.height;
        if (edges.top) appendRootHighlightEdge(item, 'hoshi-reader-selection-highlight-edge-top', 0, 0, inlineEnd, lineSize, color);
        if (edges.right) appendRootHighlightEdge(item, 'hoshi-reader-selection-highlight-edge-right', rightLineLeft, 0, lineSize, blockEnd, color);
        if (edges.bottom) appendRootHighlightEdge(item, 'hoshi-reader-selection-highlight-edge-bottom', 0, bottomLineTop, inlineEnd, lineSize, color);
        if (edges.left) appendRootHighlightEdge(item, 'hoshi-reader-selection-highlight-edge-left', 0, 0, lineSize, blockEnd, color);
    }

    function renderRootHighlight(visible) {
        if (!visible || !rootHighlight || rootHighlight.pending || !Array.isArray(rootHighlight.rects)) {
            document.getElementById(LAYER_ID)
                ?.querySelector('.hoshi-reader-selection-highlight-layer')
                ?.replaceChildren();
            return;
        }
        const layer = ensureHighlightLayer();
        layer.replaceChildren();
        const color = rootHighlight.eInkMode
            ? (rootHighlight.darkMode ? '#fff' : '#000')
            : (rootHighlight.darkMode ? 'rgba(255, 255, 255, 0.32)' : 'rgba(160, 160, 160, 0.32)');
        const rects = rootHighlightBoxRects(
            rootHighlight.rects.filter(rect => rect && rect.width > 0 && rect.height > 0),
        );
        const boxEdges = rootHighlight.eInkMode ? rootHighlightBoxEdges(rects) : [];
        for (let index = 0; index < rects.length; index++) {
            const rect = rects[index];
            const item = document.createElement('div');
            item.className = 'hoshi-reader-selection-highlight-rect';
            if (rootHighlight.eInkMode) {
                applyRootHighlightBox(item, rect, color, boxEdges[index]);
            } else {
                item.style.background = color;
                item.style.left = `${rect.x}px`;
                item.style.top = `${rect.y}px`;
                item.style.width = `${rect.width}px`;
                item.style.height = `${rect.height}px`;
            }
            layer.appendChild(item);
        }
    }

    function syncRootReveal() {
        if (hasSasayakiHighlight()) {
            renderSasayakiHighlight(sasayakiHighlight);
        }
        const rootRecord = activeRootRecord();
        for (const record of frames.values()) {
            setRevealReady(record, !rootHighlightBlocksReveal(record));
        }
        const highlightVisible = !!rootHighlight &&
            !rootHighlight.pending &&
            Array.isArray(rootHighlight.rects) &&
            rootHighlight.rects.length > 0 &&
            (!rootRecord || (rootRecord.contentReady && rootRecord.revealReady));
        renderRootHighlight(highlightVisible);
    }

    function renderStack(payload) {
        const items = Array.isArray(payload) ? payload : (payload?.popups || []);
        rootHighlight = Array.isArray(payload) ? null : (payload?.rootHighlight || null);
        const activeIds = new Set(items.map(item => item.id));
        items.forEach((item, index) => renderPayload(item, index));
        removeMissing(activeIds);
        syncRootReveal();
    }

    function topPopupId() {
        let topId = null;
        for (const id of frames.keys()) {
            topId = id;
        }
        return topId;
    }

    function resolveMessage(popupId, id, body) {
        const record = frames.get(popupId);
        record?.iframe.contentWindow?.postMessage({ type: 'reply', id, body }, ORIGIN);
    }

    function highlightSelection(popupId, count) {
        const record = frames.get(popupId);
        record?.iframe.contentWindow?.postMessage({ type: 'highlightSelection', count }, ORIGIN);
    }

    function navigateBack(popupId) {
        const record = frames.get(popupId);
        if (!record) return;
        record.iframe.contentWindow?.postMessage({ type: 'navigateBack' }, ORIGIN);
        postNative({ name: 'navigateBack', popupId });
    }

    function navigateForward(popupId) {
        const record = frames.get(popupId);
        if (!record) return;
        record.iframe.contentWindow?.postMessage({ type: 'navigateForward' }, ORIGIN);
        postNative({ name: 'navigateForward', popupId });
    }

    function adjustSelectionBody(popupId, body) {
        const record = frames.get(popupId);
        if (!record || !body?.rect) return body;
        return {
            ...body,
            rect: {
                ...body.rect,
                x: body.rect.x + record.payload.frame.left,
                y: body.rect.y + record.payload.frame.top + frameContentTop(record.payload)
            }
        };
    }

    window.addEventListener('message', function(event) {
        if (event.origin !== ORIGIN) return;
        const data = event.data || {};
        const popupId = data.popupId || frameSources.get(event.source);
        if (data.source !== 'hoshi-popup-iframe' || !popupId) return;
        const record = frames.get(popupId);
        if (data.name === 'contentReady' && record) {
            setContentReady(record, true);
        }
        const body = data.name === 'textSelected' ? adjustSelectionBody(popupId, data.body) : data.body;
        postNative({
            name: data.name,
            id: data.id || null,
            popupId,
            body: body === undefined ? null : body
        });
    });

    if (window.__hoshiReaderPopupHostDismissTopPopupOnOutsideTap === true) {
        window.addEventListener('pointerdown', function(event) {
            if (frames.size === 0) return;
            if (event.target?.closest?.(`#${LAYER_ID} .hoshi-reader-popup-shell`)) return;
            const popupId = topPopupId();
            if (!popupId) return;
            postNative({ name: 'swipeDismiss', popupId });
        }, true);
    }

    const style = document.createElement('style');
    style.textContent = `
        #${LAYER_ID} .hoshi-reader-popup-shell {
            position: fixed;
            box-sizing: border-box;
            overflow: hidden;
            pointer-events: auto;
            writing-mode: horizontal-tb;
            direction: ltr;
            text-orientation: mixed;
            background: #fff;
            border: 1px solid rgba(120, 120, 128, 0.36);
            border-radius: 10px;
            box-shadow: 0 3px 12px rgba(0, 0, 0, 0.22);
            visibility: hidden;
            opacity: 0;
            pointer-events: none;
        }
        #${LAYER_ID} .hoshi-reader-popup-shell[data-content-ready="true"][data-reveal-ready="true"] {
            visibility: visible;
            opacity: 1;
            pointer-events: auto;
        }
        #${LAYER_ID} .hoshi-reader-selection-highlight-layer {
            position: fixed;
            inset: 0;
            pointer-events: none;
            contain: layout style paint;
        }
        #${LAYER_ID} .hoshi-reader-sasayaki-highlight-layer {
            position: fixed;
            inset: 0;
            pointer-events: none;
            contain: layout style paint;
        }
        #${LAYER_ID} .hoshi-reader-selection-highlight-rect {
            position: absolute;
            box-sizing: border-box;
            pointer-events: none;
        }
        #${LAYER_ID} .hoshi-reader-popup-shell[data-dark-mode="true"] {
            background: #000;
            border-color: rgba(255, 255, 255, 0.34);
            box-shadow: 0 3px 12px rgba(0, 0, 0, 0.44);
        }
        #${LAYER_ID} .hoshi-reader-popup-shell[data-e-ink-mode="true"] {
            border-radius: 0;
            box-shadow: none;
            border-color: #000;
        }
        #${LAYER_ID} .hoshi-reader-popup-shell[data-dark-mode="true"][data-e-ink-mode="true"] {
            border-color: #fff;
        }
        #${LAYER_ID} .hoshi-reader-popup-shell[data-popup-id="dictionary-search-root"] {
            background: transparent;
            border: 0;
            border-radius: 0;
            box-shadow: none;
        }
        #${LAYER_ID} .hoshi-reader-popup-iframe {
            position: absolute;
            box-sizing: border-box;
            left: 0;
            width: 100%;
            border: 0;
            background: transparent;
        }
        #${LAYER_ID} .hoshi-reader-popup-bar {
            box-sizing: border-box;
            width: 100%;
            writing-mode: horizontal-tb;
            direction: ltr;
            height: ${ACTION_BAR_HEIGHT}px;
            display: flex;
            align-items: center;
            padding: 0 8px;
            border-bottom: 1px solid rgba(120, 120, 128, 0.36);
            color: rgba(60, 60, 67, 0.86);
            background: inherit;
        }
        #${LAYER_ID} .hoshi-reader-popup-sasayaki-bar {
            justify-content: center;
            gap: 12px;
        }
        #${LAYER_ID} .hoshi-reader-popup-action-bar {
            gap: 12px;
        }
        #${LAYER_ID} .hoshi-reader-popup-flex-spacer {
            flex: 1 1 auto;
        }
        #${LAYER_ID} .hoshi-reader-popup-shell[data-dark-mode="true"] .hoshi-reader-popup-bar {
            color: rgba(255, 255, 255, 0.92);
            border-bottom-color: rgba(255, 255, 255, 0.34);
        }
        #${LAYER_ID} .hoshi-reader-popup-control {
            appearance: none;
            width: 34px;
            height: 34px;
            border: 0;
            border-radius: 17px;
            background: transparent;
            color: inherit;
            padding: 7px;
            -webkit-tap-highlight-color: transparent;
        }
        #${LAYER_ID} .hoshi-reader-popup-sasayaki-control {
            padding: 5px;
        }
        #${LAYER_ID} .hoshi-reader-popup-control:active {
            background: rgba(128, 128, 128, 0.18);
        }
        #${LAYER_ID} .hoshi-reader-popup-control:disabled {
            opacity: 0.34;
        }
        #${LAYER_ID} .hoshi-reader-popup-control::before {
            content: "";
            display: block;
            width: 100%;
            height: 100%;
            background: currentColor;
            -webkit-mask-image: var(--icon-url);
            mask-image: var(--icon-url);
            -webkit-mask-position: center;
            mask-position: center;
            -webkit-mask-repeat: no-repeat;
            mask-repeat: no-repeat;
            -webkit-mask-size: contain;
            mask-size: contain;
        }
    `;
    document.documentElement.appendChild(style);

    window.hoshiReaderPopupHost = {
        renderStack,
        resolveMessage,
        highlightSelection,
        navigateBack,
        navigateForward,
        renderSasayakiHighlight,
        clearSasayakiHighlight,
        preloadIdleRootFrame
    };
    if (window.__hoshiReaderPopupIframeUrl) {
        preloadIdleRootFrame(window.__hoshiReaderPopupIframeUrl);
    }
    if (window.__hoshiPendingReaderPopupStack) {
        renderStack(window.__hoshiPendingReaderPopupStack);
        window.__hoshiPendingReaderPopupStack = null;
    }
})();
