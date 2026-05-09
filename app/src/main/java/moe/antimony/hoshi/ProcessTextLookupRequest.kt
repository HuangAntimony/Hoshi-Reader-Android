package moe.antimony.hoshi

import android.content.Intent

private const val ActionProcessText = "android.intent.action.PROCESS_TEXT"
private const val ActionTranslate = "android.intent.action.TRANSLATE"

data class ProcessTextLookupRequest(
    val query: String,
    val id: Long = 0L,
) {
    companion object {
        fun from(
            action: String?,
            selectedText: CharSequence?,
            sharedText: CharSequence? = null,
        ): ProcessTextLookupRequest? {
            val text = when (action) {
                ActionProcessText -> selectedText
                ActionTranslate, Intent.ACTION_SEND -> sharedText
                else -> null
            }
            val query = text?.toString()?.trim().orEmpty()
            return query.takeIf { it.isNotEmpty() }?.let(::ProcessTextLookupRequest)
        }

        fun fromIntent(intent: Intent?): ProcessTextLookupRequest? =
            intent?.let {
                from(
                    action = it.action,
                    selectedText = it.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT),
                    sharedText = it.getCharSequenceExtra(Intent.EXTRA_TEXT),
                )
            }
    }
}
