package moe.antimony.hoshi.features.diagnostics

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.content.pm.PackageInfo
import android.os.Build
import androidx.annotation.RequiresApi
import java.io.File
import java.io.IOException
import java.io.PrintWriter
import java.io.StringWriter
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import kotlin.system.exitProcess

enum class ProcessExitReason(val label: String) {
    Anr("ANR"),
    JavaCrash("Java crash"),
    NativeCrash("Native crash"),
    LowMemory("Low memory kill"),
    ExcessiveResourceUsage("Excessive resource usage"),
    InitializationFailure("Initialization failure"),
    UserRequested("User requested"),
    Signaled("Signaled"),
    Other("Other"),
    Unknown("Unknown"),
}

data class ProcessExitRecord(
    val timestampMillis: Long,
    val reason: ProcessExitReason,
    val status: Int,
    val importance: Int,
    val pssKb: Long,
    val rssKb: Long,
    val description: String?,
    val trace: String?,
)

data class CapturedCrashRecord(
    val timestampMillis: Long,
    val text: String,
)

data class ProcessExitDiagnosticsReport(
    val packageName: String,
    val versionName: String,
    val versionCode: Long,
    val sdkInt: Int,
    val capturedCrashes: List<CapturedCrashRecord> = emptyList(),
    val records: List<ProcessExitRecord>,
) {
    fun toShareText(): String = buildString {
        appendLine("Hoshi Diagnostics")
        appendLine("Package: $packageName")
        appendLine("Version: $versionName ($versionCode)")
        appendLine("Android SDK: $sdkInt")
        appendLine()

        if (capturedCrashes.isNotEmpty()) {
            appendLine("Captured Crashes")
            capturedCrashes.forEachIndexed { index, crash ->
                appendLine("Captured Crash ${index + 1}")
                appendLine("Time: ${Instant.ofEpochMilli(crash.timestampMillis)}")
                appendLine("Trace:")
                appendLine(crash.text.takeBoundedTrace())
                if (index != capturedCrashes.lastIndex) appendLine()
            }
            appendLine()
        }

        if (records.isEmpty()) {
            if (sdkInt < Build.VERSION_CODES.R) {
                appendLine("Process exit history is available on Android 11 and later.")
            } else {
                appendLine("No recent process exits recorded by Android.")
            }
            return@buildString
        }

        records.forEachIndexed { index, record ->
            appendLine("Exit ${index + 1}")
            appendLine("Time: ${Instant.ofEpochMilli(record.timestampMillis)}")
            appendLine("Reason: ${record.reason.label}")
            appendLine("Status: ${record.status}")
            appendLine("Importance: ${record.importance}")
            appendLine("PSS: ${record.pssKb} kB")
            appendLine("RSS: ${record.rssKb} kB")
            record.description?.takeIf { it.isNotBlank() }?.let {
                appendLine("Description: $it")
            }
            record.trace?.readableDiagnosticTextOrNull()?.let {
                appendLine("Trace:")
                appendLine(it.takeBoundedTrace())
            }
            if (index != records.lastIndex) appendLine()
        }
    }

    private fun String.takeBoundedTrace(): String =
        if (length <= MAX_TRACE_CHARS) {
            this
        } else {
            val trace = this
            val headLength = MAX_TRACE_CHARS / 2
            val tailLength = MAX_TRACE_CHARS - headLength
            buildString {
                append(trace.take(headLength))
                appendLine()
                appendLine("[trace truncated to first and last $MAX_TRACE_CHARS characters]")
                append(trace.takeLast(tailLength))
            }
        }

    companion object {
        const val MAX_TRACE_CHARS = 12_000
        const val MAX_TRACE_BYTES = 64_000
    }
}

fun loadProcessExitDiagnosticsReport(context: Context, maxRecords: Int = 10): ProcessExitDiagnosticsReport {
    val packageInfo = context.packageManager.getHoshiPackageInfo(context.packageName)
    val records = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        loadProcessExitRecords(context, maxRecords)
    } else {
        emptyList()
    }
    return ProcessExitDiagnosticsReport(
        packageName = context.packageName,
        versionName = packageInfo.versionName ?: "unknown",
        versionCode = packageInfo.hoshiLongVersionCode(),
        sdkInt = Build.VERSION.SDK_INT,
        capturedCrashes = loadCapturedCrashDiagnostics(context.crashDiagnosticsDir()),
        records = records,
    )
}

fun diagnosticsExportFileName(nowMillis: Long = System.currentTimeMillis()): String =
    "hoshi-diagnostics-${DiagnosticsFileNameFormatter.format(Instant.ofEpochMilli(nowMillis))}.txt"

fun installCrashDiagnostics(context: Context) {
    val appContext = context.applicationContext
    val previous = Thread.getDefaultUncaughtExceptionHandler()
    if (previous is HoshiCrashDiagnosticsHandler) return
    Thread.setDefaultUncaughtExceptionHandler(HoshiCrashDiagnosticsHandler(appContext, previous))
}

private class HoshiCrashDiagnosticsHandler(
    private val context: Context,
    private val previous: Thread.UncaughtExceptionHandler?,
) : Thread.UncaughtExceptionHandler {
    override fun uncaughtException(thread: Thread, throwable: Throwable) {
        runCatching {
            val packageInfo = context.packageManager.getHoshiPackageInfo(context.packageName)
            saveCapturedCrashDiagnostic(
                diagnosticsDir = context.crashDiagnosticsDir(),
                thread = thread,
                throwable = throwable,
                timestampMillis = System.currentTimeMillis(),
                packageName = context.packageName,
                versionName = packageInfo.versionName ?: "unknown",
                versionCode = packageInfo.hoshiLongVersionCode(),
                sdkInt = Build.VERSION.SDK_INT,
            )
        }
        previous?.uncaughtException(thread, throwable) ?: run {
            android.os.Process.killProcess(android.os.Process.myPid())
            exitProcess(10)
        }
    }
}

internal fun saveCapturedCrashDiagnostic(
    diagnosticsDir: File,
    thread: Thread,
    throwable: Throwable,
    timestampMillis: Long,
    packageName: String,
    versionName: String,
    versionCode: Long,
    sdkInt: Int,
): File {
    diagnosticsDir.mkdirs()
    val file = File(diagnosticsDir, "crash-$timestampMillis.txt")
    file.writeText(
        buildString {
            appendLine("Captured Hoshi crash")
            appendLine("Time: ${Instant.ofEpochMilli(timestampMillis)}")
            appendLine("Package: $packageName")
            appendLine("Version: $versionName ($versionCode)")
            appendLine("Android SDK: $sdkInt")
            appendLine("Thread: ${thread.name}")
            appendLine()
            appendLine(throwable.stackTraceText())
        },
    )
    pruneCapturedCrashDiagnostics(diagnosticsDir)
    return file
}

internal fun loadCapturedCrashDiagnostics(
    diagnosticsDir: File,
    maxRecords: Int = MAX_CAPTURED_CRASH_RECORDS,
): List<CapturedCrashRecord> =
    diagnosticsDir.listFiles { file -> file.isFile && file.name.startsWith("crash-") && file.extension == "txt" }
        .orEmpty()
        .sortedByDescending { it.name }
        .take(maxRecords)
        .mapNotNull { file ->
            val text = runCatching { file.readText().readableDiagnosticTextOrNull() }.getOrNull() ?: return@mapNotNull null
            CapturedCrashRecord(
                timestampMillis = file.name.removePrefix("crash-").removeSuffix(".txt").toLongOrNull() ?: file.lastModified(),
                text = text,
            )
        }

private fun pruneCapturedCrashDiagnostics(diagnosticsDir: File) {
    diagnosticsDir.listFiles { file -> file.isFile && file.name.startsWith("crash-") && file.extension == "txt" }
        .orEmpty()
        .sortedByDescending { it.name }
        .drop(MAX_CAPTURED_CRASH_RECORDS)
        .forEach { it.delete() }
}

private fun Throwable.stackTraceText(): String =
    StringWriter().use { writer ->
        PrintWriter(writer).use { printStackTrace(it) }
        writer.toString()
    }

private fun Context.crashDiagnosticsDir(): File =
    File(filesDir, "diagnostics/crashes")

@RequiresApi(Build.VERSION_CODES.R)
private fun loadProcessExitRecords(context: Context, maxRecords: Int): List<ProcessExitRecord> {
    val activityManager = context.getSystemService(ActivityManager::class.java)
    return activityManager
        .getHistoricalProcessExitReasons(context.packageName, 0, maxRecords)
        .map { it.toProcessExitRecord() }
}

@RequiresApi(Build.VERSION_CODES.R)
private fun ApplicationExitInfo.toProcessExitRecord(): ProcessExitRecord = ProcessExitRecord(
    timestampMillis = timestamp,
    reason = reason.toProcessExitReason(),
    status = status,
    importance = importance,
    pssKb = pss,
    rssKb = rss,
    description = description,
    trace = readTraceText(),
)

@RequiresApi(Build.VERSION_CODES.R)
private fun ApplicationExitInfo.readTraceText(): String? =
    try {
        traceInputStream
            ?.use { it.readBytes(ProcessExitDiagnosticsReport.MAX_TRACE_BYTES) }
            ?.toString(Charsets.UTF_8)
            ?.readableDiagnosticTextOrNull()
    } catch (_: IOException) {
        null
    }

private fun String.readableDiagnosticTextOrNull(): String? {
    if (isBlank()) return null
    val badCharacters = count {
        it == '\uFFFD' || (Character.isISOControl(it) && it != '\n' && it != '\r' && it != '\t')
    }
    if (badCharacters.toDouble() / length.toDouble() > 0.05) return null

    val recognizableCharacters = count {
        it.isLetterOrDigit() ||
            it.isWhitespace() ||
            when (it) {
                '.', ',', ':', ';', '/', '\\', '-', '_', '+', '#', '$', '%',
                '(', ')', '[', ']', '{', '}', '<', '>', '=', '"', '\'' -> true
                else -> false
            }
    }
    return if (recognizableCharacters.toDouble() / length.toDouble() >= 0.75) this else null
}

private fun java.io.InputStream.readBytes(maxBytes: Int): ByteArray {
    val buffer = ByteArray(maxBytes)
    var offset = 0
    while (offset < maxBytes) {
        val read = read(buffer, offset, maxBytes - offset)
        if (read == -1) break
        offset += read
    }
    return buffer.copyOf(offset)
}

@RequiresApi(Build.VERSION_CODES.R)
private fun Int.toProcessExitReason(): ProcessExitReason = when (this) {
    ApplicationExitInfo.REASON_ANR -> ProcessExitReason.Anr
    ApplicationExitInfo.REASON_CRASH -> ProcessExitReason.JavaCrash
    ApplicationExitInfo.REASON_CRASH_NATIVE -> ProcessExitReason.NativeCrash
    ApplicationExitInfo.REASON_LOW_MEMORY -> ProcessExitReason.LowMemory
    ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> ProcessExitReason.ExcessiveResourceUsage
    ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> ProcessExitReason.InitializationFailure
    ApplicationExitInfo.REASON_USER_REQUESTED -> ProcessExitReason.UserRequested
    ApplicationExitInfo.REASON_SIGNALED -> ProcessExitReason.Signaled
    ApplicationExitInfo.REASON_OTHER -> ProcessExitReason.Other
    else -> ProcessExitReason.Unknown
}

@Suppress("DEPRECATION")
private fun android.content.pm.PackageManager.getHoshiPackageInfo(packageName: String): PackageInfo =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        getPackageInfo(packageName, android.content.pm.PackageManager.PackageInfoFlags.of(0))
    } else {
        getPackageInfo(packageName, 0)
    }

@Suppress("DEPRECATION")
private fun PackageInfo.hoshiLongVersionCode(): Long =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) longVersionCode else versionCode.toLong()

private val DiagnosticsFileNameFormatter: DateTimeFormatter =
    DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneOffset.UTC)

private const val MAX_CAPTURED_CRASH_RECORDS = 5
