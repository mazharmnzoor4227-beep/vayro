package com.vayro.app.ui.navigation

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * Overlay-screen navigation state for PocketDev (Milestone 1).
 *
 * Separates UI navigation from domain operations: this class owns *which*
 * overlay screen is visible and the transitions between them, while the
 * screens themselves and the domain logic stay where they are. The navigator
 * is remembered at the top of [com.vayro.app.ui.PocketDevApp] and passed down;
 * domain actions (e.g. "open the App Factory") call [open] instead of
 * mutating composable-local flags.
 *
 * Only one overlay is visible at a time; opening one closes the others.
 */
class PocketDevNavigator {

    /** Which overlay screen is currently open, if any. */
    var openOverlay by mutableStateOf<OverlayScreen?>(null)
        private set

    /** Back/close handling: returns true if an overlay was open and is now closed. */
    fun goBack(): Boolean {
        return if (openOverlay != null) {
            openOverlay = null
            true
        } else {
            false
        }
    }

    fun openAppFactory() {
        openOverlay = OverlayScreen.APP_FACTORY
    }

    fun openTeamStudio() {
        openOverlay = OverlayScreen.TEAM_STUDIO
    }

    fun openCloudHybrid() {
        openOverlay = OverlayScreen.CLOUD_HYBRID
    }

    fun openMarketplace() {
        openOverlay = OverlayScreen.MARKETPLACE
    }

    fun openGitHubStudio() {
        openOverlay = OverlayScreen.GITHUB_STUDIO
    }

    fun closeOverlay() {
        openOverlay = null
    }

    // Backward-compatible flag views for existing call sites during migration.
    val showAppFactoryScreen: Boolean get() = openOverlay == OverlayScreen.APP_FACTORY
    val showTeamStudioScreen: Boolean get() = openOverlay == OverlayScreen.TEAM_STUDIO
    val showCloudHybridScreen: Boolean get() = openOverlay == OverlayScreen.CLOUD_HYBRID
    val showMarketplaceScreen: Boolean get() = openOverlay == OverlayScreen.MARKETPLACE
    val showGitHubStudioScreen: Boolean get() = openOverlay == OverlayScreen.GITHUB_STUDIO
}

/** The full-screen overlay destinations reachable from PocketDev. */
enum class OverlayScreen {
    APP_FACTORY,
    TEAM_STUDIO,
    CLOUD_HYBRID,
    MARKETPLACE,
    GITHUB_STUDIO,
}
