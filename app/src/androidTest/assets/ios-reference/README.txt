iOS popup CSS reference fixture

Source repository: https://github.com/Manhhao/Hoshi-Reader
Source commit: d24b2fa6e5fc559c7d4032b4db355eecdd669b29
Source path: Features/Popup/popup.css
Source URL: https://github.com/Manhhao/Hoshi-Reader/blob/d24b2fa6e5fc559c7d4032b4db355eecdd669b29/Features/Popup/popup.css
SHA-256: dd3ad54bcee33c49dfb8982e7319be25268ed9615937945437f6d0e107b61a53
License: GPL-3.0-or-later; original copyright and SPDX notices remain in popup.css.

This is an unmodified snapshot used only by LookupPopupThemeWebViewTest.
Android's normal androidTest assets packaging includes it in the test APK;
it is not a production asset. Builds and CI never fetch or read the iOS checkout.

When adopting an upstream popup palette change, replace popup.css with the
complete unmodified upstream file, update the commit, URL and checksum here,
and run LookupPopupThemeWebViewTest's rendered color contract on a device.
Do not edit the reference colors just to match Android or copy Android's palette
into this fixture. This checks the adopted upstream snapshot, not live upstream.
