package moe.antimony.hoshi.features.anki

import android.content.Context
import android.net.Uri
import com.ichi2.anki.api.AddContentApi

class AndroidAnkiContentApi(
    context: Context,
) : AnkiContentApi {
    private val appContext = context.applicationContext
    private val api = AddContentApi(appContext)

    override fun deckList(): Map<Long, String> =
        api.deckList.orEmpty()

    override fun modelList(): Map<Long, String> =
        api.modelList.orEmpty()

    override fun fieldList(modelId: Long): List<String> =
        api.getFieldList(modelId)?.toList().orEmpty()

    override fun findDuplicateNotes(modelId: Long, key: String): Boolean =
        api.findDuplicateNotes(modelId, key).isNotEmpty()

    override fun addNote(
        modelId: Long,
        deckId: Long,
        fields: Array<String>,
        tags: Set<String>,
    ): Long? = api.addNote(modelId, deckId, fields, tags)

    override fun addMediaFromUri(uriString: String, preferredName: String, mimeType: String): String? =
        api.addMediaFromUri(
            Uri.parse(uriString),
            preferredName.substringBeforeLast('.'),
            if (mimeType.startsWith("audio/")) "audio" else "image",
        )

    override fun isAvailable(): Boolean =
        AddContentApi.getAnkiDroidPackageName(appContext) != null
}
