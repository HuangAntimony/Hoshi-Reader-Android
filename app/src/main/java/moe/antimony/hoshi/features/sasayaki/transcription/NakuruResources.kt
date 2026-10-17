package moe.antimony.hoshi.features.sasayaki.transcription

import android.content.Context
import android.os.Build
import android.os.Process
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.IOException
import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import moe.antimony.hoshi.di.IoDispatcher
import moe.antimony.hoshi.features.sasayaki.SasayakiFailureKind
import moe.antimony.hoshi.features.sasayaki.SasayakiOperationFailure

internal class NakuruResources @Inject constructor(
    @param:ApplicationContext private val context: Context,
    @param:IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) {
    val root = File(context.noBackupFilesDir, "Nakuru")

    suspend fun stores(): List<SasayakiModelStore> {
        val runtime = context.assets.open("nakuru-runtime.json").bufferedReader().use {
            Json.decodeFromString<NakuruRuntimeCatalog>(it.readText()).files
        }
        val abis = if (Process.is64Bit()) Build.SUPPORTED_64_BIT_ABIS else Build.SUPPORTED_32_BIT_ABIS
        val files = runtime[abis.firstOrNull { it in runtime }]
            ?: throw SasayakiOperationFailure(SasayakiFailureKind.NakuruUnavailable, IOException("Unsupported Nakuru ABI"))
        val model = context.assets.open("nakuru-model.json").bufferedReader().use {
            Json.decodeFromString<List<SasayakiModelFile>>(it.readText())
        }
        val groups = listOf(files to true, model to false)
        val stores = groups.map { (entries, readOnly) ->
            SasayakiModelStore(root, HttpSasayakiModelTransport(), ioDispatcher, entries, readOnly = readOnly)
        }
        groups.zip(stores).forEach { (group, store) ->
            if (group.first.any { it.url.isEmpty() } && store.missingBytes() > 0) {
                throw SasayakiOperationFailure(SasayakiFailureKind.NakuruUnavailable, IOException("Nakuru is not installed"))
            }
        }
        return stores
    }
}

@Serializable
private data class NakuruRuntimeCatalog(val files: Map<String, List<SasayakiModelFile>>)
