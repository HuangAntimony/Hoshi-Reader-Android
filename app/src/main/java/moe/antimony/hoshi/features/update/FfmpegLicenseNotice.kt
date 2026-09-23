package moe.antimony.hoshi.features.update

import android.content.Intent
import androidx.core.net.toUri
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import moe.antimony.hoshi.R
import moe.antimony.hoshi.ui.HoshiAlertDialog

@Composable
internal fun FfmpegLicenseNotice() {
    val context = LocalContext.current
    val resources = LocalResources.current
    var showLicense by remember { mutableStateOf(false) }
    TextButton(onClick = { showLicense = true }) {
        Text(stringResource(R.string.about_ffmpeg_license))
    }
    if (showLicense) {
        val license by produceState("", resources) {
            value = withContext(Dispatchers.IO) {
                resources.openRawResource(R.raw.ffmpeg_license).bufferedReader().use { it.readText() }
            }
        }
        HoshiAlertDialog(
            onDismissRequest = { showLicense = false },
            title = { Text(stringResource(R.string.about_ffmpeg_license)) },
            text = { Text(license, Modifier.verticalScroll(rememberScrollState())) },
            confirmButton = {
                TextButton(onClick = { showLicense = false }) { Text(stringResource(R.string.action_close)) }
            },
            dismissButton = {
                TextButton(onClick = {
                    context.startActivity(Intent(Intent.ACTION_VIEW, "https://ffmpeg.org/releases/ffmpeg-9.0.2.tar.xz".toUri()))
                }) { Text(stringResource(R.string.about_ffmpeg_source)) }
            },
        )
    }
}

@Composable
internal fun TranscriptionLicenseNotice() {
    val context = LocalContext.current
    var showLicense by remember { mutableStateOf(false) }
    TextButton(onClick = { showLicense = true }) {
        Text(stringResource(R.string.about_transcription_license))
    }
    if (showLicense) {
        val license by produceState("", context) {
            value = withContext(Dispatchers.IO) {
                context.assets.open("transcription-runtime-NOTICES.txt").bufferedReader().use { it.readText() }
            }
        }
        HoshiAlertDialog(
            onDismissRequest = { showLicense = false },
            title = { Text(stringResource(R.string.about_transcription_license)) },
            text = { Text(license, Modifier.verticalScroll(rememberScrollState())) },
            confirmButton = {
                TextButton(onClick = { showLicense = false }) { Text(stringResource(R.string.action_close)) }
            },
        )
    }
}
