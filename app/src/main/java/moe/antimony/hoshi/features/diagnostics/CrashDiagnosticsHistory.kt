package moe.antimony.hoshi.features.diagnostics

/** Original crash sources stay separate; a record belongs to only one category. */
internal sealed interface CrashDiagnosticRecord {
    val timestampMillis: Long
    val reason: ProcessExitReason
    val summary: String

    data class Captured(val record: CapturedCrashRecord) : CrashDiagnosticRecord {
        override val timestampMillis: Long get() = record.timestampMillis
        override val reason: ProcessExitReason get() = ProcessExitReason.JavaCrash
        override val summary: String get() = record.text.lineSequence().firstOrNull().orEmpty()
    }

    data class SystemExit(val record: ProcessExitRecord) : CrashDiagnosticRecord {
        override val timestampMillis: Long get() = record.timestampMillis
        override val reason: ProcessExitReason get() = record.reason
        override val summary: String get() = record.description.orEmpty()
    }
}

internal val ProcessExitReason.isCrash: Boolean
    get() = this == ProcessExitReason.JavaCrash || this == ProcessExitReason.NativeCrash
