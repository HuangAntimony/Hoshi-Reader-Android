package moe.antimony.hoshi.features.tts

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.unit.dp
import moe.antimony.hoshi.R
import moe.antimony.hoshi.features.reader.ReaderSheetCard
import moe.antimony.hoshi.features.reader.ReaderSheetSliderRow
import moe.antimony.hoshi.features.reader.ReaderSheetSwitchRow
import moe.antimony.hoshi.ui.theme.hoshiSurfaces
import kotlin.math.roundToInt

@Composable
internal fun TtsSynthesisControls(settings: TtsSettings, voice: TtsVoiceInfo?, onChange: (TtsSettingsUpdate) -> Unit) {
    val source = TtsOnlineSource.from(settings.engine)
    val options = settings.options()
    val update: ((TtsSynthesisOptions) -> TtsSynthesisOptions) -> Unit = { change ->
        onChange { current -> current.withOptions(change(current.options())) }
    }
    TtsParameterSlider(R.string.tts_prefetch_sentences, settings.prefetchSentences.coerceIn(ttsPrefetchRange).toFloat(),
        ttsPrefetchRange.first.toFloat()..ttsPrefetchRange.last.toFloat(), ttsPrefetchRange.last - ttsPrefetchRange.first - 1,
        { pluralStringResource(R.plurals.tts_sentence_count, it.roundToInt(), it.roundToInt()) }) {
        val value = it.roundToInt()
        onChange { current -> current.copy(prefetchSentences = value) }
    }
    TtsPauseSlider(R.string.tts_sentence_pause, settings.sentencePauseMs.coerceIn(0, ttsSentencePauseMaxMs).toInt(), maximum = ttsSentencePauseMaxMs.toInt()) {
        val value = it.toLong()
        onChange { current -> current.copy(sentencePauseMs = value) }
    }
    Text(stringResource(R.string.tts_sentence_pause_hint), style = MaterialTheme.typography.bodySmall,
        color = hoshiSurfaces.muted, modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp))
    settings.customSource()?.let { custom ->
        Column(Modifier.padding(horizontal = 20.dp, vertical = 6.dp)) {
            TtsAudioFormatChoice(custom.format) { format ->
                onChange { current -> current.copy(customSources = current.customSources.map { profile ->
                    if (profile.id == custom.id) profile.copy(format = format) else profile
                }) }
            }
        }
    }
    if (source == null || source == TtsOnlineSource.GoogleTranslate) return

    TtsParameterSlider(R.string.tts_synthesis_volume, options.volume,
        if (source == TtsOnlineSource.GoogleCloud) -96f..16f else -100f..100f,
        if (source == TtsOnlineSource.GoogleCloud) 111 else 199,
        { stringResource(if (source == TtsOnlineSource.GoogleCloud) R.string.tts_decibels else R.string.tts_relative_percent, it.roundToInt()) },
    ) { value -> update { current -> current.copy(volume = value) } }

    if (source.usesAzureRest) {
        if (!voice?.styles.isNullOrEmpty()) {
            ReaderSheetCard(verticalPadding = 4.dp) {
                TtsParameterChoice(R.string.tts_speaking_style, options.style, voice.styles) { value ->
                    update { current -> current.copy(style = value, role = if (value.isBlank()) "" else current.role) }
                }
            }
            if (options.style.isNotBlank()) {
                TtsParameterSlider(R.string.tts_style_degree, options.styleDegree, .01f..2f, 198,
                    { stringResource(R.string.tts_rate_value, it) }) { value -> update { current -> current.copy(styleDegree = value) } }
                if (voice.roles.isNotEmpty()) ReaderSheetCard(verticalPadding = 4.dp) {
                    TtsParameterChoice(R.string.tts_speaking_role, options.role, voice.roles) { value -> update { current -> current.copy(role = value) } }
                }
            }
        }
        TtsPauseControl(R.string.tts_leading_pause, options.leadingMs) { value -> update { current -> current.copy(leadingMs = value) } }
        TtsPauseControl(R.string.tts_trailing_pause, options.trailingMs) { value -> update { current -> current.copy(trailingMs = value) } }
        TtsPauseControl(R.string.tts_comma_pause, options.commaMs) { value -> update { current -> current.copy(commaMs = value) } }
        TtsPauseControl(R.string.tts_semicolon_pause, options.semicolonMs) { value -> update { current -> current.copy(semicolonMs = value) } }
        TtsPauseControl(R.string.tts_enumeration_pause, options.enumerationCommaMs) { value -> update { current -> current.copy(enumerationCommaMs = value) } }
        TtsPauseControl(R.string.tts_boundary_pause, options.sentenceBoundaryMs) { value -> update { current -> current.copy(sentenceBoundaryMs = value) } }
    }
    if (source.usesAzureRest || source == TtsOnlineSource.GoogleCloud) {
        Column(Modifier.padding(horizontal = 20.dp, vertical = 6.dp)) {
            TtsAudioFormatChoice(options.format) { value -> update { current -> current.copy(format = value) } }
            val rates = listOf(16000, 24000, 48000).map { TtsChoiceOption(it.toString(), stringResource(R.string.tts_sample_rate_hz, it)) }
            TtsChoice(stringResource(R.string.tts_sample_rate), stringResource(R.string.tts_sample_rate_hz, options.sampleRate),
                options.sampleRate.toString(), rates) { value -> update { current -> current.copy(sampleRate = value.toInt()) } }
        }
    }
    if (source == TtsOnlineSource.GoogleCloud) ReaderSheetCard(verticalPadding = 4.dp) {
        val labels = listOf(R.string.tts_profile_none, R.string.tts_profile_wearable, R.string.tts_profile_handset,
            R.string.tts_profile_headphones, R.string.tts_profile_small_speaker, R.string.tts_profile_medium_speaker,
            R.string.tts_profile_home, R.string.tts_profile_car, R.string.tts_profile_telephone)
        val choices = googleAudioProfiles.mapIndexed { index, id -> TtsChoiceOption(id, stringResource(labels[index])) }
        TtsChoice(stringResource(R.string.tts_audio_profile), choices.first { it.id == options.effectsProfile }.label,
            options.effectsProfile, choices) { value -> update { current -> current.copy(effectsProfile = value) } }
    }
}

@Composable
private fun TtsAudioFormatChoice(format: TtsAudioFormat, onChange: (TtsAudioFormat) -> Unit) {
    val formats = listOf(TtsChoiceOption(TtsAudioFormat.Wav.name, stringResource(R.string.tts_format_wav)),
        TtsChoiceOption(TtsAudioFormat.Mp3.name, stringResource(R.string.tts_format_mp3)))
    TtsChoice(stringResource(R.string.tts_output_format), formats.first { it.id == format.name }.label,
        format.name, formats) { onChange(TtsAudioFormat.valueOf(it)) }
}

@Composable
private fun TtsParameterChoice(label: Int, value: String, choices: List<String>, onChange: (String) -> Unit) {
    val default = stringResource(R.string.tts_provider_default)
    TtsChoice(stringResource(label), value.ifBlank { default }, value,
        listOf(TtsChoiceOption("", default)) + choices.map { TtsChoiceOption(it, it) }, onSelected = onChange)
}

@Composable
private fun TtsPauseControl(label: Int, value: Int, onChange: (Int) -> Unit) {
    ReaderSheetSwitchRow(
        label = stringResource(label), checked = value >= 0,
        onCheckedChange = { onChange(if (it) 300 else -1) },
        supportingText = stringResource(if (value < 0) R.string.tts_provider_default else R.string.tts_pause_override),
    )
    if (value >= 0) TtsPauseSlider(label, value, onChange = onChange)
}

@Composable
private fun TtsPauseSlider(label: Int, value: Int, maximum: Int = ttsProviderPauseMaxMs, onChange: (Int) -> Unit) {
    TtsParameterSlider(label, value.coerceIn(0, maximum).toFloat(), 0f..maximum.toFloat(), maximum / 10 - 1,
        { stringResource(R.string.tts_milliseconds, it.roundToInt()) }) { onChange(it.roundToInt()) }
}

@Composable
private fun TtsParameterSlider(label: Int, value: Float, range: ClosedFloatingPointRange<Float>, steps: Int,
    display: @Composable (Float) -> String, onChange: (Float) -> Unit,
) {
    var draft by remember(value) { mutableFloatStateOf(value) }
    ReaderSheetSliderRow(label = stringResource(label), valueText = display(draft), value = draft,
        range = range, steps = steps, onValueChange = { draft = it }, onValueChangeFinished = { onChange(draft) }, showTicks = false)
}
