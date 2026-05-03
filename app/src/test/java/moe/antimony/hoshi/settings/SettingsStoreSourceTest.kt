package moe.antimony.hoshi.settings

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class SettingsStoreSourceTest {
    @Test
    fun readerSettingsSharedPreferencesContractIsCharacterized() {
        val source = source("features/reader/ReaderSettings.kt")

        source.mustContainAll(
            """context.getSharedPreferences("reader-settings", Context.MODE_PRIVATE)""",
            """val theme: ReaderTheme = ReaderTheme.System""",
            """val eInkMode: Boolean = false""",
            """val sepiaInvertInDark: Boolean = false""",
            """val verticalWriting: Boolean = true""",
            """val selectedFont: String = ReaderFontManager.defaultMinchoFont""",
            """val fontSize: Int = 22""",
            """val hideFurigana: Boolean = false""",
            """val horizontalPadding: Int = 5""",
            """val verticalPadding: Int = 0""",
            """val avoidPageBreak: Boolean = false""",
            """val justifyText: Boolean = false""",
            """val layoutAdvanced: Boolean = false""",
            """val lineHeight: Double = 1.65""",
            """val characterSpacing: Double = 0.0""",
            """val showTitle: Boolean = true""",
            """val showCharacters: Boolean = true""",
            """val showPercentage: Boolean = true""",
            """val showProgressTop: Boolean = true""",
            """val popupWidth: Int = 320""",
            """val popupHeight: Int = 250""",
            """val popupActionBar: Boolean = false""",
            """val popupFullWidth: Boolean = false""",
            """val popupSwipeToDismiss: Boolean = true""",
            """val popupSwipeThreshold: Int = 30""",
            """val volumeKeysTurnPages: Boolean = false""",
            """val reverseVolumeKeyDirection: Boolean = false""",
            """preferences.getString("theme", null)""",
            """ReaderTheme.entries.firstOrNull { it.label == saved }""",
            """preferences.getBoolean("eInkMode", false)""",
            """preferences.getBoolean("sepiaInvertInDark", false)""",
            """preferences.getBoolean("verticalWriting", true)""",
            """ReaderFontManager.normalizeDefaultFont(""",
            """preferences.getString("selectedFont", null) ?: ReaderFontManager.defaultMinchoFont""",
            """preferences.getInt("fontSize", 22)""",
            """preferences.getBoolean("readerHideFurigana", false)""",
            """preferences.getInt("layoutHorizontalPadding", 5)""",
            """preferences.getInt("layoutVerticalPadding", 0)""",
            """preferences.getBoolean("avoidPageBreak", false)""",
            """preferences.getBoolean("justifyText", false)""",
            """preferences.getBoolean("layoutAdvanced", false)""",
            """preferences.getFloat("lineHeight", 1.65f).toDouble()""",
            """preferences.getFloat("characterSpacing", 0f).toDouble()""",
            """preferences.getBoolean("readerShowTitle", true)""",
            """preferences.getBoolean("readerShowCharacters", true)""",
            """preferences.getBoolean("readerShowPercentage", true)""",
            """preferences.getBoolean("readerShowProgressTop", true)""",
            """preferences.getInt("popupWidth", 320)""",
            """preferences.getInt("popupHeight", 250)""",
            """preferences.getBoolean("popupActionBar", false)""",
            """preferences.getBoolean("popupFullWidth", false)""",
            """preferences.getBoolean("popupSwipeToDismiss", true)""",
            """preferences.getInt("popupSwipeThreshold", 30).coerceIn(20, 60)""",
            """preferences.getBoolean("volumeKeysTurnPages", false)""",
            """preferences.getBoolean("reverseVolumeKeyDirection", false)""",
            """.putString("theme", settings.theme.label)""",
            """.putBoolean("eInkMode", settings.eInkMode)""",
            """.putBoolean("sepiaInvertInDark", settings.sepiaInvertInDark)""",
            """.putBoolean("verticalWriting", settings.verticalWriting)""",
            """.putString("selectedFont", settings.selectedFont)""",
            """.putInt("fontSize", settings.fontSize)""",
            """.putBoolean("readerHideFurigana", settings.hideFurigana)""",
            """.putInt("layoutHorizontalPadding", settings.horizontalPadding)""",
            """.putInt("layoutVerticalPadding", settings.verticalPadding)""",
            """.putBoolean("avoidPageBreak", settings.avoidPageBreak)""",
            """.putBoolean("justifyText", settings.justifyText)""",
            """.putBoolean("layoutAdvanced", settings.layoutAdvanced)""",
            """.putFloat("lineHeight", settings.lineHeight.toFloat())""",
            """.putFloat("characterSpacing", settings.characterSpacing.toFloat())""",
            """.putBoolean("readerShowTitle", settings.showTitle)""",
            """.putBoolean("readerShowCharacters", settings.showCharacters)""",
            """.putBoolean("readerShowPercentage", settings.showPercentage)""",
            """.putBoolean("readerShowProgressTop", settings.showProgressTop)""",
            """.putInt("popupWidth", settings.popupWidth)""",
            """.putInt("popupHeight", settings.popupHeight)""",
            """.putBoolean("popupActionBar", settings.popupActionBar)""",
            """.putBoolean("popupFullWidth", settings.popupFullWidth)""",
            """.putBoolean("popupSwipeToDismiss", settings.popupSwipeToDismiss)""",
            """.putInt("popupSwipeThreshold", settings.popupSwipeThreshold)""",
            """.putBoolean("volumeKeysTurnPages", settings.volumeKeysTurnPages)""",
            """.putBoolean("reverseVolumeKeyDirection", settings.reverseVolumeKeyDirection)""",
        )
    }

    @Test
    fun dictionarySettingsSharedPreferencesContractIsCharacterized() {
        val source = source("features/dictionary/DictionarySettings.kt")

        source.mustContainAll(
            """context.getSharedPreferences("dictionary-settings", Context.MODE_PRIVATE)""",
            """val dictionaryTabDefault: Boolean = false""",
            """val maxResults: Int = 16""",
            """val scanLength: Int = 16""",
            """val collapseDictionaries: Boolean = false""",
            """val compactGlossaries: Boolean = true""",
            """val showExpressionTags: Boolean = false""",
            """val harmonicFrequency: Boolean = false""",
            """val deduplicatePitchAccents: Boolean = false""",
            """val compactPitchAccents: Boolean = true""",
            """val customCSS: String = """"",
            """const val MIN_MAX_RESULTS = 1""",
            """const val MAX_MAX_RESULTS = 50""",
            """const val MIN_SCAN_LENGTH = 1""",
            """const val MAX_SCAN_LENGTH = 64""",
            """maxResults = maxResults.coerceIn(MIN_MAX_RESULTS, MAX_MAX_RESULTS)""",
            """scanLength = scanLength.coerceIn(MIN_SCAN_LENGTH, MAX_SCAN_LENGTH)""",
            """dictionaryTabDefault = preferences.getBoolean(KEY_DICTIONARY_TAB_DEFAULT, false)""",
            """maxResults = preferences.getInt(KEY_MAX_RESULTS, 16)""",
            """scanLength = preferences.getInt(KEY_SCAN_LENGTH, 16)""",
            """collapseDictionaries = preferences.getBoolean(KEY_COLLAPSE_DICTIONARIES, false)""",
            """compactGlossaries = preferences.getBoolean(KEY_COMPACT_GLOSSARIES, true)""",
            """showExpressionTags = preferences.getBoolean(KEY_SHOW_EXPRESSION_TAGS, false)""",
            """harmonicFrequency = preferences.getBoolean(KEY_HARMONIC_FREQUENCY, false)""",
            """deduplicatePitchAccents = preferences.getBoolean(KEY_DEDUPLICATE_PITCH_ACCENTS, false)""",
            """compactPitchAccents = preferences.getBoolean(KEY_COMPACT_PITCH_ACCENTS, true)""",
            """customCSS = preferences.getString(KEY_CUSTOM_CSS, "").orEmpty()""",
            """).normalized()""",
            """val normalized = settings.normalized()""",
            """.putBoolean(KEY_DICTIONARY_TAB_DEFAULT, normalized.dictionaryTabDefault)""",
            """.putInt(KEY_MAX_RESULTS, normalized.maxResults)""",
            """.putInt(KEY_SCAN_LENGTH, normalized.scanLength)""",
            """.putBoolean(KEY_COLLAPSE_DICTIONARIES, normalized.collapseDictionaries)""",
            """.putBoolean(KEY_COMPACT_GLOSSARIES, normalized.compactGlossaries)""",
            """.putBoolean(KEY_SHOW_EXPRESSION_TAGS, normalized.showExpressionTags)""",
            """.putBoolean(KEY_HARMONIC_FREQUENCY, normalized.harmonicFrequency)""",
            """.putBoolean(KEY_DEDUPLICATE_PITCH_ACCENTS, normalized.deduplicatePitchAccents)""",
            """.putBoolean(KEY_COMPACT_PITCH_ACCENTS, normalized.compactPitchAccents)""",
            """.putString(KEY_CUSTOM_CSS, normalized.customCSS)""",
            """const val KEY_DICTIONARY_TAB_DEFAULT = "dictionaryTabDefault"""",
            """const val KEY_MAX_RESULTS = "maxResults"""",
            """const val KEY_SCAN_LENGTH = "scanLength"""",
            """const val KEY_COLLAPSE_DICTIONARIES = "collapseDictionaries"""",
            """const val KEY_COMPACT_GLOSSARIES = "compactGlossaries"""",
            """const val KEY_SHOW_EXPRESSION_TAGS = "showExpressionTags"""",
            """const val KEY_HARMONIC_FREQUENCY = "harmonicFrequency"""",
            """const val KEY_DEDUPLICATE_PITCH_ACCENTS = "deduplicatePitchAccents"""",
            """const val KEY_COMPACT_PITCH_ACCENTS = "compactPitchAccents"""",
            """const val KEY_CUSTOM_CSS = "customCSS"""",
        )
    }

    @Test
    fun audioSettingsSharedPreferencesContractIsCharacterized() {
        val source = source("features/audio/AudioSettings.kt")

        source.mustContainAll(
            """context.getSharedPreferences("audio-settings", Context.MODE_PRIVATE)""",
            """private val json = Json { ignoreUnknownKeys = true }""",
            """val audioSources: List<AudioSource> = listOf(DefaultAudioSource)""",
            """val enableLocalAudio: Boolean = false""",
            """val enableAutoplay: Boolean = false""",
            """val playbackMode: AudioPlaybackMode = AudioPlaybackMode.Interrupt""",
            """Interrupt("interrupt", "Interrupt")""",
            """Duck("duck", "Lower Volume")""",
            """Mix("mix", "Keep Volume")""",
            """entries.firstOrNull { it.rawValue == value } ?: Interrupt""",
            """const val LocalAudioPath = "Audio/android.db"""",
            """const val LocalAudioUrl = "http://localhost:8765/localaudio/get/?term={term}&reading={reading}"""",
            """name = "Local"""",
            """name = "Default"""",
            """url = "https://hoshi-reader.manhhaoo-do.workers.dev/?term={term}&reading={reading}"""",
            """isDefault = true""",
            """preferences.getString(KEY_AUDIO_SOURCES, null)""",
            """json.decodeFromString(ListSerializer(AudioSource.serializer()), encoded)""",
            """?: listOf(AudioSettings.DefaultAudioSource)""",
            """enableLocalAudio = preferences.getBoolean(KEY_ENABLE_LOCAL_AUDIO, false)""",
            """enableAutoplay = preferences.getBoolean(KEY_AUDIO_ENABLE_AUTOPLAY, false)""",
            """playbackMode = AudioPlaybackMode.fromRawValue(preferences.getString(KEY_AUDIO_PLAYBACK_MODE, null))""",
            """settings.enableLocalAudio && settings.audioSources.none { it.url == AudioSettings.LocalAudioSource.url }""",
            """!settings.enableLocalAudio && settings.audioSources.any { it.url == AudioSettings.LocalAudioSource.url }""",
            """settings.withLocalAudioEnabled(true)""",
            """settings.withLocalAudioEnabled(false)""",
            """.putString(KEY_AUDIO_SOURCES, json.encodeToString(ListSerializer(AudioSource.serializer()), settings.audioSources))""",
            """.putBoolean(KEY_ENABLE_LOCAL_AUDIO, settings.enableLocalAudio)""",
            """.remove(KEY_LOCAL_AUDIO_DATABASE_URI)""",
            """.putBoolean(KEY_AUDIO_ENABLE_AUTOPLAY, settings.enableAutoplay)""",
            """.putString(KEY_AUDIO_PLAYBACK_MODE, settings.playbackMode.rawValue)""",
            """const val KEY_AUDIO_SOURCES = "audioSources"""",
            """const val KEY_ENABLE_LOCAL_AUDIO = "enableLocalAudio"""",
            """const val KEY_LOCAL_AUDIO_DATABASE_URI = "localAudioDatabaseUri"""",
            """const val KEY_AUDIO_ENABLE_AUTOPLAY = "audioEnableAutoplay"""",
            """const val KEY_AUDIO_PLAYBACK_MODE = "audioPlaybackMode"""",
        )
    }

    @Test
    fun sasayakiSettingsSharedPreferencesContractIsCharacterized() {
        val source = source("features/sasayaki/SasayakiSettings.kt")

        source.mustContainAll(
            """context.getSharedPreferences("sasayaki-settings", Context.MODE_PRIVATE)""",
            """val enabled: Boolean = false""",
            """val showReaderToggle: Boolean = false""",
            """val copyAudiobookToPrivateStorage: Boolean = false""",
            """val autoScroll: Boolean = true""",
            """val autoPause: Boolean = true""",
            """val lightTextColor: Long = 0xFF000000""",
            """val lightBackgroundColor: Long = 0x6687CEEB""",
            """val darkTextColor: Long = 0xFFFFFFFF""",
            """val darkBackgroundColor: Long = 0x6687CEEB""",
            """enabled = preferences.getBoolean(KEY_ENABLE, false)""",
            """showReaderToggle = preferences.getBoolean(KEY_SHOW_READER_TOGGLE, false)""",
            """copyAudiobookToPrivateStorage = preferences.getBoolean(KEY_COPY_AUDIOBOOK_TO_PRIVATE_STORAGE, false)""",
            """autoScroll = preferences.getBoolean(KEY_AUTO_SCROLL, true)""",
            """autoPause = preferences.getBoolean(KEY_AUTO_PAUSE, true)""",
            """lightTextColor = preferences.getLong(KEY_LIGHT_TEXT_COLOR, 0xFF000000)""",
            """lightBackgroundColor = preferences.getLong(KEY_LIGHT_BACKGROUND_COLOR, 0x6687CEEB)""",
            """darkTextColor = preferences.getLong(KEY_DARK_TEXT_COLOR, 0xFFFFFFFF)""",
            """darkBackgroundColor = preferences.getLong(KEY_DARK_BACKGROUND_COLOR, 0x6687CEEB)""",
            """.putBoolean(KEY_ENABLE, settings.enabled)""",
            """.putBoolean(KEY_SHOW_READER_TOGGLE, settings.showReaderToggle)""",
            """.putBoolean(KEY_COPY_AUDIOBOOK_TO_PRIVATE_STORAGE, settings.copyAudiobookToPrivateStorage)""",
            """.putBoolean(KEY_AUTO_SCROLL, settings.autoScroll)""",
            """.putBoolean(KEY_AUTO_PAUSE, settings.autoPause)""",
            """.putLong(KEY_LIGHT_TEXT_COLOR, settings.lightTextColor)""",
            """.putLong(KEY_LIGHT_BACKGROUND_COLOR, settings.lightBackgroundColor)""",
            """.putLong(KEY_DARK_TEXT_COLOR, settings.darkTextColor)""",
            """.putLong(KEY_DARK_BACKGROUND_COLOR, settings.darkBackgroundColor)""",
            """const val KEY_ENABLE = "enableSasayaki"""",
            """const val KEY_SHOW_READER_TOGGLE = "readerShowSasayakiToggle"""",
            """const val KEY_COPY_AUDIOBOOK_TO_PRIVATE_STORAGE = "sasayakiCopyAudiobookToPrivateStorage"""",
            """const val KEY_AUTO_SCROLL = "sasayakiAutoScroll"""",
            """const val KEY_AUTO_PAUSE = "sasayakiAutoPause"""",
            """const val KEY_LIGHT_TEXT_COLOR = "sasayakiTextColor"""",
            """const val KEY_LIGHT_BACKGROUND_COLOR = "sasayakiBackgroundColor"""",
            """const val KEY_DARK_TEXT_COLOR = "sasayakiDarkTextColor"""",
            """const val KEY_DARK_BACKGROUND_COLOR = "sasayakiDarkBackgroundColor"""",
        )
    }

    @Test
    fun currentSynchronousSettingsStoreCallSitesAreCharacterizedForRepositoryMigration() {
        source("MainActivity.kt").mustContainAll(
            "readerSettingsRepository()",
            "readerSettingsRepository.settings.collect",
            "readerSettingsRepository.update { settings }",
        )
        source("navigation/AppShell.kt").mustContainAll(
            "DictionarySettingsStore(context).load().dictionaryTabDefault",
        )
        source("features/audio/AudioView.kt").mustContainAll(
            "AudioSettingsStore(context)",
            "mutableStateOf(store.load())",
        )
        source("features/sasayaki/SasayakiSettingsView.kt").mustContainAll(
            "SasayakiSettingsStore(context)",
            "mutableStateOf(store.load())",
        )
        source("features/dictionary/DictionarySearchView.kt").mustContainAll(
            "DictionarySettingsStore(context)",
            "AudioSettingsStore(context)",
            "mutableStateOf(dictionarySettingsStore.load())",
            "mutableStateOf(audioSettingsStore.load())",
        )
        source("features/reader/ReaderWebView.kt").mustContainAll(
            "DictionarySettingsStore(context)",
            "AudioSettingsStore(context)",
            "SasayakiSettingsStore(context)",
            "mutableStateOf(sasayakiSettingsStore.load())",
            "dictionarySettingsStore.load()",
            "audioSettingsStore.load()",
        )
        source("features/bookshelf/BookshelfRepository.kt").mustContainAll(
            "SasayakiSettingsStore",
            "sasayakiSettingsStore.load().enabled",
        )
        source("features/dictionary/DictionaryViewModel.kt").mustContainAll(
            "DictionarySettingsStore",
            "override fun loadSettings(): DictionarySettings = settingsStore.load()",
        )
    }

    private fun source(path: String): String =
        File("src/main/java/moe/antimony/hoshi/$path").readText()

    private fun String.mustContainAll(vararg needles: String) {
        needles.forEach { needle ->
            assertTrue("Expected source to contain: $needle", contains(needle))
        }
    }
}
