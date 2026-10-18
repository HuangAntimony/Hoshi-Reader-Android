package moe.antimony.hoshi.ui.theme

import android.content.Context
import android.content.ContextWrapper
import android.content.res.Configuration
import android.webkit.WebView
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate

/** All app WebViews share the native DayNight configuration, including first construction. */
internal open class HoshiWebView(context: Context) : WebView(context) {
    init {
        val activity = context.appCompatActivity()
        val expected = when (AppCompatDelegate.getDefaultNightMode()) {
            AppCompatDelegate.MODE_NIGHT_YES -> Configuration.UI_MODE_NIGHT_YES
            AppCompatDelegate.MODE_NIGHT_NO -> Configuration.UI_MODE_NIGHT_NO
            else -> null
        }
        if (activity != null && expected != null &&
            activity.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK != expected) {
            // Some providers reset Resources while creating the first View, even after AndroidX
            // startup completes. Tell the delegate about the actual reset so it can reapply the
            // app policy and dispatch to existing Views; don't mutate Resources ourselves.
            activity.delegate.onConfigurationChanged(Configuration(activity.resources.configuration))
            // This new View isn't attached to the decor tree yet.
            dispatchConfigurationChanged(activity.resources.configuration)
        }
    }
}

private tailrec fun Context.appCompatActivity(): AppCompatActivity? = when (this) {
    is AppCompatActivity -> this
    is ContextWrapper -> baseContext.appCompatActivity()
    else -> null
}
