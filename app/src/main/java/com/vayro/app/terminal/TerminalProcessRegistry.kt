package com.vayro.app.terminal

/**
 * Milestone 2 — attach/detach binding between terminal sessions and their
 * running processes.
 *
 * A process keeps running when the user switches to another session tab
 * (detach); switching back re-attaches the UI to the still-running process.
 * The registry is pure logic (no Android dependencies) and fully unit-
 * testable with fake handles.
 *
 * Processes cannot survive app process death: on session restore after a
 * restart, any session still marked running is reported as interrupted (see
 * `TerminalCoordinator.loadProjectTerminal`), never silently resurrected.
 */

/** Minimal process handle abstraction so the registry stays testable. */
interface TerminalProcessHandle {
    val isAlive: Boolean
    fun destroy()
    fun destroyForcibly()
    fun interrupt()
    /** Writes a line to the process stdin. Returns false when not accepted. */
    fun sendInput(text: String): Boolean
}

/**
 * Maps session ids to their running process handles.
 *
 * All methods are expected to be called from the main thread (via the
 * coordinator). Detaching removes the binding without touching the process;
 * the process keeps running until it exits or is explicitly destroyed.
 */
class TerminalProcessRegistry {

    private val processes = mutableMapOf<String, TerminalProcessHandle>()

    /** Binds [handle] to [sessionId], replacing any previous binding. */
    fun attach(sessionId: String, handle: TerminalProcessHandle) {
        processes[sessionId] = handle
    }

    /**
     * Removes the binding for [sessionId] without touching the process.
     * Returns the handle, or null when nothing was bound.
     */
    fun detach(sessionId: String): TerminalProcessHandle? = processes.remove(sessionId)

    /** Returns the live handle bound to [sessionId], or null. */
    fun handleFor(sessionId: String): TerminalProcessHandle? =
        processes[sessionId]?.takeIf { it.isAlive } ?: run {
            // Prune dead handles lazily so hasRunningProcess stays truthful.
            if (processes[sessionId]?.isAlive == false) processes.remove(sessionId)
            null
        }

    /** True when [sessionId] currently has a live process. */
    fun hasRunningProcess(sessionId: String): Boolean = handleFor(sessionId) != null

    /** Session ids with a live process right now. */
    fun runningSessionIds(): Set<String> {
        processes.entries.removeIf { !it.value.isAlive }
        return processes.keys.toSet()
    }

    /** Destroys every tracked process and clears all bindings. */
    fun destroyAll() {
        processes.values.forEach { runCatching { it.destroy() } }
        processes.clear()
    }
}
