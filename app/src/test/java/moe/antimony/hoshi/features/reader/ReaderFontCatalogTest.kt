package moe.antimony.hoshi.features.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ReaderFontCatalogTest {
    @Test
    fun recommendedVariantKeepsTheFontProvidedNameInsteadOfNormalizingItsWeight() {
        val remote = ReaderRemoteFontFile(
            path = "ofl/test/Test.ttf",
            fileName = "Test.ttf",
            expectedSize = 1,
            sha256 = "0".repeat(64),
        )

        val book = ReaderRecommendedVariantMetadata(weight = 400, displayName = "Book")
            .toReaderFontVariant(remote)
        val demiBold = ReaderRecommendedVariantMetadata(weight = 600, displayName = "DemiBold")
            .toReaderFontVariant(remote)

        assertEquals("Book", book.displayName)
        assertEquals(400, book.weight)
        assertEquals("DemiBold", demiBold.displayName)
        assertEquals(600, demiBold.weight)
    }

    @Test
    fun catalogExposesTheApprovedFamiliesAndWeights() {
        val weights = ReaderRecommendedFontCatalog.families.associate { family ->
            family.displayName to family.variants.map(ReaderFontVariant::weight)
        }

        assertEquals(
            listOf(
                "Noto Serif JP",
                "Shippori Mincho",
                "BIZ UDMincho",
                "Zen Old Mincho",
                "Noto Sans JP",
                "BIZ UDPGothic",
                "Zen Kaku Gothic New",
                "M PLUS 2",
                "M PLUS Rounded 1c",
                "Kiwi Maru",
                "Klee One",
            ),
            weights.keys.toList(),
        )
        assertEquals((200..900 step 100).toList(), weights.getValue("Noto Serif JP"))
        assertEquals((100..900 step 100).toList(), weights.getValue("Noto Sans JP"))
        assertEquals((100..900 step 100).toList(), weights.getValue("M PLUS 2"))
        assertEquals(listOf(400, 600), weights.getValue("Klee One"))
        assertEquals(
            listOf(
                "Thin",
                "ExtraLight",
                "Light",
                "Regular",
                "Medium",
                "SemiBold",
                "Bold",
                "ExtraBold",
                "Black",
            ),
            ReaderRecommendedFontCatalog.families
                .first { it.displayName == "Noto Sans JP" }
                .variants
                .map(ReaderFontVariant::displayName),
        )
        assertEquals(
            listOf("Regular", "SemiBold"),
            ReaderRecommendedFontCatalog.families
                .first { it.displayName == "Klee One" }
                .variants
                .map(ReaderFontVariant::displayName),
        )
    }

    @Test
    fun notoVariantsShareOneVariableFontDownload() {
        val family = ReaderRecommendedFontCatalog.families.first { it.displayName == "Noto Sans JP" }

        assertTrue(family.variants.all { it.remoteFile != null })
        family.variants.drop(1).forEach { variant ->
            assertSame(family.variants.first().remoteFile, variant.remoteFile)
        }
        assertTrue(requireNotNull(family.variants.first().remoteFile).url.endsWith("NotoSansJP%5Bwght%5D.ttf"))
    }

    @Test
    fun mPlus2VariantsShareOneVariableFontDownload() {
        val family = ReaderRecommendedFontCatalog.families.first { it.displayName == "M PLUS 2" }

        assertEquals(
            listOf(
                "Thin",
                "ExtraLight",
                "Light",
                "Regular",
                "Medium",
                "SemiBold",
                "Bold",
                "ExtraBold",
                "Black",
            ),
            family.variants.map(ReaderFontVariant::displayName),
        )
        assertTrue(family.variants.all { it.remoteFile != null })
        family.variants.drop(1).forEach { variant ->
            assertSame(family.variants.first().remoteFile, variant.remoteFile)
        }
        assertTrue(requireNotNull(family.variants.first().remoteFile).url.endsWith("MPLUS2%5Bwght%5D.ttf"))
    }

    @Test
    fun everyCatalogFileHasPinnedIntegrityMetadata() {
        val files = ReaderRecommendedFontCatalog.families
            .flatMap(ReaderFontFamily::variants)
            .mapNotNull(ReaderFontVariant::remoteFile)
            .distinct()

        assertTrue(files.isNotEmpty())
        files.forEach { file ->
            assertTrue("Missing size for ${file.path}", file.expectedSize > 0)
            assertTrue("Missing SHA-256 for ${file.path}", file.sha256.matches(Regex("[0-9a-f]{64}")))
            assertTrue(file.url.contains(ReaderRemoteFontFile.GOOGLE_FONTS_COMMIT))
        }
    }

    @Test
    fun stableIdsAndCssAliasesDoNotDependOnDisplayNames() {
        val recommended = ReaderRecommendedFontCatalog.families.first { it.displayName == "Klee One" }
        val imported = ReaderFontFamily.user(
            displayName = "Klee One",
            vendorId = "TEST",
            variants = emptyList(),
        )

        assertEquals("recommended:kleeone", recommended.id)
        assertEquals("hoshi-font-recommended-kleeone", recommended.cssFamily)
        assertTrue(imported.id.startsWith("user:"))
        assertTrue(imported.cssFamily.startsWith("hoshi-font-user-"))
        assertTrue(imported.cssFamily != recommended.cssFamily)
    }
}
