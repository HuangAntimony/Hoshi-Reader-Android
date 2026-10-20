package moe.antimony.hoshi.features.diagnostics

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import moe.antimony.hoshi.R
import moe.antimony.hoshi.di.ApplicationScope
import moe.antimony.hoshi.di.FilesDir
import moe.antimony.hoshi.di.IoDispatcher
import moe.antimony.hoshi.ui.ErrorDiagnosticBuffer
import moe.antimony.hoshi.ui.ErrorDiagnosticEvents
import moe.antimony.hoshi.ui.UiText
import moe.antimony.hoshi.ui.diagnosticMessage
import moe.antimony.hoshi.ui.resolve

@Serializable
data class NonfatalDiagnosticRecord(
    val timestampMillis: Long,
    val operation: String,
    val exceptionType: String,
    val message: String,
    val stackTrace: String,
)

@Singleton
internal class NonfatalDiagnosticsStore internal constructor(
    private val directory: File,
    internal val ioDispatcher: CoroutineDispatcher,
    private val maxRecords: Int = 100,
) {
    @Inject constructor(@FilesDir filesDir: File, @IoDispatcher ioDispatcher: CoroutineDispatcher) :
        this(File(filesDir, "diagnostics"), ioDispatcher, 100)

    private val mutex = Mutex()
    private val recordsFile = File(directory, "handled-errors.json")

    init { require(maxRecords > 0) }

    suspend fun append(
        record: NonfatalDiagnosticRecord,
        onCorruption: ((SerializationException) -> NonfatalDiagnosticRecord)? = null,
    ) = withContext(ioDispatcher) {
        mutex.withLock {
            writeRecords((listOf(record) + readRecords(onCorruption)).take(maxRecords))
        }
    }

    suspend fun snapshot(
        onCorruption: ((SerializationException) -> NonfatalDiagnosticRecord)? = null,
    ): List<NonfatalDiagnosticRecord> = withContext(ioDispatcher) {
        mutex.withLock { readRecords(onCorruption) }
    }

    private fun readRecords(onCorruption: ((SerializationException) -> NonfatalDiagnosticRecord)?): List<NonfatalDiagnosticRecord> {
        if (!recordsFile.exists()) return emptyList()
        val serialized = recordsFile.readText()
        return try {
            Json.decodeFromString<List<NonfatalDiagnosticRecord>>(serialized).take(maxRecords)
        } catch (error: SerializationException) {
            val diagnostic = onCorruption?.invoke(error) ?: throw error
            Files.copy(recordsFile.toPath(), File(directory, "handled-errors.corrupt.json").toPath(), StandardCopyOption.REPLACE_EXISTING)
            listOf(diagnostic).also(::writeRecords)
        }
    }

    private fun writeRecords(records: List<NonfatalDiagnosticRecord>) {
        Files.createDirectories(directory.toPath())
        val temporary = File(directory, "handled-errors.json.tmp")
        try {
            temporary.writeText(Json.encodeToString(records))
            try {
                Files.move(temporary.toPath(), recordsFile.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temporary.toPath(), recordsFile.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            temporary.delete()
        }
    }
}

@Singleton
internal class NonfatalDiagnosticsLogger internal constructor(
    private val store: NonfatalDiagnosticsStore,
    private val applicationScope: CoroutineScope,
    private val events: ErrorDiagnosticBuffer,
    private val resolveOperation: (UiText) -> String,
) {
    @Inject constructor(
        store: NonfatalDiagnosticsStore,
        @ApplicationScope applicationScope: CoroutineScope,
        @ApplicationContext context: Context,
    ) : this(store, applicationScope, ErrorDiagnosticEvents.buffer, { it.resolve(context) })

    private val mutex = Mutex()
    private var collector: Job? = null

    @Synchronized
    fun start() {
        if (collector != null) return
        collector = applicationScope.launch(store.ioDispatcher) {
            for (ignored in events.notifications) {
                mutex.withLock { persistPending() }
            }
        }
    }

    /** A barrier for UI entry/export: include events whose background wake has not run yet. */
    suspend fun snapshot(): List<NonfatalDiagnosticRecord> = withContext(store.ioDispatcher) {
        mutex.withLock {
            persistPending()
            store.snapshot(::corruptHistoryDiagnostic)
        }
    }

    private fun corruptHistoryDiagnostic(error: SerializationException): NonfatalDiagnosticRecord =
        error.toNonfatalDiagnosticRecord(
            resolveOperation(UiText.Resource(R.string.diagnostics_load_failed)),
            System.currentTimeMillis(),
        )

    // Once drained, a batch belongs to application history even if the requesting screen closes.
    private suspend fun persistPending() = withContext(NonCancellable) {
        events.drain().forEach { event ->
            try {
                val operation = resolveOperation(event.operation)
                val record = event.error?.toNonfatalDiagnosticRecord(operation, event.timestampMillis)
                    ?: NonfatalDiagnosticRecord(
                        timestampMillis = event.timestampMillis,
                        operation = operation.boundedDiagnosticText(2_000),
                        exceptionType = "",
                        message = event.serviceDetails.orEmpty().boundedDiagnosticText(12_000),
                        stackTrace = "",
                    )
                store.append(record, ::corruptHistoryDiagnostic)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // Diagnostic persistence is best effort. Never replace or recursively log the original failure.
            }
        }
    }
}

private fun Throwable.toNonfatalDiagnosticRecord(operation: String, timestampMillis: Long): NonfatalDiagnosticRecord {
    val stackTrace = StringWriter().use { writer ->
        PrintWriter(writer).use { printStackTrace(it) }
        writer.toString()
    }
    return NonfatalDiagnosticRecord(
        timestampMillis = timestampMillis,
        operation = operation.boundedDiagnosticText(2_000),
        exceptionType = javaClass.name,
        message = diagnosticMessage().boundedDiagnosticText(12_000),
        stackTrace = stackTrace.boundedDiagnosticText(24_000),
    )
}

private fun String.boundedDiagnosticText(maxChars: Int): String =
    if (length <= maxChars) this else take(maxChars / 2) + "\n…\n" + takeLast(maxChars / 2)

internal fun createDiagnosticsShareFile(cacheDir: File, text: String, nowMillis: Long): File {
    val directory = File(cacheDir, "diagnostics-exports")
    Files.createDirectories(directory.toPath())
    val file = File(directory, diagnosticsExportFileName(nowMillis))
    file.writeText(text)
    directory.listFiles { candidate -> candidate.isFile && candidate != file }
        .orEmpty()
        .sortedByDescending { it.lastModified() }
        .drop(4)
        .forEach { it.delete() }
    return file
}

internal suspend fun loadDiagnosticsReport(
    nonfatalLogger: NonfatalDiagnosticsLogger,
    readErrorOperation: String,
    loadProcessReport: () -> ProcessExitDiagnosticsReport,
): ProcessExitDiagnosticsReport {
    val errors = try {
        nonfatalLogger.snapshot()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Exception) {
        listOf(error.toNonfatalDiagnosticRecord(readErrorOperation, System.currentTimeMillis()))
    }
    return loadProcessReport().copy(nonfatalErrors = errors)
}
