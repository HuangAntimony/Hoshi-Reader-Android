package moe.antimony.hoshi.ui

import android.content.Context
import android.content.res.Resources
import androidx.annotation.PluralsRes
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

sealed interface UiText {
    data class Resource(@param:StringRes val id: Int, val args: List<Any> = emptyList()) : UiText {
        constructor(@StringRes id: Int, vararg args: Any) : this(id, args.toList())
    }

    data class Plural(@param:PluralsRes val id: Int, val quantity: Int, val args: List<Any> = emptyList()) : UiText {
        constructor(@PluralsRes id: Int, quantity: Int, vararg args: Any) : this(id, quantity, args.toList())
    }

    data class Literal(val value: String) : UiText

    data class Joined(val parts: List<UiText>, val separator: String = "\n") : UiText
}

fun UiText.resolve(resources: Resources): String =
    resolve(
        getString = { id, args -> resources.getString(id, *args) },
        getQuantityString = { id, quantity, args -> resources.getQuantityString(id, quantity, *args) },
    )

fun UiText.resolve(context: Context): String =
    resolve(context.resources)

fun UiText.resolve(
    getString: (id: Int, args: Array<out Any>) -> String,
    getQuantityString: (id: Int, quantity: Int, args: Array<out Any>) -> String,
): String =
    when (this) {
        is UiText.Literal -> value
        is UiText.Resource -> getString(id, args.map { if (it is UiText) it.resolve(getString, getQuantityString) else it }.toTypedArray())
        is UiText.Plural -> getQuantityString(id, quantity, args.map { if (it is UiText) it.resolve(getString, getQuantityString) else it }.toTypedArray())
        is UiText.Joined -> parts.joinToString(separator) { it.resolve(getString, getQuantityString) }
    }

@Composable
fun UiText.asString(): String =
    resolve(LocalContext.current)
