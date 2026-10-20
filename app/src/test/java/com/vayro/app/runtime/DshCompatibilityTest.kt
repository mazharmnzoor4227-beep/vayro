package com.vayro.app.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The DeepSeek Harness compatibility gate (v3.2.47). Field failure being
 * locked out: the agent update paths chased npm latest (0.2.1-rc.2, whose
 * sources were refactored), installed it over the pinned 0.1.2-rc.1 tree,
 * and every Work session then died AFTER the launch approval with
 * "DeepSeek Harness 0.1.2-rc.1 is not compatible with this Kodex build".
 * These tests pin the decision logic: only the pinned, patch-ready tree may
 * ever reach an approval prompt; anything else repairs first or fails with
 * one plain chat message.
 */
class DshCompatibilityTest {

    private val pinned = "0.1.2-rc.1"

    private fun source(vararg lines: String): String = lines.joinToString("\n")

    private val persistencePatchable = source(
        "// dsh-session-persistence-jsonl (pinned release)",
        DshCompatibility.PERSISTENCE_SPEC.importBefore,
        "async function publish() {",
        "    " + DshCompatibility.PERSISTENCE_SPEC.callBefore,
        "}",
    )
    private val persistencePatched = source(
        DshCompatibility.PERSISTENCE_SPEC.importAfter,
        "    " + DshCompatibility.PERSISTENCE_SPEC.callAfter,
    )
    private val fsLocalPatchable = source(
        DshCompatibility.FS_LOCAL_SPEC.importBefore,
        "    " + DshCompatibility.FS_LOCAL_SPEC.callBefore,
    )
    private val fsLocalPatched = source(
        DshCompatibility.FS_LOCAL_SPEC.importAfter,
        "    " + DshCompatibility.FS_LOCAL_SPEC.callAfter,
    )

    // The actual import/call lines served by npm for @deepseek-ai/dsh
    // 0.2.1-rc.2 (verified against the registry tarballs): the publish call
    // survived but the import lists changed, so the pinned patch must NOT
    // recognize this tree.
    private val persistence021 = source(
        "import { link, lstat, mkdir, mkdtemp, open, readFile, readdir, realpath, rm, stat, truncate } from \"node:fs/promises\";",
        "    await link(tmp, finalPath);",
    )
    private val fsLocal021 = source(
        "import { chmod, link, lstat, mkdir, open, readFile, readdir, rename, rm, stat } from \"node:fs/promises\";",
        "    await linkFile(tempPath, absolutePath);",
    )

    @Test
    fun patchStateClassifiesPinnedPatchedForeignAndMissing() {
        assertEquals(
            DshPatchState.PATCHABLE,
            DshCompatibility.patchStateOf(persistencePatchable, DshCompatibility.PERSISTENCE_SPEC),
        )
        assertEquals(
            DshPatchState.PATCHED,
            DshCompatibility.patchStateOf(persistencePatched, DshCompatibility.PERSISTENCE_SPEC),
        )
        assertEquals(
            DshPatchState.PATCHABLE,
            DshCompatibility.patchStateOf(fsLocalPatchable, DshCompatibility.FS_LOCAL_SPEC),
        )
        assertEquals(
            DshPatchState.PATCHED,
            DshCompatibility.patchStateOf(fsLocalPatched, DshCompatibility.FS_LOCAL_SPEC),
        )
        assertEquals(
            DshPatchState.MISSING,
            DshCompatibility.patchStateOf(null, DshCompatibility.PERSISTENCE_SPEC),
        )
        // The npm-latest tree that bricked Work mode: neither patchable nor patched.
        assertEquals(
            DshPatchState.UNRECOGNIZED,
            DshCompatibility.patchStateOf(persistence021, DshCompatibility.PERSISTENCE_SPEC),
        )
        assertEquals(
            DshPatchState.UNRECOGNIZED,
            DshCompatibility.patchStateOf(fsLocal021, DshCompatibility.FS_LOCAL_SPEC),
        )
        // Partial patch (import rewritten, call not) is not a runnable state.
        val partial = source(
            DshCompatibility.PERSISTENCE_SPEC.importAfter,
            "    " + DshCompatibility.PERSISTENCE_SPEC.callBefore,
        )
        assertEquals(
            DshPatchState.UNRECOGNIZED,
            DshCompatibility.patchStateOf(partial, DshCompatibility.PERSISTENCE_SPEC),
        )
    }

    @Test
    fun readyRequiresPinnedVersionAndBothFilesPatchReady() {
        val ready = DshCompatibility.assess(pinned, persistencePatchable, fsLocalPatched)
        assertTrue(ready.isReady(pinned))
        // Same files, foreign version marker: not the verified tree.
        val foreign = DshCompatibility.assess("0.2.1-rc.2", persistencePatchable, fsLocalPatchable)
        assertFalse(foreign.isReady(pinned))
        // Pinned version but one file foreign: not ready.
        val mixed = DshCompatibility.assess(pinned, persistencePatchable, fsLocal021)
        assertFalse(mixed.isReady(pinned))
        // Pinned version but a managed file is gone: not ready.
        val partialTree = DshCompatibility.assess(pinned, persistencePatchable, null)
        assertFalse(partialTree.isReady(pinned))
    }

    @Test
    fun compatibleTreeProceedsToApproval() {
        val assessment = DshCompatibility.assess(pinned, persistencePatched, fsLocalPatched)
        assertEquals(
            DshLaunchStep.PROCEED_TO_APPROVAL,
            DshCompatibility.decideLaunch(assessment, pinned),
        )
        val freshBundle = DshCompatibility.assess(pinned, persistencePatchable, fsLocalPatchable)
        assertEquals(
            DshLaunchStep.PROCEED_TO_APPROVAL,
            DshCompatibility.decideLaunch(freshBundle, pinned),
        )
    }

    @Test
    fun incompatibleTreeWithUpdateAvailableRepairsThenRuns() {
        // The bricked-device state: npm-latest tree + its version marker.
        val bricked = DshCompatibility.assess("0.2.1-rc.2", persistence021, fsLocal021)
        assertEquals(
            DshLaunchStep.REPAIR_THEN_APPROVAL,
            DshCompatibility.decideLaunch(bricked, pinned),
        )
        // After the pinned-bundle restore the same decision proceeds.
        val healed = DshCompatibility.assess(pinned, persistencePatchable, fsLocalPatchable)
        assertEquals(
            DshLaunchStep.PROCEED_TO_APPROVAL,
            DshCompatibility.decideLaunch(healed, pinned),
        )
        // Marker lost but a tree is present: also a repair, not "not installed".
        val markerLost = DshCompatibility.assess(null, persistence021, fsLocal021)
        assertEquals(
            DshLaunchStep.REPAIR_THEN_APPROVAL,
            DshCompatibility.decideLaunch(markerLost, pinned),
        )
        // Pinned marker but damaged files: repair.
        val damaged = DshCompatibility.assess(pinned, persistence021, fsLocalPatchable)
        assertEquals(
            DshLaunchStep.REPAIR_THEN_APPROVAL,
            DshCompatibility.decideLaunch(damaged, pinned),
        )
    }

    @Test
    fun nothingInstalledKeepsTheInstallFlow() {
        val empty = DshCompatibility.assess(null, null, null)
        assertEquals(
            DshLaunchStep.NOT_INSTALLED,
            DshCompatibility.decideLaunch(empty, pinned),
        )
        val blankMarker = DshCompatibility.assess("  ", null, null)
        assertEquals(
            DshLaunchStep.NOT_INSTALLED,
            DshCompatibility.decideLaunch(blankMarker, pinned),
        )
    }

    @Test
    fun approvalNeverPrecedesTheCheck() {
        // Every non-ready assessment must resolve to a step that is NOT the
        // approval step: the launch decision exposes no path where an
        // incompatible tree reaches the approval prompt.
        val nonReady = listOf(
            DshCompatibility.assess("0.2.1-rc.2", persistence021, fsLocal021),
            DshCompatibility.assess("0.2.1-rc.2", persistencePatchable, fsLocalPatchable),
            DshCompatibility.assess(pinned, persistence021, fsLocal021),
            DshCompatibility.assess(pinned, null, fsLocalPatchable),
            DshCompatibility.assess(null, persistencePatchable, fsLocalPatchable),
            DshCompatibility.assess(null, null, null),
        )
        nonReady.forEach { assessment ->
            assertFalse(assessment.isReady(pinned))
            assertTrue(
                DshCompatibility.decideLaunch(assessment, pinned) != DshLaunchStep.PROCEED_TO_APPROVAL,
            )
        }
    }

    @Test
    fun repairFailedMessageIsPlainActionableAndClassifierSafe() {
        val message = DshCompatibility.repairFailedMessage("0.2.1-rc.2", pinned)
        assertTrue(message.startsWith(DshCompatibility.REPAIR_FAILED_PREFIX))
        assertTrue(message.contains("0.2.1-rc.2"))
        assertTrue(message.contains(pinned))
        assertTrue(message.contains("send your message again"))
        // The message must not trip the transient-network or API-key failure
        // classifiers (which would swallow it into a silent retry loop).
        val lower = message.lowercase()
        listOf(
            "connection", "offline", "network", "timeout", "timed out",
            "quota", "rate limit", "api key", "authentication", "http 401",
            "http 403", "http 429", "expired", "service unavailable",
        ).forEach { token ->
            assertFalse("message must not contain classifier token: $token", lower.contains(token))
        }
        // Unknown installed version still produces a complete message.
        val unknown = DshCompatibility.repairFailedMessage(null, pinned)
        assertTrue(unknown.startsWith(DshCompatibility.REPAIR_FAILED_PREFIX))
        assertTrue(unknown.contains(pinned))
    }

    @Test
    fun technicalDetailNamesBothVersionsAndStates() {
        val assessment = DshCompatibility.assess("0.2.1-rc.2", persistence021, fsLocalPatchable)
        val detail = assessment.technicalDetail(pinned)
        assertTrue(detail.contains("0.2.1-rc.2"))
        assertTrue(detail.contains(pinned))
        assertTrue(detail.contains("UNRECOGNIZED"))
        assertTrue(detail.contains("PATCHABLE"))
    }
}
