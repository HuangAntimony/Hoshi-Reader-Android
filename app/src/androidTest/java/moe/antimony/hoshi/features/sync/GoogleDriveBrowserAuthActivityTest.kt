package moe.antimony.hoshi.features.sync

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleCallback
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class GoogleDriveBrowserAuthActivityTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val launches = CopyOnWriteArrayList<Intent>()
    private val browserLaunched = CountDownLatch(1)
    private val stopped = CountDownLatch(1)
    private var authActivity: GoogleDriveBrowserAuthActivity? = null
    private val lifecycle = ActivityLifecycleCallback { activity, stage ->
        if (activity is GoogleDriveBrowserAuthActivity) {
            authActivity = activity
            if (stage == Stage.STOPPED) stopped.countDown()
        }
    }
    private val browser = object : Instrumentation.ActivityMonitor() {
        override fun onStartActivity(intent: Intent): Instrumentation.ActivityResult? {
            if (intent.action == Intent.ACTION_VIEW && intent.data?.host == "accounts.google.com") {
                launches += Intent(intent)
                intent.setClassName(instrumentation.context.packageName, OAuthBrowserFixtureActivity::class.java.name)
                browserLaunched.countDown()
            }
            return null
        }
    }

    @Before
    fun interceptBrowser() {
        OAuthCallerFixtureActivity.completed = CountDownLatch(1)
        OAuthCallerFixtureActivity.result = null
        ActivityLifecycleMonitorRegistry.getInstance().addLifecycleCallback(lifecycle)
        instrumentation.addMonitor(browser)
    }

    @After
    fun removeBrowserMonitor() {
        browserCommand("cancel")
        instrumentation.runOnMainSync { authActivity?.finish() }
        instrumentation.removeMonitor(browser)
        ActivityLifecycleMonitorRegistry.getInstance().removeLifecycleCallback(lifecycle)
    }

    @Test
    fun opensCustomTabAndReturnsRedirectToCaller() {
        launch()
        assertTrue(browserLaunched.await(10, TimeUnit.SECONDS))
        assertEquals(AuthorizationUri, launches.single().data.toString())
        assertTrue(launches.single().hasExtra("android.support.customtabs.extra.SESSION"))
        browserCommand("redirect")
        assertEquals(Activity.RESULT_OK, result().resultCode)
        assertEquals(Callback, result().resultData.data)
    }

    @Test
    fun returningWithoutRedirectCancels() {
        launch()
        assertTrue(browserLaunched.await(10, TimeUnit.SECONDS))
        browserCommand("cancel")
        assertEquals(Activity.RESULT_CANCELED, result().resultCode)
        assertEquals(1, launches.size)
    }

    @Test
    fun recreationDoesNotOpenTheBrowserAgain() {
        launch()
        assertTrue(stopped.await(10, TimeUnit.SECONDS))
        val recreated = CountDownLatch(1)
        val callback = ActivityLifecycleCallback { activity, stage ->
            if (activity is GoogleDriveBrowserAuthActivity && stage == Stage.CREATED) recreated.countDown()
        }
        val monitor = ActivityLifecycleMonitorRegistry.getInstance()
        monitor.addLifecycleCallback(callback)
        try {
            instrumentation.runOnMainSync { authActivity!!.recreate() }
            assertTrue(recreated.await(10, TimeUnit.SECONDS))
            browserCommand("redirect")
            assertEquals(Activity.RESULT_OK, result().resultCode)
            assertEquals(Callback, result().resultData.data)
            assertEquals(1, launches.size)
        } finally {
            monitor.removeLifecycleCallback(callback)
        }
    }

    @Test
    fun unsolicitedRedirectDoesNotLaunchAuthorization() {
        launch(Intent(context, OAuthCallerFixtureActivity::class.java).setData(Callback))
        assertEquals(Activity.RESULT_CANCELED, result().resultCode)
        assertTrue(launches.isEmpty())
    }

    private fun result(): Instrumentation.ActivityResult {
        assertTrue(OAuthCallerFixtureActivity.completed.await(10, TimeUnit.SECONDS))
        return OAuthCallerFixtureActivity.result
    }

    private fun browserCommand(action: String) {
        context.startActivity(
            Intent(action).setClassName(instrumentation.context.packageName, OAuthBrowserFixtureActivity::class.java.name)
                .setData(Callback).putExtra("targetPackage", context.packageName)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
        )
    }

    private fun launch(
        intent: Intent = Intent(context, OAuthCallerFixtureActivity::class.java)
            .putExtra(GoogleDriveBrowserAuthActivity.AuthorizationUri, AuthorizationUri),
    ) {
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK))
    }

    companion object {
        private const val AuthorizationUri = "https://accounts.google.com/o/oauth2/v2/auth?client_id=test"
        private val Callback = Uri.parse("com.googleusercontent.apps.test:/oauth2callback?code=code&state=state")
    }
}
