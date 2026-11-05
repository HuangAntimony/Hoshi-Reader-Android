@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package moe.antimony.hoshi.features.diagnostics

import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromByteArray
import kotlinx.serialization.protobuf.ProtoBuf
import kotlinx.serialization.protobuf.ProtoNumber

/** API 31+ exposes native exit traces as protobuf, not UTF-8 tombstone text. */
internal fun decodeNativeCrashTrace(bytes: ByteArray): String? {
    val tombstone = try {
        ProtoBuf.decodeFromByteArray<NativeTombstone>(bytes)
    } catch (_: IllegalArgumentException) {
        return null
    } catch (_: IndexOutOfBoundsException) {
        // The protobuf decoder can report invalid string lengths as bounds errors.
        return null
    }
    if (tombstone.pid <= 0 || tombstone.tid <= 0 || (tombstone.signal?.number ?: 0) <= 0) return null
    val thread = tombstone.threads[tombstone.tid]
    val addressWidth = if (tombstone.architecture == 0 || tombstone.architecture == 2) 8 else 16
    return buildString {
        tombstone.timestamp.takeIf { it.isNotBlank() }?.let { appendLine("Timestamp: $it") }
        append("pid: ${tombstone.pid}, tid: ${tombstone.tid}, name: ${thread?.name.orEmpty()}")
        if (tombstone.commandLine.isNotEmpty()) append("  >>> ${tombstone.commandLine.joinToString(" ")} <<<")
        appendLine()
        tombstone.signal?.let { signal ->
            append("signal ${signal.number}")
            if (signal.name.isNotBlank()) append(" (${signal.name})")
            append(", code ${signal.code}")
            if (signal.codeName.isNotBlank()) append(" (${signal.codeName})")
            if (signal.hasFaultAddress) append(", fault addr 0x${signal.faultAddress.hex()}")
            appendLine()
        }
        if (tombstone.abortMessage.isNotBlank()) appendLine("Abort message: '${tombstone.abortMessage}'")
        tombstone.causes.forEach { if (it.description.isNotBlank()) appendLine("Cause: ${it.description}") }
        thread?.notes?.forEach { appendLine(it) }
        if (!thread?.frames.isNullOrEmpty()) {
            appendLine("backtrace:")
            thread.frames.forEachIndexed { index, frame ->
                append("    #${index.toString().padStart(2, '0')} pc ${frame.relativePc.hex(addressWidth)}  ")
                append(frame.fileName.ifBlank { "[unknown]" })
                if (frame.fileMapOffset != 0L) append(" (offset 0x${frame.fileMapOffset.hex()})")
                if (frame.functionName.isNotBlank()) append(" (${frame.functionName}+${frame.functionOffset.toULong()})")
                if (frame.buildId.isNotBlank()) append(" (BuildId: ${frame.buildId})")
                appendLine()
            }
        }
    }
}

private fun Long.hex(width: Int = 1): String = toULong().toString(16).padStart(width, '0')

// Read only diagnostic fields from Android's stable tombstone wire schema. The
// protobuf decoder skips memory dumps/maps/logs and future or OEM-specific fields.
// https://android.googlesource.com/platform/system/core/+/refs/tags/android-14.0.0_r1/debuggerd/proto/tombstone.proto
@Serializable
private data class NativeTombstone(
    @ProtoNumber(1) val architecture: Int = 0,
    @ProtoNumber(4) val timestamp: String = "",
    @ProtoNumber(5) val pid: Int = 0,
    @ProtoNumber(6) val tid: Int = 0,
    @ProtoNumber(9) val commandLine: List<String> = emptyList(),
    @ProtoNumber(10) val signal: NativeSignal? = null,
    @ProtoNumber(14) val abortMessage: String = "",
    @ProtoNumber(15) val causes: List<NativeCause> = emptyList(),
    @ProtoNumber(16) val threads: Map<Int, NativeThread> = emptyMap(),
)

@Serializable
private data class NativeSignal(
    @ProtoNumber(1) val number: Int = 0,
    @ProtoNumber(2) val name: String = "",
    @ProtoNumber(3) val code: Int = 0,
    @ProtoNumber(4) val codeName: String = "",
    @ProtoNumber(8) val hasFaultAddress: Boolean = false,
    @ProtoNumber(9) val faultAddress: Long = 0,
)

@Serializable
private data class NativeCause(@ProtoNumber(1) val description: String = "")

@Serializable
private data class NativeThread(
    @ProtoNumber(2) val name: String = "",
    @ProtoNumber(4) val frames: List<NativeFrame> = emptyList(),
    @ProtoNumber(7) val notes: List<String> = emptyList(),
)

@Serializable
private data class NativeFrame(
    @ProtoNumber(1) val relativePc: Long = 0,
    @ProtoNumber(4) val functionName: String = "",
    @ProtoNumber(5) val functionOffset: Long = 0,
    @ProtoNumber(6) val fileName: String = "",
    @ProtoNumber(7) val fileMapOffset: Long = 0,
    @ProtoNumber(8) val buildId: String = "",
)
