window.hoshiReaderViewport = {
  ensureDeviceViewport: function() {
    var viewport = document.querySelector('meta[name="viewport"]');
    if (viewport) viewport.remove();
    var newViewport = document.createElement('meta');
    newViewport.name = 'viewport';
    newViewport.content = 'width=device-width, initial-scale=1.0, maximum-scale=1.0, user-scalable=no';
    document.head.appendChild(newViewport);
  }
};
