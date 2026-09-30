package moe.antimony.hoshi.features.reader

import moe.antimony.hoshi.features.tts.TtsPlaybackState
import moe.antimony.hoshi.features.tts.TtsSentence

internal enum class ReaderTtsPresentation { None, Highlight, Follow }

internal fun readerTtsPresentation(
    playback: TtsPlaybackState,
    sentence: TtsSentence,
    currentChapter: Int,
    autoScroll: Boolean,
): ReaderTtsPresentation = when {
    !playback.started || playback.sentence != sentence -> ReaderTtsPresentation.None
    playback.playing && autoScroll -> ReaderTtsPresentation.Follow
    currentChapter == sentence.chapter -> ReaderTtsPresentation.Highlight
    else -> ReaderTtsPresentation.None
}
