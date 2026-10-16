package moe.antimony.hoshi.features.profiles

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import moe.antimony.hoshi.R
import moe.antimony.hoshi.ui.withErrorDetails
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import moe.antimony.hoshi.ui.UiText
import kotlinx.coroutines.launch
import moe.antimony.hoshi.profiles.ProfileRepository
import moe.antimony.hoshi.profiles.ProfileState

@HiltViewModel
internal class ProfilesViewModel @Inject constructor(
    private val profileRepository: ProfileRepository,
) : ViewModel() {
    private val _error = MutableStateFlow<UiText?>(null)
    val error = _error.asStateFlow()

    val profileState: StateFlow<ProfileState> = profileRepository.state

    fun dismissError() { _error.value = null }

    private fun mutate(operation: suspend () -> Unit) {
        viewModelScope.launch {
            _error.value = null
            try {
                operation()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                _error.value = UiText.Resource(R.string.profiles_update_failed).withErrorDetails(error)
            }
        }
    }

    fun createProfile(name: String, dictionaryLanguageId: String) {
        mutate {
            profileRepository.createProfile(name, dictionaryLanguageId)
        }
    }

    fun renameProfile(profileId: String, name: String) {
        mutate {
            profileRepository.renameProfile(profileId, name)
        }
    }

    fun deleteProfile(profileId: String) {
        mutate {
            profileRepository.deleteProfile(profileId)
        }
    }

    fun activateGlobal(profileId: String) {
        mutate {
            profileRepository.activateGlobal(profileId)
        }
    }

    fun setPrimaryProfile(dictionaryLanguageId: String, profileId: String) {
        mutate {
            profileRepository.setPrimaryProfile(dictionaryLanguageId, profileId)
        }
    }
}
