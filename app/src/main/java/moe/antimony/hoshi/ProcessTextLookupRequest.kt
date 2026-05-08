package moe.antimony.hoshi

import android.content.Intent

private const val ActionProcessText = "android.intent.action.PROCESS_TEXT"

data class ProcessTextLookupRequest(
    val query: String,
    val id: Long = 0L,
) {
    companion object {
        fun from(action: String?, selectedText: CharSequence?): ProcessTextLookupRequest? {
            if (action != ActionProcessText) return null
            val query = selectedText?.toString()?.trim().orEmpty()
            return query.takeIf { it.isNotEmpty() }?.let(::ProcessTextLookupRequest)
        }

        fun fromIntent(intent: Intent?): ProcessTextLookupRequest? =
            intent?.let {
                from(
                    action = it.action,
                    selectedText = it.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT),
                )
            }
    }
}
