package moe.antimony.hoshi.features.update

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import moe.antimony.hoshi.BuildConfig
import java.util.concurrent.TimeUnit

internal object UpdateScheduler {
    const val UniqueWorkName = "github-release-update-check"
    const val UniqueImmediateWorkName = "github-release-update-check-now"

    fun sync(context: Context) {
        val appContext = context.applicationContext
        CoroutineScope(Dispatchers.IO).launch {
            syncNow(appContext)
        }
    }

    suspend fun syncNow(context: Context) {
        val enabled = context.updateSettingsRepository().settings.first().autoDownloadUpdates
        if (enabled) {
            schedule(context)
            scheduleImmediateCheck(context)
        } else {
            cancel(context)
        }
    }

    fun schedule(context: Context) {
        val request = PeriodicWorkRequestBuilder<UpdateCheckWorker>(1, TimeUnit.DAYS)
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build(),
            )
            .build()
        WorkManager.getInstance(context.applicationContext).enqueueUniquePeriodicWork(
            UniqueWorkName,
            ExistingPeriodicWorkPolicy.UPDATE,
            request,
        )
    }

    fun cancel(context: Context) {
        val workManager = WorkManager.getInstance(context.applicationContext)
        workManager.cancelUniqueWork(UniqueWorkName)
        workManager.cancelUniqueWork(UniqueImmediateWorkName)
    }

    fun scheduleImmediateCheck(context: Context) {
        val request = OneTimeWorkRequestBuilder<UpdateCheckWorker>()
            .setConstraints(networkConstraints())
            .build()
        WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
            UniqueImmediateWorkName,
            ExistingWorkPolicy.KEEP,
            request,
        )
    }

    private fun networkConstraints(): Constraints =
        Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()
}

internal class UpdateCheckWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val enabled = applicationContext.updateSettingsRepository().settings.first().autoDownloadUpdates
        if (!enabled) return Result.success()
        return runCatching {
            updateCheckService(applicationContext).check(downloadIfAvailable = true)
        }.fold(
            onSuccess = { Result.success() },
            onFailure = { Result.retry() },
        )
    }
}

internal fun updateCheckService(context: Context): UpdateCheckService =
    UpdateCheckService(
        currentVersionName = BuildConfig.VERSION_NAME,
        releaseRepository = GitHubReleaseUpdateRepository(),
        downloadController = AndroidUpdateDownloadManager(
            context = context.applicationContext,
            store = context.applicationContext.updateDownloadStore(),
        ),
    )
