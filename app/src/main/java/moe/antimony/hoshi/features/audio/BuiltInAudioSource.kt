package moe.antimony.hoshi.features.audio

import androidx.annotation.StringRes
import moe.antimony.hoshi.R

enum class BuiltInAudioSource(
    val id: String,
    private val storageName: String,
    @param:StringRes val nameRes: Int,
) {
    JapanesePod101("jpod101", "JapanesePod101", R.string.audio_source_jpod101),
    LanguagePod101("language-pod-101", "LanguagePod101", R.string.audio_source_language_pod101),
    Jisho("jisho", "Jisho", R.string.audio_source_jisho);

    val url: String get() = "$Scheme://$id/?term={term}&reading={reading}"

    fun settingsSource(isEnabled: Boolean = true): AudioSource =
        AudioSource(name = storageName, url = url, isEnabled = isEnabled, isDefault = true)

    companion object {
        const val Scheme = "hoshi-builtin-audio-source"

        fun fromSource(source: AudioSource): BuiltInAudioSource? =
            entries.firstOrNull { source.isDefault && source.url == it.url }
    }
}
