//
//  selection-ja.js
//  Hoshi Reader
//
//  Copyright © 2026 Manhhao.
//  Copyright © 2026 Antimony.
//  SPDX-License-Identifier: GPL-3.0-or-later
//

(function() {
    const isCodePointJapanese = window.hoshiLanguageUtilities?.ja?.isCodePointJapanese;
    const JapaneseSelectionLanguage = {
        isScanBoundary(char, selection) {
            return /^[\s\u3000]$/.test(char) ||
                selection.scanDelimiters.includes(char) ||
                (window.scanNonJapaneseText === false && !isCodePointJapanese?.(char.codePointAt(0)));
        },
    };

    window.hoshiSelectionLanguagePolicies = {
        ...window.hoshiSelectionLanguagePolicies,
        default: JapaneseSelectionLanguage,
        ja: JapaneseSelectionLanguage,
    };
})();
