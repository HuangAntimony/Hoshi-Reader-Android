(function(global) {
  'use strict';

  var ttuRegexNegated = /[^0-9A-Za-z○◯々-〇〻ぁ-ゖゝ-ゞァ-ヺー０-９Ａ-Ｚａ-ｚｦ-ﾝ가-힣ㄱ-ㆎ\p{Radical}\p{Unified_Ideograph}]+/gimu;
  var ttuRegex = /[0-9A-Za-z○◯々-〇〻ぁ-ゖゝ-ゞァ-ヺー０-９Ａ-Ｚａ-ｚｦ-ﾝ가-힣ㄱ-ㆎ\p{Radical}\p{Unified_Ideograph}]/iu;

  function normalizeText(text) {
    return String(text || '').replace(ttuRegexNegated, '');
  }

  function isMatchableChar(char) {
    return ttuRegex.test(char || '');
  }

  function isJapaneseBreakCharacter(text) {
    var code = (text || '').codePointAt(0);
    return (code >= 0x3000 && code <= 0x303f) ||
      (code >= 0x3040 && code <= 0x30ff) ||
      (code >= 0x3400 && code <= 0x9fff) ||
      (code >= 0xf900 && code <= 0xfaff) ||
      (code >= 0xff00 && code <= 0xffef);
  }

  function countChars(text) {
    return Array.from(normalizeText(text)).length;
  }

  function countRawChars(text) {
    return Array.from(text || '').length;
  }

  // Search includes interior punctuation, but not punctuation outside the first
  // and last matchable characters (unlike Sasayaki sentence presentation).
  function searchRawRange(entries, offset, length) {
    if (!Number.isInteger(offset) || !Number.isInteger(length) || offset < 0 || length <= 0) return null;
    var raw = 0;
    var normalized = 0;
    var start = null;
    for (var entry of entries) {
      for (var char of String(entry.text || '')) {
        if (isMatchableChar(char)) {
          if (normalized === offset) start = raw;
          normalized += 1;
          if (normalized === offset + length) return { start: start, end: raw + 1 };
        }
        raw += 1;
      }
    }
    return null;
  }

  var sasayakiOpening = new Set(Array.from('「『（〔［｛〈《【〖〘〚“‘｢([{'));
  var sasayakiTrailing = new Set(Array.from('」』）〕］｝〉》】〗〙〛”’｣)]}。、，．！？!?‼⁇⁈⁉､｡・･：；:;'));
  var sasayakiNeutral = new Set(Array.from('…‥—―–─〜～'));

  // Entries contain ruby-free source text and structural boundary flags. Raw
  // offsets count code points; DOM adapters retain their own UTF-16 mapping.
  function createSasayakiTextIndex(entries) {
    var starts = [];
    var ends = [];
    var raw = 0;
    var pendingRight = null;
    var left = -1;
    var neutralOwner = 'right';
    var indexedEntries = [];
    entries.forEach(function(entry) {
      if (entry.boundaryBefore) {
        pendingRight = null;
        left = -1;
        neutralOwner = 'right';
      }
      var text = String(entry.text || '');
      var rawStart = raw;
      var utf16Offsets = [0];
      var utf16 = 0;
      for (var char of text) {
        if (isMatchableChar(char)) {
          starts.push(pendingRight === null ? raw : pendingRight);
          ends.push(raw + 1);
          left = ends.length - 1;
          pendingRight = null;
          neutralOwner = 'left';
        } else {
          var owner = sasayakiOpening.has(char) ? 'right'
            : sasayakiTrailing.has(char) ? 'left'
            : sasayakiNeutral.has(char) ? neutralOwner : null;
          if (owner === 'right') {
            if (pendingRight === null) pendingRight = raw;
            left = -1;
          } else if (owner === 'left') {
            if (left >= 0) ends[left] = raw + 1;
            pendingRight = null;
          } else {
            pendingRight = null;
            left = -1;
          }
          // A neutral run inherits one owner across text-node boundaries.
          if (!sasayakiNeutral.has(char)) {
            neutralOwner = sasayakiOpening.has(char) || /\s/u.test(char) ? 'right' : 'left';
          }
        }
        raw += 1;
        utf16 += char.length;
        utf16Offsets.push(utf16);
      }
      indexedEntries.push({ node: entry.node, rawStart: rawStart, rawEnd: raw, utf16Offsets: utf16Offsets });
    });
    return {
      entries: indexedEntries,
      range: function(start, length) {
        start = Number(start);
        length = Number(length);
        if (!Number.isInteger(start) || !Number.isInteger(length) || start < 0 || length <= 0 || start + length > starts.length) return null;
        return { start: starts[start], end: ends[start + length - 1] };
      }
    };
  }

  global.hoshiReaderTextSemantics = {
    searchRawRange: searchRawRange,
    createSasayakiTextIndex: createSasayakiTextIndex,
    normalizeText: normalizeText,
    isMatchableChar: isMatchableChar,
    isJapaneseBreakCharacter: isJapaneseBreakCharacter,
    countChars: countChars,
    countRawChars: countRawChars
  };
})(window);
