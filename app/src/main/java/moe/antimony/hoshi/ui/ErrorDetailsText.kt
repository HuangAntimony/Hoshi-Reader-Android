package moe.antimony.hoshi.ui

import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** Error dialogs keep long original diagnostics readable and available to copy. */
@Composable
fun ErrorDetailsText(text: String, modifier: Modifier = Modifier) {
    SelectionContainer(modifier) {
        Text(text, Modifier.heightIn(max = 400.dp).verticalScroll(rememberScrollState()))
    }
}
