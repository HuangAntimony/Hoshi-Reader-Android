package moe.antimony.hoshi.dictionary

import org.junit.Assert.assertArrayEquals
import org.junit.Test
import java.io.File

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

    private class RecordingDictionaryNativeBridge : DictionaryNativeBridge {
        lateinit var termPaths: Array<String>
        lateinit var freqPaths: Array<String>
        lateinit var pitchPaths: Array<String>

        override fun importDictionary(zipPath: String, outputDir: String): NativeDictionaryImportResult =
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
