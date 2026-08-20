package moe.antimony.hoshi.features.reader

import moe.antimony.hoshi.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class ReaderFontPickerOrganizationTest {
    @Test
    fun familyMenuUsesOnlyOneLevelOfVisibleHeadings() {
        val publisher = family("publisher", ReaderFontSource.PUBLISHER, ReaderFontCategory.PUBLISHER)
        val system = family("system", ReaderFontSource.SYSTEM, ReaderFontCategory.SYSTEM)
        val serifA = family("serif-a", ReaderFontSource.RECOMMENDED, ReaderFontCategory.SERIF)
        val sans = family("sans", ReaderFontSource.RECOMMENDED, ReaderFontCategory.SANS_SERIF)
        val rounded = family("rounded", ReaderFontSource.RECOMMENDED, ReaderFontCategory.ROUNDED)
        val handwriting = family("handwriting", ReaderFontSource.RECOMMENDED, ReaderFontCategory.HANDWRITING)
        val serifB = family("serif-b", ReaderFontSource.RECOMMENDED, ReaderFontCategory.SERIF)
        val imported = family("imported", ReaderFontSource.USER, ReaderFontCategory.IMPORTED)

        val entries = buildReaderFontPickerEntries(
            listOf(imported, sans, publisher, serifA, system, rounded, handwriting, serifB),
        )

        assertEquals(
            listOf(
                ReaderFontPickerEntry.Family(publisher),
                ReaderFontPickerEntry.Divider,
                ReaderFontPickerEntry.Family(system),
                ReaderFontPickerEntry.Divider,
                ReaderFontPickerEntry.Header(ReaderFontCategory.IMPORTED),
                ReaderFontPickerEntry.Family(imported),
                ReaderFontPickerEntry.Divider,
                ReaderFontPickerEntry.Header(ReaderFontCategory.SERIF),
                ReaderFontPickerEntry.Family(serifA),
                ReaderFontPickerEntry.Family(serifB),
                ReaderFontPickerEntry.Header(ReaderFontCategory.SANS_SERIF),
                ReaderFontPickerEntry.Family(sans),
                ReaderFontPickerEntry.Header(ReaderFontCategory.ROUNDED),
                ReaderFontPickerEntry.Family(rounded),
                ReaderFontPickerEntry.Header(ReaderFontCategory.HANDWRITING),
                ReaderFontPickerEntry.Family(handwriting),
            ),
            entries,
        )
    }

    @Test
    fun familyMenuOmitsEmptySectionsAndRedundantDividers() {
        val system = family("system", ReaderFontSource.SYSTEM, ReaderFontCategory.SYSTEM)
        val imported = family("imported", ReaderFontSource.USER, ReaderFontCategory.IMPORTED)

        assertEquals(
            listOf(
                ReaderFontPickerEntry.Family(system),
                ReaderFontPickerEntry.Divider,
                ReaderFontPickerEntry.Header(ReaderFontCategory.IMPORTED),
                ReaderFontPickerEntry.Family(imported),
            ),
            buildReaderFontPickerEntries(listOf(imported, system)),
        )
    }

    @Test
    fun importedSectionUsesItsVisibleLocalizedHeading() {
        assertEquals(
            R.string.reader_appearance_font_imported,
            readerFontCategoryStringResource(ReaderFontCategory.IMPORTED),
        )
    }

    @Test
    fun familySelectionRestoresRememberedVariantThenFallsBackToRegular() {
        val light = ReaderFontVariant("light", "Light", 300)
        val regular = ReaderFontVariant("regular", "Regular", 400)
        val bold = ReaderFontVariant("bold", "Bold", 700)
        val family = family(
            id = "recommended",
            source = ReaderFontSource.RECOMMENDED,
            category = ReaderFontCategory.SERIF,
            variants = listOf(light, regular, bold),
        )

        assertSame(bold, family.preferredVariant("bold"))
        assertSame(regular, family.preferredVariant("missing"))
        assertSame(
            light,
            family(
                id = "without-regular",
                source = ReaderFontSource.RECOMMENDED,
                category = ReaderFontCategory.SERIF,
                variants = listOf(light, bold),
            ).preferredVariant(null),
        )
    }

    private fun family(
        id: String,
        source: ReaderFontSource,
        category: ReaderFontCategory,
        variants: List<ReaderFontVariant> = listOf(ReaderFontVariant("regular", "Regular", 400)),
    ) = ReaderFontFamily(
        id = id,
        displayName = id,
        cssFamily = "hoshi-$id",
        source = source,
        category = category,
        variants = variants,
    )
}
