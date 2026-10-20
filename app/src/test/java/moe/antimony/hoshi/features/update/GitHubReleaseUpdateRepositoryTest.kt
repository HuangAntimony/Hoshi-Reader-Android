package moe.antimony.hoshi.features.update

import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import moe.antimony.hoshi.R
import moe.antimony.hoshi.ui.ErrorDiagnosticEvents
import moe.antimony.hoshi.ui.UiText
import moe.antimony.hoshi.ui.resolve
import moe.antimony.hoshi.ui.withErrorDetails
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class GitHubReleaseUpdateRepositoryTest {
    @Test
    fun failedReleaseRequestPreservesItsUrlAndUnderlyingCauseChain() = runBlocking {
        val failure = java.io.IOException("Release request failed", IllegalStateException("TLS certificate expired"))
        val repository = GitHubReleaseUpdateRepository(
            apiMirrorPrefixes = emptyList(),
            httpClient = object : GitHubHttpClient {
                override fun get(url: String, headers: Map<String, String>): String = throw failure
            },
        )
        val result = runCatching { repository.latestRelease() }
        assertSame(failure, result.exceptionOrNull()?.cause)
        assertEquals("TLS certificate expired", result.exceptionOrNull()?.cause?.cause?.message)
        assertTrue(result.exceptionOrNull()?.message.orEmpty().contains(GitHubReleaseUpdateRepository.LatestReleaseUrl))
    }

    @Test
    fun failedApiCandidatesRetainEveryUrlAndOriginalCauseInVisibleDiagnostics() = runBlocking {
        ErrorDiagnosticEvents.buffer.drain()
        try {
            val direct = GitHubReleaseUpdateRepository.LatestReleaseUrl
            val mirror = "https://ghproxy.vip/$direct"
            val failures = mapOf(
                direct to IOException("Release request failed", IllegalStateException("TLS certificate expired")),
                mirror to IOException("HTTP 403", IllegalStateException("API rate limit exceeded")),
            )
            val requestedUrls = mutableListOf<String>()
            val repository = GitHubReleaseUpdateRepository(httpClient = object : GitHubHttpClient {
                override fun get(url: String, headers: Map<String, String>): String {
                    requestedUrls += url
                    throw failures[url] ?: IOException("Unexpected API mirror: $url")
                }
            })

            val failure = requireNotNull(runCatching { repository.latestRelease() }.exceptionOrNull())
            val text = UiText.Resource(R.string.about_update_check_failed).withErrorDetails(failure)
                .resolve({ _, _ -> "Unable to check updates" }, { _, _, _ -> "" })

            assertEquals(listOf(direct, mirror), requestedUrls)
            listOf(direct, mirror, "Release request failed", "TLS certificate expired", "HTTP 403", "API rate limit exceeded")
                .forEach { assertTrue("Missing $it in $text", text.contains(it)) }
            assertEquals(failures.values.toList(), failure.suppressed.toList())
            assertTrue(failure.stackTraceToString().contains("TLS certificate expired"))
            assertTrue(failure.stackTraceToString().contains("API rate limit exceeded"))
            assertSame(failure, ErrorDiagnosticEvents.buffer.drain().single().error)
        } finally {
            ErrorDiagnosticEvents.buffer.drain()
        }
    }

    @Test
    fun cancelledReleaseRequestDoesNotTryAnotherSource() = runBlocking {
        val cancelled = CancellationException("Check cancelled")
        val requestedUrls = mutableListOf<String>()
        val repository = GitHubReleaseUpdateRepository(httpClient = object : GitHubHttpClient {
            override fun get(url: String, headers: Map<String, String>): String {
                requestedUrls += url
                throw cancelled
            }
        })

        assertSame(cancelled, runCatching { repository.latestRelease() }.exceptionOrNull())
        assertEquals(listOf(GitHubReleaseUpdateRepository.LatestReleaseUrl), requestedUrls)
    }

    @Test
    fun rateLimitedResponseKeepsOriginalBodyAndRateLimitHeaders() = runBlocking {
        MockWebServer().use { server ->
            val body = """{"message":"API rate limit exceeded for test address"}"""
            server.enqueue(MockResponse().setResponseCode(403)
                .setHeader("Content-Type", "application/json")
                .setHeader("Retry-After", "60")
                .setHeader("X-RateLimit-Remaining", "0")
                .setHeader("X-RateLimit-Reset", "1792200000")
                .setBody(body))
            val url = server.url("/repos/example/reader/releases/latest").toString()
            val repository = GitHubReleaseUpdateRepository(latestReleaseUrl = url,
                apiMirrorPrefixes = emptyList(), httpClient = UrlConnectionGitHubHttpClient)

            val failure = requireNotNull(runCatching { repository.latestRelease() }.exceptionOrNull())
            val text = failure.stackTraceToString()
            listOf(url, "HTTP 403", "Content-Type: application/json", "Retry-After: 60",
                "X-RateLimit-Remaining: 0", "X-RateLimit-Reset: 1792200000", body)
                .forEach { assertTrue("Missing $it in $text", text.contains(it)) }
        }
    }

    @Test
    fun redirectedHtmlResponseKeepsBothUrlsAndOriginalResponseWithoutJsonParsing() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", server.url("/")))
            val body = "<!DOCTYPE html><html lang=\"zh-CN\"><title>Proxy homepage</title></html>"
            server.enqueue(MockResponse().setHeader("Content-Type", "text/html; charset=utf-8").setBody(body))
            val url = server.url("/releases/latest").toString()
            val repository = GitHubReleaseUpdateRepository(latestReleaseUrl = url,
                apiMirrorPrefixes = emptyList(), httpClient = UrlConnectionGitHubHttpClient)

            val failure = requireNotNull(runCatching { repository.latestRelease() }.exceptionOrNull())
            val text = failure.stackTraceToString()
            listOf(url, server.url("/").toString(), "HTTP 200", "Content-Type: text/html; charset=utf-8", body)
                .forEach { assertTrue("Missing $it in $text", text.contains(it)) }
            assertTrue("HTML must be rejected before the release JSON parser", !text.contains("JsonDecodingException"))
        }
    }

    @Test
    fun normalJsonResponseAndRequestHeadersArePreserved() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setHeader("Content-Type", "application/json; charset=utf-8")
                .setBody("""{"tag_name":"v1.4.0","html_url":"https://github.com/example/reader/releases/tag/v1.4.0"}"""))
            val repository = GitHubReleaseUpdateRepository(latestReleaseUrl = server.url("/releases/latest").toString(),
                apiMirrorPrefixes = emptyList(), httpClient = UrlConnectionGitHubHttpClient)

            assertEquals("v1.4.0", repository.latestRelease().tagName)
            val request = server.takeRequest()
            assertEquals("application/vnd.github+json", request.getHeader("Accept"))
            assertEquals("Hoshi-Reader-Android", request.getHeader("User-Agent"))
        }
    }

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
    fun selectsArm64ReleaseAssetForArm64Devices() {
        val release = splitAbiRelease()

        val update = release.availableUpdateOrNull(
            currentVersionName = "0.3.4",
            supportedAbis = listOf("arm64-v8a", "armeabi-v7a"),
        )

        requireNotNull(update)
        assertEquals("Hoshi-Reader-v0.3.5-arm64-v8a.apk", update.assetName)
        assertEquals("https://example.com/Hoshi-Reader-v0.3.5-arm64-v8a.apk", update.downloadUrl)
    }

    @Test
    fun selectsV7aReleaseAssetForThirtyTwoBitArmDevices() {
        val release = splitAbiRelease()

        val update = release.availableUpdateOrNull(
            currentVersionName = "0.3.4",
            supportedAbis = listOf("armeabi-v7a"),
        )

        requireNotNull(update)
        assertEquals("Hoshi-Reader-v0.3.5-armeabi-v7a.apk", update.assetName)
    }

    @Test
    fun prefersMatchingAbiAssetOverLegacyArm64AliasDuringTransition() {
        val release = splitAbiRelease().copy(
            assets = listOf(
                GitHubReleaseAsset(
                    name = "Hoshi-Reader-v0.3.5.apk",
                    browserDownloadUrl = "https://example.com/Hoshi-Reader-v0.3.5.apk",
                    digest = null,
                ),
            ) + splitAbiRelease().assets,
        )

        val update = release.availableUpdateOrNull(
            currentVersionName = "0.3.4",
            supportedAbis = listOf("armeabi-v7a"),
        )

        requireNotNull(update)
        assertEquals("Hoshi-Reader-v0.3.5-armeabi-v7a.apk", update.assetName)
    }

    @Test
    fun returnsNoUpdateWhenSplitAbiReleaseHasNoCompatibleAsset() {
        val release = splitAbiRelease()

        val update = release.availableUpdateOrNull(
            currentVersionName = "0.3.4",
            supportedAbis = listOf("x86_64"),
        )

        assertEquals(null, update)
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

    private fun splitAbiRelease(): GitHubRelease =
        GitHubRelease(
            tagName = "v0.3.5",
            htmlUrl = "https://example.com/releases/tag/v0.3.5",
            assets = listOf(
                GitHubReleaseAsset(
                    name = "Hoshi-Reader-v0.3.5-arm64-v8a.apk",
                    browserDownloadUrl = "https://example.com/Hoshi-Reader-v0.3.5-arm64-v8a.apk",
                    digest = null,
                ),
                GitHubReleaseAsset(
                    name = "Hoshi-Reader-v0.3.5-armeabi-v7a.apk",
                    browserDownloadUrl = "https://example.com/Hoshi-Reader-v0.3.5-armeabi-v7a.apk",
                    digest = null,
                ),
                GitHubReleaseAsset(
                    name = "LICENSE",
                    browserDownloadUrl = "https://example.com/LICENSE",
                    digest = null,
                ),
            ),
        )

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
}
