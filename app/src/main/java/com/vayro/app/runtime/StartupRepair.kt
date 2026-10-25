package com.vayro.app.runtime

import java.io.File

/**
 * Where a failed startup's Retry sends the user.
 *
 * INITIALIZE_EXISTING re-runs the already-installed runtime; REINSTALL
 * re-enters the setup flow so a broken core can be repaired.
 */
enum class StartupRetryRoute {
    INITIALIZE_EXISTING,
    REINSTALL,
}

/**
 * Pure Retry-routing decision, kept free of Android APIs so it is
 * unit-testable on the JVM.
 *
 * isInstalled() only checks that core files EXIST, while initializeExisting()
 * requires node and git to be EXECUTABLE. A core whose binaries lost their
 * exec bits (interrupted extraction, backup/restore round-trip) therefore
 * passed isInstalled() yet failed every initializeExisting() with
 * "Core runtime is missing Node.js/Git" — and Retry re-ran initializeRuntime
 * forever without ever re-entering setup, so packages could never
 * download again. Only a fully installed core with executable binaries
 * may take the initialize path; everything else reinstalls.
 */
fun startupRetryRoute(
    installed: Boolean,
    nodeExecutable: Boolean,
    gitExecutable: Boolean,
): StartupRetryRoute =
    if (installed && nodeExecutable && gitExecutable) {
        StartupRetryRoute.INITIALIZE_EXISTING
    } else {
        StartupRetryRoute.REINSTALL
    }

/**
 * Best-effort restore of exec bits on core binaries. Returns true only when
 * every listed file exists and is executable afterwards; a file that is
 * missing or still not executable after the attempt reports false so the
 * caller can fall back to rebuilding the core.
 */
fun repairCoreExecBits(files: List<File>): Boolean {
    files.forEach { file ->
        if (file.isFile && !file.canExecute()) {
            runCatching { file.setExecutable(true) }
        }
    }
    return files.all { it.isFile && it.canExecute() }
}
