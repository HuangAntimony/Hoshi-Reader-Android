package moe.antimony.hoshi.features.audio

import org.junit.Assert.assertEquals
import org.junit.Test

class LocalAudioSourceConfigTest {
    @Test
    fun defaultOrderSortsKnownSourcesByBuiltInPriorityAndUnknownSourcesByName() {
        val order = LocalAudioSourceOrder.defaultOrder(
            listOf("custom_b", "forvo", "nhk16", "custom_a", "jpod"),
        )

        assertEquals(listOf("nhk16", "jpod", "forvo", "custom_a", "custom_b"), order)
    }

    @Test
    fun missingConfigCreatesDefaultOrderForExistingDatabaseSources() {
        val config = LocalAudioSourceConfig.defaultFor(
            setOf("forvo", "nhk16", "custom_a"),
        )

        assertEquals(LocalAudioSourceConfig(sourceOrder = listOf("nhk16", "forvo", "custom_a")), config)
    }

    @Test
    fun repairKeepsCustomOrderDropsMissingSourcesAndAppendsNewSources() {
        val repaired = LocalAudioSourceConfig(
            sourceOrder = listOf("forvo", "missing", "nhk16"),
        ).repair(availableSources = setOf("nhk16", "forvo", "jpod", "custom_a"))

        assertEquals(
            LocalAudioSourceConfig(sourceOrder = listOf("forvo", "nhk16", "jpod", "custom_a")),
            repaired,
        )
    }

    @Test
    fun cacheLoadsSourceConfigOnlyOnceUntilInvalidated() {
        var loads = 0
        val cache = LocalAudioSourceConfigCache {
            loads += 1
            LocalAudioSourceConfig(sourceOrder = listOf("nhk16", "forvo"))
        }

        assertEquals(listOf("nhk16", "forvo"), cache.get().sourceOrder)
        assertEquals(listOf("nhk16", "forvo"), cache.get().sourceOrder)
        assertEquals(1, loads)

        cache.clear()
        assertEquals(listOf("nhk16", "forvo"), cache.get().sourceOrder)
        assertEquals(2, loads)
    }

    @Test
    fun cacheReplaceUpdatesValueWithoutReloading() {
        var loads = 0
        val cache = LocalAudioSourceConfigCache {
            loads += 1
            LocalAudioSourceConfig(sourceOrder = listOf("nhk16", "forvo"))
        }

        assertEquals(listOf("nhk16", "forvo"), cache.get().sourceOrder)
        cache.replace(LocalAudioSourceConfig(sourceOrder = listOf("forvo", "nhk16")))

        assertEquals(listOf("forvo", "nhk16"), cache.get().sourceOrder)
        assertEquals(1, loads)
    }
}
