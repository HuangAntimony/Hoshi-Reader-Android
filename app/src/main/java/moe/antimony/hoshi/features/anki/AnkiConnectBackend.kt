package moe.antimony.hoshi.features.anki

import java.io.IOException
import kotlinx.coroutines.CancellationException
import moe.antimony.hoshi.R
import moe.antimony.hoshi.ui.ErrorDiagnosticEvents
import moe.antimony.hoshi.ui.UiText
import moe.antimony.hoshi.ui.diagnosticMessage
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.Base64
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

fun interface AnkiConnectTransport {
    fun post(url: String, body: String, timeoutMillis: Int): String
}

class HttpAnkiConnectTransport : AnkiConnectTransport {
    override fun post(url: String, body: String, timeoutMillis: Int): String {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = timeoutMillis
            readTimeout = timeoutMillis
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
        }
        return try {
            connection.outputStream.use { output ->
                output.write(body.toByteArray(Charsets.UTF_8))
            }
            val stream = if (connection.responseCode in 200..299) {
                connection.inputStream
            } else {
                connection.errorStream ?: throw IOException("AnkiConnect HTTP ${connection.responseCode}")
            }
            stream.bufferedReader(Charsets.UTF_8).use { it.readText() }.also { responseBody ->
                if (connection.responseCode !in 200..299) {
                    val key = runCatching { Json.parseToJsonElement(body).jsonObject["key"]?.jsonPrimitive?.contentOrNull }.getOrNull().orEmpty()
                    throw IOException("AnkiConnect HTTP ${connection.responseCode}: ${responseBody.withoutAnkiKey(key)}")
                }
            }
        } finally {
            connection.disconnect()
        }
    }
}

class AnkiConnectBackend(
    endpoint: String,
    private val transport: AnkiConnectTransport = HttpAnkiConnectTransport(),
    private val timeoutMillis: Int = 10_000,
    private val apiKey: String = "",
) : AnkiBackend {
    private val endpoint = AnkiConnectUrlValidator.requireValidEndpoint(endpoint).toString()

    override fun checkAvailability(): Boolean {
        request("version")
        return true
    }

    override fun isAvailable(): Boolean =
        runCatching {
            request("version")
            true
        }.getOrElse { error ->
            if (error is CancellationException) throw error
            ErrorDiagnosticEvents.buffer.record(UiText.Resource(R.string.anki_fetch_connect_ankiconnect_failed), error)
            false
        }

    override fun fetchDecks(): List<AnkiDeck> =
        wrapFetch {
            requestArray("deckNames").strings().map { name ->
                AnkiDeck(id = ankiConnectStableId("deck", name), name = name)
            }.sortedAnkiDecks()
        }

    override fun fetchNoteTypes(): List<AnkiNoteType> =
        wrapFetch {
            requestArray("modelNames").strings().map { model ->
                val fields = requestArray(
                    "modelFieldNames",
                    buildJsonObject { put("modelName", model) },
                ).strings()
                AnkiNoteType(
                    id = ankiConnectStableId("model", model),
                    name = model,
                    fields = fields,
                )
            }
        }

    override fun isDuplicate(
        deck: AnkiDeck,
        noteType: AnkiNoteType,
        key: String,
        duplicateScope: AnkiDuplicateScope,
        checkDuplicatesAcrossAllModels: Boolean,
    ): Boolean {
        if (key.isBlank()) return false
        return runCatching {
            val firstField = noteType.fields.firstOrNull() ?: return false
            val note = ankiConnectNote(
                deck = deck,
                noteType = noteType,
                fieldsByName = mapOf(firstField to key),
                options = ankiConnectDuplicateOptions(
                    deck = deck,
                    allowDupes = false,
                    duplicateScope = duplicateScope,
                    checkDuplicatesAcrossAllModels = checkDuplicatesAcrossAllModels,
                ),
            )
            val results = requestArray(
                "canAddNotesWithErrorDetail",
                buildJsonObject {
                    put("notes", buildJsonArray { add(note) })
                },
            )
            val first = results.firstOrNull()?.jsonObject ?: return false
            !(first["canAdd"]?.jsonPrimitive?.booleanOrNull ?: true)
        }.getOrElse { error ->
            if (error is CancellationException) throw error
            ErrorDiagnosticEvents.buffer.record(UiText.Resource(R.string.anki_operation_failed), error)
            false
        }
    }

    override fun addNote(
        deck: AnkiDeck,
        noteType: AnkiNoteType,
        fieldsByName: Map<String, String>,
        tags: Set<String>,
        allowDupes: Boolean,
        duplicateScope: AnkiDuplicateScope,
        checkDuplicatesAcrossAllModels: Boolean,
    ): Boolean {
        val note = ankiConnectNote(
            deck = deck,
            noteType = noteType,
            fieldsByName = fieldsByName,
            options = ankiConnectDuplicateOptions(
                deck = deck,
                allowDupes = allowDupes,
                duplicateScope = duplicateScope,
                checkDuplicatesAcrossAllModels = checkDuplicatesAcrossAllModels,
            ),
        )
        val noteWithTags = if (tags.isEmpty()) {
            note
        } else {
            buildJsonObject {
                note.forEach { (key, value) -> put(key, value) }
                put(
                    "tags",
                    buildJsonArray {
                        tags.forEach { tag -> add(JsonPrimitive(tag)) }
                    },
                )
            }
        }
        request(
            "addNote",
            buildJsonObject {
                put("note", noteWithTags)
            },
        )
        return true
    }

    override fun addMediaFromUri(uriString: String, preferredName: String, mimeType: String): String? = null

    override fun addMediaFromBytes(bytes: ByteArray, preferredName: String, mimeType: String): String? {
        val filename = ankiConnectMediaFilename(preferredName)
        request(
            "storeMediaFile",
            buildJsonObject {
                put("filename", filename)
                put("data", Base64.getEncoder().encodeToString(bytes))
            },
        )
        return when {
            mimeType.startsWith("audio/") -> "[sound:$filename]"
            mimeType.startsWith("image/") -> """<img src="$filename">"""
            else -> filename
        }
    }

    override fun sync(): Boolean {
        request("sync")
        return true
    }

    override fun openNotes(
        deck: AnkiDeck,
        noteType: AnkiNoteType,
        key: String,
        duplicateScope: AnkiDuplicateScope,
        checkDuplicatesAcrossAllModels: Boolean,
    ): Boolean {
        val query = ankiNoteBrowserQuery(
            deck = deck,
            noteType = noteType,
            key = key,
            duplicateScope = duplicateScope,
            checkAllModels = checkDuplicatesAcrossAllModels,
        )
        if (query.isBlank()) return false
        request("guiBrowse", buildJsonObject { put("query", query) })
        return true
    }

    private fun ankiConnectNote(
        deck: AnkiDeck,
        noteType: AnkiNoteType,
        fieldsByName: Map<String, String>,
        options: JsonObject,
    ): JsonObject =
        buildJsonObject {
            put("deckName", deck.name)
            put("modelName", noteType.name)
            put(
                "fields",
                buildJsonObject {
                    fieldsByName
                        .activeAnkiFieldMappings(noteType)
                        .forEach { (field, value) -> put(field, value) }
                },
            )
            put("options", options)
        }

    private fun ankiConnectDuplicateOptions(
        deck: AnkiDeck,
        allowDupes: Boolean,
        duplicateScope: AnkiDuplicateScope,
        checkDuplicatesAcrossAllModels: Boolean,
    ): JsonObject {
        var duplicateScopeOptions: JsonObject? = null
        val duplicateScopeValue = when (duplicateScope) {
            AnkiDuplicateScope.Collection -> "collection"
            AnkiDuplicateScope.Deck -> "deck"
            AnkiDuplicateScope.DeckRoot -> {
                val rootDeck = deck.name.substringBefore("::")
                duplicateScopeOptions = buildJsonObject {
                    put("deckName", rootDeck)
                    put("checkChildren", true)
                }
                "deck"
            }
        }
        if (checkDuplicatesAcrossAllModels) {
            val previous = duplicateScopeOptions
            duplicateScopeOptions = buildJsonObject {
                previous?.forEach { (key, value) -> put(key, value) }
                put("checkAllModels", true)
            }
        }
        return buildJsonObject {
            put("allowDuplicate", allowDupes)
            put("duplicateScope", duplicateScopeValue)
            duplicateScopeOptions?.let { put("duplicateScopeOptions", it) }
        }
    }

    private fun requestArray(action: String, params: JsonObject? = null): JsonArray =
        request(action, params)?.jsonArray ?: JsonArray(emptyList())

    private fun request(action: String, params: JsonObject? = null): JsonElement? {
        val body = buildJsonObject {
            put("action", action)
            put("version", 6)
            if (params != null) {
                put("params", params)
            }
            if (apiKey.isNotEmpty()) {
                put("key", apiKey)
            }
        }
        try {
            val response = json.parseToJsonElement(transport.post(endpoint, body.toString(), timeoutMillis)).jsonObject
            val error = response["error"]
            if (error != null && error !is JsonNull) {
                throw AnkiConnectRequestException(error.jsonPrimitive.content.withoutAnkiKey(apiKey))
            }
            return response["result"]?.takeUnless { it is JsonNull }
        } catch (error: kotlinx.coroutines.CancellationException) { throw error
        } catch (error: Exception) {
            val diagnostic = error.diagnosticMessage()
            val safe = diagnostic.withoutAnkiKey(apiKey)
            if (safe == diagnostic) throw error
            throw AnkiConnectRequestException(safe).apply { stackTrace = error.stackTrace }
        }
    }

    private inline fun <T> wrapFetch(block: () -> T): T =
        try {
            block()
        } catch (error: AnkiFetchException) {
            throw error
        } catch (error: kotlinx.coroutines.CancellationException) {
            throw error
        } catch (error: Throwable) {
            throw AnkiFetchException(
                AnkiFetchFailure.ProviderFailure,
                error.message ?: AnkiFetchFailure.ProviderFailure.userMessage,
                error,
            )
        }

    private companion object {
        val json = Json { ignoreUnknownKeys = true }
    }
}

private class AnkiConnectRequestException(message: String) : RuntimeException(message)

internal fun ankiConnectStableId(namespace: String, value: String): Long {
    val digest = MessageDigest.getInstance("SHA-256")
        .digest("$namespace:$value".toByteArray(Charsets.UTF_8))
    var id = 0L
    for (index in 0 until 8) {
        id = (id shl 8) or (digest[index].toLong() and 0xffL)
    }
    return id and Long.MAX_VALUE
}

private fun JsonArray.strings(): List<String> =
    map { it.jsonPrimitive.contentOrNull.orEmpty() }

private fun ankiConnectMediaFilename(preferredName: String): String =
    preferredName
        .substringAfterLast('/')
        .substringAfterLast('\\')
        .ifBlank { "hoshi_media" }

private fun String.withoutAnkiKey(key: String): String = if (key.isEmpty()) this else replace(key, "***")
