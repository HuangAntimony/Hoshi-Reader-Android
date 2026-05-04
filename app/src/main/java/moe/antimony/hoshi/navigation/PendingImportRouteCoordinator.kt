package moe.antimony.hoshi.navigation

import androidx.navigation3.runtime.NavKey

internal class PendingImportRouteCoordinator {
    fun routePendingImport(
        hasPendingImport: Boolean,
        backStack: MutableList<NavKey>,
    ) {
        if (hasPendingImport) {
            backStack.routeExternalBookImport()
        }
    }
}
