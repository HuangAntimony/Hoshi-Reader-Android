package moe.antimony.hoshi.features.update

import android.Manifest
import android.os.Build
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import coil3.compose.AsyncImage
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import moe.antimony.hoshi.R
import moe.antimony.hoshi.features.settings.GroupDivider
import moe.antimony.hoshi.ui.ErrorDetailsText
import moe.antimony.hoshi.ui.HoshiAlertDialog
import moe.antimony.hoshi.ui.asString

internal fun showWechatSupport(language: String): Boolean = language == "zh"

@Composable
internal fun rememberAboutLinkOpener(model: AboutActionsViewModel): (String) -> Unit {
    val context = LocalContext.current
    return remember(context, model) {
        { url ->
            try { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
            catch (error: Exception) { model.reportError(R.string.about_open_link_failed, error) }
        }
    }
}

@Composable
internal fun AboutActionErrors(model: AboutActionsViewModel) {
    val state by model.state.collectAsStateWithLifecycle()
    state.error?.let { error ->
        HoshiAlertDialog(onDismissRequest = model::dismissError,
            title = { Text(stringResource(R.string.dialog_error_title)) },
            text = { ErrorDetailsText(error.asString()) },
            confirmButton = { TextButton(onClick = model::dismissError) { Text(stringResource(R.string.action_ok)) } })
    }
}

@Composable
internal fun AboutSupportSection(model: AboutActionsViewModel, openLink: (String) -> Unit) {
    val chinese = showWechatSupport(LocalConfiguration.current.locales[0].language)
    AboutSection(stringResource(R.string.about_support), footer = stringResource(R.string.about_support_footer)) {
        AboutRow(stringResource(R.string.about_sponsors), onClick = { openLink("https://github.com/sponsors/HuangAntimony") })
        GroupDivider()
        AboutRow(stringResource(R.string.about_kofi), onClick = { openLink("https://ko-fi.com/huangantimony") })
        if (chinese) {
            GroupDivider()
            AboutRow(stringResource(R.string.about_wechat), onClick = { model.openWechat() })
        }
    }
}

/** Keep the permission callback and dialog registered even when the support rows scroll off screen. */
@Composable
internal fun AboutSupportEffects(model: AboutActionsViewModel) {
    val state by model.state.collectAsStateWithLifecycle()
    val chinese = showWechatSupport(LocalConfiguration.current.locales[0].language)
    LaunchedEffect(chinese) { if (!chinese) model.closeWechat() }
    val context = LocalContext.current
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) model.saveImage() else model.permissionDenied()
    }
    if (chinese && state.showWechat) {
        HoshiAlertDialog(onDismissRequest = { model.closeWechat() },
            title = { Text(stringResource(R.string.about_wechat)) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    AsyncImage(model = "file:///android_asset/wechat_support.png", contentDescription = null,
                        onError = { model.reportError(R.string.about_image_load_failed, it.result.throwable) },
                        contentScale = ContentScale.Fit, modifier = Modifier.fillMaxWidth().aspectRatio(1f))
                    Text(stringResource(if (state.saved) R.string.reader_image_saved else R.string.about_wechat_hint))
                }
            },
            confirmButton = {
                TextButton(enabled = !state.saving, onClick = {
                    try {
                        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P &&
                            ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                            permission.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                        } else model.saveImage()
                    }
                    catch (error: Exception) { model.reportError(R.string.about_save_image_failed, error) }
                }) { Text(stringResource(if (state.saving) R.string.about_image_saving else R.string.about_save_image)) }
            },
            dismissButton = { TextButton(onClick = { model.closeWechat() }) { Text(stringResource(R.string.action_close)) } },
        )
    }
}
