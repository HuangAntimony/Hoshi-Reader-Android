// Copyright © 2026 Antimony.
// SPDX-License-Identifier: GPL-3.0-or-later

(function() {
    const isLanguageCharacter = window.hoshiLanguageUtilities?.zh?.isCodePointChinese;
    // Like Yomitan, scan from the tapped character rather than rewinding to a word start.
    const ChineseSelectionLanguage = {
        isScanBoundary(char, selection) {
            return /^[\s\u3000]$/.test(char) ||
                selection.scanDelimiters.includes(char) ||
                (window.scanNonJapaneseText === false && !isLanguageCharacter?.(char.codePointAt(0)));
        },
    };

    window.hoshiSelectionLanguagePolicies = {
        ...window.hoshiSelectionLanguagePolicies,
        zh: ChineseSelectionLanguage,
    };
})();
