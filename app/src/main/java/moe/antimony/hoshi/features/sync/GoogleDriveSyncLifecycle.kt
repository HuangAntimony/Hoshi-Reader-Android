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
import androidx.work.ExistingWorkPolicy
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
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import moe.antimony.hoshi.di.ApplicationScope

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
                scope.launch { manager.start() }
            }

            override fun onStop(owner: LifecycleOwner) {
                scope.launch {
                    manager.pausePolling()
                    val config = settings.settings.first()
                    if (config.enabled && config.provider == SyncProvider.Gdrive) {
                        workManager.get().enqueueUniqueWork(BackgroundWork, ExistingWorkPolicy.KEEP,
                            OneTimeWorkRequestBuilder<GoogleDriveSyncWorker>().build())
                    }
                }
            }
        })
        scope.launch {
            settings.settings.map { it.enabled }.distinctUntilChanged().collect { enabled ->
                if (enabled) manager.start() else manager.stop()
            }
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

@HiltWorker
class GoogleDriveSyncWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted parameters: WorkerParameters,
    private val manager: GoogleDriveSyncManager,
) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        manager.syncInBackground()
        return Result.success()
    }
}
