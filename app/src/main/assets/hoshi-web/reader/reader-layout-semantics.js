(function(global) {
  'use strict';

  function pixelValue(value) {
    var parsed = parseFloat(value);
    return Number.isFinite(parsed) ? parsed : 0;
  }

  function hasGeneratedContent(style) {
    var content = style && style.content;
    return !!content && content !== 'none' && content !== 'normal' && content !== '""' && content !== "''";
  }

  function isMeaningfulEmptySpan(element) {
    if (element.hasAttribute && (element.hasAttribute('id') || element.hasAttribute('name'))) {
      return true;
    }
    var style = global.getComputedStyle(element);
    if (style.backgroundImage && style.backgroundImage !== 'none') {
      return true;
    }
    return hasGeneratedContent(global.getComputedStyle(element, '::before'))
      || hasGeneratedContent(global.getComputedStyle(element, '::after'));
  }

  function sanitizeInlineBlocks(scope, vertical) {
    var root = scope || global.document;
    var viewport = root && (root.body || root);
    if (!viewport || !root.querySelectorAll || !global.getComputedStyle) return;

    var bodyStyle = global.getComputedStyle(viewport);
    var blockExtent = vertical
      ? viewport.clientWidth - pixelValue(bodyStyle.paddingLeft) - pixelValue(bodyStyle.paddingRight)
      : viewport.clientHeight - pixelValue(bodyStyle.paddingTop) - pixelValue(bodyStyle.paddingBottom);
    var inlineExtent = vertical
      ? viewport.clientHeight - pixelValue(bodyStyle.paddingTop) - pixelValue(bodyStyle.paddingBottom)
      : viewport.clientWidth - pixelValue(bodyStyle.paddingLeft) - pixelValue(bodyStyle.paddingRight);

    function blockSize(element) {
      var rect = element.getBoundingClientRect();
      return vertical ? rect.width : rect.height;
    }

    function inlineSize(element) {
      var rect = element.getBoundingClientRect();
      return vertical ? rect.height : rect.width;
    }

    var imageOnlyParagraphs = new Set();
    Array.from(root.querySelectorAll('p')).forEach(function(paragraph) {
      if ((paragraph.textContent || '').trim()) return;
      var images = Array.from(paragraph.querySelectorAll('img, svg'));
      if (images.length !== 1) return;
      var image = images[0];
      if (String(image.tagName).toLowerCase() === 'img') {
        var classes = image.getAttribute('class') || '';
        if (/gaiji/i.test(classes)) return;
        // VN sanitizes its source before media setup marks the displayed clone.
        if (!/(^|\s)block-img(\s|$)/.test(classes)
          && !(image.naturalWidth > 256 || image.naturalHeight > 256)) return;
      }
      var descendants = [paragraph].concat(Array.from(paragraph.querySelectorAll('*')));
      if (descendants.some(function(element) {
        return hasGeneratedContent(global.getComputedStyle(element, '::before'))
          || hasGeneratedContent(global.getComputedStyle(element, '::after'));
      })) return;
      imageOnlyParagraphs.add(paragraph);
      var imageWrapper = image.parentNode;
      while (imageWrapper && imageWrapper !== paragraph) {
        if (global.getComputedStyle(imageWrapper).display === 'inline-block'
          && inlineSize(imageWrapper) + 1 < inlineSize(image)) {
          imageWrapper.style.setProperty(vertical ? 'height' : 'width', 'auto', 'important');
        }
        imageWrapper = imageWrapper.parentNode;
      }
      var style = global.getComputedStyle(paragraph);
      var startMargin = pixelValue(vertical ? style.marginLeft : style.marginTop);
      var endMargin = pixelValue(vertical ? style.marginRight : style.marginBottom);
      if (startMargin + endMargin <= 0) return;
      if (blockSize(image) + startMargin + endMargin <= blockExtent + 1) return;
      paragraph.style.setProperty(vertical ? 'margin-left' : 'margin-top', '0px', 'important');
      paragraph.style.setProperty(vertical ? 'margin-right' : 'margin-bottom', '0px', 'important');
    });

    Array.from(root.querySelectorAll('div, span')).forEach(function(element) {
      var paragraph = element.querySelector('p');
      if (global.getComputedStyle(element).display !== 'inline-block' || !paragraph) {
        return;
      }
      // A shrink-wrapped pure-image paragraph otherwise centers inside the image's
      // own height rather than the reader line. Keep its nodes and links intact.
      var imageOnlyWrapper = imageOnlyParagraphs.has(paragraph)
        && element.children.length === 1 && element.children[0] === paragraph
        && !(element.textContent || '').trim()
        && !hasGeneratedContent(global.getComputedStyle(element, '::before'))
        && !hasGeneratedContent(global.getComputedStyle(element, '::after'));
      if (!imageOnlyWrapper && inlineSize(element) <= inlineExtent + 1 && blockSize(element) <= blockExtent + 1) {
        return;
      }
      element.style.setProperty('display', 'block', 'important');
      if (!element.parentNode || !element.parentNode.querySelectorAll) return;
      element.parentNode.querySelectorAll('span:empty').forEach(function(strut) {
        if (
          strut.parentNode === element.parentNode
          && !isMeaningfulEmptySpan(strut)
          && global.getComputedStyle(strut).display === 'inline-block'
        ) {
          strut.style.setProperty('display', 'none', 'important');
        }
      });
    });

    Array.from(root.querySelectorAll('span:empty')).forEach(function(element) {
      if (
        !isMeaningfulEmptySpan(element)
        && global.getComputedStyle(element).display === 'inline-block'
        && blockSize(element) > blockExtent
      ) {
        element.style.setProperty(vertical ? 'width' : 'height', blockExtent + 'px', 'important');
      }
    });
  }

  global.hoshiReaderLayoutSemantics = {
    sanitizeInlineBlocks: sanitizeInlineBlocks
  };
})(window);
