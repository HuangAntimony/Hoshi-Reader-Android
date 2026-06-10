package moe.antimony.hoshi.features.reader

import android.content.Context
import androidx.compose.ui.unit.IntSize

internal object ReaderViewportCache {
    private const val PREFS_NAME = "reader_viewport_cache"

    fun load(context: Context): IntSize {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val key = key(context)
        val width = prefs.getInt("$key.width", 0)
        val height = prefs.getInt("$key.height", 0)
        return if (width > 0 && height > 0) IntSize(width, height) else IntSize.Zero
    }

    fun save(context: Context, size: IntSize) {
        if (size.width <= 0 || size.height <= 0) return
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val key = key(context)
        if (prefs.getInt("$key.width", 0) == size.width && prefs.getInt("$key.height", 0) == size.height) return
        prefs.edit()
            .putInt("$key.width", size.width)
            .putInt("$key.height", size.height)
            .apply()
    }

    private fun key(context: Context): String =
        "orientation-${context.resources.configuration.orientation}"
}
