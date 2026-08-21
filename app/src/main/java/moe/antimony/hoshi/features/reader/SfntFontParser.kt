package moe.antimony.hoshi.features.reader

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

data class SfntFontMetadata(
    val familyName: String,
    val subfamilyName: String,
    val postScriptName: String?,
    val vendorId: String?,
    val weight: Int,
    val italic: Boolean,
    val namedInstances: List<SfntNamedInstance> = emptyList(),
    val variableWeightRange: IntRange? = null,
)

data class SfntNamedInstance(
    val name: String,
    val coordinates: Map<String, Float>,
)

class InvalidFontException(message: String, cause: Throwable? = null) :
    IllegalArgumentException(message, cause)

object SfntFontParser {
    private const val MAX_FONT_SIZE = 64 * 1024 * 1024

    fun parse(file: File): SfntFontMetadata {
        if (!file.isFile || file.length() !in 12..MAX_FONT_SIZE.toLong()) {
            throw InvalidFontException("Invalid font file size.")
        }
        return parse(file.readBytes())
    }

    fun parse(bytes: ByteArray): SfntFontMetadata = try {
        if (bytes.size !in 12..MAX_FONT_SIZE) throw InvalidFontException("Invalid font file size.")
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
        val signature = buffer.getInt(0)
        if (signature != 0x00010000 && signature != 0x4f54544f) {
            throw InvalidFontException("Unsupported font container.")
        }
        val tableCount = buffer.u16(4)
        if (tableCount !in 1..128) throw InvalidFontException("Invalid font table count.")
        checkedRange(bytes.size, 12, tableCount * 16)
        val tables = buildMap {
            repeat(tableCount) { index ->
                val record = 12 + index * 16
                val tag = bytes.copyOfRange(record, record + 4).toString(Charsets.US_ASCII)
                val offset = buffer.u32(record + 8)
                val length = buffer.u32(record + 12)
                checkedRange(bytes.size, offset, length)
                put(tag, Table(offset, length))
            }
        }
        val nameTable = tables["name"] ?: throw InvalidFontException("Missing name table.")
        val names = parseNames(buffer, bytes, nameTable)
        val os2 = tables["OS/2"]
        val head = tables["head"]
        val weight = os2?.takeIf { it.length >= 6 }?.let { buffer.u16(it.offset + 4) }
            ?.coerceIn(1, 1000) ?: 400
        val vendor = os2?.takeIf { it.length >= 62 }?.let {
            bytes.copyOfRange(it.offset + 58, it.offset + 62)
                .toString(Charsets.US_ASCII).trim().takeIf(String::isNotEmpty)
        }
        val italicFromOs2 = os2?.takeIf { it.length >= 64 }
            ?.let { buffer.u16(it.offset + 62) and 1 != 0 }
        val italicFromHead = head?.takeIf { it.length >= 46 }
            ?.let { buffer.u16(it.offset + 44) and 2 != 0 }
        val family = names[16] ?: names[1] ?: throw InvalidFontException("Missing font family name.")
        val subfamily = names[17] ?: names[2] ?: standardFontWeightName(weight)
        val variation = tables["fvar"]?.let { parseFvar(buffer, bytes, it, names) }
        SfntFontMetadata(
            familyName = family,
            subfamilyName = subfamily,
            postScriptName = names[6],
            vendorId = vendor,
            weight = weight,
            italic = italicFromOs2 ?: italicFromHead ?: subfamily.contains("italic", ignoreCase = true),
            namedInstances = variation?.instances.orEmpty(),
            variableWeightRange = variation?.weightRange,
        )
    } catch (error: InvalidFontException) {
        throw error
    } catch (error: RuntimeException) {
        throw InvalidFontException("Malformed font file.", error)
    }

    private fun parseNames(
        buffer: ByteBuffer,
        bytes: ByteArray,
        table: Table,
    ): Map<Int, String> {
        if (table.length < 6) throw InvalidFontException("Invalid name table.")
        val count = buffer.u16(table.offset + 2)
        if (count > 4096) throw InvalidFontException("Too many font names.")
        val storageOffset = buffer.u16(table.offset + 4)
        checkedRange(table.offset + table.length, table.offset + 6, count * 12)
        val candidates = mutableMapOf<Int, MutableList<NameCandidate>>()
        repeat(count) { index ->
            val record = table.offset + 6 + index * 12
            val platform = buffer.u16(record)
            val encoding = buffer.u16(record + 2)
            val language = buffer.u16(record + 4)
            val nameId = buffer.u16(record + 6)
            val length = buffer.u16(record + 8)
            val offset = table.offset + storageOffset + buffer.u16(record + 10)
            checkedRange(table.offset + table.length, offset, length)
            val raw = bytes.copyOfRange(offset, offset + length)
            val value = when (platform) {
                0, 3 -> if (length % 2 == 0) raw.toString(Charsets.UTF_16BE) else null
                1 -> raw.toString(Charsets.ISO_8859_1)
                else -> null
            }?.trim('\u0000', ' ')
            if (!value.isNullOrEmpty()) {
                val priority = when {
                    platform == 3 && encoding in setOf(1, 10) && language == 0x0409 -> 0
                    platform == 3 && encoding in setOf(1, 10) -> 1
                    platform == 0 -> 2
                    else -> 3
                }
                candidates.getOrPut(nameId, ::mutableListOf).add(NameCandidate(priority, value))
            }
        }
        return candidates.mapValues { (_, values) -> values.minBy(NameCandidate::priority).value }
    }

    private fun parseFvar(
        buffer: ByteBuffer,
        bytes: ByteArray,
        table: Table,
        names: Map<Int, String>,
    ): FvarMetadata {
        if (table.length < 16) throw InvalidFontException("Invalid fvar table.")
        val axesOffset = buffer.u16(table.offset + 4)
        val axisCount = buffer.u16(table.offset + 8)
        val axisSize = buffer.u16(table.offset + 10)
        val instanceCount = buffer.u16(table.offset + 12)
        val instanceSize = buffer.u16(table.offset + 14)
        if (axisCount !in 1..32 || instanceCount !in 0..256 || axisSize < 20 ||
            instanceSize < 4 + axisCount * 4
        ) {
            throw InvalidFontException("Invalid fvar dimensions.")
        }
        val axesStart = table.offset + axesOffset
        checkedRange(table.offset + table.length, axesStart, axisCount * axisSize)
        val axes = List(axisCount) { index ->
            val offset = axesStart + index * axisSize
            val tag = bytes.copyOfRange(offset, offset + 4).toString(Charsets.US_ASCII)
            val minimum = buffer.fixed(offset + 4)
            val maximum = buffer.fixed(offset + 12)
            if (!tag.matches(Regex("[ -~]{4}")) || minimum > maximum) {
                throw InvalidFontException("Invalid variation axis.")
            }
            Axis(tag, minimum, maximum)
        }
        val instancesStart = axesStart + axisCount * axisSize
        checkedRange(table.offset + table.length, instancesStart, instanceCount * instanceSize)
        val instances = List(instanceCount) { index ->
            val offset = instancesStart + index * instanceSize
            val nameId = buffer.u16(offset)
            val coordinates = buildMap {
                axes.forEachIndexed { axisIndex, axis ->
                    val value = buffer.fixed(offset + 4 + axisIndex * 4)
                    if (value < axis.minimum || value > axis.maximum) {
                        throw InvalidFontException("Variation coordinate is out of range.")
                    }
                    put(axis.tag, value)
                }
            }
            SfntNamedInstance(
                name = names[nameId] ?: throw InvalidFontException("Missing variation instance name."),
                coordinates = coordinates,
            )
        }
        val weightAxis = axes.firstOrNull { it.tag == "wght" }
        return FvarMetadata(
            instances = instances,
            weightRange = weightAxis?.let { axis ->
                axis.minimum.toInt().coerceIn(1, 1000)..axis.maximum.toInt().coerceIn(1, 1000)
            },
        )
    }

    private fun checkedRange(limit: Int, offset: Int, length: Int) {
        if (offset < 0 || length < 0 || offset > limit || length > limit - offset) {
            throw InvalidFontException("Font table is out of bounds.")
        }
    }

    private fun ByteBuffer.u16(offset: Int): Int = getShort(offset).toInt() and 0xffff

    private fun ByteBuffer.u32(offset: Int): Int {
        val value = getInt(offset).toLong() and 0xffffffffL
        if (value > Int.MAX_VALUE) throw InvalidFontException("Font table is too large.")
        return value.toInt()
    }

    private fun ByteBuffer.fixed(offset: Int): Float = getInt(offset) / 65536f

    private data class Table(val offset: Int, val length: Int)
    private data class NameCandidate(val priority: Int, val value: String)
    private data class Axis(val tag: String, val minimum: Float, val maximum: Float)
    private data class FvarMetadata(
        val instances: List<SfntNamedInstance>,
        val weightRange: IntRange?,
    )
}
