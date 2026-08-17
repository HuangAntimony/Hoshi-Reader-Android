package moe.antimony.hoshi.features.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class ReaderFontPickerOrganizationTest {
    @Test
    fun publisherIsStandaloneAndRecommendedFamiliesAreGroupedByType() {
        val publisher = family("publisher", ReaderFontSource.PUBLISHER, ReaderFontCategory.PUBLISHER)
        val system = family("system", ReaderFontSource.SYSTEM, ReaderFontCategory.SYSTEM)
        val serifA = family("serif-a", ReaderFontSource.RECOMMENDED, ReaderFontCategory.SERIF)
        val sans = family("sans", ReaderFontSource.RECOMMENDED, ReaderFontCategory.SANS_SERIF)
        val rounded = family("rounded", ReaderFontSource.RECOMMENDED, ReaderFontCategory.ROUNDED)
        val handwriting = family("handwriting", ReaderFontSource.RECOMMENDED, ReaderFontCategory.HANDWRITING)
        val serifB = family("serif-b", ReaderFontSource.RECOMMENDED, ReaderFontCategory.SERIF)
        val imported = family("imported", ReaderFontSource.USER, ReaderFontCategory.IMPORTED)

        val organization = organizeReaderFontFamilies(
            listOf(imported, sans, publisher, serifA, system, rounded, handwriting, serifB),
        )

        assertSame(publisher, organization.publisher)
        assertEquals(listOf(system), organization.system)
        assertEquals(listOf(imported), organization.imported)
        assertEquals(
            listOf(
                ReaderFontCategory.SERIF to listOf(serifA, serifB),
                ReaderFontCategory.SANS_SERIF to listOf(sans),
                ReaderFontCategory.ROUNDED to listOf(rounded),
                ReaderFontCategory.HANDWRITING to listOf(handwriting),
            ),
            organization.recommended.map { it.category to it.families },
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
