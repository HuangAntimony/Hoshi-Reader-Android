(function(global) {
  'use strict';

  function documentForNode(node) {
    return (node && node.ownerDocument) || global.document || (typeof document !== 'undefined' ? document : null);
  }

  function isGaijiImage(img) {
    return !!(img && img.classList && (img.classList.contains('gaiji') || img.classList.contains('gaiji-line')));
  }

  function isLargeImage(img) {
    return Number(img && img.naturalWidth || 0) > 256 || Number(img && img.naturalHeight || 0) > 256;
  }

  function imageSource(img) {
    return (img && (img.currentSrc || img.src || (img.getAttribute && img.getAttribute('src')))) || '';
  }

  function svgImageSource(svgImage) {
    if (!svgImage) return '';
    return svgImage.href && svgImage.href.baseVal
      ? svgImage.href.baseVal
      : ((svgImage.getAttribute && (svgImage.getAttribute('href') || svgImage.getAttribute('xlink:href'))) || '');
  }

  function postImageBridge(src, imageBridge, doc) {
    var bridge = imageBridge || global.HoshiReaderImage;
    if (bridge && bridge.postMessage) {
      bridge.postMessage(new URL(src, doc && doc.baseURI ? doc.baseURI : undefined).href);
    }
  }

  function setupReaderImage(element, src, options) {
    options = options || {};
    if (!element || !src || element.hoshiReaderImageSetup) return;
    element.hoshiReaderImageSetup = true;
    var blurElement = options.blurElement || element;
    if (options.blurImages) {
      blurElement.classList.add('blurred');
      if (options.wrap && !(blurElement.parentElement && blurElement.parentElement.classList.contains('blur-wrapper'))) {
        var doc = documentForNode(blurElement);
        if (doc && doc.createElement && blurElement.parentNode) {
          var target = doc.createElement('span');
          target.className = 'blur-wrapper';
          blurElement.parentNode.insertBefore(target, blurElement);
          target.appendChild(blurElement);
        }
      }
    }
    element.addEventListener('click', function(event) {
      event.preventDefault();
      event.stopPropagation();
      if (blurElement.classList.contains('blurred')) {
        blurElement.classList.remove('blurred');
        return;
      }
      postImageBridge(src, options.imageBridge, documentForNode(element));
    });
  }

  function setupSvgImages(scope, options) {
    var svgImages = Array.from(scope.querySelectorAll ? scope.querySelectorAll('svg image') : []);
    svgImages.forEach(function(svgImage) {
      var svg = svgImage.closest && svgImage.closest('svg');
      if (!svg) return;
      if (svg.getAttribute('preserveAspectRatio') === 'none') {
        svg.setAttribute('preserveAspectRatio', 'xMidYMid meet');
      }
      setupReaderImage(svgImage, svgImageSource(svgImage), {
        blurImages: options.blurImages,
        imageBridge: options.imageBridge,
        wrap: false,
        blurElement: svg
      });
    });
  }

  function setupImage(img, options, resolve) {
    var mark = function() {
      if (!isGaijiImage(img) && isLargeImage(img)) {
        img.classList.add('block-img');
        setupReaderImage(img, imageSource(img), {
          blurImages: options.blurImages,
          imageBridge: options.imageBridge,
          wrap: true
        });
      }
      if (resolve) resolve();
    };
    if (img.complete) {
      if ((Number(img.naturalWidth) || 0) > 0) {
        mark();
      } else if (resolve) {
        resolve();
      }
      return;
    }
    img.onload = mark;
    if (resolve) {
      img.onerror = function() { resolve(); };
    }
  }

  function setupReaderImages(scope, options) {
    options = options || {};
    scope = scope || global.document || (typeof document !== 'undefined' ? document : null);
    if (!scope || !scope.querySelectorAll) return Promise.resolve();
    setupSvgImages(scope, options);
    var images = Array.from(scope.querySelectorAll('img'));
    var waitForImages = options.waitForImages !== false;
    if (!waitForImages) {
      images.forEach(function(img) { setupImage(img, options, null); });
      return Promise.resolve();
    }
    return Promise.all(images.map(function(img) {
      return new Promise(function(resolve) {
        setupImage(img, options, resolve);
      });
    }));
  }

  global.hoshiReaderMediaSemantics = {
    setupReaderImage: setupReaderImage,
    setupReaderImages: setupReaderImages
  };
})(window);
