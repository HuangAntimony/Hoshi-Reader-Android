package moe.antimony.hoshi.navigation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.scene.Scene
import androidx.navigation3.scene.SceneDecoratorStrategy
import androidx.navigation3.scene.SceneDecoratorStrategyScope
import moe.antimony.hoshi.features.bookshelf.HoshiMainShell
import moe.antimony.hoshi.features.bookshelf.MainShellLayoutSpec
import moe.antimony.hoshi.features.bookshelf.MainTab
import kotlin.reflect.KClass

internal const val AppShellRouteMetadataKey = "moe.antimony.hoshi.navigation.AppRoute"

internal fun appShellNavEntryMetadata(route: AppRoute): Map<String, Any> =
    mapOf(AppShellRouteMetadataKey to route)

internal fun appRouteUsesMainShell(route: AppRoute): Boolean = when (route) {
    AppRoute.MainRoute,
    AppRoute.BooksRoute,
    AppRoute.DictionaryRoute,
    AppRoute.StatisticsRoute,
    AppRoute.SettingsRoute,
    -> true
    is AppRoute.ReaderRoute,
    is AppRoute.SettingsDetailRoute,
    is AppRoute.AnkiCardFormatRoute,
    AppRoute.AnkiAdvancedRoute,
    -> false
}

internal fun appShellMainShellRoute(entries: List<NavEntry<NavKey>>): AppRoute? =
    (entries.lastOrNull()?.metadata?.get(AppShellRouteMetadataKey) as? AppRoute)
        ?.takeIf(::appRouteUsesMainShell)

internal fun appShellMainShellSceneKey(scene: Scene<NavKey>): Any =
    MainShellSceneKey(
        sceneClass = scene::class,
        sceneKey = scene.key,
    )

@Composable
internal fun rememberMainShellSceneDecoratorStrategy(
    selectedTab: MainTab,
    visibleTabs: List<MainTab>,
    onSelectedTabChange: (MainTab) -> Unit,
): SceneDecoratorStrategy<NavKey> {
    val currentShellState = rememberUpdatedState(
        MainShellSceneState(
            selectedTab = selectedTab,
            visibleTabs = visibleTabs,
            onSelectedTabChange = onSelectedTabChange,
        ),
    )
    return remember {
        MainShellSceneDecoratorStrategy { currentShellState.value }
    }
}

@Composable
internal fun currentMainShellLayoutSpec(): MainShellLayoutSpec = LocalMainShellLayoutSpec.current

private val LocalMainShellLayoutSpec = staticCompositionLocalOf<MainShellLayoutSpec> {
    error("Main shell layout spec is only available inside AppShell main scenes.")
}

private data class MainShellSceneState(
    val selectedTab: MainTab,
    val visibleTabs: List<MainTab>,
    val onSelectedTabChange: (MainTab) -> Unit,
)

private data class MainShellSceneKey(
    val sceneClass: KClass<*>,
    val sceneKey: Any,
)

private class MainShellSceneDecoratorStrategy(
    private val shellStateProvider: () -> MainShellSceneState,
) : SceneDecoratorStrategy<NavKey> {
    override fun SceneDecoratorStrategyScope<NavKey>.decorateScene(scene: Scene<NavKey>): Scene<NavKey> =
        if (appShellMainShellRoute(scene.entries) != null) {
            MainShellScene(
                delegate = scene,
                shellStateProvider = shellStateProvider,
            )
        } else {
            scene
        }
}

private class MainShellScene(
    private val delegate: Scene<NavKey>,
    private val shellStateProvider: () -> MainShellSceneState,
) : Scene<NavKey> by delegate {
    override val key: Any = appShellMainShellSceneKey(delegate)
    override val content: @Composable () -> Unit = {
        val shellState = shellStateProvider()
        HoshiMainShell(
            selectedTab = shellState.selectedTab,
            onSelectedTabChange = shellState.onSelectedTabChange,
            visibleTabs = shellState.visibleTabs,
            modifier = Modifier.fillMaxSize(),
        ) { contentModifier, layoutSpec ->
            CompositionLocalProvider(LocalMainShellLayoutSpec provides layoutSpec) {
                Box(modifier = contentModifier) {
                    delegate.content()
                }
            }
        }
    }
}
