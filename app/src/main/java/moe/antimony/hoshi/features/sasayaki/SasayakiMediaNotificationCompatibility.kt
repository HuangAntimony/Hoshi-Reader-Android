package moe.antimony.hoshi.features.sasayaki

internal object SasayakiMediaNotificationCompatibility {
    fun shouldPublishNotifications(mode: SasayakiSystemMediaControlsMode): Boolean =
        suppressionReason(mode) == null

    fun suppressionReason(mode: SasayakiSystemMediaControlsMode): String? =
        if (mode == SasayakiSystemMediaControlsMode.Off) {
            "disabled by Sasayaki settings"
        } else {
            null
        }
}
