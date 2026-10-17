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
    suspend fun store(): SasayakiModelStore = withContext(ioDispatcher) {
        val catalog = transcriptionRuntimeCatalog(context)
        val supported = if (Process.is64Bit()) Build.SUPPORTED_64_BIT_ABIS else Build.SUPPORTED_32_BIT_ABIS
        val abi = supported.firstOrNull { it in catalog } ?: throw IOException("Unsupported transcription ABI")
        SasayakiModelStore(File(context.noBackupFilesDir, "SasayakiRuntime/$abi"),
            HttpSasayakiModelTransport(), ioDispatcher, catalog.getValue(abi), readOnly = true)
    }

    suspend fun load(directory: File) = withContext(ioDispatcher) {
        val catalog = transcriptionRuntimeCatalog(context)
        val files = catalog.getValue(directory.name).map { File(directory, it.name) }
        SasayakiNativeLibraries.prepareAudio(files.single())
    }
}

/** The decoder may only be opened after its verified private library is loaded. */
internal object SasayakiNativeLibraries {
    private var audioLoaded = false

    @Synchronized fun prepareAudio(file: File) {
        if (!audioLoaded) System.load(file.absolutePath)
        audioLoaded = true
    }

    @Synchronized fun requireAudio() {
        check(audioLoaded) { "Transcription audio runtime must be prepared first" }
    }
}

@Serializable
private data class SasayakiAudioRuntimeCatalog(val sourceSha256: String, val files: Map<String, List<SasayakiModelFile>>)

internal fun transcriptionRuntimeCatalog(context: Context): Map<String, List<SasayakiModelFile>> {
    return context.assets.open("transcription-audio-runtime.json").bufferedReader().use {
        Json.decodeFromString<SasayakiAudioRuntimeCatalog>(it.readText()).files
    }
}
