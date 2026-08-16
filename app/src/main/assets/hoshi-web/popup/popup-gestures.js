(function() {
    if (window.reducedMotionScrolling) {
        var reducedMotionStartY = 0;
        var root = function() {
            return document.scrollingElement || document.documentElement || document.body;
        };
        var scrollByPopupHeight = function(direction) {
            var scrollRoot = root();
            var popupHeight = document.documentElement.clientHeight || window.innerHeight || scrollRoot.clientHeight;
            var maxScroll = Math.max(0, scrollRoot.scrollHeight - popupHeight);
            var current = scrollRoot.scrollTop || window.scrollY || 0;
            var target = Math.max(0, Math.min(maxScroll, current + popupHeight * window.reducedMotionScrollScale * direction));
            scrollRoot.scrollTop = target;
            window.scrollTo(0, target);
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
