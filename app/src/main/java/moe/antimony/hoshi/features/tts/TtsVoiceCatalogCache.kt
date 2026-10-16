package moe.antimony.hoshi.features.tts

import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Keys preserve the disk names and configuration fingerprints used by earlier releases. */
internal data class TtsVoiceCatalogKey(val fileName: String, val configuration: String, val allowEmpty: Boolean) {
    companion object {
        fun online(source: TtsOnlineSource, config: TtsOnlineConfig) = TtsVoiceCatalogKey(
            "${source.name}.json", hash("${config.region}\u0000${config.encryptedKey}"), false)
        fun custom(source: TtsCustomSource) = TtsVoiceCatalogKey("custom-${hash(source.id)}.json",
            hash("${source.id}\u0000${source.protocol}\u0000${source.baseUrl}\u0000${source.encryptedKey}"), true)
        private fun hash(value: String) = MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray()).joinToString("") { "%02x".format(it) }
    }
}

internal fun TtsSettings.voiceCatalogKey(): TtsVoiceCatalogKey? = customSource()?.let(TtsVoiceCatalogKey::custom)
    ?: TtsOnlineSource.from(engine)?.let { TtsVoiceCatalogKey.online(it, online[engine] ?: TtsOnlineConfig()) }

/** Called on the adapters' IO dispatcher. Network waits never hold the cache metadata lock. */
@Singleton
class TtsVoiceCatalogCache internal constructor(private val store: TtsVoiceCatalogStore? = null, private val capacity: Int = 32) {
    @Inject constructor(store: TtsVoiceCatalogStore) : this(store, 32)
    private val metadata = Mutex()
    private val voices = linkedMapOf<TtsVoiceCatalogKey, List<TtsVoiceInfo>>()
    private class Request(val mutex: Mutex = Mutex(), var users: Int = 0)
    private val requests = mutableMapOf<TtsVoiceCatalogKey, Request>()

    internal suspend fun cached(key: TtsVoiceCatalogKey): List<TtsVoiceInfo>? = metadata.withLock {
        voices[key] ?: store?.read(key)?.also { remember(key, it) }
    }

    internal suspend fun load(key: TtsVoiceCatalogKey, force: Boolean, fetch: suspend () -> List<TtsVoiceInfo>): List<TtsVoiceInfo> {
        if (!force) cached(key)?.let { return it }
        val request = metadata.withLock { requests.getOrPut(key) { Request() }.also { it.users++ } }
        try {
            return request.mutex.withLock {
                if (!force) cached(key)?.let { return@withLock it }
                val result = fetch()
                currentCoroutineContext().ensureActive()
                if (result.isEmpty() && !key.allowEmpty) throw TtsException(TtsFailure.MissingJapanese)
                metadata.withLock {
                    remember(key, result)
                    store?.write(key, result)
                }
                result
            }
        } finally {
            withContext(NonCancellable) { metadata.withLock {
                if (--request.users == 0) requests.remove(key)
            } }
        }
    }

    private fun remember(key: TtsVoiceCatalogKey, value: List<TtsVoiceInfo>) {
        voices[key] = value
        if (voices.size > capacity) voices.remove(voices.keys.first())
    }
}
