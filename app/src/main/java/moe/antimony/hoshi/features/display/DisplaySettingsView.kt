package moe.antimony.hoshi.features.display

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import moe.antimony.hoshi.R
import moe.antimony.hoshi.features.reader.ReaderBottomPanel
import moe.antimony.hoshi.features.reader.ReaderColorPickerDialog
import moe.antimony.hoshi.features.reader.ReaderColorSettingRow
import moe.antimony.hoshi.features.reader.readerColorFromHexInput
import moe.antimony.hoshi.features.reader.readerColorBlue
import moe.antimony.hoshi.features.reader.readerColorGreen
import moe.antimony.hoshi.features.reader.readerColorRed
import moe.antimony.hoshi.features.reader.readerSheetStyle
import moe.antimony.hoshi.features.reader.toReaderColorHexInput
import moe.antimony.hoshi.features.reader.withReaderColorBlue
import moe.antimony.hoshi.features.reader.withReaderColorGreen
import moe.antimony.hoshi.features.reader.withReaderColorRed
import moe.antimony.hoshi.features.settings.SettingsDetailScaffold
import moe.antimony.hoshi.features.settings.GroupDivider
import moe.antimony.hoshi.ui.HoshiAlertDialog
import moe.antimony.hoshi.ui.HoshiButton
import moe.antimony.hoshi.ui.asString
import moe.antimony.hoshi.ui.hoshiOutlinedTextFieldColors
import moe.antimony.hoshi.ui.theme.hoshiContainerOutline
import moe.antimony.hoshi.ui.theme.hoshiContainerBorder
import moe.antimony.hoshi.ui.theme.hoshiColorScheme
import moe.antimony.hoshi.ui.theme.hoshiSurfaces
import kotlin.math.roundToInt

@Composable
internal fun DisplaySettingsScreen(
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: DisplaySettingsViewModel = hiltViewModel(),
) {
    SettingsDetailScaffold(
        title = stringResource(R.string.settings_display),
        onClose = onClose,
        modifier = modifier,
    ) { padding ->
        DisplaySettingsContent(
            viewModel = viewModel,
            contentPadding = PaddingValues(
                start = 20.dp,
                top = padding.calculateTopPadding() + 12.dp,
                end = 20.dp,
                bottom = 96.dp,
            ),
            modifier = Modifier.fillMaxSize(),
        )
    }
}

@Composable
internal fun DisplaySettingsSheet(
    onDismiss: () -> Unit,
    viewModel: DisplaySettingsViewModel = hiltViewModel(),
) {
    ReaderBottomPanel(
        sheetStyle = readerSheetStyle(),
        onDismiss = onDismiss,
    ) {
        DisplaySettingsContent(
            viewModel = viewModel,
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 24.dp),
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            header = {
                Text(
                    text = stringResource(R.string.settings_display),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(vertical = 8.dp),
                )
            },
        )
    }
}

@Composable
private fun DisplaySettingsContent(
    viewModel: DisplaySettingsViewModel,
    contentPadding: PaddingValues,
    modifier: Modifier = Modifier,
    header: (@Composable () -> Unit)? = null,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val settings = state.settings
    val systemDark = isSystemInDarkTheme()

    val scrollState = rememberScrollState()
    Column(
        modifier = modifier
            .then(if (settings != null) Modifier.verticalScroll(scrollState) else Modifier)
            .padding(contentPadding),
    ) {
        header?.invoke()
        if (settings == null) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                if (state.error == null) CircularProgressIndicator()
            }
        } else Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            DisplaySettingsGroup {
                DisplaySwitchRow(
                    label = stringResource(R.string.display_settings_auto_switch),
                    checked = settings.autoSwitch,
                    interactionEnabled = !state.isSaving,
                    onCheckedChange = { viewModel.setAutoSwitch(it, systemDark) },
                )
            }

            DisplaySettingsGroup {
                DisplaySwitchRow(
                    label = stringResource(R.string.display_settings_eink_mode),
                    checked = settings.eInkMode,
                    interactionEnabled = !state.isSaving,
                    onCheckedChange = viewModel::setEInkMode,
                )
                if (settings.eInkMode) {
                    GroupDivider()
                    Text(
                        text = stringResource(R.string.display_settings_eink_explanation),
                        style = MaterialTheme.typography.bodyMedium,
                        color = hoshiSurfaces.muted,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                    )
                }
            }

            if (settings.eInkMode) {
                if (!settings.autoSwitch) {
                    val dark = resolveDisplaySettings(settings, systemDark).isDark
                    DisplaySettingsGroup(title = stringResource(R.string.display_settings_eink_appearance)) {
                        DisplayChoiceRow(
                            label = stringResource(R.string.reader_appearance_theme_light),
                            selected = !dark,
                            interactionEnabled = !state.isSaving,
                            onClick = { viewModel.setEInkDarkTheme(false) },
                        )
                        GroupDivider()
                        DisplayChoiceRow(
                            label = stringResource(R.string.reader_appearance_theme_dark),
                            selected = dark,
                            interactionEnabled = !state.isSaving,
                            onClick = { viewModel.setEInkDarkTheme(true) },
                        )
                    }
                }
            } else {
                for (slot in DisplayPaletteSlot.entries) {
                    val light = slot == DisplayPaletteSlot.Light
                    PaletteSlotSection(
                        title = stringResource(if (light) R.string.display_settings_light_palette else R.string.display_settings_dark_palette),
                        slot = slot,
                        selection = settings.selection(slot),
                        hasSelection = settings.autoSwitch || settings.manualPaletteSlot == slot,
                        presets = if (light) listOf(
                            DisplayPalettePreset.Light,
                            DisplayPalettePreset.Sepia,
                            DisplayPalettePreset.Custom,
                        ) else listOf(
                            DisplayPalettePreset.Dark,
                            DisplayPalettePreset.DarkSepia,
                            DisplayPalettePreset.Custom,
                        ),
                        interactionEnabled = !state.isSaving,
                        onSelect = viewModel::selectPalettePreset,
                    )
                }

                AccentSection(
                    settings = settings,
                    interactionEnabled = !state.isSaving,
                    resolvedDark = resolveDisplaySettings(settings, systemDark).isDark,
                    onSystem = viewModel::selectSystemAccent,
                    onPreset = viewModel::selectAccentPreset,
                    onCustom = viewModel::openAccentEditor,
                )
            }
        }
    }

    state.paletteDraft?.let { draft ->
        PaletteEditorDialog(
            draft = draft,
            isSaving = state.isSaving,
            onUpdate = viewModel::updatePaletteDraft,
            onSave = viewModel::savePaletteDraft,
            onDismiss = viewModel::dismissPaletteEditor,
        )
    }
    state.accentDraft?.let { draft ->
        AccentEditorDialog(
            draft = draft,
            systemDark = settings?.let { resolveDisplaySettings(it, systemDark).isDark } ?: systemDark,
            isSaving = state.isSaving,
            onUpdate = viewModel::updateAccentDraft,
            onSave = viewModel::saveAccentDraft,
            onDismiss = viewModel::dismissAccentEditor,
        )
    }
    state.error?.let { error ->
        HoshiAlertDialog(
            onDismissRequest = {
                if (settings != null) viewModel.dismissError()
            },
            title = { Text(stringResource(R.string.dialog_error_title)) },
            text = { Text(error.asString()) },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (settings == null) viewModel.retryLoad() else viewModel.dismissError()
                    },
                ) {
                    Text(stringResource(if (settings == null) R.string.reader_appearance_font_retry else R.string.action_ok))
                }
            },
        )
    }
}

@Composable
private fun PaletteSlotSection(
    title: String,
    slot: DisplayPaletteSlot,
    selection: DisplayPaletteSelection,
    hasSelection: Boolean,
    presets: List<DisplayPalettePreset>,
    interactionEnabled: Boolean,
    onSelect: (DisplayPaletteSlot, DisplayPalettePreset) -> Unit,
) {
    DisplaySettingsGroup(title = title) {
        presets.forEachIndexed { index, preset ->
            if (index > 0) GroupDivider()
            DisplayChoiceRow(
                label = stringResource(if (preset == DisplayPalettePreset.Custom) slot.customLabelRes else preset.labelRes),
                selected = hasSelection && selection.preset == preset,
                interactionEnabled = interactionEnabled,
                previewColors = palettePreviewColors(slot, preset, selection),
                onClick = { onSelect(slot, preset) },
            )
        }
    }
}

@Composable
private fun AccentSection(
    settings: AppDisplaySettings,
    interactionEnabled: Boolean,
    resolvedDark: Boolean,
    onSystem: () -> Unit,
    onPreset: (Long) -> Unit,
    onCustom: () -> Unit,
) {
    val matchingPreset = AccentPreset.entries.firstOrNull { it.color == settings.accentSeed }
    DisplaySettingsGroup(title = stringResource(R.string.display_settings_accent)) {
        DisplayChoiceRow(
            label = stringResource(R.string.display_settings_accent_system),
            selected = settings.accentSource == DisplayAccentSource.System,
            interactionEnabled = interactionEnabled,
            onClick = onSystem,
        )
        AccentPreset.entries.forEach { preset ->
            GroupDivider()
            DisplayChoiceRow(
                label = stringResource(preset.labelRes),
                selected = settings.accentSource == DisplayAccentSource.Custom && matchingPreset == preset,
                interactionEnabled = interactionEnabled,
                previewColor = preset.color,
                onClick = { onPreset(preset.color) },
            )
        }
        GroupDivider()
        DisplayChoiceRow(
            label = stringResource(R.string.reader_appearance_theme_custom),
            selected = settings.accentSource == DisplayAccentSource.Custom && matchingPreset == null,
            interactionEnabled = interactionEnabled,
            previewColor = settings.accentSeed,
            onClick = onCustom,
        )
        GroupDivider()
        AccentControlPreview(
            seed = settings.accentSeed.takeIf { settings.accentSource == DisplayAccentSource.Custom },
            dark = resolvedDark,
        )
    }
}

@Composable
private fun AccentControlPreview(seed: Long?, dark: Boolean) {
    if (seed == null) {
        AccentControlPreviewContent()
    } else {
        val scheme = remember(seed, dark) { hoshiColorScheme(dark, eInkMode = false, accentSeed = seed) }
        MaterialTheme(colorScheme = scheme) { AccentControlPreviewContent() }
    }
}

@Composable
private fun AccentControlPreviewContent() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .padding(14.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(R.string.display_settings_accent_preview),
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        HoshiButton(onClick = {}) {
            Text(stringResource(R.string.display_settings_accent_preview_action))
        }
    }
}

@Composable
private fun DisplaySettingsGroup(
    title: String? = null,
    content: @Composable () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        title?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.titleSmall,
                color = hoshiSurfaces.content,
                modifier = Modifier.padding(start = 4.dp),
            )
        }
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(18.dp),
            color = hoshiSurfaces.group,
            contentColor = hoshiSurfaces.content,
            border = hoshiContainerBorder(),
            tonalElevation = 0.dp,
        ) {
            Column { content() }
        }
    }
}

@Composable
private fun DisplaySwitchRow(
    label: String,
    checked: Boolean,
    interactionEnabled: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = interactionEnabled) { onCheckedChange(!checked) }
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(label, modifier = Modifier.weight(1f), color = hoshiSurfaces.content)
        Switch(
            checked = checked,
            onCheckedChange = { if (interactionEnabled) onCheckedChange(it) },
        )
    }
}

@Composable
private fun DisplayChoiceRow(
    label: String,
    selected: Boolean,
    interactionEnabled: Boolean,
    onClick: () -> Unit,
    previewColor: Long? = null,
    previewColors: Triple<Long, Long, Long>? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = interactionEnabled, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        when {
            previewColors != null -> PaletteSwatch(previewColors)
            previewColor != null -> ColorSwatch(previewColor)
        }
        if (previewColor != null || previewColors != null) Spacer(Modifier.size(10.dp))
        Text(
            text = label,
            modifier = Modifier.weight(1f),
            color = hoshiSurfaces.content,
        )
        RadioButton(selected = selected, onClick = null)
    }
}

@Composable
private fun PaletteSwatch(colors: Triple<Long, Long, Long>) {
    Row(
        modifier = Modifier
            .size(width = 50.dp, height = 28.dp)
            .hoshiContainerOutline(RoundedCornerShape(8.dp)),
    ) {
        Box(Modifier.weight(1f).height(28.dp).background(Color(colors.first)))
        Box(Modifier.weight(1f).height(28.dp).background(Color(colors.second)))
        Box(Modifier.weight(1f).height(28.dp).background(Color(colors.third)))
    }
}

@Composable
private fun ColorSwatch(color: Long) {
    Surface(
        modifier = Modifier.size(28.dp),
        shape = RoundedCornerShape(14.dp),
        color = Color(color),
        border = BorderStroke(1.dp, hoshiSurfaces.outline),
        tonalElevation = 0.dp,
    ) {}
}

@Composable
private fun PaletteEditorDialog(
    draft: DisplayPaletteDraft,
    isSaving: Boolean,
    onUpdate: (Long?, Long?, Long?) -> Unit,
    onSave: () -> Unit,
    onDismiss: () -> Unit,
) {
    var editing by remember { mutableStateOf<PaletteColorField?>(null) }
    HoshiAlertDialog(
        onDismissRequest = { if (!isSaving) onDismiss() },
        title = { Text(stringResource(draft.slot.customLabelRes)) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    text = stringResource(if (draft.slot == DisplayPaletteSlot.Light) R.string.display_settings_custom_light_explanation else R.string.display_settings_custom_dark_explanation),
                    style = MaterialTheme.typography.bodyMedium,
                )
                PalettePreview(draft)
                ReaderColorSettingRow(
                    label = stringResource(R.string.reader_appearance_background_color),
                    color = draft.backgroundColor,
                    onClick = { if (!isSaving) editing = PaletteColorField.Background },
                    horizontalPadding = 0.dp,
                )
                ReaderColorSettingRow(
                    label = stringResource(R.string.reader_appearance_text_color),
                    color = draft.textColor,
                    onClick = { if (!isSaving) editing = PaletteColorField.Text },
                    horizontalPadding = 0.dp,
                )
                ReaderColorSettingRow(
                    label = stringResource(R.string.reader_appearance_info_color),
                    color = draft.infoColor,
                    onClick = { if (!isSaving) editing = PaletteColorField.Info },
                    horizontalPadding = 0.dp,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onSave, enabled = !isSaving) {
                Text(stringResource(R.string.action_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !isSaving) {
                Text(stringResource(R.string.action_cancel))
            }
        },
    )
    editing?.let { field ->
        val color = when (field) {
            PaletteColorField.Background -> draft.backgroundColor
            PaletteColorField.Text -> draft.textColor
            PaletteColorField.Info -> draft.infoColor
        }
        ReaderColorPickerDialog(
            title = stringResource(field.labelRes),
            initialColor = color,
            defaultColor = color,
            onColorChange = { updated ->
                if (!isSaving) {
                    when (field) {
                        PaletteColorField.Background -> onUpdate(updated, null, null)
                        PaletteColorField.Text -> onUpdate(null, updated, null)
                        PaletteColorField.Info -> onUpdate(null, null, updated)
                    }
                }
                editing = null
            },
            onDismiss = { editing = null },
        )
    }
}

@Composable
private fun PalettePreview(draft: DisplayPaletteDraft) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        color = Color(draft.backgroundColor),
        border = BorderStroke(1.dp, hoshiSurfaces.outline),
        tonalElevation = 0.dp,
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(stringResource(R.string.display_settings_palette_preview_title), color = Color(draft.textColor))
            Text(
                stringResource(R.string.display_settings_palette_preview_info),
                color = Color(draft.infoColor),
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun AccentEditorDialog(
    draft: DisplayAccentDraft,
    systemDark: Boolean,
    isSaving: Boolean,
    onUpdate: (Long) -> Unit,
    onSave: () -> Unit,
    onDismiss: () -> Unit,
) {
    var input by remember(draft.color) { mutableStateOf(draft.color.toReaderColorHexInput(includeAlpha = false)) }
    val parsed = input.takeIf { it.trim().removePrefix("#").length == 6 }?.let(::readerColorFromHexInput)
    val invalid = input.isNotBlank() && parsed == null
    HoshiAlertDialog(
        onDismissRequest = { if (!isSaving) onDismiss() },
        title = { Text(stringResource(R.string.display_settings_custom_accent)) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                AccentControlPreview(draft.color, systemDark)
                OutlinedTextField(
                    value = input,
                    onValueChange = { value ->
                        input = value
                        val raw = value.trim().removePrefix("#")
                        if (raw.length == 6) readerColorFromHexInput(value)?.let(onUpdate)
                    },
                    label = { Text(stringResource(R.string.display_settings_accent_hex)) },
                    supportingText = if (invalid) {
                        { Text(stringResource(R.string.display_settings_accent_hex_invalid)) }
                    } else {
                        null
                    },
                    isError = invalid,
                    singleLine = true,
                    enabled = !isSaving,
                    colors = hoshiOutlinedTextFieldColors(),
                    modifier = Modifier.fillMaxWidth(),
                )
                AccentChannelSlider(
                    label = stringResource(R.string.reader_appearance_color_red),
                    value = draft.color.readerColorRed(),
                    enabled = !isSaving,
                    onValueChange = { color ->
                        onUpdate(draft.color.withReaderColorRed(color))
                        input = draft.color.withReaderColorRed(color).toReaderColorHexInput(includeAlpha = false)
                    },
                )
                AccentChannelSlider(
                    label = stringResource(R.string.reader_appearance_color_green),
                    value = draft.color.readerColorGreen(),
                    enabled = !isSaving,
                    onValueChange = { color ->
                        onUpdate(draft.color.withReaderColorGreen(color))
                        input = draft.color.withReaderColorGreen(color).toReaderColorHexInput(includeAlpha = false)
                    },
                )
                AccentChannelSlider(
                    label = stringResource(R.string.reader_appearance_color_blue),
                    value = draft.color.readerColorBlue(),
                    enabled = !isSaving,
                    onValueChange = { color ->
                        onUpdate(draft.color.withReaderColorBlue(color))
                        input = draft.color.withReaderColorBlue(color).toReaderColorHexInput(includeAlpha = false)
                    },
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onSave, enabled = !isSaving && parsed != null) {
                Text(stringResource(R.string.action_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !isSaving) {
                Text(stringResource(R.string.action_cancel))
            }
        },
    )
}

@Composable
private fun AccentChannelSlider(
    label: String,
    value: Int,
    enabled: Boolean,
    onValueChange: (Int) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            Text(value.toString(), style = MaterialTheme.typography.bodyMedium)
        }
        Slider(
            value = value.toFloat(),
            onValueChange = { onValueChange(it.roundToInt()) },
            enabled = enabled,
            valueRange = 0f..255f,
            steps = 254,
        )
    }
}

private enum class PaletteColorField(val labelRes: Int) {
    Background(R.string.reader_appearance_background_color),
    Text(R.string.reader_appearance_text_color),
    Info(R.string.reader_appearance_info_color),
}

private enum class AccentPreset(val color: Long, val labelRes: Int) {
    Purple(0xFF6750A4L, R.string.display_settings_accent_purple),
    Blue(0xFF1565C0L, R.string.display_settings_accent_blue),
    Teal(0xFF00796BL, R.string.display_settings_accent_teal),
    Green(0xFF2E7D32L, R.string.display_settings_accent_green),
    Yellow(0xFFF9A825L, R.string.display_settings_accent_yellow),
    Orange(0xFFEF6C00L, R.string.display_settings_accent_orange),
    Red(0xFFC62828L, R.string.display_settings_accent_red),
    Pink(0xFFAD1457L, R.string.display_settings_accent_pink),
}

private val DisplayPalettePreset.labelRes: Int
    get() = when (this) {
        DisplayPalettePreset.Light -> R.string.reader_appearance_theme_light
        DisplayPalettePreset.Sepia -> R.string.reader_appearance_theme_sepia
        DisplayPalettePreset.Dark -> R.string.reader_appearance_theme_dark
        DisplayPalettePreset.DarkSepia -> R.string.display_settings_palette_dark_sepia
        DisplayPalettePreset.Custom -> R.string.reader_appearance_theme_custom
    }

private fun palettePreviewColors(
    slot: DisplayPaletteSlot,
    preset: DisplayPalettePreset,
    selection: DisplayPaletteSelection,
): Triple<Long, Long, Long> {
    val display = resolveDisplaySettings(
        AppDisplaySettings(
            autoSwitch = false,
            manualPaletteSlot = slot,
            lightPalette = selection.copy(preset = preset),
            darkPalette = selection.copy(preset = preset),
        ),
        systemDark = false,
    )
    return Triple(display.backgroundColor, display.textColor, display.infoColor)
}

private val DisplayPaletteSlot.customLabelRes: Int
    get() = when (this) {
        DisplayPaletteSlot.Light -> R.string.display_settings_custom_light_palette
        DisplayPaletteSlot.Dark -> R.string.display_settings_custom_dark_palette
    }
