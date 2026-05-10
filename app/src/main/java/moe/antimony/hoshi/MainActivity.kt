package moe.antimony.hoshi

import android.annotation.SuppressLint
import android.content.Intent
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
import kotlinx.coroutines.launch
import moe.antimony.hoshi.features.reader.ReaderSettings
import moe.antimony.hoshi.features.reader.usesDarkInterface
import moe.antimony.hoshi.features.update.DownloadedUpdatePrompt
import moe.antimony.hoshi.navigation.AppShell
import moe.antimony.hoshi.ui.theme.HoshiReaderTheme

class MainActivity : ComponentActivity() {
    private var pendingImportUri by mutableStateOf<Uri?>(null)
    private var readerKeyEventHandler: ((KeyEvent) -> Boolean)? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pendingImportUri = intent.importUri()
        enableEdgeToEdge()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.isNavigationBarContrastEnforced = false
        }
        setContent {
            val appContainer = remember { HoshiAppContainer(applicationContext) }
            val readerSettingsRepository = appContainer.readerSettingsRepository
            val scope = rememberCoroutineScope()
            var readerSettings by remember { mutableStateOf<ReaderSettings?>(null) }
            LaunchedEffect(readerSettingsRepository) {
                readerSettingsRepository.settings.collect { settings ->
                    readerSettings = settings
                }
            }
            val systemDark = isSystemInDarkTheme()
            val loadedReaderSettings = readerSettings
            CompositionLocalProvider(LocalHoshiAppContainer provides appContainer) {
                HoshiReaderTheme(
                    darkTheme = loadedReaderSettings?.usesDarkInterface(systemDark) ?: systemDark,
                    eInkMode = loadedReaderSettings?.eInkMode ?: false,
                ) {
                    val loadedReaderSettings = readerSettings ?: return@HoshiReaderTheme
                    AppShell(
                        pendingImportUri = pendingImportUri,
                        onPendingImportConsumed = { pendingImportUri = null },
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
    }

    private fun Intent?.importUri(): Uri? =
        this?.data?.takeIf { action == Intent.ACTION_VIEW }
}
