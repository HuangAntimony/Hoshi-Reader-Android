package moe.antimony.hoshi.features.dictionary

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import moe.antimony.hoshi.di.IoDispatcher
import moe.antimony.hoshi.dictionary.DictionaryRename
import moe.antimony.hoshi.dictionary.DictionaryRepository
import moe.antimony.hoshi.dictionary.DictionaryUpdateProgress
import moe.antimony.hoshi.dictionary.DictionaryUpdateSummary
import moe.antimony.hoshi.features.anki.AnkiSettingsRepository

internal interface DictionaryUpdateClock {
    fun currentTimeMillis(): Long
}

private object SystemDictionaryUpdateClock : DictionaryUpdateClock {
    override fun currentTimeMillis(): Long = System.currentTimeMillis()
}

@Singleton
internal class DictionaryUpdateService(
    private val dictionaryRepository: DictionaryRepository,
    private val dictionarySettingsRepository: DictionarySettingsRepository,
    private val ankiSettingsRepository: AnkiSettingsRepository,
    private val ioDispatcher: CoroutineDispatcher,
    private val clock: DictionaryUpdateClock,
) {
    @Inject
    constructor(
        dictionaryRepository: DictionaryRepository,
        dictionarySettingsRepository: DictionarySettingsRepository,
        ankiSettingsRepository: AnkiSettingsRepository,
        @IoDispatcher ioDispatcher: CoroutineDispatcher,
    ) : this(
        dictionaryRepository = dictionaryRepository,
        dictionarySettingsRepository = dictionarySettingsRepository,
        ankiSettingsRepository = ankiSettingsRepository,
        ioDispatcher = ioDispatcher,
        clock = SystemDictionaryUpdateClock,
    )

    private val updateMutex = Mutex()

    val isMutationInProgress: Boolean
        get() = updateMutex.isLocked

    suspend fun updateDictionaries(
        onProgress: (DictionaryUpdateProgress) -> Unit = {},
    ): DictionaryUpdateSummary {
        if (!updateMutex.tryLock()) {
            return DictionaryUpdateSummary(
                checkedCount = 0,
                successfulCount = 0,
                updatedCount = 0,
            )
        }
        try {
            return withContext(ioDispatcher) {
                val settings = dictionarySettingsRepository.settings.first()
                val summary = dictionaryRepository.updateDictionaries(
                    lowRamImport = settings.lowRamDictionaryImport,
                    onProgress = onProgress,
                )
                persistUpdateEffects(summary)
                summary
            }
        } finally {
            updateMutex.unlock()
        }
    }

    private suspend fun persistUpdateEffects(summary: DictionaryUpdateSummary) {
        if (summary.renamedDictionaries.isEmpty() && summary.successfulCount <= 0) return
        dictionarySettingsRepository.update { current ->
            val renamed = if (summary.renamedDictionaries.isEmpty()) {
                current.collapsedDictionaries
            } else {
                current.collapsedDictionaries.renamedBy(summary.renamedDictionaries)
            }
            current.copy(
                collapsedDictionaries = renamed,
                lastDictionaryUpdateEpochMillis = if (summary.successfulCount > 0) {
                    clock.currentTimeMillis()
                } else {
                    current.lastDictionaryUpdateEpochMillis
                },
            )
        }
        if (summary.renamedDictionaries.isNotEmpty()) {
            ankiSettingsRepository.update { current ->
                current.copy(
                    fieldMappings = current.fieldMappings.mapValues { (_, template) ->
                        summary.renamedDictionaries.fold(template) { value, rename ->
                            value.replace(
                                oldValue = "{single-glossary-${rename.oldTitle}}",
                                newValue = "{single-glossary-${rename.newTitle}}",
                            )
                        }
                    },
                )
            }
        }
    }
}

private fun Set<String>.renamedBy(renames: List<DictionaryRename>): Set<String> {
    val renameMap = renames.associate { it.oldTitle to it.newTitle }
    return mapTo(mutableSetOf()) { title -> renameMap[title] ?: title }
}
