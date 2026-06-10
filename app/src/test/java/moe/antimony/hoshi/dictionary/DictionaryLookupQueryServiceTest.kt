package moe.antimony.hoshi.dictionary

import de.manhhao.hoshi.DictionaryStyle
import de.manhhao.hoshi.GlossaryEntry
import de.manhhao.hoshi.LookupResult
import de.manhhao.hoshi.TermResult
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DictionaryLookupQueryServiceTest {
    @Test
    fun rebuildForwardsEnabledPathsByDictionaryTypeToNativeBridge() {
        val bridge = RecordingDictionaryNativeBridge()
        val service = DictionaryLookupQueryService(bridge)

        service.rebuild(
            termDictionaries = listOf(File("/dicts/Term/JMdict")),
            frequencyDictionaries = listOf(File("/dicts/Frequency/Freq")),
            pitchDictionaries = listOf(File("/dicts/Pitch/Pitch")),
        )

        assertArrayEquals(arrayOf("/dicts/Term/JMdict"), bridge.termPaths)
        assertArrayEquals(arrayOf("/dicts/Frequency/Freq"), bridge.freqPaths)
        assertArrayEquals(arrayOf("/dicts/Pitch/Pitch"), bridge.pitchPaths)
    }

    @Test
    fun rebuildPublishesNewQueryWithoutMutatingCurrentQueryInPlace() {
        val bridge = RecordingDictionaryNativeBridge()
        val service = DictionaryLookupQueryService(bridge)

        service.rebuild(
            termDictionaries = listOf(File("/dicts/Term/Old")),
            frequencyDictionaries = emptyList(),
            pitchDictionaries = emptyList(),
        )
        val oldResult = service.lookup("食べる").single().term.glossaries.single().glossary

        service.rebuild(
            termDictionaries = listOf(File("/dicts/Term/New")),
            frequencyDictionaries = emptyList(),
            pitchDictionaries = emptyList(),
        )

        assertEquals("session-1:/dicts/Term/Old", oldResult)
        assertEquals("session-2:/dicts/Term/New", service.lookup("食べる").single().term.glossaries.single().glossary)
        assertEquals(listOf(1L), bridge.destroyedSessions)
    }

    @Test
    fun failedRebuildKeepsCurrentQueryAvailable() {
        val bridge = RecordingDictionaryNativeBridge()
        val service = DictionaryLookupQueryService(bridge)

        service.rebuild(
            termDictionaries = listOf(File("/dicts/Term/Stable")),
            frequencyDictionaries = emptyList(),
            pitchDictionaries = emptyList(),
        )
        bridge.failNextRebuild = true

        val failure = runCatching {
            service.rebuild(
                termDictionaries = listOf(File("/dicts/Term/Broken")),
                frequencyDictionaries = emptyList(),
                pitchDictionaries = emptyList(),
            )
        }

        assertTrue(failure.isFailure)
        assertEquals("session-1:/dicts/Term/Stable", service.lookup("食べる").single().term.glossaries.single().glossary)
        assertEquals(listOf(2L), bridge.destroyedSessions)
    }

    @Test
    fun rebuildDoesNotDestroyPreviousQueryWhileLookupIsReadingIt() {
        val lookupStarted = CountDownLatch(1)
        val releaseLookup = CountDownLatch(1)
        val bridge = RecordingDictionaryNativeBridge(
            onLookup = { session ->
                if (session == 1L) {
                    lookupStarted.countDown()
                    assertTrue(releaseLookup.await(5, TimeUnit.SECONDS))
                }
            },
        )
        val service = DictionaryLookupQueryService(bridge)
        service.rebuild(
            termDictionaries = listOf(File("/dicts/Term/Old")),
            frequencyDictionaries = emptyList(),
            pitchDictionaries = emptyList(),
        )

        val lookupThread = thread(start = true) {
            service.lookup("食べる")
        }
        assertTrue(lookupStarted.await(5, TimeUnit.SECONDS))

        val rebuildThread = thread(start = true) {
            service.rebuild(
                termDictionaries = listOf(File("/dicts/Term/New")),
                frequencyDictionaries = emptyList(),
                pitchDictionaries = emptyList(),
            )
        }
        Thread.sleep(100)

        assertFalse(bridge.destroyedSessions.contains(1L))
        releaseLookup.countDown()
        lookupThread.join(5_000)
        rebuildThread.join(5_000)
        assertEquals(listOf(1L), bridge.destroyedSessions)
        assertEquals("session-2:/dicts/Term/New", service.lookup("食べる").single().term.glossaries.single().glossary)
    }

    private class RecordingDictionaryNativeBridge : DictionaryNativeBridge {
        constructor()

        constructor(onLookup: (Long) -> Unit) {
            this.onLookup = onLookup
        }

        lateinit var termPaths: Array<String>
        lateinit var freqPaths: Array<String>
        lateinit var pitchPaths: Array<String>
        val destroyedSessions = mutableListOf<Long>()
        var failNextRebuild = false
        private var nextSession = 1L
        private val sessionTermPaths = mutableMapOf<Long, Array<String>>()
        private var onLookup: (Long) -> Unit = {}

        override fun importDictionary(zipPath: String, outputDir: String, lowRam: Boolean): NativeDictionaryImportResult =
            NativeDictionaryImportResult(
                success = true,
                title = "",
                termCount = 1,
                metaCount = 0,
                freqCount = 0,
                pitchCount = 0,
                mediaCount = 0,
            )

        override fun createLookupObject(): Long = nextSession++

        override fun destroyLookupObject(session: Long) {
            destroyedSessions += session
        }

        override fun rebuildQuery(
            session: Long,
            termPaths: Array<String>,
            freqPaths: Array<String>,
            pitchPaths: Array<String>,
        ) {
            if (failNextRebuild) {
                failNextRebuild = false
                error("Unable to rebuild query.")
            }
            this.termPaths = termPaths
            this.freqPaths = freqPaths
            this.pitchPaths = pitchPaths
            sessionTermPaths[session] = termPaths
        }

        override fun lookup(session: Long, text: String, maxResults: Int, scanLength: Int): List<LookupResult> {
            onLookup(session)
            val termPath = sessionTermPaths.getValue(session).singleOrNull().orEmpty()
            return listOf(
                LookupResult(
                    matched = text,
                    deinflected = text,
                    process = emptyArray(),
                    term = TermResult(
                        expression = text,
                        reading = text,
                        rules = "",
                        glossaries = arrayOf(
                            GlossaryEntry(
                                dictName = "Test",
                                glossary = "session-$session:$termPath",
                                definitionTags = "",
                                termTags = "",
                            ),
                        ),
                        frequencies = emptyArray(),
                        pitches = emptyArray(),
                    ),
                    preprocessorSteps = 0,
                ),
            )
        }

        override fun getStyles(session: Long): List<DictionaryStyle> = emptyList()

        override fun getMediaFile(session: Long, dictionary: String, path: String): ByteArray? = null
    }
}
