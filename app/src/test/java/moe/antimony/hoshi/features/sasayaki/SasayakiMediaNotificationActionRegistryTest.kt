package moe.antimony.hoshi.features.sasayaki

import org.junit.Assert.assertEquals
import org.junit.Test

class SasayakiMediaNotificationActionRegistryTest {
    @Test
    fun dispatchesActionsToMatchingSessionOnly() {
        val firstActions = mutableListOf<String>()
        val secondActions = mutableListOf<String>()

        SasayakiMediaNotificationActionRegistry.register("first", firstActions::add)
        SasayakiMediaNotificationActionRegistry.register("second", secondActions::add)
        SasayakiMediaNotificationActionRegistry.dispatch(
            sessionId = "second",
            action = SasayakiMediaNotificationActions.ActionNext,
        )

        assertEquals(emptyList<String>(), firstActions)
        assertEquals(listOf(SasayakiMediaNotificationActions.ActionNext), secondActions)

        SasayakiMediaNotificationActionRegistry.unregister("first")
        SasayakiMediaNotificationActionRegistry.unregister("second")
    }

    @Test
    fun unregisterStopsDispatch() {
        val actions = mutableListOf<String>()

        SasayakiMediaNotificationActionRegistry.register("session", actions::add)
        SasayakiMediaNotificationActionRegistry.unregister("session")
        SasayakiMediaNotificationActionRegistry.dispatch(
            sessionId = "session",
            action = SasayakiMediaNotificationActions.ActionPause,
        )

        assertEquals(emptyList<String>(), actions)
    }
}
