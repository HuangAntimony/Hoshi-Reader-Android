package moe.antimony.hoshi.features.sasayaki

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SasayakiMediaNotificationCompatibilityTest {
    @Test
    fun autoPublishesNotifications() {
        assertTrue(
            SasayakiMediaNotificationCompatibility.shouldPublishNotifications(
                mode = SasayakiSystemMediaControlsMode.Auto,
            ),
        )
    }

    @Test
    fun onPublishesNotifications() {
        assertTrue(
            SasayakiMediaNotificationCompatibility.shouldPublishNotifications(
                mode = SasayakiSystemMediaControlsMode.On,
            ),
        )
    }

    @Test
    fun offDisablesNotifications() {
        assertFalse(
            SasayakiMediaNotificationCompatibility.shouldPublishNotifications(
                mode = SasayakiSystemMediaControlsMode.Off,
            ),
        )
    }
}
