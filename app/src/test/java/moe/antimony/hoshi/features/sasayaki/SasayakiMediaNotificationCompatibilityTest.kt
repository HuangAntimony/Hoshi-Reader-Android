package moe.antimony.hoshi.features.sasayaki

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SasayakiMediaNotificationCompatibilityTest {
    @Test
    fun disablesNotificationsForHisenseA5ProOnAndroid10() {
        assertFalse(
            SasayakiMediaNotificationCompatibility.shouldPublishNotifications(
                mode = SasayakiSystemMediaControlsMode.Auto,
                manufacturer = "Hisense",
                model = "HLTE203T",
                sdkInt = 29,
            ),
        )
    }

    @Test
    fun keepsNotificationsForOtherHisenseAndroid10Devices() {
        assertTrue(
            SasayakiMediaNotificationCompatibility.shouldPublishNotifications(
                mode = SasayakiSystemMediaControlsMode.Auto,
                manufacturer = "Hisense",
                model = "Other",
                sdkInt = 29,
            ),
        )
    }

    @Test
    fun keepsNotificationsForHisenseA5ProOnOtherAndroidVersions() {
        assertTrue(
            SasayakiMediaNotificationCompatibility.shouldPublishNotifications(
                mode = SasayakiSystemMediaControlsMode.Auto,
                manufacturer = "Hisense",
                model = "HLTE203T",
                sdkInt = 30,
            ),
        )
    }

    @Test
    fun ignoresManufacturerCase() {
        assertFalse(
            SasayakiMediaNotificationCompatibility.shouldPublishNotifications(
                mode = SasayakiSystemMediaControlsMode.Auto,
                manufacturer = "hisense",
                model = "HLTE203T",
                sdkInt = 29,
            ),
        )
    }

    @Test
    fun onOverridesKnownIncompatibleDevices() {
        assertTrue(
            SasayakiMediaNotificationCompatibility.shouldPublishNotifications(
                mode = SasayakiSystemMediaControlsMode.On,
                manufacturer = "Hisense",
                model = "HLTE203T",
                sdkInt = 29,
            ),
        )
    }

    @Test
    fun offDisablesNotificationsOnOtherwiseCompatibleDevices() {
        assertFalse(
            SasayakiMediaNotificationCompatibility.shouldPublishNotifications(
                mode = SasayakiSystemMediaControlsMode.Off,
                manufacturer = "Google",
                model = "Pixel",
                sdkInt = 35,
            ),
        )
    }
}
