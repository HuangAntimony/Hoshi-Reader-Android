package moe.antimony.hoshi.features.diagnostics

import moe.antimony.hoshi.ui.theme.hoshiSurfaces
import moe.antimony.hoshi.ui.theme.hoshiContainerBorder
import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.SaveAlt
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import moe.antimony.hoshi.R

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiagnosticsView(
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    val report by produceState<ProcessExitDiagnosticsReport?>(initialValue = null) {
        value = withContext(Dispatchers.IO) {
            loadProcessExitDiagnosticsReport(context)
        }
    }
    val shareText = report?.toShareText().orEmpty()
    BackHandler(onBack = onClose)
    val saveLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        if (uri == null || shareText.isBlank()) return@rememberLauncherForActivityResult
        scope.launch {
            val saved = withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.openOutputStream(uri)?.bufferedWriter()?.use {
                        it.write(shareText)
                    } != null
                }.getOrDefault(false)
            }
            snackbarHostState.showSnackbar(
                if (saved) {
                    resources.getString(R.string.diagnostics_saved)
                } else {
                    resources.getString(R.string.diagnostics_save_failed)
                },
            )
        }
    }

    Scaffold(
        modifier = modifier,
        containerColor = hoshiSurfaces.page,
        contentColor = MaterialTheme.colorScheme.onBackground,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text(stringResource(R.string.settings_diagnostics)) },
                navigationIcon = {
                    IconButton(onClick = onClose) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = stringResource(R.string.action_back))
                    }
                },
                actions = {
                    IconButton(
                        enabled = shareText.isNotBlank(),
                        onClick = { saveLauncher.launch(diagnosticsExportFileName()) },
                    ) {
                        Icon(Icons.Rounded.SaveAlt, contentDescription = stringResource(R.string.diagnostics_save))
                    }
                    IconButton(
                        enabled = shareText.isNotBlank(),
                        onClick = {
                            val sendIntent = Intent(Intent.ACTION_SEND)
                                .setType("text/plain")
                                .putExtra(Intent.EXTRA_SUBJECT, resources.getString(R.string.diagnostics_share_subject))
                                .putExtra(Intent.EXTRA_TEXT, shareText)
                            context.startActivity(
                                Intent.createChooser(sendIntent, resources.getString(R.string.diagnostics_share)),
                            )
                        },
                    ) {
                        Icon(Icons.Rounded.Share, contentDescription = stringResource(R.string.diagnostics_share))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = hoshiSurfaces.page,
                    scrolledContainerColor = hoshiSurfaces.page,
                ),
            )
        },
    ) { innerPadding ->
        if (report == null) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator()
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                contentPadding = PaddingValues(16.dp),
            ) {
                item {
                    SelectionContainer {
                        Text(
                            text = shareText,
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                        )
                    }
                }
            }
        }
    }
}
