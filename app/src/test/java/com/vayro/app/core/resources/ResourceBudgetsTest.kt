package com.vayro.app.core.resources

import com.vayro.app.core.result.OperationStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Pure-JVM tests for [ResourceGate] and [ResourceBudgets].
 *
 * The [ResourceProbe] is faked: no Android framework involved, so these run
 * under plain JUnit4.
 */
class ResourceBudgetsTest {

    @get:Rule
    val tempFolder: TemporaryFolder = TemporaryFolder()

    private val budgets = ResourceBudgets()

    private fun fakeProbe(
        freeStorageMb: Long = budgets.minFreeStorageMb,
        availableRamMb: Long = budgets.minAvailableRamMb,
        fileCount: Long = 0,
        bytes: Long = 0,
    ): ResourceProbe = object : ResourceProbe {
        override fun freeStorageMb(): Long = freeStorageMb
        override fun availableRamMb(): Long = availableRamMb
        override fun projectFileCount(projectRoot: File): Long = fileCount
        override fun projectBytes(projectRoot: File): Long = bytes
    }

    private fun assertFailedNamingBudget(result: com.vayro.app.core.result.OperationResult, budgetName: String) {
        assertEquals(OperationStatus.FAILED, result.status)
        val error = result.error
        assertTrue("error must name the violated budget ($budgetName), was: $error", error != null)
        assertTrue("error must be user-actionable, was: $error", error!!.isNotBlank())
    }

    @Test
    fun `checkDevice fails when free storage is below minimum`() {
        val gate = ResourceGate(budgets)
        val result = gate.checkDevice(fakeProbe(freeStorageMb = budgets.minFreeStorageMb - 1))
        assertFailedNamingBudget(result, "storage")
        assertTrue(result.error!!.contains("storage", ignoreCase = true))
    }

    @Test
    fun `checkDevice fails when available RAM is below minimum`() {
        val gate = ResourceGate(budgets)
        val result = gate.checkDevice(fakeProbe(availableRamMb = budgets.minAvailableRamMb - 1))
        assertFailedNamingBudget(result, "RAM")
        assertTrue(result.error!!.contains("RAM", ignoreCase = true))
    }

    @Test
    fun `checkDevice succeeds when storage and RAM are within budget`() {
        val gate = ResourceGate(budgets)
        val result = gate.checkDevice(fakeProbe())
        assertEquals(OperationStatus.SUCCESS, result.status)
    }

    @Test
    fun `checkDevice succeeds at exact boundary values`() {
        val gate = ResourceGate(budgets)
        val result = gate.checkDevice(
            fakeProbe(
                freeStorageMb = budgets.minFreeStorageMb,
                availableRamMb = budgets.minAvailableRamMb,
            )
        )
        assertEquals(OperationStatus.SUCCESS, result.status)
    }

    @Test
    fun `checkProject fails when file count exceeds maximum`() {
        val gate = ResourceGate(budgets)
        val root = tempFolder.root
        val result = gate.checkProject(root, fakeProbe(fileCount = budgets.maxFilesPerProject + 1))
        assertFailedNamingBudget(result, "file-count")
        assertTrue(result.error!!.contains("file", ignoreCase = true))
    }

    @Test
    fun `checkProject fails when project size exceeds maximum`() {
        val gate = ResourceGate(budgets)
        val root = tempFolder.root
        val result = gate.checkProject(root, fakeProbe(bytes = budgets.maxProjectBytes + 1))
        assertFailedNamingBudget(result, "size")
    }

    @Test
    fun `checkProject succeeds when file count and size are within budget`() {
        val gate = ResourceGate(budgets)
        val root = tempFolder.root
        val result = gate.checkProject(
            root,
            fakeProbe(fileCount = budgets.maxFilesPerProject, bytes = budgets.maxProjectBytes),
        )
        assertEquals(OperationStatus.SUCCESS, result.status)
    }

    @Test
    fun `checkProject succeeds at exact boundary values`() {
        val gate = ResourceGate(budgets)
        val root = tempFolder.root
        val result = gate.checkProject(
            root,
            fakeProbe(
                fileCount = budgets.maxFilesPerProject,
                bytes = budgets.maxProjectBytes,
            ),
        )
        assertEquals(OperationStatus.SUCCESS, result.status)
    }

    @Test
    fun `never returns SUCCESS when a budget is violated`() {
        val gate = ResourceGate(budgets)
        val root = tempFolder.root
        val results = listOf(
            gate.checkDevice(fakeProbe(freeStorageMb = 0)),
            gate.checkDevice(fakeProbe(availableRamMb = 0)),
            gate.checkProject(root, fakeProbe(fileCount = Long.MAX_VALUE)),
            gate.checkProject(root, fakeProbe(bytes = Long.MAX_VALUE)),
        )
        results.forEach { result ->
            assertTrue("violated budget must never report SUCCESS, was: $result", result.status != OperationStatus.SUCCESS)
            assertEquals(OperationStatus.FAILED, result.status)
        }
    }

    @Test
    fun `custom budgets are honored`() {
        val custom = budgets.copy(minFreeStorageMb = 1000, maxFilesPerProject = 100)
        val gate = ResourceGate(custom)
        val ok = gate.checkDevice(fakeProbe(freeStorageMb = 1000))
        assertEquals(OperationStatus.SUCCESS, ok.status)
        val tooMany = gate.checkProject(tempFolder.root, fakeProbe(fileCount = 101))
        assertEquals(OperationStatus.FAILED, tooMany.status)
    }

    @Test
    fun `defaults match documented low-end device tuning`() {
        assertEquals(500L, budgets.minFreeStorageMb)
        assertEquals(400L, budgets.minAvailableRamMb)
        assertEquals(20_000L, budgets.maxFilesPerProject)
        assertEquals(500L * 1024 * 1024, budgets.maxProjectBytes)
        assertEquals(20, budgets.maxOpenEditorTabs)
        assertEquals(5_000, budgets.maxTerminalBufferLines)
    }
}
