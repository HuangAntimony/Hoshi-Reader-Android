package moe.antimony.hoshi.features.wallpaper

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import android.provider.Settings
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.nio.ByteBuffer
import java.util.Locale
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher
import moe.antimony.hoshi.di.IoDispatcher

data class IReaderBookCoverCapability(
    val isSupported: Boolean,
    val isBookCoverScreenSaverSelected: Boolean,
)

fun interface IReaderBookCoverCapabilityProvider {
    fun capability(): IReaderBookCoverCapability
}

class AndroidIReaderBookCoverTarget @Inject constructor(
    @param:ApplicationContext private val context: Context,
    @param:IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) : BookCoverIReaderTarget, IReaderBookCoverCapabilityProvider {
    private val logoDirectory = File(IReaderLogoDirectory)
    private val bookDirectory = File(logoDirectory, IReaderBookDirectoryName)

    override fun capability(): IReaderBookCoverCapability = IReaderBookCoverCapability(
        isSupported = isIReaderDeviceBrand(Build.MANUFACTURER, Build.BRAND) &&
            logoDirectory.isDirectory &&
            logoDirectory.canWrite(),
        isBookCoverScreenSaverSelected = isIReaderBookCoverScreenSaverSelected(wallpaperSetting()),
    )

    override suspend fun publish(image: File): BookCoverPublishFailure? {
        if (!capability().isSupported) return BookCoverPublishFailure.IReaderUnsupported
        return fileTarget().publish(image)
    }

    private fun fileTarget(): IReaderBookCoverFileTarget = IReaderBookCoverFileTarget(
        directory = bookDirectory,
        isBookCoverScreenSaverSelected = {
            isIReaderBookCoverScreenSaverSelected(wallpaperSetting())
        },
        encoder = AndroidIReaderRawBitmapEncoder,
        notifier = IReaderBookCoverNotifier {
            context.sendBroadcast(
                Intent(IReaderWallpaperChangedAction)
                    .setPackage(IReaderSystemUiPackage)
                    .putExtra(IReaderWallpaperTypeExtra, IReaderBookWallpaperName)
                    .putExtra(IReaderOrderTypeExtra, IReaderCarouselOrderName),
            )
        },
        outputName = { "hoshi-${UUID.randomUUID()}.rmb" },
        ioDispatcher = ioDispatcher,
    )

    private fun wallpaperSetting(): String? =
        runCatching {
            Settings.System.getString(context.contentResolver, IReaderWallpaperSetting)
        }.getOrNull()
}

internal fun isIReaderDeviceBrand(manufacturer: String, brand: String): Boolean {
    val normalizedManufacturer = manufacturer.lowercase(Locale.ROOT)
    val normalizedBrand = brand.lowercase(Locale.ROOT)
    return normalizedManufacturer == IReaderBrand ||
        normalizedBrand == IReaderBrand ||
        (normalizedManufacturer == MusnapManufacturer && normalizedBrand == MusnapBrand)
}

internal fun isIReaderBookCoverScreenSaverSelected(rawSetting: String?): Boolean =
    rawSetting
        ?.substringBefore(',')
        ?.trim()
        ?.toIntOrNull() == IReaderBookWallpaperType

private object AndroidIReaderRawBitmapEncoder : IReaderRawBitmapEncoder {
    override fun encode(source: File, output: File) {
        val bitmap = BitmapFactory.decodeFile(
            source.absolutePath,
            BitmapFactory.Options().apply {
                inPreferredConfig = Bitmap.Config.ARGB_8888
            },
        ) ?: error("Rendered cover cannot be decoded.")
        try {
            val pixels = ByteBuffer.allocate(bitmap.byteCount)
            bitmap.copyPixelsToBuffer(pixels)
            pixels.flip()
            writeIReaderRawBitmap(
                output = output,
                width = bitmap.width,
                height = bitmap.height,
                configOrdinal = bitmap.config?.ordinal ?: Bitmap.Config.ARGB_8888.ordinal,
                pixels = pixels,
            )
        } finally {
            bitmap.recycle()
        }
    }
}

private const val IReaderBrand = "ireader"
private const val MusnapManufacturer = "chitech"
private const val MusnapBrand = "byybuo"
private const val IReaderLogoDirectory = "/data/zhangyue/logo"
private const val IReaderBookDirectoryName = "book"
private const val IReaderBookWallpaperType = 2
private const val IReaderWallpaperSetting = "wallpaper_lock_screen_info"
private const val IReaderWallpaperChangedAction =
    "com.szzy.ireader.ink.wallpaper.ACTION_LOCK_SCREEN_WALLPAPER_CHANGED"
private const val IReaderSystemUiPackage = "com.szzy.ireader.systemui"
private const val IReaderWallpaperTypeExtra = "extra_wallpaper_type"
private const val IReaderOrderTypeExtra = "extra_order_type"
private const val IReaderBookWallpaperName = "BOOK"
private const val IReaderCarouselOrderName = "CAROUSEL"
