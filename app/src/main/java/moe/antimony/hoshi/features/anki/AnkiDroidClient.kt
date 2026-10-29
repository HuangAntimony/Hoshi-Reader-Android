package moe.antimony.hoshi.features.anki

import kotlinx.serialization.Serializable

@Serializable
enum class AnkiDroidClient(val packageName: String, val displayName: String) {
    AnkiDroid("com.ichi2.anki", "AnkiDroid"),
    Kuma3("io.github.pedrubik2000.kuma3", "kuma3 Anki"),
    ;

    val authority: String get() = "$packageName.flashcards"
    val readWritePermission: String get() = "$packageName.permission.READ_WRITE_DATABASE"
}

internal data class AnkiConnectionIdentity(
    val backendKind: AnkiBackendKind,
    val client: AnkiDroidClient,
    val clientRevision: Long,
    val endpoint: String,
    val apiKey: String,
)

internal val AnkiSettings.connectionIdentity: AnkiConnectionIdentity
    get() = AnkiConnectionIdentity(backendKind, ankiDroidClient, ankiDroidClientRevision, ankiConnectUrl, ankiConnectApiKey)

internal fun AnkiSettings.withAnkiDroidClient(client: AnkiDroidClient): AnkiSettings {
    if (ankiDroidClient == client) return this
    return copy(
        ankiDroidClient = client,
        ankiDroidClientRevision = ankiDroidClientRevision + 1,
        availableDecks = emptyList(),
        availableNoteTypes = emptyList(),
        selectedDeckId = null,
        selectedDeckName = null,
        selectedNoteTypeId = null,
        selectedNoteTypeName = null,
        cardFormats = cardFormats.map {
            it.copy(selectedDeckId = null, selectedDeckName = null, selectedNoteTypeId = null, selectedNoteTypeName = null)
        },
    )
}
