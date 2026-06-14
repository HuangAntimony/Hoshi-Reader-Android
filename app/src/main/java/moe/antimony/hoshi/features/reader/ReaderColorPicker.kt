package moe.antimony.hoshi.features.reader

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.round
import moe.antimony.hoshi.R
import moe.antimony.hoshi.ui.hoshiOutlinedTextFieldColors

@Composable
internal fun ReaderColorSettingRow(
    label: String,
    color: Long,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    horizontalPadding: Dp = 14.dp,
) {
    val metrics = readerSheetDensityMetrics()
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = horizontalPadding, vertical = metrics.appearanceRowVerticalPaddingDp.dp),
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
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Surface(
                modifier = Modifier.size(28.dp),
                shape = RoundedCornerShape(14.dp),
                color = Color(color),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                tonalElevation = 0.dp,
            ) {}
            Text(
                text = color.toReaderColorHexInput(includeAlpha = color.readerColorAlpha() != 0xFF),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
    }
}

@Composable
internal fun ReaderColorPickerDialog(
    title: String,
    initialColor: Long,
    defaultColor: Long,
    onColorChange: (Long) -> Unit,
    onDismiss: () -> Unit,
    previewBorderColor: Color? = null,
    cursorColor: Color? = null,
) {
    var draftColor by remember(initialColor) { mutableStateOf(initialColor) }
    var hexInput by remember(initialColor) {
        mutableStateOf(initialColor.toReaderColorHexInput(includeAlpha = initialColor.readerColorAlpha() != 0xFF))
    }
    val parsedColor = readerColorFromHexInput(hexInput)
    val inputError = hexInput.isNotBlank() && parsedColor == null
    val colorScheme = MaterialTheme.colorScheme

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp),
                    shape = RoundedCornerShape(8.dp),
                    color = draftColor.toOpaqueReaderColor(),
                    border = BorderStroke(1.dp, previewBorderColor ?: colorScheme.outlineVariant),
                    tonalElevation = 0.dp,
                ) {}
                OutlinedTextField(
                    value = hexInput,
                    onValueChange = { value ->
                        hexInput = value
                        readerColorFromHexInput(value)?.let { draftColor = it }
                    },
                    label = { Text(stringResource(R.string.reader_appearance_color_hex)) },
                    singleLine = true,
                    isError = inputError,
                    supportingText = if (inputError) {
                        { Text(stringResource(R.string.reader_appearance_color_invalid)) }
                    } else {
                        null
                    },
                    colors = hoshiOutlinedTextFieldColors(cursorColor = cursorColor ?: colorScheme.onSurface),
                    modifier = Modifier.fillMaxWidth(),
                )
                ReaderColorChannelSlider(
                    label = stringResource(R.string.reader_appearance_color_alpha),
                    value = draftColor.readerColorAlpha(),
                    onValueChange = { value ->
                        draftColor = draftColor.withReaderColorAlpha(value)
                        hexInput = draftColor.toReaderColorHexInput(includeAlpha = true)
                    },
                )
                ReaderColorChannelSlider(
                    label = stringResource(R.string.reader_appearance_color_red),
                    value = draftColor.readerColorRed(),
                    onValueChange = { value ->
                        draftColor = draftColor.withReaderColorRed(value)
                        hexInput = draftColor.toReaderColorHexInput(includeAlpha = draftColor.readerColorAlpha() != 0xFF)
                    },
                )
                ReaderColorChannelSlider(
                    label = stringResource(R.string.reader_appearance_color_green),
                    value = draftColor.readerColorGreen(),
                    onValueChange = { value ->
                        draftColor = draftColor.withReaderColorGreen(value)
                        hexInput = draftColor.toReaderColorHexInput(includeAlpha = draftColor.readerColorAlpha() != 0xFF)
                    },
                )
                ReaderColorChannelSlider(
                    label = stringResource(R.string.reader_appearance_color_blue),
                    value = draftColor.readerColorBlue(),
                    onValueChange = { value ->
                        draftColor = draftColor.withReaderColorBlue(value)
                        hexInput = draftColor.toReaderColorHexInput(includeAlpha = draftColor.readerColorAlpha() != 0xFF)
                    },
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = !inputError,
                onClick = { onColorChange(parsedColor ?: draftColor) },
            ) {
                Text(stringResource(R.string.action_save))
            }
        },
        dismissButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(
                    onClick = {
                        draftColor = defaultColor
                        hexInput = defaultColor.toReaderColorHexInput(includeAlpha = defaultColor.readerColorAlpha() != 0xFF)
                    },
                ) {
                    Text(stringResource(R.string.action_reset))
                }
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        },
    )
}

@Composable
private fun ReaderColorChannelSlider(
    label: String,
    value: Int,
    onValueChange: (Int) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(
                text = label,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(value.toString(), style = MaterialTheme.typography.bodyMedium)
        }
        Slider(
            value = value.toFloat(),
            onValueChange = { onValueChange(round(it).toInt()) },
            valueRange = 0f..255f,
            steps = 254,
        )
    }
}

private fun Long.toOpaqueReaderColor(): Color =
    Color(withReaderColorAlpha(0xFF))
