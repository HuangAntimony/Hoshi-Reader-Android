package moe.antimony.hoshi.features.sasayaki

import androidx.annotation.OptIn
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.SonicAudioProcessor
import androidx.media3.common.util.UnstableApi

@OptIn(UnstableApi::class)
internal fun sasayakiCueAudioProcessor(rate: Float): AudioProcessor =
    SonicAudioProcessor().apply {
        setSpeed(rate)
        setPitch(1f)
    }
