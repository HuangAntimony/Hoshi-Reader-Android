package moe.antimony.hoshi.features.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.Assert.assertThrows
import java.io.File
import kotlin.io.path.createTempDirectory

class ReaderFontManagerTest {
    @Test
    fun importFontStoresFileAndUsesBasenameAsFontNameLikeIos() {
        val root = createTempDirectory().toFile()
        val source = File(root, "KleeOne-SemiBold.woff2").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        val manager = ReaderFontManager(root)

        val imported = manager.importFont(source)

        assertEquals("KleeOne-SemiBold", imported.name)
        assertEquals("KleeOne-SemiBold.woff2", imported.fileName)
        assertEquals(listOf("KleeOne-SemiBold"), manager.storedFonts().map { it.name })
        assertEquals(source.readBytes().toList(), imported.file.readBytes().toList())
    }

    @Test
    fun deleteFontRemovesImportedFontAndLeavesDefaultsUntouched() {
        val root = createTempDirectory().toFile()
        val source = File(root, "KleeOne-SemiBold.woff").apply { writeBytes(byteArrayOf(1)) }
        val manager = ReaderFontManager(root)

        manager.importFont(source)
        manager.deleteFont("KleeOne-SemiBold")

        assertTrue(manager.storedFonts().isEmpty())
        assertTrue(manager.isDefaultFont("Noto Serif CJK JP"))
        assertFalse(manager.isDefaultFont("KleeOne-SemiBold"))
    }

    @Test
    fun legacySystemFontNamesRemainStableForSettingsCompatibility() {
        assertEquals(listOf("Noto Serif CJK JP", "Noto Sans CJK JP"), ReaderFontManager.defaultFonts)
    }

    @Test
    fun popupFontFaceCssExposesImportedFontsThroughLocalWebViewBridge() {
        val root = createTempDirectory().toFile()
        val manager = ReaderFontManager(root)
        manager.importFont(File(root, "Klee One.woff2").apply { writeBytes(byteArrayOf(1)) })

        val css = manager.popupFontFaceCss()

        assertTrue(css.contains("@font-face"))
        assertTrue(css.contains("""font-family: "hoshi-font-user-"""))
        assertTrue(css.contains("""font-family: "Klee One";"""))
        assertTrue(css.contains("""src: url("https://appassets.androidplatform.net/fonts/Klee%20One.woff2");"""))
        assertTrue(css.contains("font-display: swap;"))
        assertTrue(css.contains("font-weight: 400;"))
    }

    @Test
    fun importFontRejectsNonFontExtensions() {
        val root = createTempDirectory().toFile()
        val source = File(root, "not-a-font.zip").apply { writeBytes(byteArrayOf(1)) }
        val manager = ReaderFontManager(root)

        assertThrows(IllegalArgumentException::class.java) {
            manager.importFont(source)
        }
    }

    @Test
    fun newMalformedSfntImportIsRejectedButLegacyFilesRemainVisible() {
        val root = createTempDirectory().toFile()
        val fonts = File(root, "Fonts").apply { mkdirs() }
        File(fonts, "Legacy.ttf").writeBytes(byteArrayOf(1, 2, 3))
        val manager = ReaderFontManager(root)

        assertEquals(listOf("Legacy"), manager.storedFonts().map(ReaderFontInfo::name))
        assertThrows(InvalidFontException::class.java) {
            manager.importFont(File(root, "Broken.ttf").apply { writeBytes(byteArrayOf(1, 2, 3)) })
        }
    }

    @Test
    fun sfntImportsAreGroupedByFamilyAndSameVariantIsReplaced() {
        val root = createTempDirectory().toFile()
        val sources = File(root, "sources").apply { mkdirs() }
        val manager = ReaderFontManager(root)
        val regular = File(sources, "one.ttf").apply {
            writeBytes(sfntFixture(family = "Fixture Family", vendorId = "TEST", weight = 400))
        }
        val bold = File(sources, "two.ttf").apply {
            writeBytes(sfntFixture(family = "Fixture Family", subfamily = "Bold", vendorId = "TEST", weight = 700))
        }

        manager.importFont(regular)
        manager.importFont(bold)
        val family = manager.fontFamilies().single { it.displayName == "Fixture Family" }

        assertEquals(listOf(400, 700), family.variants.map(ReaderFontVariant::weight))
        assertEquals(2, manager.storedFonts().size)

        regular.writeBytes(sfntFixture(family = "Fixture Family", vendorId = "TEST", weight = 400) + byteArrayOf(9))
        manager.importFont(regular)
        assertEquals(2, manager.storedFonts().size)
    }

    @Test
    fun webResourceBridgeAllowsOnlyDirectUserFilesAndManagedSystemFiles() {
        val root = createTempDirectory().toFile()
        val manager = ReaderFontManager(root)
        val user = File(root, "Fonts/User.woff").apply { parentFile?.mkdirs(); writeBytes(byteArrayOf(1)) }
        val system = File(root, "Fonts/System/System.ttf").apply { parentFile?.mkdirs(); writeBytes(byteArrayOf(2)) }

        assertEquals(user.canonicalFile, manager.fontFileForRequest("User.woff"))
        assertEquals(system.canonicalFile, manager.fontFileForRequest("System/System.ttf"))
        assertEquals(null, manager.fontFileForRequest("../outside.ttf"))
        assertEquals(null, manager.fontFileForRequest("Other/nested.ttf"))
    }

    @Test
    fun legacyFileBasenameSelectionRestoresItsExactVariant() {
        val root = createTempDirectory().toFile()
        val fonts = File(root, "Fonts").apply { mkdirs() }
        File(fonts, "Fixture-Regular.ttf").writeBytes(
            sfntFixture(family = "Fixture Family", vendorId = "TEST", weight = 400),
        )
        File(fonts, "Fixture-Bold.ttf").writeBytes(
            sfntFixture(family = "Fixture Family", subfamily = "Bold", vendorId = "TEST", weight = 700),
        )
        val manager = ReaderFontManager(root)

        val spec = manager.resolveRenderSpec(selectedFont = "Fixture-Bold")

        assertEquals(700, spec.weight)
    }

    @Test
    fun replacingLegacyVariantPreservesItsSelectionAndCssAlias() {
        val root = createTempDirectory().toFile()
        val fonts = File(root, "Fonts").apply { mkdirs() }
        val legacy = File(fonts, "Fixture-Bold.ttf").apply {
            writeBytes(
                sfntFixture(family = "Fixture Family", subfamily = "Bold", vendorId = "TEST", weight = 700),
            )
            setLastModified(1_000L)
        }
        val manager = ReaderFontManager(root)
        val replacement = File(root, "Replacement.ttf").apply {
            writeBytes(
                sfntFixture(family = "Fixture Family", subfamily = "Bold", vendorId = "TEST", weight = 700) +
                    byteArrayOf(9),
            )
        }

        val imported = manager.importFont(replacement)
        val spec = manager.resolveRenderSpec(selectedFont = "Fixture-Bold")

        assertFalse(legacy.exists())
        assertEquals(700, spec.weight)
        val legacyAliasFaces = manager.popupFontFaceCss().split("@font-face")
            .filter { it.contains("""font-family: "Fixture-Bold";""") }
        assertEquals(1, legacyAliasFaces.size)
        assertTrue(legacyAliasFaces.single().contains("font-weight: 700;"))
        assertTrue(legacyAliasFaces.single().contains(imported.file.name))
    }

    @Test
    fun failedAtomicInstallLeavesReplacedLegacyVariantUntouched() {
        val root = createTempDirectory().toFile()
        val fonts = File(root, "Fonts").apply { mkdirs() }
        val legacy = File(fonts, "Fixture-Regular.ttf").apply {
            writeBytes(sfntFixture(family = "Fixture Family", vendorId = "TEST", weight = 400))
        }
        val manager = ReaderFontManager(root)
        val replacement = File(root, "Replacement.ttf").apply {
            writeBytes(sfntFixture(family = "Fixture Family", vendorId = "TEST", weight = 400) + byteArrayOf(9))
        }
        val familySuffix = ReaderFontFamily.user("Fixture Family", "TEST", emptyList())
            .id.substringAfter(':')
        File(fonts, "$familySuffix-${replacement.sha256File().take(16)}.ttf").mkdirs()

        assertThrows(Exception::class.java) { manager.importFont(replacement) }

        assertTrue(legacy.exists())
        assertEquals(400, manager.resolveRenderSpec(selectedFont = "Fixture-Regular").weight)
    }

    @Test
    fun concurrentRefreshesPublishStrictlyIncreasingRevisions() {
        val root = createTempDirectory().toFile()
        val manager = ReaderFontManager(root)
        val initialRevision = manager.libraryState.value.revision
        val threads = List(12) { Thread(manager::refresh).apply(Thread::start) }

        threads.forEach(Thread::join)

        assertEquals(initialRevision + threads.size, manager.libraryState.value.revision)
        assertNotEquals(initialRevision, manager.libraryState.value.revision)
    }

    @Test
    fun replacingOneVariableFontSlotKeepsOtherInstancesAndSharedFile() {
        val root = createTempDirectory().toFile()
        val fonts = File(root, "Fonts").apply { mkdirs() }
        val variable = File(fonts, "Fixture-Variable.ttf").apply {
            writeBytes(
                sfntFixture(
                    family = "Fixture Family",
                    vendorId = "TEST",
                    weight = 400,
                    namedInstances = listOf("Regular" to 400f, "Bold" to 700f),
                ),
            )
            setLastModified(1_000L)
        }
        val manager = ReaderFontManager(root)
        val staticBold = File(root, "Fixture-Static-Bold.ttf").apply {
            writeBytes(
                sfntFixture(
                    family = "Fixture Family",
                    subfamily = "Bold",
                    vendorId = "TEST",
                    weight = 700,
                ),
            )
        }

        manager.importFont(staticBold)

        val family = manager.fontFamilies().single { it.displayName == "Fixture Family" }
        assertTrue(variable.exists())
        assertEquals(listOf(400, 700), family.variants.map(ReaderFontVariant::weight))
        assertEquals(variable.canonicalFile, family.variants.single { it.weight == 400 }.localFile?.canonicalFile)
        assertNotEquals(variable.canonicalFile, family.variants.single { it.weight == 700 }.localFile?.canonicalFile)
    }

    @Test
    fun replacingVariableBaseWeightDoesNotOverwriteItsSharedBackingFile() {
        val root = createTempDirectory().toFile()
        val manager = ReaderFontManager(root)
        val variableSource = File(root, "Fixture-Variable.ttf").apply {
            writeBytes(
                sfntFixture(
                    family = "Fixture Family",
                    vendorId = "TEST",
                    weight = 400,
                    namedInstances = listOf("Regular" to 400f, "Bold" to 700f),
                ),
            )
        }
        val variable = manager.importFont(variableSource).file.apply { setLastModified(1_000L) }
        manager.refresh()

        manager.importFont(
            File(root, "Fixture-Static-Regular.ttf").apply {
                writeBytes(sfntFixture(family = "Fixture Family", vendorId = "TEST", weight = 400))
            },
        )

        val family = manager.fontFamilies().single { it.displayName == "Fixture Family" }
        assertTrue(variable.exists())
        assertEquals(listOf(400, 700), family.variants.map(ReaderFontVariant::weight))
        assertEquals(variable.canonicalFile, family.variants.single { it.weight == 700 }.localFile?.canonicalFile)
        assertNotEquals(variable.canonicalFile, family.variants.single { it.weight == 400 }.localFile?.canonicalFile)
    }

    @Test
    fun variableInstancesWithSameWeightAndDifferentCoordinatesRemainSelectable() {
        val root = createTempDirectory().toFile()
        val fonts = File(root, "Fonts").apply { mkdirs() }
        File(fonts, "Fixture-Variable.ttf").writeBytes(
            sfntFixture(
                family = "Fixture Family",
                vendorId = "TEST",
                weight = 400,
                namedCoordinates = listOf(
                    "Condensed" to mapOf("wght" to 400f, "wdth" to 75f),
                    "Expanded" to mapOf("wght" to 400f, "wdth" to 125f),
                ),
            ),
        )
        val manager = ReaderFontManager(root)

        val variants = manager.fontFamilies().single { it.displayName == "Fixture Family" }.variants

        assertEquals(2, variants.size)
        assertEquals(setOf(75f, 125f), variants.mapNotNull { it.variationSettings["wdth"] }.toSet())
        val renderSpec = manager.resolveRenderSpec(
            selectedFont = "Fixture Family",
            familyId = manager.fontFamilies().single { it.displayName == "Fixture Family" }.id,
            variantId = variants.last().id,
        )
        assertEquals(100..900, renderSpec.faces.single().variableWeightRange)
    }

    @Test
    fun variableFontWithoutNamedInstancesStillExposesItsWeightRange() {
        val root = createTempDirectory().toFile()
        val fonts = File(root, "Fonts").apply { mkdirs() }
        val variable = File(fonts, "Fixture-Variable.ttf").apply { writeBytes(
            sfntFixture(
                family = "Fixture Family",
                vendorId = "TEST",
                weight = 400,
                weightAxisRange = 200..800,
            ),
        ) }
        val manager = ReaderFontManager(root)
        manager.importFont(
            File(root, "Fixture-Static-Regular.ttf").apply {
                writeBytes(sfntFixture(family = "Fixture Family", vendorId = "TEST", weight = 400))
            },
        )
        val family = manager.fontFamilies().single { it.displayName == "Fixture Family" }
        val variableVariant = family.variants.single { it.variableWeightRange == 200..800 }

        val spec = manager.resolveRenderSpec("Fixture Family", family.id, variableVariant.id)

        assertTrue(variable.exists())
        assertEquals(2, family.variants.size)
        assertTrue(spec.faces.any { it.variableWeightRange == 200..800 })
    }

    @Test
    fun replacedVariableRangeLegacyAliasDoesNotResolveToCoexistingStaticWeight() {
        val root = createTempDirectory().toFile()
        val fonts = File(root, "Fonts").apply { mkdirs() }
        val legacyVariable = File(fonts, "Legacy-Variable.ttf").apply {
            writeBytes(
                sfntFixture(
                    family = "Fixture Family",
                    vendorId = "TEST",
                    weight = 400,
                    weightAxisRange = 200..800,
                ),
            )
            setLastModified(1_000L)
        }
        val manager = ReaderFontManager(root)
        manager.importFont(
            File(root, "Static-Regular.ttf").apply {
                writeBytes(sfntFixture(family = "Fixture Family", vendorId = "TEST", weight = 400))
            },
        )
        manager.importFont(
            File(root, "Variable-Replacement.ttf").apply {
                writeBytes(
                    sfntFixture(
                        family = "Fixture Family",
                        vendorId = "TEST",
                        weight = 400,
                        weightAxisRange = 200..800,
                    ) + byteArrayOf(9),
                )
            },
        )

        val spec = manager.resolveRenderSpec(selectedFont = "Legacy-Variable")
        val aliasFaces = manager.popupFontFaceCss().split("@font-face")
            .filter { it.contains("""font-family: "Legacy-Variable";""") }

        assertFalse(legacyVariable.exists())
        assertTrue(spec.variantId.contains("range-200-800"))
        assertEquals(1, aliasFaces.size)
        assertTrue(aliasFaces.single().contains("font-weight: 200 800;"))
    }

    @Test
    fun deletingFamilyRemovesBackingFilesHiddenByNewerStaticVariants() {
        val root = createTempDirectory().toFile()
        val fonts = File(root, "Fonts").apply { mkdirs() }
        File(fonts, "Fixture-Variable.ttf").apply {
            writeBytes(
                sfntFixture(
                    family = "Fixture Family",
                    vendorId = "TEST",
                    weight = 400,
                    namedInstances = listOf("Regular" to 400f, "Bold" to 700f),
                ),
            )
            setLastModified(1_000L)
        }
        val manager = ReaderFontManager(root)
        manager.importFont(
            File(root, "Regular.ttf").apply {
                writeBytes(sfntFixture(family = "Fixture Family", vendorId = "TEST", weight = 400))
            },
        )
        manager.importFont(
            File(root, "Bold.ttf").apply {
                writeBytes(
                    sfntFixture(family = "Fixture Family", subfamily = "Bold", vendorId = "TEST", weight = 700),
                )
            },
        )
        val familyId = manager.fontFamilies().single { it.displayName == "Fixture Family" }.id

        manager.deleteFamily(familyId)

        assertTrue(manager.storedFonts().isEmpty())
        assertTrue(File(root, "Fonts").listFiles().orEmpty().none { it.isFile })
    }
}
