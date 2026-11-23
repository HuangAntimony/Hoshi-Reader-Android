(function(global) {
  'use strict';

  var SVG_NAMESPACE = 'http://www.w3.org/2000/svg';
  var GAIJI_TEXT_COLOR_FILTER_ID = 'hoshi-gaiji-text-color-filter';
  var TEXT_COLOR_IMAGE_CLASS = 'hoshi-text-color-image';
  var MAX_COLOR_SAMPLE_PIXELS = 262144;

  function documentForNode(node) {
    return (node && node.ownerDocument) || global.document || (typeof document !== 'undefined' ? document : null);
  }

  function isGaijiImage(img) {
    if (!img) return false;
    var className = img.getAttribute ? img.getAttribute('class') : '';
    if (!className) className = img.className;
    return String(className || '')
      .split(/\s+/)
      .some(function(token) { return token.toLowerCase().indexOf('gaiji') >= 0; });
  }

  var imageReferenceCounts = global.hoshiReaderImageReferenceCounts || {};
  var imageReferenceCountsPromise;

  function setImageReferenceCounts(counts) {
    imageReferenceCounts = counts || {};
  }

  function loadImageReferenceCounts() {
    if (!imageReferenceCountsPromise) {
      imageReferenceCountsPromise = global.hoshiReaderImageReferenceCounts || typeof global.fetch !== 'function'
        ? Promise.resolve()
        : global.fetch('https://appassets.androidplatform.net/reader-media/image-references.json')
          .then(function(response) { return response.ok ? response.json() : null; })
          .then(setImageReferenceCounts)
          .catch(function() { /* Unknown counts preserve ordinary image holds. */ });
    }
    return imageReferenceCountsPromise;
  }

  function shouldHoldMedia(element) {
    if (!element) return true;
    var tag = String(element.tagName || '').toLowerCase();
    if (tag === 'img' && isGaijiImage(element)) return false;
    if (tag === 'picture' || tag === 'svg') {
      var descendants = Array.from(element.querySelectorAll ? element.querySelectorAll('*') : []);
      if (tag === 'svg' && descendants.some(function(child) {
        return ['path', 'rect', 'circle', 'ellipse', 'line', 'polyline', 'polygon', 'text', 'use', 'foreignobject'].indexOf(String(child.tagName || '').toLowerCase()) >= 0;
      })) return true;
      var images = descendants.filter(function(child) {
        return ['img', 'image'].indexOf(String(child.tagName || '').toLowerCase()) >= 0;
      });
      return !images.length || images.some(shouldHoldMedia);
    }
    if (tag !== 'img' && tag !== 'image') return true;
    var source = tag === 'img' ? imageSource(element) : svgImageSource(element);
    if (!source) return true;
    try {
      var doc = documentForNode(element);
      var url = new URL(source, doc && doc.baseURI);
      if (url.origin !== 'https://appassets.androidplatform.net' || url.pathname.indexOf('/epub/') !== 0) return true;
      var path = decodeURIComponent(url.pathname.slice('/epub/'.length));
      return !(Number(imageReferenceCounts[path]) >= 3);
    } catch (_error) {
      return true;
    }
  }

  function isLargeImage(img) {
    return Number(img && img.naturalWidth || 0) > 256 || Number(img && img.naturalHeight || 0) > 256;
  }

  function appendSvgElement(parent, tagName, attributes) {
    var element = parent.ownerDocument.createElementNS(SVG_NAMESPACE, tagName);
    Object.keys(attributes || {}).forEach(function(name) {
      element.setAttribute(name, attributes[name]);
    });
    parent.appendChild(element);
    return element;
  }

  function ensureGaijiTextColorFilter(doc) {
    if (!doc || !doc.documentElement || !doc.createElementNS) return;
    if (doc.getElementById && doc.getElementById(GAIJI_TEXT_COLOR_FILTER_ID)) return;

    var svg = doc.createElementNS(SVG_NAMESPACE, 'svg');
    svg.setAttribute('width', '0');
    svg.setAttribute('height', '0');
    svg.setAttribute('aria-hidden', 'true');
    svg.setAttribute('focusable', 'false');
    svg.setAttribute(
      'style',
      'position: absolute !important; width: 0 !important; height: 0 !important; ' +
        'overflow: hidden !important; pointer-events: none !important'
    );

    var filter = appendSvgElement(svg, 'filter', {
      id: GAIJI_TEXT_COLOR_FILTER_ID,
      x: '-10%',
      y: '-10%',
      width: '120%',
      height: '120%',
      'color-interpolation-filters': 'sRGB'
    });
    appendSvgElement(filter, 'feColorMatrix', {
      'in': 'SourceGraphic',
      result: 'inverseLuminance',
      type: 'matrix',
      values: '0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 -0.2126 -0.7152 -0.0722 0 1'
    });
    appendSvgElement(filter, 'feComposite', {
      'in': 'inverseLuminance',
      in2: 'SourceAlpha',
      operator: 'in',
      result: 'glyphMask'
    });
    var componentTransfer = appendSvgElement(filter, 'feComponentTransfer', {
      'in': 'glyphMask',
      result: 'solidStrokeMask'
    });
    appendSvgElement(componentTransfer, 'feFuncA', {
      type: 'linear',
      slope: '1.1'
    });
    appendSvgElement(filter, 'feFlood', {
      result: 'textColor',
      style: 'flood-color: var(--hoshi-text-color)'
    });
    appendSvgElement(filter, 'feComposite', {
      'in': 'textColor',
      in2: 'solidStrokeMask',
      operator: 'in'
    });
    doc.documentElement.appendChild(svg);
  }

  function isEmbeddedInText(img) {
    if (!img || !img.closest) return false;
    var container = img.closest('p, li, dt, dd, blockquote');
    return !!(container && /\S/.test(String(container.textContent || '')));
  }

  function isTransparentMonochromeImage(img) {
    if (!isEmbeddedInText(img)) return false;
    var width = Number(img.naturalWidth) || 0;
    var height = Number(img.naturalHeight) || 0;
    var doc = documentForNode(img);
    if (!width || !height || !doc || !doc.createElement) return false;

    try {
      var scale = Math.min(1, Math.sqrt(MAX_COLOR_SAMPLE_PIXELS / (width * height)));
      var canvas = doc.createElement('canvas');
      canvas.width = Math.max(1, Math.round(width * scale));
      canvas.height = Math.max(1, Math.round(height * scale));
      var context = canvas.getContext && canvas.getContext('2d', { willReadFrequently: true });
      if (!context) return false;
      context.drawImage(img, 0, 0, canvas.width, canvas.height);
      var pixels = context.getImageData(0, 0, canvas.width, canvas.height).data;
      var pixelCount = canvas.width * canvas.height;
      var visibleCount = 0;
      var transparentCount = 0;
      for (var index = 0; index < pixels.length; index += 4) {
        var alpha = pixels[index + 3];
        if (alpha <= 16) {
          transparentCount += 1;
          continue;
        }
        visibleCount += 1;
        var red = pixels[index];
        var green = pixels[index + 1];
        var blue = pixels[index + 2];
        if (Math.max(red, green, blue) - Math.min(red, green, blue) > 12) return false;
      }
      return visibleCount > 0 && transparentCount >= Math.ceil(pixelCount * 0.05);
    } catch (_error) {
      return false;
    }
  }

  function markTextColorImage(img) {
    if (isGaijiImage(img) || isTransparentMonochromeImage(img)) {
      img.classList.add(TEXT_COLOR_IMAGE_CLASS);
    }
  }

  function replaceFailedGaiji(img) {
    if (!isGaijiImage(img) || !img.parentNode) return;
    var doc = documentForNode(img);
    if (!doc || !doc.createElement) return;
    var alt = (img.getAttribute && img.getAttribute('alt')) || '';
    if (!alt.trim()) return;
    var fallback = doc.createElement('span');
    fallback.className = 'hoshi-gaiji-fallback';
    if (Array.from(alt.trim()).length === 1) {
      fallback.classList.add('hoshi-gaiji-fallback-single');
    }
    fallback.setAttribute('data-hoshi-gaiji-alt', alt);
    img.parentNode.insertBefore(fallback, img);
    img.parentNode.removeChild(img);
  }

  function imageSource(img) {
    return (img && (img.currentSrc || img.src || (img.getAttribute && img.getAttribute('src')))) || '';
  }

  function svgImageSource(svgImage) {
    if (!svgImage) return '';
    return svgImage.href && svgImage.href.baseVal
      ? svgImage.href.baseVal
      : ((svgImage.getAttribute && (svgImage.getAttribute('href') || svgImage.getAttribute('xlink:href') || svgImage.getAttribute('data-hoshi-image-href'))) || '');
  }

  function postImageBridge(src, imageBridge, doc) {
    var bridge = imageBridge || global.HoshiReaderImage;
    if (bridge && bridge.postMessage) {
      bridge.postMessage(new URL(src, doc && doc.baseURI ? doc.baseURI : undefined).href);
    }
  }

  var liveBlurTargets;
  var liveBlurEnabled;

  function applyImageBlur(element, wrap, enabled) {
    if (!enabled) {
      element.classList.remove('blurred');
      var wrapper = element.hoshiReaderBlurWrapper;
      if (wrapper && wrapper.parentNode && element.parentNode === wrapper) {
        wrapper.parentNode.insertBefore(element, wrapper);
        wrapper.parentNode.removeChild(wrapper);
        element.hoshiReaderBlurWrapper = null;
      }
      return;
    }
    element.classList.add('blurred');
    if (wrap && element.parentNode && !element.parentElement?.classList.contains('blur-wrapper')) {
      var wrapper = documentForNode(element).createElement('span');
      wrapper.className = 'blur-wrapper';
      element.parentNode.insertBefore(wrapper, element);
      wrapper.appendChild(element);
      element.hoshiReaderBlurWrapper = wrapper;
    }
  }

  function setImageBlur(enabled) {
    if (!liveBlurTargets || liveBlurEnabled === enabled) return;
    liveBlurEnabled = enabled;
    liveBlurTargets.forEach(function(wrap, element) { applyImageBlur(element, wrap, enabled); });
  }

  function setupReaderImage(element, src, options) {
    options = options || {};
    if (!element || !src || element.hoshiReaderImageSetup) return;
    element.hoshiReaderImageSetup = true;
    var blurElement = options.blurElement || element;
    if (liveBlurTargets) {
      // Several <image> nodes may share one SVG. Register its visual state only once.
      if (!liveBlurTargets.has(blurElement)) {
        liveBlurTargets.set(blurElement, options.wrap);
        if (liveBlurEnabled) applyImageBlur(blurElement, options.wrap, true);
      }
    } else if (options.blurImages) {
      applyImageBlur(blurElement, options.wrap, true);
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

  // SVG image elements lack HTML's loading attribute. Observe the sized SVG viewport,
  // which also works when the unloaded raster has no bounds of its own.
  var svgObserver;
  var pendingBookSvg = new Set();

  function loadSvgImages(svg) {
    svg.querySelectorAll('image[data-hoshi-image-href]').forEach(function(image) {
      image.setAttribute('href', image.getAttribute('data-hoshi-image-href'));
      image.removeAttribute('data-hoshi-image-href');
    });
  }

  function deferSvgImages(svg) {
    if (!global.IntersectionObserver) {
      loadSvgImages(svg);
      return;
    }
    if (!svgObserver) {
      svgObserver = new global.IntersectionObserver(function(entries) {
        entries.forEach(function(entry) {
          if (!entry.isIntersecting) return;
          loadSvgImages(entry.target);
          svgObserver.unobserve(entry.target);
        });
      }, { root: documentForNode(svg).body, rootMargin: '100%' });
    }
    svgObserver.observe(svg);
  }

  function setupSvgImages(scope, options) {
    var svgImages = Array.from(scope.querySelectorAll ? scope.querySelectorAll('svg image') : []);
    svgImages.forEach(function(svgImage) {
      var svg = svgImage.closest && svgImage.closest('svg');
      if (!svg) return;
      if (svgImage.getAttribute('data-hoshi-image-href')) pendingBookSvg.add(svg);
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

  function observeBookImagesAtReadingPosition() {
    pendingBookSvg.forEach(deferSvgImages);
    pendingBookSvg.clear();
  }

  function setupImage(img, options, resolve) {
    var setupIllustration = function() {
      img.classList.add('block-img');
      setupReaderImage(img, imageSource(img), {
        blurImages: options.blurImages,
        imageBridge: options.imageBridge,
        wrap: true
      });
    };
    if (img.hasAttribute?.('data-hoshi-sized-art')) setupIllustration();
    var mark = function() {
      markTextColorImage(img);
      if (!isGaijiImage(img) && isLargeImage(img)) setupIllustration();
      if (resolve) resolve();
    };
    var fail = function() {
      replaceFailedGaiji(img);
      if (resolve) resolve();
    };
    if (img.complete) {
      if ((Number(img.naturalWidth) || 0) > 0) {
        mark();
      } else {
        fail();
      }
      return;
    }
    img.onload = mark;
    img.onerror = fail;
  }

  function setupReaderImages(scope, options) {
    options = options || {};
    scope = scope || global.document || (typeof document !== 'undefined' ? document : null);
    if (!scope || !scope.querySelectorAll) return Promise.resolve();
    if (options.liveBlur && !liveBlurTargets) {
      liveBlurTargets = new Map();
      liveBlurEnabled = !!options.blurImages;
    }
    ensureGaijiTextColorFilter(documentForNode(scope));
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
    isGaijiImage: isGaijiImage,
    setImageReferenceCounts: setImageReferenceCounts,
    loadImageReferenceCounts: loadImageReferenceCounts,
    shouldHoldMedia: shouldHoldMedia,
    setImageBlur: setImageBlur,
    setupReaderImage: setupReaderImage,
    observeBookImagesAtReadingPosition: observeBookImagesAtReadingPosition,
    setupReaderImages: setupReaderImages
  };
})(window);
