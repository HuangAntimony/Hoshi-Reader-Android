package moe.antimony.hoshi.features.reader

import androidx.annotation.StringRes
import moe.antimony.hoshi.R

internal sealed interface ReaderFontPickerEntry {
    data class Header(val category: ReaderFontCategory) : ReaderFontPickerEntry

    data class Family(val family: ReaderFontFamily) : ReaderFontPickerEntry

    data object Divider : ReaderFontPickerEntry
}

internal fun buildReaderFontPickerEntries(
    families: List<ReaderFontFamily>,
): List<ReaderFontPickerEntry> = buildList {
    val publisher = families.firstOrNull { it.source == ReaderFontSource.PUBLISHER }
    val system = families.filter { it.source == ReaderFontSource.SYSTEM }
    val recommended = families.filter { it.source == ReaderFontSource.RECOMMENDED }
    val imported = families.filter { it.source == ReaderFontSource.USER }

    val recommendedCategoryOrder = listOf(
        ReaderFontCategory.SERIF,
        ReaderFontCategory.SANS_SERIF,
        ReaderFontCategory.ROUNDED,
        ReaderFontCategory.HANDWRITING,
    )
    val recommendedGroups = recommendedCategoryOrder.mapNotNull { category ->
        recommended.filter { it.category == category }
            .takeIf(List<ReaderFontFamily>::isNotEmpty)
            ?.let { category to it }
    }

    publisher?.let { add(ReaderFontPickerEntry.Family(it)) }
    if (publisher != null && system.isNotEmpty()) add(ReaderFontPickerEntry.Divider)
    system.forEach { add(ReaderFontPickerEntry.Family(it)) }
    if (system.isNotEmpty() && (imported.isNotEmpty() || recommendedGroups.isNotEmpty())) {
        add(ReaderFontPickerEntry.Divider)
    }

    if (imported.isNotEmpty()) {
        add(ReaderFontPickerEntry.Header(ReaderFontCategory.IMPORTED))
        imported.forEach { add(ReaderFontPickerEntry.Family(it)) }
    }

    val needsDividerBeforeRecommended = imported.isNotEmpty() || (system.isEmpty() && publisher != null)
    if (recommendedGroups.isNotEmpty() && needsDividerBeforeRecommended) {
        add(ReaderFontPickerEntry.Divider)
    }
    recommendedGroups.forEach { (category, categoryFamilies) ->
        add(ReaderFontPickerEntry.Header(category))
        categoryFamilies.forEach { add(ReaderFontPickerEntry.Family(it)) }
    }

}

@StringRes
internal fun readerFontCategoryStringResource(category: ReaderFontCategory): Int = when (category) {
    ReaderFontCategory.SERIF -> R.string.reader_appearance_font_category_serif
    ReaderFontCategory.SANS_SERIF -> R.string.reader_appearance_font_category_sans
    ReaderFontCategory.ROUNDED -> R.string.reader_appearance_font_category_rounded
    ReaderFontCategory.HANDWRITING -> R.string.reader_appearance_font_category_handwriting
    ReaderFontCategory.IMPORTED -> R.string.reader_appearance_font_imported
    ReaderFontCategory.PUBLISHER,
    ReaderFontCategory.SYSTEM,
    -> error("$category is not a font-picker heading")
}

internal fun ReaderFontFamily.preferredVariant(rememberedVariantId: String?): ReaderFontVariant =
    variants.firstOrNull { it.id == rememberedVariantId }
        ?: variants.firstOrNull { it.weight == 400 && !it.italic }
        ?: variants.first()
