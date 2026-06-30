package moe.antimony.hoshi.navigation

import androidx.navigation3.runtime.NavKey

internal fun MutableList<NavKey>.popAppRoute(
    onReaderRouteRemoved: () -> Unit = {},
) {
    val hadReaderRoute = containsReaderRoute()
    if (size > 1) {
        removeAt(lastIndex)
    }
    notifyReaderRouteRemoved(hadReaderRoute, onReaderRouteRemoved)
}

internal fun MutableList<NavKey>.replaceWithTopLevelRoute(
    route: AppRoute,
    onReaderRouteRemoved: () -> Unit = {},
) {
    val hadReaderRoute = containsReaderRoute()
    if (size == 1 && lastOrNull() == route) {
        return
    }
    clear()
    add(route)
    notifyReaderRouteRemoved(hadReaderRoute, onReaderRouteRemoved)
}

internal fun MutableList<NavKey>.openReaderRoute(bookId: String) {
    replaceWithTopLevelRoute(AppRoute.BooksRoute)
    add(AppRoute.ReaderRoute(bookId))
}

internal fun MutableList<NavKey>.removeReaderRoutes(
    onReaderRouteRemoved: () -> Unit = {},
) {
    val hadReaderRoute = containsReaderRoute()
    removeAll { route -> route is AppRoute.ReaderRoute }
    notifyReaderRouteRemoved(hadReaderRoute, onReaderRouteRemoved)
}

internal fun MutableList<NavKey>.routeExternalBookImport(
    onReaderRouteRemoved: () -> Unit = {},
) {
    replaceWithTopLevelRoute(
        route = AppRoute.BooksRoute,
        onReaderRouteRemoved = onReaderRouteRemoved,
    )
}

internal fun MutableList<NavKey>.returnFromMediaSession(
    bookId: String,
    onReaderRouteRemoved: () -> Unit = {},
) {
    if (lastOrNull() == AppRoute.ReaderRoute(bookId)) {
        return
    }
    replaceWithTopLevelRoute(
        route = AppRoute.BooksRoute,
        onReaderRouteRemoved = onReaderRouteRemoved,
    )
    add(AppRoute.ReaderRoute(bookId))
}

private fun List<NavKey>.containsReaderRoute(): Boolean =
    any { route -> route is AppRoute.ReaderRoute }

private fun List<NavKey>.notifyReaderRouteRemoved(
    hadReaderRoute: Boolean,
    onReaderRouteRemoved: () -> Unit,
) {
    if (hadReaderRoute && !containsReaderRoute()) {
        onReaderRouteRemoved()
    }
}
