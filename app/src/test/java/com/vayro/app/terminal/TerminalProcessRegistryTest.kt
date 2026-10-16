package com.vayro.app.terminal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private class FakeHandle(var alive: Boolean = true) : TerminalProcessHandle {
    var destroyed = false
    var interruptCount = 0
    val sentInputs = mutableListOf<String>()
    override val isAlive: Boolean get() = alive && !destroyed
    override fun destroy() {
        destroyed = true
    }
    override fun destroyForcibly() {
        destroyed = true
    }
    override fun interrupt() {
        interruptCount++
    }
    override fun sendInput(text: String): Boolean {
        if (!isAlive) return false
        sentInputs.add(text)
        return true
    }
}

/**
 * Milestone 2 — attach/detach registry tests.
 */
class TerminalProcessRegistryTest {

    @Test
    fun attachAndLookup() {
        val registry = TerminalProcessRegistry()
        val handle = FakeHandle()
        registry.attach("s1", handle)
        assertTrue(registry.hasRunningProcess("s1"))
        assertEquals(handle, registry.handleFor("s1"))
        assertFalse(registry.hasRunningProcess("s2"))
    }

    @Test
    fun detachKeepsProcessAliveButUnbinds() {
        val registry = TerminalProcessRegistry()
        val handle = FakeHandle()
        registry.attach("s1", handle)
        val detached = registry.detach("s1")
        assertEquals(handle, detached)
        assertTrue(handle.isAlive) // process NOT destroyed by detach
        assertFalse(registry.hasRunningProcess("s1"))
        assertNull(registry.handleFor("s1"))
    }

    @Test
    fun deadHandleIsPruned() {
        val registry = TerminalProcessRegistry()
        val handle = FakeHandle(alive = false)
        registry.attach("s1", handle)
        assertFalse(registry.hasRunningProcess("s1"))
        assertNull(registry.handleFor("s1"))
        assertTrue(registry.runningSessionIds().isEmpty())
    }

    @Test
    fun reattachReplacesPreviousBinding() {
        val registry = TerminalProcessRegistry()
        val first = FakeHandle()
        val second = FakeHandle()
        registry.attach("s1", first)
        registry.attach("s1", second)
        assertEquals(second, registry.handleFor("s1"))
    }

    @Test
    fun multipleSessionsTrackedIndependently() {
        val registry = TerminalProcessRegistry()
        registry.attach("s1", FakeHandle())
        registry.attach("s2", FakeHandle())
        assertEquals(setOf("s1", "s2"), registry.runningSessionIds())
        registry.detach("s1")
        assertEquals(setOf("s2"), registry.runningSessionIds())
    }

    @Test
    fun destroyAllKillsEverything() {
        val registry = TerminalProcessRegistry()
        val h1 = FakeHandle()
        val h2 = FakeHandle()
        registry.attach("s1", h1)
        registry.attach("s2", h2)
        registry.destroyAll()
        assertTrue(h1.destroyed)
        assertTrue(h2.destroyed)
        assertTrue(registry.runningSessionIds().isEmpty())
    }
}
