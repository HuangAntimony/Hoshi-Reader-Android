package moe.antimony.hoshi.features.reader

internal data class ReaderRecommendedFontGroup(
    val category: ReaderFontCategory,
    val families: List<ReaderFontFamily>,
)

internal data class ReaderFontPickerOrganization(
    val publisher: ReaderFontFamily?,
    val system: List<ReaderFontFamily>,
    val recommended: List<ReaderRecommendedFontGroup>,
    val imported: List<ReaderFontFamily>,
)

internal fun organizeReaderFontFamilies(
    families: List<ReaderFontFamily>,
): ReaderFontPickerOrganization {
    val recommendedFamilies = families.filter { it.source == ReaderFontSource.RECOMMENDED }
    val recommendedCategoryOrder = listOf(
        ReaderFontCategory.SERIF,
        ReaderFontCategory.SANS_SERIF,
        ReaderFontCategory.ROUNDED,
        ReaderFontCategory.HANDWRITING,
    )
    return ReaderFontPickerOrganization(
        publisher = families.firstOrNull { it.source == ReaderFontSource.PUBLISHER },
        system = families.filter { it.source == ReaderFontSource.SYSTEM },
        recommended = recommendedCategoryOrder.mapNotNull { category ->
            recommendedFamilies.filter { it.category == category }
                .takeIf(List<ReaderFontFamily>::isNotEmpty)
                ?.let { ReaderRecommendedFontGroup(category, it) }
        },
        imported = families.filter { it.source == ReaderFontSource.USER },
    )
}

internal fun ReaderFontFamily.preferredVariant(rememberedVariantId: String?): ReaderFontVariant =
    variants.firstOrNull { it.id == rememberedVariantId }
        ?: variants.firstOrNull { it.weight == 400 && !it.italic }
        ?: variants.first()
