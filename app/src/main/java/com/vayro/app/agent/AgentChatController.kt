package com.vayro.app.agent

import com.vayro.app.model.ActiveRoadmap
import com.vayro.app.model.ActivityItem
import com.vayro.app.model.ChatAttachment
import com.vayro.app.model.ChatMessage
import com.vayro.app.model.ExecutionMode
import com.vayro.app.ui.RoadmapParser
import com.vayro.app.model.ScratchpadItem
import com.vayro.app.model.StepStatus
import com.vayro.app.model.TaskStatus
import java.io.File
import java.util.UUID

/**
 * Controller responsible for managing chat execution, message history,
 * prompt construction, attachment sanitization, and stream response parsing.
 * Decoupled from MainViewModel for architectural separation and unit testing.
 */
class AgentChatController {

    companion object {
        const val MAX_ATTACHMENTS_PER_MESSAGE = 10
    }

    /**
     * Sanitizes attachment names to prevent path traversal or unsafe characters.
     */
    fun sanitizeAttachmentName(name: String): String {
        val clean = name.substringAfterLast('/')
            .replace(Regex("[^A-Za-z0-9._ -]"), "_")
            .trim()
            .trim('.')
            .take(100)
        return clean.ifBlank { "attachment-${UUID.randomUUID().toString().take(8)}" }
    }

    /**
     * Resolves `@path/to/file` mentions from the prompt within the given workspace.
     */
    fun resolveMentionedWorkspacePaths(prompt: String, workspace: File?): List<String> {
        val validWorkspace = runCatching { workspace?.canonicalFile }.getOrNull() ?: return emptyList()
        val workspacePath = validWorkspace.toPath()
        val candidates = Regex("@([A-Za-z0-9._/-]+)")
            .findAll(prompt)
            .map { it.groupValues[1].trimEnd('.', ',', ';', ':', ')', ']', '}') }
            .filter { it.isNotBlank() && !it.startsWith("/") && ".." !in it.split('/') }
            .distinct()
            .toList()

        return candidates.filter { relativePath ->
            runCatching {
                val target = File(validWorkspace, relativePath).canonicalFile
                target.isFile && target.toPath().startsWith(workspacePath)
            }.getOrDefault(false)
        }.take(16)
    }

    /**
     * Constructs mode instructions for PLAN, BUILD, or UNIFIED modes.
     */
    fun buildModeInstruction(mode: ExecutionMode, requestText: String): String {
        if (requestText.contains("<execution_mode")) return ""
        return when (mode) {
            ExecutionMode.PLAN -> """
<execution_mode name="PLAN">
You are running in PLAN mode.
Your objective: Formulate a concise implementation plan and roadmap for the user's request.
CRITICAL CONSTRAINTS FOR PLAN MODE:
- DO NOT perform any file edits, modifications, creations, deletions, or write operations.
- DO NOT execute commands that modify system state, files, or environment. Only read-only exploration commands (ls, cat, git status, etc.) or read-only tools are allowed.
- Be token-efficient: keep the summary to 2-3 sentences, architecture decisions to a short bullet list, and each roadmap step to one title line plus one brief description line. No verbose prose.
- Output your plan structured with a brief summary, key architecture decisions, and an interactive roadmap under the header '### Implementation Roadmap'.
- In the roadmap, format each task as a checklist item:
1. [ ] Step title
   - Description / Details
   - Files affected: `path/to/file`
2. [ ] Next step
</execution_mode>
""".trimIndent()
            ExecutionMode.BUILD -> """
<execution_mode name="BUILD">
You are running in BUILD mode.
Your objective: Directly understand the user request, plan internally, and immediately begin implementing all required changes end-to-end.
- Read relevant files, edit code, execute build/test commands, verify changes, and report progress.
- Once finished, summarize concisely what was accomplished: list what changed (files), with one brief line per verification. Keep it short, no verbose prose.
- Do NOT output future roadmaps, speculative checklists, or ask the user to 'Approve & Build' when the task is complete.
</execution_mode>
""".trimIndent()
            ExecutionMode.UNIFIED -> """
<execution_mode name="UNIFIED">
You are running in UNIFIED mode (Phase 1: Roadmap & Planning).
Your objective: Analyze the request and workspace, formulate the plan, and provide an interactive roadmap for user review and approval before any implementation begins.
CRITICAL INSTRUCTIONS FOR UNIFIED MODE PHASE 1:
- Do NOT make any modifications to code or files yet.
- Inspect the codebase to understand requirements.
- Be token-efficient: keep the explanation to 2-3 sentences and each roadmap step to one title line plus one brief description line. No verbose prose.
- Present a brief explanation followed by '### Implementation Roadmap'.
- List the steps as numbered checklist items:
1. [ ] Step title
   - Description: Brief explanation of what will be done
   - Files: `path/to/file`
- Conclude by asking the user to review the roadmap, add any comments/adjustments, or click 'Approve & Build' to proceed.
</execution_mode>
""".trimIndent()
        }
    }

    /**
     * Builds full prompt string with mode instructions, mentioned files, attachments, and skill context.
     */
    fun buildEnrichedPrompt(
        requestText: String,
        modeInstruction: String,
        mentionedPaths: List<String>,
        attachments: List<ChatAttachment>,
        guestRoot: String,
        skillContext: String = "",
    ): String = buildString {
        appendLine(requestText)
        if (modeInstruction.isNotBlank()) {
            appendLine()
            appendLine(modeInstruction)
        }
        if (mentionedPaths.isNotEmpty()) {
            appendLine()
            appendLine("<mentioned_files>")
            appendLine("The user explicitly referenced these workspace files with @mentions. Inspect the referenced files before deciding what to change; they are context pointers, not permission to modify other files.")
            mentionedPaths.forEach { relativePath ->
                appendLine("- $guestRoot/$relativePath")
            }
            appendLine("</mentioned_files>")
        }
        if (attachments.isNotEmpty()) {
            appendLine()
            appendLine("<attached_files>")
            attachments.forEach { attachment ->
                appendLine("- ${attachment.displayName}: $guestRoot/${attachment.relativePath} (${attachment.mimeType})")
            }
            appendLine("These files were explicitly attached by the user. Inspect them only as needed for the request.")
            appendLine("</attached_files>")
        }
        if (skillContext.isNotBlank()) {
            appendLine()
            appendLine(skillContext)
        }
    }

    /**
     * Filters noisy runtime internal logs from the user-facing process list.
     */
    fun isNoisyRuntimeItem(item: ActivityItem): Boolean {
        val combined = "${item.title} ${item.detail}"
        return combined.contains("Starting Claude Code", true) ||
            combined.contains("Agent process started", true) ||
            combined.contains("Claude Code connected", true) ||
            combined.contains("Runtime warning", true) ||
            combined.contains("unrecognized_model", true) ||
            combined.contains("Writing response", true) ||
            combined.contains("Claude Code finished", true) ||
            combined.contains("Task completed", true)
    }

    /**
     * Summarizes tool actions for the planning summary.
     */
    fun toolPlanSummary(toolName: String, detail: String): String {
        val clean = detail.replace(Regex("\\s+"), " ").trim()
        val short = clean.take(90).ifBlank { "the current project" }
        return when (toolName) {
            "Write" -> "Preparing to create ${clean.substringAfterLast('/').ifBlank { "a project file" }}"
            "Edit", "NotebookEdit" -> "Preparing to update ${clean.substringAfterLast('/').ifBlank { "a project file" }}"
            "Read" -> "Preparing to inspect ${clean.substringAfterLast('/').ifBlank { "a project file" }}"
            "Glob" -> "Preparing to find matching project files"
            "Grep" -> "Preparing to search the project for $short"
            "Bash" -> if (clean.contains("cat ", true) || clean.contains("printf ", true) || clean.contains(" >")) {
                "Preparing to create or update project files with Bash"
            } else {
                "Preparing to run: $short"
            }
            else -> "Preparing to use $toolName for the next step"
        }
    }

    /**
     * Generates a human-friendly request planning summary for display in the process stream.
     */
    fun requestPlanningSummary(
        request: String,
        agentTitle: String,
        toolName: String? = null,
        detail: String = "",
    ): String {
        val cleanRequest = request.replace(Regex("\\s+"), " ").trim().take(110)
        val requestPart = if (cleanRequest.isBlank()) {
            "$agentTitle is reviewing the request"
        } else {
            "The user is asking: “$cleanRequest”"
        }
        return if (toolName == null) {
            "$requestPart. $agentTitle is deciding the next useful step."
        } else {
            "$requestPart. ${toolPlanSummary(toolName, detail)}."
        }
    }

    /**
     * Appends streaming assistant delta text to existing messages, coalescing contiguous assistant tokens.
     */
    fun appendAssistantDelta(messages: List<ChatMessage>, text: String): List<ChatMessage> {
        val lastMessage = messages.lastOrNull()
        return if (lastMessage != null && !lastMessage.fromUser && lastMessage.workItems.isEmpty() && lastMessage.workedMillis == 0L) {
            messages.dropLast(1) + lastMessage.copy(text = lastMessage.text + text)
        } else {
            messages + ChatMessage(fromUser = false, text = text)
        }
    }

    /**
     * Converts completed work items into a structured ChatMessage block in the timeline.
     */
    fun finishWorkSegment(
        messages: List<ChatMessage>,
        liveProcess: List<ActivityItem>,
        liveThinking: Boolean,
        workSegmentStartedAtMillis: Long?,
        taskStartedAtMillis: Long?,
        finishedAt: Long = System.currentTimeMillis(),
    ): Pair<List<ChatMessage>, Boolean> {
        val meaningfulItems = liveProcess.filterNot(::isNoisyRuntimeItem)
            .map { if (it.isComplete) it else it.copy(isComplete = true) }
        if (!liveThinking && meaningfulItems.isEmpty()) {
            return messages to false
        }
        val startedAt = workSegmentStartedAtMillis ?: taskStartedAtMillis ?: finishedAt
        val block = ChatMessage(
            fromUser = false,
            text = "",
            workItems = meaningfulItems,
            workedMillis = (finishedAt - startedAt).coerceAtLeast(0L),
        )
        return (messages + block) to true
    }

    /**
     * Adds the completed task duration to the final response produced for the latest user message.
     */
    fun attachTaskDuration(
        messages: List<ChatMessage>,
        taskStartedAtMillis: Long?,
        finishedAt: Long,
    ): List<ChatMessage> {
        val startedAt = taskStartedAtMillis ?: return messages
        val lastUserIndex = messages.indexOfLast { it.fromUser }
        val responseIndex = messages.indices.lastOrNull { index ->
            index > lastUserIndex && !messages[index].fromUser && messages[index].text.isNotBlank()
        } ?: return messages
        val updated = messages.toMutableList()
        updated[responseIndex] = updated[responseIndex].copy(
            workedMillis = (finishedAt - startedAt).coerceAtLeast(1L),
        )
        return updated
    }

    /**
     * Derives scratchpad progress items from the active roadmap and live process items.
     */
    fun deriveScratchpadItems(
        roadmap: ActiveRoadmap?,
        liveProcess: List<ActivityItem>,
        isRunning: Boolean,
    ): List<ScratchpadItem> {
        val items = mutableListOf<ScratchpadItem>()
        if (roadmap != null && roadmap.steps.isNotEmpty()) {
            roadmap.steps.forEach { step ->
                val status = when (step.status) {
                    StepStatus.COMPLETED -> TaskStatus.FINISHED
                    StepStatus.IN_PROGRESS -> if (isRunning) TaskStatus.RUNNING else TaskStatus.FINISHED
                    StepStatus.SKIPPED -> TaskStatus.FINISHED
                    StepStatus.PENDING -> TaskStatus.PENDING
                }
                items.add(
                    ScratchpadItem(
                        id = step.id,
                        title = step.title,
                        status = status,
                        detail = step.description,
                    )
                )
            }
        }
        liveProcess.forEach { proc ->
            if (proc.title.isNotBlank() && proc.title != "Think") {
                val status = if (proc.isComplete || !isRunning) TaskStatus.FINISHED else TaskStatus.RUNNING
                if (items.none { it.title.equals(proc.title, ignoreCase = true) }) {
                    items.add(
                        ScratchpadItem(
                            id = UUID.randomUUID().toString(),
                            title = proc.title,
                            status = status,
                            detail = proc.detail,
                        )
                    )
                }
            }
        }
        return items
    }

    /**
     * Ensures a completion message is present in the chat history if assistant did not emit text.
     */
    fun ensureCompletionMessage(
        messages: List<ChatMessage>,
        totalDurationText: String,
    ): List<ChatMessage> {
        val lastUserIndex = messages.indexOfLast { it.fromUser }
        val hasResponseForLastUser = messages.indices.any {
            it > lastUserIndex && !messages[it].fromUser && messages[it].text.isNotBlank()
        }
        return if (!hasResponseForLastUser) {
            messages + ChatMessage(
                fromUser = false,
                text = "Task completed successfully in $totalDurationText.",
            )
        } else {
            messages
        }
    }
}
