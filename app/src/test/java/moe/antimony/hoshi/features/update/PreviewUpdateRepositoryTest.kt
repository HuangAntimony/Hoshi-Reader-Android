package moe.antimony.hoshi.features.update

import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class PreviewUpdateRepositoryTest {
    private val sha = "a".repeat(40)
    private val tag = "apk-preview-aaaaaaa-42-1"
    private fun release(tag: String = this.tag, date: String = "2026-10-21T10:00:00Z", extra: String = "") =
        """{"tag_name":"$tag","html_url":"https://github.com/example/releases/$tag","prerelease":true,"draft":false,"body":"<!-- hoshi-apk-preview -->","published_at":"$date","assets":[{"name":"Hoshi-Reader-preview-${tag.removePrefix("apk-preview-")}-arm64-v8a.apk","browser_download_url":"https://example/apk","digest":"sha256:${"b".repeat(64)}"}]$extra}"""

    @Test fun selectsNewestOwnedReleaseAcrossPagesAndResolvesFullAnnotatedCommit() = runTest {
        val requested = mutableListOf<String>()
        val repository = GitHubPreviewRepository(client = object : GitHubHttpClient {
            override fun get(url: String, headers: Map<String, String>): String {
                requested += url
                return when {
                    "&page=1" in url -> "[${release(date = "2026-10-20T10:00:00Z")}]"
                    "&page=2" in url -> "[${release(tag = "apk-preview-aaaaaaa-43-1")}]"
                    "&page=3" in url -> "[]"
                    "/git/ref/" in url -> """{"object":{"type":"tag","sha":"${"c".repeat(40)}"}}"""
                    "/git/tags/" in url -> """{"tag":"apk-preview-aaaaaaa-43-1","message":"<!-- hoshi-apk-preview -->","object":{"type":"commit","sha":"$sha"}}"""
                    else -> error(url)
                }
            }
        }, apiMirrorPrefixes = emptyList(), pageSize = 1, supportedAbis = listOf("arm64-v8a"))
        val build = (repository.latest() as PreviewLookup.Found).build
        assertEquals(sha, build.commitSha)
        assertEquals("apk-preview-aaaaaaa-43-1", build.tag)
        assertEquals("b".repeat(64), build.apk.sha256)
        assertEquals(5, requested.size)
    }

    @Test fun ignoresComponentsDraftsAndUnmarkedReleases() = runTest {
        val payload = "[" + listOf(
            release(tag = "transcription-audio-test"),
            release().replace("\"draft\":false", "\"draft\":true"),
            release().replace("<!-- hoshi-apk-preview -->", "unrelated"),
        ).joinToString(",") + "]"
        assertEquals(PreviewLookup.None, repository(payload).latest())
    }

    @Test fun noMatchingAbiDoesNotSelectAnArbitraryApk() = runTest {
        assertEquals(PreviewLookup.NoCompatibleAsset, repository("[${release()}]", listOf("x86_64")).latest())
    }

    @Test fun malformedTagResolutionFailsWithOriginalContext() = runTest {
        val repository = repository("[${release()}]")
        val error = runCatching { repository.latest() }.exceptionOrNull()
        assertNotNull(error)
        assertTrue(error!!.message.orEmpty().contains("git/ref/tags/$tag"))
    }

    private fun repository(payload: String, abis: List<String> = listOf("arm64-v8a")) =
        GitHubPreviewRepository(client = object : GitHubHttpClient {
            override fun get(url: String, headers: Map<String, String>) =
                if ("/releases?" in url) payload else "{}"
        }, apiMirrorPrefixes = emptyList(), supportedAbis = abis)
}
