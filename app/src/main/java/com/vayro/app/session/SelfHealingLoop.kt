package com.vayro.app.session

enum class ErrorType {
    COMPILATION_ERROR,
    TEST_FAILURE,
    SYNTAX_ERROR,
    MISSING_DEPENDENCY,
    RUNTIME_CRASH,
    LINTER_ERROR,
    BUILD_TIMEOUT,
    UNKNOWN,
}

data class ErrorDiagnosis(
    val type: ErrorType,
    val affectedFiles: List<String>,
    val rootCause: String,
    val suggestedFix: String,
    val lineCoordinates: Map<String, Int> = emptyMap(),
    val confidence: Float = 0.9f,
)

/**
 * Intelligent error analysis and self-healing loop for autonomous builds and tests.
 */
class SelfHealingLoop(val maxIterations: Int = 3) {

    var currentIteration: Int = 0
        private set

    private val failureHistory = mutableListOf<ErrorDiagnosis>()

    fun canRetry(): Boolean = currentIteration < maxIterations

    fun recordFailure(output: String): ErrorDiagnosis {
        currentIteration++
        val diagnosis = diagnose(output)
        failureHistory.add(diagnosis)
        return diagnosis
    }

    fun reset() {
        currentIteration = 0
        failureHistory.clear()
    }


    fun diagnose(output: String): ErrorDiagnosis {
        val lines = output.lines()
        val fileRegex = Regex("""(?:([A-Za-z0-9_./\\-]+\.(?:kt|java|ts|js|py|go|rs|php|dart)):(\d+))""")
        val affected = mutableListOf<String>()
        val lineCoords = mutableMapOf<String, Int>()

        for (line in lines) {
            fileRegex.findAll(line).forEach { m ->
                val f = m.groupValues[1]
                val ln = m.groupValues[2].toIntOrNull() ?: 1
                affected.add(f)
                lineCoords[f] = ln
            }
        }

        val distinctFiles = affected.distinct()
        val lower = output.lowercase()

        return when {
            lower.contains("unresolved reference") || lower.contains("cannot find symbol") || lower.contains("type mismatch") || lower.contains("incompatible types") -> {
                ErrorDiagnosis(
                    type = ErrorType.COMPILATION_ERROR,
                    affectedFiles = distinctFiles,
                    rootCause = "Type mismatch, missing import, or unresolved symbol",
                    suggestedFix = "Add missing imports, verify variable types, or check symbol signatures in affected files",
                    lineCoordinates = lineCoords,
                    confidence = 0.95f,
                )
            }
            lower.contains("assertionerror") || lower.contains("tests failed") || lower.contains("test failed") || lower.contains("comparisonfailure") -> {
                ErrorDiagnosis(
                    type = ErrorType.TEST_FAILURE,
                    affectedFiles = distinctFiles,
                    rootCause = "Test assertion mismatch or expectation violation",
                    suggestedFix = "Inspect test assertions and align implementation behavior with test requirements",
                    lineCoordinates = lineCoords,
                    confidence = 0.92f,
                )
            }
            lower.contains("syntax error") || lower.contains("expecting") || lower.contains("unexpected token") || lower.contains("parse error") -> {
                ErrorDiagnosis(
                    type = ErrorType.SYNTAX_ERROR,
                    affectedFiles = distinctFiles,
                    rootCause = "Syntax or delimiter violation",
                    suggestedFix = "Fix unclosed brackets, keywords, or punctuation near reported line coordinates",
                    lineCoordinates = lineCoords,
                    confidence = 0.98f,
                )
            }
            lower.contains("could not resolve") || lower.contains("package does not exist") || lower.contains("modulenotfounderror") -> {
                ErrorDiagnosis(
                    type = ErrorType.MISSING_DEPENDENCY,
                    affectedFiles = distinctFiles,
                    rootCause = "Unresolved package or library dependency",
                    suggestedFix = "Verify package declarations, dependencies in build files, or import paths",
                    lineCoordinates = lineCoords,
                    confidence = 0.88f,
                )
            }
            lower.contains("timed out") || lower.contains("timeout") || lower.contains("execution timed out") -> {
                ErrorDiagnosis(
                    type = ErrorType.BUILD_TIMEOUT,
                    affectedFiles = distinctFiles,
                    rootCause = "Build or test process timed out",
                    suggestedFix = "Optimize test execution, increase timeout, or eliminate blocking operations",
                    lineCoordinates = lineCoords,
                    confidence = 0.85f,
                )
            }
            lower.contains("lint error") || lower.contains("checkstyle") || lower.contains("formatting error") -> {
                ErrorDiagnosis(
                    type = ErrorType.LINTER_ERROR,
                    affectedFiles = distinctFiles,
                    rootCause = "Code style or static analysis rule violation",
                    suggestedFix = "Apply code formatter or satisfy linter requirements in affected files",
                    lineCoordinates = lineCoords,
                    confidence = 0.90f,
                )
            }
            lower.contains("exception in thread") || lower.contains("caused by:") || lower.contains("nullpointerexception") -> {
                ErrorDiagnosis(
                    type = ErrorType.RUNTIME_CRASH,
                    affectedFiles = distinctFiles,
                    rootCause = "Unhandled runtime exception during execution",
                    suggestedFix = "Add null guards, bounds checks, or exception handling around failing line coordinates",
                    lineCoordinates = lineCoords,
                    confidence = 0.93f,
                )
            }
            else -> {
                ErrorDiagnosis(
                    type = ErrorType.UNKNOWN,
                    affectedFiles = distinctFiles,
                    rootCause = "Build or test failure",
                    suggestedFix = "Review compiler/runner log output and adjust recent modifications",
                    lineCoordinates = lineCoords,
                    confidence = 0.70f,
                )
            }
        }
    }

    /**
     * Constructs actionable repair prompt fed back into agent runtime during autonomous healing.
     */
    fun buildRepairPrompt(diagnosis: ErrorDiagnosis, iteration: Int, maxIterations: Int): String {
        return buildString {
            appendLine("### Autonomous Self-Healing Repair (Iteration $iteration of $maxIterations)")
            appendLine("The previous verification failed with **${diagnosis.type.name}**.")
            appendLine("- **Root Cause:** ${diagnosis.rootCause}")
            appendLine("- **Suggested Fix:** ${diagnosis.suggestedFix}")
            if (diagnosis.affectedFiles.isNotEmpty()) {
                appendLine("- **Affected Files:** ${diagnosis.affectedFiles.joinToString(", ")}")
            }
            if (diagnosis.lineCoordinates.isNotEmpty()) {
                appendLine("- **Coordinates:** ${diagnosis.lineCoordinates.entries.joinToString(", ") { "${it.key}:${it.value}" }}")
            }
            appendLine("\nPlease modify the affected code to resolve this issue and ensure verification succeeds.")
        }.trimEnd()
    }
}
