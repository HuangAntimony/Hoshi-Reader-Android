package moe.antimony.hoshi.features.update

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import moe.antimony.hoshi.R
import moe.antimony.hoshi.ui.UiText
import moe.antimony.hoshi.ui.withErrorDetails

internal class AboutImageRepository @Inject constructor(@param:ApplicationContext private val context: Context) {
    suspend fun saveImage() = withContext(Dispatchers.IO) {
        saveGalleryImage({ context.assets.open("wechat_support.png") }, AndroidGalleryImageDestination(context))
    }
}

internal data class AboutActionsState(val saving: Boolean = false, val showWechat: Boolean = false, val saved: Boolean = false, val error: UiText? = null)

@HiltViewModel
internal class AboutActionsViewModel internal constructor(private val save: suspend () -> Unit) : ViewModel() {
    @Inject constructor(repository: AboutImageRepository) : this(repository::saveImage)
    private val mutableState = MutableStateFlow(AboutActionsState())
    val state = mutableState.asStateFlow()

    fun saveImage() {
        if (state.value.saving) return
        mutableState.update { it.copy(saving = true, saved = false, error = null) }
        viewModelScope.launch {
            try { save(); mutableState.update { it.copy(saved = true) } }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { reportError(R.string.about_save_image_failed, error) }
            finally { mutableState.update { it.copy(saving = false) } }
        }
    }
    fun reportError(resource: Int, error: Throwable) {
        if (error is CancellationException) throw error
        mutableState.update { it.copy(error = UiText.Resource(resource).withErrorDetails(error)) }
    }
    fun permissionDenied() {
        mutableState.update { it.copy(error = UiText.Resource(R.string.about_gallery_permission_needed)) }
    }
    fun dismissError() { mutableState.update { it.copy(error = null) } }
    fun openWechat() { mutableState.update { it.copy(showWechat = true, saved = false) } }
    fun closeWechat() { mutableState.update { it.copy(showWechat = false) } }
}
