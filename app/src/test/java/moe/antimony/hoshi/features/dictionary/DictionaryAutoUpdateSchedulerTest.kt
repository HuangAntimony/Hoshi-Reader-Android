package moe.antimony.hoshi.features.dictionary

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DictionaryAutoUpdateSchedulerTest {
    @Test
    fun autoUpdateIsDueWhenEnabledWithUpdatableDictionariesAndNoLastUpdate() {
        val settings = DictionarySettings(
            autoUpdateDictionaries = true,
            dictionaryUpdateInterval = DictionaryUpdateInterval.Weekly,
            lastDictionaryUpdateEpochMillis = null,
        )

        assertTrue(
            shouldEnqueueDictionaryAutoUpdate(
                settings = settings,
                nowEpochMillis = 1_900_000_000_000L,
                hasUpdatableDictionaries = true,
                isMutationInProgress = false,
            ),
        )
    }

    @Test
    fun autoUpdateIsSkippedWhenDisabledOrThereAreNoUpdatableDictionaries() {
        assertFalse(
            shouldEnqueueDictionaryAutoUpdate(
                settings = DictionarySettings(autoUpdateDictionaries = false),
                nowEpochMillis = 1_900_000_000_000L,
                hasUpdatableDictionaries = true,
                isMutationInProgress = false,
            ),
        )
        assertFalse(
            shouldEnqueueDictionaryAutoUpdate(
                settings = DictionarySettings(autoUpdateDictionaries = true),
                nowEpochMillis = 1_900_000_000_000L,
                hasUpdatableDictionaries = false,
                isMutationInProgress = false,
            ),
        )
    }

    @Test
    fun autoUpdateRespectsIntervalAndBusyState() {
        val lastUpdate = 1_900_000_000_000L
        val settings = DictionarySettings(
            autoUpdateDictionaries = true,
            dictionaryUpdateInterval = DictionaryUpdateInterval.Weekly,
            lastDictionaryUpdateEpochMillis = lastUpdate,
        )

        assertFalse(
            shouldEnqueueDictionaryAutoUpdate(
                settings = settings,
                nowEpochMillis = lastUpdate + DictionaryUpdateInterval.Weekly.intervalMillis - 1L,
                hasUpdatableDictionaries = true,
                isMutationInProgress = false,
            ),
        )
        assertTrue(
            shouldEnqueueDictionaryAutoUpdate(
                settings = settings,
                nowEpochMillis = lastUpdate + DictionaryUpdateInterval.Weekly.intervalMillis,
                hasUpdatableDictionaries = true,
                isMutationInProgress = false,
            ),
        )
        assertFalse(
            shouldEnqueueDictionaryAutoUpdate(
                settings = settings,
                nowEpochMillis = lastUpdate + DictionaryUpdateInterval.Weekly.intervalMillis,
                hasUpdatableDictionaries = true,
                isMutationInProgress = true,
            ),
        )
    }
}
