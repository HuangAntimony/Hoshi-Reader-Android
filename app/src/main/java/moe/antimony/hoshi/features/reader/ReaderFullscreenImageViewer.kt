package moe.antimony.hoshi.features.reader

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.graphics.Color as AndroidColor
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.zIndex
import androidx.core.content.FileProvider
import java.io.File
import java.util.UUID
import moe.antimony.hoshi.R
import moe.antimony.hoshi.webview.applyHoshiWebViewSecurityDefaults

internal data class ReaderFullscreenImage(
    val sourceUrl: String,
    val resource: ReaderWebResource,
)

@Composable
internal fun ReaderFullscreenImageOverlay(
    image: ReaderFullscreenImage,
    resourceBridge: ReaderWebResourceBridge,
    backgroundColor: Color,
    topSafeAreaPadding: Dp,
    bottomSafeAreaPadding: Dp,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val panAllowance = with(density) {
        ReaderFullscreenImagePanAllowance(
            topPx = (topSafeAreaPadding + 76.dp).toPx(),
            bottomPx = bottomSafeAreaPadding.toPx(),
        )
    }
    BackHandler(onBack = onDismiss)
    Box(
        modifier = modifier
            .background(backgroundColor),
    ) {
        if (image.resource.mediaType.equals("image/svg+xml", ignoreCase = true)) {
            ReaderFullscreenSvgImage(
                image = image,
                resourceBridge = resourceBridge,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            ReaderFullscreenRasterImage(
                image = image,
                panAllowance = panAllowance,
                modifier = Modifier.fillMaxSize(),
            )
        }
        Row(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .zIndex(1f)
                .padding(
                    top = topSafeAreaPadding + 20.dp,
                    end = 12.dp,
                ),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ReaderFullscreenImageButton(
                icon = Icons.Rounded.ContentCopy,
                contentDescription = stringResource(R.string.action_copy),
                onClick = { copyReaderImage(context, image) },
            )
            ReaderFullscreenImageButton(
                icon = Icons.Rounded.Download,
                contentDescription = stringResource(R.string.action_save),
                onClick = { saveReaderImage(context, image) },
            )
            ReaderFullscreenImageButton(
                icon = Icons.Rounded.Share,
                contentDescription = stringResource(R.string.action_share),
                onClick = { shareReaderImage(context, image) },
            )
            ReaderFullscreenImageButton(
                icon = Icons.Rounded.Close,
                contentDescription = stringResource(R.string.action_close),
                onClick = onDismiss,
            )
        }
    }
}

@Composable
private fun ReaderFullscreenRasterImage(
    image: ReaderFullscreenImage,
    panAllowance: ReaderFullscreenImagePanAllowance,
    modifier: Modifier = Modifier,
) {
    val bitmap = remember(image.resource.data) {
        BitmapFactory.decodeByteArray(image.resource.data, 0, image.resource.data.size)
    }
    val intrinsicSize = remember(bitmap) {
        bitmap?.let { decoded -> Size(width = decoded.width.toFloat(), height = decoded.height.toFloat()) } ?: Size.Zero
    }
    var viewport by remember(image.sourceUrl) { mutableStateOf(Size.Zero) }
    val fittedImage = readerFullscreenFittedImageSize(intrinsicSize, viewport)
    var transform by remember(image.sourceUrl) { mutableStateOf(ReaderFullscreenImageTransform()) }
    LaunchedEffect(viewport, fittedImage, panAllowance) {
        transform = transform.constrainedTo(viewport, fittedImage, panAllowance)
    }
    Box(
        modifier = modifier
            .onSizeChanged { size ->
                viewport = Size(width = size.width.toFloat(), height = size.height.toFloat())
            }
            .pointerInput(image.sourceUrl, viewport, fittedImage, panAllowance) {
                detectTapGestures(
                    onDoubleTap = { centroid ->
                        if (transform.scale > ReaderFullscreenImageTransform.MIN_SCALE) {
                            transform = ReaderFullscreenImageTransform()
                        } else {
                            transform = transform.doubleTapZoomTo(
                                targetScale = 3f,
                                centroid = centroid,
                                viewport = viewport,
                                fittedImage = fittedImage,
                                panAllowance = panAllowance,
                            )
                        }
                    },
                )
            }
            .pointerInput(image.sourceUrl, viewport, fittedImage, panAllowance) {
                detectTransformGestures { centroid, pan, zoom, _ ->
                    transform = transform.transformBy(
                        centroid = centroid,
                        pan = pan,
                        zoomChange = zoom,
                        viewport = viewport,
                        fittedImage = fittedImage,
                        panAllowance = panAllowance,
                    )
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        bitmap?.let { decoded ->
            Image(
                bitmap = decoded.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        scaleX = transform.scale
                        scaleY = transform.scale
                        translationX = transform.offset.x
                        translationY = transform.offset.y
                    },
            )
        }
    }
}

@Composable
private fun ReaderFullscreenSvgImage(
    image: ReaderFullscreenImage,
    resourceBridge: ReaderWebResourceBridge,
    modifier: Modifier = Modifier,
) {
    AndroidView(
        modifier = modifier,
        factory = { context ->
            WebView(context).apply {
                applyHoshiWebViewSecurityDefaults()
                settings.builtInZoomControls = true
                settings.displayZoomControls = false
                settings.setSupportZoom(true)
                setBackgroundColor(AndroidColor.TRANSPARENT)
                webViewClient = object : WebViewClient() {
                    override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
                        request.url?.toString()?.let(resourceBridge::resourceForUrl)?.toWebResourceResponse()
                }
            }
        },
        update = { webView ->
            val escapedSource = image.sourceUrl.htmlAttributeEscaped()
            val html = """
                <!doctype html>
                <html>
                <head>
                  <meta name="viewport" content="width=device-width, initial-scale=1.0" />
                  <style>
                    html, body { margin: 0; width: 100%; height: 100%; background: transparent; overflow: hidden; }
                    body { display: flex; align-items: center; justify-content: center; }
                    img { max-width: 100vw; max-height: 100vh; width: auto; height: auto; object-fit: contain; }
                  </style>
                </head>
                <body><img src="$escapedSource" /></body>
                </html>
            """.trimIndent()
            webView.loadDataWithBaseURL("https://hoshi.local/", html, "text/html", "UTF-8", null)
        },
    )
}

@Composable
private fun ReaderFullscreenImageButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
) {
    Surface(
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.9f),
        contentColor = MaterialTheme.colorScheme.onSurface,
    ) {
        IconButton(onClick = onClick) {
            Icon(icon, contentDescription = contentDescription)
        }
    }
}

private fun shareReaderImage(context: Context, image: ReaderFullscreenImage) {
    val file = readerImageShareFile(context, image)
    file.writeBytes(image.resource.data)
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    val intent = Intent(Intent.ACTION_SEND)
        .setType(image.resource.mediaType)
        .putExtra(Intent.EXTRA_STREAM, uri)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    intent.clipData = ClipData.newUri(context.contentResolver, file.name, uri)
    runCatching {
        context.startActivity(Intent.createChooser(intent, context.getString(R.string.action_share)))
    }.recoverCatching { error ->
        if (error is ActivityNotFoundException || error is SecurityException) Unit else throw error
    }
}

private fun copyReaderImage(context: Context, image: ReaderFullscreenImage) {
    val file = readerImageShareFile(context, image)
    file.writeBytes(image.resource.data)
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    val clipboard = context.getSystemService(ClipboardManager::class.java)
    val clip = ClipData.newUri(
        context.contentResolver,
        context.getString(R.string.reader_image_clip_label),
        uri,
    )
    clipboard.setPrimaryClip(clip)
    if (shouldShowReaderImageCopyToast()) {
        Toast.makeText(context, R.string.reader_image_copied, Toast.LENGTH_SHORT).show()
    }
}

private fun saveReaderImage(context: Context, image: ReaderFullscreenImage) {
    val name = "hoshi_reader_${UUID.randomUUID()}.${readerImageExtension(image.sourceUrl, image.resource.mediaType)}"
    val values = ContentValues().apply {
        put(MediaStore.Images.Media.DISPLAY_NAME, name)
        put(MediaStore.Images.Media.MIME_TYPE, image.resource.mediaType)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/Hoshi Reader")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
    }
    val resolver = context.contentResolver
    val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
    } else {
        MediaStore.Images.Media.EXTERNAL_CONTENT_URI
    }
    var uri: Uri? = null
    runCatching {
        uri = resolver.insert(collection, values) ?: error("MediaStore insert failed")
        resolver.openOutputStream(requireNotNull(uri))?.use { output ->
            output.write(image.resource.data)
        } ?: error("MediaStore output stream unavailable")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            values.clear()
            values.put(MediaStore.Images.Media.IS_PENDING, 0)
            resolver.update(requireNotNull(uri), values, null, null)
        }
    }.onSuccess {
        Toast.makeText(context, R.string.reader_image_saved, Toast.LENGTH_SHORT).show()
    }.onFailure {
        uri?.let { failedUri -> resolver.delete(failedUri, null, null) }
        Toast.makeText(context, R.string.reader_image_save_failed, Toast.LENGTH_SHORT).show()
    }
}

private fun readerImageShareFile(context: Context, image: ReaderFullscreenImage): File {
    val dir = File(context.cacheDir, "reader-images").also { it.mkdirs() }
    val extension = readerImageExtension(image.sourceUrl, image.resource.mediaType)
    return File(dir, "hoshi_reader_${UUID.randomUUID()}.$extension")
}

internal fun shouldShowReaderImageCopyToast(sdkInt: Int = Build.VERSION.SDK_INT): Boolean =
    sdkInt < Build.VERSION_CODES.TIRAMISU

private fun readerImageExtension(sourceUrl: String, mediaType: String): String {
    val pathExtension = runCatching {
        Uri.parse(sourceUrl).lastPathSegment
            ?.substringAfterLast('.', missingDelimiterValue = "")
            ?.takeIf { it.isNotBlank() }
    }.getOrNull()
    return pathExtension ?: when (mediaType.substringBefore(';').lowercase()) {
        "image/jpeg" -> "jpg"
        "image/png" -> "png"
        "image/gif" -> "gif"
        "image/webp" -> "webp"
        "image/svg+xml" -> "svg"
        else -> "bin"
    }
}

private fun String.htmlAttributeEscaped(): String =
    replace("&", "&amp;")
        .replace("\"", "&quot;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
