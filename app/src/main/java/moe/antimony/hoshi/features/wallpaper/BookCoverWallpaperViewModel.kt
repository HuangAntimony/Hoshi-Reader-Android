package moe.antimony.hoshi.features.wallpaper

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import java.io.File
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import moe.antimony.hoshi.R
import moe.antimony.hoshi.ui.withErrorDetails
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import moe.antimony.hoshi.ui.UiText
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

@HiltViewModel
internal class BookCoverWallpaperViewModel internal constructor(
    val settings: Flow<BookCoverWallpaperSettings>,
    private val updateSettings: suspend ((BookCoverWallpaperSettings) -> BookCoverWallpaperSettings) -> Unit,
    private val capabilityProvider: BookCoverWallpaperCapabilityProvider,
    private val iReaderCapabilityProvider: IReaderBookCoverCapabilityProvider,
    private val publisher: BookCoverPublisher,
    private val coroutineScope: CoroutineScope?,
) : ViewModel() {
    @Inject
    constructor(
        settingsRepository: BookCoverWallpaperSettingsRepository,
        capabilityProvider: BookCoverWallpaperCapabilityProvider,
        iReaderCapabilityProvider: IReaderBookCoverCapabilityProvider,
        publisher: BookCoverPublisher,
    ) : this(
        settings = settingsRepository.settings,
        updateSettings = settingsRepository::update,
        capabilityProvider = capabilityProvider,
        iReaderCapabilityProvider = iReaderCapabilityProvider,
        publisher = publisher,
        coroutineScope = null,
    )

    private val _error = MutableStateFlow<UiText?>(null)
    val error = _error.asStateFlow()

    private val scope: CoroutineScope
        get() = coroutineScope ?: viewModelScope

    fun capability(): BookCoverWallpaperCapability = capabilityProvider.capability()

    fun iReaderCapability(): IReaderBookCoverCapability = iReaderCapabilityProvider.capability()

    fun setUpdateLockScreen(enabled: Boolean) {
        save {
            updateSettings { it.copy(updateLockScreen = enabled) }
        }
    }

    fun setExportEnabled(enabled: Boolean) {
        save {
            updateSettings { it.copy(exportEnabled = enabled) }
        }
    }

    fun setUpdateIReaderBookCover(enabled: Boolean) {
        save {
            updateSettings { it.copy(updateIReaderBookCover = enabled) }
        }
    }

    fun setScaleMode(scaleMode: BookCoverScaleMode) {
        save {
            updateSettings { it.copy(scaleMode = scaleMode) }
        }
    }

    fun setExportTarget(uri: String, onUpdated: () -> Unit = {}) {
        save {
            updateSettings { it.copy(exportEnabled = true, exportTargetUri = uri) }
            onUpdated()
        }
    }

    fun dismissError() { _error.value = null }

    private fun save(operation: suspend () -> Unit) {
        scope.launch {
            _error.value = null
            try {
                operation()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                _error.value = UiText.Resource(R.string.display_settings_save_failed).withErrorDetails(error)
            }
        }
    }

    suspend fun publishCurrentCover(coverFile: File?): BookCoverPublishResult {
        if (coverFile != null) {
            return try {
                publisher.publish(coverFile)
            } catch (exception: CancellationException) {
                throw exception
            } catch (error: Exception) {
                BookCoverPublishResult.failedForAll(BookCoverPublishFailure.UnexpectedFailure, error)
            }
        }
        val current = try {
            settings.first()
        } catch (exception: CancellationException) {
            throw exception
        } catch (error: Exception) {
            return BookCoverPublishResult.failedForAll(BookCoverPublishFailure.SettingsUnavailable, error)
        }
        if (!current.updateLockScreen && !current.updateIReaderBookCover && !current.exportEnabled) {
            return BookCoverPublishResult.Skipped
        }
        val missing = BookCoverTargetResult.Failed(BookCoverPublishFailure.MissingCover)
        return BookCoverPublishResult(
            lockScreen = if (current.updateLockScreen) missing else BookCoverTargetResult.Skipped,
            export = if (current.exportEnabled) missing else BookCoverTargetResult.Skipped,
            iReader = if (current.updateIReaderBookCover) missing else BookCoverTargetResult.Skipped,
        )
    }
}
