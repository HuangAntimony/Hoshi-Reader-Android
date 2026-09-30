package moe.antimony.hoshi.features.tts

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.util.Locale
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class TtsEngineInfo(val id: String, val label: String)
data class TtsVoiceInfo(val id: String, val network: Boolean, val styles: List<String> = emptyList(), val roles: List<String> = emptyList())
data class TtsCatalog(val engines: List<TtsEngineInfo>, val selectedEngine: String, val voices: List<TtsVoiceInfo>)
enum class TtsFailure {
    NoEngine, MissingJapanese, EngineUnavailable, Synthesis, Timeout, EmptyText, AudioUnavailable, Storage,
    Configuration, Authentication, RateLimited, Network, InvalidResponse,
}
class TtsException(val failure: TtsFailure) : Exception(failure.name)

// Engines may expose ISO 639-1 (Google) or ISO 639-2 (Samsung) locale language codes.
internal fun isJapaneseTtsLocale(locale: Locale): Boolean = locale.language == "ja" || locale.language == "jpn"

/** Android engines share one serial queue. Cancellation stops the exact active utterance. */
@Singleton
class AndroidTtsEngine @Inject constructor(@param:ApplicationContext private val context: Context) {
    private val mutex = Mutex()
    private var instance: TextToSpeech? = null
    private var engineId: String? = null

    private fun installed(): List<TtsEngineInfo> = context.packageManager
        .queryIntentServices(Intent(TextToSpeech.Engine.INTENT_ACTION_TTS_SERVICE), 0)
        .map { TtsEngineInfo(it.serviceInfo.packageName, it.loadLabel(context.packageManager).toString()) }
        .distinctBy { it.id }.sortedBy { it.label }

    private suspend fun connect(id: String): TextToSpeech {
        if (engineId == id) instance?.let { return it }
        instance?.shutdown()
        instance = null
        engineId = null
        val ready = CompletableDeferred<Int>()
        val created = withContext(Dispatchers.Main) { TextToSpeech(context, { ready.complete(it) }, id) }
        try {
            if (withTimeout(15_000) { ready.await() } != TextToSpeech.SUCCESS) throw TtsException(TtsFailure.EngineUnavailable)
            // Android may silently substitute the default when the requested package disappears.
            if (installed().none { it.id == id }) throw TtsException(TtsFailure.EngineUnavailable)
            instance = created
            engineId = id
            return created
        } catch (error: Throwable) {
            created.shutdown()
            throw error
        }
    }

    private suspend fun resolve(settings: TtsSettings): Pair<TextToSpeech, String> {
        val engines = installed()
        if (engines.isEmpty()) throw TtsException(TtsFailure.NoEngine)
        if (settings.engine.isNotBlank()) {
            if (engines.none { it.id == settings.engine }) throw TtsException(TtsFailure.EngineUnavailable)
            return connect(settings.engine) to settings.engine
        }
        val systemDefault = android.provider.Settings.Secure.getString(context.contentResolver, "tts_default_synth")
        for (engine in engines.sortedByDescending { it.id == systemDefault }) {
            val candidate = try { connect(engine.id) } catch (_: TtsException) { continue }
            if (candidate.isLanguageAvailable(Locale.JAPANESE) >= TextToSpeech.LANG_AVAILABLE && japaneseVoices(candidate).isNotEmpty())
                return candidate to engine.id
        }
        throw TtsException(TtsFailure.MissingJapanese)
    }

    private fun japaneseVoices(tts: TextToSpeech) = tts.voices.orEmpty()
        .filter { isJapaneseTtsLocale(it.locale) }.sortedWith(compareBy({ it.isNetworkConnectionRequired }, { it.name }))

    suspend fun catalog(settings: TtsSettings): TtsCatalog = withContext(Dispatchers.IO) {
        mutex.withLock {
            val engines = installed()
            val (tts, id) = resolve(settings)
            val voices = japaneseVoices(tts)
            TtsCatalog(engines, id, voices.map { TtsVoiceInfo(it.name, it.isNetworkConnectionRequired) })
        }
    }

    suspend fun installedEngines(): List<TtsEngineInfo> = withContext(Dispatchers.IO) { installed() }

    suspend fun synthesize(text: String, settings: TtsSettings, directory: File): List<File> {
        val files = mutableListOf<File>()
        try {
            return withContext(Dispatchers.IO) { mutex.withLock {
                val (tts, _) = resolve(settings)
                val voices = japaneseVoices(tts)
                val voice = if (settings.voice.isNotBlank()) voices.firstOrNull { it.name == settings.voice }
                    else voices.firstOrNull()
                if (voice == null || tts.setVoice(voice) != TextToSpeech.SUCCESS) throw TtsException(TtsFailure.MissingJapanese)
                if (tts.setSpeechRate(settings.rate) != TextToSpeech.SUCCESS) throw TtsException(TtsFailure.Synthesis)
                if (tts.setPitch(Math.pow(2.0, settings.pitch.coerceIn(-12f, 12f) / 12.0).toFloat()) != TextToSpeech.SUCCESS)
                    throw TtsException(TtsFailure.Synthesis)
                if (text.isBlank()) throw TtsException(TtsFailure.EmptyText)
                directory.mkdirs()
                try {
                    for (part in TtsText.chunks(text, TextToSpeech.getMaxSpeechInputLength() - 1)) {
                        val id = UUID.randomUUID().toString()
                        val file = File(directory, "$id.wav")
                        files += file
                        val completion = CompletableDeferred<Unit>()
                        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                            override fun onStart(utteranceId: String?) = Unit
                            override fun onDone(utteranceId: String?) { if (utteranceId == id) completion.complete(Unit) }
                            @Deprecated("Android callback")
                            override fun onError(utteranceId: String?) { if (utteranceId == id) completion.completeExceptionally(TtsException(TtsFailure.Synthesis)) }
                            override fun onError(utteranceId: String?, errorCode: Int) = onError(utteranceId)
                            override fun onStop(utteranceId: String?, interrupted: Boolean) = onError(utteranceId)
                        })
                        if (tts.synthesizeToFile(part, Bundle(), file, id) != TextToSpeech.SUCCESS) throw TtsException(TtsFailure.Synthesis)
                        withTimeout(60_000) { completion.await() }
                        if (!file.isFile || file.length() <= 44) throw TtsException(TtsFailure.Synthesis)
                    }
                    files
                } catch (error: Throwable) {
                    tts.stop()
                    files.forEach { it.delete() }
                    throw error
                } finally {
                    tts.setOnUtteranceProgressListener(null)
                }
            } }
        } catch (error: Throwable) {
            // Also own files when cancellation discards a successful cross-dispatcher return.
            withContext(NonCancellable + Dispatchers.IO) { files.forEach { it.delete() } }
            throw error
        }
    }
}
