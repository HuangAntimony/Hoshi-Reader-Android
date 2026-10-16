package moe.antimony.hoshi.features.tts

import java.io.File
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

    @Test fun legacyCatalogFilesRemainReadableAndConfigurationChangesStayIsolated() = runTest {
        // These are the filenames/fingerprints written before cache consolidation.
        File(folder.root, "Azure.json").writeText("""{"configuration":"a4254c9ed1bcf2ebeb637c69a4c541dabe70f6ebc1fcba28587197051542d6b6","voices":[{"id":"azure","network":true}]}""")
        File(folder.root, "custom-34ae4337cab3895c3e841ef4ce399d2c6a593edee6419979be25296ec05205d2.json")
            .writeText("""{"configuration":"8f0cf204bbd7908c536174b18ff0e882c23b9c733fee3d47c950b7e548f4e2ad","voices":[{"id":"local","network":true}]}""")
        val cache = TtsVoiceCatalogCache(TtsVoiceCatalogStore(folder.root))
        assertEquals("azure", cache.cached(online)!!.single().id)
        assertEquals("local", cache.cached(custom)!!.single().id)
        assertNull(cache.cached(TtsVoiceCatalogKey.online(TtsOnlineSource.Azure, TtsOnlineConfig("eastasia", "new-key"))))
        cache.load(custom, true) { fresh }
        assertEquals(fresh, TtsVoiceCatalogCache(TtsVoiceCatalogStore(folder.root)).cached(custom))
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
