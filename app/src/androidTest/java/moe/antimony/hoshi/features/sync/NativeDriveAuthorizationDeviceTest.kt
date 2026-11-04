package moe.antimony.hoshi.features.sync

import android.app.PendingIntent
import android.content.Intent
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.android.gms.auth.api.identity.AuthorizationResult
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.CommonStatusCodes
import com.google.android.gms.common.api.Status
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import moe.antimony.hoshi.ui.diagnosticMessage
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Real SDK result/DataStore integration using only a unique scratch store and unsent PendingIntent. */
@RunWith(AndroidJUnit4::class)
class NativeDriveAuthorizationDeviceTest {
    @Test fun resolutionRetainsLinkAcrossRestartAndAllowsSilentRecoveryWithSafeDiagnostics() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(context.cacheDir, "native-drive-auth-${UUID.randomUUID()}").apply { mkdirs() }
        val file = directory.resolve("auth.preferences_pb")
        var job = Job()
        var store = PreferenceDataStoreFactory.create(scope = CoroutineScope(Dispatchers.IO + job)) { file }
        val pending = PendingIntent.getActivity(context, 0,
            Intent(context, GoogleDriveBrowserAuthActivity::class.java).setAction(UUID.randomUUID().toString()),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_ONE_SHOT)
        val snapshot = NativeDriveAuthorizationContext(true, 0, true, true, false)
        try {
            store.edit { it[booleanPreferencesKey("connected")] = true }
            val network = ApiException(Status(CommonStatusCodes.NETWORK_ERROR, "fixture network failure"))
            val networkFailure = runCatching {
                store.nativeDriveAccessToken {
                    withNativeDriveAuthorizationDiagnostics(snapshot, false) { throw network }
                }
            }.exceptionOrNull()!!
            assertEquals(DriveAuthStatus.Connected, store.nativeDriveStatus())
            assertSame(network, networkFailure.cause)
            assertTrue(networkFailure.isRetryableBackgroundSyncFailure())

            @Suppress("DEPRECATION")
            val result = AuthorizationResult(null, "fixture-private-token", null, emptyList(), null, pending)
            assertTrue(result.hasResolution())
            val resolution = runCatching {
                store.nativeDriveAccessToken {
                    nativeDriveToken(result.accessToken, result.hasResolution(), result.grantedScopes, snapshot)
                }
            }.exceptionOrNull()!!
            assertTrue(resolution is NativeDriveAuthorizationRecoveryException)
            assertTrue(resolution.isRetryableBackgroundSyncFailure())
            val details = resolution.diagnosticMessage()
            assertTrue(details.contains("requiresResolution=true"))
            assertTrue(details.contains("validated=false"))
            assertFalse(details.contains("fixture-private-token"))
            job.cancelAndJoin()
            job = Job()
            store = PreferenceDataStoreFactory.create(scope = CoroutineScope(Dispatchers.IO + job)) { file }
            assertEquals(DriveAuthStatus.RecoveryRequired, store.nativeDriveStatus())
            assertTrue(store.nativeDriveStatus().isLinked)
            assertEquals("fixture-recovered-token", store.nativeDriveAccessToken { "fixture-recovered-token" })
            assertEquals(DriveAuthStatus.RecoveryRequired, store.nativeDriveStatus())
            store.completeNativeDriveRecovery()
            assertEquals(DriveAuthStatus.Connected, store.nativeDriveStatus())
        } finally {
            pending.cancel()
            job.cancelAndJoin()
            directory.deleteRecursively()
        }
    }
}
