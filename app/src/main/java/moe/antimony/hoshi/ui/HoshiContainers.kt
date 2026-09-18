package moe.antimony.hoshi.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.PopupProperties
import moe.antimony.hoshi.ui.theme.LocalHoshiEInkMode
import moe.antimony.hoshi.ui.theme.hoshiContainerBorder
import moe.antimony.hoshi.ui.theme.hoshiContainerOutline
import moe.antimony.hoshi.ui.theme.hoshiSurfaces

/** Material interactions with one app-owned color and E-ink boundary policy. */
@Composable
fun HoshiAlertDialog(
    onDismissRequest: () -> Unit,
    confirmButton: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    dismissButton: (@Composable () -> Unit)? = null,
    icon: (@Composable () -> Unit)? = null,
    title: (@Composable () -> Unit)? = null,
    text: (@Composable () -> Unit)? = null,
    shape: Shape = AlertDialogDefaults.shape,
    containerColor: Color = hoshiSurfaces.overlay,
    iconContentColor: Color = AlertDialogDefaults.iconContentColor,
    titleContentColor: Color = AlertDialogDefaults.titleContentColor,
    textContentColor: Color = AlertDialogDefaults.textContentColor,
    tonalElevation: Dp = 0.dp,
    properties: DialogProperties = DialogProperties(),
) = AlertDialog(
    onDismissRequest, confirmButton, modifier.hoshiContainerOutline(shape), dismissButton, icon,
    title, text, shape, containerColor, iconContentColor, titleContentColor, textContentColor,
    tonalElevation, properties,
)

@Composable
fun HoshiDropdownMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    offset: DpOffset = DpOffset(0.dp, 0.dp),
    scrollState: ScrollState = rememberScrollState(),
    properties: PopupProperties = PopupProperties(focusable = true),
    shape: Shape = MenuDefaults.shape,
    containerColor: Color = hoshiSurfaces.overlay,
    tonalElevation: Dp = 0.dp,
    shadowElevation: Dp = if (LocalHoshiEInkMode.current) 0.dp else MenuDefaults.ShadowElevation,
    border: BorderStroke? = hoshiContainerBorder(),
    content: @Composable ColumnScope.() -> Unit,
) = DropdownMenu(expanded, onDismissRequest, modifier, offset, scrollState, properties, shape,
    containerColor, tonalElevation, shadowElevation, border, content)

@Composable
fun HoshiButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    shape: Shape = ButtonDefaults.shape,
    colors: ButtonColors = ButtonDefaults.buttonColors(),
    elevation: ButtonElevation? = ButtonDefaults.buttonElevation(),
    border: BorderStroke? = hoshiContainerBorder(),
    contentPadding: PaddingValues = ButtonDefaults.ContentPadding,
    interactionSource: MutableInteractionSource? = null,
    content: @Composable RowScope.() -> Unit,
) = Button(onClick, modifier, enabled, shape, colors,
    if (LocalHoshiEInkMode.current) null else elevation, border, contentPadding, interactionSource, content)
