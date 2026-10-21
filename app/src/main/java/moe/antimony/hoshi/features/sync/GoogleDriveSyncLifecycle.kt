package moe.antimony.hoshi.features.sync

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import androidx.hilt.work.HiltWorker
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.work.CoroutineWorker
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.ListenableWorker.Result
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dagger.Lazy
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import moe.antimony.hoshi.di.ApplicationScope
import moe.antimony.hoshi.R
import moe.antimony.hoshi.ui.ErrorDiagnosticEvents
import moe.antimony.hoshi.ui.UiText

@Singleton
class GoogleDriveSyncLifecycle @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val manager: GoogleDriveSyncManager,
    private val settings: SyncSettingsRepository,
    private val workManager: Lazy<WorkManager>,
    @param:ApplicationScope private val scope: CoroutineScope,
) {
    fun register(lifecycle: Lifecycle = ProcessLifecycleOwner.get().lifecycle) {
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) {
                workManager.get().cancelUniqueWork(BackgroundWork)
            }

            override fun onStop(owner: LifecycleOwner) {
                scope.launch {
                    manager.pausePolling()
                    val config = settings.settings.first()
                    if (config.enabled && config.provider == SyncProvider.Gdrive) {
                        workManager.get().enqueueUniqueWork(BackgroundWork, ExistingWorkPolicy.KEEP,
                            OneTimeWorkRequestBuilder<GoogleDriveSyncWorker>()
                                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                                .build())
                    }
                }
            }
        })
        scope.launch {
            observeSyncPolling(lifecycle, settings.settings, manager)
        }
        context.getSystemService(ConnectivityManager::class.java).registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
            private var connected: Network? = null

            override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
                if (capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) && connected != network) {
                    connected = network
                    scope.launch { manager.sync() }
                }
            }

            override fun onLost(network: Network) {
                if (connected == network) connected = null
            }
        })
    }

    companion object {
        const val BackgroundWork = "google-drive-sync-background"
    }
}

internal suspend fun observeSyncPolling(
    lifecycle: Lifecycle,
    settings: Flow<SyncSettings>,
    manager: GoogleDriveSyncManager,
) {
    combine(lifecycle.currentStateFlow, settings) { state, config ->
        state.isAtLeast(Lifecycle.State.STARTED) to config.enabled
    }.distinctUntilChanged().collectLatest { (foreground, enabled) ->
        when {
            !enabled -> manager.stop()
            // start() resumes the transport shared with TTU and only polls for Hoshi.
            foreground -> manager.start()
            else -> manager.pausePolling()
        }
    }
}

@HiltWorker
class GoogleDriveSyncWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted parameters: WorkerParameters,
    private val manager: GoogleDriveSyncManager,
) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result = backgroundSyncResult(manager::syncInBackground)
}

internal suspend fun backgroundSyncResult(sync: suspend () -> Unit): Result = try {
    sync()
    Result.success()
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (error: Exception) {
    ErrorDiagnosticEvents.buffer.record(UiText.Resource(R.string.bookshelf_sync_failed), error)
    if (error.isRetryableBackgroundSyncFailure()) Result.retry() else Result.failure()
}

internal fun Throwable.isRetryableBackgroundSyncFailure(): Boolean = when (this) {
    is GoogleDriveUnavailableException -> cause is java.io.IOException || cause.isRetryableBackgroundSyncFailure()
    is GoogleDriveApiException -> isRateLimited || (statusCode ?: 0) >= 500 || isTransientDriveNetworkFailure()
    else -> false
}
