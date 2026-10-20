package moe.antimony.hoshi.features.diagnostics

import moe.antimony.hoshi.ui.theme.hoshiSurfaces
import android.content.Intent
import android.content.ClipData
import androidx.core.content.FileProvider
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.Lifecycle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.SaveAlt
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import java.io.IOException
import java.io.OutputStream
import java.text.DateFormat
import java.util.Date
import moe.antimony.hoshi.ui.withErrorDetails
import moe.antimony.hoshi.ui.HoshiAlertDialog
import androidx.compose.material3.TextButton
import kotlinx.coroutines.launch
import moe.antimony.hoshi.R
import moe.antimony.hoshi.ui.ErrorDetailsText
import moe.antimony.hoshi.ui.asString
import moe.antimony.hoshi.features.settings.GroupCard
import moe.antimony.hoshi.features.settings.GroupDivider
import moe.antimony.hoshi.features.settings.SectionTitle

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiagnosticsView(
    onClose: () -> Unit,
    onOpenRecords: (DiagnosticsRecordKind) -> Unit,
    records: DiagnosticsRecordKind? = null,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    val viewModel: DiagnosticsViewModel = hiltViewModel()
    val state by viewModel.state.collectAsStateWithLifecycle()
    val report = state.report
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.reload() }
    var operationError by remember { mutableStateOf<String?>(null) }
    var selectedDetails by remember { mutableStateOf<String?>(null) }
    val pageTitle = stringResource(when (records) {
        null -> R.string.settings_diagnostics
        DiagnosticsRecordKind.Crashes -> R.string.diagnostics_crash_records
        DiagnosticsRecordKind.RuntimeErrors -> R.string.diagnostics_handled_errors
        DiagnosticsRecordKind.ProcessExits -> R.string.diagnostics_process_exits
    })
    state.loadError?.let { error ->
        HoshiAlertDialog(
            onDismissRequest = onClose,
            text = { ErrorDetailsText(error.asString()) },
            confirmButton = {
                TextButton(onClick = viewModel::reload) { Text(stringResource(R.string.settings_retry)) }
            },
            dismissButton = {
                TextButton(onClick = onClose) { Text(stringResource(R.string.action_close)) }
            },
        )
    }
    operationError?.let { message ->
        HoshiAlertDialog(
            onDismissRequest = { operationError = null },
            text = { ErrorDetailsText(message) },
            confirmButton = {
                TextButton(onClick = { operationError = null }) { Text(stringResource(R.string.action_ok)) }
            },
        )
    }
    val nonfatalLabels = NonfatalDiagnosticsLabels(
        title = stringResource(R.string.diagnostics_handled_errors),
        time = { resources.getString(R.string.diagnostics_runtime_time, it) },
        operation = { resources.getString(R.string.diagnostics_runtime_operation, it) },
        exception = { resources.getString(R.string.diagnostics_runtime_exception, it) },
        details = stringResource(R.string.diagnostics_runtime_details),
        trace = stringResource(R.string.diagnostics_runtime_trace),
    )
    val locale = resources.configuration.locales.toLanguageTags()
    selectedDetails?.let { details ->
        HoshiAlertDialog(
            onDismissRequest = { selectedDetails = null },
            title = { Text(pageTitle) },
            text = {
                ProvideTextStyle(MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace)) {
                    ErrorDetailsText(details)
                }
            },
            confirmButton = {
                TextButton(onClick = { selectedDetails = null }) { Text(stringResource(R.string.action_close)) }
            },
        )
    }
    val shareText = remember(report, locale) { report?.toShareText(nonfatalLabels).orEmpty() }
    BackHandler(onBack = onClose)
    val saveLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        if (uri == null || shareText.isBlank()) return@rememberLauncherForActivityResult
        scope.launch {
            try {
                viewModel.save(uri, shareText)
                snackbarHostState.showSnackbar(resources.getString(R.string.diagnostics_saved))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                operationError = resources.getString(R.string.diagnostics_save_failed).withErrorDetails(error)
            }
        }
    }

    Scaffold(
        modifier = modifier,
        containerColor = hoshiSurfaces.page,
        contentColor = MaterialTheme.colorScheme.onBackground,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text(pageTitle) },
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
                            scope.launch {
                                try {
                                    val file = viewModel.shareFile(shareText)
                                    val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
                                    val sendIntent = Intent(Intent.ACTION_SEND)
                                        .setType("text/plain")
                                        .putExtra(Intent.EXTRA_SUBJECT, resources.getString(R.string.diagnostics_share_subject))
                                        .putExtra(Intent.EXTRA_STREAM, uri)
                                        .apply { clipData = ClipData.newRawUri("", uri) }
                                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                    context.startActivity(Intent.createChooser(sendIntent, resources.getString(R.string.diagnostics_share)))
                                } catch (cancelled: CancellationException) {
                                    throw cancelled
                                } catch (error: Exception) {
                                    operationError = resources.getString(R.string.diagnostics_share_failed).withErrorDetails(error)
                                }
                            }
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
                when (records) {
                    null -> {
                        item {
                            GroupCard {
                                DiagnosticsInfoRow(stringResource(R.string.diagnostics_package_name), report.packageName)
                                GroupDivider()
                                DiagnosticsInfoRow(stringResource(R.string.diagnostics_version_name), report.versionName)
                                GroupDivider()
                                DiagnosticsInfoRow(stringResource(R.string.diagnostics_version_code), report.versionCode.toString())
                                GroupDivider()
                                DiagnosticsInfoRow(stringResource(R.string.diagnostics_android_sdk), report.sdkInt.toString())
                            }
                            Spacer(Modifier.height(24.dp))
                            SectionTitle(stringResource(R.string.diagnostics_webview))
                            GroupCard {
                                DiagnosticsInfoRow(stringResource(R.string.diagnostics_package_name),
                                    report.webViewPackageName ?: stringResource(R.string.diagnostics_unavailable))
                                GroupDivider()
                                DiagnosticsInfoRow(stringResource(R.string.diagnostics_version_name),
                                    report.webViewVersionName ?: stringResource(R.string.diagnostics_unavailable))
                            }
                            Spacer(Modifier.height(24.dp))
                            GroupCard {
                                DiagnosticsNavigationRow(stringResource(R.string.diagnostics_crash_records)) {
                                    onOpenRecords(DiagnosticsRecordKind.Crashes)
                                }
                                GroupDivider()
                                DiagnosticsNavigationRow(stringResource(R.string.diagnostics_handled_errors)) {
                                    onOpenRecords(DiagnosticsRecordKind.RuntimeErrors)
                                }
                                GroupDivider()
                                DiagnosticsNavigationRow(stringResource(R.string.diagnostics_process_exits)) {
                                    onOpenRecords(DiagnosticsRecordKind.ProcessExits)
                                }
                            }
                        }
                    }
                    DiagnosticsRecordKind.RuntimeErrors -> {
                        if (report.nonfatalErrors.isEmpty()) {
                            item { DiagnosticsEmptyText(stringResource(R.string.diagnostics_no_handled_errors)) }
                        }
                        items(report.nonfatalErrors) { error ->
                            DiagnosticsRecordRow(error.operation, error.timestampMillis, error.message) {
                                selectedDetails = error.toReportText(nonfatalLabels)
                            }
                        }
                    }
                    DiagnosticsRecordKind.Crashes -> {
                        if (report.capturedCrashes.isEmpty()) {
                            item { DiagnosticsEmptyText(stringResource(R.string.diagnostics_no_crashes)) }
                        } else {
                            items(report.capturedCrashes) { crash ->
                                DiagnosticsRecordRow(stringResource(R.string.diagnostics_captured_crash), crash.timestampMillis,
                                    crash.text.lineSequence().firstOrNull().orEmpty()) {
                                    selectedDetails = crash.text
                                }
                            }
                        }
                    }
                    DiagnosticsRecordKind.ProcessExits -> {
                        if (report.records.isEmpty()) {
                            item { DiagnosticsEmptyText(stringResource(R.string.diagnostics_no_process_exits)) }
                        }
                        items(report.records) { exit ->
                            DiagnosticsRecordRow(stringResource(exit.reason.messageRes()), exit.timestampMillis,
                                exit.description.orEmpty()) {
                                selectedDetails = report.copy(capturedCrashes = emptyList(), nonfatalErrors = emptyList(),
                                    records = listOf(exit)).toShareText()
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DiagnosticsInfoRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(label, Modifier.weight(0.35f), style = MaterialTheme.typography.bodyMedium)
        SelectionContainer(Modifier.weight(0.65f)) {
            Text(value, Modifier.fillMaxWidth(), style = MaterialTheme.typography.bodyMedium,
                color = hoshiSurfaces.muted, textAlign = TextAlign.End,
                maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun DiagnosticsNavigationRow(label: String, onClick: () -> Unit) {
    ListItem(
        headlineContent = { Text(label) },
        trailingContent = { Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, contentDescription = null) },
        colors = ListItemDefaults.colors(containerColor = hoshiSurfaces.group),
        modifier = Modifier.clickable(onClick = onClick),
    )
}

@Composable
private fun DiagnosticsRecordRow(title: String, timestampMillis: Long, summary: String, onClick: () -> Unit) {
    val locale = LocalResources.current.configuration.locales.toLanguageTags()
    val time = remember(timestampMillis, locale) {
        DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.MEDIUM).format(Date(timestampMillis))
    }
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(time, style = MaterialTheme.typography.bodySmall, color = hoshiSurfaces.muted)
            if (summary.isNotBlank()) {
                Text(summary, style = MaterialTheme.typography.bodySmall, maxLines = 2,
                    overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp))
            }
        }
        Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, contentDescription = null)
    }
    HorizontalDivider(color = hoshiSurfaces.outline)
}

@Composable
private fun DiagnosticsEmptyText(text: String) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = hoshiSurfaces.muted,
        modifier = Modifier.padding(vertical = 12.dp))
}

private fun ProcessExitReason.messageRes(): Int = when (this) {
    ProcessExitReason.Anr -> R.string.diagnostics_exit_anr
    ProcessExitReason.JavaCrash -> R.string.diagnostics_exit_java_crash
    ProcessExitReason.NativeCrash -> R.string.diagnostics_exit_native_crash
    ProcessExitReason.LowMemory -> R.string.diagnostics_exit_low_memory
    ProcessExitReason.ExcessiveResourceUsage -> R.string.diagnostics_exit_resources
    ProcessExitReason.InitializationFailure -> R.string.diagnostics_exit_initialization
    ProcessExitReason.UserRequested -> R.string.diagnostics_exit_user_requested
    ProcessExitReason.Signaled -> R.string.diagnostics_exit_signaled
    ProcessExitReason.Other -> R.string.diagnostics_exit_other
    ProcessExitReason.Unknown -> R.string.diagnostics_exit_unknown
}

internal fun saveDiagnosticsReport(text: String, openOutputStream: () -> OutputStream?) {
    val output = openOutputStream() ?: throw IOException("Diagnostics destination output stream unavailable")
    output.bufferedWriter().use { it.write(text) }
}
