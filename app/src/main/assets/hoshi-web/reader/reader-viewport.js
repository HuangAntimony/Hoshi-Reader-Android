window.hoshiReaderViewport = {
  preloadBodyFont: function() {
    if (!document.fonts?.load) return null;
    var style = window.getComputedStyle(document.body);
    var family = style.getPropertyValue('--hoshi-preload-face').trim();
    if (!family) return null;
    // Start the selected face while document-start preparation still hides the
    // root. Load only the reader-owned family: the body's entire fallback list
    // can request publisher faces that no character in this chapter needs.
    return document.fonts.load(`${style.fontStyle} ${style.fontWeight} ${style.fontSize} ${family}`)
      .catch(() => {}); // An unavailable face still uses its CSS fallback.
  },
  ensureDeviceViewport: function() {
    var viewport = document.querySelector('meta[name="viewport"]');
    if (viewport) viewport.remove();
    var newViewport = document.createElement('meta');
    newViewport.name = 'viewport';
    newViewport.content = 'width=device-width, initial-scale=1.0, maximum-scale=1.0, user-scalable=no';
    document.head.appendChild(newViewport);
  }
};
