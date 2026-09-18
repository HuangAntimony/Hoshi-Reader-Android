package moe.antimony.hoshi.features.settings

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Box
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Language
import moe.antimony.hoshi.ui.HoshiDropdownMenu as DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import moe.antimony.hoshi.R

@Composable
internal fun AppLanguageListItem(
    selected: AppLanguageMode,
    onSelected: (AppLanguageMode) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    ListItem(
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        leadingContent = { Icon(Icons.Rounded.Language, contentDescription = null) },
        headlineContent = { Text(stringResource(R.string.settings_language)) },
        trailingContent = {
            Box {
                TextButton(onClick = { expanded = true }) {
                    Text(stringResource(selected.labelRes))
                }
                DropdownMenu(
                    expanded = expanded,
                    onDismissRequest = { expanded = false },
                ) {
                    AppLanguageMode.entries.forEach { mode ->
                        DropdownMenuItem(
                            text = { Text(stringResource(mode.labelRes)) },
                            onClick = {
                                expanded = false
                                onSelected(mode)
                            },
                        )
                    }
                }
            }
        },
    )
}

@get:StringRes
private val AppLanguageMode.labelRes: Int
    get() = when (this) {
        AppLanguageMode.System -> R.string.language_follow_system
        AppLanguageMode.English -> R.string.language_english
        AppLanguageMode.SimplifiedChinese -> R.string.language_simplified_chinese
    }
