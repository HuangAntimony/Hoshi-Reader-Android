package moe.antimony.hoshi.dictionary

import org.junit.Assert.assertArrayEquals
import org.junit.Test
import java.io.File

class DictionaryLookupQueryServiceTest {
    @Test
    fun rebuildForwardsEnabledPathsByDictionaryTypeToNativeBridge() {
        val bridge = RecordingDictionaryNativeBridge()
        val service = DictionaryLookupQueryService(bridge)
        val termDictionary = File("/dicts/Term/JMdict")
        val frequencyDictionary = File("/dicts/Frequency/Freq")
        val pitchDictionary = File("/dicts/Pitch/Pitch")

        service.rebuild(
            termDictionaries = listOf(termDictionary),
            frequencyDictionaries = listOf(frequencyDictionary),
            pitchDictionaries = listOf(pitchDictionary),
        )

        assertArrayEquals(arrayOf(termDictionary.absolutePath), bridge.termPaths)
        assertArrayEquals(arrayOf(frequencyDictionary.absolutePath), bridge.freqPaths)
        assertArrayEquals(arrayOf(pitchDictionary.absolutePath), bridge.pitchPaths)
    }

    private class RecordingDictionaryNativeBridge : DictionaryNativeBridge {
        lateinit var termPaths: Array<String>
        lateinit var freqPaths: Array<String>
        lateinit var pitchPaths: Array<String>

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

        override fun rebuildQuery(
            termPaths: Array<String>,
            freqPaths: Array<String>,
            pitchPaths: Array<String>,
        ) {
            this.termPaths = termPaths
            this.freqPaths = freqPaths
            this.pitchPaths = pitchPaths
        }
    }
}
