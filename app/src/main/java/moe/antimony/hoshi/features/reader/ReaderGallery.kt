package moe.antimony.hoshi.features.reader

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import java.io.File
import java.net.URI
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import moe.antimony.hoshi.R
import moe.antimony.hoshi.epub.EpubBook

private const val ReaderGalleryMaxPixelSize = 1600

internal fun EpubBook.galleryResourceUrl(path: String): String? {
    val rawPath = path.trim().replace('\\', '/')
    if (rawPath.isBlank() || rawPath.startsWith('/') || rawPath.startsWith("//")) return null
    val normalized = File(rawPath).normalize().invariantSeparatorsPath
    if (normalized == ".." || normalized.startsWith("../")) return null
    return URI(
        "https",
        "appassets.androidplatform.net",
        "/epub/$normalized",
        null,
    ).toASCIIString()
}

@Composable
internal fun ReaderGalleryTab(
    book: EpubBook,
    onImageSelected: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val images = book.bookInfo.images.orEmpty()
    if (images.isEmpty()) {
        Box(modifier = modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(
                    imageVector = Icons.Rounded.PhotoLibrary,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = stringResource(R.string.reader_no_images),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        }
        return
    }
    LazyColumn(
        modifier = modifier.fillMaxWidth(),
    ) {
        items(images, key = { it }) { path ->
            ReaderGalleryImage(
                book = book,
                path = path,
                onClick = { onImageSelected(path) },
                modifier = Modifier.padding(start = 20.dp, top = 8.dp, end = 20.dp, bottom = 8.dp),
            )
        }
    }
}

@Composable
private fun ReaderGalleryImage(
    book: EpubBook,
    path: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var image by remember(book, path) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(book, path) {
        image = withContext(Dispatchers.IO) {
            book.readResource(path)?.decodeReaderGalleryBitmap()?.asImageBitmap()
        }
    }
    val shape = RoundedCornerShape(4.dp)
    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .clickable(onClick = onClick)
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)),
        contentAlignment = Alignment.Center,
    ) {
        if (image != null) {
            Image(
                bitmap = requireNotNull(image),
                contentDescription = stringResource(R.string.reader_gallery_image),
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxWidth(),
            )
        } else {
            Box(modifier = Modifier.fillMaxWidth().aspectRatio(0.7f))
        }
    }
}

private fun ByteArray.decodeReaderGalleryBitmap(): Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(this, 0, size, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    var sampleSize = 1
    while (maxOf(bounds.outWidth, bounds.outHeight) / sampleSize > ReaderGalleryMaxPixelSize * 2) {
        sampleSize *= 2
    }
    val decoded = BitmapFactory.decodeByteArray(
        this,
        0,
        size,
        BitmapFactory.Options().apply { inSampleSize = sampleSize },
    ) ?: return null
    val longestEdge = maxOf(decoded.width, decoded.height)
    if (longestEdge <= ReaderGalleryMaxPixelSize) return decoded
    val scale = ReaderGalleryMaxPixelSize.toFloat() / longestEdge.toFloat()
    val scaled = Bitmap.createScaledBitmap(
        decoded,
        (decoded.width * scale).toInt().coerceAtLeast(1),
        (decoded.height * scale).toInt().coerceAtLeast(1),
        true,
    )
    if (scaled !== decoded) decoded.recycle()
    return scaled
}
