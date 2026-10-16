package moe.antimony.hoshi.features.tts

import moe.antimony.hoshi.features.sasayaki.SasayakiSettings
import moe.antimony.hoshi.features.sasayaki.SasayakiSettingsUpdate

/** Only shared reading behavior is editable from TTS; preserve audiobook-owned preferences. */
internal fun SasayakiSettings.withTtsReadingSettings(update: SasayakiSettingsUpdate): SasayakiSettings {
    val value = update(this)
    return copy(
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
}
