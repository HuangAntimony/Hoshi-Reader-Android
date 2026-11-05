package moe.antimony.hoshi.features.diagnostics

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream

internal fun readProcessExitTrace(reason: ProcessExitReason, openStream: () -> InputStream?): String? =
    try {
        openStream()?.use { input ->
            val native = reason == ProcessExitReason.NativeCrash
            val limit = if (native) MAX_NATIVE_TRACE_BYTES else ProcessExitDiagnosticsReport.MAX_TRACE_BYTES
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (output.size() < limit) {
                val read = input.read(buffer, 0, minOf(buffer.size, limit - output.size()))
                if (read == -1) break
                if (read == 0) {
                    val byte = input.read()
                    if (byte == -1) break
                    output.write(byte)
                } else {
                    output.write(buffer, 0, read)
                }
            }
            // Protobuf needs a complete payload; retained ANR text can still use its prefix.
            val truncated = native && output.size() == limit && input.read() != -1
            val bytes = output.toByteArray()
            if (native && !truncated) {
                decodeNativeCrashTrace(bytes)?.let { return@use it }
            }
            bytes.copyOf(minOf(bytes.size, ProcessExitDiagnosticsReport.MAX_TRACE_BYTES))
                .toString(Charsets.UTF_8).readableDiagnosticTextOrNull()
        }
    } catch (_: IOException) {
        null
    } catch (_: SecurityException) {
        null
    }

private const val MAX_NATIVE_TRACE_BYTES = 8 * 1024 * 1024
