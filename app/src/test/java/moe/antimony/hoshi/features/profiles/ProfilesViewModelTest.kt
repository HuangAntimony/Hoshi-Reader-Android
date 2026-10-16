package moe.antimony.hoshi.features.profiles

import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.job
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import moe.antimony.hoshi.profiles.ProfileRepository
import moe.antimony.hoshi.ui.resolve
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

@OptIn(ExperimentalCoroutinesApi::class)
class ProfilesViewModelTest {
    @get:Rule val tempFolder = TemporaryFolder()

    @Test
    fun profilePersistenceFailureExposesTheUnderlyingFileReason() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        val files = tempFolder.newFolder("files")
        val repository = ProfileRepository(filesDir = files, ioDispatcher = dispatcher)
        val model = ProfilesViewModel(repository)
        try {
            val index = files.resolve("Profiles/profiles.json")
            check(index.delete())
            check(index.mkdir())
            model.renameProfile(repository.state.value.defaultProfileId, "New name")
            runCurrent()
            val message = model.error.value?.resolve({ _, _ -> "Profile update failed" }, { _, _, _ -> "" }).orEmpty()
            assertTrue(message.contains("Profiles/profiles.json"))
            assertTrue(message.contains("directory", ignoreCase = true))
        } finally {
            model.viewModelScope.coroutineContext.job.cancelAndJoin()
            Dispatchers.resetMain()
        }
    }
}
