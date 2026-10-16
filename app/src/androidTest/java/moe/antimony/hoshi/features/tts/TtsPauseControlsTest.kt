package moe.antimony.hoshi.features.tts

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import moe.antimony.hoshi.ui.theme.HoshiReaderTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Isolated controls and in-memory settings; no synthesis or saved device preferences. */
@RunWith(AndroidJUnit4::class)
class TtsPauseControlsTest {
    @get:Rule val compose = createComposeRule()

    private fun controls(initial: TtsSettings): MutableState<TtsSettings> {
        val state = mutableStateOf(initial)
        compose.setContent {
            HoshiReaderTheme(darkTheme = false, dynamicColor = false) {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    TtsSynthesisControls(state.value, TtsVoiceInfo("ja-JP-NanamiNeural", true)) { update -> state.value = update(state.value) }
                }
            }
        }
        return state
    }

    private fun providerSlider() = compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.ProgressBarRangeInfo)).onLast().performScrollTo()
    private fun setProvider(value: Float) = providerSlider().performSemanticsAction(SemanticsActions.SetProgress) { it(value) }

    @Test fun allProviderPausesAllowTenMillisecondChangesAndClampAtOneSecond() {
        val state = controls(TtsSettings(engine = TtsOnlineSource.MicrosoftTranslator.id))
        val options = listOf(TtsSynthesisOptions(leadingMs = 300), TtsSynthesisOptions(trailingMs = 300),
            TtsSynthesisOptions(commaMs = 300), TtsSynthesisOptions(semicolonMs = 300),
            TtsSynthesisOptions(enumerationCommaMs = 300), TtsSynthesisOptions(sentenceBoundaryMs = 300))
        for (option in options) {
            compose.runOnIdle { state.value = state.value.withOptions(option) }
            setProvider(310f)
            compose.runOnIdle { assertEquals(listOf(310), enabledPauses(state.value.options())) }
            setProvider(1200f)
            compose.runOnIdle { assertEquals(listOf(1000), enabledPauses(state.value.options())) }
        }
    }

    @Test fun extraSentencePauseAllowsTenMillisecondChangesAndClampsAtTwoSeconds() {
        val state = controls(TtsSettings(sentencePauseMs = 300))
        // Installed engines expose only prefetch and the extra sentence pause here.
        setProvider(310f)
        compose.runOnIdle { assertEquals(310L, state.value.sentencePauseMs) }
        setProvider(3000f)
        compose.runOnIdle { assertEquals(2000L, state.value.sentencePauseMs) }
    }

    private fun enabledPauses(options: TtsSynthesisOptions) = listOf(options.leadingMs, options.trailingMs,
        options.commaMs, options.semicolonMs, options.enumerationCommaMs, options.sentenceBoundaryMs).filter { it >= 0 }
}
