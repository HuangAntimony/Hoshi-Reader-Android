package moe.antimony.hoshi.ui

import android.content.Context
import androidx.core.content.ContextCompat

/** Resolve at the point of use: application/service contexts retain system locales on API < 33. */
internal fun Context.appLanguageContext(): Context = ContextCompat.getContextForLanguage(this)
