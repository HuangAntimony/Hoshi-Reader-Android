(function(global) {
  'use strict';

  var ttuRegexNegated = /[^0-9A-Za-z○◯々-〇〻ぁ-ゖゝ-ゞァ-ヺー０-９Ａ-Ｚａ-ｚｦ-ﾝ\p{Radical}\p{Unified_Ideograph}]+/gimu;
  var ttuRegex = /[0-9A-Za-z○◯々-〇〻ぁ-ゖゝ-ゞァ-ヺー０-９Ａ-Ｚａ-ｚｦ-ﾝ\p{Radical}\p{Unified_Ideograph}]/iu;

  function normalizeText(text) {
    return String(text || '').replace(ttuRegexNegated, '');
  }

  function isMatchableChar(char) {
    return ttuRegex.test(char || '');
  }

  function countChars(text) {
    return Array.from(normalizeText(text)).length;
  }

  function countRawChars(text) {
    return Array.from(text || '').length;
  }

  global.hoshiReaderTextSemantics = {
    normalizeText: normalizeText,
    isMatchableChar: isMatchableChar,
    countChars: countChars,
    countRawChars: countRawChars
  };
})(window);
