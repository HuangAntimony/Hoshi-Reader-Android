package moe.antimony.hoshi.epub

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class ReadingStatisticsSidecarTest {
    private val json = Json {
        encodeDefaults = true
        ignoreUnknownKeys = true
    }

    @Test
    fun serializesIosStatisticsJsonShapeWithDefaults() {
        val encoded = json.encodeToString(
            ListSerializer(ReadingStatistics.serializer()),
            listOf(ReadingStatistics(title = "Book", dateKey = "2026-05-13")),
        )

        assertEquals(
            """[{"title":"Book","dateKey":"2026-05-13","charactersRead":0,"readingTime":0.0,"minReadingSpeed":0,"altMinReadingSpeed":0,"lastReadingSpeed":0,"maxReadingSpeed":0,"lastStatisticModified":0}]""",
            encoded,
        )
    }

    @Test
    fun decodesIosStatisticsJsonShape() {
        val decoded = json.decodeFromString(
            ListSerializer(ReadingStatistics.serializer()),
            """[{"title":"Book","dateKey":"2026-05-13","charactersRead":42,"readingTime":5.5,"minReadingSpeed":120,"altMinReadingSpeed":180,"lastReadingSpeed":240,"maxReadingSpeed":300,"lastStatisticModified":1778623200000}]""",
        )

        assertEquals(
            ReadingStatistics(
                title = "Book",
                dateKey = "2026-05-13",
                charactersRead = 42,
                readingTime = 5.5,
                minReadingSpeed = 120,
                altMinReadingSpeed = 180,
                lastReadingSpeed = 240,
                maxReadingSpeed = 300,
                lastStatisticModified = 1_778_623_200_000,
            ),
            decoded.single(),
        )
    }

    @Test
    fun repositoryDeduplicatesStatisticsByDateKeyKeepingLatestModifiedEntry() = runBlocking {
        val repository = BookRepository(Files.createTempDirectory("hoshi-statistics").toFile())
        val root = repository.createBookDirectory("book-a")
        root.resolve("statistics.json").writeText(
            """
            [
                {"title":"Book","dateKey":"2026-05-13","charactersRead":10,"readingTime":1.0,"minReadingSpeed":1,"altMinReadingSpeed":1,"lastReadingSpeed":1,"maxReadingSpeed":1,"lastStatisticModified":100},
                {"title":"Book","dateKey":"2026-05-13","charactersRead":20,"readingTime":2.0,"minReadingSpeed":2,"altMinReadingSpeed":2,"lastReadingSpeed":2,"maxReadingSpeed":2,"lastStatisticModified":200},
                {"title":"Book","dateKey":"2026-05-14","charactersRead":30,"readingTime":3.0,"minReadingSpeed":3,"altMinReadingSpeed":3,"lastReadingSpeed":3,"maxReadingSpeed":3,"lastStatisticModified":150}
            ]
            """.trimIndent(),
        )

        val statistics = repository.loadStatistics(root)

        assertEquals(listOf("2026-05-13", "2026-05-14"), statistics.map { it.dateKey }.sorted())
        assertEquals(20, statistics.single { it.dateKey == "2026-05-13" }.charactersRead)
        assertEquals(30, statistics.single { it.dateKey == "2026-05-14" }.charactersRead)
    }

    @Test
    fun repositoryWritesStatisticsJsonUsingIosSidecarName() = runBlocking {
        val repository = BookRepository(Files.createTempDirectory("hoshi-statistics-write").toFile())
        val root = repository.createBookDirectory("book-a")

        repository.saveStatistics(
            root,
            listOf(ReadingStatistics(title = "Book", dateKey = "2026-05-13", charactersRead = 12)),
        )

        assertTrue(root.resolve("statistics.json").isFile)
        assertEquals(12, repository.loadStatistics(root).single().charactersRead)
    }
}
