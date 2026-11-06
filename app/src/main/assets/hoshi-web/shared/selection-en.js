//
//  selection-en.js
//  Hoshi Reader
//
//  Copyright © 2026 Antimony.
//  SPDX-License-Identifier: GPL-3.0-or-later
//

(function() {
    const EnglishScanDelimiters = '"“”„‟\'‘’‚‛«»‹›!?—–-‐‑‒/\\|@#$%^&*_+=~`<>';
    const EnglishWordInternalDelimiters = '\'’`-‐‑';

    function isEnglishWordChar(char) {
        return !!char && /[A-Za-z0-9]/.test(char);
    }

    function isWordInternalDelimiter(text, offset, char) {
        return EnglishWordInternalDelimiters.includes(char) &&
            isEnglishWordChar(text[offset - 1]) &&
            isEnglishWordChar(text[offset + 1]);
    }

    function isEnglishScanBoundary(text, offset, selection, char = selection.codePointAt(text, offset)) {
        return selection.scanDelimiters.includes(char) ||
            (EnglishScanDelimiters.includes(char) && !isWordInternalDelimiter(text, offset, char));
    }

    const EnglishSelectionLanguage = {
        isScanBoundary(char, selection) {
            return selection.scanDelimiters.includes(char) || EnglishScanDelimiters.includes(char);
        },

        isScanBoundaryAt(text, offset, selection, char) {
            return isEnglishScanBoundary(text, offset, selection, char);
        },

        isHitBoundary(char, selection) {
            return /^[\s\u3000]$/.test(char) || this.isScanBoundary(char, selection);
        },

        isHitBoundaryAt(text, offset, selection) {
            const char = selection.codePointAt?.(text, offset) ?? text[offset];
            return /^[\s\u3000]$/.test(char) || isEnglishScanBoundary(text, offset, selection, char);
        },

        isWordStartBoundary(char, selection) {
            return this.isHitBoundary(char, selection);
        },

        isWordStartBoundaryAt(text, offset, selection) {
            return this.isHitBoundaryAt(text, offset, selection);
        },

        selectionStartForHit(hit, selection) {
            return selection.findWordStart(hit);
        },
    };

    window.hoshiSelectionLanguagePolicies = {
        ...window.hoshiSelectionLanguagePolicies,
        en: EnglishSelectionLanguage,
    };
})();
