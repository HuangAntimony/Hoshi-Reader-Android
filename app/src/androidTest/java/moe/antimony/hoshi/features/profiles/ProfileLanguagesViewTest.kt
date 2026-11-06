package moe.antimony.hoshi.features.profiles

import android.content.Context
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import moe.antimony.hoshi.R
import moe.antimony.hoshi.profiles.ProfileRepository
import moe.antimony.hoshi.ui.theme.HoshiReaderTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Renders the real profile editor against a scratch repository, without altering saved profiles. */
class ProfileLanguagesViewTest {
    @get:Rule val compose = createComposeRule()
    @get:Rule val temporaryFolder = TemporaryFolder()

    @Test fun createsChineseAndKoreanProfilesFromLanguageDropdown() {
        val repository = ProfileRepository(temporaryFolder.newFolder("profiles"))
        val viewModel = ProfilesViewModel(repository)
        lateinit var context: Context
        try {
            compose.setContent {
                context = LocalContext.current
                HoshiReaderTheme(darkTheme = false, dynamicColor = false) {
                    ProfilesView(onClose = {}, viewModel = viewModel)
                }
            }
            compose.waitForIdle()
            listOf("zh" to R.string.profile_language_chinese, "ko" to R.string.profile_language_korean).forEach { (id, label) ->
                compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText(context.getString(R.string.profiles_create)))
                compose.onNodeWithText(context.getString(R.string.profiles_create)).performClick()
                compose.onNodeWithText(context.getString(R.string.profiles_new_profile_name)).performTextInput("Fixture $id")
                compose.onNodeWithText(context.getString(R.string.profiles_language)).performClick()
                compose.onNodeWithText(context.getString(label)).performClick()
                compose.onNodeWithText(context.getString(R.string.action_save)).performClick()
                compose.waitUntil(5_000) { repository.state.value.profiles.any { it.dictionaryLanguageId == id } }
                val profile = repository.state.value.profiles.single { it.dictionaryLanguageId == id }
                assertEquals("Fixture $id", profile.name)
                assertEquals(profile.id, repository.state.value.primaryProfileIdsByLanguage[id])
            }
        } finally {
            runBlocking { viewModel.viewModelScope.coroutineContext[Job]?.cancelAndJoin() }
        }
    }
}
