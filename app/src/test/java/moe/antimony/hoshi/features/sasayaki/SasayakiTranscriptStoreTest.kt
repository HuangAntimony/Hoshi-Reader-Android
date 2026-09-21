package moe.antimony.hoshi.features.sasayaki

import java.io.IOException
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.StandardTestDispatcher
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SasayakiTranscriptStoreTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun readsIosTranscriptAndAtomicallyReplacesCheckpoint() = runTest {
        val root = temporary.newFolder()
        val file = root.resolve("sasayaki_transcript.json")
        file.writeText("""{"through":12.5,"duration":60,"tokens":[{"text":"今日は","start":10,"end":12}],"future":true}""")
        val store = SasayakiTranscriptStore(StandardTestDispatcher(testScheduler))
        val loaded = store.load(root)!!
        assertEquals(12.5, loaded.through, 0.0)
        assertEquals("今日は", loaded.tokens.single().text)
        val next = loaded.copy(through = 30.0)
        store.save(root, next)
        assertEquals(next, store.load(root))
        assertEquals(listOf("sasayaki_transcript.json"), root.list()!!.toList())
    }

    @Test fun clearingTranscriptPreservesMatch() = runTest {
        val root = temporary.newFolder()
        root.resolve("sasayaki_match.json").writeText("existing match")
        val store = SasayakiTranscriptStore(StandardTestDispatcher(testScheduler))
        store.save(root, SasayakiTranscript(1.0, 5.0, emptyList()))
        assertTrue(root.resolve("sasayaki_transcript.json").isFile)
        store.clear(root)
        assertNull(store.load(root))
        assertEquals("existing match", root.resolve("sasayaki_match.json").readText())
    }

    @Test fun checkpointCannotRecreateDeletedBook() = runTest {
        val root = temporary.newFolder().also { it.delete() }
        val store = SasayakiTranscriptStore(StandardTestDispatcher(testScheduler))
        try {
            store.save(root, SasayakiTranscript(1.0, 5.0, emptyList()))
            fail("A deleted book must not be recreated")
        } catch (_: IOException) { }
        assertFalse(root.exists())
    }

    @Test fun invalidCheckpointIsRejectedWithoutDiscardingValidFile() = runTest {
        val root = temporary.newFolder()
        val store = SasayakiTranscriptStore(StandardTestDispatcher(testScheduler))
        val original = SasayakiTranscript(1.0, 5.0, listOf(SasayakiToken("音", 0.2, 0.8)))
        store.save(root, original)
        try {
            store.save(root, original.copy(through = Double.NaN))
            fail("Nonfinite progress must be rejected")
        } catch (_: IllegalArgumentException) { }
        assertEquals(original, store.load(root))
    }
}
