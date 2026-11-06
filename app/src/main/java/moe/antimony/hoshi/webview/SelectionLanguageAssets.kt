package moe.antimony.hoshi.webview

import android.content.Context

/** Shared by reader documents and popup resource routes; one load per language script. */
internal class SelectionLanguageAssets(private val readAsset: (String) -> String) {
    val languageJapaneseJs by lazy { readAsset("language-ja.js") }
    val selectionJapaneseJs by lazy { readAsset("selection-ja.js") }
    val selectionEnglishJs by lazy { readAsset("selection-en.js") }
    val languageCjkJs by lazy { readAsset("language-cjk.js") }
    val selectionChineseJs by lazy { readAsset("selection-zh.js") }
    val selectionKoreanJs by lazy { readAsset("selection-ko.js") }

    companion object {
        @Volatile private var cached: SelectionLanguageAssets? = null

        fun load(context: Context): SelectionLanguageAssets = cached ?: synchronized(this) {
            cached ?: run {
                val assets = context.applicationContext.assets
                SelectionLanguageAssets { name ->
                    assets.open("hoshi-web/shared/$name").bufferedReader().use { it.readText() }
                }.also { cached = it }
            }
        }
    }
}
