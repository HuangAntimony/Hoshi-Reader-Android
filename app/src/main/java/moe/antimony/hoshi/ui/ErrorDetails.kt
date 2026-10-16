package moe.antimony.hoshi.ui

import java.util.Collections
import java.util.IdentityHashMap
import kotlinx.coroutines.CancellationException

/** Keep operation context and the original diagnostic in the same visible error. */
fun UiText.withErrorDetails(error: Throwable): UiText {
    val details = error.diagnosticMessage()
    ErrorDiagnosticEvents.buffer.record(this, error)
    return UiText.Joined(listOf(this, UiText.Literal(details)))
}

fun String.withErrorDetails(error: Throwable): String {
    val details = error.diagnosticMessage()
    ErrorDiagnosticEvents.buffer.record(UiText.Literal(this), error)
    return listOf(this, details).filter { it.isNotBlank() }.distinct().joinToString("\n")
}

/** Exception messages are external diagnostic data; application copy stays in resources. */
fun Throwable.diagnosticMessage(): String {
    if (this is CancellationException) throw this
    val visited = Collections.newSetFromMap(IdentityHashMap<Throwable, Boolean>())
    val details = linkedSetOf<String>()
    var current: Throwable? = this
    while (current != null && visited.add(current)) {
        details += current.localizedMessage?.trim()?.takeIf { it.isNotEmpty() }
            ?: current.javaClass.simpleName.takeIf { it.isNotEmpty() }
            ?: current.javaClass.name
        current = current.cause
    }
    return details.joinToString("\n")
}
