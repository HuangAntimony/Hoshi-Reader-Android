package moe.antimony.hoshi.features.sasayaki

import moe.antimony.hoshi.epub.SasayakiMatch

class SasayakiCueDisplayActionDispatcher(
    private val onCue: (SasayakiMatch, Boolean, SasayakiCueRevealSource) -> Unit,
    private val onClearCue: () -> Unit,
) {
    fun apply(action: SasayakiCueDisplayAction) {
        when (action) {
            SasayakiCueDisplayAction.None -> Unit
            SasayakiCueDisplayAction.Clear -> onClearCue()
            is SasayakiCueDisplayAction.Display -> onCue(action.cue, action.reveal, action.source)
            is SasayakiCueDisplayAction.ClearAndDisplay -> {
                onClearCue()
                onCue(action.cue, action.reveal, action.source)
            }
        }
    }
}
