package moe.antimony.hoshi.features.reader

import android.content.Intent
import android.net.Uri
import android.text.format.Formatter
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.annotation.StringRes
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import moe.antimony.hoshi.R
import moe.antimony.hoshi.features.settings.SettingsDetailScaffold
import moe.antimony.hoshi.features.sasayaki.SasayakiSettings
import moe.antimony.hoshi.importing.FileImportContent
import moe.antimony.hoshi.importing.ImportFileType
import moe.antimony.hoshi.ui.HoshiBlockingProgressOverlay
import moe.antimony.hoshi.ui.asString
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
    viewModel: ReaderAppearanceViewModel = hiltViewModel(),
) {
    val palette = appearancePalette()
    SettingsDetailScaffold(
        title = stringResource(R.string.settings_appearance),
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
            viewModel = viewModel,
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
    progressDisplay: ReaderProgressDisplay = ReaderProgressDisplay.characters(),
    onSettingsChange: (ReaderSettings) -> Unit,
    sasayakiSettings: SasayakiSettings,
    onSasayakiSettingsChange: (SasayakiSettings) -> Unit,
    fontManager: ReaderFontManager,
    onDismiss: () -> Unit,
    viewModel: ReaderAppearanceViewModel = hiltViewModel(),
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
            progressDisplay = progressDisplay,
            onSettingsChange = onSettingsChange,
            sasayakiSettings = sasayakiSettings,
            onSasayakiSettingsChange = onSasayakiSettingsChange,
            fontManager = fontManager,
            viewModel = viewModel,
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
    modifier: Modifier = Modifier,
    progressDisplay: ReaderProgressDisplay = ReaderProgressDisplay.characters(),
    onSettingsChange: (ReaderSettings) -> Unit,
    sasayakiSettings: SasayakiSettings,
    onSasayakiSettingsChange: (SasayakiSettings) -> Unit,
    fontManager: ReaderFontManager,
    viewModel: ReaderAppearanceViewModel,
    contentPadding: PaddingValues,
    showTitle: Boolean = true,
    showDone: Boolean = false,
    onDone: () -> Unit = {},
) {
    val context = LocalContext.current
    val fontUiState by viewModel.uiState.collectAsStateWithLifecycle()
    val currentSettings by rememberUpdatedState(settings)
    val currentOnSettingsChange by rememberUpdatedState(onSettingsChange)
    var fontMenuExpanded by remember { mutableStateOf(false) }
    var fontVariantMenuExpanded by remember { mutableStateOf(false) }
    var fontDownloadOrigin by remember { mutableStateOf<ReaderFontDownloadOrigin?>(null) }
    var fontToDelete by remember { mutableStateOf<ReaderFontFamily?>(null) }
    var colorDialogRow by remember { mutableStateOf<ReaderAppearanceCustomColorRow?>(null) }
    val fontImporter = rememberLauncherForActivityResult(FileImportContent()) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        runCatching {
            context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        viewModel.importFont(context.contentResolver, uri)
    }
    val selectedFontSpec = remember(settings, fontUiState.library.revision) {
        fontManager.resolveRenderSpec(
            selectedFont = settings.selectedFont,
            familyId = settings.selectedFontFamilyId,
            variantId = settings.selectedFontVariantId,
        )
    }
    val selectedFontDisplayLabel = readerFontLabel(selectedFontSpec.familyId, selectedFontSpec.displayName)
    val selectedFontFamily = fontUiState.library.families.firstOrNull { it.id == selectedFontSpec.familyId }
    val selectedFontVariant = selectedFontFamily?.variants?.firstOrNull { it.id == selectedFontSpec.variantId }
    val fontPickerEntries = remember(fontUiState.library.families) {
        buildReaderFontPickerEntries(fontUiState.library.families)
    }
    val downloadingFamily = fontUiState.download?.let { activeDownload ->
        fontUiState.library.families.firstOrNull { it.id == activeDownload.familyId }
    }
    val downloadingVariant = downloadingFamily?.variants?.firstOrNull {
        it.id == fontUiState.download?.variantId
    }
    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                is ReaderAppearanceFontEvent.Apply -> {
                    val family = fontManager.fontFamilies().firstOrNull { it.id == event.familyId }
                    val variant = family?.variants?.firstOrNull { it.id == event.variantId }
                    if (family != null && variant != null) {
                        currentOnSettingsChange(currentSettings.withFontSelection(family, variant))
                        fontMenuExpanded = false
                        fontVariantMenuExpanded = false
                        fontDownloadOrigin = null
                    }
                }
            }
        }
    }
    DisposableEffect(viewModel) {
        onDispose(viewModel::cancelDownload)
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
                        text = stringResource(R.string.settings_appearance),
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = palette.onBackground,
                        modifier = Modifier.padding(top = 6.dp, bottom = 2.dp),
                    )
                }
                AppearanceSection(title = stringResource(R.string.reader_appearance_theme), palette = palette) {
                    val themeLabels = ReaderTheme.entries.associateWith { stringResource(it.labelRes) }
                    SegmentedRow(
                        label = stringResource(R.string.settings_appearance),
                        options = ReaderTheme.entries.map { themeLabels.getValue(it) },
                        selected = themeLabels.getValue(settings.theme),
                        onSelected = { label ->
                            ReaderTheme.entries.firstOrNull { themeLabels.getValue(it) == label }?.let {
                                onSettingsChange(settings.copy(theme = it))
                            }
                        },
                        palette = palette,
                    )
                    AppearanceDivider(palette)
                    SwitchRow(
                        label = stringResource(R.string.reader_appearance_eink_mode),
                        checked = settings.eInkMode,
                        onCheckedChange = { onSettingsChange(settings.copy(eInkMode = it)) },
                    )
                    if (settings.theme == ReaderTheme.System) {
                        AppearanceDivider(palette)
                        SwitchRow(
                            label = stringResource(R.string.reader_appearance_use_sepia_light_theme),
                            checked = settings.systemLightSepia,
                            onCheckedChange = { onSettingsChange(settings.copy(systemLightSepia = it)) },
                        )
                    }
                    if (settings.theme == ReaderTheme.Sepia) {
                        AppearanceDivider(palette)
                        SwitchRow(
                            label = stringResource(R.string.reader_appearance_invert_sepia_dark),
                            checked = settings.sepiaInvertInDark,
                            onCheckedChange = { onSettingsChange(settings.copy(sepiaInvertInDark = it)) },
                        )
                    }
                    if (readerAppearanceShowsCustomInterfaceTheme(settings)) {
                        AppearanceDivider(palette)
                        val interfaceLabels = ReaderInterfaceTheme.entries.associateWith { stringResource(it.labelRes) }
                        SegmentedRow(
                            label = stringResource(R.string.reader_appearance_interface),
                            options = ReaderInterfaceTheme.entries.map { interfaceLabels.getValue(it) },
                            selected = interfaceLabels.getValue(settings.uiTheme),
                            onSelected = { label ->
                                ReaderInterfaceTheme.entries.firstOrNull { interfaceLabels.getValue(it) == label }?.let {
                                    onSettingsChange(settings.copy(uiTheme = it))
                                }
                            },
                            palette = palette,
                        )
                        readerAppearanceCustomColorRows(settings).forEach { row ->
                            AppearanceDivider(palette)
                            ReaderColorSettingRow(
                                label = stringResource(row.labelRes),
                                color = row.color(settings),
                                onClick = { colorDialogRow = row },
                            )
                        }
                    }
                }
                AppearanceSection(title = stringResource(R.string.reader_appearance_text), palette = palette) {
                    val verticalLabel = stringResource(R.string.reader_appearance_vertical)
                    val horizontalLabel = stringResource(R.string.reader_appearance_horizontal)
                    SegmentedRow(
                        label = stringResource(R.string.reader_appearance_text_orientation),
                        options = listOf(verticalLabel, horizontalLabel),
                        selected = if (settings.verticalWriting) verticalLabel else horizontalLabel,
                        onSelected = { label ->
                            onSettingsChange(settings.copy(verticalWriting = label == verticalLabel))
                        },
                        palette = palette,
                    )
                    AppearanceDivider(palette)
                    ReaderFontRow(
                        label = stringResource(R.string.reader_appearance_font),
                        selectedLabel = selectedFontDisplayLabel,
                        menuExpanded = fontMenuExpanded,
                        onMenuExpandedChange = { fontMenuExpanded = it },
                        enabled = fontUiState.download == null,
                        download = fontUiState.download.takeIf {
                            fontDownloadOrigin == ReaderFontDownloadOrigin.FAMILY
                        },
                        downloadLabel = downloadingFamily?.let {
                            readerFontLabel(it.id, it.displayName)
                        },
                        onCancelDownload = {
                            fontDownloadOrigin = null
                            viewModel.cancelDownload()
                        },
                        canDeleteFont = fontManager.fontFamilies().any {
                            it.id == selectedFontSpec.familyId && it.source == ReaderFontSource.USER
                        },
                        onDeleteFont = {
                            fontToDelete = fontManager.fontFamilies().firstOrNull {
                                it.id == selectedFontSpec.familyId && it.source == ReaderFontSource.USER
                            }
                        },
                    ) {
                        ReaderFontFamilyDropdownContent(
                            entries = fontPickerEntries,
                            activeFamilyId = selectedFontSpec.familyId,
                            rememberedVariants = settings.fontVariantSelections,
                            onFamilySelected = { family ->
                                fontMenuExpanded = false
                                val variant = family.preferredVariant(settings.fontVariantSelections[family.id])
                                fontDownloadOrigin = ReaderFontDownloadOrigin.FAMILY
                                viewModel.selectVariant(family.id, variant.id)
                            },
                        )
                    }
                    AppearanceDivider(palette)
                    ReaderFontRow(
                        label = stringResource(R.string.reader_appearance_font_variant),
                        selectedLabel = if (selectedFontSpec.publisherFont) {
                            stringResource(R.string.reader_appearance_font_variant_publisher)
                        } else {
                            readerFontVariantLabel(selectedFontVariant, selectedFontSpec.weight)
                        },
                        menuExpanded = fontVariantMenuExpanded,
                        onMenuExpandedChange = { fontVariantMenuExpanded = it },
                        enabled = !selectedFontSpec.publisherFont && selectedFontFamily != null,
                        download = fontUiState.download.takeIf {
                            fontDownloadOrigin == ReaderFontDownloadOrigin.VARIANT
                        },
                        downloadLabel = downloadingVariant?.let {
                            readerFontVariantLabel(it, it.weight)
                        },
                        onCancelDownload = {
                            fontDownloadOrigin = null
                            viewModel.cancelDownload()
                        },
                        canDeleteFont = false,
                        onDeleteFont = {},
                    ) {
                        selectedFontFamily?.let { family ->
                            ReaderFontVariantDropdownContent(
                                family = family,
                                activeVariantId = selectedFontSpec.variantId,
                                onVariantSelected = { variant ->
                                    fontVariantMenuExpanded = false
                                    fontDownloadOrigin = ReaderFontDownloadOrigin.VARIANT
                                    viewModel.selectVariant(family.id, variant.id)
                                },
                            )
                        }
                    }
                    AppearanceDivider(palette)
                    ActionRow(
                        label = stringResource(R.string.reader_appearance_import_font),
                        button = if (fontUiState.isImporting) {
                            stringResource(R.string.reader_appearance_importing)
                        } else {
                            stringResource(R.string.action_import)
                        },
                        enabled = !fontUiState.isImporting,
                        onClick = { fontImporter.launch(ImportFileType.ReaderFont.mimeTypes) },
                    )
                    AppearanceDivider(palette)
                    StepperRow(
                        label = stringResource(R.string.reader_appearance_font_size),
                        value = settings.fontSize.toString(),
                        onDecrease = { onSettingsChange(settings.copy(fontSize = (settings.fontSize - 1).coerceAtLeast(10))) },
                        onIncrease = { onSettingsChange(settings.copy(fontSize = (settings.fontSize + 1).coerceAtMost(60))) },
                        palette = palette,
                    )
                    AppearanceDivider(palette)
                    SwitchRow(
                        label = stringResource(R.string.reader_appearance_hide_furigana),
                        checked = settings.hideFurigana,
                        onCheckedChange = { onSettingsChange(settings.copy(hideFurigana = it)) },
                    )
                }
                AppearanceSection(title = stringResource(R.string.reader_appearance_layout), palette = palette) {
                    val paginatedLabel = stringResource(R.string.reader_appearance_paginated)
                    val continuousLabel = stringResource(R.string.reader_appearance_continuous)
                    val visualNovelLabel = stringResource(R.string.reader_appearance_visual_novel)
                    SegmentedRow(
                        label = stringResource(R.string.reader_appearance_mode),
                        options = listOf(paginatedLabel, continuousLabel, visualNovelLabel),
                        selected = when (settings.viewMode) {
                            ReaderViewMode.Paginated -> paginatedLabel
                            ReaderViewMode.Continuous -> continuousLabel
                            ReaderViewMode.VisualNovel -> visualNovelLabel
                        },
                        onSelected = { label ->
                            onSettingsChange(
                                settings.copy(
                                    viewMode = when (label) {
                                        continuousLabel -> ReaderViewMode.Continuous
                                        visualNovelLabel -> ReaderViewMode.VisualNovel
                                        else -> ReaderViewMode.Paginated
                                    },
                                ),
                            )
                        },
                        palette = palette,
                    )
                    if (settings.viewMode == ReaderViewMode.Continuous) {
                        AppearanceDivider(palette)
                        SliderRow(
                            label = stringResource(R.string.reader_appearance_chapter_swipe_distance),
                            value = settings.chapterSwipeDistance.toString(),
                            sliderValue = settings.chapterSwipeDistance.toFloat(),
                            valueRange = 10f..60f,
                            steps = 9,
                            onValueChange = { value ->
                                onSettingsChange(settings.copy(chapterSwipeDistance = (round(value / 5) * 5).toInt()))
                            },
                        )
                    } else if (readerAppearanceShowsPageSwipeThreshold(settings.viewMode)) {
                        AppearanceDivider(palette)
                        SliderRow(
                            label = stringResource(R.string.reader_appearance_page_swipe_threshold),
                            value = if (settings.pageSwipeThresholdPx == 0) {
                                stringResource(R.string.reader_appearance_page_swipe_disabled)
                            } else {
                                stringResource(
                                    R.string.reader_appearance_page_swipe_threshold_value,
                                    settings.pageSwipeThresholdPx,
                                )
                            },
                            sliderValue = settings.pageSwipeThresholdPx.toFloat(),
                            valueRange = ReaderPageSwipeThresholdMinPx.toFloat()..
                                ReaderPageSwipeThresholdMaxPx.toFloat(),
                            steps = readerAppearancePageSwipeThresholdSliderSteps(),
                            onValueChange = { value ->
                                onSettingsChange(
                                    settings.copy(
                                        pageSwipeThresholdPx = readerAppearancePageSwipeThresholdFromSlider(value),
                                    ),
                                )
                            },
                        )
                    }
                    if (settings.viewMode == ReaderViewMode.VisualNovel) {
                        AppearanceDivider(palette)
                        SliderRow(
                            label = stringResource(R.string.reader_visual_novel_reveal_speed),
                            value = if (settings.visualNovelRevealSpeed == 0) {
                                stringResource(R.string.reader_visual_novel_reveal_speed_instant)
                            } else {
                                settings.visualNovelRevealSpeed.toString()
                            },
                            sliderValue = settings.visualNovelRevealSpeed.toFloat(),
                            valueRange = 0f..120f,
                            steps = 23,
                            onValueChange = { value ->
                                onSettingsChange(settings.copy(visualNovelRevealSpeed = (round(value / 5) * 5).toInt()))
                            },
                        )
                        AppearanceDivider(palette)
                        val blockLabel = stringResource(R.string.reader_visual_novel_screen_mode_block)
                        val sentencesLabel = stringResource(R.string.reader_visual_novel_screen_mode_sentences)
                        SegmentedRow(
                            label = stringResource(R.string.reader_visual_novel_screen_mode),
                            options = listOf(blockLabel, sentencesLabel),
                            selected = when (settings.visualNovelScreenMode) {
                                VisualNovelScreenMode.Block -> blockLabel
                                VisualNovelScreenMode.Sentences -> sentencesLabel
                            },
                            onSelected = { label ->
                                onSettingsChange(
                                    settings.copy(
                                        visualNovelScreenMode = if (label == sentencesLabel) {
                                            VisualNovelScreenMode.Sentences
                                        } else {
                                            VisualNovelScreenMode.Block
                                        },
                                    ),
                                )
                            },
                            palette = palette,
                        )
                        if (settings.visualNovelScreenMode == VisualNovelScreenMode.Sentences) {
                            AppearanceDivider(palette)
                            StepperRow(
                                label = stringResource(R.string.reader_visual_novel_sentences_per_screen),
                                value = settings.visualNovelSentencesPerScreen.toString(),
                                onDecrease = {
                                    onSettingsChange(
                                        settings.copy(
                                            visualNovelSentencesPerScreen = (settings.visualNovelSentencesPerScreen - 1).coerceAtLeast(1),
                                        ),
                                    )
                                },
                                onIncrease = {
                                    onSettingsChange(
                                        settings.copy(
                                            visualNovelSentencesPerScreen = (settings.visualNovelSentencesPerScreen + 1).coerceAtMost(12),
                                        ),
                                    )
                                },
                                palette = palette,
                            )
                            AppearanceDivider(palette)
                            SwitchRow(
                                label = stringResource(R.string.reader_visual_novel_preserve_dialogue),
                                checked = settings.visualNovelPreserveDialogueBubbles,
                                onCheckedChange = { onSettingsChange(settings.copy(visualNovelPreserveDialogueBubbles = it)) },
                            )
                        }
                        AppearanceDivider(palette)
                        SwitchRow(
                            label = stringResource(R.string.reader_visual_novel_click_advance),
                            checked = settings.visualNovelClickAdvance,
                            onCheckedChange = { onSettingsChange(settings.copy(visualNovelClickAdvance = it)) },
                        )
                        AppearanceDivider(palette)
                        SwitchRow(
                            label = stringResource(R.string.reader_visual_novel_merge_cross_screen_sasayaki_cues),
                            checked = settings.visualNovelMergeCrossScreenSasayakiCues,
                            onCheckedChange = {
                                onSettingsChange(settings.copy(visualNovelMergeCrossScreenSasayakiCues = it))
                            },
                        )
                    }
                    AppearanceDivider(palette)
                    StepperRow(
                        label = stringResource(R.string.reader_appearance_horizontal_padding),
                        value = "${settings.horizontalPadding}%",
                        onDecrease = { onSettingsChange(settings.copy(horizontalPadding = (settings.horizontalPadding - 1).coerceAtLeast(0))) },
                        onIncrease = { onSettingsChange(settings.copy(horizontalPadding = (settings.horizontalPadding + 1).coerceAtMost(50))) },
                        palette = palette,
                    )
                    AppearanceDivider(palette)
                    StepperRow(
                        label = stringResource(R.string.reader_appearance_vertical_padding),
                        value = "${settings.verticalPadding}%",
                        onDecrease = { onSettingsChange(settings.copy(verticalPadding = (settings.verticalPadding - 1).coerceAtLeast(0))) },
                        onIncrease = { onSettingsChange(settings.copy(verticalPadding = (settings.verticalPadding + 1).coerceAtMost(50))) },
                        palette = palette,
                    )
                    AppearanceDivider(palette)
                    SliderRow(
                        label = stringResource(R.string.reader_appearance_top_safe_area),
                        value = "${settings.topSafeAreaDp.coerceReaderTopSafeAreaDp()}dp",
                        sliderValue = settings.topSafeAreaDp.coerceReaderTopSafeAreaDp().toFloat(),
                        valueRange = ReaderTopSafeAreaMinDp.toFloat()..ReaderTopSafeAreaMaxDp.toFloat(),
                        steps = readerAppearanceTopSafeAreaSliderSteps(),
                        onValueChange = { value ->
                            onSettingsChange(
                                settings.copy(topSafeAreaDp = readerAppearanceTopSafeAreaFromSlider(value)),
                            )
                        },
                    )
                    AppearanceDivider(palette)
                    SliderRow(
                        label = stringResource(R.string.reader_appearance_bottom_safe_area),
                        value = "${settings.bottomSafeAreaDp.coerceReaderBottomSafeAreaDp()}dp",
                        sliderValue = settings.bottomSafeAreaDp.coerceReaderBottomSafeAreaDp().toFloat(),
                        valueRange = ReaderBottomSafeAreaMinDp.toFloat()..ReaderBottomSafeAreaMaxDp.toFloat(),
                        steps = readerAppearanceBottomSafeAreaSliderSteps(),
                        onValueChange = { value ->
                            onSettingsChange(
                                settings.copy(bottomSafeAreaDp = readerAppearanceBottomSafeAreaFromSlider(value)),
                            )
                        },
                    )
                    if (settings.viewMode != ReaderViewMode.VisualNovel) {
                        AppearanceDivider(palette)
                        SwitchRow(
                            label = stringResource(R.string.reader_appearance_avoid_page_break),
                            checked = settings.avoidPageBreak,
                            onCheckedChange = { onSettingsChange(settings.copy(avoidPageBreak = it)) },
                        )
                    }
                    AppearanceDivider(palette)
                    SwitchRow(
                        label = stringResource(R.string.reader_appearance_justify_text),
                        checked = settings.justifyText,
                        onCheckedChange = { onSettingsChange(settings.copy(justifyText = it)) },
                    )
                    AppearanceDivider(palette)
                    SwitchRow(
                        label = stringResource(R.string.reader_appearance_blur_images),
                        checked = settings.blurImages,
                        onCheckedChange = { onSettingsChange(settings.copy(blurImages = it)) },
                    )
                    AppearanceDivider(palette)
                    SwitchRow(
                        label = stringResource(R.string.settings_advanced),
                        checked = settings.layoutAdvanced,
                        onCheckedChange = { onSettingsChange(settings.copy(layoutAdvanced = it)) },
                    )
                    if (settings.layoutAdvanced) {
                        AppearanceDivider(palette)
                        SliderRow(
                            label = stringResource(R.string.reader_appearance_line_height),
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
                            label = stringResource(R.string.reader_appearance_character_spacing),
                            value = "${settings.characterSpacing.toInt()}%",
                            sliderValue = settings.characterSpacing.toFloat(),
                            valueRange = -10f..10f,
                            steps = 19,
                            onValueChange = { value ->
                                onSettingsChange(settings.copy(characterSpacing = round(value).toDouble()))
                            },
                        )
                        AppearanceDivider(palette)
                        SliderRow(
                            label = stringResource(R.string.reader_appearance_paragraph_spacing),
                            value = String.format(Locale.US, "%.1fem", settings.paragraphSpacing),
                            sliderValue = settings.paragraphSpacing.toFloat(),
                            valueRange = 0f..3f,
                            steps = 29,
                            onValueChange = { value ->
                                onSettingsChange(settings.copy(paragraphSpacing = round(value * 10) / 10.0))
                            },
                        )
                    }
                }
                AppearanceSection(title = stringResource(R.string.reader_appearance_progress), palette = palette) {
                    SwitchRow(
                        label = stringResource(R.string.reader_appearance_show_progress),
                        checked = settings.showProgress,
                        onCheckedChange = { onSettingsChange(settings.copy(showProgress = it)) },
                    )
                    AppearanceDivider(palette)
                    SwitchRow(
                        label = stringResource(R.string.reader_appearance_show_chapter_progress),
                        checked = settings.showChapterProgress,
                        onCheckedChange = { onSettingsChange(settings.copy(showChapterProgress = it)) },
                    )
                    if (readerAppearanceShowsAlwaysShowProgress(settings)) {
                        AppearanceDivider(palette)
                        SwitchRow(
                            label = stringResource(
                                if (progressDisplay.usesWords) {
                                    R.string.reader_appearance_show_word_count
                                } else {
                                    R.string.reader_appearance_show_character_count
                                },
                            ),
                            checked = settings.showCharacters,
                            onCheckedChange = { onSettingsChange(settings.copy(showCharacters = it)) },
                        )
                        AppearanceDivider(palette)
                        SwitchRow(
                            label = stringResource(R.string.reader_appearance_show_percentage),
                            checked = settings.showPercentage,
                            onCheckedChange = { onSettingsChange(settings.copy(showPercentage = it)) },
                        )
                        AppearanceDivider(palette)
                        SwitchRow(
                            label = stringResource(R.string.reader_appearance_always_show_progress),
                            checked = settings.alwaysShowProgress,
                            onCheckedChange = { onSettingsChange(settings.copy(alwaysShowProgress = it)) },
                        )
                    }
                    if (readerAppearanceShowsProgressPosition(settings)) {
                        AppearanceDivider(palette)
                        val topLabel = stringResource(R.string.reader_appearance_progress_top)
                        val bottomLabel = stringResource(R.string.reader_appearance_progress_bottom)
                        SegmentedRow(
                            label = stringResource(R.string.reader_appearance_progress_position),
                            options = listOf(topLabel, bottomLabel),
                            selected = if (settings.showProgressTop) topLabel else bottomLabel,
                            onSelected = { label -> onSettingsChange(settings.copy(showProgressTop = label == topLabel)) },
                            palette = palette,
                        )
                    }
                }
                AppearanceSection(title = stringResource(R.string.reader_appearance_display), palette = palette) {
                    SwitchRow(
                        label = stringResource(R.string.reader_appearance_show_title),
                        checked = settings.showTitle,
                        onCheckedChange = { onSettingsChange(settings.copy(showTitle = it)) },
                    )
                    AppearanceDivider(palette)
                    SwitchRow(
                        label = stringResource(R.string.reader_appearance_show_back_button),
                        checked = settings.showReaderBackButton,
                        onCheckedChange = { onSettingsChange(settings.copy(showReaderBackButton = it)) },
                    )
                    readerAppearanceStatisticsRows(settings).forEach { row ->
                        AppearanceDivider(palette)
                        SwitchRow(
                            label = stringResource(row.labelRes),
                            checked = row.checked(settings),
                            onCheckedChange = { checked ->
                                onSettingsChange(row.updated(settings, checked))
                            },
                        )
                    }
                    readerAppearanceSasayakiRows(sasayakiSettings).forEach { labelRes ->
                        AppearanceDivider(palette)
                        SwitchRow(
                            label = stringResource(labelRes),
                            checked = sasayakiSettings.showReaderToggle,
                            onCheckedChange = {
                                onSasayakiSettingsChange(sasayakiSettings.copy(showReaderToggle = it))
                            },
                        )
                    }
                }
                AppearanceSection(title = stringResource(R.string.reader_appearance_popup), palette = palette) {
                    SliderRow(
                        label = stringResource(R.string.reader_appearance_width),
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
                        label = stringResource(R.string.reader_appearance_height),
                        value = settings.popupHeight.toString(),
                        sliderValue = settings.popupHeight.toFloat(),
                        valueRange = 100f..1000f,
                        steps = 89,
                        onValueChange = { value ->
                            onSettingsChange(settings.copy(popupHeight = (round(value / 10) * 10).toInt()))
                        },
                    )
                    AppearanceDivider(palette)
                    SliderRow(
                        label = stringResource(R.string.reader_appearance_scale),
                        value = String.format(Locale.US, "%.2f", settings.popupScale),
                        sliderValue = settings.popupScale.toFloat(),
                        valueRange = ReaderPopupScaleMin.toFloat()..ReaderPopupScaleMax.toFloat(),
                        steps = ReaderPopupScaleSliderSteps,
                        onValueChange = { value ->
                            val step = ReaderPopupScaleStep.toFloat()
                            val roundedScale = (round(value / step) * step).toDouble()
                            onSettingsChange(settings.copy(popupScale = roundedScale))
                        },
                    )
                    AppearanceDivider(palette)
                    SwitchRow(
                        label = stringResource(R.string.reader_appearance_reduced_motion_scrolling),
                        checked = settings.popupReducedMotionScrolling,
                        onCheckedChange = { onSettingsChange(settings.copy(popupReducedMotionScrolling = it)) },
                    )
                    if (settings.popupReducedMotionScrolling) {
                        AppearanceDivider(palette)
                        SliderRow(
                            label = stringResource(R.string.reader_appearance_scroll_amount),
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
                            label = stringResource(R.string.reader_appearance_scroll_swipe_threshold),
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
                        label = stringResource(R.string.reader_appearance_show_action_bar),
                        checked = settings.popupActionBar,
                        onCheckedChange = { onSettingsChange(settings.copy(popupActionBar = it)) },
                    )
                    AppearanceDivider(palette)
                    SwitchRow(
                        label = stringResource(R.string.reader_appearance_full_width),
                        checked = settings.popupFullWidth,
                        onCheckedChange = { onSettingsChange(settings.copy(popupFullWidth = it)) },
                    )
                    AppearanceDivider(palette)
                    SwitchRow(
                        label = stringResource(R.string.reader_appearance_swipe_to_dismiss),
                        checked = settings.popupSwipeToDismiss,
                        onCheckedChange = { onSettingsChange(settings.copy(popupSwipeToDismiss = it)) },
                    )
                    if (settings.popupSwipeToDismiss) {
                        AppearanceDivider(palette)
                        SliderRow(
                            label = stringResource(R.string.reader_appearance_swipe_threshold),
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
                        Text(stringResource(R.string.action_done))
                    }
                }
            }
            if (fontUiState.isImporting) {
                HoshiBlockingProgressOverlay(
                    message = stringResource(R.string.reader_appearance_importing),
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }

    fontToDelete?.let { family ->
        AlertDialog(
            onDismissRequest = { fontToDelete = null },
            title = { Text(stringResource(R.string.reader_appearance_delete_font_title_format, family.displayName)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.deleteFamily(family.id)
                        if (selectedFontSpec.familyId == family.id) {
                            onSettingsChange(settings.withDefaultFont())
                        }
                        fontToDelete = null
                    },
                ) {
                    Text(stringResource(R.string.action_delete))
                }
            },
            dismissButton = {
                TextButton(onClick = { fontToDelete = null }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }
    if (fontUiState.error != null) {
        val failedSelection = fontUiState.failedSelection
        AlertDialog(
            onDismissRequest = {
                fontDownloadOrigin = null
                viewModel.clearError()
            },
            title = { Text(stringResource(R.string.reader_appearance_font)) },
            text = { Text(requireNotNull(fontUiState.error).asString()) },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.clearError()
                        if (failedSelection == null) {
                            fontDownloadOrigin = null
                        } else {
                            viewModel.selectVariant(failedSelection.familyId, failedSelection.variantId)
                        }
                    },
                ) {
                    Text(
                        stringResource(
                            if (failedSelection == null) R.string.action_close
                            else R.string.reader_appearance_font_retry,
                        ),
                    )
                }
            },
            dismissButton = if (failedSelection == null) null else {
                {
                    TextButton(
                        onClick = {
                            fontDownloadOrigin = null
                            viewModel.clearError()
                        },
                    ) {
                        Text(stringResource(R.string.action_close))
                    }
                }
            },
        )
    }
    colorDialogRow?.let { row ->
        ReaderColorPickerDialog(
            title = stringResource(row.labelRes),
            initialColor = row.color(settings),
            defaultColor = row.defaultColor,
            onColorChange = { color ->
                onSettingsChange(row.updated(settings, color))
                colorDialogRow = null
            },
            onDismiss = { colorDialogRow = null },
            previewBorderColor = palette.divider,
            cursorColor = palette.onGroup,
        )
    }
}

internal fun readerAppearanceSasayakiRows(settings: SasayakiSettings): List<Int> =
    if (settings.enabled) listOf(R.string.reader_appearance_show_sasayaki_toggle) else emptyList()

internal fun readerAppearanceFontOptions(importedFontNames: List<String>, selectedFont: String): List<String> =
    (listOf(ReaderFontManager.publisherFont) + ReaderFontManager.defaultFonts + importedFontNames + selectedFont)
        .filter { it.isNotBlank() }
        .distinct()

internal fun readerAppearanceShowsCustomInterfaceTheme(settings: ReaderSettings): Boolean =
    settings.theme == ReaderTheme.Custom

internal fun readerAppearanceCustomColorRows(settings: ReaderSettings): List<ReaderAppearanceCustomColorRow> =
    if (settings.theme == ReaderTheme.Custom) {
        ReaderAppearanceCustomColorRow.entries
    } else {
        emptyList()
    }

internal fun readerAppearanceShowsAlwaysShowProgress(settings: ReaderSettings): Boolean =
    settings.showProgress || settings.showChapterProgress

internal fun readerAppearanceShowsProgressPosition(settings: ReaderSettings): Boolean =
    readerAppearanceShowsAlwaysShowProgress(settings) && !settings.alwaysShowProgress

internal fun readerAppearanceTopSafeAreaSliderSteps(): Int =
    ((ReaderTopSafeAreaMaxDp - ReaderTopSafeAreaMinDp) / ReaderTopSafeAreaStepDp) - 1

internal fun readerAppearanceTopSafeAreaFromSlider(value: Float): Int =
    (round(value / ReaderTopSafeAreaStepDp) * ReaderTopSafeAreaStepDp)
        .toInt()
        .coerceReaderTopSafeAreaDp()

internal fun readerAppearanceBottomSafeAreaSliderSteps(): Int =
    ((ReaderBottomSafeAreaMaxDp - ReaderBottomSafeAreaMinDp) / ReaderBottomSafeAreaStepDp) - 1

internal fun readerAppearanceBottomSafeAreaFromSlider(value: Float): Int =
    (round(value / ReaderBottomSafeAreaStepDp) * ReaderBottomSafeAreaStepDp)
        .toInt()
        .coerceReaderBottomSafeAreaDp()

internal fun readerAppearanceShowsPageSwipeThreshold(viewMode: ReaderViewMode): Boolean =
    viewMode == ReaderViewMode.Paginated || viewMode == ReaderViewMode.VisualNovel

internal fun readerAppearancePageSwipeThresholdSliderSteps(): Int =
    (ReaderPageSwipeThresholdMaxPx - ReaderPageSwipeThresholdMinPx) /
        ReaderPageSwipeThresholdStepPx - 1

internal fun readerAppearancePageSwipeThresholdFromSlider(value: Float): Int =
    (round(value / ReaderPageSwipeThresholdStepPx) * ReaderPageSwipeThresholdStepPx)
        .toInt()
        .coerceReaderPageSwipeThresholdPx()

internal fun readerAppearanceStatisticsRows(settings: ReaderSettings): List<ReaderAppearanceStatisticsRow> =
    if (settings.enableStatistics) {
        ReaderAppearanceStatisticsRow.entries
    } else {
        emptyList()
    }

internal enum class ReaderAppearanceStatisticsRow(@get:StringRes val labelRes: Int) {
    Toggle(R.string.reader_appearance_show_statistics_toggle),
    ReadingSpeed(R.string.reader_appearance_show_reading_speed),
    ReadingTime(R.string.reader_appearance_show_reading_time);

    fun checked(settings: ReaderSettings): Boolean =
        when (this) {
            Toggle -> settings.showStatisticsToggle
            ReadingSpeed -> settings.showReadingSpeed
            ReadingTime -> settings.showReadingTime
        }

    fun updated(settings: ReaderSettings, checked: Boolean): ReaderSettings =
        when (this) {
            Toggle -> settings.copy(showStatisticsToggle = checked)
            ReadingSpeed -> settings.copy(showReadingSpeed = checked)
            ReadingTime -> settings.copy(showReadingTime = checked)
        }
}

internal enum class ReaderAppearanceCustomColorRow(@get:StringRes val labelRes: Int, val defaultColor: Long) {
    Background(R.string.reader_appearance_background_color, 0xFFFFFFFF),
    Text(R.string.reader_appearance_text_color, 0xFF000000),
    Info(R.string.reader_appearance_info_color, 0xFF999999);

    fun color(settings: ReaderSettings): Long =
        when (this) {
            Background -> settings.customBackgroundColor
            Text -> settings.customTextColor
            Info -> settings.customInfoColor
        }

    fun updated(settings: ReaderSettings, color: Long): ReaderSettings =
        when (this) {
            Background -> settings.copy(customBackgroundColor = color)
            Text -> settings.copy(customTextColor = color)
            Info -> settings.copy(customInfoColor = color)
        }
}

@get:StringRes
private val ReaderTheme.labelRes: Int
    get() = when (this) {
        ReaderTheme.System -> R.string.reader_appearance_theme_system
        ReaderTheme.Light -> R.string.reader_appearance_theme_light
        ReaderTheme.Dark -> R.string.reader_appearance_theme_dark
        ReaderTheme.Sepia -> R.string.reader_appearance_theme_sepia
        ReaderTheme.Custom -> R.string.reader_appearance_theme_custom
    }

@get:StringRes
private val ReaderInterfaceTheme.labelRes: Int
    get() = when (this) {
        ReaderInterfaceTheme.System -> R.string.reader_appearance_theme_system
        ReaderInterfaceTheme.Light -> R.string.reader_appearance_theme_light
        ReaderInterfaceTheme.Dark -> R.string.reader_appearance_theme_dark
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
        options.any { it.length >= 8 } -> 180
        options.any { it.length >= 6 } -> 120
        else -> 100
    }

@Composable
private fun ReaderFontRow(
    label: String,
    selectedLabel: String,
    menuExpanded: Boolean,
    onMenuExpandedChange: (Boolean) -> Unit,
    enabled: Boolean = true,
    download: ReaderFontDownloadUiState?,
    downloadLabel: String?,
    onCancelDownload: () -> Unit,
    canDeleteFont: Boolean,
    onDeleteFont: () -> Unit,
    menuContent: @Composable ColumnScope.() -> Unit,
) {
    val metrics = readerSheetDensityMetrics()
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = metrics.appearanceFontRowVerticalPaddingDp.dp),
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
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box {
                    CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides 0.dp) {
                        TextButton(
                            onClick = { onMenuExpandedChange(true) },
                            enabled = enabled,
                        ) {
                            Text(
                                text = selectedLabel,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                    DropdownMenu(
                        expanded = menuExpanded,
                        onDismissRequest = { onMenuExpandedChange(false) },
                        modifier = Modifier
                            .widthIn(min = 280.dp)
                            .heightIn(max = 480.dp),
                        content = menuContent,
                    )
                }
                if (canDeleteFont) {
                    CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides 0.dp) {
                        TextButton(onClick = onDeleteFont) {
                            Text(stringResource(R.string.action_delete))
                        }
                    }
                }
            }
        }
        if (download != null && downloadLabel != null) {
            ReaderFontDownloadStatus(
                download = download,
                label = downloadLabel,
                onCancelDownload = onCancelDownload,
            )
        }
    }
}

private enum class ReaderFontDownloadOrigin {
    FAMILY,
    VARIANT,
}

@Composable
private fun ReaderFontFamilyDropdownContent(
    entries: List<ReaderFontPickerEntry>,
    activeFamilyId: String,
    rememberedVariants: Map<String, String>,
    onFamilySelected: (ReaderFontFamily) -> Unit,
) {
    entries.forEach { entry ->
        when (entry) {
            is ReaderFontPickerEntry.Family -> {
                val family = entry.family
                ReaderFontDropdownItem(
                    label = readerFontLabel(family.id, family.displayName),
                    selected = family.id == activeFamilyId,
                    downloadable = !family.preferredVariant(rememberedVariants[family.id]).isInstalled,
                    onClick = { onFamilySelected(family) },
                )
            }
            is ReaderFontPickerEntry.Header -> ReaderFontDropdownHeader(
                stringResource(readerFontCategoryStringResource(entry.category)),
            )
            ReaderFontPickerEntry.Divider -> HorizontalDivider(
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            )
        }
    }
}

@Composable
private fun ReaderFontVariantDropdownContent(
    family: ReaderFontFamily,
    activeVariantId: String,
    onVariantSelected: (ReaderFontVariant) -> Unit,
) {
    val context = LocalContext.current
    family.variants.forEach { variant ->
        ReaderFontDropdownItem(
            label = readerFontVariantLabel(variant, variant.weight),
            selected = variant.id == activeVariantId,
            downloadable = !variant.isInstalled,
            supportingLabel = if (!variant.isInstalled) {
                formatFontBytes(
                    context = context,
                    bytes = requireNotNull(variant.remoteFile).expectedSize,
                )
            } else {
                null
            },
            onClick = { onVariantSelected(variant) },
        )
    }
}

@Composable
private fun ReaderFontDropdownHeader(
    title: String,
) {
    Text(
        text = title,
        style = MaterialTheme.typography.labelLarge,
        fontWeight = FontWeight.SemiBold,
        color = LocalContentColor.current,
        modifier = Modifier.padding(
            start = 12.dp,
            top = 8.dp,
            end = 12.dp,
            bottom = 0.dp,
        ),
    )
}

@Composable
private fun ReaderFontDropdownItem(
    label: String,
    selected: Boolean,
    downloadable: Boolean,
    onClick: () -> Unit,
    supportingLabel: String? = null,
) {
    DropdownMenuItem(
        text = {
            Column {
                Text(
                    text = label,
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                )
                supportingLabel?.let { size ->
                    Text(
                        text = stringResource(R.string.reader_appearance_font_download_size_format, size),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        },
        trailingIcon = {
            when {
                selected -> Icon(Icons.Rounded.Check, contentDescription = null)
                downloadable -> Icon(
                    Icons.Rounded.Download,
                    contentDescription = stringResource(R.string.action_download),
                )
            }
        },
        onClick = onClick,
    )
}

@Composable
private fun ReaderFontDownloadStatus(
    download: ReaderFontDownloadUiState,
    onCancelDownload: () -> Unit,
    label: String,
) {
    val progress = download.progress
    val fraction = if (progress.totalBytes > 0) {
        progress.downloadedBytes.toFloat() / progress.totalBytes.toFloat()
    } else {
        0f
    }
    LinearProgressIndicator(
        progress = { fraction.coerceIn(0f, 1f) },
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp),
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(
                R.string.reader_appearance_font_downloading_item_format,
                label,
                (fraction * 100).toInt(),
            ),
            style = MaterialTheme.typography.bodySmall,
        )
        TextButton(onClick = onCancelDownload) {
            Text(stringResource(R.string.action_cancel))
        }
    }
}

private fun formatFontBytes(context: android.content.Context, bytes: Long): String =
    Formatter.formatShortFileSize(context, bytes)

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
private fun readerFontLabel(familyId: String, fontName: String): String = when (familyId) {
    ReaderFontManager.publisherFamilyId -> stringResource(R.string.reader_appearance_font_publisher)
    ReaderFontManager.systemMinchoFamilyId -> stringResource(R.string.reader_appearance_font_system_serif)
    ReaderFontManager.systemGothicFamilyId -> stringResource(R.string.reader_appearance_font_system_sans_serif)
    else -> fontName
}

@Composable
private fun readerFontVariantLabel(variant: ReaderFontVariant?, fallbackWeight: Int): String {
    val name = variant?.displayName ?: ReaderRecommendedFontCatalog.weightName(fallbackWeight)
    val weight = variant?.weight ?: fallbackWeight
    return stringResource(R.string.reader_appearance_font_variant_format, name, weight)
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
                            contentDescription = stringResource(R.string.action_decrease),
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
                            contentDescription = stringResource(R.string.action_increase),
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
