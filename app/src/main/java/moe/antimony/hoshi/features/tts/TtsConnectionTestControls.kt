package moe.antimony.hoshi.features.tts

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.HourglassTop
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import moe.antimony.hoshi.R
import moe.antimony.hoshi.ui.HoshiButton
import moe.antimony.hoshi.ui.theme.LocalHoshiEInkMode

@Composable
internal fun TtsConnectionTestControls(
    enabled: Boolean, testing: Boolean, verified: Boolean, failure: TtsFailure?, onTest: () -> Unit,
) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(Modifier.fillMaxWidth().heightIn(min = 24.dp)) {
            if (failure != null) Text(stringResource(failure.messageRes()),
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
            else if (verified) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Rounded.CheckCircle, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
                Text(stringResource(R.string.tts_connection_success), style = MaterialTheme.typography.bodyMedium)
            }
        }
        HoshiButton(onClick = onTest, enabled = enabled, modifier = Modifier.fillMaxWidth().height(48.dp)) {
            if (testing) {
                if (LocalHoshiEInkMode.current) Icon(Icons.Rounded.HourglassTop, null, Modifier.size(18.dp))
                else CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = LocalContentColor.current)
                Spacer(Modifier.width(8.dp))
            }
            Text(stringResource(R.string.tts_test_connection))
        }
    }
}
