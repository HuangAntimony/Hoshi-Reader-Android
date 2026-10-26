package moe.antimony.hoshi.features.anki

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertTrue
import org.junit.Test

class HoshiFileProviderConfigurationTest {
    @Test
    fun declaresTtsExportDirectoryForSentenceAudioSharing() {
        val document = DocumentBuilderFactory.newInstance()
            .newDocumentBuilder()
            .parse(File("src/main/res/xml/file_paths.xml"))
        val paths = document.getElementsByTagName("cache-path")

        assertTrue(
            "TTS sentence exports must be shareable with AnkiDroid without exposing all TTS cache files",
            (0 until paths.length).any { index ->
                paths.item(index).attributes.getNamedItem("path").nodeValue.trimEnd('/') == "tts/export"
            },
        )
    }
}
