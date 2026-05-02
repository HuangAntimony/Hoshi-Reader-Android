package moe.antimony.hoshi.features.reader

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import moe.antimony.hoshi.importing.FileImportContent
import moe.antimony.hoshi.importing.ImportFileType
import java.util.Locale
import kotlin.math.round

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ReaderAppearanceScreen(
    settings: ReaderSettings,
    onSettingsChange: (ReaderSettings) -> Unit,
    fontManager: ReaderFontManager,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = appearancePalette()
    BackHandler(onBack = onClose)
    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("Appearance", fontWeight = FontWeight.SemiBold) },
                navigationIcon = {
                    IconButton(onClick = onClose) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                            contentDescription = "Back",
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = palette.background,
                    titleContentColor = palette.onBackground,
                    navigationIconContentColor = palette.onBackground,
                ),
            )
        },
        containerColor = palette.background,
    ) { padding ->
        ReaderAppearanceContent(
            settings = settings,
            onSettingsChange = onSettingsChange,
            fontManager = fontManager,
            contentPadding = PaddingValues(
                start = 24.dp,
                end = 24.dp,
                top = padding.calculateTopPadding() + 14.dp,
                bottom = 128.dp,
            ),
            modifier = Modifier.fillMaxSize(),
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ReaderAppearanceSheet(
    settings: ReaderSettings,
    onSettingsChange: (ReaderSettings) -> Unit,
    fontManager: ReaderFontManager,
    onDismiss: () -> Unit,
) {
    val palette = appearancePalette()
    val sheetStyle = readerSheetStyle()
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = sheetStyle.containerColor,
        contentColor = sheetStyle.contentColor,
        scrimColor = sheetStyle.scrimColor,
        dragHandle = { ReaderSheetDragHandle(sheetStyle) },
    ) {
        ReaderAppearanceContent(
            settings = settings,
            onSettingsChange = onSettingsChange,
            fontManager = fontManager,
            contentPadding = PaddingValues(start = 24.dp, end = 24.dp, bottom = 32.dp),
            showDone = true,
            onDone = onDismiss,
        )
    }
}

@Composable
private fun ReaderAppearanceContent(
    settings: ReaderSettings,
    onSettingsChange: (ReaderSettings) -> Unit,
    fontManager: ReaderFontManager,
    contentPadding: PaddingValues,
    modifier: Modifier = Modifier,
    showDone: Boolean = false,
    onDone: () -> Unit = {},
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var importedFonts by remember { mutableStateOf(fontManager.storedFonts()) }
    var fontMenuExpanded by remember { mutableStateOf(false) }
    var fontToDelete by remember { mutableStateOf<String?>(null) }
    var isImportingFont by remember { mutableStateOf(false) }
    val fontImporter = rememberLauncherForActivityResult(FileImportContent()) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        runCatching {
            context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        scope.launch {
            isImportingFont = true
            runCatching {
                withContext(Dispatchers.IO) {
                    fontManager.importFont(context.contentResolver, uri)
                }
            }
            importedFonts = fontManager.storedFonts()
            isImportingFont = false
        }
    }
    val fontOptions = remember(importedFonts, settings.selectedFont) {
        (ReaderFontManager.defaultFonts + importedFonts.map { it.name } + settings.selectedFont)
            .filter { it.isNotBlank() }
            .distinct()
    }
    val palette = appearancePalette()

    CompositionLocalProvider(LocalContentColor provides palette.onBackground) {
        LazyColumn(
            modifier = modifier
                .fillMaxWidth()
                .background(palette.background),
            contentPadding = contentPadding,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item {
                Text(
                    text = "Appearance",
                    style = MaterialTheme.typography.headlineLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = palette.onBackground,
                    modifier = Modifier.padding(top = 8.dp, bottom = 4.dp),
                )
            }
            item {
                AppearanceSection(title = "Theme", palette = palette) {
                    SegmentedRow(
                        label = "Appearance",
                        options = ReaderTheme.entries.map { it.label },
                        selected = settings.theme.label,
                        onSelected = { label ->
                            ReaderTheme.entries.firstOrNull { it.label == label }?.let {
                                onSettingsChange(settings.copy(theme = it))
                            }
                        },
                        palette = palette,
                    )
                    AppearanceDivider(palette)
                    SwitchRow(
                        label = "E-ink Mode",
                        checked = settings.eInkMode,
                        onCheckedChange = { onSettingsChange(settings.copy(eInkMode = it)) },
                    )
                }
            }
            item {
                AppearanceSection(title = "Text", palette = palette) {
                SegmentedRow(
                    label = "Text Orientation",
                    options = listOf("縦", "横"),
                    selected = if (settings.verticalWriting) "縦" else "横",
                    onSelected = { label ->
                        onSettingsChange(settings.copy(verticalWriting = label == "縦"))
                    },
                    palette = palette,
                )
                AppearanceDivider(palette)
                ReaderFontRow(
                    settings = settings,
                    fontOptions = fontOptions,
                    fontMenuExpanded = fontMenuExpanded,
                    onFontMenuExpandedChange = { fontMenuExpanded = it },
                    onFontSelected = { fontName ->
                        fontMenuExpanded = false
                        onSettingsChange(settings.copy(selectedFont = fontName))
                    },
                    canDeleteFont = !fontManager.isDefaultFont(settings.selectedFont),
                    onDeleteFont = { fontToDelete = settings.selectedFont },
                )
                AppearanceDivider(palette)
                ActionRow(
                    label = "Import Font",
                    button = if (isImportingFont) "Importing..." else "Import",
                    enabled = !isImportingFont,
                    onClick = { fontImporter.launch(ImportFileType.ReaderFont.mimeTypes) },
                )
                AppearanceDivider(palette)
                StepperRow(
                    label = "Font Size",
                    value = settings.fontSize.toString(),
                    onDecrease = { onSettingsChange(settings.copy(fontSize = (settings.fontSize - 1).coerceAtLeast(16))) },
                    onIncrease = { onSettingsChange(settings.copy(fontSize = (settings.fontSize + 1).coerceAtMost(40))) },
                    palette = palette,
                )
                AppearanceDivider(palette)
                SwitchRow(
                    label = "Hide Furigana",
                    checked = settings.hideFurigana,
                    onCheckedChange = { onSettingsChange(settings.copy(hideFurigana = it)) },
                )
            }
        }
        item {
            AppearanceSection(title = "Layout", palette = palette) {
                StepperRow(
                    label = "Horizontal Padding",
                    value = "${settings.horizontalPadding}%",
                    onDecrease = { onSettingsChange(settings.copy(horizontalPadding = (settings.horizontalPadding - 1).coerceAtLeast(0))) },
                    onIncrease = { onSettingsChange(settings.copy(horizontalPadding = (settings.horizontalPadding + 1).coerceAtMost(50))) },
                    palette = palette,
                )
                AppearanceDivider(palette)
                StepperRow(
                    label = "Vertical Padding",
                    value = "${settings.verticalPadding}%",
                    onDecrease = { onSettingsChange(settings.copy(verticalPadding = (settings.verticalPadding - 1).coerceAtLeast(0))) },
                    onIncrease = { onSettingsChange(settings.copy(verticalPadding = (settings.verticalPadding + 1).coerceAtMost(50))) },
                    palette = palette,
                )
                AppearanceDivider(palette)
                SwitchRow(
                    label = "Avoid Page Break",
                    checked = settings.avoidPageBreak,
                    onCheckedChange = { onSettingsChange(settings.copy(avoidPageBreak = it)) },
                )
                AppearanceDivider(palette)
                SwitchRow(
                    label = "Justify Text",
                    checked = settings.justifyText,
                    onCheckedChange = { onSettingsChange(settings.copy(justifyText = it)) },
                )
                AppearanceDivider(palette)
                SwitchRow(
                    label = "Advanced",
                    checked = settings.layoutAdvanced,
                    onCheckedChange = { onSettingsChange(settings.copy(layoutAdvanced = it)) },
                )
                if (settings.layoutAdvanced) {
                    AppearanceDivider(palette)
                    SliderRow(
                        label = "Line Height",
                        value = String.format(Locale.US, "%.2f", settings.lineHeight),
                        sliderValue = settings.lineHeight.toFloat(),
                        valueRange = 1.0f..2.5f,
                        steps = 29,
                        onValueChange = { value ->
                            onSettingsChange(settings.copy(lineHeight = round(value * 20) / 20.0))
                        },
                    )
                    AppearanceDivider(palette)
                    SliderRow(
                        label = "Character Spacing",
                        value = "${settings.characterSpacing.toInt()}%",
                        sliderValue = settings.characterSpacing.toFloat(),
                        valueRange = -10f..10f,
                        steps = 19,
                        onValueChange = { value ->
                            onSettingsChange(settings.copy(characterSpacing = round(value).toDouble()))
                        },
                    )
                }
            }
        }
        item {
            AppearanceSection(title = "Display", palette = palette) {
                SwitchRow(
                    label = "Show Title",
                    checked = settings.showTitle,
                    onCheckedChange = { onSettingsChange(settings.copy(showTitle = it)) },
                )
                AppearanceDivider(palette)
                SwitchRow(
                    label = "Show Character Count",
                    checked = settings.showCharacters,
                    onCheckedChange = { onSettingsChange(settings.copy(showCharacters = it)) },
                )
                AppearanceDivider(palette)
                SwitchRow(
                    label = "Show Percentage",
                    checked = settings.showPercentage,
                    onCheckedChange = { onSettingsChange(settings.copy(showPercentage = it)) },
                )
                if (settings.showCharacters || settings.showPercentage) {
                    AppearanceDivider(palette)
                    SegmentedRow(
                        label = "Progress Position",
                        options = listOf("Top", "Bottom"),
                        selected = if (settings.showProgressTop) "Top" else "Bottom",
                        onSelected = { label -> onSettingsChange(settings.copy(showProgressTop = label == "Top")) },
                        palette = palette,
                    )
                }
            }
        }
        item {
            AppearanceSection(title = "Popup", palette = palette) {
                SliderRow(
                    label = "Width",
                    value = settings.popupWidth.toString(),
                    sliderValue = settings.popupWidth.toFloat(),
                    valueRange = 100f..700f,
                    steps = 59,
                    onValueChange = { value ->
                        onSettingsChange(settings.copy(popupWidth = (round(value / 10) * 10).toInt()))
                    },
                )
                AppearanceDivider(palette)
                SliderRow(
                    label = "Height",
                    value = settings.popupHeight.toString(),
                    sliderValue = settings.popupHeight.toFloat(),
                    valueRange = 100f..500f,
                    steps = 39,
                    onValueChange = { value ->
                        onSettingsChange(settings.copy(popupHeight = (round(value / 10) * 10).toInt()))
                    },
                )
                AppearanceDivider(palette)
                SwitchRow(
                    label = "Full-width",
                    checked = settings.popupFullWidth,
                    onCheckedChange = { onSettingsChange(settings.copy(popupFullWidth = it)) },
                )
                AppearanceDivider(palette)
                SwitchRow(
                    label = "Swipe to Dismiss",
                    checked = settings.popupSwipeToDismiss,
                    onCheckedChange = { onSettingsChange(settings.copy(popupSwipeToDismiss = it)) },
                )
                if (settings.popupSwipeToDismiss) {
                    AppearanceDivider(palette)
                    SliderRow(
                        label = "Swipe Threshold",
                        value = settings.popupSwipeThreshold.toString(),
                        sliderValue = settings.popupSwipeThreshold.toFloat(),
                        valueRange = 20f..60f,
                        steps = 7,
                        onValueChange = { value ->
                            onSettingsChange(settings.copy(popupSwipeThreshold = (round(value / 5) * 5).toInt()))
                        },
                    )
                }
            }
        }
            if (showDone) {
                item {
                    Button(
                        onClick = onDone,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Done")
                    }
                }
            }
        }
    }

    fontToDelete?.let { fontName ->
        AlertDialog(
            onDismissRequest = { fontToDelete = null },
            title = { Text("Delete \"$fontName\"?") },
            confirmButton = {
                TextButton(
                    onClick = {
                        fontManager.deleteFont(fontName)
                        importedFonts = fontManager.storedFonts()
                        onSettingsChange(settings.copy(selectedFont = ReaderFontManager.defaultFonts.first()))
                        fontToDelete = null
                    },
                ) {
                    Text("Delete")
                }
            },
            dismissButton = {
                TextButton(onClick = { fontToDelete = null }) {
                    Text("Cancel")
                }
            },
        )
    }
}

@Composable
private fun AppearanceSection(
    title: String,
    palette: AppearancePalette,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = palette.onMuted,
            modifier = Modifier.padding(start = 12.dp),
        )
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(28.dp),
            color = palette.group,
            contentColor = palette.onGroup,
            border = BorderStroke(1.dp, palette.divider),
            tonalElevation = 0.dp,
        ) {
            Column(content = content)
        }
    }
}

@Composable
private fun SegmentedRow(
    label: String,
    options: List<String>,
    selected: String,
    onSelected: (String) -> Unit,
    palette: AppearancePalette,
) {
    val controls = @Composable {
        SingleChoiceSegmentedButtonRow(
            modifier = if (options.size <= 2) Modifier.width(segmentedControlWidthDp(options.size).dp) else Modifier.fillMaxWidth(),
        ) {
            options.forEachIndexed { index, option ->
                SegmentedButton(
                    selected = option == selected,
                    onClick = { onSelected(option) },
                    shape = SegmentedButtonDefaults.itemShape(index = index, count = options.size),
                ) {
                    Text(
                        text = option,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
    if (options.size > 2) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            controls()
        }
    } else {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 14.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            controls()
        }
    }
}

internal fun segmentedControlWidthDp(optionCount: Int): Int =
    if (optionCount <= 2) 180 else optionCount * 82

@Composable
private fun ReaderFontRow(
    settings: ReaderSettings,
    fontOptions: List<String>,
    fontMenuExpanded: Boolean,
    onFontMenuExpandedChange: (Boolean) -> Unit,
    onFontSelected: (String) -> Unit,
    canDeleteFont: Boolean,
    onDeleteFont: () -> Unit,
) {
    Column(
        modifier = Modifier.padding(horizontal = 20.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("Font", style = MaterialTheme.typography.bodyLarge)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(modifier = Modifier.weight(1f)) {
                TextButton(onClick = { onFontMenuExpandedChange(true) }) {
                    Text(settings.selectedFont)
                }
                DropdownMenu(
                    expanded = fontMenuExpanded,
                    onDismissRequest = { onFontMenuExpandedChange(false) },
                ) {
                    fontOptions.forEach { fontName ->
                        DropdownMenuItem(
                            text = { Text(fontName) },
                            onClick = { onFontSelected(fontName) },
                        )
                    }
                }
            }
            if (canDeleteFont) {
                TextButton(onClick = onDeleteFont) {
                    Text("Delete")
                }
            }
        }
    }
}

@Composable
private fun ActionRow(
    label: String,
    button: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 14.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodyLarge)
        Button(onClick = onClick, enabled = enabled) {
            Text(button)
        }
    }
}

@Composable
private fun SwitchRow(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodyLarge)
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun SliderRow(
    label: String,
    value: String,
    sliderValue: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    steps: Int,
    onValueChange: (Float) -> Unit,
) {
    Column(
        modifier = Modifier.padding(horizontal = 20.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            Text(value, style = MaterialTheme.typography.bodyLarge)
        }
        Slider(
            value = sliderValue.coerceIn(valueRange.start, valueRange.endInclusive),
            onValueChange = onValueChange,
            valueRange = valueRange,
            steps = steps,
        )
    }
}

@Composable
private fun StepperRow(
    label: String,
    value: String,
    onDecrease: () -> Unit,
    onIncrease: () -> Unit,
    palette: AppearancePalette,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 14.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodyLarge)
        Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(value, style = MaterialTheme.typography.bodyLarge)
            Surface(
                shape = RoundedCornerShape(24.dp),
                color = palette.stepperContainer,
                contentColor = palette.onGroup,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onDecrease) {
                        Icon(
                            imageVector = Icons.Rounded.Remove,
                            contentDescription = "Decrease",
                        )
                    }
                    Box(
                        modifier = Modifier
                            .size(width = 1.dp, height = 28.dp)
                            .background(palette.stepperDivider),
                    )
                    IconButton(onClick = onIncrease) {
                        Icon(
                            imageVector = Icons.Rounded.Add,
                            contentDescription = "Increase",
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun AppearanceDivider(palette: AppearancePalette) {
    HorizontalDivider(
        modifier = Modifier.padding(horizontal = 20.dp),
        color = palette.divider,
    )
}

private data class AppearancePalette(
    val background: Color,
    val group: Color,
    val onBackground: Color,
    val onGroup: Color,
    val onMuted: Color,
    val divider: Color,
    val stepperContainer: Color,
    val stepperDivider: Color,
)

@Composable
private fun appearancePalette(): AppearancePalette {
    val colorScheme = MaterialTheme.colorScheme
    return AppearancePalette(
        background = colorScheme.background,
        group = colorScheme.surface,
        onBackground = colorScheme.onBackground,
        onGroup = colorScheme.onSurface,
        onMuted = colorScheme.onSurfaceVariant,
        divider = colorScheme.outlineVariant,
        stepperContainer = colorScheme.surfaceVariant,
        stepperDivider = colorScheme.outline,
    )
}
