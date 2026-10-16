package moe.antimony.hoshi.features.tts

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class TtsVoiceCatalogCacheTest {
    @get:Rule val folder = TemporaryFolder()
    private val old = listOf(TtsVoiceInfo("old", true))
    private val fresh = listOf(TtsVoiceInfo("new", true))
    private val online = TtsVoiceCatalogKey.online(TtsOnlineSource.Azure, TtsOnlineConfig("eastasia", "encrypted"))
    private val custom = TtsVoiceCatalogKey.custom(TtsCustomSource("custom:test", "Local",
        baseUrl = "https://example.com/v1/", encryptedKey = "encrypted", model = "tts"))

    @Test fun pendingLoadCoalescesForTheSameConfigurationButDoesNotBlockOtherSources() = runTest {
        val cache = TtsVoiceCatalogCache()
        val result = CompletableDeferred<List<TtsVoiceInfo>>()
        var calls = 0
        val first = async { cache.load(online, false) { calls++; result.await() } }
        val second = async { cache.load(online, false) { calls++; fresh } }
        runCurrent()
        assertEquals(old, cache.load(custom, false) { old })
        assertEquals(1, calls)
        result.complete(fresh)
        assertEquals(fresh, first.await())
        assertEquals(fresh, second.await())
        assertEquals(1, calls)
    }

    @Test fun failedOrCancelledRefreshKeepsTheLastCatalogAndCanBeRetried() = runTest {
        val cache = TtsVoiceCatalogCache()
        cache.load(online, false) { old }
        val blocked = CompletableDeferred<List<TtsVoiceInfo>>()
        val refresh = async { cache.load(online, true) { blocked.await() } }
        runCurrent()
        assertEquals(old, cache.cached(online))
        assertEquals(old, cache.load(online, false) { fail("Unexpected request"); emptyList() })
        refresh.cancelAndJoin()
        try { cache.load(online, true) { throw TtsException(TtsFailure.Network) }; fail("Expected failure") }
        catch (error: TtsException) { assertEquals(TtsFailure.Network, error.failure) }
        assertEquals(old, cache.cached(online))
        assertEquals(fresh, cache.load(online, true) { fresh })
    }

    @Test fun catalogsPersistAcrossCacheRecreationAndConfigurationChangesStayIsolated() = runTest {
        val store = TtsVoiceCatalogStore(folder.root)
        val cache = TtsVoiceCatalogCache(store)
        cache.load(online, false) { old }
        cache.load(custom, false) { fresh }
        val reloaded = TtsVoiceCatalogCache(store)
        assertEquals(old, reloaded.cached(online))
        assertEquals(fresh, reloaded.cached(custom))
        assertNull(reloaded.cached(TtsVoiceCatalogKey.online(TtsOnlineSource.Azure, TtsOnlineConfig("eastasia", "new-key"))))
        reloaded.load(custom, true) { old }
        assertEquals(old, TtsVoiceCatalogCache(store).cached(custom))
    }

    @Test fun optionalEmptyCatalogSurvivesReloadButRequiredEmptyRefreshDoesNotOverwriteVoices() = runTest {
        val store = TtsVoiceCatalogStore(folder.root)
        val cache = TtsVoiceCatalogCache(store)
        cache.load(custom, false) { emptyList() }
        assertEquals(emptyList<TtsVoiceInfo>(), TtsVoiceCatalogCache(store).cached(custom))
        cache.load(online, false) { old }
        try { cache.load(online, true) { emptyList() }; fail("Expected missing voices") }
        catch (error: TtsException) { assertEquals(TtsFailure.MissingJapanese, error.failure) }
        assertEquals(old, TtsVoiceCatalogCache(store).cached(online))
    }

    @Test fun evictedMemoryEntriesRemainAvailableOnDisk() = runTest {
        val cache = TtsVoiceCatalogCache(TtsVoiceCatalogStore(folder.root), 1)
        cache.load(online, false) { old }
        cache.load(custom, false) { fresh }
        assertEquals(old, cache.load(online, false) { fail("Eviction must not force discovery"); emptyList() })
    }
}
