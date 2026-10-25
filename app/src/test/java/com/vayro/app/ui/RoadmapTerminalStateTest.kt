package com.vayro.app.ui

import com.vayro.app.model.ChatMessage
import com.vayro.app.model.ExecutionMode
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/**
 * Covers the roadmap terminal-state rules: isTerminal, segment parsing
 * stopping after a terminal roadmap, and action buttons hiding once the
 * task that produced a roadmap is over.
 */
class RoadmapTerminalStateTest {

    private val pendingRoadmapText = "### Implementation Roadmap\n1. [ ] Step one: do it\n2. [ ] Step two: verify it"
    private val doneRoadmapText = "### Implementation Roadmap\n1. [x] Step one: done\n2. [x] Step two: done"

    private fun msg(text: String, fromUser: Boolean = false, createdAt: Instant = Instant.now()) =
        ChatMessage(fromUser = fromUser, text = text, createdAt = createdAt)

    @Test
    fun isTerminalApprovesApprovedOrAllCompleted() {
        val pending = RoadmapParser.parseFromText(pendingRoadmapText, ExecutionMode.UNIFIED)!!
        assertFalse(RoadmapParser.isTerminal(pending))
        val done = RoadmapParser.parseFromText(doneRoadmapText, ExecutionMode.UNIFIED)!!
        assertTrue(RoadmapParser.isTerminal(done))
        assertTrue(RoadmapParser.isTerminal(pending.copy(isApproved = true)))
    }

    @Test
    fun attachParsedRoadmapsStopsAfterTerminalRoadmap() {
        val messages = listOf(
            msg(doneRoadmapText),
            msg(pendingRoadmapText),
        )
        val attached = RoadmapParser.attachParsedRoadmaps(messages, ExecutionMode.UNIFIED)
        assertTrue(attached[0].roadmap != null)
        // The second message sits after a terminal roadmap in the same
        // segment, so it must NOT gain a fresh parse.
        assertNull(attached[1].roadmap)
    }

    @Test
    fun segmentHasTerminalRoadmapRespectsUserBoundary() {
        val withTerminal = listOf(msg(doneRoadmapText), msg("follow-up"))
        val parsed = RoadmapParser.attachParsedRoadmaps(withTerminal, ExecutionMode.UNIFIED)
        assertTrue(RoadmapParser.segmentHasTerminalRoadmap(parsed, 1))
        val afterUser = listOf(msg(doneRoadmapText), msg("hi", fromUser = true), msg("follow-up"))
        val parsed2 = RoadmapParser.attachParsedRoadmaps(afterUser, ExecutionMode.UNIFIED)
        assertFalse(RoadmapParser.segmentHasTerminalRoadmap(parsed2, 2))
    }

    @Test
    fun roadmapActionsEnabledHidesAfterOutcomeOrLaterUserMessage() {
        val created = Instant.ofEpochMilli(1000L)
        val roadmap = RoadmapParser.parseFromText(pendingRoadmapText, ExecutionMode.UNIFIED)!!
        val base = listOf(msg(pendingRoadmapText, createdAt = created).copy(roadmap = roadmap))
        assertTrue(RoadmapParser.roadmapActionsEnabled(base, 0, null))
        // Outcome recorded after the message was created: task is over.
        assertFalse(RoadmapParser.roadmapActionsEnabled(base, 0, 2000L))
        // A later user message closed the segment.
        val withLaterUser = base + msg("next task", fromUser = true)
        assertFalse(RoadmapParser.roadmapActionsEnabled(withLaterUser, 0, null))
        // Terminal roadmaps never show actions.
        val done = RoadmapParser.parseFromText(doneRoadmapText, ExecutionMode.UNIFIED)!!
        val terminalBase = listOf(msg(doneRoadmapText, createdAt = created).copy(roadmap = done))
        assertFalse(RoadmapParser.roadmapActionsEnabled(terminalBase, 0, null))
        // No roadmap attached at all.
        assertFalse(RoadmapParser.roadmapActionsEnabled(listOf(msg("hello")), 0, null))
    }
}
