package moe.antimony.hoshi.features.audio

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource

@Composable
internal fun audioSourceName(source: AudioSource): String =
    BuiltInAudioSource.fromSource(source)?.let { stringResource(it.nameRes) } ?: source.name

/** Resolve resource-backed names at the UI boundary before building popup payloads. */
@Composable
internal fun AudioSettings.withLocalizedSourceNames(): AudioSettings =
    copy(audioSources = audioSources.map { it.copy(name = audioSourceName(it)) })
