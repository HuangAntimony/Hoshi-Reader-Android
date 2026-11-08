package moe.antimony.hoshi.features.audio

import android.content.res.AssetFileDescriptor
import android.os.Build
import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.ceil

@RunWith(AndroidJUnit4::class)
class LocalAudioPerformanceDeviceTest {
    @Test
    fun compareFrameworkAndFdQueriesAndVerifyRealCopyLinkModes(): Unit = runBlocking {
        val fixture = RealLocalAudioFixture()
        fixture.requirePersistedReadGrant()
        val directory = Files.createTempDirectory(fixture.evidenceDirectory.toPath(), "benchmark-").toFile()
        val repository = fixture.repository(directory)
        val files = directory.resolve("files/Audio")
        try {
            val resolver = fixture.context.contentResolver
            requireNotNull(resolver.openAssetFileDescriptor(fixture.uri, "r")).use { descriptor ->
                val databaseSize = descriptor.openDatabase().use { it.sizeBytes }
                assertTrue("A scratch copy needs $databaseSize bytes", directory.usableSpace > databaseSize + 256L * 1024 * 1024)
                var progress: LocalAudioImportProgress? = null
                val copyStarted = SystemClock.elapsedRealtimeNanos()
                val copied = repository.importDatabase(fixture.uri, LocalAudioImportMode.Copy) { progress = it }
                val copyElapsed = SystemClock.elapsedRealtimeNanos() - copyStarted
                assertFalse(copied.isExternal)
                assertEquals(databaseSize, copied.sizeBytes)
                assertEquals(databaseSize, progress?.copiedBytes)
                val copyFile = requireNotNull(files.listFiles()).single()
                assertEquals(databaseSize, copyFile.length())
                val (copiedFile, copiedAudio) = repository.readFixtureAudio()
                assertEquals(copied.generation, copiedFile.generation)

                val linkStarted = SystemClock.elapsedRealtimeNanos()
                val linked = repository.importDatabase(fixture.uri, LocalAudioImportMode.Link)
                val linkElapsed = SystemClock.elapsedRealtimeNanos() - linkStarted
                assertTrue(linked.isExternal)
                assertEquals(databaseSize, linked.sizeBytes)
                assertNotEquals(copied.generation, linked.generation)
                assertTrue(repository.hasRetainedPrivateDatabase())
                assertEquals(listOf(copyFile), files.listFiles()?.toList())
                assertNull(repository.loadAudio(copiedFile))
                val (linkedFile, linkedAudio) = repository.readFixtureAudio()
                assertEquals(linked.generation, linkedFile.generation)
                assertEquals(copiedFile.copy(generation = linked.generation), linkedFile)
                assertArrayEquals(copiedAudio, linkedAudio)

                val report = compare(copyFile, descriptor).apply {
                    put("device", Build.MODEL)
                    put("sdk", Build.VERSION.SDK_INT)
                    put("databaseBytes", databaseSize)
                    put("copyImportMs", copyElapsed / 1e6)
                    put("linkImportMs", linkElapsed / 1e6)
                    put("repositoryBlobSha256", linkedAudio.sha256())
                    put("sourceCount", repository.ensureSourceConfig().sourceOrder.size)
                }
                repository.deleteDatabase()
                assertNull(repository.databaseInfo())
                // Reopen through the provider after unlink, rather than using an existing descriptor.
                requireNotNull(resolver.openAssetFileDescriptor(fixture.uri, "r")).use { reopened ->
                    reopened.openDatabase().use { database ->
                        assertArrayEquals(linkedAudio, database.loadAudio(linkedFile.source, linkedFile.file))
                    }
                }
                repository.deleteRetainedPrivateDatabase()
                assertFalse(copyFile.exists())
                val output = fixture.evidenceDirectory.resolve("performance.json")
                output.writeText(report.toString(2))
                Log.i("HoshiLocalAudioPerf", "$output $report")
            }
        } finally {
            try {
                repository.deleteDatabase()
                repository.deleteRetainedPrivateDatabase()
            } finally {
                assertTrue(directory.deleteRecursively())
            }
        }
    }

    private fun compare(path: File, descriptor: AssetFileDescriptor): JSONObject {
        val reusedStart = SystemClock.elapsedRealtimeNanos()
        return descriptor.openDatabase().use { reused ->
            val reusedOpenMs = (SystemClock.elapsedRealtimeNanos() - reusedStart) / 1e6
            val frameworkSamples = Samples()
            val freshSamples = Samples()
            val reusedSamples = Samples()
            val freshStats = LongArray(4)
            val arms: List<(Request) -> Lookup> = listOf(
                { request -> frameworkSamples.measure { readFramework(path, request) } },
                { request ->
                    freshSamples.measure {
                        descriptor.openDatabase().use { database ->
                            readNative(database, request).also {
                                val statistics = database.statistics()
                                for (index in statistics.indices) freshStats[index] += statistics[index]
                            }
                        }
                    }
                },
                { request -> reusedSamples.measure { readNative(reused, request) } },
            )
            // Prime the Android/Java call paths once. The input copy also warms filesystem caches.
            readFramework(path, corpus.first())
            descriptor.openDatabase().use { readNative(it, corpus.first()) }
            readNative(reused, corpus.first())
            val beforeReuse = reused.statistics()
            var hits = 0
            repeat(3) { round ->
                corpus.forEachIndexed { requestIndex, request ->
                    val results = arrayOfNulls<Lookup>(arms.size)
                    for (step in arms.indices) {
                        val arm = (step + requestIndex + round) % arms.size
                        results[arm] = arms[arm](request)
                    }
                    val expected = requireNotNull(results[0])
                    val rows = expected.rows.groupingBy { it }.eachCount()
                    val candidates = LocalAudioResolver.resolveCandidates(request.term, request.reading, expected.rows)
                    for (result in results.drop(1).filterNotNull()) {
                        assertEquals(request.toString(), rows, result.rows.groupingBy { it }.eachCount())
                        assertEquals(
                            request.toString(),
                            candidates,
                            LocalAudioResolver.resolveCandidates(request.term, request.reading, result.rows),
                        )
                        assertArrayEquals(request.toString(), expected.audio, result.audio)
                    }
                    if (expected.audio != null) hits++
                }
            }
            val requests = 3 * corpus.size
            assertTrue("Expected audio for multiple Japanese terms, found $hits of $requests", hits >= 15)
            val reusedStats = reused.statistics()
            assertEquals(beforeReuse[2], reusedStats[2])
            assertEquals(reusedStats[2] * requests, freshStats[2])
            assertEquals(freshStats[3], reusedStats[3] - beforeReuse[3])
            JSONObject().apply {
                put("cacheCondition", "Fresh SQLite connection per request, with uncontrolled OS page cache warmed by the copy")
                put("requestsPerArm", requests)
                put("audioHitsPerArm", hits)
                put("framework", frameworkSamples.json())
                put("freshFd", freshSamples.json().put("io", statsJson(freshStats)))
                put("reusedFd", reusedSamples.json().put("io", statsJson(LongArray(4) { reusedStats[it] - beforeReuse[it] })))
                put("reusedFdOpenMs", reusedOpenMs)
                put("reusedFdStatementsAtOpen", beforeReuse[2])
            }
        }
    }

    private fun readNative(database: LocalAudioNativeDatabase, request: Request) = readLookup(
        request,
        database.queryEntries(request.term, request.reading),
        database::loadAudio,
    )

    private fun readFramework(path: File, request: Request): Lookup {
        val rows = openFrameworkAudioDatabase(path).use { frameworkAudioEntries(it, request.term, request.reading) }
        return readLookup(request, rows) { source, file ->
            openFrameworkAudioDatabase(path).use { database ->
                database.query("android", arrayOf("data"), "source = ? AND file = ?", arrayOf(source, file), null, null, null, "1").use {
                    if (it.moveToFirst()) it.getBlob(0) else null
                }
            }
        }
    }

    private fun readLookup(request: Request, rows: List<LocalAudioEntry>, audio: (String, String) -> ByteArray?): Lookup {
        val candidate = LocalAudioResolver.resolve(request.term, request.reading, rows)
        return Lookup(rows, candidate?.let { audio(it.source, it.file) })
    }

    private fun AssetFileDescriptor.openDatabase(): LocalAudioNativeDatabase =
        LocalAudioNativeDatabase.open(parcelFileDescriptor.fd, startOffset, declaredLength)

    private fun statsJson(stats: LongArray) = JSONObject().apply {
        put("preadCalls", stats[0])
        put("preadBytes", stats[1])
        put("statementsPrepared", stats[2])
        put("queries", stats[3])
    }

    private class Samples {
        private val nanos = mutableListOf<Long>()

        fun measure(action: () -> Lookup): Lookup {
            val start = SystemClock.elapsedRealtimeNanos()
            val value = action()
            nanos += SystemClock.elapsedRealtimeNanos() - start
            return value
        }

        fun json(): JSONObject {
            val sorted = nanos.sorted()
            fun percentile(fraction: Double) = sorted[ceil(fraction * sorted.size).toInt() - 1] / 1e6
            return JSONObject().apply {
                put("p50Ms", percentile(.50))
                put("p95Ms", percentile(.95))
            }
        }
    }

    private data class Lookup(val rows: List<LocalAudioEntry>, val audio: ByteArray?)
    private data class Request(val term: String, val reading: String)

    private val corpus = listOf(
        Request("食べる", "たべる"), Request("帯広", "おびひろ"), Request("帯封", "おびふう"),
        Request("お姫様", "おひめさま"), Request("お冷や", "おひや"), Request("図書館", "としょかん"),
        Request("電車", "でんしゃ"), Request("勉強", "べんきょう"), Request("日本", "にほん"),
        Request("買い物", "かいもの"), Request("猫", "ねこ"), Request("夕焼け", "ゆうやけ"),
        Request("桜", "さくら"), Request("海岸", "かいがん"), Request("山道", "やまみち"),
        Request("病院", "びょういん"), Request("図鑑", "ずかん"), Request("気温", "きおん"),
        Request("風景", "ふうけい"), Request("朝日", "あさひ"), Request("旅行", "りょこう"),
        Request("時計", ""), Request("お姫様", ""), Request("__hoshi_audio_no_match__", ""),
    )
}
