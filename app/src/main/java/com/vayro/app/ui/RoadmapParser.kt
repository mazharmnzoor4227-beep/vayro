package com.vayro.app.ui

import com.vayro.app.model.ActiveRoadmap
import com.vayro.app.model.ExecutionMode
import com.vayro.app.model.RoadmapStep
import com.vayro.app.model.StepStatus
import java.util.UUID

object RoadmapParser {

    private val CHECKLIST_REGEX = Regex(
        """^[\s>*-]*?(?:(\d+)\.\s*)?\[([ xX])]?(.*?)$"""
    )
    private val NUMBERED_STEP_REGEX = Regex(
        """^[\s>]*?(?:(\d+)\.|\*|-)\s+\*\*(.+?)\*\*[:\s-]*(.*)$"""
    )
    private val HEADER_REGEX = Regex(
        """^#{1,4}\s+(.*)"""
    )
    private val FILE_PATH_REGEX = Regex(
        """`([A-Za-z0-9._/-]+\.[A-Za-z0-9]+)`"""
    )

    private const val MAX_PARSE_INPUT_CHARS = 30_000
    private const val MAX_ITEM_LINE_CHARS = 300

    fun parseFromText(
        markdown: String,
        mode: ExecutionMode = ExecutionMode.UNIFIED,
    ): ActiveRoadmap? {
        if (markdown.isBlank()) return null
        if (markdown.length > MAX_PARSE_INPUT_CHARS) return null

        val lines = markdown.lines()
        var roadmapTitle = "Implementation Roadmap"
        var summary = ""
        val steps = mutableListOf<RoadmapStep>()

        var inRoadmapSection = false
        var foundHeader = false
        val summaryBuilder = StringBuilder()

        var currentStepTitle: String? = null
        val currentStepDesc = StringBuilder()
        val currentStepFiles = mutableListOf<String>()
        var currentStepStatus = StepStatus.PENDING

        fun commitCurrentStep() {
            val title = currentStepTitle?.trim() ?: return
            if (title.isNotBlank()) {
                steps.add(
                    RoadmapStep(
                        id = UUID.randomUUID().toString(),
                        title = title,
                        description = currentStepDesc.toString().trim(),
                        filesAffected = currentStepFiles.distinct(),
                        status = currentStepStatus,
                    )
                )
            }
            currentStepTitle = null
            currentStepDesc.clear()
            currentStepFiles.clear()
            currentStepStatus = StepStatus.PENDING
        }

        for (line in lines) {
            val trimmed = line.trim()

            // Check for header
            val headerMatch = HEADER_REGEX.find(trimmed)
            if (headerMatch != null) {
                val headerText = headerMatch.groupValues[1].trim()
                if (isRoadmapHeader(headerText)) {
                    commitCurrentStep()
                    inRoadmapSection = true
                    foundHeader = true
                    roadmapTitle = headerText.replace(Regex("""^[#\s*]+"""), "").trim()
                    continue
                } else if (inRoadmapSection && steps.isNotEmpty()) {
                    // Encountered next unrelated major header after having collected steps
                    val level = headerMatch.value.takeWhile { it == '#' }.length
                    if (level <= 2) {
                        break
                    }
                }
            }

            // Check for checklist items: "- [ ] Step name" or "1. [ ] Step name"
            val checkMatch = CHECKLIST_REGEX.find(trimmed)
            if (checkMatch != null) {
                commitCurrentStep()
                val isChecked = checkMatch.groupValues[2].equals("x", ignoreCase = true)
                val rawTitle = checkMatch.groupValues[3].trim()
                    .replace(Regex("""^\*+|\*+$"""), "")
                    .trim()
                currentStepTitle = cleanTitle(rawTitle)
                currentStepStatus = if (isChecked) StepStatus.COMPLETED else StepStatus.PENDING
                inRoadmapSection = true

                // Check for files in the same line
                FILE_PATH_REGEX.findAll(trimmed).forEach { m ->
                    currentStepFiles.add(m.groupValues[1])
                }
                continue
            }

            // Check for bolded numbered list: "1. **Architecture design**: Setup models..."
            val numberedMatch = NUMBERED_STEP_REGEX.find(trimmed)
            if ((inRoadmapSection || isLikelyRoadmapItem(trimmed)) && numberedMatch != null) {
                commitCurrentStep()
                val titlePart = numberedMatch.groupValues[2].trim()
                val descPart = numberedMatch.groupValues[3].trim()
                currentStepTitle = titlePart
                currentStepStatus = StepStatus.PENDING
                inRoadmapSection = true

                if (descPart.isNotBlank()) {
                    currentStepDesc.append(descPart)
                }
                FILE_PATH_REGEX.findAll(trimmed).forEach { m ->
                    currentStepFiles.add(m.groupValues[1])
                }
                continue
            }

            // If we are inside an active step, accumulate description & files
            if (currentStepTitle != null) {
                if (trimmed.startsWith("- Files:") || trimmed.startsWith("Files:") || trimmed.startsWith("- Affected:")) {
                    FILE_PATH_REGEX.findAll(trimmed).forEach { m ->
                        currentStepFiles.add(m.groupValues[1])
                    }
                } else if (trimmed.isNotBlank()) {
                    if (currentStepDesc.isNotEmpty()) currentStepDesc.append("\n")
                    currentStepDesc.append(trimmed.removePrefix("- ").removePrefix("* "))
                    FILE_PATH_REGEX.findAll(trimmed).forEach { m ->
                        currentStepFiles.add(m.groupValues[1])
                    }
                }
            } else if (inRoadmapSection && !foundHeader && steps.isEmpty()) {
                if (trimmed.isNotBlank() && !trimmed.startsWith("#")) {
                    if (summaryBuilder.isNotEmpty()) summaryBuilder.append(" ")
                    summaryBuilder.append(trimmed)
                }
            } else if (foundHeader && steps.isEmpty() && trimmed.isNotBlank() && !trimmed.startsWith("#")) {
                if (summaryBuilder.isNotEmpty()) summaryBuilder.append(" ")
                summaryBuilder.append(trimmed)
            }
        }
        commitCurrentStep()
        if (steps.isEmpty()) {
            return null
        }

        val allCompleted = steps.all { it.status == StepStatus.COMPLETED }

        return ActiveRoadmap(
            id = UUID.randomUUID().toString(),
            title = roadmapTitle.ifBlank { "Implementation Roadmap" },
            summary = summaryBuilder.toString().take(300).trim(),
            steps = steps.take(50),
            comments = emptyList(),
            rawMarkdown = markdown.take(10000),
            isApproved = allCompleted,
            mode = mode,
            createdAtMillis = System.currentTimeMillis(),
        )
    }

    private fun cleanTitle(raw: String): String {
        return raw.replace(Regex("""^Step\s*\d+[:.\s-]*""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""^\d+[:.\s-]*"""), "")
            .trim()
            .ifBlank { raw }
    }

    private fun isRoadmapHeader(headerText: String): Boolean {
        val lower = headerText.lowercase()
        return lower.contains("roadmap") ||
            lower.contains("implementation plan") ||
            lower.contains("proposed plan") ||
            lower.contains("action plan") ||
            lower.contains("execution plan") ||
            lower.contains("steps to implement")
    }

    private fun isLikelyRoadmapItem(line: String): Boolean {
        if (line.length > MAX_ITEM_LINE_CHARS) return false
        val lower = line.lowercase()
        return lower.contains("step") ||
            lower.contains("phase") ||
            lower.contains("task") ||
            lower.contains("implement") ||
            lower.contains("update") ||
            lower.contains("create") ||
            lower.contains("add ")
    }
}
