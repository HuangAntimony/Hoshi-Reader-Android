package moe.antimony.hoshi.features.dictionary

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dagger.Lazy
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import moe.antimony.hoshi.di.ApplicationScope
import moe.antimony.hoshi.di.IoDispatcher
import moe.antimony.hoshi.dictionary.DictionaryRepository

internal fun shouldEnqueueDictionaryAutoUpdate(
    settings: DictionarySettings,
    nowEpochMillis: Long,
    hasUpdatableDictionaries: Boolean,
    isMutationInProgress: Boolean,
): Boolean {
    if (!settings.autoUpdateDictionaries) return false
    if (!hasUpdatableDictionaries) return false
    if (isMutationInProgress) return false
    val lastUpdate = settings.lastDictionaryUpdateEpochMillis ?: return true
    return nowEpochMillis - lastUpdate >= settings.dictionaryUpdateInterval.intervalMillis
}

@Singleton
internal class DictionaryAutoUpdateScheduler @Inject constructor(
    private val dictionarySettingsRepository: DictionarySettingsRepository,
    private val dictionaryRepository: DictionaryRepository,
    private val dictionaryUpdateService: DictionaryUpdateService,
    private val workManager: Lazy<WorkManager>,
    @param:ApplicationScope private val appScope: CoroutineScope,
    @param:IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) {
    fun registerProcessForegroundChecks(
        lifecycle: Lifecycle = ProcessLifecycleOwner.get().lifecycle,
    ) {
        lifecycle.addObserver(
            object : DefaultLifecycleObserver {
                override fun onStart(owner: LifecycleOwner) {
                    onAppForeground()
                }
            },
        )
    }

    fun onAppForeground() {
        appScope.launch(ioDispatcher) {
            enqueueIfDue()
        }
    }

    internal suspend fun enqueueIfDue(
        nowEpochMillis: Long = System.currentTimeMillis(),
    ) {
        val settings = dictionarySettingsRepository.settings.first()
        val hasUpdatableDictionaries = dictionaryRepository.updatableDictionaries().isNotEmpty()
        if (!shouldEnqueueDictionaryAutoUpdate(
                settings = settings,
                nowEpochMillis = nowEpochMillis,
                hasUpdatableDictionaries = hasUpdatableDictionaries,
                isMutationInProgress = dictionaryUpdateService.isMutationInProgress,
            )
        ) {
            return
        }
        val request = OneTimeWorkRequestBuilder<DictionaryAutoUpdateWorker>()
            .setConstraints(networkConstraints())
            .build()
        workManager.get().enqueueUniqueWork(
            UniqueWorkName,
            ExistingWorkPolicy.KEEP,
            request,
        )
    }

    private fun networkConstraints(): Constraints =
        Constraints.Builder()
            .setRequiredNetworkType(NetworkType.UNMETERED)
            .build()

    companion object {
        const val UniqueWorkName = "dictionary-auto-update"
    }
}

@HiltWorker
internal class DictionaryAutoUpdateWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val dictionaryUpdateService: DictionaryUpdateService,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result =
        runCatching {
            dictionaryUpdateService.updateDictionaries()
        }.fold(
            onSuccess = { Result.success() },
            onFailure = { Result.retry() },
        )
}
