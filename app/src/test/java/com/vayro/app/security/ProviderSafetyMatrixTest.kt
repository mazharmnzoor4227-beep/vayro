package com.vayro.app.security

import com.vayro.app.mcp.McpPermissionManager
import com.vayro.app.mcp.McpPermissionPolicy
import com.vayro.app.mcp.McpServerInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * M5 GATE TEST: every runtime provider adapter is exercised against the SAME
 * shared deny/approve matrix ([SafetyTestMatrix]).
 *
 * Gate: no provider or MCP tool can bypass a denied operation — on DENY the
 * effect lambda must never execute and [SafetyDeniedException] must surface;
 * on PROMPT the lambda must not execute either.
 */
class ProviderSafetyMatrixTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var workspaceRoot: File

    private val mcpServerId = "matrix-mcp-server"

    @Before
    fun setUp() {
        workspaceRoot = tempFolder.newFolder("workspace")
        File(workspaceRoot, "src").mkdirs()
    }

    private fun buildGateway(setup: SafetyTestMatrix.MatrixSetup): SafetyGateway {
        // Hermetic per case: a fresh policy dir so grants/denies from one
        // case can never leak into another via the persisted JSON.
        val casePolicyDir = tempFolder.newFolder("security-case-${System.nanoTime()}")
        val caseMcpDir = tempFolder.newFolder("mcp-case-${System.nanoTime()}")
        val engine = PermissionPolicyEngine(policyDir = casePolicyDir)
        setup.globalDenies.forEach { engine.grantPermission(it, PermissionLevel.DENY) }
        setup.globalAlways.forEach { engine.grantPermission(it, PermissionLevel.ALWAYS) }
        if (setup.killSwitch) engine.engageEmergencyKillSwitch("matrix test")

        val mcpManager = McpPermissionManager(caseMcpDir, engine)
        // Matrix MCP case expects DENY: deny the matrix server up front.
        mcpManager.setGlobalServerPolicy(mcpServerId, McpPermissionPolicy.DENY)
        val server = McpServerInfo(
            id = mcpServerId,
            name = "Matrix Server",
            command = "node",
            isBuiltIn = false
        )
        return SafetyGateway(
            policyEngine = engine,
            workspaceRoot = workspaceRoot,
            mcpPermissionManager = mcpManager,
            mcpServerResolver = { id -> if (id == mcpServerId) server else null }
        )
    }

    /**
     * Routes [operation] through its provider adapter. Returns the lambda
     * result on ALLOW; throws on DENY/PROMPT without running the lambda.
     */
    private fun <T> runThroughAdapter(
        gateway: SafetyGateway,
        operation: SafetyOperation,
        effect: () -> T
    ): T {
        return when (operation) {
            is SafetyOperation.FileOp -> {
                val adapter = FileSafetyAdapter(gateway)
                when (operation.kind) {
                    FileOpKind.READ -> adapter.read(operation.path, effect = effect)
                    FileOpKind.WRITE -> adapter.write(operation.path, effect = effect)
                    FileOpKind.CREATE -> adapter.create(operation.path, effect = effect)
                    FileOpKind.DELETE -> adapter.delete(operation.path, effect = effect)
                }
            }
            is SafetyOperation.ShellExec -> {
                val adapter = ShellSafetyAdapter(gateway)
                adapter.exec(operation.command, operation.workingDir, effect = effect)
            }
            is SafetyOperation.GitOp -> {
                val adapter = GitSafetyAdapter(gateway)
                adapter.git(operation.kind, operation.repoPath, operation.remoteUrl, effect = effect)
            }
            is SafetyOperation.McpToolCall -> {
                val adapter = McpSafetyAdapter(gateway)
                adapter.callTool(operation.serverId, operation.toolName, operation.argsSummary, effect = effect)
            }
            is SafetyOperation.NetworkRequest -> {
                val adapter = NetworkSafetyAdapter(gateway)
                adapter.request(operation.url, operation.method, effect = effect)
            }
            is SafetyOperation.ApkInstall -> {
                val adapter = ApkSafetyAdapter(gateway)
                adapter.install(operation.apkPath, operation.packageName, effect = effect)
            }
            is SafetyOperation.RemoteExec -> {
                val adapter = RemoteWorkSafetyAdapter(gateway)
                adapter.execRemote(operation.target, operation.command, effect = effect)
            }
        }
    }

    @Test
    fun `every matrix case behaves identically through its provider adapter`() {
        val cases = SafetyTestMatrix.cases(mcpServerId)
        assertFalse("matrix must not be empty", cases.isEmpty())

        for (case in cases) {
            val gateway = buildGateway(case.setup)
            var executed = false
            try {
                val result = runThroughAdapter(gateway, case.operation) {
                    executed = true
                    "executed:${case.id}"
                }
                // ALLOW: lambda must have run and returned.
                assertEquals(
                    "case ${case.id}: expected ${case.expected} but lambda returned",
                    SafetyVerdict.ALLOW, case.expected
                )
                assertTrue("case ${case.id}: lambda must run on ALLOW", executed)
                assertEquals("executed:${case.id}", result)
            } catch (e: SafetyDeniedException) {
                assertEquals(
                    "case ${case.id}: expected ${case.expected} but got DENY",
                    SafetyVerdict.DENY, case.expected
                )
                assertFalse("case ${case.id}: DENIED lambda must never execute", executed)
            } catch (e: SafetyPromptRequiredException) {
                assertEquals(
                    "case ${case.id}: expected ${case.expected} but got PROMPT",
                    SafetyVerdict.PROMPT, case.expected
                )
                assertFalse("case ${case.id}: PROMPT lambda must never execute", executed)
                // Prompt must be precise: names the operation target.
                val text = e.prompt.renderText()
                assertTrue(
                    "case ${case.id}: prompt must describe the operation",
                    text.contains(case.operation.describe().take(20))
                )
            }
        }
    }

    @Test
    fun `matrix covers all seven provider families`() {
        val kinds = SafetyTestMatrix.cases(mcpServerId).map { it.operation::class.simpleName }.toSet()
        val expected = setOf(
            "FileOp", "ShellExec", "GitOp", "McpToolCall",
            "NetworkRequest", "ApkInstall", "RemoteExec"
        )
        assertEquals(expected, kinds)
    }

    @Test
    fun `matrix contains both allow and deny cases`() {
        val verdicts = SafetyTestMatrix.cases(mcpServerId).map { it.expected }.toSet()
        assertTrue(verdicts.contains(SafetyVerdict.ALLOW))
        assertTrue(verdicts.contains(SafetyVerdict.DENY))
        assertTrue(verdicts.contains(SafetyVerdict.PROMPT))
    }
}
