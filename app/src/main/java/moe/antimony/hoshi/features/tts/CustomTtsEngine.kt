package moe.antimony.hoshi.features.tts

import java.io.File
import java.security.MessageDigest
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*
import moe.antimony.hoshi.di.IoDispatcher
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/** Self-hosted protocols share the reader's file-based playback and export pipeline. */
@Singleton
class CustomTtsEngine internal constructor(
    private val transport: OnlineTtsTransport,
    private val decryptKey: suspend (String, String) -> String,
    private val io: CoroutineDispatcher,
    private val store: TtsVoiceCatalogStore? = null,
) {
    @Inject constructor(transport: OnlineTtsTransport, cipher: TtsCredentialCipher, @IoDispatcher io: CoroutineDispatcher,
        store: TtsVoiceCatalogStore) : this(transport, cipher::decrypt, io, store)
    private val synthesis = Mutex()
    private val catalogs = Mutex()
    private val voiceCache = linkedMapOf<String, List<TtsVoiceInfo>>()
    private fun source(settings: TtsSettings) = settings.customSource()?.validate() ?: throw TtsException(TtsFailure.Configuration)
    private fun fingerprint(source: TtsCustomSource): String = MessageDigest.getInstance("SHA-256")
        .digest("${source.id}\u0000${source.protocol}\u0000${source.baseUrl}\u0000${source.encryptedKey}".toByteArray())
        .joinToString("") { "%02x".format(it) }
    private fun catalog(source: TtsCustomSource, voices: List<TtsVoiceInfo>) = TtsCatalog(emptyList(), source.id, source.voiceChoices(voices))

    suspend fun cachedCatalog(settings: TtsSettings): TtsCatalog? = withContext(io) {
        val source = source(settings)
        catalogs.withLock {
            val key = fingerprint(source)
            (voiceCache[key] ?: store?.readCustom(source.id, key)?.also { remember(key, it) })?.let { catalog(source, it) }
        }
    }
    suspend fun catalog(settings: TtsSettings, force: Boolean = false): TtsCatalog = withContext(io) {
        val source = source(settings)
        catalogs.withLock {
            val key = fingerprint(source)
            if (!force) (voiceCache[key] ?: store?.readCustom(source.id, key))?.let {
                remember(key, it); return@withLock catalog(source, it)
            }
            val voices = when (source.protocol) {
                TtsCustomProtocol.OpenAi -> try {
                    openAiVoices(request(source, "audio/voices"))
                } catch (error: TtsException) {
                    // Voice discovery is a non-standard extension; manually configured IDs remain usable.
                    if (error.statusCode != 404 && error.statusCode != 405) throw error
                    emptyList()
                }
                TtsCustomProtocol.Vits -> vitsVoices(request(source, "voice/speakers"))
            }
            remember(key, voices); store?.writeCustom(source.id, key, voices)
            catalog(source, voices)
        }
    }
    private fun remember(key: String, voices: List<TtsVoiceInfo>) {
        voiceCache[key] = voices
        if (voiceCache.size > 32) voiceCache.remove(voiceCache.keys.first())
    }
    suspend fun models(source: TtsCustomSource): List<String> = withContext(io) {
        try {
            val data = parse(request(source, "models")).jsonObject["data"]?.jsonArray.orEmpty()
            data.mapNotNull { it.jsonObject["id"]?.jsonPrimitive?.contentOrNull }.distinct()
        } catch (error: CancellationException) { throw error }
        catch (_: Exception) { emptyList() }
    }

    suspend fun synthesize(text: String, settings: TtsSettings, directory: File): List<File> {
        val files = mutableListOf<File>()
        try {
            return withContext(io) { synthesis.withLock {
                val source = source(settings)
                if (text.isBlank()) throw TtsException(TtsFailure.EmptyText)
                val speaker = if (source.protocol == TtsCustomProtocol.Vits) {
                    vitsSpeaker(source.voice.ifBlank { catalog(settings).voices.firstOrNull()?.id.orEmpty() })
                        ?: throw TtsException(TtsFailure.MissingJapanese)
                } else null
                directory.mkdirs()
                for (part in OnlineTtsProtocol.chunks(text, 3000)) {
                    currentCoroutineContext().ensureActive()
                    val body = buildJsonObject {
                        if (source.protocol == TtsCustomProtocol.OpenAi) {
                            put("model", source.model); put("input", part)
                            if (source.voice.isNotBlank()) put("voice", source.voice)
                            put("speed", source.normalizeRate(source.rate)); put("response_format", source.format.name.lowercase())
                        } else {
                            put("text", part); put("id", speaker!!.second); put("lang", "ja")
                            put("format", source.format.name.lowercase()); put("streaming", false)
                            if (speaker.first != "GPT-SOVITS") put("length", 1f / source.normalizeRate(source.rate))
                            else if (source.preset.isNotBlank()) put("preset", source.preset)
                        }
                    }
                    val path = if (speaker == null) "audio/speech" else "voice/${vitsModels.getValue(speaker.first)}"
                    val bytes = request(source, path, body)
                    currentCoroutineContext().ensureActive()
                    validateTtsAudio(bytes, source.format)
                    File(directory, "${UUID.randomUUID()}.${source.format.name.lowercase()}").also {
                        files += it; it.writeBytes(bytes)
                    }
                }
                files
            } }
        } catch (error: Throwable) {
            withContext(NonCancellable + io) { files.forEach { it.delete() } }
            throw error
        }
    }
    private suspend fun request(source: TtsCustomSource, path: String, body: JsonObject? = null): ByteArray {
        val url = customTtsBaseUrl(source.baseUrl).newBuilder().apply { path.split('/').forEach { addPathSegment(it) } }.build()
        val request = Request.Builder().url(url)
        if (source.encryptedKey.isNotBlank()) {
            val key = decryptKey(source.encryptedKey, source.id)
            if (key.isBlank() || key.any { it <= ' ' || it >= '\u007f' }) throw TtsException(TtsFailure.Configuration)
            if (source.protocol == TtsCustomProtocol.OpenAi) request.header("Authorization", "Bearer $key") else request.header("X-API-KEY", key)
        }
        if (body != null) request.post(body.toString().toByteArray(Charsets.UTF_8).toRequestBody("application/json".toMediaType()))
        return transport.request(request.build(), selfHosted = true)
    }

    private fun parse(bytes: ByteArray): JsonElement = try { Json.parseToJsonElement(bytes.toString(Charsets.UTF_8)) }
        catch (_: Exception) { throw TtsException(TtsFailure.InvalidResponse) }
    private fun openAiVoices(bytes: ByteArray): List<TtsVoiceInfo> = try {
        val root = parse(bytes)
        val values = if (root is JsonArray) root else root.jsonObject["voices"] ?: root.jsonObject["data"] ?: JsonArray(emptyList())
        values.jsonArray.mapNotNull { value ->
            if (value is JsonPrimitive) value.contentOrNull?.let { TtsVoiceInfo(it, true) }
            else {
                val obj = value.jsonObject
                val id = obj["id"]?.jsonPrimitive?.contentOrNull ?: obj["voice_id"]?.jsonPrimitive?.contentOrNull ?: obj["name"]?.jsonPrimitive?.contentOrNull
                id?.let { TtsVoiceInfo(it, true, label = obj["name"]?.jsonPrimitive?.contentOrNull ?: it) }
            }
        }.filter { it.id.isNotBlank() }.distinctBy { it.id }
    } catch (_: Exception) { throw TtsException(TtsFailure.InvalidResponse) }
    private fun vitsVoices(bytes: ByteArray): List<TtsVoiceInfo> = try {
        val root = parse(bytes).jsonObject
        vitsModels.keys.flatMap { model -> root[model]?.jsonArray.orEmpty().mapNotNull { value ->
            val obj = value.jsonObject
            val languages = obj["lang"]?.let { if (it is JsonArray) it.map { lang -> lang.jsonPrimitive.content } else listOf(it.jsonPrimitive.content) }.orEmpty()
            val id = obj["id"]?.jsonPrimitive?.intOrNull
            if (id == null || id < 0 || languages.isNotEmpty() && languages.none { it == "ja" || it == "jpn" || it.startsWith("ja-") }) null
            else TtsVoiceInfo("$model:$id", true, label = obj["name"]?.jsonPrimitive?.contentOrNull ?: "$model:$id")
        } }.distinctBy { it.id }
    } catch (_: Exception) { throw TtsException(TtsFailure.InvalidResponse) }
}

internal fun validateTtsAudio(bytes: ByteArray, format: TtsAudioFormat) {
    if (format == TtsAudioFormat.Mp3) { TtsMp3.validate(bytes); return }
    if (bytes.size <= 44 || String(bytes, 0, 4, Charsets.US_ASCII) != "RIFF" || String(bytes, 8, 4, Charsets.US_ASCII) != "WAVE")
        throw TtsException(TtsFailure.InvalidResponse)
    var cursor = 12
    var hasFormat = false
    var hasData = false
    while (cursor + 8 <= bytes.size) {
        val size = java.nio.ByteBuffer.wrap(bytes, cursor + 4, 4).order(java.nio.ByteOrder.LITTLE_ENDIAN).int
        if (size < 0 || cursor.toLong() + 8 + size > bytes.size) throw TtsException(TtsFailure.InvalidResponse)
        when (String(bytes, cursor, 4, Charsets.US_ASCII)) { "fmt " -> hasFormat = size >= 16; "data" -> hasData = size > 0 }
        cursor += 8 + size + size % 2
    }
    if (!hasFormat || !hasData) throw TtsException(TtsFailure.InvalidResponse)
}
