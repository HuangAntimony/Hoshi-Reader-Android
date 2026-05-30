package moe.antimony.hoshi.features.audio

import kotlinx.serialization.Serializable

@Serializable
data class LocalAudioSourceConfig(
    val version: Int = CurrentVersion,
    val sourceOrder: List<String> = emptyList(),
) {
    fun repair(availableSources: Set<String>): LocalAudioSourceConfig {
        val kept = sourceOrder
            .filter { it in availableSources }
            .distinct()
        val appended = LocalAudioSourceOrder.defaultOrder(availableSources - kept.toSet())
        return copy(version = CurrentVersion, sourceOrder = kept + appended)
    }

    companion object {
        const val CurrentVersion = 1

        fun defaultFor(availableSources: Set<String>): LocalAudioSourceConfig =
            LocalAudioSourceConfig(sourceOrder = LocalAudioSourceOrder.defaultOrder(availableSources))
    }
}

object LocalAudioSourceOrder {
    val BuiltInPriority = listOf(
        "nhk16",
        "daijisen",
        "shinmeikai8",
        "jpod",
        "jpod_alternate",
        "taas",
        "ozk5",
        "forvo",
        "forvo_ext",
        "forvo_ext2",
    )

    fun defaultOrder(sources: Collection<String>): List<String> =
        sources
            .filter { it.isNotBlank() }
            .distinct()
            .sortedWith(
                compareBy<String> {
                    val index = BuiltInPriority.indexOf(it)
                    if (index >= 0) index else Int.MAX_VALUE
                }.thenBy { it },
            )
}

internal class LocalAudioSourceConfigCache(
    private val load: () -> LocalAudioSourceConfig,
) {
    @Volatile
    private var cached: LocalAudioSourceConfig? = null

    fun get(): LocalAudioSourceConfig =
        cached ?: synchronized(this) {
            cached ?: load().also { cached = it }
        }

    fun replace(config: LocalAudioSourceConfig) {
        synchronized(this) {
            cached = config
        }
    }

    fun clear() {
        synchronized(this) {
            cached = null
        }
    }
}
