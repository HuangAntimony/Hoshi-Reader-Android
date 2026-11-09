(function(global) {
  'use strict';

  // The native WebView already stays invisible until restore. Avoid laying out
  // publisher content before the reader has installed its viewport/styles and
  // prepared the ruby/paragraph DOM.
  var path = global.location.pathname;
  if (global.top !== global || !(path.startsWith('/epub/') ||
      /^\/reader-pages\/[a-f0-9-]{36}\/epub\//.test(path))) return;
  if (global.hoshiReaderDocumentPreparation) return;

  var root = null;
  var display = '';
  var priority = '';
  var observer = new MutationObserver(function() { prepare(); });

  function prepare() {
    if (!document.documentElement) return;
    root = document.documentElement;
    display = root.style.getPropertyValue('display');
    priority = root.style.getPropertyPriority('display');
    root.style.setProperty('display', 'none', 'important');
    observer.disconnect();
  }

  global.hoshiReaderDocumentPreparation = {
    resume: function() {
      observer.disconnect();
      if (!root) return;
      if (display) root.style.setProperty('display', display, priority);
      else root.style.removeProperty('display');
      root = null;
    }
  };

  observer.observe(document, { childList: true });
  prepare();
})(window);
