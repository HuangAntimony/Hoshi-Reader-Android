(function() {
    if (window.reducedMotionScrolling) {
        var reducedMotionStartY = 0;
        var scrollByPopupHeight = function(direction) {
            window.hoshiPopupGeometry?.scrollByViewport(direction, window.reducedMotionScrollScale);
        };
        document.addEventListener('touchstart', function(e) {
            if (e.touches.length === 1) {
                reducedMotionStartY = e.touches[0].clientY;
            }
        }, { passive: true });
        document.addEventListener('touchmove', function(e) {
            if (e.touches.length === 1 && e.cancelable) {
                e.preventDefault();
            }
        }, { passive: false });
        document.addEventListener('touchend', function(e) {
            if (!e.changedTouches.length) return;
            var delta = reducedMotionStartY - e.changedTouches[0].clientY;
            var threshold = window.reducedMotionSwipeThreshold;
            if (delta > threshold) {
                scrollByPopupHeight(1);
            } else if (delta < -threshold) {
                scrollByPopupHeight(-1);
            }
        }, { passive: true });
        document.addEventListener('wheel', function(e) {
            if (e.deltaY === 0) return;
            scrollByPopupHeight(e.deltaY > 0 ? 1 : -1);
            e.preventDefault();
        }, { passive: false });
    }
    if (!window.swipeThreshold) {
        return;
    }
    var startX, startY;
    document.addEventListener('touchstart', function(e) {
        startX = e.touches[0].clientX;
        startY = e.touches[0].clientY;
    });
    document.addEventListener('touchend', function(e) {
        var dx = e.changedTouches[0].clientX - startX;
        var dy = e.changedTouches[0].clientY - startY;
        var absDx = Math.abs(dx);
        var absDy = Math.abs(dy);
        var isHorizontalDismiss = absDx > window.swipeThreshold && absDx > absDy * 1.75;
        var hasSelection = window.getSelection().toString();
        if (isHorizontalDismiss && !hasSelection) {
            webkit.messageHandlers.swipeDismiss.postMessage(null);
        }
    });
})();
