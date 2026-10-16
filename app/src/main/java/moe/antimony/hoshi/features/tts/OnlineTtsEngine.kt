package moe.antimony.hoshi.features.tts

import java.io.File
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import moe.antimony.hoshi.di.IoDispatcher
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

@Singleton
class OnlineTtsEngine internal constructor(
    private val transport: OnlineTtsTransport,
    private val decryptKey: suspend (String, String) -> String,
    private val io: CoroutineDispatcher,
    private val translator: MicrosoftTranslatorSession = MicrosoftTranslatorSession(transport),
    private val catalogs: TtsVoiceCatalogCache = TtsVoiceCatalogCache(),
) {
    @Inject constructor(transport: OnlineTtsTransport, cipher: TtsCredentialCipher, @IoDispatcher io: CoroutineDispatcher,
        translator: MicrosoftTranslatorSession, catalogs: TtsVoiceCatalogCache) : this(transport, cipher::decrypt, io, translator, catalogs)
    internal constructor(transport: OnlineTtsTransport, cipher: TtsCredentialCipher, io: CoroutineDispatcher) : this(transport, cipher::decrypt, io)
    private val synthesis = Mutex()
    suspend fun cachedCatalog(settings: TtsSettings): TtsCatalog? = withContext(io) {
        val source = source(settings)
        val config = settings.online[source.id] ?: TtsOnlineConfig()
        catalogs.cached(TtsVoiceCatalogKey.online(source, config))?.let { TtsCatalog(source.id, it) }
    }

    suspend fun catalog(settings: TtsSettings, force: Boolean = false): TtsCatalog = withContext(io) {
        val source = source(settings)
        val config = settings.online[source.id] ?: TtsOnlineConfig()
        val voices = catalogs.load(TtsVoiceCatalogKey.online(source, config), force) { fetchVoices(source, config) }
        TtsCatalog(source.id, voices)
    }

    private suspend fun fetchVoices(source: TtsOnlineSource, config: TtsOnlineConfig): List<TtsVoiceInfo> {
        val voices = when (source) {
            TtsOnlineSource.GoogleTranslate -> listOf(TtsVoiceInfo("ja", true))
            TtsOnlineSource.Edge -> OnlineTtsProtocol.microsoftVoices(transport.request(edgeRequest(false)).toString(Charsets.UTF_8))
            TtsOnlineSource.Azure, TtsOnlineSource.MicrosoftTranslator -> OnlineTtsProtocol.microsoftVoices(
                azureSpeechRequest(source, config, "/cognitiveservices/voices/list").toString(Charsets.UTF_8))
            TtsOnlineSource.GoogleCloud -> OnlineTtsProtocol.googleVoices(transport.request(Request.Builder()
                .url("https://texttospeech.googleapis.com/v1/voices?languageCode=ja-JP")
                .header("X-Goog-Api-Key", key(source, config)).build()).toString(Charsets.UTF_8))
        }
        return voices
    }

    suspend fun synthesize(text: String, settings: TtsSettings, directory: File): List<File> {
        val files = mutableListOf<File>()
        try {
            return withContext(io) { synthesis.withLock {
                if (text.isBlank()) throw TtsException(TtsFailure.EmptyText)
                val source = source(settings)
                val config = settings.online[source.id] ?: TtsOnlineConfig()
                val voices = catalog(settings).voices
                val voice = if (settings.voice.isBlank()) voices.first().id else settings.voice
                if (voices.none { it.id == voice }) throw TtsException(TtsFailure.MissingJapanese)
                val options = settings.options()
                val rate = source.normalizeRate(settings.rate)
                val pitch = ttsPitchRange(source, voice)?.let { settings.pitch.coerceIn(it) } ?: 0f
                val pitchText = (if (pitch >= 0) "+" else "") + pitch.toInt() + if (source == TtsOnlineSource.Edge) "Hz" else "st"
                val credential = if (source == TtsOnlineSource.GoogleCloud) key(source, config) else ""
                val mp3 = settings.audioExtension() == "mp3"
                directory.mkdirs()
                // Conservative UTF-8 budgets also leave room for SSML escaping on Edge.
                val budget = if (source == TtsOnlineSource.GoogleTranslate) 100 else 3000
                for (part in OnlineTtsProtocol.chunks(text, budget)) {
                    currentCoroutineContext().ensureActive()
                    val bytes = when (source) {
                        TtsOnlineSource.Edge -> transport.edgeAudio(edgeRequest(true), OnlineTtsProtocol.ssml(
                            part, voice, rate, pitchText, options.volume))
                        TtsOnlineSource.Azure, TtsOnlineSource.MicrosoftTranslator -> azureSpeechRequest(source, config,
                            "/cognitiveservices/v1", OnlineTtsProtocol.azureSsml(part, voices.first { it.id == voice }, rate, pitch, options), options)
                        TtsOnlineSource.GoogleCloud -> OnlineTtsProtocol.googleAudio(transport.request(Request.Builder()
                            .url("https://texttospeech.googleapis.com/v1/text:synthesize")
                            .header("X-Goog-Api-Key", credential)
                            .post(OnlineTtsProtocol.googleBody(part, voice, rate, pitch, options).toRequestBody("application/json".toMediaType())).build()).toString(Charsets.UTF_8))
                        TtsOnlineSource.GoogleTranslate -> OnlineTtsProtocol.translateAudio(transport.request(Request.Builder()
                            .url("https://translate.google.com/_/TranslateWebserverUi/data/batchexecute?rpcids=jQ1olc")
                            .header("Referer", "https://translate.google.com/").header("User-Agent", "Mozilla/5.0")
                            .post(FormBody.Builder().add("f.req", OnlineTtsProtocol.translateBody(part, rate < 1f)).build()).build()).toString(Charsets.UTF_8))
                    }
                    currentCoroutineContext().ensureActive()
                    validateTtsAudio(bytes, if (mp3) TtsAudioFormat.Mp3 else TtsAudioFormat.Wav)
                    val file = File(directory, "${UUID.randomUUID()}.${if (mp3) "mp3" else "wav"}")
                    files += file
                    file.writeBytes(bytes)
                }
                files
            } }
        } catch (error: Throwable) {
            withContext(NonCancellable + io) { files.forEach { it.delete() } }
            throw error
        }
    }

    private fun source(settings: TtsSettings) = TtsOnlineSource.from(settings.engine) ?: throw TtsException(TtsFailure.Configuration)
    private suspend fun azureSpeechRequest(source: TtsOnlineSource, config: TtsOnlineConfig, path: String,
        ssml: String? = null, options: TtsSynthesisOptions = TtsSynthesisOptions(),
    ): ByteArray {
        suspend fun request(region: String, header: String, credential: String): ByteArray = transport.request(Request.Builder()
            .url(OnlineTtsProtocol.azureBase(region) + path).header(header, credential).header("User-Agent", "Hoshi")
            .apply { if (ssml != null) {
                header("X-Microsoft-OutputFormat", azureFormat(options))
                post(ssml.toRequestBody("application/ssml+xml".toMediaType()))
            } }.build())
        return when (source) {
            TtsOnlineSource.Azure -> request(config.region, "Ocp-Apim-Subscription-Key", key(source, config))
            TtsOnlineSource.MicrosoftTranslator -> translator.authorized { request(it.region, "Authorization", "Bearer ${it.bearer}") }
            else -> throw TtsException(TtsFailure.Configuration)
        }
    }
    private fun azureFormat(options: TtsSynthesisOptions): String {
        val khz = options.sampleRate / 1000
        return if (options.format == TtsAudioFormat.Wav) "riff-${khz}khz-16bit-mono-pcm"
            else "audio-${khz}khz-${if (khz == 48) 192 else if (khz == 24) 96 else 64}kbitrate-mono-mp3"
    }
    private suspend fun key(source: TtsOnlineSource, config: TtsOnlineConfig): String {
        if (config.encryptedKey.isBlank()) throw TtsException(TtsFailure.Configuration)
        return decryptKey(config.encryptedKey, source.id).takeIf { it.isNotBlank() } ?: throw TtsException(TtsFailure.Configuration)
    }

    private fun edgeRequest(socket: Boolean): Request {
        val path = if (socket) "/edge/v1" else "/voices/list"
        val url = (OnlineTtsProtocol.edgeBase + path).toHttpUrl().newBuilder()
            .addQueryParameter("TrustedClientToken", OnlineTtsProtocol.edgeToken)
            .addQueryParameter("Sec-MS-GEC", OnlineTtsProtocol.edgeGec(System.currentTimeMillis() / 1000))
            .addQueryParameter("Sec-MS-GEC-Version", "1-${OnlineTtsProtocol.edgeVersion}")
        if (socket) url.addQueryParameter("ConnectionId", UUID.randomUUID().toString().replace("-", ""))
        return Request.Builder().url(url.build())
            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/143.0.0.0 Safari/537.36 Edg/143.0.0.0")
            .header("Origin", "chrome-extension://jdiccldimpdaibmpdkjnbmckianbfold")
            .header("Cookie", "muid=${UUID.randomUUID().toString().replace("-", "").uppercase()};")
            .build()
    }
}
