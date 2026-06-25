(function(global) {
  'use strict';

  function ReaderRangeMap(reader) {
    this.reader = reader;
    this.cloneTextOffsets = new WeakMap();
    this.cloneTextRawOffsets = new WeakMap();
  }

  ReaderRangeMap.prototype = {
    registerCloneTextOffset: function(node, charOffset, rawOffset) {
      this.cloneTextOffsets.set(node, charOffset === undefined ? 0 : charOffset);
      this.cloneTextRawOffsets.set(node, rawOffset === undefined ? 0 : rawOffset);
    },

    cloneTextOffsetForNode: function(node) {
      return this.cloneTextOffsets.get(node);
    },

    cloneTextRawOffsetForNode: function(node) {
      return this.cloneTextRawOffsets.get(node);
    },

    collectRawSegments: function(offset, length) {
      var start = Number(offset) || 0;
      var end = start + Math.max(0, Number(length) || 0);
      var segments = [];
      var walker = this.reader.createWalker();
      var node;
      while (node = walker.nextNode()) {
        var nodeStart = this.reader.nodeStartRawOffsets.get(node);
        if (nodeStart === undefined) continue;
        var text = node.textContent || '';
        var rawCursor = nodeStart;
        var i = 0;
        var segment = null;
        var flushSegment = function() {
          if (!segment) return;
          segments.push(segment);
          segment = null;
        };
        while (i < text.length && rawCursor < end) {
          var char = String.fromCodePoint(text.codePointAt(i));
          var next = i + char.length;
          if (rawCursor >= start) {
            if (!segment) {
              segment = { node: node, start: i, end: next };
            } else {
              segment.end = next;
            }
          }
          rawCursor += 1;
          i = next;
        }
        flushSegment();
      }
      return segments;
    },

    collectMatchableCueRanges: function(cues) {
      var result = [];
      for (var i = 0; i < cues.length; i++) {
        var cue = cues[i];
        if (!cue || !cue.id) continue;
        var start = Math.max(0, Number(cue.start) || 0);
        var length = Math.max(0, Number(cue.length) || 0);
        result.push({
          id: cue.id,
          ranges: this.collectMatchableSegments(start, start + length)
        });
      }
      return result;
    },

    collectMatchableSegments: function(startOffset, endOffset) {
      var start = Math.max(0, Number(startOffset) || 0);
      var end = Math.max(start, Number(endOffset) || 0);
      var ranges = [];
      if (end <= start) return ranges;
      var walker = this.reader.createWalker();
      var node;
      while (node = walker.nextNode()) {
        var nodeStart = this.reader.nodeStartOffsets.get(node);
        if (nodeStart === undefined) continue;
        var text = node.textContent || '';
        var cursor = nodeStart;
        var offset = 0;
        var segment = null;
        var flushSegment = function() {
          if (!segment) return;
          ranges.push(segment);
          segment = null;
        };
        while (offset < text.length && cursor < end) {
          var char = String.fromCodePoint(text.codePointAt(offset));
          var next = offset + char.length;
          if (this.reader.isMatchableChar(char)) {
            if (cursor >= start && cursor < end) {
              if (!segment) {
                segment = { node: node, start: offset, end: next };
              } else {
                segment.end = next;
              }
            } else {
              flushSegment();
            }
            cursor += 1;
            if (cursor === end) flushSegment();
          } else if (segment) {
            segment.end = next;
          } else if (cursor > start && cursor < end) {
            segment = { node: node, start: offset, end: next };
          }
          offset = next;
        }
        flushSegment();
      }
      return ranges;
    }
  };

  global.hoshiReaderRangeMap = {
    create: function(reader) {
      return new ReaderRangeMap(reader);
    }
  };
})(window);
