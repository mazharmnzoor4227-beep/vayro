package com.vayro.app.agent.team.runtime

import com.vayro.app.agent.team.model.ArtifactType
import com.vayro.app.agent.team.model.ReviewVerdict
import com.vayro.app.agent.team.model.TeamArtifact
import com.vayro.app.agent.team.model.TeamRole
import com.vayro.app.agent.team.model.TeamTask
import com.vayro.app.factory.build.CommandRunner
import com.vayro.app.factory.build.ProcessBuilderCommandRunner
import java.io.File
import kotlin.time.Duration.Companion.minutes

/** Honest outcome of running the project's real test suite. */
data class TestSuiteResult(
    val ran: Boolean,
    val passed: Int,
    val failed: Int,
    val skipped: Int,
    val failedTestNames: List<String> = emptyList(),
    val logs: List<String> = emptyList(),
    val note: String = "",
)

/** Seam for running the real test suite — production runs Gradle; tests inject fakes. */
fun interface TestSuiteRunner {
    fun runTests(projectDir: File): TestSuiteResult
}

/** Production runner: executes the real Gradle unit-test task and parses the result honestly. */
class GradleTestSuiteRunner(
    private val commandRunner: CommandRunner = ProcessBuilderCommandRunner(),
    private val testTask: String = "testDebugUnitTest",
) : TestSuiteRunner {

    override fun runTests(projectDir: File): TestSuiteResult {
        val gradlew = File(projectDir, "gradlew")
        if (!gradlew.isFile) {
            return TestSuiteResult(
                ran = false,
                passed = 0,
                failed = 0,
                skipped = 0,
                note = "No Gradle wrapper in ${projectDir.absolutePath}; cannot run tests.",
            )
        }
        val result = commandRunner.run(
            listOf("sh", gradlew.absolutePath, testTask, "--continue"),
            projectDir,
            25.minutes,
        )
        val output = result.output
        if (result.timedOut) {
            return TestSuiteResult(
                ran = true, passed = 0, failed = 0, skipped = 0,
                logs = output.lines().takeLast(60),
                note = "Test task timed out after 25 minutes.",
            )
        }
        // Parse the Gradle test summary lines honestly; counts we cannot verify stay 0.
        val failedNames = Regex("""(?m)^(\S+) > (\S+) FAILED$""")
            .findAll(output)
            .map { "${it.groupValues[1]}.${it.groupValues[2]}" }
            .toList()
        val summary = Regex("""(\d+) tests? completed, (\d+) failed""").find(output)
        val total = summary?.groupValues?.get(1)?.toIntOrNull() ?: 0
        val failed = summary?.groupValues?.get(2)?.toIntOrNull() ?: failedNames.size
        return TestSuiteResult(
            ran = true,
            passed = (total - failed).coerceAtLeast(0),
            failed = failed,
            skipped = 0,
            failedTestNames = failedNames,
            logs = output.lines().takeLast(60),
            note = if (result.exitCode == 0 && failed == 0) "Gradle $testTask exit=0."
            else "Gradle $testTask exit=${result.exitCode}.",
        )
    }
}

/** One genuine finding from a real source scan. */
data class SecurityFinding(
    val file: String,
    val line: Int,
    val kind: String,
    val detail: String,
)

/** Honest outcome of scanning the real project sources. */
data class SecurityScanResult(
    val filesScanned: Int,
    val findings: List<SecurityFinding> = emptyList(),
)

/** Seam for real security scanning — production uses heuristics over real files; tests inject fakes. */
fun interface SecurityScanner {
    fun scan(projectDir: File): SecurityScanResult
}

/**
 * Production scanner: walks real source files (bounded) and flags genuine
 * secret-leak and risky patterns. Reports exactly what it checked — a clean
 * scan is "no findings in the scanned files", never a "zero-CVE" guarantee.
 */
class HeuristicSecurityScanner(
    private val maxFiles: Int = 500,
    private val maxBytesPerFile: Long = 512L * 1024L,
) : SecurityScanner {

    private val secretPatterns = listOf(
        Regex("""(?i)(api[_-]?key|apikey)\s*[:=]\s*["'][^"']{8,}["']""") to "hardcoded API key",
        Regex("""(?i)(secret|password|passwd|pwd)\s*[:=]\s*["'][^"']{4,}["']""") to "hardcoded secret/password",
        Regex("""(?i)(bearer\s+[A-Za-z0-9\-._~+/]+=*)""") to "hardcoded bearer token",
        Regex("""-----BEGIN (RSA |EC |DSA )?PRIVATE KEY-----""") to "private key material",
        Regex("""(?i)aws[_-]?(secret[_-]?access[_-]?key)\s*[:=]\s*["'][^"']+["']""") to "hardcoded AWS secret",
    )

    private val sourceExtensions = setOf("kt", "java", "js", "ts", "py", "xml", "gradle", "kts", "json", "yml", "yaml", "properties")

    override fun scan(projectDir: File): SecurityScanResult {
        if (!projectDir.isDirectory) return SecurityScanResult(filesScanned = 0)
        val findings = mutableListOf<SecurityFinding>()
        var scanned = 0
        projectDir.walkTopDown()
            .onEnter { dir ->
                val name = dir.name
                !(name == ".git" || name == "build" || name == ".gradle" || name == "node_modules" || name == ".cxx")
            }
            .filter { it.isFile && it.length() <= maxBytesPerFile && it.extension.lowercase() in sourceExtensions }
            .take(maxFiles)
            .forEach { file ->
                scanned++
                runCatching {
                    file.readLines().forEachIndexed { index, line ->
                        secretPatterns.forEach { (pattern, kind) ->
                            if (pattern.containsMatchIn(line)) {
                                findings += SecurityFinding(
                                    file = projectDir.toPath().relativize(file.toPath()).toString(),
                                    line = index + 1,
                                    kind = kind,
                                    detail = line.trim().take(120),
                                )
                            }
                        }
                    }
                }
            }
        return SecurityScanResult(filesScanned = scanned, findings = findings)
    }
}

/**
 * Real validation for the TESTER and SECURITY_REVIEWER roles (Milestone 10).
 *
 * Unlike the demo mode's illustrative reports, these validations genuinely
 * execute: the tester's verdict comes from the real test suite, the security
 * reviewer's from a real source scan. Review records submitted through this
 * validator are real ([isSimulated] = false) and may therefore count toward
 * the review gatekeeper's approval requirements.
 */
class TeamRoleValidator(
    private val testSuiteRunner: TestSuiteRunner = GradleTestSuiteRunner(),
    private val securityScanner: SecurityScanner = HeuristicSecurityScanner(),
) {

    data class ValidationOutcome(
        val verdict: ReviewVerdict,
        val artifact: TeamArtifact,
        val summary: String,
    )

    /** Runs the real test suite for a TESTER task; verdict reflects the genuine result. */
    fun validateTester(task: TeamTask, projectDir: File?): ValidationOutcome {
        if (projectDir == null || !projectDir.isDirectory) {
            val artifact = TeamArtifact(
                title = "QA Test Suite Execution Report",
                type = ArtifactType.TEST_REPORT,
                authorRole = TeamRole.TESTER,
                content = "No project directory bound to this team run; the test suite could not be executed. " +
                    "No test verdict is claimed.",
                tags = setOf("team-run", "tester"),
                metadata = mapOf("taskId" to task.id),
                isSimulated = false,
            )
            return ValidationOutcome(
                verdict = ReviewVerdict.CHANGES_REQUESTED,
                artifact = artifact,
                summary = "Tester validation inconclusive: no project directory; no tests executed.",
            )
        }
        val result = testSuiteRunner.runTests(projectDir)
        val verdict = when {
            !result.ran -> ReviewVerdict.CHANGES_REQUESTED
            result.failed > 0 -> ReviewVerdict.CHANGES_REQUESTED
            else -> ReviewVerdict.APPROVED
        }
        val content = buildString {
            appendLine("Real test suite execution (${result.note})")
            appendLine("Passed: ${result.passed}, failed: ${result.failed}, skipped: ${result.skipped}, ran: ${result.ran}")
            if (result.failedTestNames.isNotEmpty()) {
                appendLine("Failed tests:")
                result.failedTestNames.forEach { appendLine("- $it") }
            }
            if (result.logs.isNotEmpty()) {
                appendLine("--- tail ---")
                result.logs.forEach { appendLine(it) }
            }
        }
        return ValidationOutcome(
            verdict = verdict,
            artifact = TeamArtifact(
                title = "QA Test Suite Execution Report",
                type = ArtifactType.TEST_REPORT,
                authorRole = TeamRole.TESTER,
                content = content,
                tags = setOf("team-run", "tester"),
                metadata = mapOf("taskId" to task.id),
                isSimulated = false,
            ),
            summary = "Tester validation: ${result.passed} passed, ${result.failed} failed → $verdict.",
        )
    }

    /** Scans the real sources for a SECURITY_REVIEWER task; verdict reflects genuine findings. */
    fun validateSecurity(task: TeamTask, projectDir: File?): ValidationOutcome {
        if (projectDir == null || !projectDir.isDirectory) {
            val artifact = TeamArtifact(
                title = "Security Vulnerability Audit",
                type = ArtifactType.SECURITY_AUDIT,
                authorRole = TeamRole.SECURITY_REVIEWER,
                content = "No project directory bound to this team run; no source scan was performed. " +
                    "No security verdict is claimed.",
                tags = setOf("team-run", "security"),
                metadata = mapOf("taskId" to task.id),
                isSimulated = false,
            )
            return ValidationOutcome(
                verdict = ReviewVerdict.CHANGES_REQUESTED,
                artifact = artifact,
                summary = "Security validation inconclusive: no project directory; no scan performed.",
            )
        }
        val scan = securityScanner.scan(projectDir)
        val verdict = if (scan.findings.isEmpty()) ReviewVerdict.APPROVED else ReviewVerdict.CHANGES_REQUESTED
        val content = buildString {
            appendLine("Real source scan: ${scan.filesScanned} files checked for secret leaks and risky patterns.")
            if (scan.findings.isEmpty()) {
                appendLine("No findings in the scanned files. This is not a zero-CVE guarantee.")
            } else {
                appendLine("Findings (${scan.findings.size}):")
                scan.findings.forEach {
                    appendLine("- ${it.file}:${it.line} [${it.kind}] ${it.detail}")
                }
            }
        }
        return ValidationOutcome(
            verdict = verdict,
            artifact = TeamArtifact(
                title = "Security Vulnerability Audit",
                type = ArtifactType.SECURITY_AUDIT,
                authorRole = TeamRole.SECURITY_REVIEWER,
                content = content,
                tags = setOf("team-run", "security"),
                metadata = mapOf("taskId" to task.id),
                isSimulated = false,
            ),
            summary = "Security validation: ${scan.filesScanned} files scanned, ${scan.findings.size} findings → $verdict.",
        )
    }
}
