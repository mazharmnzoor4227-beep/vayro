package com.vayro.app.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.SocketTimeoutException

class AppErrorCoordinatorTest {

    private val coordinator = AppErrorCoordinator(maxHistorySize = 10)

    @Test
    fun `reportError captures category, message, and default suggested action`() {
        val report = coordinator.reportError(
            category = AppErrorCategory.FILESYSTEM_CORRUPTION,
            message = "Missing critical rootfs file",
            throwable = RuntimeException("Corrupted disk block"),
            isRecoverable = true
        )

        assertNotNull(report.id)
        assertEquals(AppErrorCategory.FILESYSTEM_CORRUPTION, report.category)
        assertEquals("Missing critical rootfs file", report.message)
        assertEquals("RuntimeException", report.exceptionType)
        assertTrue(report.isRecoverable)
        assertTrue(report.suggestedAction!!.contains("checkpoint") || report.suggestedAction!!.contains("integrity"))

        assertEquals(1, coordinator.recentErrors.value.size)
        assertNull(coordinator.activeCriticalError.value)
    }

    @Test
    fun `critical unrecoverable error sets activeCriticalError state`() {
        val report = coordinator.reportError(
            category = AppErrorCategory.RUNTIME_CRASH,
            message = "Unrecoverable SIGSEGV in proot process",
            throwable = null,
            isRecoverable = false
        )

        assertNotNull(coordinator.activeCriticalError.value)
        assertEquals(report.id, coordinator.activeCriticalError.value?.id)

        coordinator.clearError(report.id)
        assertNull(coordinator.activeCriticalError.value)
        assertEquals(0, coordinator.recentErrors.value.size)
    }

    @Test
    fun `diagnoseThrowable correctly categorizes exceptions`() {
        assertEquals(
            AppErrorCategory.PROCESS_TIMEOUT,
            coordinator.diagnoseThrowable(SocketTimeoutException("Read timed out"))
        )
        assertEquals(
            AppErrorCategory.PERMISSION_DENIED,
            coordinator.diagnoseThrowable(SecurityException("Permission denied for write"))
        )
        assertEquals(
            AppErrorCategory.LOW_STORAGE,
            coordinator.diagnoseThrowable(java.io.IOException("No space left on device"))
        )
    }
}
