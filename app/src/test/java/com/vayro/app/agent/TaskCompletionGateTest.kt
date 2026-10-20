package com.vayro.app.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v3.2.44 — Task Completion Gate decision battery.
 *
 * The user's live defect: the workspace agent took a video task, kept
 * reporting progress ("scenes done, setup done…") and ENDED the run
 * without ever producing the video file. These tests pin the mechanical
 * gate that now stands between a run and its "completed" ending.
 */
class TaskCompletionGateTest {

    private fun input(
        text: String,
        armed: Boolean = true,
        steps: List<TaskCompletionGate.PlanStepState> = emptyList(),
        existingPaths: Set<String> = emptySet(),
    ) = TaskCompletionGate.Input(
        armed = armed,
        finalText = text,
        planSteps = steps,
        pathExists = { it in existingPaths },
    )

    private fun verdict(text: String, steps: List<TaskCompletionGate.PlanStepState> = emptyList()) =
        TaskCompletionGate.evaluate(input(text, steps = steps))

    // (i) Promise language at the end of a run -> NOT complete.
    @Test
    fun promiseLanguageContinues() {
        val v = verdict(
            "Setup is done and the first four scenes are written. " +
                "Next I will assemble the composition and render the video.",
        )
        assertTrue(v is TaskCompletionGate.Verdict.Continue)
        assertTrue((v as TaskCompletionGate.Verdict.Continue).markerHits.isNotEmpty())
    }

    @Test
    fun standingByContinues() {
        val v = verdict("Everything is prepared. Standing by for your go-ahead to render.")
        assertTrue(v is TaskCompletionGate.Verdict.Continue)
    }

    @Test
    fun romanUrduPromiseContinues() {
        val v = verdict("Scenes tayyar hain, ab main render karunga — baqi hai.")
        assertTrue(v is TaskCompletionGate.Verdict.Continue)
    }

    // (ii) A named deliverable path that does not exist -> NOT complete,
    // and the continuation instruction names the missing path.
    @Test
    fun missingNamedPathContinues() {
        val path = "/workspace/video/out/kodex-promo.mp4"
        val v = verdict("Finished — the promo video is ready at $path")
        assertTrue(v is TaskCompletionGate.Verdict.Continue)
        val cont = v as TaskCompletionGate.Verdict.Continue
        assertEquals(listOf(path), cont.missingPaths)
        val instruction = TaskCompletionGate.buildContinuationInstruction(cont, "Make the Kodex promo video")
        assertTrue(instruction.contains(path))
    }

    // (iii) Named path exists (non-empty per the resolver) + clean ending
    // + no open steps -> the run may complete.
    @Test
    fun existingPathAndCleanEndingCompletes() {
        val path = "/workspace/video/out/kodex-promo.mp4"
        val v = TaskCompletionGate.evaluate(
            input("Finished. The promo video is ready at $path (58 seconds).", existingPaths = setOf(path)),
        )
        assertEquals(TaskCompletionGate.Verdict.Complete, v)
    }

    // (iv) Open plan steps -> NOT complete, from stored state and from
    // the agent's own unchecked checklist alike.
    @Test
    fun openPlanStepsContinue() {
        val steps = listOf(
            TaskCompletionGate.PlanStepState("Write the scenes", done = true),
            TaskCompletionGate.PlanStepState("Render the final video", done = false),
        )
        val v = verdict("All the preparation is done.", steps = steps)
        assertTrue(v is TaskCompletionGate.Verdict.Continue)
        assertTrue((v as TaskCompletionGate.Verdict.Continue).openSteps.contains("Render the final video"))
    }

    @Test
    fun uncheckedChecklistInFinalMessageContinues() {
        val v = verdict(
            """
            Progress so far:
            - [x] Write scenes 1-4
            - [ ] Write the remaining scenes
            - [ ] Render out/kodex-promo.mp4
            """.trimIndent(),
        )
        assertTrue(v is TaskCompletionGate.Verdict.Continue)
        val open = (v as TaskCompletionGate.Verdict.Continue).openSteps
        assertEquals(2, open.size)
        assertTrue(open.any { it.contains("remaining scenes") })
    }

    // A stored step the agent checks off in its final message is done —
    // this is the mechanical way a continued run clears the gate.
    @Test
    fun storedStepCheckedOffInFinalMessageCompletes() {
        val steps = listOf(TaskCompletionGate.PlanStepState("Render the final video", done = false))
        val v = verdict(
            """
            All steps are now done:
            - [x] Render the final video
            The file is verified and listed in the project files.
            """.trimIndent(),
            steps = steps,
        )
        assertEquals(TaskCompletionGate.Verdict.Complete, v)
    }

    // (v) Cap semantics: the gate itself is stateless, the cap is the
    // caller's budget (MAX_CONTINUATIONS), and the capped ending text
    // must never claim completion.
    @Test
    fun capConstantsAndIncompleteWording() {
        assertEquals(10, TaskCompletionGate.MAX_CONTINUATIONS)
        assertEquals(2, TaskCompletionGate.MAX_SAME_BLOCKER)
        val message = TaskCompletionGate.buildIncompleteMessage(
            openSteps = listOf("Render the final video"),
            missingPaths = listOf("/workspace/video/out/kodex-promo.mp4"),
            blockerCause = null,
        )
        assertTrue(message.contains("Task not finished"))
        assertTrue(message.contains("Render the final video"))
        assertTrue(message.contains("/workspace/video/out/kodex-promo.mp4"))
        assertFalse(message.contains("Task completed"))
        assertFalse(message.contains("completed successfully"))
    }

    // (vi) A pure conversation turn never arms the gate — even with
    // promise-shaped words and a path that does not exist.
    @Test
    fun conversationTurnNeverArms() {
        val v = TaskCompletionGate.evaluate(
            input(
                text = "Sure — next I will explain it. The file would be at /workspace/x/out.mp4. Standing by for your questions!",
                armed = false,
            ),
        )
        assertEquals(TaskCompletionGate.Verdict.Complete, v)
    }

    // (vii) A declared concrete blocker is a Blocked verdict with a
    // STABLE signature (so a repeat of the same blocker ends the run
    // incomplete-with-reason); a vague "couldn't" is NOT a blocker.
    @Test
    fun concreteBlockerDetectedWithStableSignature() {
        val text = "I cannot continue because the ElevenLabs API key is missing. " +
            "Add a valid key and the voice can be generated."
        val first = verdict(text)
        val second = verdict(text)
        assertTrue(first is TaskCompletionGate.Verdict.Blocked)
        assertTrue(second is TaskCompletionGate.Verdict.Blocked)
        assertEquals(
            (first as TaskCompletionGate.Verdict.Blocked).signature,
            (second as TaskCompletionGate.Verdict.Blocked).signature,
        )
        assertTrue(first.signature.contains("api key"))
    }

    @Test
    fun vagueFailureIsNotABlocker() {
        val v = verdict("Sorry, I couldn't do it this time.")
        assertFalse(v is TaskCompletionGate.Verdict.Blocked)
        assertTrue(v is TaskCompletionGate.Verdict.Continue)
        assertTrue((v as TaskCompletionGate.Verdict.Continue).endedOnFailureNote)
    }

    // URLs in a final message are not file paths and must not be gated.
    @Test
    fun urlsAreNotPaths() {
        val text = "The site is live at https://mazharmnzoor4227-beep.github.io/demo/ — open it to check."
        assertTrue(TaskCompletionGate.extractReferencedPaths(text).isEmpty())
        assertEquals(TaskCompletionGate.Verdict.Complete, verdict(text))
    }

    // Delivered document text may legitimately talk about the future in
    // its body; only the closing portion is promise-scanned, and strong
    // markers aside, a long clean delivery completes.
    @Test
    fun cleanLongDeliveryCompletes() {
        val body = buildString {
            appendLine("# Master Prompt")
            appendLine()
            appendLine("You are an expert assistant. When the user asks, you will create a plan first and explain each step.")
            repeat(30) { appendLine("Section detail line with ordinary instructions for the reader.") }
            appendLine()
            appendLine("The document above is complete and saved in the project files.")
        }
        assertEquals(TaskCompletionGate.Verdict.Complete, verdict(body))
    }

    // Blank final text with open steps still continues; with nothing
    // open it completes (the existing empty-response handling applies).
    @Test
    fun blankFinalText() {
        val steps = listOf(TaskCompletionGate.PlanStepState("Render the final video", done = false))
        assertTrue(verdict("", steps = steps) is TaskCompletionGate.Verdict.Continue)
        assertEquals(TaskCompletionGate.Verdict.Complete, verdict(""))
    }
}
