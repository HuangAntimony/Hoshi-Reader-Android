package moe.antimony.hoshi.features.tts

import moe.antimony.hoshi.features.sasayaki.SasayakiSettings

/** Only shared reading behavior is editable from TTS; preserve audiobook-owned preferences. */
internal fun SasayakiSettings.withTtsReadingSettings(value: SasayakiSettings): SasayakiSettings = copy(
    showReaderBottomPlaybackControls = value.showReaderBottomPlaybackControls,
    reverseVerticalReaderSkipButtons = value.reverseVerticalReaderSkipButtons,
    autoScroll = value.autoScroll,
    autoPause = value.autoPause,
    imageHoldSeconds = value.imageHoldSeconds,
    lightTextColor = value.lightTextColor,
    lightBackgroundColor = value.lightBackgroundColor,
    darkTextColor = value.darkTextColor,
    darkBackgroundColor = value.darkBackgroundColor,
)
