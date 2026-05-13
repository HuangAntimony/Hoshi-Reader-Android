package moe.antimony.hoshi.features.sync

import android.content.Context

interface DriveConnectionStore {
    fun isConnected(): Boolean

    fun setConnected(connected: Boolean)
}

class SharedPreferencesDriveConnectionStore(
    context: Context,
) : DriveConnectionStore {
    private val preferences = context.applicationContext.getSharedPreferences(PreferencesName, Context.MODE_PRIVATE)

    override fun isConnected(): Boolean =
        preferences.getBoolean(ConnectedKey, false)

    override fun setConnected(connected: Boolean) {
        preferences.edit().putBoolean(ConnectedKey, connected).apply()
    }

    companion object {
        private const val PreferencesName = "google-drive-connection"
        private const val ConnectedKey = "connected"
    }
}
