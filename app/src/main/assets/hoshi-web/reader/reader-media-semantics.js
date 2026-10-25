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

  function setImageReferenceCounts(counts) {
    imageReferenceCounts = counts || {};
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
      markTextColorImage(img);
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
    shouldHoldMedia: shouldHoldMedia,
    setupReaderImage: setupReaderImage,
    setupReaderImages: setupReaderImages
  };
})(window);
