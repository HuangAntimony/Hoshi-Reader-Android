package moe.antimony.hoshi.features.update

import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.layout.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import moe.antimony.hoshi.features.settings.StaticSettingsDetailScaffold
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import moe.antimony.hoshi.R
import moe.antimony.hoshi.features.settings.GroupDivider

private data class AboutCredit(val name: Int, val license: Int, val url: String)

// Direct runtime dependencies grouped by project, plus bundled native components.
private val dependencies = listOf(
    AboutCredit(R.string.credit_rbook, R.string.license_apache2, "https://github.com/DevinSterling/rbook"),
    AboutCredit(R.string.credit_uniffi, R.string.license_mpl2, "https://github.com/mozilla/uniffi-rs"),
    AboutCredit(R.string.credit_thiserror, R.string.license_mit_apache2, "https://github.com/dtolnay/thiserror"),
    AboutCredit(R.string.credit_coil, R.string.license_apache2, "https://github.com/coil-kt/coil"),
    AboutCredit(R.string.credit_re2j, R.string.license_bsd3, "https://github.com/google/re2j"),
    AboutCredit(R.string.credit_jna, R.string.license_apache2, "https://github.com/java-native-access/jna"),
    AboutCredit(R.string.credit_materialkolor, R.string.license_mit, "https://github.com/jordond/MaterialKolor"),
    AboutCredit(R.string.credit_androidx, R.string.license_apache2, "https://github.com/androidx/androidx"),
    AboutCredit(R.string.credit_media3, R.string.license_apache2, "https://github.com/androidx/media"),
    AboutCredit(R.string.credit_hilt, R.string.license_apache2, "https://github.com/google/dagger"),
    AboutCredit(R.string.credit_kotlin, R.string.license_apache2, "https://github.com/JetBrains/kotlin"),
    AboutCredit(R.string.credit_coroutines, R.string.license_apache2, "https://github.com/Kotlin/kotlinx.coroutines"),
    AboutCredit(R.string.credit_serialization, R.string.license_apache2, "https://github.com/Kotlin/kotlinx.serialization"),
    AboutCredit(R.string.credit_play_services, R.string.license_android_sdk, "https://developers.google.com/android/guides/overview"),
    AboutCredit(R.string.credit_desugar, R.string.license_gpl2_classpath, "https://github.com/google/desugar_jdk_libs"),
    AboutCredit(R.string.credit_utfcpp, R.string.license_bsl1, "https://github.com/nemtrif/utfcpp"),
    AboutCredit(R.string.credit_xxhash, R.string.license_bsd2, "https://github.com/Cyan4973/xxHash"),
    AboutCredit(R.string.credit_kanji_processor, R.string.license_mit, "https://github.com/yomidevs/kanji-processor"),
    AboutCredit(R.string.credit_okhttp, R.string.license_apache2, "https://github.com/square/okhttp"),
    AboutCredit(R.string.credit_jsoup, R.string.license_mit, "https://github.com/jhy/jsoup"),
    AboutCredit(R.string.credit_hoshidicts, R.string.license_gpl3, "https://github.com/Manhhao/hoshidicts"),
    AboutCredit(R.string.credit_glaze, R.string.license_mit, "https://github.com/stephenberry/glaze"),
    AboutCredit(R.string.credit_zstd, R.string.license_bsd3, "https://github.com/facebook/zstd"),
    AboutCredit(R.string.credit_libdeflate, R.string.license_mit, "https://github.com/ebiggers/libdeflate"),
    AboutCredit(R.string.credit_unordered_dense, R.string.license_mit, "https://github.com/martinus/unordered_dense"),
    AboutCredit(R.string.credit_utf8proc, R.string.license_mit, "https://github.com/JuliaStrings/utf8proc"),
    AboutCredit(R.string.credit_ffmpeg, R.string.license_lgpl21, "https://ffmpeg.org/"),
)
private val attribution = listOf(
    AboutCredit(R.string.credit_yomitan, R.string.license_gpl3, "https://github.com/yomidevs/yomitan"),
    AboutCredit(R.string.credit_ankidroid, R.string.license_gpl3, "https://github.com/ankidroid/Anki-Android"),
    AboutCredit(R.string.credit_ankiconnect, R.string.license_gpl3, "https://github.com/KamWithK/AnkiconnectAndroid"),
    AboutCredit(R.string.credit_ttu, R.string.license_bsd3, "https://github.com/ttu-ttu/ebook-reader"),
    AboutCredit(R.string.credit_jmdict, R.string.license_cc_by_sa4, "https://github.com/yomidevs/jmdict-yomitan"),
    AboutCredit(R.string.credit_jiten, R.string.license_apache2, "https://github.com/Sirush/Jiten"),
    AboutCredit(R.string.credit_kanji_stroke_order, R.string.license_bsd3, "https://www.nihilist.org.uk"),
)

@Composable
fun AboutDependenciesScreen(onClose: () -> Unit) {
    AboutCreditsScreen(stringResource(R.string.about_dependencies), dependencies, onClose)
}

@Composable
fun AboutAcknowledgementsScreen(onClose: () -> Unit) {
    AboutCreditsScreen(stringResource(R.string.about_attribution), attribution, onClose)
}

@Composable
private fun AboutCreditsScreen(title: String, credits: List<AboutCredit>, onClose: () -> Unit) {
    val model: AboutActionsViewModel = hiltViewModel()
    val openLink = rememberAboutLinkOpener(model)
    AboutActionErrors(model)
    StaticSettingsDetailScaffold(
        title = title,
        onClose = onClose,
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
        ) {
            item {
                AboutSection {
                    credits.forEachIndexed { index, credit ->
                        if (index > 0) GroupDivider()
                        CreditRow(credit, openLink)
                    }
                }
            }
        }
    }
}

@Composable
private fun CreditRow(credit: AboutCredit, openLink: (String) -> Unit) {
    AboutRow(stringResource(credit.name), value = stringResource(credit.license),
        onClick = { openLink(credit.url) })
}
