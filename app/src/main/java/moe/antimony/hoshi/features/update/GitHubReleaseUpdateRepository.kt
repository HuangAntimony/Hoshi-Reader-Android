package moe.antimony.hoshi.features.update

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.net.HttpURLConnection
import java.net.URL

internal data class AppVersion(
    private val major: Int,
    private val minor: Int,
    private val patch: Int,
) : Comparable<AppVersion> {
    override fun compareTo(other: AppVersion): Int =
        compareValuesBy(this, other, AppVersion::major, AppVersion::minor, AppVersion::patch)

    override fun toString(): String = "$major.$minor.$patch"

    companion object {
        private val VersionRegex = Regex("""^v?(\d+)\.(\d+)\.(\d+)$""")

        fun parse(raw: String): AppVersion? {
            val match = VersionRegex.matchEntire(raw.trim()) ?: return null
            return AppVersion(
                major = match.groupValues[1].toInt(),
                minor = match.groupValues[2].toInt(),
                patch = match.groupValues[3].toInt(),
            )
        }
    }
}

internal data class GitHubRelease(
    val tagName: String,
    val htmlUrl: String,
    val assets: List<GitHubReleaseAsset>,
)

internal data class GitHubReleaseAsset(
    val name: String,
    val browserDownloadUrl: String,
    val digest: String?,
)

internal data class AvailableUpdate(
    val versionName: String,
    val releaseUrl: String,
    val assetName: String,
    val downloadUrl: String,
    val sha256: String?,
)

internal fun GitHubRelease.availableUpdateOrNull(currentVersionName: String): AvailableUpdate? {
    val releaseVersion = AppVersion.parse(tagName) ?: return null
    val currentVersion = AppVersion.parse(currentVersionName) ?: return null
    if (releaseVersion <= currentVersion) return null

    val normalizedVersion = releaseVersion.toString()
    val apkAssets = assets.filter { it.name.endsWith(".apk", ignoreCase = true) }
    val expectedName = "Hoshi-Reader-v$normalizedVersion.apk"
    val selectedAsset = apkAssets.firstOrNull { it.name == expectedName }
        ?: apkAssets.singleOrNull()
        ?: return null
    return AvailableUpdate(
        versionName = normalizedVersion,
        releaseUrl = htmlUrl,
        assetName = selectedAsset.name,
        downloadUrl = selectedAsset.browserDownloadUrl,
        sha256 = selectedAsset.normalizedSha256(),
    )
}

private fun GitHubReleaseAsset.normalizedSha256(): String? =
    digest
        ?.removePrefix("sha256:")
        ?.takeIf { it.matches(Regex("[A-Fa-f0-9]{64}")) }

internal interface ReleaseUpdateRepository {
    suspend fun latestRelease(): GitHubRelease
}

internal class GitHubReleaseUpdateRepository(
    private val latestReleaseUrl: String = LatestReleaseUrl,
) : ReleaseUpdateRepository {
    override suspend fun latestRelease(): GitHubRelease {
        val connection = URL(latestReleaseUrl).openConnection() as HttpURLConnection
        connection.requestMethod = "GET"
        connection.setRequestProperty("Accept", "application/vnd.github+json")
        connection.setRequestProperty("User-Agent", "Hoshi-Reader-Android")
        connection.connectTimeout = 15_000
        connection.readTimeout = 15_000
        return connection.use {
            val code = it.responseCode
            if (code !in 200..299) {
                val retryAfter = it.getHeaderField("Retry-After")
                throw GitHubReleaseException("GitHub update check failed with HTTP $code${retryAfter?.let { value -> " (retry after $value)" }.orEmpty()}.")
            }
            GitHubReleaseJson.parse(it.inputStream.bufferedReader().use { reader -> reader.readText() })
        }
    }

    companion object {
        const val LatestReleaseUrl =
            "https://api.github.com/repos/HuangAntimony/Hoshi-Reader-Android/releases/latest"
    }
}

internal class GitHubReleaseException(message: String) : RuntimeException(message)

internal object GitHubReleaseJson {
    private val json = Json { ignoreUnknownKeys = true }

    fun parse(rawJson: String): GitHubRelease {
        val release = json.decodeFromString<GitHubReleaseResponse>(rawJson)
        return GitHubRelease(
            tagName = release.tagName,
            htmlUrl = release.htmlUrl,
            assets = release.assets.map { asset ->
                GitHubReleaseAsset(
                    name = asset.name,
                    browserDownloadUrl = asset.browserDownloadUrl,
                    digest = asset.digest,
                )
            },
        )
    }
}

@Serializable
private data class GitHubReleaseResponse(
    @SerialName("tag_name") val tagName: String,
    @SerialName("html_url") val htmlUrl: String,
    val assets: List<GitHubReleaseAssetResponse> = emptyList(),
)

@Serializable
private data class GitHubReleaseAssetResponse(
    val name: String,
    val digest: String? = null,
    @SerialName("browser_download_url") val browserDownloadUrl: String,
)

private inline fun <T : HttpURLConnection, R> T.use(block: (T) -> R): R =
    try {
        block(this)
    } finally {
        disconnect()
    }
