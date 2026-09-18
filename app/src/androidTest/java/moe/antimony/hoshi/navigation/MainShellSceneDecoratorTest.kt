package moe.antimony.hoshi.navigation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.rememberDecoratedNavEntries
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.ui.NavDisplay
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import moe.antimony.hoshi.R
import moe.antimony.hoshi.features.bookshelf.CompactNavigationBarTag
import moe.antimony.hoshi.features.bookshelf.MainTab
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MainShellSceneDecoratorTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun cachedTopLevelEntriesUseLatestVisibleTabs() {
        val statisticsLabel = InstrumentationRegistry
            .getInstrumentation()
            .targetContext
            .getString(R.string.main_tab_statistics)

        composeRule.setContent {
            MaterialTheme {
                Box(
                    modifier = Modifier
                        .requiredSize(width = 360.dp, height = 780.dp),
                ) {
                    DynamicStatisticsTabHarness()
                }
            }
        }

        composeRule.onNodeWithText("Enable statistics").performClick()

        composeRule.onNodeWithText("Statistics content").assertIsDisplayed()
        composeRule
            .onNode(
                hasText(statisticsLabel) and
                    hasAnyAncestor(hasTestTag(CompactNavigationBarTag)),
            )
            .assertIsDisplayed()
    }

    @Composable
    private fun DynamicStatisticsTabHarness() {
        var selectedTab by remember { mutableStateOf(MainTab.Settings) }
        var visibleTabs by remember {
            mutableStateOf(listOf(MainTab.Books, MainTab.Dictionary, MainTab.Settings))
        }
        val booksBackStack = rememberNavBackStack(AppRoute.BooksRoute)
        val dictionaryBackStack = rememberNavBackStack(AppRoute.DictionaryRoute)
        val statisticsBackStack = rememberNavBackStack(AppRoute.StatisticsRoute)
        val settingsBackStack = rememberNavBackStack(AppRoute.SettingsRoute)
        val entryDecorators = listOf(rememberSaveableStateHolderNavEntryDecorator<NavKey>())
        val entryProvider: (NavKey) -> NavEntry<NavKey> = { key ->
            val route = key as AppRoute
            NavEntry(route, metadata = appShellNavEntryMetadata(route)) {
                when (route) {
                    AppRoute.BooksRoute -> Text("Books content")
                    AppRoute.DictionaryRoute -> Text("Dictionary content")
                    AppRoute.StatisticsRoute -> Text("Statistics content")
                    AppRoute.StatisticsSettingsRoute -> Text("Statistics settings content")
                    is AppRoute.StatisticsBookRoute -> Text("Statistics book content")
                    AppRoute.SettingsRoute -> Column {
                        Text("Settings content")
                        Button(
                            onClick = {
                                visibleTabs = MainTab.entries
                                selectedTab = MainTab.Statistics
                            },
                        ) {
                            Text("Enable statistics")
                        }
                    }
                    AppRoute.MainRoute -> Text("Main content")
                    is AppRoute.ReaderRoute -> Text("Reader content")
                    is AppRoute.SettingsDetailRoute -> Text("Settings detail content")
                    AppRoute.AnkiAdvancedRoute -> Text("Anki advanced content")
                    is AppRoute.AnkiCardFormatRoute -> Text("Anki format content")
                }
            }
        }
        val booksEntries = rememberDecoratedNavEntries(
            backStack = booksBackStack,
            entryDecorators = entryDecorators,
            entryProvider = entryProvider,
        )
        val dictionaryEntries = rememberDecoratedNavEntries(
            backStack = dictionaryBackStack,
            entryDecorators = entryDecorators,
            entryProvider = entryProvider,
        )
        val statisticsEntries = rememberDecoratedNavEntries(
            backStack = statisticsBackStack,
            entryDecorators = entryDecorators,
            entryProvider = entryProvider,
        )
        val settingsEntries = rememberDecoratedNavEntries(
            backStack = settingsBackStack,
            entryDecorators = entryDecorators,
            entryProvider = entryProvider,
        )
        val sceneDecorator = rememberMainShellSceneDecoratorStrategy(
            selectedTab = selectedTab,
            visibleTabs = visibleTabs,
            onSelectedTabChange = { tab -> selectedTab = tab },
        )
        val currentEntries = when (selectedTab) {
            MainTab.Books -> booksEntries
            MainTab.Dictionary -> dictionaryEntries
            MainTab.Statistics -> statisticsEntries
            MainTab.Settings -> settingsEntries
        }

        NavDisplay(
            entries = currentEntries,
            modifier = Modifier.fillMaxSize(),
            onBack = {},
            sceneDecoratorStrategies = listOf(sceneDecorator),
        )
    }
}
