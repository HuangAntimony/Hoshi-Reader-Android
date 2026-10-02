package moe.antimony.hoshi.features.reader

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import moe.antimony.hoshi.ui.theme.hoshiContainerBorder
import moe.antimony.hoshi.ui.theme.hoshiContainerOutline
import moe.antimony.hoshi.ui.theme.hoshiSurfaces

/** Shared chrome for the Reader's audio panels; playback state stays with each feature. */
@Composable
internal fun ReaderSheetTabs(labels: List<String>, selectedIndex: Int, onSelected: (Int) -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier.fillMaxWidth().selectableGroup()
            .background(hoshiSurfaces.nested, RoundedCornerShape(12.dp))
            .hoshiContainerOutline(RoundedCornerShape(12.dp)).padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        labels.forEachIndexed { index, label ->
            val selected = index == selectedIndex
            Box(
                Modifier.weight(1f)
                    .background(if (selected) hoshiSurfaces.selected else Color.Transparent, RoundedCornerShape(10.dp))
                    .then(if (selected) Modifier.hoshiContainerOutline(RoundedCornerShape(10.dp)) else Modifier)
                    .selectable(selected = selected, role = Role.Tab, onClick = { onSelected(index) })
                    .padding(vertical = 7.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(label, style = MaterialTheme.typography.labelLarge,
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (selected) hoshiSurfaces.onSelected else hoshiSurfaces.muted,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
internal fun ReaderSheetSliderRow(
    label: String,
    valueText: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    steps: Int,
    onValueChange: (Float) -> Unit,
    onValueChangeFinished: (() -> Unit)? = null,
    showTicks: Boolean = true,
) {
    val metrics = readerSheetDensityMetrics()
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = metrics.sasayakiSliderVerticalPaddingDp.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, modifier = Modifier.weight(1f))
            Text(valueText, fontWeight = FontWeight.SemiBold)
        }
        Slider(value = value, onValueChange = onValueChange, valueRange = range, steps = steps,
            onValueChangeFinished = onValueChangeFinished,
            colors = if (showTicks) SliderDefaults.colors() else SliderDefaults.colors(
                activeTickColor = Color.Transparent, inactiveTickColor = Color.Transparent))
    }
}

@Composable
internal fun ReaderSheetCard(verticalPadding: Dp = 14.dp, shape: Shape = RoundedCornerShape(8.dp), content: @Composable ColumnScope.() -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp),
        shape = shape, color = hoshiSurfaces.group,
        border = hoshiContainerBorder(), tonalElevation = 0.dp,
    ) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = verticalPadding), content = content)
    }
}

@Composable
internal fun ReaderSheetSwitchRow(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    supportingText: String? = null,
) {
    val metrics = readerSheetDensityMetrics()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = metrics.sasayakiRowVerticalPaddingDp.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (supportingText == null) Text(label, style = MaterialTheme.typography.bodyLarge)
        else Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            Text(supportingText, style = MaterialTheme.typography.bodySmall, color = hoshiSurfaces.muted)
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
        )
    }
}
