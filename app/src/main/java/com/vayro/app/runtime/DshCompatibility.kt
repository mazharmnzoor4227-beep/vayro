package com.vayro.app.runtime

/**
 * Pure decision logic for the DeepSeek Harness Android-compatibility gate.
 *
 * This Kodex build is verified against exactly one DeepSeek Harness release
 * ([RuntimeInstaller.DSH_VERSION], the pinned bundle). Two of that release's
 * source files publish new files with POSIX hard links, which Android blocks
 * inside PRoot, so the installer rewrites those two call sites to
 * COPYFILE_EXCL — but only after verifying the files contain the pinned
 * release's exact source lines. Any other harness tree (for example an
 * npm-latest install, whose sources have since been refactored) fails that
 * verification, and running it unpatched would corrupt session files.
 *
 * The field failure this logic prevents: the agent update paths used to chase
 * the npm "latest" release, install it, and only then discover the patched
 * sources no longer matched — leaving a harness tree in place that every
 * session rejected as "not compatible", with no way back (the version marker
 * already claimed the newest release, so no update was ever offered again).
 *
 * The decision made here runs BEFORE any launch approval in
 * [DshRuntimeBridge.startSession]: a ready tree proceeds, a foreign or
 * partial tree is first restored from the pinned bundle, and only a repair
 * that still does not produce a ready tree ends the turn — with
 * [repairFailedMessage] shown in chat, never a silent dead end.
 */

/** Patch state of one harness source file the Android compatibility patch manages. */
enum class DshPatchState {
    /** The file does not exist. */
    MISSING,

    /** The file holds the pinned release's original lines; the patch applies cleanly. */
    PATCHABLE,

    /** The file already holds the patched lines; nothing to do. */
    PATCHED,

    /** The file exists but matches neither the pinned original nor the patched form. */
    UNRECOGNIZED,
}

/** The exact source lines the Android compatibility patch rewrites in one file. */
data class DshFilePatchSpec(
    /** Path inside the guest rootfs (no leading slash). */
    val guestPath: String,
    val importBefore: String,
    val importAfter: String,
    val callBefore: String,
    val callAfter: String,
)

/** What a DeepSeek Harness session launch must do next, decided before approval. */
enum class DshLaunchStep {
    /** The installed tree is the pinned release and patch-ready: ask for approval. */
    PROCEED_TO_APPROVAL,

    /** A harness tree is present but is not the verified one: restore the pinned bundle first. */
    REPAIR_THEN_APPROVAL,

    /** Nothing is installed: keep the existing "not installed" session flow. */
    NOT_INSTALLED,
}

/** Snapshot of the installed harness tree, classified by [DshCompatibility]. */
data class DshCompatibilityAssessment(
    val installedVersion: String?,
    val persistenceState: DshPatchState,
    val fsLocalState: DshPatchState,
) {
    /** True only for the pinned release whose two managed files are patchable or patched. */
    fun isReady(pinnedVersion: String): Boolean =
        installedVersion == pinnedVersion &&
            (persistenceState == DshPatchState.PATCHABLE || persistenceState == DshPatchState.PATCHED) &&
            (fsLocalState == DshPatchState.PATCHABLE || fsLocalState == DshPatchState.PATCHED)

    /** One-line technical summary for logs and the Activity detail. */
    fun technicalDetail(pinnedVersion: String): String =
        "installed=${installedVersion ?: "none"}, expected=$pinnedVersion, " +
            "session-persistence=$persistenceState, fs-local=$fsLocalState"
}

object DshCompatibility {
    /** dsh-session-persistence-jsonl: publishes new session files with a hard link. */
    val PERSISTENCE_SPEC = DshFilePatchSpec(
        guestPath = "usr/local/lib/dsh/node_modules/@deepseek-ai/dsh-session-persistence-jsonl/lib/index.js",
        importBefore = "import { link, mkdir, mkdtemp, open, readFile, readdir, realpath, rm, stat, truncate } from \"node:fs/promises\";",
        importAfter = "import { copyFile, link, mkdir, mkdtemp, open, readFile, readdir, realpath, rm, stat, truncate } from \"node:fs/promises\";",
        callBefore = "await link(tmp, finalPath);",
        callAfter = "await copyFile(tmp, finalPath, 1);",
    )

    /** dsh-fs-local: publishes new workspace files with a hard link. */
    val FS_LOCAL_SPEC = DshFilePatchSpec(
        guestPath = "usr/local/lib/dsh/node_modules/@deepseek-ai/dsh-fs-local/lib/index.js",
        importBefore = "import { chmod, link, lstat, mkdir, open, readFile, readdir, realpath, rename, rm, stat } from \"node:fs/promises\";",
        importAfter = "import { chmod, copyFile, link, lstat, mkdir, open, readFile, readdir, realpath, rename, rm, stat } from \"node:fs/promises\";",
        callBefore = "await linkFile(tempPath, absolutePath);",
        callAfter = "await copyFile(tempPath, absolutePath, 1);",
    )

    /**
     * Prefix of the chat message emitted when an automatic repair could not
     * restore a runnable harness. Since v3.2.49 the UI surfaces it through
     * the general FailureReasonSurfacing rule (every bridge's substantive
     * failure reason becomes one chat message); the prefix remains the
     * message's stable opening for tests and support.
     */
    const val REPAIR_FAILED_PREFIX = "DeepSeek Harness could not be repaired."

    /** Classifies one managed file's content against its patch spec. */
    fun patchStateOf(source: String?, spec: DshFilePatchSpec): DshPatchState = when {
        source == null -> DshPatchState.MISSING
        spec.callAfter in source && spec.importAfter in source -> DshPatchState.PATCHED
        spec.callBefore in source && spec.importBefore in source -> DshPatchState.PATCHABLE
        else -> DshPatchState.UNRECOGNIZED
    }

    /** Builds the assessment from raw facts (marker version + the two files' contents). */
    fun assess(
        installedVersion: String?,
        persistenceSource: String?,
        fsLocalSource: String?,
    ): DshCompatibilityAssessment = DshCompatibilityAssessment(
        installedVersion = installedVersion?.trim()?.takeIf { it.isNotEmpty() },
        persistenceState = patchStateOf(persistenceSource, PERSISTENCE_SPEC),
        fsLocalState = patchStateOf(fsLocalSource, FS_LOCAL_SPEC),
    )

    /**
     * The pre-approval launch decision. Approval is reachable ONLY from a
     * ready tree (directly, or after a repair that re-verified as ready);
     * an incompatible tree can never produce an approval prompt.
     */
    fun decideLaunch(assessment: DshCompatibilityAssessment, pinnedVersion: String): DshLaunchStep = when {
        assessment.isReady(pinnedVersion) -> DshLaunchStep.PROCEED_TO_APPROVAL
        assessment.installedVersion == null &&
            assessment.persistenceState == DshPatchState.MISSING &&
            assessment.fsLocalState == DshPatchState.MISSING -> DshLaunchStep.NOT_INSTALLED
        else -> DshLaunchStep.REPAIR_THEN_APPROVAL
    }

    /**
     * The plain, actionable chat message that ends a turn whose automatic
     * repair failed. Deliberately free of network/auth keywords so session
     * failure classifiers never mistake it for a transient or key failure.
     */
    fun repairFailedMessage(installedVersion: String?, pinnedVersion: String): String =
        REPAIR_FAILED_PREFIX + " This phone has DeepSeek Harness " +
            (installedVersion?.takeIf { it.isNotBlank() } ?: "an unknown version") +
            " installed, but this Kodex build works with version $pinnedVersion. " +
            "Kodex tried to restore the supported version automatically, but the repair did not finish. " +
            "Your projects and files are not affected. Make sure the phone is online, " +
            "then send your message again — Kodex will retry the repair first."
}
