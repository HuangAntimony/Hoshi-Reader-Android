package moe.antimony.hoshi.features.sasayaki

internal object SasayakiMediaNotificationActionRegistry {
    private val callbacks = mutableMapOf<String, (String) -> Unit>()

    fun register(sessionId: String, onAction: (String) -> Unit) {
        callbacks[sessionId] = onAction
    }

    fun unregister(sessionId: String) {
        callbacks.remove(sessionId)
    }

    fun dispatch(sessionId: String, action: String) {
        callbacks[sessionId]?.invoke(action)
    }
}
