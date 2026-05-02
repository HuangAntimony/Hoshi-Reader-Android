package moe.antimony.hoshi.importing

import android.app.Activity
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.result.contract.ActivityResultContract

class FileImportContent : ActivityResultContract<Array<String>, Uri?>() {
    override fun createIntent(context: Context, input: Array<String>): Intent =
        createImportIntent(input, allowMultiple = false)

    override fun parseResult(resultCode: Int, intent: Intent?): Uri? {
        if (resultCode != Activity.RESULT_OK) return null
        return intent?.data
    }
}

class OpenDocumentContent : ActivityResultContract<Array<String>, Uri?>() {
    override fun createIntent(context: Context, input: Array<String>): Intent =
        createOpenDocumentIntent(input)

    override fun parseResult(resultCode: Int, intent: Intent?): Uri? {
        if (resultCode != Activity.RESULT_OK) return null
        return intent?.data
    }
}

class MultipleFileImportContent : ActivityResultContract<Array<String>, List<Uri>>() {
    override fun createIntent(context: Context, input: Array<String>): Intent =
        createImportIntent(input, allowMultiple = true)

    override fun parseResult(resultCode: Int, intent: Intent?): List<Uri> {
        if (resultCode != Activity.RESULT_OK || intent == null) return emptyList()
        return (intent.clipData.toUris() + listOfNotNull(intent.data)).distinct()
    }
}

private fun createImportIntent(mimeTypes: Array<String>, allowMultiple: Boolean): Intent {
    val requestedMimeTypes = mimeTypes.takeIf { it.isNotEmpty() } ?: arrayOf("*/*")
    return Intent(Intent.ACTION_GET_CONTENT)
        .addCategory(Intent.CATEGORY_OPENABLE)
        .setType(if (requestedMimeTypes.size == 1) requestedMimeTypes.single() else "*/*")
        .putExtra(Intent.EXTRA_MIME_TYPES, requestedMimeTypes)
        .putExtra(Intent.EXTRA_ALLOW_MULTIPLE, allowMultiple)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
}

private fun createOpenDocumentIntent(mimeTypes: Array<String>): Intent {
    val requestedMimeTypes = mimeTypes.takeIf { it.isNotEmpty() } ?: arrayOf("*/*")
    return Intent(Intent.ACTION_OPEN_DOCUMENT)
        .addCategory(Intent.CATEGORY_OPENABLE)
        .setType(if (requestedMimeTypes.size == 1) requestedMimeTypes.single() else "*/*")
        .putExtra(Intent.EXTRA_MIME_TYPES, requestedMimeTypes)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        .addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
}

private fun ClipData?.toUris(): List<Uri> {
    val data = this ?: return emptyList()
    return List(data.itemCount) { index -> data.getItemAt(index).uri }
        .filterNotNull()
}
