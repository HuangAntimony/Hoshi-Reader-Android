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
    val fallbackDownloadUrls: List<String> = emptyList(),
)

internal data class AvailableUpdate(
    val versionName: String,
    val releaseUrl: String,
    val assetName: String,
    val downloadUrl: String,
    val fallbackDownloadUrls: List<String> = emptyList(),
    val sha256: String?,
)

internal fun AvailableUpdate.downloadUrlCandidates(): List<String> =
    (listOf(downloadUrl) + fallbackDownloadUrls).distinct()

internal fun AvailableUpdate.downloadUrlAfterFailed(failedDownloadUrl: String?): String =
    if (failedDownloadUrl == null) {
        downloadUrl
    } else {
        val candidates = downloadUrlCandidates()
        candidates.getOrNull(candidates.indexOf(failedDownloadUrl) + 1) ?: downloadUrl
    }

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
        fallbackDownloadUrls = selectedAsset.fallbackDownloadUrls,
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

internal interface GitHubHttpClient {
    fun get(url: String, headers: Map<String, String>): String
}

internal class GitHubReleaseUpdateRepository(
    private val latestReleaseUrl: String = LatestReleaseUrl,
    private val apiMirrorPrefixes: List<String> = DefaultApiMirrorPrefixes,
    private val downloadMirrorPrefixes: List<String> = DefaultDownloadMirrorPrefixes,
    private val httpClient: GitHubHttpClient = UrlConnectionGitHubHttpClient,
) : ReleaseUpdateRepository {
    override suspend fun latestRelease(): GitHubRelease {
        val headers = mapOf(
            "Accept" to "application/vnd.github+json",
            "User-Agent" to "Hoshi-Reader-Android",
        )
        var lastError: Throwable? = null
        latestReleaseCandidates().forEachIndexed { index, url ->
            val preferMirrors = index > 0
            runCatching {
                GitHubReleaseJson
                    .parse(httpClient.get(url, headers))
                    .withDownloadMirrors(downloadMirrorPrefixes, preferMirrors)
            }.onSuccess { release ->
                return release
            }.onFailure { error ->
                lastError = error
            }
        }
        throw GitHubReleaseException(lastError?.message ?: "GitHub update check failed.")
    }

    private fun latestReleaseCandidates(): List<String> =
        (listOf(latestReleaseUrl) + apiMirrorPrefixes.map { prefix -> prefix + latestReleaseUrl }).distinct()

    private fun GitHubRelease.withDownloadMirrors(
        mirrorPrefixes: List<String>,
        preferMirrors: Boolean,
    ): GitHubRelease = copy(
        assets = assets.map { asset ->
            val mirroredUrls = mirrorPrefixes.map { prefix -> prefix + asset.browserDownloadUrl }.distinct()
            if (preferMirrors && mirroredUrls.isNotEmpty()) {
                asset.copy(
                    browserDownloadUrl = mirroredUrls.first(),
                    fallbackDownloadUrls = (mirroredUrls.drop(1) + asset.browserDownloadUrl).distinct(),
                )
            } else {
                asset.copy(fallbackDownloadUrls = mirroredUrls)
            }
        },
    )

    companion object {
        const val LatestReleaseUrl =
            "https://api.github.com/repos/HuangAntimony/Hoshi-Reader-Android/releases/latest"
    }
}

internal object UrlConnectionGitHubHttpClient : GitHubHttpClient {
    override fun get(url: String, headers: Map<String, String>): String {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.requestMethod = "GET"
        headers.forEach { (name, value) -> connection.setRequestProperty(name, value) }
        connection.connectTimeout = 15_000
        connection.readTimeout = 15_000
        return connection.use {
            val code = it.responseCode
            if (code !in 200..299) {
                val retryAfter = it.getHeaderField("Retry-After")
                throw GitHubReleaseException("GitHub update check failed with HTTP $code${retryAfter?.let { value -> " (retry after $value)" }.orEmpty()}.")
            }
            it.inputStream.bufferedReader().use { reader -> reader.readText() }
        }
    }
}

private val DefaultApiMirrorPrefixes = listOf(
    "https://ghproxy.vip/",
    "https://githubproxy.cc/",
)

private val DefaultDownloadMirrorPrefixes = listOf(
    "https://gh-proxy.com/",
    "https://gh.llkk.cc/",
    "https://ghpull.com/",
    "https://fastgit.cc/",
)

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
