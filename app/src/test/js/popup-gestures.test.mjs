import assert from 'node:assert/strict';
import fs from 'node:fs';
import test from 'node:test';
import vm from 'node:vm';

function popupGestures({
    iframeHeight = 300,
    iframeTop = 0,
    parentViewportHeight = iframeHeight,
    scrollHeight = 3_000,
    reducedMotionScrollScale = 0.9,
} = {}) {
    const listeners = new Map();
    const scrollRoot = {
        clientHeight: iframeHeight,
        scrollHeight,
        scrollTop: 0,
    };
    const document = {
        documentElement: {
            clientHeight: iframeHeight,
        },
        body: scrollRoot,
        scrollingElement: scrollRoot,
        addEventListener(type, listener) {
            const typeListeners = listeners.get(type) ?? [];
            typeListeners.push(listener);
            listeners.set(type, typeListeners);
        },
    };
    const parent = {
        innerHeight: parentViewportHeight,
    };
    const window = {
        innerHeight: iframeHeight,
        parent,
        frameElement: {
            getBoundingClientRect() {
                return {
                    top: iframeTop,
                    bottom: iframeTop + iframeHeight,
                    height: iframeHeight,
                };
            },
        },
        reducedMotionScrolling: true,
        reducedMotionScrollScale,
        reducedMotionSwipeThreshold: 40,
        swipeThreshold: 0,
        scrollY: 0,
        scrollTo(_x, y) {
            this.scrollY = y;
        },
    };
    const script = fs.readFileSync(
        new URL('../../main/assets/hoshi-web/popup/popup-gestures.js', import.meta.url),
        'utf8',
    );
    vm.runInNewContext(script, { console, document, Math, window });
    return {
        scrollRoot,
        window,
        dispatch(type, event) {
            (listeners.get(type) ?? []).forEach((listener) => listener(event));
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

test('reduced motion uses the iframe height visible inside the parent viewport', () => {
    const { dispatch, scrollRoot, window } = popupGestures({
        iframeHeight: 1_000,
        parentViewportHeight: 800,
    });

    dispatch('wheel', {
        deltaY: 1,
        preventDefault() {},
    });

    assert.equal(scrollRoot.scrollTop, 720);
    assert.equal(window.scrollY, 720);
});
