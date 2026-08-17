package moe.antimony.hoshi.features.reader

import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class SfntFontParserTest {
    @Test
    fun parsesTypographicFamilyVendorWeightAndItalicStyle() {
        val bytes = sfntFixture(
            family = "Legacy Family",
            typographicFamily = "Fixture Serif",
            subfamily = "Italic",
            typographicSubfamily = "SemiBold Italic",
            postScriptName = "FixtureSerif-SemiBoldItalic",
            vendorId = "TST1",
            weight = 600,
            italic = true,
        )

        val metadata = SfntFontParser.parse(bytes)

        assertEquals("Fixture Serif", metadata.familyName)
        assertEquals("TST1", metadata.vendorId)
        assertEquals(600, metadata.weight)
        assertTrue(metadata.italic)
        assertEquals("SemiBold Italic", metadata.subfamilyName)
        assertEquals("FixtureSerif-SemiBoldItalic", metadata.postScriptName)
    }

    @Test
    fun fallsBackToLegacyFamilyAndSubfamilyNames() {
        val metadata = SfntFontParser.parse(
            sfntFixture(
                family = "Fixture Sans",
                subfamily = "Regular",
                vendorId = "TST2",
                weight = 400,
            ),
        )

        assertEquals("Fixture Sans", metadata.familyName)
        assertEquals("Regular", metadata.subfamilyName)
    }

    @Test
    fun rejectsTruncatedOrOutOfBoundsTables() {
        assertThrows(InvalidFontException::class.java) {
            SfntFontParser.parse(byteArrayOf(0, 1, 2, 3))
        }

        val bytes = sfntFixture(family = "Broken", vendorId = "BAD1", weight = 400)
        ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN).putInt(20, Int.MAX_VALUE)
        assertThrows(InvalidFontException::class.java) {
            SfntFontParser.parse(bytes)
        }
    }

    @Test
    fun parsesVariableFontNamedInstancesAndCoordinates() {
        val metadata = SfntFontParser.parse(
            sfntFixture(
                family = "Variable Fixture",
                vendorId = "VAR1",
                weight = 400,
                namedInstances = listOf("Semi Bold" to 600f),
            ),
        )

        assertEquals(1, metadata.namedInstances.size)
        assertEquals("Semi Bold", metadata.namedInstances.single().name)
        assertEquals(600f, metadata.namedInstances.single().coordinates["wght"])
        assertEquals(100..900, metadata.variableWeightRange)
    }

    @Test
    fun parsesVariableWeightRangeWithoutNamedInstances() {
        val metadata = SfntFontParser.parse(
            sfntFixture(
                family = "Variable Fixture",
                vendorId = "VAR1",
                weight = 400,
                weightAxisRange = 200..800,
            ),
        )

        assertTrue(metadata.namedInstances.isEmpty())
        assertEquals(200..800, metadata.variableWeightRange)
    }
}

internal fun sfntFixture(
    family: String,
    typographicFamily: String? = null,
    subfamily: String = "Regular",
    typographicSubfamily: String? = null,
    postScriptName: String = family.replace(" ", "") + "-" + subfamily.replace(" ", ""),
    vendorId: String,
    weight: Int,
    italic: Boolean = false,
    namedInstances: List<Pair<String, Float>> = emptyList(),
    namedCoordinates: List<Pair<String, Map<String, Float>>> = emptyList(),
    weightAxisRange: IntRange? = null,
): ByteArray {
    val variableInstances = namedCoordinates.ifEmpty {
        namedInstances.map { (name, coordinate) -> name to mapOf("wght" to coordinate) }
    }
    val names = buildList {
        add(1 to family)
        add(2 to subfamily)
        add(6 to postScriptName)
        typographicFamily?.let { add(16 to it) }
        typographicSubfamily?.let { add(17 to it) }
        variableInstances.forEachIndexed { index, instance -> add(256 + index to instance.first) }
    }
    val encodedNames = names.map { (_, value) -> value.toByteArray(Charsets.UTF_16BE) }
    val nameHeaderSize = 6 + names.size * 12
    val nameTable = ByteBuffer.allocate(nameHeaderSize + encodedNames.sumOf(ByteArray::size))
        .order(ByteOrder.BIG_ENDIAN)
    nameTable.putShort(0)
    nameTable.putShort(names.size.toShort())
    nameTable.putShort(nameHeaderSize.toShort())
    var stringOffset = 0
    names.zip(encodedNames).forEach { (entry, bytes) ->
        nameTable.putShort(3)
        nameTable.putShort(1)
        nameTable.putShort(0x0409.toShort())
        nameTable.putShort(entry.first.toShort())
        nameTable.putShort(bytes.size.toShort())
        nameTable.putShort(stringOffset.toShort())
        stringOffset += bytes.size
    }
    encodedNames.forEach(nameTable::put)

    val os2 = ByteBuffer.allocate(64).order(ByteOrder.BIG_ENDIAN)
    os2.putShort(0, 4)
    os2.putShort(4, weight.toShort())
    vendorId.padEnd(4).take(4).toByteArray(Charsets.US_ASCII).copyInto(os2.array(), 58)
    os2.putShort(62, if (italic) 1 else 0)

    val hasVariation = variableInstances.isNotEmpty() || weightAxisRange != null
    val fvar = if (hasVariation) {
        val instances = variableInstances
        val axes = (instances.flatMap { it.second.keys } + listOfNotNull(weightAxisRange?.let { "wght" }))
            .distinct().sorted()
        val instanceSize = 4 + axes.size * 4
        ByteBuffer.allocate(16 + axes.size * 20 + instances.size * instanceSize)
            .order(ByteOrder.BIG_ENDIAN).apply {
            putShort(1)
            putShort(0)
            putShort(16)
            putShort(2)
            putShort(axes.size.toShort())
            putShort(20)
            putShort(instances.size.toShort())
            putShort(instanceSize.toShort())
            axes.forEachIndexed { index, axis ->
                put(axis.toByteArray(Charsets.US_ASCII))
                putInt((if (axis == "wght") weightAxisRange?.first ?: 100 else 0) shl 16)
                putInt((if (axis == "wght") 400 else 100) shl 16)
                putInt((if (axis == "wght") weightAxisRange?.last ?: 900 else 200) shl 16)
                putShort(0)
                putShort((512 + index).toShort())
            }
            instances.forEachIndexed { index, (_, coordinates) ->
                putShort((256 + index).toShort())
                putShort(0)
                axes.forEach { axis ->
                    val coordinate = coordinates[axis] ?: if (axis == "wght") 400f else 100f
                    putInt((coordinate * 65536f).toInt())
                }
            }
        }.array()
    } else null
    val tables = buildList {
        add("name" to nameTable.array())
        add("OS/2" to os2.array())
        fvar?.let { add("fvar" to it) }
    }
    val headerSize = 12 + tables.size * 16
    val totalSize = headerSize + tables.sumOf { (_, table) -> (table.size + 3) and -4 }
    val sfnt = ByteBuffer.allocate(totalSize).order(ByteOrder.BIG_ENDIAN)
    sfnt.putInt(0x00010000)
    sfnt.putShort(tables.size.toShort())
    sfnt.putShort(0)
    sfnt.putShort(0)
    sfnt.putShort(0)
    var offset = headerSize
    tables.forEach { (tag, table) ->
        sfnt.put(tag.toByteArray(Charsets.US_ASCII))
        sfnt.putInt(0)
        sfnt.putInt(offset)
        sfnt.putInt(table.size)
        val oldPosition = sfnt.position()
        sfnt.position(offset)
        sfnt.put(table)
        offset += (table.size + 3) and -4
        sfnt.position(oldPosition)
    }
    return sfnt.array()
}
