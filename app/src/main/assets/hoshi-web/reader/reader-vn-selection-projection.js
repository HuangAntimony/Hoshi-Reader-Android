(function(global) {
  'use strict';

  function ReaderVnSelectionProjection(reader) {
    this.reader = reader;
  }

  ReaderVnSelectionProjection.prototype = {
    toSemanticHit: function(renderedHit) {
      if (!renderedHit || !renderedHit.node) return null;
      var chapterPosition = this.reader.rangeMap.chapterPositionForClone(
        renderedHit.node,
        renderedHit.offset
      );
      if (!chapterPosition) return null;
      return this.reader.contentStream.sourcePositionForRawOffset(chapterPosition.rawOffset);
    },

    normalizedOffsetForHit: function(semanticHit) {
      if (!semanticHit || !semanticHit.node) return null;
      return this.reader.contentStream.matchableOffsetForSourcePosition(
        semanticHit.node,
        semanticHit.offset
      );
    },

    visibleRangesForSemanticRanges: function(ranges) {
      var visibleRanges = [];
      for (var i = 0; i < ranges.length; i++) {
        var range = ranges[i];
        var start = this.reader.contentStream.rawOffsetForSourcePosition(
          range.node,
          range.start
        );
        var end = this.reader.contentStream.rawOffsetForSourcePosition(
          range.node,
          range.end
        );
        if (start === null || end === null || end < start) return [];
        var segments = this.reader.rangeMap.collectRawSegments(start, end - start);
        Array.prototype.push.apply(visibleRanges, segments);
      }
      return visibleRanges;
    }
  };

  global.hoshiReaderVnSelectionProjection = {
    create: function(reader) {
      return new ReaderVnSelectionProjection(reader);
    }
  };
})(window);
