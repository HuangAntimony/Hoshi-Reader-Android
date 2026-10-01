package moe.antimony.hoshi.features.tts

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.unit.dp
import moe.antimony.hoshi.R
import moe.antimony.hoshi.features.reader.ReaderSheetCard
import moe.antimony.hoshi.features.reader.ReaderSheetSliderRow
import moe.antimony.hoshi.ui.theme.hoshiSurfaces
import kotlin.math.roundToInt

@Composable
internal fun TtsSynthesisControls(settings: TtsSettings, voice: TtsVoiceInfo?, onChange: (TtsSettings) -> Unit) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    val source = TtsOnlineSource.from(settings.engine)
    val options = settings.options()
    val update: (TtsSynthesisOptions) -> Unit = { onChange(settings.withOptions(it)) }
    TextButton(onClick = { expanded = !expanded }, modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
        Text(stringResource(R.string.tts_more_parameters), modifier = Modifier.weight(1f))
        Icon(if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore, null)
    }
    if (!expanded) return
    TtsParameterSlider(R.string.tts_prefetch_sentences, settings.prefetchSentences.coerceIn(ttsPrefetchRange).toFloat(),
        ttsPrefetchRange.first.toFloat()..ttsPrefetchRange.last.toFloat(), ttsPrefetchRange.last - ttsPrefetchRange.first - 1,
        { pluralStringResource(R.plurals.tts_sentence_count, it.roundToInt(), it.roundToInt()) }) {
        onChange(settings.copy(prefetchSentences = it.roundToInt()))
    }
    TtsPauseSlider(R.string.tts_sentence_pause, settings.sentencePauseMs.coerceIn(0, 5000).toInt(), usualMaximum = 2000) {
        onChange(settings.copy(sentencePauseMs = it.toLong()))
    }
    Text(stringResource(R.string.tts_sentence_pause_hint), style = MaterialTheme.typography.bodySmall,
        color = hoshiSurfaces.muted, modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp))
    if (source == null || source == TtsOnlineSource.GoogleTranslate) return

    TtsParameterSlider(R.string.tts_synthesis_volume, options.volume,
        if (source == TtsOnlineSource.GoogleCloud) -96f..16f else -100f..100f,
        if (source == TtsOnlineSource.GoogleCloud) 111 else 199,
        { stringResource(if (source == TtsOnlineSource.GoogleCloud) R.string.tts_decibels else R.string.tts_relative_percent, it.roundToInt()) },
    ) { update(options.copy(volume = it)) }

    if (source.usesAzureRest) {
        if (!voice?.styles.isNullOrEmpty()) {
            ReaderSheetCard {
                TtsParameterChoice(R.string.tts_speaking_style, options.style, voice.styles) {
                    update(options.copy(style = it, role = if (it.isBlank()) "" else options.role))
                }
            }
            if (options.style.isNotBlank()) {
                TtsParameterSlider(R.string.tts_style_degree, options.styleDegree, .01f..2f, 198,
                    { stringResource(R.string.tts_rate_value, it) }) { update(options.copy(styleDegree = it)) }
                if (voice.roles.isNotEmpty()) ReaderSheetCard {
                    TtsParameterChoice(R.string.tts_speaking_role, options.role, voice.roles) { update(options.copy(role = it)) }
                }
            }
        }
        TtsPauseControl(R.string.tts_leading_pause, options.leadingMs) { update(options.copy(leadingMs = it)) }
        TtsPauseControl(R.string.tts_trailing_pause, options.trailingMs) { update(options.copy(trailingMs = it)) }
        TtsPauseControl(R.string.tts_comma_pause, options.commaMs) { update(options.copy(commaMs = it)) }
        TtsPauseControl(R.string.tts_semicolon_pause, options.semicolonMs) { update(options.copy(semicolonMs = it)) }
        TtsPauseControl(R.string.tts_enumeration_pause, options.enumerationCommaMs) { update(options.copy(enumerationCommaMs = it)) }
        TtsPauseControl(R.string.tts_boundary_pause, options.sentenceBoundaryMs) { update(options.copy(sentenceBoundaryMs = it)) }
    }
    if (source.usesAzureRest || source == TtsOnlineSource.GoogleCloud) {
        ReaderSheetCard {
            val formats = listOf(TtsChoiceOption(TtsAudioFormat.Wav.name, stringResource(R.string.tts_format_wav)),
                TtsChoiceOption(TtsAudioFormat.Mp3.name, stringResource(R.string.tts_format_mp3)))
            TtsChoice(stringResource(R.string.tts_output_format), formats.first { it.id == options.format.name }.label,
                options.format.name, formats) { update(options.copy(format = TtsAudioFormat.valueOf(it))) }
            val rates = listOf(16000, 24000, 48000).map { TtsChoiceOption(it.toString(), stringResource(R.string.tts_sample_rate_hz, it)) }
            TtsChoice(stringResource(R.string.tts_sample_rate), stringResource(R.string.tts_sample_rate_hz, options.sampleRate),
                options.sampleRate.toString(), rates) { update(options.copy(sampleRate = it.toInt())) }
        }
    }
    if (source == TtsOnlineSource.GoogleCloud) ReaderSheetCard {
        val labels = listOf(R.string.tts_profile_none, R.string.tts_profile_wearable, R.string.tts_profile_handset,
            R.string.tts_profile_headphones, R.string.tts_profile_small_speaker, R.string.tts_profile_medium_speaker,
            R.string.tts_profile_home, R.string.tts_profile_car, R.string.tts_profile_telephone)
        val choices = googleAudioProfiles.mapIndexed { index, id -> TtsChoiceOption(id, stringResource(labels[index])) }
        TtsChoice(stringResource(R.string.tts_audio_profile), choices.first { it.id == options.effectsProfile }.label,
            options.effectsProfile, choices) { update(options.copy(effectsProfile = it)) }
    }
}

@Composable
private fun TtsParameterChoice(label: Int, value: String, choices: List<String>, onChange: (String) -> Unit) {
    val default = stringResource(R.string.tts_provider_default)
    TtsChoice(stringResource(label), value.ifBlank { default }, value,
        listOf(TtsChoiceOption("", default)) + choices.map { TtsChoiceOption(it, it) }, onSelected = onChange)
}

@Composable
private fun TtsPauseControl(label: Int, value: Int, onChange: (Int) -> Unit) {
    ReaderSheetCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(label), style = MaterialTheme.typography.bodyLarge)
                Text(stringResource(if (value < 0) R.string.tts_provider_default else R.string.tts_pause_override),
                    style = MaterialTheme.typography.bodySmall, color = hoshiSurfaces.muted)
            }
            Switch(value >= 0, { onChange(if (it) 300 else -1) })
        }
    }
    if (value >= 0) TtsPauseSlider(label, value, onChange = onChange)
}

@Composable
private fun TtsPauseSlider(label: Int, value: Int, usualMaximum: Int = 1000, onChange: (Int) -> Unit) {
    // Preserve a previously saved longer pause until the user explicitly changes it.
    val maximum = ((maxOf(usualMaximum, value) + 9) / 10) * 10
    TtsParameterSlider(label, value.toFloat(), 0f..maximum.toFloat(), maximum / 10 - 1,
        { stringResource(R.string.tts_milliseconds, it.roundToInt()) }) { onChange(it.roundToInt()) }
}

@Composable
private fun TtsParameterSlider(label: Int, value: Float, range: ClosedFloatingPointRange<Float>, steps: Int,
    display: @Composable (Float) -> String, onChange: (Float) -> Unit,
) {
    var draft by remember(value) { mutableFloatStateOf(value) }
    ReaderSheetSliderRow(label = stringResource(label), valueText = display(draft), value = draft,
        range = range, steps = steps, onValueChange = { draft = it }, onValueChangeFinished = { onChange(draft) })
}
