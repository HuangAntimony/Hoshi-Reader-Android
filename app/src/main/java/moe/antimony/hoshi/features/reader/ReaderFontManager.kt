package moe.antimony.hoshi.features.reader

import android.content.ContentResolver
import android.database.Cursor
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.net.URLEncoder
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.locks.ReentrantLock
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.concurrent.withLock
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import moe.antimony.hoshi.di.ApplicationScope
import moe.antimony.hoshi.di.FilesDir
import moe.antimony.hoshi.di.IoDispatcher

data class ReaderFontInfo(
    val name: String,
    val fileName: String,
    val file: File,
    val familyId: String? = null,
    val variantId: String? = null,
)

data class ReaderFontLibraryState(
    val families: List<ReaderFontFamily>,
    val revision: Long,
)

private data class ParsedUserFont(
    val file: File,
    val family: ReaderFontFamily,
    val variants: List<ReaderFontVariant>,
)

private data class FontLogicalSlot(
    val weight: Int,
    val italic: Boolean,
    val standaloneVariableRange: IntRange? = null,
)

@Serializable
private data class FontLegacyAlias(
    val familyId: String,
    val weight: Int? = null,
    val italic: Boolean? = null,
    val variableWeightStart: Int? = null,
    val variableWeightEnd: Int? = null,
)

@Singleton
class ReaderFontManager @Inject constructor(
    @param:FilesDir private val filesDir: File,
    @ApplicationScope applicationScope: CoroutineScope,
    @IoDispatcher ioDispatcher: CoroutineDispatcher,
) {
    private val fontsDirectory = File(filesDir, "Fonts")
    private val managedFontsDirectory = File(fontsDirectory, "System")
    private val legacyAliasesFile = File(fontsDirectory, ".legacy-font-aliases.json")
    private val libraryLock = ReentrantLock()
    private var legacyAliases = readLegacyAliases()
    private val _libraryState = MutableStateFlow(initialLibrary())

    val libraryState: StateFlow<ReaderFontLibraryState> = _libraryState.asStateFlow()

    constructor(filesDir: File) : this(
        filesDir = filesDir,
        applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
        ioDispatcher = Dispatchers.Unconfined,
    )

    init {
        applicationScope.launch(ioDispatcher) { refresh() }
    }

    fun importFont(source: File): ReaderFontInfo {
        require(source.name.isSupportedFontFileName()) { "Unsupported font file." }
        source.inputStream().use { input -> return importFont(input, source.name) }
    }

    fun importFont(contentResolver: ContentResolver, uri: Uri): ReaderFontInfo {
        val fileName = contentResolver.displayName(uri)
        require(fileName.isSupportedFontFileName()) { "Unsupported font file." }
        contentResolver.openInputStream(uri).use { input ->
            return importFont(requireNotNull(input) { "Unable to open font file." }, fileName)
        }
    }

    fun storedFonts(): List<ReaderFontInfo> = libraryLock.withLock {
        val families = userFamilies()
        userFontFiles().map { file ->
            val family = families.firstOrNull { candidate ->
                candidate.variants.any { it.localFile?.canonicalFile == file.canonicalFile }
            }
            val variant = family?.variants?.firstOrNull { it.localFile?.canonicalFile == file.canonicalFile }
            ReaderFontInfo(
                name = family?.displayName ?: file.nameWithoutExtension,
                fileName = file.name,
                file = file,
                familyId = family?.id,
                variantId = variant?.id,
            )
        }
    }

    fun storedFont(name: String): ReaderFontInfo? = storedFonts().firstOrNull {
        it.name == name || it.file.nameWithoutExtension == name
    }

    fun fontFamilies(): List<ReaderFontFamily> = _libraryState.value.families

    fun refresh() {
        libraryLock.withLock { publishLibraryLocked() }
    }

    fun deleteFont(name: String) {
        libraryLock.withLock {
            val family = fontFamilies().firstOrNull {
                it.source == ReaderFontSource.USER && (it.displayName == name || it.id == name)
            }
            if (family != null) {
                deleteFamilyLocked(family.id)
            } else {
                storedFont(name)?.file?.delete()
                publishLibraryLocked()
            }
        }
    }

    fun deleteFamily(familyId: String) {
        libraryLock.withLock { deleteFamilyLocked(familyId) }
    }

    private fun deleteFamilyLocked(familyId: String) {
        if (fontFamilies().none { it.id == familyId && it.source == ReaderFontSource.USER }) return
        writeLegacyAliases(legacyAliases.filterValues { it.familyId != familyId })
        parsedUserFonts().filter { it.family.id == familyId }.map(ParsedUserFont::file)
            .distinctBy(File::getAbsolutePath)
            .forEach(File::delete)
        publishLibraryLocked()
    }

    fun isDefaultFont(name: String): Boolean =
        name in defaultFonts || ReaderRecommendedFontCatalog.families.any { it.displayName == name }

    fun webViewFontUrl(name: String): String? {
        val legacy = storedFont(name)?.file
        if (legacy != null) return localFontUrl(legacy)
        legacyAliases[name]?.let { alias ->
            val family = fontFamilies().firstOrNull { it.id == alias.familyId }
            val variant = family?.variantForAlias(alias)
            variant?.localFile?.let { return localFontUrl(it) }
        }
        val family = fontFamilies().firstOrNull { it.displayName == name || it.id == name } ?: return null
        val variant = family.variants.firstOrNull { it.weight == 400 && !it.italic }
            ?: family.variants.firstOrNull()
        return variant?.localFile?.let(::localFontUrl)
    }

    fun allFontNames(): List<String> = fontFamilies()
        .filter { family ->
            family.source in setOf(ReaderFontSource.SYSTEM, ReaderFontSource.USER) ||
                family.source == ReaderFontSource.RECOMMENDED && family.variants.any(ReaderFontVariant::isInstalled)
        }
        .map(ReaderFontFamily::displayName)
        .distinct()

    fun cssFontName(name: String): String = fontFamilies()
        .firstOrNull { it.displayName == name || it.id == name }
        ?.cssFamily ?: name

    fun popupFontFaceCss(): String {
        val families = fontFamilies()
        val currentFaces = families
            .filter { it.source in setOf(ReaderFontSource.RECOMMENDED, ReaderFontSource.USER) }
            .flatMap { family -> family.variants.mapNotNull { variant ->
                variant.localFile?.let { file -> Triple(family, variant, file) }
            } }
            .distinctBy { (family, variant, file) -> fontFaceIdentity(family.id, variant, file) }
            .flatMap { (family, variant, file) ->
                val sourceUrl = requireNotNull(localFontUrl(file))
                buildList {
                    add(fontFaceCss(family.cssFamily, variant, sourceUrl))
                    if (family.source == ReaderFontSource.USER && file.nameWithoutExtension != family.cssFamily) {
                        add(fontFaceCss(file.nameWithoutExtension, variant, sourceUrl))
                    }
                }
            }
        val legacyFaces = legacyAliases.flatMap { (aliasName, alias) ->
            val family = families.firstOrNull { it.id == alias.familyId } ?: return@flatMap emptyList()
            val aliasedVariants = if (alias.weight != null) {
                listOfNotNull(family.variantForAlias(alias))
            } else {
                family.variants
            }
            aliasedVariants.mapNotNull { variant ->
                variant.localFile?.let { file -> variant to file }
            }.distinctBy { (variant, file) -> fontFaceIdentity(aliasName, variant, file) }
                .map { (variant, file) ->
                    fontFaceCss(aliasName, variant, requireNotNull(localFontUrl(file)))
                }
        }
        return (currentFaces + legacyFaces).distinct().joinToString(separator = "\n")
    }

    fun resolveRenderSpec(
        selectedFont: String,
        familyId: String? = null,
        variantId: String? = null,
    ): ReaderFontRenderSpec {
        val families = fontFamilies()
        val explicitFamily = families.firstOrNull { it.id == familyId }
        val displayFamily = families.firstOrNull { it.displayName == selectedFont }
        val legacySelection = if (explicitFamily == null && displayFamily == null) {
            families.firstNotNullOfOrNull { candidate ->
                candidate.variants.firstOrNull { it.localFile?.nameWithoutExtension == selectedFont }
                    ?.let { candidate to it }
            } ?: legacyAliases[selectedFont]?.let { alias ->
                families.firstOrNull { it.id == alias.familyId }?.let { family ->
                    family.variantForAlias(alias)?.let { family to it }
                }
            }
        } else null
        val family = explicitFamily
            ?: displayFamily
            ?: legacySelection?.first
            ?: families.first { it.id == systemMinchoFamilyId }
        if (family.source == ReaderFontSource.PUBLISHER) {
            return ReaderFontRenderSpec(
                familyId = family.id,
                variantId = publisherVariantId,
                cssFamily = null,
                displayName = family.displayName,
                weight = 400,
                italic = false,
                publisherFont = true,
                revision = libraryState.value.revision,
            )
        }
        val variant = family.variants.firstOrNull { it.id == variantId }
            ?: legacySelection?.second
            ?: family.variants.firstOrNull { it.weight == 400 && !it.italic }
            ?: family.variants.first()
        val faces = family.variants.mapNotNull { item ->
            item.localFile?.let { file ->
                ReaderFontFace(
                    url = requireNotNull(localFontUrl(file)),
                    weight = item.weight,
                    italic = item.italic,
                    variableWeightRange = item.variableWeightRange ?: item.remoteFile?.variableWeightRange,
                )
            }
        }.distinctBy { face -> Triple(face.url, face.variableWeightRange, face.italic) }
        return ReaderFontRenderSpec(
            familyId = family.id,
            variantId = variant.id,
            cssFamily = family.cssFamily,
            displayName = family.displayName,
            weight = variant.weight,
            italic = variant.italic,
            variationSettings = variant.variationSettings,
            faces = faces,
            revision = libraryState.value.revision,
        )
    }

    fun fontFileForRequest(fileName: String): File? {
        val requested = File(fontsDirectory, fileName)
        val fontsRoot = fontsDirectory.canonicalFile
        val systemRoot = managedFontsDirectory.canonicalFile
        val canonical = runCatching { requested.canonicalFile }.getOrNull() ?: return null
        val allowed = canonical.parentFile == fontsRoot || canonical.parentFile == systemRoot
        return canonical.takeIf { allowed && it.isFile }
    }

    fun managedFontsDirectory(): File = managedFontsDirectory

    private fun importFont(input: InputStream, originalName: String): ReaderFontInfo = libraryLock.withLock {
        fontsDirectory.mkdirs()
        val extension = originalName.substringAfterLast('.').lowercase()
        val part = File(fontsDirectory, ".import-${System.nanoTime()}.$extension.part")
        try {
            part.outputStream().use { output -> input.copyTo(output) }
            val metadata = if (extension in setOf("ttf", "otf")) SfntFontParser.parse(part) else null
            var replacedFonts = emptyList<ParsedUserFont>()
            val destination = if (metadata == null) {
                File(fontsDirectory, originalName.safeFileName())
            } else {
                val family = ReaderFontFamily.user(metadata.familyName, metadata.vendorId, emptyList())
                val familySuffix = family.id.substringAfter(':')
                val target = File(fontsDirectory, "$familySuffix-${part.sha256File().take(16)}.$extension")
                val incomingSlots = metadata.logicalSlots()
                replacedFonts = parsedUserFonts().filter { existing ->
                        existing.family.id == family.id &&
                            existing.variants.all { it.logicalSlot() in incomingSlots }
                    }
                target
            }
            val newestExistingTimestamp = userFontFiles().maxOfOrNull(File::lastModified) ?: 0L
            val destinationExisted = destination.isFile
            moveAtomically(part, destination)
            try {
                destination.setLastModified(maxOf(System.currentTimeMillis(), newestExistingTimestamp + 1))
                if (metadata != null && replacedFonts.isNotEmpty()) {
                    val familyId = ReaderFontFamily.user(metadata.familyName, metadata.vendorId, emptyList()).id
                    val updatedAliases = legacyAliases.toMutableMap()
                    replacedFonts.forEach { replaced ->
                        val slots = replaced.variants.map(ReaderFontVariant::logicalSlot).distinct()
                        val onlySlot = slots.singleOrNull()
                        updatedAliases[replaced.file.nameWithoutExtension] = FontLegacyAlias(
                            familyId = familyId,
                            weight = onlySlot?.weight,
                            italic = onlySlot?.italic,
                            variableWeightStart = onlySlot?.standaloneVariableRange?.first,
                            variableWeightEnd = onlySlot?.standaloneVariableRange?.last,
                        )
                    }
                    writeLegacyAliases(updatedAliases)
                }
            } catch (error: Exception) {
                if (!destinationExisted) destination.delete()
                throw error
            }
            replacedFonts.map(ParsedUserFont::file)
                .filter { it.canonicalFile != destination.canonicalFile }
                .forEach(File::delete)
            publishLibraryLocked()
            return storedFonts().first { it.file.canonicalFile == destination.canonicalFile }
        } finally {
            part.delete()
        }
    }

    private fun publishLibraryLocked() {
        _libraryState.value = scanLibrary(_libraryState.value.revision + 1)
    }

    private fun scanLibrary(revision: Long): ReaderFontLibraryState {
        fontsDirectory.mkdirs()
        managedFontsDirectory.mkdirs()
        legacyAliases = readLegacyAliases()
        return ReaderFontLibraryState(
            families = systemFamilies() + recommendedFamilies() + userFamilies(),
            revision = revision,
        )
    }

    private fun initialLibrary(): ReaderFontLibraryState = ReaderFontLibraryState(
        families = systemFamilies() + ReaderRecommendedFontCatalog.families,
        revision = 0,
    )

    private fun systemFamilies(): List<ReaderFontFamily> = listOf(
        ReaderFontFamily(
            id = publisherFamilyId,
            displayName = publisherFont,
            cssFamily = publisherFont,
            source = ReaderFontSource.PUBLISHER,
            category = ReaderFontCategory.PUBLISHER,
            variants = listOf(ReaderFontVariant(publisherVariantId, "Publisher", 400)),
        ),
        systemFamily(systemMinchoFamilyId, defaultMinchoFont),
        systemFamily(systemGothicFamilyId, defaultGothicFont),
    )

    private fun systemFamily(id: String, displayName: String) = ReaderFontFamily(
        id = id,
        displayName = displayName,
        cssFamily = displayName,
        source = ReaderFontSource.SYSTEM,
        category = ReaderFontCategory.SYSTEM,
        variants = systemWeights.map { weight ->
            ReaderFontVariant("wght-$weight-normal", ReaderRecommendedFontCatalog.weightName(weight), weight)
        },
    )

    private fun recommendedFamilies(): List<ReaderFontFamily> {
        val verifiedFiles = ReaderRecommendedFontCatalog.families
            .flatMap(ReaderFontFamily::variants)
            .mapNotNull(ReaderFontVariant::remoteFile)
            .distinctBy(ReaderRemoteFontFile::fileName)
            .associateWith { remote ->
                File(managedFontsDirectory, remote.fileName).takeIf { it.isVerified(remote) }
            }
        return ReaderRecommendedFontCatalog.families.map { family ->
        family.copy(variants = family.variants.map { variant ->
            val remote = requireNotNull(variant.remoteFile)
            variant.copy(localFile = verifiedFiles[remote])
        })
        }
    }

    private fun userFamilies(): List<ReaderFontFamily> {
        return parsedUserFonts().groupBy { it.family.id }.values.map { group ->
            val ordered = group.sortedWith(
                compareByDescending<ParsedUserFont> { it.file.lastModified() }
                    .thenByDescending { it.file.name },
            )
            val claimedSlots = mutableSetOf<FontLogicalSlot>()
            val visibleVariants = buildList {
                ordered.forEach { parsed ->
                    parsed.variants.groupBy(ReaderFontVariant::logicalSlot).forEach { (slot, variants) ->
                        if (claimedSlots.add(slot)) addAll(variants)
                    }
                }
            }
            ordered.first().family.copy(
                variants = visibleVariants.distinctBy(ReaderFontVariant::id)
                    .sortedWith(
                        compareBy(
                            ReaderFontVariant::weight,
                            ReaderFontVariant::italic,
                            ReaderFontVariant::displayName,
                        ),
                    ),
            )
        }.sortedBy { it.displayName.lowercase() }
    }

    private fun parsedUserFonts(): List<ParsedUserFont> = userFontFiles().map { file ->
            val extension = file.extension.lowercase()
            val metadata = if (extension in setOf("ttf", "otf")) {
                runCatching { SfntFontParser.parse(file) }.getOrNull()
            } else null
            if (metadata == null) {
                val family = ReaderFontFamily.user(file.nameWithoutExtension, null, emptyList())
                ParsedUserFont(
                    file,
                    family,
                    listOf(ReaderFontVariant("wght-400-normal", "Regular", 400, localFile = file)),
                )
            } else {
                val family = ReaderFontFamily.user(metadata.familyName, metadata.vendorId, emptyList())
                val variants = if (metadata.namedInstances.isEmpty()) {
                    val rangeSuffix = metadata.variableWeightRange
                        ?.let { range -> "-range-${range.first}-${range.last}" }
                        .orEmpty()
                    listOf(
                        ReaderFontVariant(
                            id = variantId(metadata.weight, metadata.italic) + rangeSuffix,
                            displayName = metadata.subfamilyName,
                            weight = metadata.weight,
                            italic = metadata.italic,
                            variableWeightRange = metadata.variableWeightRange,
                            localFile = file,
                        ),
                    )
                } else {
                    metadata.namedInstances.map { instance ->
                        val weight = instance.coordinates["wght"]?.toInt() ?: metadata.weight
                        ReaderFontVariant(
                            id = variantId(weight, metadata.italic, instance.coordinates),
                            displayName = instance.name,
                            weight = weight,
                            italic = metadata.italic,
                            variationSettings = instance.coordinates,
                            variableWeightRange = metadata.variableWeightRange,
                            localFile = file,
                        )
                    }
                }
                ParsedUserFont(file, family, variants)
            }
        }

    private fun userFontFiles(): List<File> {
        fontsDirectory.mkdirs()
        return fontsDirectory.listFiles()
            ?.filter { it.isFile && !it.isHidden && it.name.isSupportedFontFileName() }
            ?.sortedBy(File::getName)
            .orEmpty()
    }

    private fun localFontUrl(file: File): String? {
        val root = fontsDirectory.canonicalFile
        val canonical = file.canonicalFile
        if (canonical.parentFile != root && canonical.parentFile != managedFontsDirectory.canonicalFile) return null
        val relative = canonical.relativeTo(root).invariantSeparatorsPath
        return "https://appassets.androidplatform.net/fonts/${relative.pathEncoded()}"
    }

    private fun readLegacyAliases(): Map<String, FontLegacyAlias> = runCatching {
        if (!legacyAliasesFile.isFile) emptyMap()
        else fontAliasJson.decodeFromString<Map<String, FontLegacyAlias>>(legacyAliasesFile.readText())
    }.getOrDefault(emptyMap())

    private fun writeLegacyAliases(aliases: Map<String, FontLegacyAlias>) {
        fontsDirectory.mkdirs()
        if (aliases.isEmpty()) {
            legacyAliasesFile.delete()
            legacyAliases = emptyMap()
            return
        }
        val part = File(fontsDirectory, ".legacy-font-aliases-${System.nanoTime()}.part")
        try {
            val bytes = fontAliasJson.encodeToString(aliases).toByteArray(Charsets.UTF_8)
            FileOutputStream(part).use { output ->
                output.write(bytes)
                output.fd.sync()
            }
            moveAtomically(part, legacyAliasesFile)
            legacyAliases = aliases
        } finally {
            part.delete()
        }
    }

    companion object {
        const val publisherFont = "__hoshi_publisher_font__"
        const val defaultMinchoFont = "Noto Serif CJK JP"
        const val defaultGothicFont = "Noto Sans CJK JP"
        const val publisherFamilyId = "publisher"
        const val publisherVariantId = "publisher"
        const val systemMinchoFamilyId = "system:mincho"
        const val systemGothicFamilyId = "system:gothic"
        val defaultFonts = listOf(defaultMinchoFont, defaultGothicFont)
        val systemWeights = listOf(300, 400, 500, 600, 700)
        private val fontAliasJson = Json { ignoreUnknownKeys = true }

        fun isPublisherFont(name: String): Boolean = name == publisherFont
    }
}

private fun variantId(weight: Int, italic: Boolean, coordinates: Map<String, Float> = emptyMap()): String {
    val style = if (italic) "italic" else "normal"
    val axes = coordinates.toSortedMap().entries.joinToString("-") { (tag, value) ->
        "$tag-${if (value % 1f == 0f) value.toInt() else value}"
    }
    return listOf("wght-$weight", style, axes).filter(String::isNotEmpty).joinToString("-")
}

private fun ReaderFontVariant.logicalSlot(): FontLogicalSlot = FontLogicalSlot(
    weight = weight,
    italic = italic,
    standaloneVariableRange = variableWeightRange?.takeIf { variationSettings.isEmpty() },
)

private fun ReaderFontFamily.variantForAlias(alias: FontLegacyAlias): ReaderFontVariant? =
    if (alias.weight != null) {
        val aliasRange = if (alias.variableWeightStart != null && alias.variableWeightEnd != null) {
            alias.variableWeightStart..alias.variableWeightEnd
        } else null
        variants.firstOrNull { variant ->
            variant.weight == alias.weight &&
                (alias.italic == null || variant.italic == alias.italic) &&
                variant.logicalSlot().standaloneVariableRange == aliasRange
        }
    } else {
        variants.firstOrNull { it.weight == 400 && !it.italic } ?: variants.firstOrNull()
    }

private fun fontFaceIdentity(owner: String, variant: ReaderFontVariant, file: File): String =
    if (variant.variableWeightRange != null || variant.remoteFile?.variableWeightRange != null) {
        "$owner|${file.absolutePath}|${variant.italic}"
    } else {
        "$owner|${variant.id}|${file.absolutePath}"
    }

private fun SfntFontMetadata.logicalSlots(): Set<FontLogicalSlot> =
    if (namedInstances.isEmpty()) {
        setOf(FontLogicalSlot(weight, italic, variableWeightRange))
    } else {
        namedInstances.mapTo(mutableSetOf()) { instance ->
            FontLogicalSlot(instance.coordinates["wght"]?.toInt() ?: weight, italic)
        }
    }

private fun moveAtomically(source: File, destination: File) {
    destination.parentFile?.mkdirs()
    Files.move(source.toPath(), destination.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
}

private fun File.isVerified(remote: ReaderRemoteFontFile): Boolean =
    isFile && length() == remote.expectedSize && sha256File().equals(remote.sha256, ignoreCase = true)

private fun ContentResolver.displayName(uri: Uri): String {
    query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null).use { cursor: Cursor? ->
        if (cursor != null && cursor.moveToFirst()) {
            val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (index >= 0) {
                val name = cursor.getString(index)
                if (!name.isNullOrBlank()) return name
            }
        }
    }
    return uri.lastPathSegment?.substringAfterLast('/')?.takeIf { it.isNotBlank() } ?: "ImportedFont.ttf"
}

private fun String.isSupportedFontFileName(): Boolean =
    substringAfterLast('.', missingDelimiterValue = "").lowercase() in setOf("ttf", "otf", "woff", "woff2")

private fun String.safeFileName(): String {
    val value = substringAfterLast('/').substringAfterLast('\\')
    return if (value.startsWith('.')) "Imported$value" else value
}

private fun String.pathEncoded(): String = split('/').joinToString("/") { segment ->
    URLEncoder.encode(segment, Charsets.UTF_8.name()).replace("+", "%20")
}

internal fun fontFaceCss(cssFamily: String, variant: ReaderFontVariant, sourceUrl: String): String {
    val weight = (variant.variableWeightRange ?: variant.remoteFile?.variableWeightRange)
        ?.let { "${it.first} ${it.last}" }
        ?: variant.weight.toString()
    return """
        @font-face {
            font-family: ${cssFamily.cssDoubleQuotedString()};
            src: url(${sourceUrl.cssDoubleQuotedString()});
            font-weight: $weight;
            font-style: ${if (variant.italic) "italic" else "normal"};
            font-display: swap;
        }
    """.trimIndent()
}

internal fun String.cssDoubleQuotedString(): String = buildString(length + 2) {
    append('"')
    this@cssDoubleQuotedString.forEach { ch ->
        when (ch) {
            '\\' -> append("\\\\")
            '"' -> append("\\\"")
            '\n' -> append("\\a ")
            '\r' -> Unit
            else -> append(ch)
        }
    }
    append('"')
}
