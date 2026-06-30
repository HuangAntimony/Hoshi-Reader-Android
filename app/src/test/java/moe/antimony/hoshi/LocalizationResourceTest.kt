package moe.antimony.hoshi

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

class LocalizationResourceTest {
    private val resDir = File("src/main/res")

    @Test
    fun simplifiedChineseDefinesEveryTranslatableDefaultString() {
        val defaultResources = readStringResources(File(resDir, "values/strings.xml"))
        val zhResources = readStringResources(File(resDir, "values-zh-rCN/strings.xml"))

        val missing = defaultResources.strings
            .filterValues { it.translatable }
            .keys
            .filterNot { it in zhResources.strings }

        assertEquals(emptyList<String>(), missing)
    }

    @Test
    fun simplifiedChineseDefinesMatchingPluralQuantities() {
        val defaultResources = readStringResources(File(resDir, "values/strings.xml"))
        val zhResources = readStringResources(File(resDir, "values-zh-rCN/strings.xml"))

        val mismatches = defaultResources.plurals.mapNotNull { (name, defaultPlural) ->
            val zhPlural = zhResources.plurals[name]
            when {
                zhPlural == null -> "$name missing"
                zhPlural.quantities != defaultPlural.quantities -> "$name ${defaultPlural.quantities} != ${zhPlural.quantities}"
                else -> null
            }
        }

        assertEquals(emptyList<String>(), mismatches)
    }

    @Test
    fun translatedResourcesKeepDefaultFormatArguments() {
        val defaultResources = readStringResources(File(resDir, "values/strings.xml"))
        val zhResources = readStringResources(File(resDir, "values-zh-rCN/strings.xml"))
        val mismatches = mutableListOf<String>()

        defaultResources.strings
            .filterValues { it.translatable }
            .forEach { (name, defaultString) ->
                val zhValue = zhResources.strings[name]?.value ?: return@forEach
                val defaultArgs = formatArguments(defaultString.value)
                val zhArgs = formatArguments(zhValue)
                if (defaultArgs != zhArgs) {
                    mismatches += "$name $defaultArgs != $zhArgs"
                }
            }

        defaultResources.plurals.forEach { (name, defaultPlural) ->
            val zhPlural = zhResources.plurals[name] ?: return@forEach
            defaultPlural.items.forEach { (quantity, defaultValue) ->
                val zhValue = zhPlural.items[quantity] ?: return@forEach
                val defaultArgs = formatArguments(defaultValue)
                val zhArgs = formatArguments(zhValue)
                if (defaultArgs != zhArgs) {
                    mismatches += "$name[$quantity] $defaultArgs != $zhArgs"
                }
            }
        }

        assertEquals(emptyList<String>(), mismatches)
    }

    @Test
    fun translatedResourcesAreNotBlank() {
        val zhResources = readStringResources(File(resDir, "values-zh-rCN/strings.xml"))

        val blankStrings = zhResources.strings
            .filterValues { it.translatable && it.value.isBlank() }
            .keys
            .toList()
        val blankPlurals = zhResources.plurals
            .flatMap { (name, plural) ->
                plural.items.filterValues { it.isBlank() }.keys.map { "$name[$it]" }
            }

        assertEquals(emptyList<String>(), blankStrings + blankPlurals)
    }

    @Test
    fun simplifiedChineseUsesRequestedTerminology() {
        val zhResources = readStringResources(File(resDir, "values-zh-rCN/strings.xml"))
        val forbiddenTerms = listOf("音高", "音高重音", "词典")
        val forbiddenUsages = zhResources.strings
            .filterValues { it.translatable }
            .flatMap { (name, value) ->
                forbiddenTerms
                    .filter { term -> value.value.contains(term) }
                    .map { term -> "$name contains $term" }
            } + zhResources.arrays.flatMap { (name, items) ->
            items.flatMapIndexed { index, item ->
                forbiddenTerms
                    .filter { term -> item.contains(term) }
                    .map { term -> "$name[$index] contains $term" }
            }
        }

        assertEquals(emptyList<String>(), forbiddenUsages)
        assertEquals("查词", zhResources.strings.getValue("main_tab_dictionary").value)
        assertEquals("有声书", zhResources.strings.getValue("sasayaki_title").value)
        assertEquals("未匹配", zhResources.strings.getValue("sasayaki_no_subtitle_match").value)
        assertEquals("自动翻页", zhResources.strings.getValue("sasayaki_auto_scroll").value)
        assertEquals("标注", zhResources.strings.getValue("reader_highlight_action").value)
        assertEquals("复制", zhResources.strings.getValue("action_copy").value)
        assertEquals("分享", zhResources.strings.getValue("action_share").value)
        assertEquals("将使用 %1\$s", zhResources.strings.getValue("bookshelf_profile_automatic_uses_format").value)
        assertEquals("段落间距", zhResources.strings.getValue("reader_appearance_paragraph_spacing").value)
        assertEquals("Profile", zhResources.strings.getValue("settings_profiles").value)
        assertEquals("Profile", zhResources.strings.getValue("profiles_title").value)
        assertEquals(
            "新 Profile 会基于当前启用 Profile 拷贝一份设置。",
            zhResources.strings.getValue("profiles_create_copy_settings_note").value,
        )
    }

    @Test
    fun visualNovelReaderSettingsUseConciseLabels() {
        val defaultResources = readStringResources(File(resDir, "values/strings.xml"))
        val zhResources = readStringResources(File(resDir, "values-zh-rCN/strings.xml"))

        assertEquals("Text Reveal Speed", defaultResources.strings.getValue("reader_visual_novel_reveal_speed").value)
        assertEquals("Instant", defaultResources.strings.getValue("reader_visual_novel_reveal_speed_instant").value)
        assertEquals("Screen Content", defaultResources.strings.getValue("reader_visual_novel_screen_mode").value)
        assertEquals("Block", defaultResources.strings.getValue("reader_visual_novel_screen_mode_block").value)
        assertEquals("Sentences", defaultResources.strings.getValue("reader_visual_novel_screen_mode_sentences").value)
        assertEquals("Sentences per Screen", defaultResources.strings.getValue("reader_visual_novel_sentences_per_screen").value)
        assertEquals("Keep Dialogue Together", defaultResources.strings.getValue("reader_visual_novel_preserve_dialogue").value)
        assertEquals("Tap Blank Area to Advance", defaultResources.strings.getValue("reader_visual_novel_click_advance").value)

        assertEquals("文字显示速度", zhResources.strings.getValue("reader_visual_novel_reveal_speed").value)
        assertEquals("立即", zhResources.strings.getValue("reader_visual_novel_reveal_speed_instant").value)
        assertEquals("屏幕内容", zhResources.strings.getValue("reader_visual_novel_screen_mode").value)
        assertEquals("按段落", zhResources.strings.getValue("reader_visual_novel_screen_mode_block").value)
        assertEquals("按句子", zhResources.strings.getValue("reader_visual_novel_screen_mode_sentences").value)
        assertEquals("每屏句数", zhResources.strings.getValue("reader_visual_novel_sentences_per_screen").value)
        assertEquals("对话保持同屏", zhResources.strings.getValue("reader_visual_novel_preserve_dialogue").value)
        assertEquals("点击空白处前进", zhResources.strings.getValue("reader_visual_novel_click_advance").value)
    }

    @Test
    fun statisticsDurationUnitsUseFullWords() {
        val defaultResources = readStringResources(File(resDir, "values/strings.xml"))
        val zhResources = readStringResources(File(resDir, "values-zh-rCN/strings.xml"))

        assertEquals("%1\$d day", defaultResources.plurals.getValue("statistics_days_value").items.getValue("one"))
        assertEquals("%1\$d days", defaultResources.plurals.getValue("statistics_days_value").items.getValue("other"))
        assertEquals("%1\$d week", defaultResources.plurals.getValue("statistics_weeks_value").items.getValue("one"))
        assertEquals("%1\$d weeks", defaultResources.plurals.getValue("statistics_weeks_value").items.getValue("other"))
        assertEquals("%1\$d 天", zhResources.plurals.getValue("statistics_days_value").items.getValue("one"))
        assertEquals("%1\$d 天", zhResources.plurals.getValue("statistics_days_value").items.getValue("other"))
        assertEquals("%1\$d 周", zhResources.plurals.getValue("statistics_weeks_value").items.getValue("one"))
        assertEquals("%1\$d 周", zhResources.plurals.getValue("statistics_weeks_value").items.getValue("other"))
    }

    @Test
    fun statisticsCurrentRangeTitleNamesSelectedRange() {
        val defaultResources = readStringResources(File(resDir, "values/strings.xml"))
        val zhResources = readStringResources(File(resDir, "values-zh-rCN/strings.xml"))

        assertEquals("Selected Range", defaultResources.strings.getValue("statistics_current_range").value)
        assertEquals("所选范围", zhResources.strings.getValue("statistics_current_range").value)
    }

    @Test
    fun statisticsStandaloneEnglishDayAndWeekCountsUsePlurals() {
        val defaultResources = readStringResources(File(resDir, "values/strings.xml"))

        val fixedCounts = defaultResources.strings
            .filterValues { string ->
                StandaloneEnglishDayOrWeekCountPattern.containsMatchIn(string.value)
            }
            .keys
            .toList()

        assertEquals(emptyList<String>(), fixedCounts)
    }

    @Test
    fun statisticsSyncModeLabelsAreLocalized() {
        val defaultResources = readStringResources(File(resDir, "values/strings.xml"))
        val zhResources = readStringResources(File(resDir, "values-zh-rCN/strings.xml"))

        assertEquals("Merge", defaultResources.strings["reader_statistics_sync_mode_merge"]?.value)
        assertEquals("Replace", defaultResources.strings["reader_statistics_sync_mode_replace"]?.value)
        assertEquals("合并", zhResources.strings["reader_statistics_sync_mode_merge"]?.value)
        assertEquals("替换", zhResources.strings["reader_statistics_sync_mode_replace"]?.value)
    }

    @Test
    fun defaultLocaleIsDeclaredForGeneratedLocaleConfig() {
        val properties = File(resDir, "resources.properties")

        assertTrue("resources.properties is required for AGP generated LocaleConfig", properties.isFile)
        assertTrue(properties.readLines().any { it.trim() == "unqualifiedResLocale=en-US" })
    }

    private fun readStringResources(file: File): StringResources {
        assertTrue("${file.path} must exist", file.isFile)
        val document = DocumentBuilderFactory.newInstance()
            .newDocumentBuilder()
            .parse(file)
        val root = document.documentElement

        val strings = mutableMapOf<String, StringValue>()
        val plurals = mutableMapOf<String, PluralValue>()
        val arrays = mutableMapOf<String, List<String>>()
        val children = root.childNodes
        for (index in 0 until children.length) {
            val node = children.item(index)
            if (node !is Element) continue
            val name = node.getAttribute("name")
            assertFalse("${file.path} contains a resource without a name", name.isBlank())
            when (node.tagName) {
                "string" -> strings[name] = StringValue(
                    value = node.textContent,
                    translatable = node.getAttribute("translatable") != "false",
                )
                "plurals" -> {
                    val items = mutableMapOf<String, String>()
                    val itemNodes = node.childNodes
                    for (itemIndex in 0 until itemNodes.length) {
                        val item = itemNodes.item(itemIndex)
                        if (item !is Element || item.tagName != "item") continue
                        items[item.getAttribute("quantity")] = item.textContent
                    }
                    plurals[name] = PluralValue(items)
                }
                "string-array" -> {
                    val items = mutableListOf<String>()
                    val itemNodes = node.childNodes
                    for (itemIndex in 0 until itemNodes.length) {
                        val item = itemNodes.item(itemIndex)
                        if (item !is Element || item.tagName != "item") continue
                        items += item.textContent
                    }
                    arrays[name] = items
                }
            }
        }
        return StringResources(strings, plurals, arrays)
    }

    private fun formatArguments(value: String): List<String> =
        FormatArgumentPattern.findAll(value)
            .map { it.value }
            .toList()

    private data class StringResources(
        val strings: Map<String, StringValue>,
        val plurals: Map<String, PluralValue>,
        val arrays: Map<String, List<String>>,
    )

    private data class StringValue(
        val value: String,
        val translatable: Boolean,
    )

    private data class PluralValue(
        val items: Map<String, String>,
    ) {
        val quantities: Set<String> = items.keys
    }

    private companion object {
        val FormatArgumentPattern = Regex("%\\d+\\$[sdDfFeEgG]")
        val StandaloneEnglishDayOrWeekCountPattern = Regex("""%\d+[$]d (days|weeks)""")
    }
}
