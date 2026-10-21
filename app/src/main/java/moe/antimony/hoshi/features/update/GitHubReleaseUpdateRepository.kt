package moe.antimony.hoshi.features.update

import android.os.Build
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import moe.antimony.hoshi.ui.diagnosticMessage
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import javax.inject.Inject

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
    val previewCommitSha: String? = null,
    val previewTag: String? = null,
    val previewPublishedAt: String? = null,
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

internal fun GitHubRelease.availableUpdateOrNull(
    currentVersionName: String,
    supportedAbis: List<String> = AndroidSupportedAbis.current(),
): AvailableUpdate? {
    val releaseVersion = AppVersion.parse(tagName) ?: return null
    val currentVersion = AppVersion.parse(currentVersionName) ?: return null
    if (releaseVersion <= currentVersion) return null

    val normalizedVersion = releaseVersion.toString()
    val apkAssets = assets.filter { it.name.endsWith(".apk", ignoreCase = true) }
    val expectedName = "Hoshi-Reader-v$normalizedVersion.apk"
    val selectedAsset = apkAssets.selectCompatibleAbiAsset(normalizedVersion, supportedAbis)
        ?: apkAssets.firstOrNull { it.name == expectedName }
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

private fun List<GitHubReleaseAsset>.selectCompatibleAbiAsset(
    normalizedVersion: String,
    supportedAbis: List<String>,
): GitHubReleaseAsset? {
    val expectedPrefix = "Hoshi-Reader-v$normalizedVersion-"
    val expectedSuffix = ".apk"
    val assetsByAbi = mapNotNull { asset ->
        val abi = asset.name
            .takeIf { it.startsWith(expectedPrefix) && it.endsWith(expectedSuffix) }
            ?.removePrefix(expectedPrefix)
            ?.removeSuffix(expectedSuffix)
            ?: return@mapNotNull null
        abi to asset
    }
    return supportedAbis.firstNotNullOfOrNull { abi ->
        assetsByAbi.singleOrNull { (assetAbi, _) -> assetAbi == abi }?.second
    }
}

private object AndroidSupportedAbis {
    fun current(): List<String> =
        runCatching { Build.SUPPORTED_ABIS.toList() }.getOrDefault(emptyList())
}

internal fun GitHubReleaseAsset.normalizedSha256(): String? =
    digest
        ?.removePrefix("sha256:")
        ?.takeIf { it.matches(Regex("[A-Fa-f0-9]{64}")) }

internal interface ReleaseUpdateRepository {
    suspend fun latestRelease(): GitHubRelease
}

internal interface GitHubHttpClient {
    fun get(url: String, headers: Map<String, String>): String
}

internal class GitHubReleaseUpdateRepository private constructor(
    private val latestReleaseUrl: String,
    private val apiMirrorPrefixes: List<String>,
    private val downloadMirrorPrefixes: List<String>,
    private val httpClient: GitHubHttpClient,
    @Suppress("UNUSED_PARAMETER")
    marker: Unit,
) : ReleaseUpdateRepository {
    @Inject
    constructor() : this(
        latestReleaseUrl = LatestReleaseUrl,
        apiMirrorPrefixes = DefaultApiMirrorPrefixes,
        downloadMirrorPrefixes = DefaultDownloadMirrorPrefixes,
        httpClient = UrlConnectionGitHubHttpClient,
        marker = Unit,
    )

    internal constructor(
        latestReleaseUrl: String = LatestReleaseUrl,
        apiMirrorPrefixes: List<String> = DefaultApiMirrorPrefixes,
        downloadMirrorPrefixes: List<String> = DefaultDownloadMirrorPrefixes,
        httpClient: GitHubHttpClient,
    ) : this(
        latestReleaseUrl = latestReleaseUrl,
        apiMirrorPrefixes = apiMirrorPrefixes,
        downloadMirrorPrefixes = downloadMirrorPrefixes,
        httpClient = httpClient,
        marker = Unit,
    )

    override suspend fun latestRelease(): GitHubRelease {
        return readGitHubApi(latestReleaseUrl, apiMirrorPrefixes, httpClient) { body, preferMirrors ->
            GitHubReleaseJson.parse(body).withDownloadMirrors(downloadMirrorPrefixes, preferMirrors)
        }
    }

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
            val details = buildString {
                append("HTTP $code\n${it.url}")
                listOf(
                    "Content-Type", "Retry-After", "X-RateLimit-Limit", "X-RateLimit-Remaining",
                    "X-RateLimit-Reset", "X-RateLimit-Used", "X-RateLimit-Resource",
                ).forEach { name ->
                    it.getHeaderField(name)?.let { value -> append("\n$name: $value") }
                }
            }
            val body = try {
                val stream = if (code in 200..299) it.inputStream else it.errorStream
                stream?.bufferedReader()?.use { reader -> reader.readText() }.orEmpty()
            } catch (error: IOException) {
                throw GitHubReleaseException(details, error)
            }
            val mediaType = it.contentType?.substringBefore(';')?.trim().orEmpty()
            val isJson = mediaType.equals("application/json", ignoreCase = true) ||
                (mediaType.startsWith("application/", ignoreCase = true) && mediaType.endsWith("+json", ignoreCase = true))
            if (code !in 200..299 || !isJson) {
                throw GitHubReleaseException(listOf(details, body).filter { value -> value.isNotBlank() }.joinToString("\n\n"))
            }
            body
        }
    }
}

internal val DefaultApiMirrorPrefixes = listOf(
    "https://ghproxy.vip/",
)

internal val DefaultDownloadMirrorPrefixes = listOf(
    "https://gh-proxy.com/",
    "https://gh.llkk.cc/",
    "https://ghpull.com/",
    "https://fastgit.cc/",
)

internal class GitHubReleaseException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

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

internal fun <T> readGitHubApi(
    url: String,
    mirrorPrefixes: List<String>,
    client: GitHubHttpClient,
    parse: (String, Boolean) -> T,
): T {
    val headers = mapOf("Accept" to "application/vnd.github+json", "User-Agent" to "Hoshi-Reader-Android")
    val failures = mutableListOf<Pair<String, Throwable>>()
    (listOf(url) + mirrorPrefixes.map { it + url }).distinct().forEachIndexed { index, candidate ->
        try {
            return parse(client.get(candidate, headers), index > 0)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            failures += candidate to error
        }
    }
    if (failures.size == 1) throw GitHubReleaseException(failures.single().first, failures.single().second)
    throw GitHubReleaseException(failures.joinToString("\n\n") { (source, error) -> "$source\n${error.diagnosticMessage()}" })
        .apply { failures.forEach { (_, error) -> addSuppressed(error) } }
}
