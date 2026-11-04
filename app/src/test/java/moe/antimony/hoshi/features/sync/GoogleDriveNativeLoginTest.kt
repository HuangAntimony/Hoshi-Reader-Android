package moe.antimony.hoshi.features.sync

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.runTest
import moe.antimony.hoshi.ui.ErrorDiagnosticEvents
import moe.antimony.hoshi.ui.UiText
import moe.antimony.hoshi.ui.resolve
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class GoogleDriveNativeLoginTest {
    @get:Rule val temporary = TemporaryFolder()

    @Before fun clearDiagnostics() { ErrorDiagnosticEvents.buffer.drain() }
    @After fun drainDiagnostics() { ErrorDiagnosticEvents.buffer.drain() }

    @Test fun authorizationResolutionPreservesLinkAndCanRecoverSilentlyAfterRestart() = runTest {
        val file = temporary.root.resolve("native-login.preferences_pb")
        val login = LoginStore(file)
        val original = runCatching {
            nativeDriveToken(null, true, emptyList(), NativeDriveAuthorizationContext(true, 0, true, true, true))
        }.exceptionOrNull()!!
        try {
            login.connect()
            assertFalse(syncConnectionActions(login.dataStore.nativeDriveStatus(), false).showConnect)
            val visible = syncOperationError { login.dataStore.nativeDriveAccessToken { throw original } }

            assertEquals(DriveAuthStatus.RecoveryRequired, login.dataStore.nativeDriveStatus())
            val actions = syncConnectionActions(login.dataStore.nativeDriveStatus(), false)
            assertTrue(actions.showConnect)
            assertTrue(actions.connectEnabled)
            assertTrue(actions.showSignOut)
            val details = visible.details()
            for (detail in listOf("requiresResolution=true", "validated=true")) assertTrue(details, details.contains(detail))
            assertSame(original, ErrorDiagnosticEvents.buffer.drain().single().error)
        } finally {
            login.close()
        }

        val restarted = LoginStore(file)
        try {
            assertEquals(DriveAuthStatus.RecoveryRequired, restarted.dataStore.nativeDriveStatus())
            assertTrue(restarted.dataStore.nativeDriveStatus().isLinked)
            assertTrue(syncConnectionActions(restarted.dataStore.nativeDriveStatus(), false).showConnect)
            assertEquals("recovered-token", restarted.dataStore.nativeDriveAccessToken { "recovered-token" })
            assertEquals("A token alone must not remove the durable root verification barrier", DriveAuthStatus.RecoveryRequired, restarted.dataStore.nativeDriveStatus())
            restarted.dataStore.completeNativeDriveRecovery()
            assertEquals(DriveAuthStatus.Connected, restarted.dataStore.nativeDriveStatus())
            assertFalse(syncConnectionActions(restarted.dataStore.nativeDriveStatus(), false).showConnect)
        } finally {
            restarted.close()
        }
    }

    @Test fun successfulSilentRefreshKeepsConnected() = runTest {
        val login = LoginStore(temporary.root.resolve("native-login.preferences_pb"))
        try {
            login.connect()
            assertEquals("refreshed-token", login.dataStore.nativeDriveAccessToken { "refreshed-token" })
            assertEquals(DriveAuthStatus.Connected, login.dataStore.nativeDriveStatus())
            assertTrue(syncConnectionActions(login.dataStore.nativeDriveStatus(), false).showSignOut)
            assertTrue(ErrorDiagnosticEvents.buffer.drain().isEmpty())
        } finally {
            login.close()
        }
    }

    @Test fun explicitSignOutDuringRecoveryCannotBeUndoneByALateToken() = runTest {
        val login = LoginStore(temporary.root.resolve("native-login.preferences_pb"))
        try {
            login.connect()
            runCatching {
                login.dataStore.nativeDriveAccessToken {
                    nativeDriveToken(null, true, emptyList(), NativeDriveAuthorizationContext(true, 0, true, true, true))
                }
            }
            assertEquals(DriveAuthStatus.RecoveryRequired, login.dataStore.nativeDriveStatus())
            login.dataStore.setNativeDriveConnected(false)
            login.dataStore.nativeDriveAccessToken { "late-token" }
            assertEquals(DriveAuthStatus.NotConnected, login.dataStore.nativeDriveStatus())
            assertFalse(login.dataStore.nativeDriveStatus().isLinked)
        } finally {
            login.close()
        }
    }

    @Test fun temporaryFailureKeepsConnectedAndPreservesVisibleCauseAndHistory() = runTest {
        val login = LoginStore(temporary.root.resolve("native-login.preferences_pb"))
        try {
            login.connect()
            val original = IOException("Token transport failed", IOException("Connection reset"))
            val visible = syncOperationError { login.dataStore.nativeDriveAccessToken { throw original } }
            assertEquals(DriveAuthStatus.Connected, login.dataStore.nativeDriveStatus())
            assertFalse(syncConnectionActions(login.dataStore.nativeDriveStatus(), false).showConnect)
            val details = visible.details()
            for (detail in listOf("Token transport failed", "Connection reset")) assertTrue(details, details.contains(detail))
            assertSame(original, ErrorDiagnosticEvents.buffer.drain().single().error)
        } finally {
            login.close()
        }
    }

    @Test fun cancellationKeepsConnectedWithoutVisibleErrorOrDiagnostic() = runTest {
        val login = LoginStore(temporary.root.resolve("native-login.preferences_pb"))
        try {
            login.connect()
            val cancellation = CancellationException("Authorization wait cancelled")
            var visible: UiText? = null
            assertSame(cancellation, runCatching {
                visible = syncOperationError { login.dataStore.nativeDriveAccessToken { throw cancellation } }
            }.exceptionOrNull())
            assertNull(visible)
            assertEquals(DriveAuthStatus.Connected, login.dataStore.nativeDriveStatus())
            assertTrue(ErrorDiagnosticEvents.buffer.drain().isEmpty())
        } finally {
            login.close()
        }
    }

    private fun UiText?.details() = this?.resolve({ _, _ -> "Operation failed" }, { _, _, _ -> "" }).orEmpty()

    private class LoginStore(file: File) {
        private val scope = CoroutineScope(Dispatchers.IO + Job())
        val dataStore: DataStore<Preferences> = PreferenceDataStoreFactory.create(scope = scope) { file }

        suspend fun connect() { dataStore.setNativeDriveConnected(true) }

        suspend fun close() { scope.coroutineContext[Job]!!.cancelAndJoin() }
    }
}
