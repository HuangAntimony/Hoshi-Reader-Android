package moe.antimony.hoshi

import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.ActivityInfo
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.launch
import moe.antimony.hoshi.features.reader.ReaderSettings
import moe.antimony.hoshi.features.reader.usesDarkInterface
import moe.antimony.hoshi.features.reader.usesDarkSystemBarIcons
import moe.antimony.hoshi.features.sasayaki.SasayakiPlaybackReturnAction
import moe.antimony.hoshi.features.sasayaki.SasayakiPlaybackReturnBookIdExtra
import moe.antimony.hoshi.features.update.DownloadedUpdatePrompt
import moe.antimony.hoshi.navigation.AppShell
import moe.antimony.hoshi.ui.theme.HoshiReaderTheme

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    @Inject internal lateinit var uiDependencies: HoshiUiDependencies

    private var pendingImportUri by mutableStateOf<Uri?>(null)
    private var pendingSasayakiReaderBookId by mutableStateOf<String?>(null)
    private var readerKeyEventHandler: ((KeyEvent) -> Boolean)? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pendingImportUri = intent.importUri()
        pendingSasayakiReaderBookId = intent.sasayakiReaderBookIdOrActivePlayback()
        enableEdgeToEdge()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.isNavigationBarContrastEnforced = false
        }
        setContent {
            val readerSettingsRepository = uiDependencies.readerSettingsRepository
            val scope = rememberCoroutineScope()
            var readerSettings by remember { mutableStateOf<ReaderSettings?>(null) }
            LaunchedEffect(readerSettingsRepository) {
                readerSettingsRepository.settings.collect { settings ->
                    readerSettings = settings
                }
            }
            val systemDark = isSystemInDarkTheme()
            val loadedReaderSettings = readerSettings
            LaunchedEffect(loadedReaderSettings?.lockCurrentOrientation) {
                val settings = loadedReaderSettings ?: return@LaunchedEffect
                requestedOrientation = requestedOrientationForLockCurrentOrientation(settings.lockCurrentOrientation)
            }
            val darkTheme = loadedReaderSettings?.usesDarkInterface(systemDark) ?: systemDark
            val useDarkSystemBarIcons = loadedReaderSettings?.usesDarkSystemBarIcons(systemDark) ?: !systemDark
            CompositionLocalProvider(LocalHoshiUiDependencies provides uiDependencies) {
                HoshiReaderTheme(
                    darkTheme = darkTheme,
                    eInkMode = loadedReaderSettings?.eInkMode ?: false,
                    useDarkSystemBarIcons = useDarkSystemBarIcons,
                ) {
                    val loadedReaderSettings = readerSettings ?: return@HoshiReaderTheme
                    AppShell(
                        pendingImportUri = pendingImportUri,
                        onPendingImportConsumed = { pendingImportUri = null },
                        pendingSasayakiReaderBookId = pendingSasayakiReaderBookId,
                        onPendingSasayakiReaderConsumed = { pendingSasayakiReaderBookId = null },
                        readerSettings = loadedReaderSettings,
                        onReaderSettingsChange = { settings ->
                            readerSettings = settings
                            scope.launch {
                                readerSettingsRepository.update { settings }
                            }
                        },
                        onReaderKeyEventHandlerChange = { handler ->
                            readerKeyEventHandler = handler
                        }
                    )
                    DownloadedUpdatePrompt()
                }
            }
        }
    }

    @SuppressLint("RestrictedApi")
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (readerKeyEventHandler?.invoke(event) == true) {
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        intent.importUri()?.let { pendingImportUri = it }
        intent.sasayakiReaderBookIdOrActivePlayback()?.let { pendingSasayakiReaderBookId = it }
    }

    private fun Intent?.importUri(): Uri? =
        this?.data?.takeIf { action == Intent.ACTION_VIEW }

    private fun Intent?.sasayakiReaderBookId(): String? =
        this?.getStringExtra(SasayakiPlaybackReturnBookIdExtra)
            ?.takeIf { action == SasayakiPlaybackReturnAction && it.isNotBlank() }

    private fun Intent?.sasayakiReaderBookIdOrActivePlayback(): String? =
        sasayakiReaderBookId()
            ?: takeIf { it?.action == Intent.ACTION_MAIN }
                ?.let { uiDependencies.sasayakiPlaybackServiceRuntime.activePlaybackBookId() }
}

internal fun requestedOrientationForLockCurrentOrientation(lockCurrentOrientation: Boolean): Int =
    if (lockCurrentOrientation) {
        ActivityInfo.SCREEN_ORIENTATION_LOCKED
    } else {
        ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
    }
