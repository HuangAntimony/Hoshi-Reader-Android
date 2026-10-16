package moe.antimony.hoshi.importing

import android.content.Context
import moe.antimony.hoshi.ui.withErrorDetails

fun Throwable.localizedImportMessage(context: Context, fallback: String): String =
    if (this is UnsupportedImportFileTypeException) {
        context.getString(messageRes)
    } else {
        fallback.withErrorDetails(this)
    }
