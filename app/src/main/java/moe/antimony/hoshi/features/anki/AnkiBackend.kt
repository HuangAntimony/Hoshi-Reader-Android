package moe.antimony.hoshi.features.anki

interface AnkiBackend {
    fun isAvailable(): Boolean
    fun fetchDecks(): List<AnkiDeck>
    fun fetchNoteTypes(): List<AnkiNoteType>
    fun isDuplicate(modelId: Long, key: String): Boolean
    fun addNote(
        deck: AnkiDeck,
        noteType: AnkiNoteType,
        fieldsByName: Map<String, String>,
        tags: Set<String>,
        allowDupes: Boolean,
    ): Boolean
    fun addMediaFromUri(uriString: String, preferredName: String, mimeType: String): String?
}

interface AnkiContentApi {
    fun deckList(): Map<Long, String>
    fun modelList(): Map<Long, String>
    fun fieldList(modelId: Long): List<String>
    fun findDuplicateNotes(modelId: Long, key: String): Boolean
    fun addNote(modelId: Long, deckId: Long, fields: Array<String>, tags: Set<String>): Long?
    fun addMediaFromUri(uriString: String, preferredName: String, mimeType: String): String? = null
    fun isAvailable(): Boolean = true
}

class AnkiDroidBackendAdapter(
    private val api: AnkiContentApi,
) : AnkiBackend {
    override fun isAvailable(): Boolean = api.isAvailable()

    override fun fetchDecks(): List<AnkiDeck> =
        api.deckList().map { (id, name) -> AnkiDeck(id, name) }

    override fun fetchNoteTypes(): List<AnkiNoteType> =
        api.modelList().map { (id, name) ->
            AnkiNoteType(id = id, name = name, fields = api.fieldList(id))
        }

    override fun isDuplicate(modelId: Long, key: String): Boolean =
        key.isNotBlank() && api.findDuplicateNotes(modelId, key)

    override fun addNote(
        deck: AnkiDeck,
        noteType: AnkiNoteType,
        fieldsByName: Map<String, String>,
        tags: Set<String>,
        allowDupes: Boolean,
    ): Boolean {
        val duplicateKey = noteType.fields.firstOrNull()?.let(fieldsByName::get).orEmpty()
        if (!allowDupes && isDuplicate(noteType.id, duplicateKey)) return false
        val fields = noteType.fields.map { fieldsByName[it].orEmpty() }.toTypedArray()
        return api.addNote(
            modelId = noteType.id,
            deckId = deck.id,
            fields = fields,
            tags = tags,
        ) != null
    }

    override fun addMediaFromUri(uriString: String, preferredName: String, mimeType: String): String? =
        api.addMediaFromUri(uriString, preferredName, mimeType)
}
