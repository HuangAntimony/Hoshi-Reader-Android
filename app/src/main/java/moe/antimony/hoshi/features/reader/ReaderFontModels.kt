package moe.antimony.hoshi.features.reader

import java.io.File
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.Locale

enum class ReaderFontSource {
    PUBLISHER,
    SYSTEM,
    RECOMMENDED,
    USER,
}

enum class ReaderFontCategory {
    PUBLISHER,
    SYSTEM,
    SERIF,
    SANS_SERIF,
    ROUNDED,
    HANDWRITING,
    IMPORTED,
}

data class ReaderRemoteFontFile(
    val path: String,
    val fileName: String,
    val expectedSize: Long,
    val sha256: String,
    val variableWeightRange: IntRange? = null,
) {
    val url: String
        get() = "$RAW_GOOGLE_FONTS_ROOT/${path.split('/').joinToString("/") { segment ->
            URLEncoder.encode(segment, Charsets.UTF_8.name()).replace("+", "%20")
        }}"

    companion object {
        const val GOOGLE_FONTS_COMMIT = "352f6b7d9d6cc4fa9e242b931291d31b21a6dc84"
        const val RAW_GOOGLE_FONTS_ROOT =
            "https://raw.githubusercontent.com/google/fonts/$GOOGLE_FONTS_COMMIT"
    }
}

data class ReaderFontVariant(
    val id: String,
    val displayName: String,
    val weight: Int,
    val italic: Boolean = false,
    val variationSettings: Map<String, Float> = emptyMap(),
    val variableWeightRange: IntRange? = null,
    val localFile: File? = null,
    val remoteFile: ReaderRemoteFontFile? = null,
) {
    val isInstalled: Boolean
        get() = remoteFile == null || localFile != null
}

data class ReaderFontFamily(
    val id: String,
    val displayName: String,
    val cssFamily: String,
    val source: ReaderFontSource,
    val category: ReaderFontCategory,
    val vendorId: String? = null,
    val variants: List<ReaderFontVariant>,
) {
    companion object {
        fun user(
            displayName: String,
            vendorId: String?,
            variants: List<ReaderFontVariant>,
        ): ReaderFontFamily {
            val identity = "${vendorId.orEmpty()}\u0000${displayName.trim().lowercase(Locale.ROOT)}"
            val suffix = identity.sha256().take(16)
            return ReaderFontFamily(
                id = "user:$suffix",
                displayName = displayName,
                cssFamily = "hoshi-font-user-$suffix",
                source = ReaderFontSource.USER,
                category = ReaderFontCategory.IMPORTED,
                vendorId = vendorId,
                variants = variants,
            )
        }
    }
}

data class ReaderFontSelection(
    val familyId: String,
    val variantId: String,
)

data class ReaderFontFace(
    val url: String,
    val weight: Int,
    val italic: Boolean,
    val variableWeightRange: IntRange? = null,
)

data class ReaderFontRenderSpec(
    val familyId: String,
    val variantId: String,
    val cssFamily: String?,
    val displayName: String,
    val weight: Int,
    val italic: Boolean,
    val variationSettings: Map<String, Float> = emptyMap(),
    val faces: List<ReaderFontFace> = emptyList(),
    val publisherFont: Boolean = false,
    val revision: Long = 0,
)

internal fun String.sha256(): String =
    MessageDigest.getInstance("SHA-256")
        .digest(toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
