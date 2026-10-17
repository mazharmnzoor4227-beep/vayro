package com.vayro.app.core.resources

import com.vayro.app.core.result.OperationResult
import java.io.File

/**
 * Declared resource limits for the Kodex on-device environment
 * (Milestone 1: profiling/limits for low RAM, storage, and large projects).
 *
 * These budgets protect the app from exhausting a low-end device: operations
 * that would cross a budget are refused up front with a FAILED
 * [OperationResult] naming the violated limit, instead of crashing the device.
 *
 * Defaults are tuned for budget Android hardware; they can be tightened per
 * device by constructing a copy with smaller values.
 */
data class ResourceBudgets(
    /**
     * Minimum free on-device storage required before an operation may proceed.
     *
     * Below this, project setup / builds / downloads are blocked because a
     * full disk corrupts Gradle caches, Git checkouts, and artifact writes.
     */
    val minFreeStorageMb: Long = 500,

    /**
     * Minimum currently-available RAM required before an operation may proceed.
     *
     * Below this, spawning Gradle, a language server, or an AI inference pass
     * risks OOM kills on 2-3 GB RAM devices.
     */
    val minAvailableRamMb: Long = 400,

    /**
     * Maximum number of files allowed in a single project tree.
     *
     * Beyond this, file indexing, search, and Git status walks become
     * too slow and too memory-hungry for a phone-class device.
     */
    val maxFilesPerProject: Long = 20_000,

    /**
     * Maximum total byte size of a single project tree (default 500 MiB).
     *
     * Beyond this, zip/archive/download handling and build staging can
     * exhaust both storage and heap on low-end devices.
     */
    val maxProjectBytes: Long = 500 * 1024 * 1024,

    /**
     * Maximum editor tabs allowed open at once.
     *
     * Each tab holds a document model and (eventually) syntax state; capping
     * tabs bounds editor memory on low-RAM devices.
     */
    val maxOpenEditorTabs: Int = 20,

    /**
     * Maximum terminal scrollback lines retained.
     *
     * An unbounded buffer grows with every long build log and can OOM the
     * terminal view on repeated large builds.
     */
    val maxTerminalBufferLines: Int = 5_000,
)

/**
 * Pure, framework-free source of live resource measurements.
 *
 * The interface keeps the gate testable and honest: the Android wiring
 * (ActivityManager / StatFs) is future work, and until it exists nothing
 * claims to have measured real device state. Implementations must return
 * real, freshly measured values — never canned guesses.
 */
interface ResourceProbe {
    /** Current free device storage in MiB. */
    fun freeStorageMb(): Long

    /** Current available RAM in MiB. */
    fun availableRamMb(): Long

    /** Total file count under [projectRoot], recursively. */
    fun projectFileCount(projectRoot: File): Long

    /** Total byte size under [projectRoot], recursively. */
    fun projectBytes(projectRoot: File): Long
}

/**
 * Enforces [ResourceBudgets] against measurements from a [ResourceProbe],
 * reporting outcomes through the Milestone 0 integrity contract
 * ([OperationResult]).
 *
 * Integrity rule: SUCCESS is returned only when every measured value is
 * within budget. Any violation returns FAILED with a user-actionable message
 * naming the exact violated budget.
 */
class ResourceGate(private val budgets: ResourceBudgets = ResourceBudgets()) {

    /**
     * Checks device-level budgets (free storage, available RAM).
     *
     * @return SUCCESS when both are at or above their minimums, otherwise
     * FAILED naming the violated budget with an actionable remediation.
     */
    fun checkDevice(probe: ResourceProbe): OperationResult {
        val freeStorage = probe.freeStorageMb()
        if (freeStorage < budgets.minFreeStorageMb) {
            val shortfall = budgets.minFreeStorageMb - freeStorage
            return OperationResult.failure(
                error = "Free storage below minimum budget: ${freeStorage} MB available, " +
                    "minimum required is ${budgets.minFreeStorageMb} MB. " +
                    "Free up $shortfall MB (delete unused projects, downloads, or app caches) and try again.",
            )
        }
        val availableRam = probe.availableRamMb()
        if (availableRam < budgets.minAvailableRamMb) {
            val shortfall = budgets.minAvailableRamMb - availableRam
            return OperationResult.failure(
                error = "Available RAM below minimum budget: ${availableRam} MB available, " +
                    "minimum required is ${budgets.minAvailableRamMb} MB. " +
                    "Close background apps to free $shortfall MB, then try again.",
            )
        }
        return OperationResult.success(
            verificationEvidence = "device budgets verified: free storage ${freeStorage} MB >= " +
                "${budgets.minFreeStorageMb} MB, available RAM ${availableRam} MB >= " +
                "${budgets.minAvailableRamMb} MB",
        )
    }

    /**
     * Checks project-level budgets (file count, total bytes) for [projectRoot].
     *
     * @return SUCCESS when both are at or under their maximums, otherwise
     * FAILED naming the violated budget with an actionable remediation.
     */
    fun checkProject(projectRoot: File, probe: ResourceProbe): OperationResult {
        val fileCount = probe.projectFileCount(projectRoot)
        if (fileCount > budgets.maxFilesPerProject) {
            return OperationResult.failure(
                error = "Project exceeds file-count budget: $fileCount files, " +
                    "maximum is ${budgets.maxFilesPerProject} files. " +
                    "Exclude build outputs, node_modules, or vendored folders from the project, " +
                    "or split it into smaller modules.",
            )
        }
        val bytes = probe.projectBytes(projectRoot)
        if (bytes > budgets.maxProjectBytes) {
            return OperationResult.failure(
                error = "Project exceeds size budget: ${bytes / (1024 * 1024)} MB, " +
                    "maximum is ${budgets.maxProjectBytes / (1024 * 1024)} MB. " +
                    "Remove generated artifacts and large binaries, or split the project into smaller modules.",
            )
        }
        return OperationResult.success(
            verificationEvidence = "project budgets verified: $fileCount files <= " +
                "${budgets.maxFilesPerProject} files, ${bytes / (1024 * 1024)} MB <= " +
                "${budgets.maxProjectBytes / (1024 * 1024)} MB",
        )
    }
}
