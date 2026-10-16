package com.vayro.app.agent

import com.vayro.app.model.ActiveRoadmap
import com.vayro.app.model.ActivityItem
import com.vayro.app.model.ChatAttachment
import com.vayro.app.model.ChatMessage
import com.vayro.app.model.ExecutionMode
import com.vayro.app.model.RoadmapStep
import com.vayro.app.model.StepStatus
import com.vayro.app.model.TaskStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class AgentChatControllerTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val controller = AgentChatController()

    @Test
    fun `sanitizeAttachmentName cleans special characters and paths`() {
        assertEquals("valid_file.txt", controller.sanitizeAttachmentName("../../valid_file.txt"))
        assertEquals("report_2026.pdf", controller.sanitizeAttachmentName("path/to/report$2026.pdf"))
        assertEquals("report 2026.pdf", controller.sanitizeAttachmentName("path/to/report 2026.pdf"))
        val emptySanitized = controller.sanitizeAttachmentName("///...")
        assertTrue(emptySanitized.startsWith("attachment-"))
    }

    @Test
    fun `buildModeInstruction returns correct prompt tags for modes`() {
        val plan = controller.buildModeInstruction(ExecutionMode.PLAN, "Build a feature")
        assertTrue(plan.contains("<execution_mode name=\"PLAN\">"))
        assertTrue(plan.contains("### Implementation Roadmap"))

        val build = controller.buildModeInstruction(ExecutionMode.BUILD, "Fix a bug")
        assertTrue(build.contains("<execution_mode name=\"BUILD\">"))

        val unified = controller.buildModeInstruction(ExecutionMode.UNIFIED, "Do something")
        assertTrue(unified.contains("<execution_mode name=\"UNIFIED\">"))

        // When prompt already contains tag, mode instruction should be empty
        val existing = controller.buildModeInstruction(ExecutionMode.BUILD, "Already has <execution_mode name=\"BUILD\">")
        assertEquals("", existing)
    }

    @Test
    fun `buildEnrichedPrompt includes mentioned and attached files`() {
        val prompt = controller.buildEnrichedPrompt(
            requestText = "Refactor this code",
            modeInstruction = "<execution_mode name=\"BUILD\">",
            mentionedPaths = listOf("src/Main.kt"),
            attachments = listOf(ChatAttachment(displayName = "spec.pdf", relativePath = "attachments/spec.pdf", mimeType = "application/pdf", sizeBytes = 1024L)),
            guestRoot = "/root/workspace",
            skillContext = "<skills>Loaded skills</skills>",
        )

        assertTrue(prompt.contains("Refactor this code"))
        assertTrue(prompt.contains("<execution_mode name=\"BUILD\">"))
        assertTrue(prompt.contains("<mentioned_files>"))
        assertTrue(prompt.contains("- /root/workspace/src/Main.kt"))
        assertTrue(prompt.contains("<attached_files>"))
        assertTrue(prompt.contains("- spec.pdf: /root/workspace/attachments/spec.pdf (application/pdf)"))
        assertTrue(prompt.contains("<skills>Loaded skills</skills>"))
    }

    @Test
    fun `isNoisyRuntimeItem filters noisy logs`() {
        assertTrue(controller.isNoisyRuntimeItem(ActivityItem("Starting Claude Code", "Initializing")))
        assertTrue(controller.isNoisyRuntimeItem(ActivityItem("Claude Code finished", "Done")))
        assertTrue(controller.isNoisyRuntimeItem(ActivityItem("Runtime warning", "Deprecated")))
        assertFalse(controller.isNoisyRuntimeItem(ActivityItem("Running Bash", "./gradlew test")))
        assertFalse(controller.isNoisyRuntimeItem(ActivityItem("Edit", "src/MainActivity.kt")))
    }

    @Test
    fun `toolPlanSummary produces clear action descriptions`() {
        assertEquals("Preparing to create MyClass.kt", controller.toolPlanSummary("Write", "src/MyClass.kt"))
        assertEquals("Preparing to update App.kt", controller.toolPlanSummary("Edit", "src/App.kt"))
        assertEquals("Preparing to inspect spec.md", controller.toolPlanSummary("Read", "docs/spec.md"))
        assertEquals("Preparing to find matching project files", controller.toolPlanSummary("Glob", "*.kt"))
        assertEquals("Preparing to run: git status", controller.toolPlanSummary("Bash", "git status"))
    }

    @Test
    fun `appendAssistantDelta coalesces tokens into active assistant message`() {
        val initial = listOf(ChatMessage(fromUser = true, text = "Hello"))
        val firstDelta = controller.appendAssistantDelta(initial, "Hi ")
        assertEquals(2, firstDelta.size)
        assertEquals("Hi ", firstDelta.last().text)
        assertFalse(firstDelta.last().fromUser)

        val secondDelta = controller.appendAssistantDelta(firstDelta, "there!")
        assertEquals(2, secondDelta.size)
        assertEquals("Hi there!", secondDelta.last().text)
    }

    @Test
    fun `finishWorkSegment creates assistant block with completed items and duration`() {
        val messages = listOf(ChatMessage(fromUser = true, text = "Do work"))
        val process = listOf(
            ActivityItem("Running Bash", "compile", isComplete = false),
            ActivityItem("Edit", "File.kt", isComplete = true),
        )

        val (updated, created) = controller.finishWorkSegment(
            messages = messages,
            liveProcess = process,
            liveThinking = false,
            workSegmentStartedAtMillis = 1000L,
            taskStartedAtMillis = 1000L,
            finishedAt = 2500L,
        )

        assertTrue(created)
        assertEquals(2, updated.size)
        val workBlock = updated.last()
        assertFalse(workBlock.fromUser)
        assertEquals(2, workBlock.workItems.size)
        assertTrue(workBlock.workItems.all { it.isComplete })
        assertEquals(1500L, workBlock.workedMillis)
    }

    @Test
    fun `attachTaskDuration updates workedMillis on final response`() {
        val messages = listOf(
            ChatMessage(fromUser = true, text = "Task"),
            ChatMessage(fromUser = false, text = "Here is the result"),
        )
        val updated = controller.attachTaskDuration(messages, 1000L, 5000L)
        assertEquals(4000L, updated.last().workedMillis)
    }

    @Test
    fun `deriveScratchpadItems builds items from roadmap and process`() {
        val roadmap = ActiveRoadmap(
            id = "rm1",
            title = "Test Roadmap",
            summary = "Test roadmap summary",
            steps = listOf(
                RoadmapStep(id = "s1", title = "Step 1", description = "Desc 1", status = StepStatus.COMPLETED),
                RoadmapStep(id = "s2", title = "Step 2", description = "Desc 2", status = StepStatus.IN_PROGRESS),
            ),
        )
        val liveProcess = listOf(ActivityItem("Running Bash", "gradle test", isComplete = false))

        val items = controller.deriveScratchpadItems(roadmap, liveProcess, isRunning = true)
        assertEquals(3, items.size)
        assertEquals(TaskStatus.FINISHED, items[0].status)
        assertEquals(TaskStatus.RUNNING, items[1].status)
        assertEquals(TaskStatus.RUNNING, items[2].status)
    }

    @Test
    fun `ensureCompletionMessage adds fallback only when no response was produced`() {
        val noResponse = listOf(ChatMessage(fromUser = true, text = "Hello"))
        val withCompletion = controller.ensureCompletionMessage(noResponse, "5 seconds")
        assertEquals(2, withCompletion.size)
        assertEquals("Task completed successfully in 5 seconds.", withCompletion.last().text)

        val alreadyHasResponse = listOf(
            ChatMessage(fromUser = true, text = "Hello"),
            ChatMessage(fromUser = false, text = "I am done."),
        )
        val unchanged = controller.ensureCompletionMessage(alreadyHasResponse, "5 seconds")
        assertEquals(2, unchanged.size)
        assertEquals("I am done.", unchanged.last().text)
    }

    @Test
    fun `resolveMentionedWorkspacePaths extracts existing workspace files and blocks traversal`() {
        val workspace = tempFolder.newFolder("project")
        val file1 = File(workspace, "file1.kt").apply { writeText("// 1") }
        File(workspace, "dir").apply { mkdir() }
        val file2 = File(workspace, "dir/file2.kt").apply { writeText("// 2") }

        val prompt = "Look at @file1.kt and @dir/file2.kt and @../secret.txt and @nonexistent.kt"
        val resolved = controller.resolveMentionedWorkspacePaths(prompt, workspace)

        assertEquals(listOf("file1.kt", "dir/file2.kt"), resolved)
    }
}
