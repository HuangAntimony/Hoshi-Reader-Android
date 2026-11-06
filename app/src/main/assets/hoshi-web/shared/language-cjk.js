// Copyright © 2026 Antimony.
// Copyright © 2024-2026 Yomitan Authors.
// SPDX-License-Identifier: GPL-3.0-or-later
// Chinese/Korean ranges from Yomitan 0273cbb33d774861d2470be62623dde56c8059d6:
// ext/js/language/{CJK-util.js,zh/chinese.js,ko/korean.js}

(function() {
    const commonRanges = [
        [0x4e00, 0x9fff], [0x3400, 0x4dbf], [0x20000, 0x2a6df],
        [0x2a700, 0x2b73f], [0x2b740, 0x2b81f], [0x2b820, 0x2ceaf],
        [0x2ceb0, 0x2ebef], [0x30000, 0x3134f], [0x31350, 0x323af],
        [0x2ebf0, 0x2ee5f], [0x323b0, 0x3347f], [0xf900, 0xfaff],
        [0x2f800, 0x2fa1f], [0x3000, 0x303f],
        [0xff10, 0xff19], [0xff21, 0xff3a], [0xff41, 0xff5a],
        [0xff01, 0xff0f], [0xff1a, 0xff1f], [0xff3b, 0xff3f],
        [0xff5b, 0xff60], [0xffe0, 0xffee],
    ];
    const chineseRanges = [
        ...commonRanges, [0x3100, 0x312f], [0x31a0, 0x31bf],
        [0x16fe0, 0x16fff], [0xfe50, 0xfe6f], [0xfe10, 0xfe1f],
    ];
    const koreanRanges = [
        ...commonRanges, [0x1100, 0x11ff], [0x3130, 0x318f],
        [0xac00, 0xd7af], [0xa960, 0xa97f], [0xd7b0, 0xd7ff], [0xffa0, 0xffdf],
    ];
    const inRanges = (codePoint, ranges) =>
        ranges.some(([start, end]) => codePoint >= start && codePoint <= end);

    window.hoshiLanguageUtilities = {
        ...window.hoshiLanguageUtilities,
        zh: { isCodePointChinese: codePoint => inRanges(codePoint, chineseRanges) },
        ko: { isCodePointKorean: codePoint => inRanges(codePoint, koreanRanges) },
    };
})();
