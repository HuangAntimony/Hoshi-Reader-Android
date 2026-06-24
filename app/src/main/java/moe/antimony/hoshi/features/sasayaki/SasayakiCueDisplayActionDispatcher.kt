package moe.antimony.hoshi.features.sasayaki

import moe.antimony.hoshi.epub.SasayakiMatch

class SasayakiCueDisplayActionDispatcher(
    private val onCue: (SasayakiMatch, Boolean) -> Unit,
    private val onClearCue: () -> Unit,
    private val onLoadChapter: (SasayakiMatch, Boolean) -> Unit,
) {
    fun apply(action: SasayakiCueDisplayAction) {
        when (action) {
            SasayakiCueDisplayAction.None -> Unit
            SasayakiCueDisplayAction.Clear -> onClearCue()
            is SasayakiCueDisplayAction.Display -> onCue(action.cue, action.reveal)
            is SasayakiCueDisplayAction.ClearAndLoadChapter -> {
                onClearCue()
                onLoadChapter(action.cue, action.reveal)
            }
        }
    }
}
