package moe.antimony.hoshi.features.tts

import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/** Reader, preview and Anki always select the same source; failures never switch providers. */
@Singleton
class TtsEngineRouter @Inject constructor(
    private val android: AndroidTtsEngine,
    private val online: OnlineTtsEngine,
    private val custom: CustomTtsEngine,
) {
    suspend fun installedEngines() = android.installedEngines()
    suspend fun cachedCatalog(settings: TtsSettings): TtsCatalog? =
        when { settings.isCustomSource() -> custom.cachedCatalog(settings)
            TtsOnlineSource.from(settings.engine) != null -> online.cachedCatalog(settings)
            else -> android.cachedCatalog(settings) }
    suspend fun catalog(settings: TtsSettings, force: Boolean = false): TtsCatalog =
        when { settings.isCustomSource() -> custom.catalog(settings, force)
            TtsOnlineSource.from(settings.engine) != null -> online.catalog(settings, force)
            else -> android.catalog(settings, force) }

    suspend fun synthesize(text: String, settings: TtsSettings, directory: File): List<File> =
        when { settings.isCustomSource() -> custom.synthesize(text, settings, directory)
            TtsOnlineSource.from(settings.engine) != null -> online.synthesize(text, settings, directory)
            else -> android.synthesize(text, settings, directory) }

    suspend fun models(source: TtsCustomSource) = custom.models(source)
    fun extension(settings: TtsSettings): String = settings.audioExtension()
}
