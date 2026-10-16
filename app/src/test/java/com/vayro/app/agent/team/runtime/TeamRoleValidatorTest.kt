package com.vayro.app.agent.team.runtime

import com.vayro.app.agent.team.model.ReviewVerdict
import com.vayro.app.agent.team.model.TeamRole
import com.vayro.app.agent.team.model.TeamTask
import com.vayro.app.agent.team.model.TeamTaskPriority
import com.vayro.app.factory.build.CommandResult
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class TeamRoleValidatorTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private fun testerTask() = TeamTask(
        title = "Automated Unit & Integration Test Suite",
        description = "desc",
        assignedRole = TeamRole.TESTER,
        priority = TeamTaskPriority.HIGH,
    )

    private fun securityTask() = TeamTask(
        title = "Security Audit & Risk Assessment",
        description = "desc",
        assignedRole = TeamRole.SECURITY_REVIEWER,
        priority = TeamTaskPriority.HIGH,
    )

    @Test
    fun testTesterPassProducesApproval() {
        val validator = TeamRoleValidator(
            testSuiteRunner = TestSuiteRunner {
                TestSuiteResult(ran = true, passed = 14, failed = 0, skipped = 0, note = "exit=0")
            }
        )
        val outcome = validator.validateTester(testerTask(), tempFolder.root)
        assertEquals(ReviewVerdict.APPROVED, outcome.verdict)
        assertFalse(outcome.artifact.isSimulated)
        assertTrue(outcome.artifact.content.contains("14"))
    }

    @Test
    fun testTesterFailureProducesChangesRequested() {
        val validator = TeamRoleValidator(
            testSuiteRunner = TestSuiteRunner {
                TestSuiteResult(
                    ran = true, passed = 12, failed = 2, skipped = 0,
                    failedTestNames = listOf("com.x.FooTest.bar", "com.x.BazTest.qux"),
                    note = "exit=1",
                )
            }
        )
        val outcome = validator.validateTester(testerTask(), tempFolder.root)
        assertEquals(ReviewVerdict.CHANGES_REQUESTED, outcome.verdict)
        assertTrue(outcome.artifact.content.contains("com.x.FooTest.bar"))
        assertFalse(outcome.artifact.isSimulated)
    }

    @Test
    fun testTesterWithoutProjectDirIsHonest() {
        val validator = TeamRoleValidator(
            testSuiteRunner = TestSuiteRunner {
                TestSuiteResult(ran = true, passed = 99, failed = 0, skipped = 0)
            }
        )
        val outcome = validator.validateTester(testerTask(), null)
        // Must not claim tests ran when there is no project.
        assertEquals(ReviewVerdict.CHANGES_REQUESTED, outcome.verdict)
        assertTrue(outcome.artifact.content.contains("could not be executed"))
        assertFalse(outcome.artifact.isSimulated)
    }

    @Test
    fun testSecurityFindingsProduceChangesRequested() {
        val validator = TeamRoleValidator(
            securityScanner = SecurityScanner {
                SecurityScanResult(
                    filesScanned = 42,
                    findings = listOf(
                        SecurityFinding("app/keys.kt", 7, "hardcoded API key", "api_key = \"abcd1234\"")
                    ),
                )
            }
        )
        val outcome = validator.validateSecurity(securityTask(), tempFolder.root)
        assertEquals(ReviewVerdict.CHANGES_REQUESTED, outcome.verdict)
        assertTrue(outcome.artifact.content.contains("app/keys.kt"))
        assertFalse(outcome.artifact.isSimulated)
    }

    @Test
    fun testCleanSecurityScanApproves() {
        val validator = TeamRoleValidator(
            securityScanner = SecurityScanner { SecurityScanResult(filesScanned = 100) }
        )
        val outcome = validator.validateSecurity(securityTask(), tempFolder.root)
        assertEquals(ReviewVerdict.APPROVED, outcome.verdict)
        assertTrue(outcome.artifact.content.contains("100 files"))
    }

    @Test
    fun testSecurityWithoutProjectDirIsHonest() {
        val validator = TeamRoleValidator()
        val outcome = validator.validateSecurity(securityTask(), null)
        assertEquals(ReviewVerdict.CHANGES_REQUESTED, outcome.verdict)
        assertTrue(outcome.artifact.content.contains("no source scan was performed"))
    }

    @Test
    fun testHeuristicScannerFindsHardcodedSecret() {
        val dir = tempFolder.newFolder("proj")
        File(dir, "Config.kt").writeText(
            "object Config {\n    const val API_KEY = \"sk-live-abcdef123456\"\n}\n"
        )
        File(dir, "Clean.kt").writeText("fun clean() = 42\n")
        val result = HeuristicSecurityScanner().scan(dir)
        assertEquals(2, result.filesScanned)
        assertEquals(1, result.findings.size)
        assertEquals("hardcoded API key", result.findings[0].kind)
        assertEquals(2, result.findings[0].line)
    }

    @Test
    fun testHeuristicScannerSkipsBuildDirs() {
        val dir = tempFolder.newFolder("proj2")
        val buildDir = File(dir, "build").apply { mkdirs() }
        File(buildDir, "Secret.kt").writeText("const val API_KEY = \"sk-live-abcdef123456\"\n")
        val result = HeuristicSecurityScanner().scan(dir)
        assertEquals(0, result.filesScanned)
        assertTrue(result.findings.isEmpty())
    }

    @Test
    fun testGradleRunnerWithoutWrapperIsHonest() {
        val dir = tempFolder.newFolder("noproj")
        val result = GradleTestSuiteRunner().runTests(dir)
        assertFalse(result.ran)
        assertTrue(result.note.contains("No Gradle wrapper"))
    }

    @Test
    fun testGradleRunnerParsesFailedTests() {
        val dir = tempFolder.newFolder("fakeproj")
        File(dir, "gradlew").writeText("#!/bin/sh\n")
        val fakeOutput = """
            > Task :app:testDebugUnitTest
            com.example.LoginTest > loginFailsWithBadPassword FAILED
            com.example.LoginTest > loginSucceeds FAILED
            10 tests completed, 2 failed
            > Task :app:testDebugUnitTest FAILED
        """.trimIndent()
        val runner = GradleTestSuiteRunner(
            commandRunner = { _, _, _ ->
                CommandResult(
                    command = listOf("sh", "gradlew"),
                    exitCode = 1,
                    output = fakeOutput,
                )
            }
        )
        val result = runner.runTests(dir)
        assertTrue(result.ran)
        assertEquals(2, result.failed)
        assertEquals(8, result.passed)
        assertEquals(2, result.failedTestNames.size)
        assertTrue(result.failedTestNames[0].contains("LoginTest"))
    }
}
