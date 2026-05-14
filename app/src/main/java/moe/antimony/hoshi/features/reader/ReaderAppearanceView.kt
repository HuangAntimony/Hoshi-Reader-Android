package moe.antimony.hoshi.features.reader

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import moe.antimony.hoshi.features.settings.SettingsDetailScaffold
import moe.antimony.hoshi.features.sasayaki.SasayakiSettings
import moe.antimony.hoshi.importing.FileImportContent
import moe.antimony.hoshi.importing.ImportFileType
import moe.antimony.hoshi.importing.importDisplayName
import moe.antimony.hoshi.ui.HoshiBlockingProgressOverlay
import moe.antimony.hoshi.ui.theme.LocalHoshiEInkMode
import java.util.Locale
import kotlin.math.round

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ReaderAppearanceScreen(
    settings: ReaderSettings,
    onSettingsChange: (ReaderSettings) -> Unit,
    sasayakiSettings: SasayakiSettings,
    onSasayakiSettingsChange: (SasayakiSettings) -> Unit,
    fontManager: ReaderFontManager,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = appearancePalette()
    SettingsDetailScaffold(
        title = "Appearance",
        onClose = onClose,
        modifier = modifier.fillMaxSize(),
        containerColor = palette.background,
        contentColor = palette.onBackground,
    ) { padding ->
        ReaderAppearanceContent(
            settings = settings,
            onSettingsChange = onSettingsChange,
            sasayakiSettings = sasayakiSettings,
            onSasayakiSettingsChange = onSasayakiSettingsChange,
            fontManager = fontManager,
            contentPadding = PaddingValues(
                start = 24.dp,
                end = 24.dp,
                top = padding.calculateTopPadding() + 12.dp,
                bottom = 128.dp,
            ),
            showTitle = false,
            modifier = Modifier.fillMaxSize(),
        )
    }
}

@Composable
internal fun ReaderAppearanceSheet(
    settings: ReaderSettings,
    onSettingsChange: (ReaderSettings) -> Unit,
    sasayakiSettings: SasayakiSettings,
    onSasayakiSettingsChange: (SasayakiSettings) -> Unit,
    fontManager: ReaderFontManager,
    onDismiss: () -> Unit,
) {
    val palette = appearancePalette()
    val sheetStyle = readerSheetStyle().copy(
        containerColor = palette.background,
        contentColor = palette.onBackground,
    )
    ReaderBottomPanel(
        sheetStyle = sheetStyle,
        onDismiss = onDismiss,
    ) {
        ReaderAppearanceContent(
            settings = settings,
            onSettingsChange = onSettingsChange,
            sasayakiSettings = sasayakiSettings,
            onSasayakiSettingsChange = onSasayakiSettingsChange,
            fontManager = fontManager,
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 24.dp),
            showTitle = false,
            showDone = true,
            onDone = onDismiss,
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
        )
    }
}

@Composable
private fun ReaderAppearanceContent(
    settings: ReaderSettings,
    onSettingsChange: (ReaderSettings) -> Unit,
    sasayakiSettings: SasayakiSettings,
    onSasayakiSettingsChange: (SasayakiSettings) -> Unit,
    fontManager: ReaderFontManager,
    contentPadding: PaddingValues,
    modifier: Modifier = Modifier,
    showTitle: Boolean = true,
    showDone: Boolean = false,
    onDone: () -> Unit = {},
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var importedFonts by remember { mutableStateOf(fontManager.storedFonts()) }
    var fontMenuExpanded by remember { mutableStateOf(false) }
    var fontToDelete by remember { mutableStateOf<String?>(null) }
    var isImportingFont by remember { mutableStateOf(false) }
    var importingFontMessage by remember { mutableStateOf<String?>(null) }
    val fontImporter = rememberLauncherForActivityResult(FileImportContent()) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        val displayName = context.contentResolver.importDisplayName(uri).ifBlank { "font" }
        runCatching {
            context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        scope.launch {
            isImportingFont = true
            importingFontMessage = "Importing $displayName..."
            runCatching {
                withContext(Dispatchers.IO) {
                    fontManager.importFont(context.contentResolver, uri)
                }
            }
            importedFonts = fontManager.storedFonts()
            importingFontMessage = null
            isImportingFont = false
        }
    }
    val fontOptions = remember(importedFonts, settings.selectedFont) {
        (ReaderFontManager.defaultFonts + importedFonts.map { it.name } + settings.selectedFont)
            .filter { it.isNotBlank() }
            .distinct()
    }
    val palette = appearancePalette()
    val metrics = readerSheetDensityMetrics()

    CompositionLocalProvider(LocalContentColor provides palette.onBackground) {
        Box(
            modifier = modifier
                .fillMaxWidth()
                .background(palette.background),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(contentPadding),
                verticalArrangement = Arrangement.spacedBy(metrics.appearanceSectionSpacingDp.dp),
            ) {
                if (showTitle) {
                    Text(
                        text = "Appearance",
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = palette.onBackground,
                        modifier = Modifier.padding(top = 6.dp, bottom = 2.dp),
                    )
                }
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
                    if (settings.theme == ReaderTheme.System) {
                        AppearanceDivider(palette)
                        SwitchRow(
                            label = "Use Sepia as Light Theme",
                            checked = settings.systemLightSepia,
                            onCheckedChange = { onSettingsChange(settings.copy(systemLightSepia = it)) },
                        )
                    }
                    if (settings.theme == ReaderTheme.Sepia) {
                        AppearanceDivider(palette)
                        SwitchRow(
                            label = "Invert in System Dark Theme",
                            checked = settings.sepiaInvertInDark,
                            onCheckedChange = { onSettingsChange(settings.copy(sepiaInvertInDark = it)) },
                        )
                    }
                }
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
                        onIncrease = { onSettingsChange(settings.copy(fontSize = (settings.fontSize + 1).coerceAtMost(60))) },
                        palette = palette,
                    )
                    AppearanceDivider(palette)
                    SwitchRow(
                        label = "Hide Furigana",
                        checked = settings.hideFurigana,
                        onCheckedChange = { onSettingsChange(settings.copy(hideFurigana = it)) },
                    )
                }
                AppearanceSection(title = "Layout", palette = palette) {
                    SegmentedRow(
                        label = "Mode",
                        options = listOf("Paginated", "Continuous"),
                        selected = if (settings.continuousMode) "Continuous" else "Paginated",
                        onSelected = { label ->
                            onSettingsChange(settings.copy(continuousMode = label == "Continuous"))
                        },
                        palette = palette,
                    )
                    if (settings.continuousMode) {
                        AppearanceDivider(palette)
                        SliderRow(
                            label = "Chapter Swipe Distance",
                            value = settings.chapterSwipeDistance.toString(),
                            sliderValue = settings.chapterSwipeDistance.toFloat(),
                            valueRange = 10f..60f,
                            steps = 9,
                            onValueChange = { value ->
                                onSettingsChange(settings.copy(chapterSwipeDistance = (round(value / 5) * 5).toInt()))
                            },
                        )
                    }
                    AppearanceDivider(palette)
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
                    readerAppearanceStatisticsRows(settings).forEach { label ->
                        AppearanceDivider(palette)
                        SwitchRow(
                            label = label,
                            checked = when (label) {
                                "Show Statistics Toggle" -> settings.showStatisticsToggle
                                "Show Reading Speed" -> settings.showReadingSpeed
                                else -> settings.showReadingTime
                            },
                            onCheckedChange = { checked ->
                                onSettingsChange(
                                    when (label) {
                                        "Show Statistics Toggle" -> settings.copy(showStatisticsToggle = checked)
                                        "Show Reading Speed" -> settings.copy(showReadingSpeed = checked)
                                        else -> settings.copy(showReadingTime = checked)
                                    },
                                )
                            },
                        )
                    }
                    readerAppearanceSasayakiRows(sasayakiSettings).forEach { label ->
                        AppearanceDivider(palette)
                        SwitchRow(
                            label = label,
                            checked = sasayakiSettings.showReaderToggle,
                            onCheckedChange = {
                                onSasayakiSettingsChange(sasayakiSettings.copy(showReaderToggle = it))
                            },
                        )
                    }
                }
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
                        label = "Reduced Motion Scrolling",
                        checked = settings.popupReducedMotionScrolling,
                        onCheckedChange = { onSettingsChange(settings.copy(popupReducedMotionScrolling = it)) },
                    )
                    if (settings.popupReducedMotionScrolling) {
                        AppearanceDivider(palette)
                        SliderRow(
                            label = "Scroll Amount",
                            value = "${settings.popupReducedMotionScrollPercent}%",
                            sliderValue = settings.popupReducedMotionScrollPercent.toFloat(),
                            valueRange = 40f..100f,
                            steps = 5,
                            onValueChange = { value ->
                                onSettingsChange(settings.copy(popupReducedMotionScrollPercent = (round(value / 10) * 10).toInt()))
                            },
                        )
                        AppearanceDivider(palette)
                        SliderRow(
                            label = "Scroll Swipe Threshold",
                            value = settings.popupReducedMotionSwipeThreshold.toString(),
                            sliderValue = settings.popupReducedMotionSwipeThreshold.toFloat(),
                            valueRange = 0f..100f,
                            steps = 9,
                            onValueChange = { value ->
                                onSettingsChange(settings.copy(popupReducedMotionSwipeThreshold = (round(value / 10) * 10).toInt()))
                            },
                        )
                    }
                    AppearanceDivider(palette)
                    SwitchRow(
                        label = "Show Action Bar",
                        checked = settings.popupActionBar,
                        onCheckedChange = { onSettingsChange(settings.copy(popupActionBar = it)) },
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
                if (showDone) {
                    Button(
                        onClick = onDone,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Done")
                    }
                }
            }
            importingFontMessage?.let { message ->
                HoshiBlockingProgressOverlay(
                    message = message,
                    modifier = Modifier.fillMaxSize(),
                )
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

internal fun readerAppearanceSasayakiRows(settings: SasayakiSettings): List<String> =
    if (settings.enabled) listOf("Show Sasayaki Toggle") else emptyList()

internal fun readerAppearanceStatisticsRows(settings: ReaderSettings): List<String> =
    if (settings.enableStatistics) {
        listOf("Show Statistics Toggle", "Show Reading Speed", "Show Reading Time")
    } else {
        emptyList()
    }

@Composable
private fun AppearanceSection(
    title: String,
    palette: AppearancePalette,
    content: @Composable ColumnScope.() -> Unit,
) {
    val metrics = readerSheetDensityMetrics()
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            color = palette.onMuted,
            modifier = Modifier.padding(start = 10.dp),
        )
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(metrics.appearanceSectionCornerRadiusDp.dp),
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
    val metrics = readerSheetDensityMetrics()
    val controls = @Composable {
        IosSegmentedControl(
            options = options,
            selected = selected,
            onSelected = onSelected,
            palette = palette,
            modifier = if (options.size <= 2) {
                Modifier.width(segmentedControlWidthDp(options).dp)
            } else {
                Modifier.fillMaxWidth()
            },
        )
    }
    if (options.size > 2) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = metrics.appearanceWideRowVerticalPaddingDp.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            controls()
        }
    } else {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = metrics.appearanceRowVerticalPaddingDp.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = label,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            controls()
        }
    }
}

@Composable
private fun IosSegmentedControl(
    options: List<String>,
    selected: String,
    onSelected: (String) -> Unit,
    palette: AppearancePalette,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.height(readerSheetDensityMetrics().appearanceSegmentedControlHeightDp.dp),
        shape = RoundedCornerShape(17.dp),
        color = palette.segmentContainer,
        contentColor = palette.onGroup,
        border = BorderStroke(1.dp, palette.segmentBorder),
        tonalElevation = 0.dp,
    ) {
        Row(modifier = Modifier.fillMaxSize()) {
            options.forEachIndexed { index, option ->
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxSize()
                        .background(if (option == selected) palette.segmentSelected else Color.Transparent)
                        .clickable(enabled = option != selected) { onSelected(option) },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = option,
                        style = MaterialTheme.typography.labelLarge,
                        color = if (option == selected) {
                            palette.segmentSelectedContent
                        } else {
                            palette.segmentUnselectedContent
                        },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (index < options.lastIndex) {
                    Box(
                        modifier = Modifier
                            .width(1.dp)
                            .fillMaxSize()
                            .background(palette.segmentBorder),
                    )
                }
            }
        }
    }
}

internal fun segmentedControlWidthDp(optionCount: Int): Int =
    if (optionCount <= 2) 120 else optionCount * 82

internal fun segmentedControlWidthDp(options: List<String>): Int =
    when {
        options.size > 2 -> options.size * 82
        options.any { it.length >= 10 } -> 180
        options.any { it.length >= 6 } -> 120
        else -> 100
    }

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
    val metrics = readerSheetDensityMetrics()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = metrics.appearanceFontRowVerticalPaddingDp.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "Font",
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Row(
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box {
                CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides 0.dp) {
                    TextButton(onClick = { onFontMenuExpandedChange(true) }) {
                        Text(
                            text = settings.selectedFont,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
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
            }
            if (canDeleteFont) {
                CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides 0.dp) {
                    TextButton(onClick = onDeleteFont) {
                        Text("Delete")
                    }
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
    val metrics = readerSheetDensityMetrics()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = metrics.appearanceRowVerticalPaddingDp.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
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
    val metrics = readerSheetDensityMetrics()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = metrics.appearanceRowVerticalPaddingDp.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides metrics.appearanceSwitchMinimumInteractiveSizeDp.dp) {
            Switch(checked = checked, onCheckedChange = onCheckedChange)
        }
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
    val metrics = readerSheetDensityMetrics()
    Column(
        modifier = Modifier.padding(horizontal = 14.dp, vertical = metrics.appearanceSliderVerticalPaddingDp.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(
                text = label,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
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
    val metrics = readerSheetDensityMetrics()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = metrics.appearanceRowVerticalPaddingDp.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(value, style = MaterialTheme.typography.bodyLarge)
            Surface(
                shape = RoundedCornerShape(18.dp),
                color = palette.stepperContainer,
                contentColor = palette.onGroup,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(
                        onClick = onDecrease,
                        modifier = Modifier.size(metrics.stepperButtonSizeDp.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Remove,
                            contentDescription = "Decrease",
                            modifier = Modifier.size(metrics.stepperIconSizeDp.dp),
                        )
                    }
                    Box(
                        modifier = Modifier
                            .size(width = 1.dp, height = 28.dp)
                            .background(palette.stepperDivider),
                    )
                    IconButton(
                        onClick = onIncrease,
                        modifier = Modifier.size(metrics.stepperButtonSizeDp.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Add,
                            contentDescription = "Increase",
                            modifier = Modifier.size(metrics.stepperIconSizeDp.dp),
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
        modifier = Modifier.padding(horizontal = 14.dp),
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
    val segmentContainer: Color,
    val segmentSelected: Color,
    val segmentSelectedContent: Color,
    val segmentUnselectedContent: Color,
    val segmentBorder: Color,
    val stepperContainer: Color,
    val stepperDivider: Color,
)

@Composable
private fun appearancePalette(): AppearancePalette {
    val colorScheme = MaterialTheme.colorScheme
    val segmentedControlColors = readerSegmentedControlColors(
        eInkMode = LocalHoshiEInkMode.current,
        background = colorScheme.background,
        content = colorScheme.onBackground,
        surfaceVariant = colorScheme.surfaceVariant,
        primaryContainer = colorScheme.primaryContainer,
        onPrimaryContainer = colorScheme.onPrimaryContainer,
        outlineVariant = colorScheme.outlineVariant,
    )
    return AppearancePalette(
        background = colorScheme.background,
        group = colorScheme.surface,
        onBackground = colorScheme.onBackground,
        onGroup = colorScheme.onSurface,
        onMuted = colorScheme.onSurfaceVariant,
        divider = colorScheme.outlineVariant,
        segmentContainer = segmentedControlColors.container,
        segmentSelected = segmentedControlColors.selected,
        segmentSelectedContent = segmentedControlColors.selectedContent,
        segmentUnselectedContent = segmentedControlColors.unselectedContent,
        segmentBorder = segmentedControlColors.border,
        stepperContainer = colorScheme.surfaceVariant,
        stepperDivider = colorScheme.outline,
    )
}

internal data class ReaderSegmentedControlColors(
    val container: Color,
    val selected: Color,
    val selectedContent: Color,
    val unselectedContent: Color,
    val border: Color,
)

internal fun readerSegmentedControlColors(
    eInkMode: Boolean,
    background: Color,
    content: Color,
    surfaceVariant: Color,
    primaryContainer: Color,
    onPrimaryContainer: Color,
    outlineVariant: Color,
): ReaderSegmentedControlColors =
    if (eInkMode) {
        ReaderSegmentedControlColors(
            container = background,
            selected = content,
            selectedContent = background,
            unselectedContent = content,
            border = content,
        )
    } else {
        ReaderSegmentedControlColors(
            container = surfaceVariant.copy(alpha = 0.5f),
            selected = primaryContainer,
            selectedContent = onPrimaryContainer,
            unselectedContent = content,
            border = outlineVariant,
        )
    }
