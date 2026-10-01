package moe.antimony.hoshi.features.tts

import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/** Reader, preview and Anki always select the same source; failures never switch providers. */
@Singleton
class TtsEngineRouter @Inject constructor(
    private val android: AndroidTtsEngine,
    private val online: OnlineTtsEngine,
) {
    suspend fun installedEngines() = android.installedEngines()
    suspend fun cachedCatalog(settings: TtsSettings): TtsCatalog? =
        if (TtsOnlineSource.from(settings.engine) == null) null else online.cachedCatalog(settings)
    suspend fun catalog(settings: TtsSettings, force: Boolean = false): TtsCatalog =
        if (TtsOnlineSource.from(settings.engine) == null) android.catalog(settings) else online.catalog(settings, force)

    suspend fun synthesize(text: String, settings: TtsSettings, directory: File): List<File> =
        if (TtsOnlineSource.from(settings.engine) == null) android.synthesize(text, settings, directory)
        else online.synthesize(text, settings, directory)

    fun extension(settings: TtsSettings): String = settings.audioExtension()
}
