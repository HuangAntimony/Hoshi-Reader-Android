package moe.antimony.hoshi.features.update

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GitHubReleaseUpdateRepositoryTest {
    @Test
    fun parsesGitHubLatestReleaseJsonAndSelectsMatchingApkAsset() {
        val release = GitHubReleaseJson.parse(
            """
            {
              "tag_name": "v0.3.5",
              "html_url": "https://github.com/HuangAntimony/Hoshi-Reader-Android/releases/tag/v0.3.5",
              "assets": [
                {
                  "name": "Hoshi-Reader-v0.3.5.apk",
                  "digest": "sha256:7977f9e95adec03fce35ef0640fdd2fe662c6521d625dc12242df5b66fb2254b",
                  "browser_download_url": "https://example.com/Hoshi-Reader-v0.3.5.apk"
                },
                {
                  "name": "LICENSE",
                  "browser_download_url": "https://example.com/LICENSE"
                }
              ]
            }
            """.trimIndent(),
        )

        val update = release.availableUpdateOrNull(currentVersionName = "0.3.4")

        requireNotNull(update)
        assertEquals("0.3.5", update.versionName)
        assertEquals("Hoshi-Reader-v0.3.5.apk", update.assetName)
        assertEquals("https://example.com/Hoshi-Reader-v0.3.5.apk", update.downloadUrl)
        assertEquals("7977f9e95adec03fce35ef0640fdd2fe662c6521d625dc12242df5b66fb2254b", update.sha256)
    }

    @Test
    fun fallsBackToMirroredLatestReleaseAndPrefersMirroredDownloads() = runBlocking {
        val client = FakeGitHubHttpClient(
            responses = mapOf(
                "https://ghproxy.vip/https://api.github.com/repos/HuangAntimony/Hoshi-Reader-Android/releases/latest" to """
                    {
                      "tag_name": "v0.3.5",
                      "html_url": "https://github.com/HuangAntimony/Hoshi-Reader-Android/releases/tag/v0.3.5",
                      "assets": [
                        {
                          "name": "Hoshi-Reader-v0.3.5.apk",
                          "browser_download_url": "https://github.com/HuangAntimony/Hoshi-Reader-Android/releases/download/v0.3.5/Hoshi-Reader-v0.3.5.apk"
                        }
                      ]
                    }
                """.trimIndent(),
            ),
            failures = setOf(GitHubReleaseUpdateRepository.LatestReleaseUrl),
        )
        val repository = GitHubReleaseUpdateRepository(httpClient = client)

        val release = repository.latestRelease()
        val update = release.availableUpdateOrNull(currentVersionName = "0.3.4")

        requireNotNull(update)
        assertEquals(
            listOf(
                GitHubReleaseUpdateRepository.LatestReleaseUrl,
                "https://ghproxy.vip/https://api.github.com/repos/HuangAntimony/Hoshi-Reader-Android/releases/latest",
            ),
            client.requestedUrls.take(2),
        )
        assertEquals(
            "https://gh-proxy.com/https://github.com/HuangAntimony/Hoshi-Reader-Android/releases/download/v0.3.5/Hoshi-Reader-v0.3.5.apk",
            update.downloadUrl,
        )
        assertTrue(update.fallbackDownloadUrls.contains("https://github.com/HuangAntimony/Hoshi-Reader-Android/releases/download/v0.3.5/Hoshi-Reader-v0.3.5.apk"))
    }

    @Test
    fun directGitHubReleaseKeepsOriginalDownloadBeforeMirrorFallbacks() = runBlocking {
        val client = FakeGitHubHttpClient(
            responses = mapOf(
                GitHubReleaseUpdateRepository.LatestReleaseUrl to """
                    {
                      "tag_name": "v0.3.5",
                      "html_url": "https://github.com/HuangAntimony/Hoshi-Reader-Android/releases/tag/v0.3.5",
                      "assets": [
                        {
                          "name": "Hoshi-Reader-v0.3.5.apk",
                          "browser_download_url": "https://github.com/HuangAntimony/Hoshi-Reader-Android/releases/download/v0.3.5/Hoshi-Reader-v0.3.5.apk"
                        }
                      ]
                    }
                """.trimIndent(),
            ),
        )
        val repository = GitHubReleaseUpdateRepository(httpClient = client)

        val update = repository.latestRelease().availableUpdateOrNull(currentVersionName = "0.3.4")

        requireNotNull(update)
        assertEquals(
            "https://github.com/HuangAntimony/Hoshi-Reader-Android/releases/download/v0.3.5/Hoshi-Reader-v0.3.5.apk",
            update.downloadUrl,
        )
        assertTrue(update.fallbackDownloadUrls.contains("https://gh-proxy.com/https://github.com/HuangAntimony/Hoshi-Reader-Android/releases/download/v0.3.5/Hoshi-Reader-v0.3.5.apk"))
    }

    @Test
    fun failedDownloadRecordAdvancesToNextCandidateUrl() {
        val update = AvailableUpdate(
            versionName = "0.3.5",
            releaseUrl = "https://example.com/releases/tag/v0.3.5",
            assetName = "Hoshi-Reader-v0.3.5.apk",
            downloadUrl = "https://github.com/HuangAntimony/Hoshi-Reader-Android/releases/download/v0.3.5/Hoshi-Reader-v0.3.5.apk",
            fallbackDownloadUrls = listOf(
                "https://gh-proxy.com/https://github.com/HuangAntimony/Hoshi-Reader-Android/releases/download/v0.3.5/Hoshi-Reader-v0.3.5.apk",
                "https://gh.llkk.cc/https://github.com/HuangAntimony/Hoshi-Reader-Android/releases/download/v0.3.5/Hoshi-Reader-v0.3.5.apk",
            ),
            sha256 = null,
        )

        assertEquals(update.downloadUrl, update.downloadUrlAfterFailed(null))
        assertEquals(
            "https://gh-proxy.com/https://github.com/HuangAntimony/Hoshi-Reader-Android/releases/download/v0.3.5/Hoshi-Reader-v0.3.5.apk",
            update.downloadUrlAfterFailed(update.downloadUrl),
        )
        assertEquals(
            "https://gh.llkk.cc/https://github.com/HuangAntimony/Hoshi-Reader-Android/releases/download/v0.3.5/Hoshi-Reader-v0.3.5.apk",
            update.downloadUrlAfterFailed(update.fallbackDownloadUrls.first()),
        )
    }

    @Test
    fun fallsBackToOnlyApkAssetWhenFileNameDoesNotMatchExpectedPattern() {
        val release = GitHubRelease(
            tagName = "0.3.5",
            htmlUrl = "https://example.com/releases/tag/0.3.5",
            assets = listOf(
                GitHubReleaseAsset(
                    name = "hoshi-universal.apk",
                    browserDownloadUrl = "https://example.com/hoshi-universal.apk",
                    digest = null,
                ),
            ),
        )

        val update = release.availableUpdateOrNull(currentVersionName = "0.3.4")

        requireNotNull(update)
        assertEquals("hoshi-universal.apk", update.assetName)
    }

    @Test
    fun rejectsInvalidReleaseVersionAndAmbiguousApkAssets() {
        val invalidVersion = GitHubRelease(
            tagName = "latest",
            htmlUrl = "https://example.com/releases/latest",
            assets = listOf(GitHubReleaseAsset("Hoshi.apk", "https://example.com/Hoshi.apk", null)),
        )
        val ambiguousAssets = GitHubRelease(
            tagName = "v0.3.5",
            htmlUrl = "https://example.com/releases/tag/v0.3.5",
            assets = listOf(
                GitHubReleaseAsset("arm64.apk", "https://example.com/arm64.apk", null),
                GitHubReleaseAsset("universal.apk", "https://example.com/universal.apk", null),
            ),
        )

        assertEquals(null, invalidVersion.availableUpdateOrNull(currentVersionName = "0.3.4"))
        assertEquals(null, ambiguousAssets.availableUpdateOrNull(currentVersionName = "0.3.4"))
    }

    @Test
    fun comparesSemanticVersionsNumerically() {
        assertTrue(AppVersion.parse("v0.10.0")!! > AppVersion.parse("0.9.9")!!)
        assertTrue(AppVersion.parse("0.3.4")!! == AppVersion.parse("v0.3.4")!!)
        assertTrue(AppVersion.parse("0.3.4")!! < AppVersion.parse("0.3.5")!!)
    }

    @Test
    fun checkServiceDownloadsNewVersionsAndSkipsRepeatDownloads() = runBlocking {
        val repository = FakeReleaseRepository(
            GitHubRelease(
                tagName = "v0.3.5",
                htmlUrl = "https://example.com/releases/tag/v0.3.5",
                assets = listOf(
                    GitHubReleaseAsset(
                        name = "Hoshi-Reader-v0.3.5.apk",
                        browserDownloadUrl = "https://example.com/Hoshi-Reader-v0.3.5.apk",
                        digest = "sha256:7977f9e95adec03fce35ef0640fdd2fe662c6521d625dc12242df5b66fb2254b",
                    ),
                ),
            ),
        )
        val downloads = FakeUpdateDownloadController()
        val service = UpdateCheckService(
            currentVersionName = "0.3.4",
            releaseRepository = repository,
            downloadController = downloads,
        )

        val first = service.check(downloadIfAvailable = true)
        val second = service.check(downloadIfAvailable = true)

        assertTrue(first is UpdateCheckOutcome.DownloadStarted)
        assertTrue(second is UpdateCheckOutcome.DownloadInProgress)
        assertEquals(1, downloads.startedDownloads)
    }

    private class FakeReleaseRepository(
        private val release: GitHubRelease,
    ) : ReleaseUpdateRepository {
        override suspend fun latestRelease(): GitHubRelease = release
    }

    private class FakeGitHubHttpClient(
        private val responses: Map<String, String>,
        private val failures: Set<String> = emptySet(),
    ) : GitHubHttpClient {
        val requestedUrls = mutableListOf<String>()

        override fun get(url: String, headers: Map<String, String>): String {
            requestedUrls += url
            if (url in failures) {
                throw GitHubReleaseException("failed")
            }
            return responses.getValue(url)
        }
    }

    private class FakeUpdateDownloadController : UpdateDownloadController {
        var startedDownloads = 0
            private set
        private var status: UpdateDownloadStatus = UpdateDownloadStatus.None

        override suspend fun statusFor(update: AvailableUpdate): UpdateDownloadStatus = status

        override suspend fun enqueue(update: AvailableUpdate): Long {
            startedDownloads += 1
            status = UpdateDownloadStatus.Downloading(42L)
            return 42L
        }
    }
}
