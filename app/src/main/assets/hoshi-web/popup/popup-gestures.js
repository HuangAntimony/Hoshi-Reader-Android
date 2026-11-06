(function() {
    if (!window.reducedMotionScrolling && !window.swipeThreshold) return;

    // Touch Events stay in the document where the first active touch began.
    // A retained reader touch can therefore send popup touches to the parent
    // iframe element. Pointer Events still target this popup independently.
    // Set the iframe's action too, since Chromium can calculate it there.
    const touchAction = window.reducedMotionScrolling ? 'none' : 'pan-y';
    document.documentElement.style.touchAction = touchAction;
    if (window.frameElement) window.frameElement.style.touchAction = touchAction;

    if (window.reducedMotionScrolling) {
        document.addEventListener('wheel', function(e) {
            if (e.deltaY === 0) return;
            window.hoshiPopupGeometry?.scrollByViewport(
                e.deltaY > 0 ? 1 : -1, window.reducedMotionScrollScale);
            e.preventDefault();
        }, { passive: false });
    }

    const pointers = new Set();
    let start = null;
    document.addEventListener('pointerdown', function(e) {
        if (e.pointerType !== 'touch' && e.pointerType !== 'pen') return;
        pointers.add(e.pointerId);
        if (pointers.size !== 1) start = null;
    }, { capture: true, passive: true });
    document.addEventListener('pointerdown', function(e) {
        // Do not require isPrimary: a retained touch in the reader can own that
        // flag while this is the only pointer interacting with the popup.
        // Menus can stop bubbling to opt out of starting a gesture, while their
        // pointers still count above when another finger is already down.
        if (pointers.size === 1 && pointers.has(e.pointerId)) {
            start = { id: e.pointerId, x: e.clientX, y: e.clientY };
        }
    }, { passive: true });
    document.addEventListener('pointercancel', function(e) {
        if (pointers.delete(e.pointerId)) start = null;
    }, { capture: true, passive: true });
    document.addEventListener('pointerup', function(e) {
        pointers.delete(e.pointerId);
        if (!start || start.id !== e.pointerId) return;
        const origin = start;
        start = null;
        if (pointers.size) return;

        const dx = e.clientX - origin.x;
        const dy = e.clientY - origin.y;
        if (window.reducedMotionScrolling && Math.abs(dy) > window.reducedMotionSwipeThreshold) {
            window.hoshiPopupGeometry?.scrollByViewport(
                dy < 0 ? 1 : -1, window.reducedMotionScrollScale);
        }
        const isHorizontalDismiss = window.swipeThreshold &&
            Math.abs(dx) > window.swipeThreshold && Math.abs(dx) > Math.abs(dy) * 1.75;
        if (isHorizontalDismiss && !window.getSelection().toString()) {
            webkit.messageHandlers.swipeDismiss.postMessage(null);
        }
    }, { capture: true, passive: true });
})();
