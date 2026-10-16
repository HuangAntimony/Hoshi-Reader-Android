package moe.antimony.hoshi.features.tts

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import moe.antimony.hoshi.ui.theme.HoshiReaderTheme
import moe.antimony.hoshi.ui.theme.hoshiSurfaces

@Preview(name = "TTS · Light", widthDp = 393, heightDp = 720)
@Composable
private fun TtsVoiceLightPreview() = TtsVoicePreview()

@Preview(name = "TTS · Dark", widthDp = 393, heightDp = 720)
@Composable
private fun TtsVoiceDarkPreview() = TtsVoicePreview(dark = true)

@Preview(name = "TTS · E-ink", widthDp = 393, heightDp = 720)
@Composable
private fun TtsVoiceEInkPreview() = TtsVoicePreview(eInk = true)

@Composable
private fun TtsVoicePreview(dark: Boolean = false, eInk: Boolean = false) {
    val settings = TtsSettings(engine = TtsOnlineSource.Azure.id).withVoice("ja-JP-NanamiNeural").withRate(1.5f)
    HoshiReaderTheme(darkTheme = dark, eInkMode = eInk, dynamicColor = false) {
        Surface(Modifier.fillMaxSize(), color = hoshiSurfaces.overlay) {
            TtsVoiceSettings(settings, TtsCatalog(emptyList(), settings.engine, listOf(TtsVoiceInfo(settings.voice, true))), emptyList(),
                false, TtsPreviewState.Idle, null, {}, {}, {}, {}, {}, {})
        }
    }
}
