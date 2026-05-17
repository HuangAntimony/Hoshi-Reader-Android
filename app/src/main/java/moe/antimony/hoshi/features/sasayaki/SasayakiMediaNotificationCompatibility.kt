package moe.antimony.hoshi.features.sasayaki

import android.os.Build

internal object SasayakiMediaNotificationCompatibility {
    fun shouldPublishNotifications(mode: SasayakiSystemMediaControlsMode): Boolean =
        shouldPublishNotifications(
            mode = mode,
            manufacturer = Build.MANUFACTURER,
            model = Build.MODEL,
            sdkInt = Build.VERSION.SDK_INT,
        )

    internal fun shouldPublishNotifications(
        mode: SasayakiSystemMediaControlsMode,
        manufacturer: String?,
        model: String?,
        sdkInt: Int,
    ): Boolean =
        suppressionReason(
            mode = mode,
            manufacturer = manufacturer,
            model = model,
            sdkInt = sdkInt,
        ) == null

    fun suppressionReason(mode: SasayakiSystemMediaControlsMode): String? =
        suppressionReason(
            mode = mode,
            manufacturer = Build.MANUFACTURER,
            model = Build.MODEL,
            sdkInt = Build.VERSION.SDK_INT,
        )

    internal fun suppressionReason(
        mode: SasayakiSystemMediaControlsMode,
        manufacturer: String?,
        model: String?,
        sdkInt: Int,
    ): String? =
        when {
            mode == SasayakiSystemMediaControlsMode.Off -> "disabled by Sasayaki settings"
            mode == SasayakiSystemMediaControlsMode.Auto &&
                isHisenseA5ProAndroid10(
                    manufacturer = manufacturer,
                    model = model,
                    sdkInt = sdkInt,
                ) -> "disabled on Hisense HLTE203T Android 10 because its SystemUI crashes while inflating the Sasayaki media notification"
            else -> null
        }

    private fun isHisenseA5ProAndroid10(
        manufacturer: String?,
        model: String?,
        sdkInt: Int,
    ): Boolean =
        sdkInt == Android10Sdk &&
            manufacturer.equals("Hisense", ignoreCase = true) &&
            model.equals("HLTE203T", ignoreCase = true)

    private const val Android10Sdk = 29
}
