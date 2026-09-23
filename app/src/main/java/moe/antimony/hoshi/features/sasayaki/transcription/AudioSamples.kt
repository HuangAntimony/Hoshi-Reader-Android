package moe.antimony.hoshi.features.sasayaki.transcription

internal const val TRANSCRIPTION_SAMPLE_RATE = 16_000

internal data class AudioSamples(val startSample: Long, val samples: FloatArray)
