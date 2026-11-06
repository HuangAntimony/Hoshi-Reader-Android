import assert from 'node:assert/strict';
import fs from 'node:fs';
import test from 'node:test';
import vm from 'node:vm';

function popupGestures({
    iframeHeight = 300,
    scrollHeight = 3_000,
    reducedMotionScrollScale = 0.9,
    reducedMotionScrolling = true,
    swipeThreshold = 0,
    selection = '',
} = {}) {
    const listeners = new Map();
    const scrollRoot = {
        clientHeight: iframeHeight,
        scrollHeight,
        scrollTop: 0,
        style: {},
    };
    const document = {
        documentElement: {
            clientHeight: iframeHeight,
            style: {},
        },
        body: scrollRoot,
        scrollingElement: scrollRoot,
        addEventListener(type, listener, options) {
            const typeListeners = listeners.get(type) ?? [];
            typeListeners.push({ listener, capture: !!options?.capture });
            listeners.set(type, typeListeners);
        },
    };
    const window = {
        innerHeight: iframeHeight,
        reducedMotionScrolling,
        reducedMotionScrollScale,
        reducedMotionSwipeThreshold: 40,
        swipeThreshold,
        frameElement: { style: {} },
        getSelection: () => ({ toString: () => selection }),
        scrollY: 0,
        scrollTo(_x, y) {
            this.scrollY = y;
        },
    };
    let dismissals = 0;
    const webkit = { messageHandlers: { swipeDismiss: { postMessage() { dismissals++; } } } };
    window.hoshiPopupGeometry = {
        scrollByViewport(direction, scale) {
            const maxScroll = Math.max(0, scrollRoot.scrollHeight - iframeHeight);
            const target = Math.max(0, Math.min(
                maxScroll,
                scrollRoot.scrollTop + iframeHeight * scale * direction,
            ));
            scrollRoot.scrollTop = target;
            window.scrollTo(0, target);
        },
    };
    const script = fs.readFileSync(
        new URL('../../main/assets/hoshi-web/popup/popup-gestures.js', import.meta.url),
        'utf8',
    );
    vm.runInNewContext(script, { console, document, Math, window, webkit });
    return {
        document,
        scrollRoot,
        window,
        get dismissals() { return dismissals; },
        dispatch(type, event, { bubble = true } = {}) {
            const rows = listeners.get(type) ?? [];
            rows.filter(row => row.capture).forEach(row => row.listener(event));
            if (bubble) rows.filter(row => !row.capture).forEach(row => row.listener(event));
        },
    };
}

test('reduced motion scrolls by the configured percentage when the iframe is fully visible', () => {
    const { dispatch, scrollRoot, window } = popupGestures();

    dispatch('wheel', {
        deltaY: 1,
        preventDefault() {},
    });

    assert.equal(scrollRoot.scrollTop, 270);
    assert.equal(window.scrollY, 270);
});

function pointer(pointerId, clientX, clientY, extra = {}) {
    return { pointerType: 'touch', pointerId, clientX, clientY, ...extra };
}

test('pages with a non-primary popup pointer when Touch Events stay in the parent document', () => {
    const { dispatch, scrollRoot, window } = popupGestures();

    // A retained parent touch makes this pointer non-primary. No Touch Events
    // reach the popup; Chromium still sends its Pointer Events to this document.
    dispatch('pointerdown', pointer(7, 50, 200, { isPrimary: false }));
    dispatch('pointerup', pointer(7, 50, 100, { isPrimary: false }));

    assert.equal(scrollRoot.scrollTop, 270);
    assert.equal(window.scrollY, 270);
});

test('horizontal popup pointers dismiss once without requiring Touch Events', () => {
    const gestures = popupGestures({ swipeThreshold: 30 });
    gestures.dispatch('pointerdown', pointer(7, 20, 200));
    gestures.dispatch('pointerup', pointer(7, 120, 190));

    assert.equal(gestures.dismissals, 1);
    assert.equal(gestures.scrollRoot.scrollTop, 0);
});

test('a cancelled pointer cannot page or dismiss and the next gesture still works', () => {
    const gestures = popupGestures({ swipeThreshold: 30 });
    gestures.dispatch('pointerdown', pointer(7, 20, 200));
    gestures.dispatch('pointercancel', pointer(7, 20, 100));
    gestures.dispatch('pointerup', pointer(7, 120, 100));
    assert.equal(gestures.dismissals, 0);
    assert.equal(gestures.scrollRoot.scrollTop, 0);

    gestures.dispatch('pointerdown', pointer(8, 20, 200));
    gestures.dispatch('pointerup', pointer(8, 20, 100));
    assert.equal(gestures.scrollRoot.scrollTop, 270);
});

test('another pointer cannot finish the current gesture', () => {
    const { dispatch, scrollRoot } = popupGestures();
    dispatch('pointerdown', pointer(7, 20, 200));
    dispatch('pointerup', pointer(8, 20, 100));
    assert.equal(scrollRoot.scrollTop, 0);
    dispatch('pointerup', pointer(7, 20, 100));
    assert.equal(scrollRoot.scrollTop, 270);
});

test('multiple popup fingers suppress the gesture until all fingers are released', () => {
    const gestures = popupGestures({ swipeThreshold: 30 });
    gestures.dispatch('pointerdown', pointer(7, 20, 200));
    gestures.dispatch('pointerdown', pointer(8, 40, 200));
    gestures.dispatch('pointerup', pointer(7, 120, 100));
    gestures.dispatch('pointerup', pointer(8, 140, 100));
    assert.equal(gestures.scrollRoot.scrollTop, 0);
    assert.equal(gestures.dismissals, 0);

    gestures.dispatch('pointerdown', pointer(9, 20, 200));
    gestures.dispatch('pointerup', pointer(9, 20, 100));
    assert.equal(gestures.scrollRoot.scrollTop, 270);
});

test('touch action blocks continuous scrolling in both the popup and its embedding frame', () => {
    const { document, window } = popupGestures();
    assert.equal(document.documentElement.style.touchAction, 'none');
    assert.equal(window.frameElement.style.touchAction, 'none');
});

test('a second finger in a menu suppresses gestures even when the menu stops pointerdown bubbling', () => {
    for (const [x, y] of [[120, 190], [20, 100]]) {
        const gestures = popupGestures({ swipeThreshold: 30 });
        gestures.dispatch('pointerdown', pointer(7, 20, 200));
        gestures.dispatch('pointerdown', pointer(8, 40, 200), { bubble: false });
        gestures.dispatch('pointerup', pointer(7, x, y));
        gestures.dispatch('pointerup', pointer(8, 40, 200), { bubble: false });
        assert.equal(gestures.dismissals, 0);
        assert.equal(gestures.scrollRoot.scrollTop, 0);

        gestures.dispatch('pointerdown', pointer(9, 20, 200));
        gestures.dispatch('pointerup', pointer(9, 20, 100));
        assert.equal(gestures.scrollRoot.scrollTop, 270);
    }
});

test('a menu that stops pointerdown bubbling does not start a popup gesture', () => {
    const gestures = popupGestures({ swipeThreshold: 30 });
    gestures.dispatch('pointerdown', pointer(7, 20, 200), { bubble: false });
    gestures.dispatch('pointerup', pointer(7, 120, 100));
    assert.equal(gestures.dismissals, 0);
    assert.equal(gestures.scrollRoot.scrollTop, 0);
});

test('ordinary scrolling keeps native vertical panning and horizontal dismissal', () => {
    const gestures = popupGestures({ reducedMotionScrolling: false, swipeThreshold: 30 });
    assert.equal(gestures.document.documentElement.style.touchAction, 'pan-y');
    assert.equal(gestures.window.frameElement.style.touchAction, 'pan-y');
    gestures.dispatch('pointerdown', pointer(7, 20, 200));
    gestures.dispatch('pointerup', pointer(7, 20, 100));
    assert.equal(gestures.scrollRoot.scrollTop, 0);
    gestures.dispatch('pointerdown', pointer(8, 20, 200));
    gestures.dispatch('pointerup', pointer(8, 120, 190));
    assert.equal(gestures.dismissals, 1);
});

test('text selection and mouse dragging do not dismiss a popup', () => {
    const selected = popupGestures({ swipeThreshold: 30, selection: 'word' });
    selected.dispatch('pointerdown', pointer(7, 20, 200));
    selected.dispatch('pointerup', pointer(7, 120, 190));
    assert.equal(selected.dismissals, 0);

    const mouse = popupGestures({ swipeThreshold: 30 });
    mouse.dispatch('pointerdown', pointer(1, 20, 200, { pointerType: 'mouse' }));
    mouse.dispatch('pointerup', pointer(1, 120, 190, { pointerType: 'mouse' }));
    assert.equal(mouse.dismissals, 0);
});
