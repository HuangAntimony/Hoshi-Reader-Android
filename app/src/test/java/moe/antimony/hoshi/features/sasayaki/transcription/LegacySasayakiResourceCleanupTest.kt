package moe.antimony.hoshi.features.sasayaki.transcription

import java.io.File
import java.io.IOException
import java.nio.file.Files
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import moe.antimony.hoshi.ui.ErrorDiagnosticBuffer
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class LegacySasayakiResourceCleanupTest {
    @get:Rule val temporary = TemporaryFolder()
    private val hash = "a".repeat(64)
    private fun seed(root: File, path: String): File = root.resolve(path).apply {
        parentFile.mkdirs()
        writeText("keep or delete")
    }

    @Test fun removesOnlyRetiredModelsAndInferenceLibrariesIncludingPartialDownloads() = runTest {
        val root = temporary.newFolder()
        val retired = mutableListOf(
            seed(root, "SasayakiModels/reazonspeech-k2-v2-int8-v1/encoder.int8.onnx"),
            seed(root, "SasayakiModels/reazonspeech-k2-v2-int8-v1/decoder.int8.onnx123.part"),
        )
        val preserved = mutableListOf(
            seed(root, "Nakuru/nakuru.pack"), seed(root, "Nakuru/libnakuru.so"),
            seed(root, "SasayakiModels/other/model.onnx"),
            seed(root, "Books/book/sasayaki_transcript.json"),
            seed(root, "Books/book/sasayaki_match.json"), seed(root, "Books/book/audio.m4b"),
        )
        for (abi in listOf("arm64-v8a", "armeabi-v7a", "x86_64")) {
            for (name in listOf("libonnxruntime.so", "libsherpa-onnx-jni.so")) {
                retired += seed(root, "SasayakiRuntime/$abi/$abi-$hash-$name")
                retired += seed(root, "SasayakiRuntime/$abi/$abi-$hash-${name}123.part")
            }
            preserved += seed(root, "SasayakiRuntime/$abi/$abi-$hash-libhoshiaudio_jni.so")
            preserved += seed(root, "SasayakiRuntime/$abi/$abi-$hash-libhoshiaudio_jni.so123.part")
            preserved += seed(root, "SasayakiRuntime/$abi/unrelated.so")
        }
        val events = ErrorDiagnosticBuffer()
        val cleanup = LegacySasayakiResourceCleanup(root, StandardTestDispatcher(testScheduler), events)
        cleanup.clean()
        cleanup.clean()
        assertTrue(retired.none { it.exists() })
        assertFalse(root.resolve("SasayakiModels/reazonspeech-k2-v2-int8-v1").exists())
        assertTrue(preserved.all { it.readText() == "keep or delete" })
        assertTrue(events.drain().isEmpty())
    }

    @Test fun failureKeepsOriginalCauseAndOtherFilesAreCleanedBeforeRetry() = runTest {
        val root = temporary.newFolder()
        val blocked = seed(root, "SasayakiModels/reazonspeech-k2-v2-int8-v1/encoder.int8.onnx")
        val other = seed(root, "SasayakiRuntime/arm64-v8a/arm64-v8a-$hash-libonnxruntime.so")
        val original = IOException("Cannot unlink encoder", IOException("Storage is read only"))
        val events = ErrorDiagnosticBuffer()
        var fail = true
        val cleanup = LegacySasayakiResourceCleanup(root, StandardTestDispatcher(testScheduler), events) {
            if (it == blocked.toPath() && fail) throw original
            Files.deleteIfExists(it)
        }
        cleanup.clean()
        assertTrue(blocked.exists())
        assertFalse(other.exists())
        val event = events.drain().single()
        assertSame(original, event.error)
        assertEquals("Storage is read only", event.error!!.cause!!.message)
        assertTrue(event.operation.toString().contains("encoder.int8.onnx"))
        fail = false
        cleanup.clean()
        assertFalse(blocked.exists())
        assertTrue(events.drain().isEmpty())
    }

    @Test fun doesNotFollowDirectorySymlinksIntoUserFiles() = runTest {
        val root = temporary.newFolder()
        val outside = temporary.newFolder()
        val saved = seed(outside, "audio.m4b")
        val model = root.resolve("SasayakiModels/reazonspeech-k2-v2-int8-v1").apply { mkdirs() }
        Files.createSymbolicLink(model.resolve("linked-directory").toPath(), outside.toPath())
        root.resolve("SasayakiRuntime").mkdirs()
        Files.createSymbolicLink(root.resolve("SasayakiRuntime/arm64-v8a").toPath(), outside.toPath())
        val events = ErrorDiagnosticBuffer()
        LegacySasayakiResourceCleanup(root, StandardTestDispatcher(testScheduler), events).clean()
        assertEquals("keep or delete", saved.readText())
        assertFalse(model.exists())
        assertTrue(events.drain().isEmpty())
    }

    @Test fun cancellationIsPropagatedWithoutDiagnostic() = runTest {
        val root = temporary.newFolder()
        seed(root, "SasayakiModels/reazonspeech-k2-v2-int8-v1/tokens.txt")
        val events = ErrorDiagnosticBuffer()
        val original = CancellationException("Stop cleanup")
        try {
            LegacySasayakiResourceCleanup(root, StandardTestDispatcher(testScheduler), events) { throw original }.clean()
            fail("Cancellation was swallowed")
        } catch (error: CancellationException) {
            assertEquals("Stop cleanup", error.message)
        }
        assertTrue(events.drain().isEmpty())
    }
}
