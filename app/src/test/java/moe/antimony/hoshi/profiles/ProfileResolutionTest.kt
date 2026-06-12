package moe.antimony.hoshi.profiles

import moe.antimony.hoshi.content.ContentLanguageProfile
import org.junit.Assert.assertEquals
import org.junit.Test

class ProfileResolutionTest {
    @Test
    fun automaticBookProfileUsesBookLanguagePrimaryProfile() {
        val japanese = HoshiProfile(
            id = "default-ja",
            name = "Japanese",
            dictionaryLanguageId = ContentLanguageProfile.JapaneseLanguageId,
            isDefault = true,
        )
        val english = HoshiProfile(
            id = "reading-en",
            name = "English",
            dictionaryLanguageId = ContentLanguageProfile.EnglishLanguageId,
        )
        val state = ProfileState(
            profiles = listOf(japanese, english),
            defaultProfileId = japanese.id,
            globalActiveProfileId = japanese.id,
            loadedProfileId = null,
            primaryProfileIdsByLanguage = mapOf(
                ContentLanguageProfile.JapaneseLanguageId to japanese.id,
                ContentLanguageProfile.EnglishLanguageId to english.id,
            ),
        )

        assertEquals(english, state.automaticBookProfile(bookLanguage = "en-US"))
    }

    @Test
    fun automaticBookProfileFallsBackToGlobalActiveProfileWhenLanguageMetadataIsMissing() {
        val japanese = HoshiProfile(
            id = "default-ja",
            name = "Japanese",
            dictionaryLanguageId = ContentLanguageProfile.JapaneseLanguageId,
            isDefault = true,
        )
        val english = HoshiProfile(
            id = "reading-en",
            name = "English",
            dictionaryLanguageId = ContentLanguageProfile.EnglishLanguageId,
        )
        val state = ProfileState(
            profiles = listOf(japanese, english),
            defaultProfileId = japanese.id,
            globalActiveProfileId = english.id,
            loadedProfileId = null,
            primaryProfileIdsByLanguage = mapOf(
                ContentLanguageProfile.JapaneseLanguageId to japanese.id,
                ContentLanguageProfile.EnglishLanguageId to english.id,
            ),
        )

        assertEquals(english, state.automaticBookProfile(bookLanguage = null))
    }
}
