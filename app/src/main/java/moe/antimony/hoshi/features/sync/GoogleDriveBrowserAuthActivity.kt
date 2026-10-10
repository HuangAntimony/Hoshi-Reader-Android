package moe.antimony.hoshi.features.sync

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.browser.customtabs.CustomTabsIntent

class GoogleDriveBrowserAuthActivity : Activity() {
    private var authorizationUri: String? = null
    private var browserStarted = false
    private var leftForBrowser = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        authorizationUri = (savedInstanceState ?: intent.extras)?.getString(AuthorizationUri)
        browserStarted = savedInstanceState?.getBoolean(BrowserStarted) == true
        leftForBrowser = savedInstanceState?.getBoolean(LeftForBrowser) == true
    }

    override fun onResume() {
        super.onResume()
        val uri = authorizationUri
        if (uri == null) {
            finish()
            return
        }
        if (!browserStarted) {
            browserStarted = true
            try {
                CustomTabsIntent.Builder().build().launchUrl(this, Uri.parse(uri))
            } catch (_: ActivityNotFoundException) {
                finish()
            }
        }
    }

    override fun onPause() {
        super.onPause()
        leftForBrowser = browserStarted
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (!hasFocus) {
            leftForBrowser = browserStarted
        } else if (leftForBrowser) {
            setResult(if (intent.data == null) RESULT_CANCELED else RESULT_OK, Intent().setData(intent.data))
            finish()
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(AuthorizationUri, authorizationUri)
        outState.putBoolean(BrowserStarted, browserStarted)
        outState.putBoolean(LeftForBrowser, leftForBrowser)
    }

    companion object {
        internal const val AuthorizationUri = "authorizationUri"
        private const val BrowserStarted = "browserStarted"
        private const val LeftForBrowser = "leftForBrowser"
    }
}

class GoogleDriveOAuthRedirectActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        startActivity(
            Intent(this, GoogleDriveBrowserAuthActivity::class.java)
                .setData(intent.data)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
        )
        finish()
    }
}
