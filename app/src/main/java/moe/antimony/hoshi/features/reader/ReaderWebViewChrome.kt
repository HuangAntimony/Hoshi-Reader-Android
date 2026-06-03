package moe.antimony.hoshi.features.reader

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.List
import androidx.compose.material.icons.automirrored.rounded.ShowChart
import androidx.compose.material.icons.rounded.BorderColor
import androidx.compose.material.icons.rounded.FastForward
import androidx.compose.material.icons.rounded.FastRewind
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Timer
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.dropShadow
import androidx.compose.ui.draw.innerShadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.shadow.Shadow
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import moe.antimony.hoshi.R

@Composable
internal fun ReaderTopInfo(
    state: ReaderChromeState,
    settings: ReaderSettings,
    colors: ReaderChromeColors,
    onStatisticsToggle: (() -> Unit)?,
    statisticsTracking: Boolean,
    onJumpBack: (() -> Unit)?,
    onJumpForward: (() -> Unit)?,
    onSasayakiToggle: (() -> Unit)?,
    sasayakiPlaying: Boolean,
    visibility: ReaderChromeVisibility,
    metrics: ReaderBottomChromeMetrics,
    modifier: Modifier = Modifier,
) {
    val progress = state.progressText(settings)
    val showProgressInTopInfo = readerShowsProgressInTopBubble(settings)
    val showBackJump = visibility.showBackJump && state.backTargetCharacter != null && onJumpBack != null
    val showForwardJump = visibility.showForwardJump && state.forwardTargetCharacter != null && onJumpForward != null
    val showStatisticsToggle = visibility.showStatisticsToggle && onStatisticsToggle != null
    val showSasayakiToggle = visibility.showSasayakiToggle && onSasayakiToggle != null
    if ((!visibility.showTitleAndProgress || !settings.showTitle) &&
        !showStatisticsToggle &&
        !showBackJump &&
        !showForwardJump &&
        !showSasayakiToggle &&
        (!visibility.showTitleAndProgress || progress.isBlank() || !showProgressInTopInfo)
    ) return
    Box(modifier = modifier.fillMaxWidth()) {
        val showStartControls = showStatisticsToggle || showBackJump
        val showEndControls = showSasayakiToggle || showForwardJump
        var startControlWidth by remember { mutableStateOf(0) }
        var endControlWidth by remember { mutableStateOf(0) }
        val density = LocalDensity.current
        val dynamicTitlePadding = with(density) {
            val maxControlWidth = maxOf(
                if (showStartControls) startControlWidth else 0,
                if (showEndControls) endControlWidth else 0,
            )
            if (maxControlWidth > 0) maxControlWidth.toDp() + 4.dp else 0.dp
        }
        val titlePadding = readerTopTitlePaddingDp(
            hasStartControl = showStartControls,
            hasEndControl = showEndControls,
        )
        val resolvedTitlePadding = maxOf(
            titlePadding.startDp.dp,
            titlePadding.endDp.dp,
            dynamicTitlePadding,
        )
        val showCenterInfo = visibility.showTitleAndProgress &&
            (settings.showTitle || (showProgressInTopInfo && progress.isNotBlank()))
        if (showCenterInfo) {
            val bubbleMetrics = readerInfoBubbleMetrics()
            Surface(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .readerChromeShadow(
                        elevationDp = colors.bubbleShadowElevationDp,
                        shadowColor = Color(colors.bubbleShadowColor),
                        shape = RoundedCornerShape(bubbleMetrics.cornerRadiusDp.dp),
                    )
                    .readerChromeOutline(
                        outlineColor = Color(colors.bubbleOutline),
                        shape = RoundedCornerShape(bubbleMetrics.cornerRadiusDp.dp),
                    )
                    .readerChromeInnerShadow(
                        shadowColor = Color(colors.bubbleInnerShadowColor),
                        shape = RoundedCornerShape(bubbleMetrics.cornerRadiusDp.dp),
                    ),
                shape = RoundedCornerShape(bubbleMetrics.cornerRadiusDp.dp),
                color = Color(colors.menuContainer),
                border = BorderStroke(readerBubbleBorderWidthDp(colors).dp, Color(colors.menuBorder)),
                tonalElevation = 0.dp,
                shadowElevation = 0.dp,
            ) {
                Column(
                    modifier = Modifier.padding(
                        horizontal = bubbleMetrics.horizontalPaddingDp.dp,
                        vertical = bubbleMetrics.verticalPaddingDp.dp,
                    ),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(0.dp),
                ) {
                    if (settings.showTitle) {
                        Text(
                            text = state.title,
                            color = Color(colors.infoText),
                            style = MaterialTheme.typography.labelLarge,
                            maxLines = 1,
                            modifier = Modifier.padding(
                                start = resolvedTitlePadding,
                                end = resolvedTitlePadding,
                            ),
                        )
                    }
                    if (showProgressInTopInfo && progress.isNotBlank()) {
                        Text(
                            text = progress,
                            color = Color(colors.infoText),
                            style = MaterialTheme.typography.labelMedium,
                        )
                    }
                }
            }
        }
        if (showStartControls) {
            Row(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .offset(
                        x = metrics.topButtonHorizontalInsetDp.dp,
                        y = metrics.topButtonOffsetYDp.dp,
                    )
                    .onSizeChanged { startControlWidth = it.width },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                if (showStatisticsToggle) {
                    ReaderRoundButton(
                        colors = colors,
                        sizeDp = metrics.topStatisticsButtonSizeDp,
                        containerColor = Color(readerTopButtonContainerColor()),
                        border = null,
                        shadowElevationDp = 0,
                        onClick = requireNotNull(onStatisticsToggle),
                    ) {
                        Icon(
                            imageVector = readerStatisticsTopToggleIcon(statisticsTracking),
                            contentDescription = if (statisticsTracking) {
                                stringResource(R.string.reader_statistics_pause)
                            } else {
                                stringResource(R.string.reader_statistics_start)
                            },
                            modifier = Modifier.size(metrics.topStatisticsIconSizeDp.dp),
                            tint = Color(colors.buttonContent),
                        )
                    }
                }
                if (showBackJump) {
                    ReaderJumpHistoryButton(
                        character = requireNotNull(state.backTargetCharacter),
                        icon = readerJumpBackIcon(),
                        iconFirst = true,
                        contentDescription = stringResource(R.string.reader_jump_back),
                        colors = colors,
                        heightDp = metrics.topStatisticsButtonSizeDp,
                        onClick = requireNotNull(onJumpBack),
                    )
                }
            }
        }
        if (showEndControls) {
            Row(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .offset(
                        x = (-metrics.topButtonHorizontalInsetDp).dp,
                        y = metrics.topButtonOffsetYDp.dp,
                    )
                    .onSizeChanged { endControlWidth = it.width },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                if (showForwardJump) {
                    ReaderJumpHistoryButton(
                        character = requireNotNull(state.forwardTargetCharacter),
                        icon = readerJumpForwardIcon(),
                        iconFirst = false,
                        contentDescription = stringResource(R.string.reader_jump_forward),
                        colors = colors,
                        heightDp = metrics.topSasayakiButtonSizeDp,
                        onClick = requireNotNull(onJumpForward),
                    )
                }
                if (showSasayakiToggle) {
                    ReaderRoundButton(
                        colors = colors,
                        sizeDp = metrics.topSasayakiButtonSizeDp,
                        containerColor = Color(readerTopButtonContainerColor()),
                        border = null,
                        shadowElevationDp = 0,
                        onClick = requireNotNull(onSasayakiToggle),
                    ) {
                        Icon(
                            imageVector = readerSasayakiTopToggleIcon(sasayakiPlaying),
                            contentDescription = if (sasayakiPlaying) {
                                stringResource(R.string.sasayaki_pause)
                            } else {
                                stringResource(R.string.sasayaki_play)
                            },
                            modifier = Modifier.size(metrics.topSasayakiIconSizeDp.dp),
                            tint = Color(colors.buttonContent),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ReaderJumpHistoryButton(
    character: Int,
    icon: ImageVector,
    iconFirst: Boolean,
    contentDescription: String,
    colors: ReaderChromeColors,
    heightDp: Int,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .height(heightDp.dp)
            .clickable(onClick = onClick)
            .padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        if (iconFirst) {
            Icon(
                imageVector = icon,
                contentDescription = contentDescription,
                modifier = Modifier.size(16.dp),
                tint = Color(colors.infoText),
            )
        }
        Text(
            text = readerJumpTargetText(character),
            color = Color(colors.infoText),
            style = MaterialTheme.typography.labelSmall,
        )
        if (!iconFirst) {
            Icon(
                imageVector = icon,
                contentDescription = contentDescription,
                modifier = Modifier.size(16.dp),
                tint = Color(colors.infoText),
            )
        }
    }
}

internal fun readerStatisticsTopToggleIcon(isTracking: Boolean): ImageVector =
    if (isTracking) Icons.Rounded.Timer else Icons.AutoMirrored.Rounded.ShowChart

internal fun readerSasayakiTopToggleIcon(isPlaying: Boolean): ImageVector =
    if (isPlaying) Icons.Rounded.Pause else Icons.Rounded.GraphicEq

@Composable
internal fun ReaderFocusModeToggleArea(
    metrics: ReaderBottomChromeMetrics,
    focusMode: Boolean,
    onToggleFocusMode: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val toggleArea = readerFocusModeToggleArea(
        metrics = metrics,
        focusMode = focusMode,
    )
    if (!toggleArea.visible) return
    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = toggleArea.horizontalPaddingDp.dp)
            .height((metrics.buttonSizeDp + metrics.bottomPaddingDp + 8).dp)
            .clickable(onClick = onToggleFocusMode),
    )
}

@Composable
internal fun rememberCurrentStatusBarPadding(): Dp {
    val density = LocalDensity.current
    return with(density) { WindowInsets.statusBars.getTop(this).toDp() }
}

@Composable
internal fun rememberStableStatusBarPadding(): Dp {
    val currentTop = rememberCurrentStatusBarPadding()
    var stableTop by remember { mutableStateOf(0.dp) }
    LaunchedEffect(currentTop) {
        if (currentTop > stableTop) {
            stableTop = currentTop
        }
    }
    return if (currentTop > 0.dp) currentTop else stableTop
}

@Composable
internal fun rememberStableNavigationBarPadding(): Dp {
    val density = LocalDensity.current
    val currentBottom = with(density) { WindowInsets.navigationBars.getBottom(this).toDp() }
    var stableBottom by remember { mutableStateOf(0.dp) }
    LaunchedEffect(currentBottom) {
        if (currentBottom > stableBottom) {
            stableBottom = currentBottom
        }
    }
    return if (currentBottom > 0.dp) currentBottom else stableBottom
}

@Composable
internal fun BoxScope.ReaderBottomChrome(
    state: ReaderChromeState,
    settings: ReaderSettings,
    layout: ReaderChromeLayout,
    colors: ReaderChromeColors,
    onClose: () -> Unit,
    onMenu: () -> Unit,
    menuExpanded: Boolean,
    onDismissMenu: () -> Unit,
    onChapters: () -> Unit,
    onHighlights: () -> Unit,
    onAppearance: () -> Unit,
    onStatistics: (() -> Unit)?,
    onSasayaki: (() -> Unit)?,
    metrics: ReaderBottomChromeMetrics,
    modifier: Modifier = Modifier,
) {
    val controlsHeightDp = metrics.buttonSizeDp
    val bottomChromeHeightDp = 8 + controlsHeightDp + metrics.bottomPaddingDp + metrics.bottomSafeAreaDp
    if (menuExpanded) {
        Box(
            modifier = Modifier
                .align(Alignment.Center)
                .fillMaxSize()
                .clickable(onClick = onDismissMenu),
        )
        ReaderMenuCard(
            colors = colors,
            metrics = metrics,
            onChapters = onChapters,
            onHighlights = onHighlights,
            onAppearance = onAppearance,
            onStatistics = onStatistics,
            onSasayaki = onSasayaki,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(end = metrics.horizontalPaddingDp.dp, bottom = metrics.menuBottomOffsetDp.dp),
        )
    }
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(bottomChromeHeightDp.dp)
            .padding(
                start = metrics.horizontalPaddingDp.dp,
                end = metrics.horizontalPaddingDp.dp,
            ),
    ) {
        Box(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 8.dp)
                .height(controlsHeightDp.dp)
                .fillMaxWidth(),
        ) {
            if (layout.bottomCenterLineCount > 0) {
                val bubbleMetrics = readerInfoBubbleMetrics()
                Surface(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .heightIn(max = layout.bottomCenterMaxHeightDp.dp)
                        .readerChromeShadow(
                            elevationDp = colors.bubbleShadowElevationDp,
                            shadowColor = Color(colors.bubbleShadowColor),
                            shape = RoundedCornerShape(bubbleMetrics.cornerRadiusDp.dp),
                        )
                        .readerChromeOutline(
                            outlineColor = Color(colors.bubbleOutline),
                            shape = RoundedCornerShape(bubbleMetrics.cornerRadiusDp.dp),
                        )
                        .readerChromeInnerShadow(
                            shadowColor = Color(colors.bubbleInnerShadowColor),
                            shape = RoundedCornerShape(bubbleMetrics.cornerRadiusDp.dp),
                        ),
                    shape = RoundedCornerShape(bubbleMetrics.cornerRadiusDp.dp),
                    color = Color(colors.menuContainer),
                    border = BorderStroke(readerBubbleBorderWidthDp(colors).dp, Color(colors.menuBorder)),
                    tonalElevation = 0.dp,
                    shadowElevation = 0.dp,
                ) {
                    Column(
                        modifier = Modifier.padding(
                            horizontal = bubbleMetrics.horizontalPaddingDp.dp,
                            vertical = bubbleMetrics.verticalPaddingDp.dp,
                        ),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        if (layout.showStatisticsInBottomBar) {
                            Text(
                                text = state.statisticsText(settings),
                                color = Color(colors.infoText),
                                style = MaterialTheme.typography.labelMedium,
                                maxLines = 1,
                            )
                        }
                        if (layout.showProgressInBottomBar) {
                            Text(
                                text = state.progressText(settings),
                                color = Color(colors.infoText),
                                style = MaterialTheme.typography.labelMedium,
                                maxLines = 1,
                            )
                        }
                    }
                }
            }
            Row(
                modifier = Modifier
                    .align(Alignment.Center)
                    .fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (settings.showReaderBackButton) {
                    ReaderGlassButton(colors = colors, metrics = metrics, onClick = onClose) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                            contentDescription = stringResource(R.string.action_back),
                            modifier = Modifier.size(metrics.primaryIconSizeDp.dp),
                            tint = Color(colors.buttonContent),
                        )
                    }
                }
                Spacer(Modifier.weight(1f))
                ReaderGlassButton(colors = colors, metrics = metrics, onClick = onMenu) {
                    Icon(
                        imageVector = Icons.Rounded.Tune,
                        contentDescription = stringResource(R.string.reader_menu),
                        modifier = Modifier.size(metrics.secondaryIconSizeDp.dp),
                        tint = Color(colors.buttonContent),
                    )
                }
            }
        }
    }
}

@Composable
internal fun ReaderBottomSafeProgress(
    state: ReaderChromeState,
    settings: ReaderSettings,
    colors: ReaderChromeColors,
    metrics: ReaderBottomChromeMetrics,
    focusMode: Boolean,
    sasayakiPlaybackControls: ReaderSasayakiBottomPlaybackControls,
    sasayakiPlaying: Boolean,
    onSasayakiSkipBackward: () -> Unit,
    onSasayakiTogglePlayback: () -> Unit,
    onSasayakiSkipForward: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val progress = readerBottomSafeProgressText(state, settings, focusMode)
    if (progress.isBlank() && !sasayakiPlaybackControls.visible) return
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(metrics.bottomSafeAreaDp.dp),
    ) {
        if (sasayakiPlaybackControls.visible) {
            Row(
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .padding(start = sasayakiPlaybackControls.horizontalPaddingDp.dp)
                    .height(sasayakiPlaybackControls.rowHeightDp.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ReaderBottomSafePlaybackButton(
                    controls = sasayakiPlaybackControls,
                    colors = colors,
                    icon = Icons.Rounded.FastRewind,
                    contentDescription = stringResource(R.string.sasayaki_rewind),
                    onClick = onSasayakiSkipBackward,
                )
                ReaderBottomSafePlaybackButton(
                    controls = sasayakiPlaybackControls,
                    colors = colors,
                    icon = if (sasayakiPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                    contentDescription = if (sasayakiPlaying) {
                        stringResource(R.string.sasayaki_pause)
                    } else {
                        stringResource(R.string.sasayaki_play)
                    },
                    onClick = onSasayakiTogglePlayback,
                )
                ReaderBottomSafePlaybackButton(
                    controls = sasayakiPlaybackControls,
                    colors = colors,
                    icon = Icons.Rounded.FastForward,
                    contentDescription = stringResource(R.string.sasayaki_fast_forward),
                    onClick = onSasayakiSkipForward,
                )
            }
        }
        if (progress.isNotBlank()) {
            Text(
                text = progress,
                color = Color(colors.infoText),
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1,
                textAlign = if (sasayakiPlaybackControls.visible) TextAlign.End else TextAlign.Center,
                modifier = if (sasayakiPlaybackControls.visible) {
                    Modifier
                        .align(Alignment.CenterEnd)
                        .fillMaxWidth()
                        .padding(
                            start = (
                                sasayakiPlaybackControls.buttonWidthDp * 3 +
                                    sasayakiPlaybackControls.horizontalPaddingDp
                                ).dp,
                            end = sasayakiPlaybackControls.horizontalPaddingDp.dp,
                        )
                } else {
                    Modifier.align(Alignment.Center)
                },
            )
        }
    }
}

@Composable
private fun ReaderBottomSafePlaybackButton(
    controls: ReaderSasayakiBottomPlaybackControls,
    colors: ReaderChromeColors,
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .width(controls.buttonWidthDp.dp)
            .height(controls.rowHeightDp.dp)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = Color(colors.infoText),
            modifier = Modifier.size(controls.iconSizeDp.dp),
        )
    }
}

@Composable
private fun ReaderMenuCard(
    colors: ReaderChromeColors,
    metrics: ReaderBottomChromeMetrics,
    onChapters: () -> Unit,
    onHighlights: () -> Unit,
    onAppearance: () -> Unit,
    onStatistics: (() -> Unit)?,
    onSasayaki: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier
            .width(metrics.menuWidthDp.dp)
            .readerChromeShadow(
                elevationDp = colors.bubbleShadowElevationDp,
                shadowColor = Color(colors.bubbleShadowColor),
                shape = RoundedCornerShape(28.dp),
            )
            .readerChromeOutline(
                outlineColor = Color(colors.bubbleOutline),
                shape = RoundedCornerShape(28.dp),
            )
            .readerChromeInnerShadow(
                shadowColor = Color(colors.bubbleInnerShadowColor),
                shape = RoundedCornerShape(28.dp),
            ),
        shape = RoundedCornerShape(28.dp),
        color = Color(colors.menuContainer),
        border = BorderStroke(readerBubbleBorderWidthDp(colors).dp, Color(colors.menuBorder)),
        tonalElevation = 0.dp,
        shadowElevation = 0.dp,
    ) {
        Column(
            modifier = Modifier.padding(vertical = metrics.menuVerticalPaddingDp.dp),
        ) {
            readerBottomMenuVisualOrder(
                showStatistics = onStatistics != null,
                showSasayaki = onSasayaki != null,
            ).forEachIndexed { index, destination ->
                if (index > 0) {
                    HorizontalDivider(
                        modifier = Modifier.padding(horizontal = metrics.menuItemHorizontalPaddingDp.dp),
                        color = Color(colors.menuBorder),
                    )
                }
                when (destination) {
                    ReaderMenuDestination.Appearance -> ReaderMenuItem(
                        text = stringResource(R.string.settings_appearance),
                        icon = {
                            Icon(
                                imageVector = Icons.Rounded.Palette,
                                contentDescription = null,
                                tint = Color(colors.menuContent),
                            )
                        },
                        colors = colors,
                        metrics = metrics,
                        onClick = onAppearance,
                    )

                    ReaderMenuDestination.Chapters -> ReaderMenuItem(
                        text = stringResource(R.string.reader_chapters),
                        icon = {
                            Icon(
                                imageVector = Icons.AutoMirrored.Rounded.List,
                                contentDescription = null,
                                tint = Color(colors.menuContent),
                            )
                        },
                        colors = colors,
                        metrics = metrics,
                        onClick = onChapters,
                    )

                    ReaderMenuDestination.Highlights -> ReaderMenuItem(
                        text = stringResource(R.string.reader_highlights),
                        icon = {
                            Icon(
                                imageVector = Icons.Rounded.BorderColor,
                                contentDescription = null,
                                tint = Color(colors.menuContent),
                            )
                        },
                        colors = colors,
                        metrics = metrics,
                        onClick = onHighlights,
                    )

                    ReaderMenuDestination.Statistics -> ReaderMenuItem(
                        text = stringResource(R.string.reader_statistics),
                        icon = {
                            Icon(
                                imageVector = Icons.AutoMirrored.Rounded.ShowChart,
                                contentDescription = null,
                                tint = Color(colors.menuContent),
                            )
                        },
                        colors = colors,
                        metrics = metrics,
                        onClick = onStatistics ?: return@forEachIndexed,
                    )

                    ReaderMenuDestination.Sasayaki -> ReaderMenuItem(
                        text = stringResource(R.string.sasayaki_title),
                        icon = {
                            Icon(
                                imageVector = Icons.Rounded.GraphicEq,
                                contentDescription = null,
                                tint = Color(colors.menuContent),
                            )
                        },
                        colors = colors,
                        metrics = metrics,
                        onClick = onSasayaki ?: return@forEachIndexed,
                    )
                }
            }
        }
    }
}

@Composable
private fun ReaderMenuItem(
    text: String,
    icon: @Composable () -> Unit,
    colors: ReaderChromeColors,
    metrics: ReaderBottomChromeMetrics,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(
                horizontal = metrics.menuItemHorizontalPaddingDp.dp,
                vertical = metrics.menuItemVerticalPaddingDp.dp,
            ),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(metrics.menuItemSpacingDp.dp),
    ) {
        Box(
            modifier = Modifier.size(metrics.menuItemIconBoxSizeDp.dp),
            contentAlignment = Alignment.Center,
        ) {
            icon()
        }
        Text(
            text = text,
            color = Color(colors.menuContent),
            style = MaterialTheme.typography.titleMedium,
        )
    }
}

@Composable
private fun ReaderGlassButton(
    colors: ReaderChromeColors,
    metrics: ReaderBottomChromeMetrics,
    onClick: () -> Unit,
    content: @Composable RowScope.() -> Unit,
) {
    ReaderRoundButton(
        colors = colors,
        sizeDp = metrics.buttonSizeDp,
        onClick = onClick,
        content = content,
    )
}

@Composable
private fun ReaderRoundButton(
    colors: ReaderChromeColors,
    sizeDp: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    containerColor: Color = Color(colors.buttonContainer),
    border: BorderStroke? = BorderStroke(readerButtonBorderWidthDp(colors).dp, Color(colors.buttonBorder)),
    shadowElevationDp: Int = colors.buttonShadowElevationDp,
    content: @Composable RowScope.() -> Unit,
) {
    Surface(
        modifier = modifier
            .size(sizeDp.dp)
            .readerChromeShadow(
                elevationDp = shadowElevationDp,
                shadowColor = Color(colors.buttonShadowColor),
                shape = CircleShape,
            )
            .readerChromeOutline(
                outlineColor = Color(colors.buttonOutline),
                shape = CircleShape,
            )
            .readerChromeInnerShadow(
                shadowColor = Color(colors.buttonInnerShadowColor),
                shape = CircleShape,
            ),
        shape = CircleShape,
        color = containerColor,
        border = border,
        tonalElevation = 0.dp,
        shadowElevation = 0.dp,
    ) {
        Row(
            modifier = Modifier
                .clickable(onClick = onClick)
                .fillMaxSize(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
            content = content,
        )
    }
}

private fun Modifier.readerChromeShadow(
    elevationDp: Int,
    shadowColor: Color,
    shape: Shape,
): Modifier =
    if (elevationDp <= 0) {
        this
    } else {
        dropShadow(
            shape = shape,
            shadow = Shadow(
                radius = (elevationDp * 5).dp,
                spread = 0.dp,
                color = shadowColor,
                offset = DpOffset.Zero,
            ),
        )
    }

private fun Modifier.readerChromeOutline(
    outlineColor: Color,
    shape: Shape,
): Modifier =
    if (outlineColor.alpha <= 0f) {
        this
    } else {
        border(
            width = 0.5.dp,
            color = outlineColor,
            shape = shape,
        )
    }

private fun Modifier.readerChromeInnerShadow(
    shadowColor: Color,
    shape: Shape,
): Modifier =
    if (shadowColor.alpha <= 0f) {
        this
    } else {
        innerShadow(
            shape = shape,
            shadow = Shadow(
                radius = 3.dp,
                spread = 0.dp,
                color = shadowColor,
                offset = DpOffset.Zero,
            ),
        )
    }
