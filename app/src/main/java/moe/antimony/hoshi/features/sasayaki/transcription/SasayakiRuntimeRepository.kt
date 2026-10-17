package moe.antimony.hoshi.features.sasayaki.transcription

import android.content.Context
import android.os.Build
import android.os.Process
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import moe.antimony.hoshi.di.IoDispatcher

@Singleton
internal class SasayakiRuntimeRepository @Inject constructor(
    @param:ApplicationContext private val context: Context,
    @param:IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) {
    // All descriptors and hashes come from the installed APK, never a remote manifest.
    suspend fun store(includeInference: Boolean = true): SasayakiModelStore = withContext(ioDispatcher) {
        val catalog = transcriptionRuntimeCatalog(context)
        val supported = if (Process.is64Bit()) Build.SUPPORTED_64_BIT_ABIS else Build.SUPPORTED_32_BIT_ABIS
        val abi = supported.firstOrNull { it in catalog } ?: throw IOException("Unsupported transcription ABI")
        SasayakiModelStore(File(context.noBackupFilesDir, "SasayakiRuntime/$abi"),
            HttpSasayakiModelTransport(), ioDispatcher, catalog.getValue(abi).let { if (includeInference) it else it.takeLast(1) }, readOnly = true)
    }

    suspend fun load(directory: File, includeInference: Boolean = true) = withContext(ioDispatcher) {
        val catalog = transcriptionRuntimeCatalog(context)
        val files = catalog.getValue(directory.name).map { File(directory, it.name) }
        if (includeInference) SasayakiNativeLibraries.prepare(files) else SasayakiNativeLibraries.prepareAudio(files.last())
    }
}

/** Called by the official bindings whose System.loadLibrary calls are redirected at build time. */
internal object SasayakiNativeLibraries {
    private var loaded: List<String>? = null
    private var audioLoaded = false

    @Synchronized fun prepareAudio(file: File) {
        if (!audioLoaded) System.load(file.absolutePath)
        audioLoaded = true
    }

    @Synchronized fun prepare(files: List<File>) {
        val paths = files.map { it.absolutePath }
        if (loaded == paths) return
        check(loaded == null) { "A different runtime is already loaded" }
        // ONNX must be available before the JNI library's DT_NEEDED dependency resolves.
        paths.forEach(System::load)
        loaded = paths
        audioLoaded = true
    }

    @JvmStatic @Synchronized fun loadLibrary(name: String) {
        check((name == "hoshiaudio_jni" && audioLoaded) || (name == "sherpa-onnx-jni" && loaded != null)) { "Transcription runtime must be prepared first" }
    }
}

@Serializable
private data class SasayakiAudioRuntimeCatalog(val sourceSha256: String, val files: Map<String, List<SasayakiModelFile>>)

internal fun transcriptionRuntimeCatalog(context: Context): Map<String, List<SasayakiModelFile>> {
    val inference = context.assets.open("transcription-runtime.json").bufferedReader().use {
        Json.decodeFromString<Map<String, List<SasayakiModelFile>>>(it.readText())
    }
    val audio = context.assets.open("transcription-audio-runtime.json").bufferedReader().use {
        Json.decodeFromString<SasayakiAudioRuntimeCatalog>(it.readText()).files
    }
    return inference.mapValues { (abi, files) -> files + audio.getValue(abi) }
}
