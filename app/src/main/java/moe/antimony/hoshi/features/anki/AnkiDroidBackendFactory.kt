package moe.antimony.hoshi.features.anki

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject

class AnkiDroidBackendFactory @Inject constructor(@ApplicationContext private val context: Context) {
    fun create(client: AnkiDroidClient): AnkiBackend =
        AnkiDroidBackendAdapter(AndroidAnkiContentApi(context, client), client.displayName)
}
