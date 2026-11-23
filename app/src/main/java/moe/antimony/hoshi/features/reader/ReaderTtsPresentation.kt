package moe.antimony.hoshi.features.reader

import moe.antimony.hoshi.features.tts.TtsPlaybackState
import moe.antimony.hoshi.features.tts.TtsSentence

internal enum class ReaderTtsPresentation { None, Highlight, Follow }
internal enum class ReaderTtsPresentationSource { Playback, DocumentRestore, Foreground }

internal fun readerTtsChapterNeedsCues(
    playback: TtsPlaybackState,
    chapterIndex: Int,
    paragraphMode: Boolean,
): Boolean = playback.started && (paragraphMode || playback.sentence?.chapter == chapterIndex)

internal fun readerTtsPresentation(
    playback: TtsPlaybackState,
    sentence: TtsSentence,
    currentChapter: Int,
    autoScroll: Boolean,
    restoring: Boolean = true,
    directJump: Boolean = false,
    passiveRestore: Boolean = false,
): ReaderTtsPresentation = when {
    !playback.started || playback.sentence != sentence -> ReaderTtsPresentation.None
    restoring && playback.playing && playback.preparing -> ReaderTtsPresentation.None
    directJump -> ReaderTtsPresentation.Follow
    playback.playing && autoScroll && !passiveRestore -> ReaderTtsPresentation.Follow
    currentChapter == sentence.chapter -> ReaderTtsPresentation.Highlight
    else -> ReaderTtsPresentation.None
}
