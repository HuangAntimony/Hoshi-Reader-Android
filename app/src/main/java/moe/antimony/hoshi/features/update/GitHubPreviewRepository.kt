package moe.antimony.hoshi.features.update

import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import java.time.Instant
import javax.inject.Inject

internal data class PreviewBuild(
    val commitSha: String,
    val tag: String,
    val publishedAt: String,
    val apk: AvailableUpdate,
)

internal sealed interface PreviewLookup {
    data object None : PreviewLookup
    data object NoCompatibleAsset : PreviewLookup
    data class Found(val build: PreviewBuild) : PreviewLookup
}

internal class GitHubPreviewRepository internal constructor(
    private val client: GitHubHttpClient,
    private val apiMirrorPrefixes: List<String> = DefaultApiMirrorPrefixes,
    private val pageSize: Int = 100,
    private val supportedAbis: List<String> = Build.SUPPORTED_ABIS.toList(),
) {
    @Inject constructor() : this(client = UrlConnectionGitHubHttpClient)

    suspend fun latest(): PreviewLookup = withContext(Dispatchers.IO) {
        val candidates = mutableListOf<Pair<JsonObject, Boolean>>()
        var page = 1
        while (true) {
            val (items, mirrored) = readGitHubApi("$Api/releases?per_page=$pageSize&page=$page", apiMirrorPrefixes, client) { body, mirror ->
                Json.parseToJsonElement(body).jsonArray to mirror
            }
            items.map { it.jsonObject }.filter { item ->
                item["draft"]?.jsonPrimitive?.boolean == false &&
                    item["prerelease"]?.jsonPrimitive?.boolean == true &&
                    Tag.matches(item["tag_name"]?.jsonPrimitive?.content.orEmpty()) &&
                    Marker in item["body"]?.jsonPrimitive?.content.orEmpty()
            }.forEach { candidates += it to mirrored }
            if (items.size < pageSize) break
            page++
        }
        val (item, mirrored) = candidates.maxByOrNull { Instant.parse(it.first.getValue("published_at").jsonPrimitive.content) }
            ?: return@withContext PreviewLookup.None
        val release = GitHubReleaseJson.parse(item.toString())
        val suffix = release.tagName.removePrefix("apk-preview-")
        val asset = supportedAbis.firstNotNullOfOrNull { abi ->
            release.assets.singleOrNull { it.name == "Hoshi-Reader-preview-$suffix-$abi.apk" }
        } ?: return@withContext PreviewLookup.NoCompatibleAsset
        val reference = readGitHubApi("$Api/git/ref/tags/${release.tagName}", apiMirrorPrefixes, client) { body, _ ->
            val obj = Json.parseToJsonElement(body).jsonObject.getValue("object").jsonObject
            require(obj.getValue("type").jsonPrimitive.content == "tag") { "Preview tag is not annotated" }
            obj.getValue("sha").jsonPrimitive.content.also { require(Sha.matches(it)) { "Invalid tag object SHA" } }
        }
        val sha = readGitHubApi("$Api/git/tags/$reference", apiMirrorPrefixes, client) { body, _ ->
            val tag = Json.parseToJsonElement(body).jsonObject
            require(tag.getValue("tag").jsonPrimitive.content == release.tagName) { "Preview tag name mismatch" }
            require(Marker in tag.getValue("message").jsonPrimitive.content.lines()) { "Preview tag ownership mismatch" }
            val obj = tag.getValue("object").jsonObject
            require(obj.getValue("type").jsonPrimitive.content == "commit") { "Preview tag must point to a commit" }
            obj.getValue("sha").jsonPrimitive.content.also {
                require(Sha.matches(it) && it.startsWith(Tag.matchEntire(release.tagName)!!.groupValues[1])) {
                    "Preview commit does not match its tag"
                }
            }
        }
        val mirrors = DefaultDownloadMirrorPrefixes.map { it + asset.browserDownloadUrl }
        val urls = if (mirrored) mirrors + asset.browserDownloadUrl else listOf(asset.browserDownloadUrl) + mirrors
        val publishedAt = item.getValue("published_at").jsonPrimitive.content
        PreviewLookup.Found(PreviewBuild(sha, release.tagName, publishedAt, AvailableUpdate(
            versionName = "", releaseUrl = release.htmlUrl, assetName = asset.name,
            downloadUrl = urls.first(), fallbackDownloadUrls = urls.drop(1), sha256 = asset.normalizedSha256(),
            previewCommitSha = sha, previewTag = release.tagName, previewPublishedAt = publishedAt,
        )))
    }

    companion object {
        private const val Api = "https://api.github.com/repos/HuangAntimony/Hoshi-Reader-Android"
        private const val Marker = "<!-- hoshi-apk-preview -->"
        private val Tag = Regex("apk-preview-([0-9a-f]{7})-[0-9]+-[0-9]+")
        private val Sha = Regex("[0-9a-f]{40}")
    }
}
