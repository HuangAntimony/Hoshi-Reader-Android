package moe.antimony.hoshi.features.sync

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

@Serializable
data class DriveSyncSettings(
    val isEnabled: Boolean = false,
    val autoSyncOnOpen: Boolean = false,
    val autoSyncOnBookmark: Boolean = false,
    val accountEmail: String? = null,
    val lastStatus: String? = null,
)

class DriveSyncSettingsStore(private val file: File) {
    @OptIn(ExperimentalSerializationApi::class)
    private val json = Json {
        prettyPrint = true
        prettyPrintIndent = "    "
        encodeDefaults = true
        ignoreUnknownKeys = true
    }

    fun load(): DriveSyncSettings {
        if (!file.isFile) return DriveSyncSettings()
        return runCatching {
            json.decodeFromString<DriveSyncSettings>(file.readText())
        }.getOrDefault(DriveSyncSettings())
    }

    fun save(settings: DriveSyncSettings) {
        file.parentFile?.mkdirs()
        file.writeText(json.encodeToString(settings))
    }
}
