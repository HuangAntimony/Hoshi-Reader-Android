package moe.antimony.hoshi.features.update

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

internal interface GalleryImageDestination {
    fun create(): String
    fun open(id: String): OutputStream
    fun publish(id: String)
    fun delete(id: String)
}

internal suspend fun saveGalleryImage(source: () -> InputStream, destination: GalleryImageDestination) {
    currentCoroutineContext().ensureActive()
    val id = destination.create()
    try {
        source().use { input -> destination.open(id).use { input.copyTo(it) } }
        currentCoroutineContext().ensureActive()
        destination.publish(id)
    } catch (error: Exception) {
        try { destination.delete(id) } catch (cleanup: Exception) { error.addSuppressed(cleanup) }
        throw error
    }
}

internal class AndroidGalleryImageDestination(context: Context) : GalleryImageDestination {
    private val resolver = context.contentResolver
    override fun create(): String {
        val name = "Hoshi-WeChat-Support-${UUID.randomUUID()}.png"
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/Hoshi Reader")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            } else {
                @Suppress("DEPRECATION")
                val directory = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), "Hoshi Reader")
                if (!directory.isDirectory && !directory.mkdirs()) throw IOException("Unable to create image directory: $directory")
                @Suppress("DEPRECATION")
                put(MediaStore.Images.Media.DATA, File(directory, name).absolutePath)
            }
        }
        return (resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: throw IOException("MediaStore returned no URI for the new image")).toString()
    }
    override fun open(id: String): OutputStream = resolver.openOutputStream(Uri.parse(id), "w")
        ?: throw IOException("MediaStore returned no output stream for the new image")

    override fun publish(id: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }
            val updated = resolver.update(Uri.parse(id), values, null, null)
            if (updated != 1) throw IOException("MediaStore image publication updated $updated rows")
        }
    }
    override fun delete(id: String) {
        val deleted = resolver.delete(Uri.parse(id), null, null)
        if (deleted != 1) throw IOException("MediaStore image cleanup deleted $deleted rows")
    }
}
