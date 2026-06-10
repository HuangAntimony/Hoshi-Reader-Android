package moe.antimony.hoshi.features.dictionary

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import moe.antimony.hoshi.dictionary.DictionaryUpdateProgress

internal enum class DictionaryMutationOperation {
    Import,
    RecommendedImport,
    ManualUpdate,
    AutoUpdate,
    Edit,
}

internal data class DictionaryMutationState(
    val operation: DictionaryMutationOperation? = null,
    val progress: DictionaryUpdateProgress? = null,
    val completedChangeVersion: Long = 0L,
) {
    val isInProgress: Boolean
        get() = operation != null
}

internal class DictionaryMutationSession internal constructor(
    private val updateState: ((DictionaryMutationState) -> DictionaryMutationState) -> Unit,
) {
    internal var changed = false
        private set

    fun report(progress: DictionaryUpdateProgress) {
        updateState { it.copy(progress = progress) }
    }

    fun markDictionariesChanged() {
        changed = true
    }
}

@Singleton
internal class DictionaryMutationCoordinator @Inject constructor() {
    private val mutex = Mutex()
    private val _state = MutableStateFlow(DictionaryMutationState())

    val state: StateFlow<DictionaryMutationState> = _state.asStateFlow()

    val isMutationInProgress: Boolean
        get() = mutex.isLocked

    suspend fun <T> runExclusive(
        operation: DictionaryMutationOperation,
        block: suspend DictionaryMutationSession.() -> T,
    ): T? {
        if (!mutex.tryLock()) return null
        val session = DictionaryMutationSession { transform ->
            _state.update(transform)
        }
        _state.update { it.copy(operation = operation, progress = null) }
        try {
            return session.block()
        } finally {
            _state.update { current ->
                current.copy(
                    operation = null,
                    progress = null,
                    completedChangeVersion = if (session.changed) {
                        current.completedChangeVersion + 1L
                    } else {
                        current.completedChangeVersion
                    },
                )
            }
            mutex.unlock()
        }
    }
}
