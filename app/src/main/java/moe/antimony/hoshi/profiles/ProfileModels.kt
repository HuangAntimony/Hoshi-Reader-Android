package moe.antimony.hoshi.profiles

import java.util.Locale
import kotlinx.serialization.Serializable
import moe.antimony.hoshi.content.ContentLanguageProfile

@Serializable
data class HoshiProfile(
    val id: String,
    val name: String,
    val dictionaryLanguageId: String,
    val isDefault: Boolean = false,
)

data class ProfileState(
    val profiles: List<HoshiProfile>,
    val defaultProfileId: String,
    val globalActiveProfileId: String,
    val loadedProfileId: String?,
    val primaryProfileIdsByLanguage: Map<String, String>,
) {
    val effectiveProfileId: String
        get() = loadedProfileId ?: globalActiveProfileId

    val effectiveProfile: HoshiProfile
        get() = profileById(effectiveProfileId) ?: profileById(defaultProfileId) ?: profiles.first()

    val globalActiveProfile: HoshiProfile
        get() = profileById(globalActiveProfileId) ?: effectiveProfile

    val effectiveContentLanguageProfile: ContentLanguageProfile
        get() = ContentLanguageProfile.fromDictionaryLanguageId(effectiveProfile.dictionaryLanguageId)
            ?: ContentLanguageProfile.Default

    fun profileById(profileId: String): HoshiProfile? =
        profiles.firstOrNull { it.id == profileId }

    fun automaticBookProfile(bookLanguage: String?): HoshiProfile {
        val automaticProfile = bookLanguage
            ?.substringBefore('-')
            ?.substringBefore('_')
            ?.lowercase(Locale.ROOT)
            ?.let { primaryProfileIdsByLanguage[it] }
            ?.let { profileById(it) }
        return automaticProfile ?: globalActiveProfile
    }
}
