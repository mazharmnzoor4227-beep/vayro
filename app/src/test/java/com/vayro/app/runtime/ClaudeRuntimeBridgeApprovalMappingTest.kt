package com.vayro.app.runtime

import com.vayro.app.security.FileOpKind
import com.vayro.app.security.SafetyOperation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * QA C7: the Claude bridge routes approvals through the SafetyGateway.
 * The tool-name/command -> SafetyOperation mapping is the new code that
 * decides what the gateway authorizes, so it is tested directly.
 */
class ClaudeRuntimeBridgeApprovalMappingTest {

    @Test
    fun `command request maps to shell exec even for a write tool`() {
        val op = mapClaudeApprovalToOperation("Write", "rm -rf /tmp/x", listOf("/tmp/x"))
        assertTrue(op is SafetyOperation.ShellExec)
        assertEquals("rm -rf /tmp/x", (op as SafetyOperation.ShellExec).command)
    }

    @Test
    fun `blank command falls through to tool mapping`() {
        val op = mapClaudeApprovalToOperation("Read", "   ", listOf("/tmp/a.txt"))
        assertEquals(
            SafetyOperation.FileOp(FileOpKind.READ, "/tmp/a.txt"),
            op
        )
    }

    @Test
    fun `write and edit tools map to file write`() {
        assertEquals(
            SafetyOperation.FileOp(FileOpKind.WRITE, "src/Main.kt"),
            mapClaudeApprovalToOperation("Write", null, listOf("src/Main.kt"))
        )
        assertEquals(
            SafetyOperation.FileOp(FileOpKind.WRITE, "src/Main.kt"),
            mapClaudeApprovalToOperation("Edit", null, listOf("src/Main.kt"))
        )
    }

    @Test
    fun `delete and remove tools map to file delete`() {
        assertEquals(
            SafetyOperation.FileOp(FileOpKind.DELETE, "src/Old.kt"),
            mapClaudeApprovalToOperation("Delete", null, listOf("src/Old.kt"))
        )
        assertEquals(
            SafetyOperation.FileOp(FileOpKind.DELETE, "src/Old.kt"),
            mapClaudeApprovalToOperation("Remove", null, listOf("src/Old.kt"))
        )
    }

    @Test
    fun `other tools map to file read`() {
        assertEquals(
            SafetyOperation.FileOp(FileOpKind.READ, "src/Main.kt"),
            mapClaudeApprovalToOperation("Read", null, listOf("src/Main.kt"))
        )
        assertEquals(
            SafetyOperation.FileOp(FileOpKind.READ, "src/Main.kt"),
            mapClaudeApprovalToOperation("Glob", null, listOf("src/Main.kt"))
        )
    }

    @Test
    fun `missing path falls back to the tool name`() {
        assertEquals(
            SafetyOperation.FileOp(FileOpKind.READ, "Bash"),
            mapClaudeApprovalToOperation("Bash", null, emptyList())
        )
        assertEquals(
            SafetyOperation.FileOp(FileOpKind.WRITE, "Write"),
            mapClaudeApprovalToOperation("Write", null, emptyList())
        )
    }
}
