package com.vayro.app.agent.team.model

import com.vayro.app.model.AgentKind
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

data class CustomTeamAgent(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val title: String,
    val description: String,
    val baseRole: TeamRole,
    val systemPrompt: String,
    val permissions: TeamRolePermissions,
    val defaultAgentKind: AgentKind = AgentKind.ANTIGRAVITY,
    val boundProjectIds: Set<String> = emptySet(),
    val isEnabled: Boolean = true,
    val createdAt: Long = System.currentTimeMillis()
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("name", name)
        put("title", title)
        put("description", description)
        put("baseRole", baseRole.name)
        put("systemPrompt", systemPrompt)
        put("permissions", JSONObject().apply {
            put("canWriteCode", permissions.canWriteCode)
            put("canRunTerminal", permissions.canRunTerminal)
            put("canAccessNetwork", permissions.canAccessNetwork)
            put("canCommitGit", permissions.canCommitGit)
            put("canApproveReviews", permissions.canApproveReviews)
        })
        put("defaultAgentKind", defaultAgentKind.name)
        put("boundProjectIds", JSONArray(boundProjectIds.toList()))
        put("isEnabled", isEnabled)
        put("createdAt", createdAt)
    }

    companion object {
        fun fromJson(o: JSONObject): CustomTeamAgent {
            val p = o.optJSONObject("permissions")
            return CustomTeamAgent(
                id = o.optString("id", UUID.randomUUID().toString()),
                name = o.optString("name", "Custom Agent"),
                title = o.optString("title", "Custom Specialist"),
                description = o.optString("description", ""),
                baseRole = runCatching { TeamRole.valueOf(o.optString("baseRole", "DEVELOPER")) }
                    .getOrDefault(TeamRole.DEVELOPER),
                systemPrompt = o.optString("systemPrompt", ""),
                permissions = TeamRolePermissions(
                    canWriteCode = p?.optBoolean("canWriteCode") ?: false,
                    canRunTerminal = p?.optBoolean("canRunTerminal") ?: false,
                    canAccessNetwork = p?.optBoolean("canAccessNetwork") ?: false,
                    canCommitGit = p?.optBoolean("canCommitGit") ?: false,
                    canApproveReviews = p?.optBoolean("canApproveReviews") ?: false
                ),
                defaultAgentKind = runCatching { AgentKind.valueOf(o.optString("defaultAgentKind", "ANTIGRAVITY")) }
                    .getOrDefault(AgentKind.ANTIGRAVITY),
                boundProjectIds = buildSet {
                    val arr = o.optJSONArray("boundProjectIds")
                    if (arr != null) for (i in 0 until arr.length()) add(arr.optString(i))
                },
                isEnabled = o.optBoolean("isEnabled", true),
                createdAt = o.optLong("createdAt", System.currentTimeMillis())
            )
        }
    }
}

data class BuiltinAgentOverride(
    val role: TeamRole,
    val systemPrompt: String = "",
    val permissions: TeamRolePermissions? = null
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("role", role.name)
        put("systemPrompt", systemPrompt)
        put("permissions", permissions?.let {
            JSONObject().apply {
                put("canWriteCode", it.canWriteCode)
                put("canRunTerminal", it.canRunTerminal)
                put("canAccessNetwork", it.canAccessNetwork)
                put("canCommitGit", it.canCommitGit)
                put("canApproveReviews", it.canApproveReviews)
            }
        })
    }

    companion object {
        fun fromJson(o: JSONObject): BuiltinAgentOverride {
            val p = o.optJSONObject("permissions")
            return BuiltinAgentOverride(
                role = runCatching { TeamRole.valueOf(o.optString("role")) }
                    .getOrDefault(TeamRole.DEVELOPER),
                systemPrompt = o.optString("systemPrompt", ""),
                permissions = if (p == null) null else TeamRolePermissions(
                    canWriteCode = p.optBoolean("canWriteCode"),
                    canRunTerminal = p.optBoolean("canRunTerminal"),
                    canAccessNetwork = p.optBoolean("canAccessNetwork"),
                    canCommitGit = p.optBoolean("canCommitGit"),
                    canApproveReviews = p.optBoolean("canApproveReviews")
                )
            )
        }
    }
}

private data class DraftTemplate(
    val name: String,
    val title: String,
    val baseRole: TeamRole,
    val keywords: List<String>,
    val shouldDo: List<String>,
    val neverDo: List<String>
)

private val draftTemplates = listOf(
    DraftTemplate(
        name = "SEO Specialist",
        title = "SEO & Search Specialist",
        baseRole = TeamRole.ARCHITECT,
        keywords = listOf("seo", "search engine", "ranking", "keywords", "meta tag"),
        shouldDo = listOf(
            "Analyze pages for on-page SEO issues (titles, meta descriptions, headings, alt text)",
            "Suggest keyword placements that read naturally for humans first",
            "Recommend technical fixes: sitemaps, canonical tags, structured data"
        ),
        neverDo = listOf(
            "Never do keyword stuffing or cloaking",
            "Never modify code or run terminal commands",
            "Never publish changes without review"
        )
    ),
    DraftTemplate(
        name = "Content Writer",
        title = "Content & Copywriter",
        baseRole = TeamRole.MANAGER,
        keywords = listOf("content", "copywrit", "blog", "article", "copy"),
        shouldDo = listOf(
            "Write clear, engaging copy matched to the target audience",
            "Follow the project's tone of voice and style guide",
            "Produce headlines that earn attention without clickbait"
        ),
        neverDo = listOf(
            "Never plagiarize or fabricate facts, quotes, or statistics",
            "Never modify code or run terminal commands",
            "Never publish without review"
        )
    ),
    DraftTemplate(
        name = "QA Specialist",
        title = "QA & Test Specialist",
        baseRole = TeamRole.TESTER,
        keywords = listOf("test", "qa ", "quality", "bug"),
        shouldDo = listOf(
            "Design test cases for happy paths, edge cases, and error states",
            "Report bugs with exact reproduction steps",
            "Verify fixes before marking tasks complete"
        ),
        neverDo = listOf(
            "Never mark untested work as done",
            "Never change production code to make a test pass",
            "Never skip edge cases to save time"
        )
    ),
    DraftTemplate(
        name = "UI Designer",
        title = "UI/UX Designer",
        baseRole = TeamRole.UI_ENGINEER,
        keywords = listOf("design", "ui ", "ux", "interface", "layout", "figma"),
        shouldDo = listOf(
            "Design clean, accessible interfaces following Material 3",
            "Keep tap targets at least 48dp and text readable",
            "Explain the reasoning behind each design choice"
        ),
        neverDo = listOf(
            "Never ship low-contrast text or tiny tap targets",
            "Never copy another app's design wholesale",
            "Never ignore accessibility"
        )
    ),
    DraftTemplate(
        name = "API Specialist",
        title = "API & Backend Specialist",
        baseRole = TeamRole.BACKEND_ENGINEER,
        keywords = listOf("api", "backend", "endpoint", "server"),
        shouldDo = listOf(
            "Design clean REST contracts with consistent naming and status codes",
            "Validate all inputs and handle errors explicitly",
            "Document every endpoint with request/response examples"
        ),
        neverDo = listOf(
            "Never expose secrets or internal errors to clients",
            "Never commit directly to the main branch",
            "Never skip input validation"
        )
    ),
    DraftTemplate(
        name = "Database Specialist",
        title = "Database Specialist",
        baseRole = TeamRole.DATABASE_ENGINEER,
        keywords = listOf("database", "sql", "schema", "migration", "room"),
        shouldDo = listOf(
            "Design normalized schemas with explicit migration paths",
            "Index foreign keys and frequently queried columns",
            "Keep migrations reversible where possible"
        ),
        neverDo = listOf(
            "Never run destructive migrations without a backup plan",
            "Never store secrets in plain text",
            "Never change a schema without updating the migration"
        )
    ),
    DraftTemplate(
        name = "Security Specialist",
        title = "Security Specialist",
        baseRole = TeamRole.SECURITY_REVIEWER,
        keywords = listOf("security", "vulnerab", "audit", "pentest", "threat"),
        shouldDo = listOf(
            "Scan for secret leaks, unsafe inputs, and permission flaws",
            "Rank findings by severity with concrete fix guidance",
            "Verify fixes with a re-scan before sign-off"
        ),
        neverDo = listOf(
            "Never exploit a vulnerability beyond proof of concept",
            "Never dismiss a finding without evidence it is a false positive",
            "Never approve code with unresolved critical findings"
        )
    ),
    DraftTemplate(
        name = "DevOps Specialist",
        title = "DevOps Specialist",
        baseRole = TeamRole.DEVOPS_ENGINEER,
        keywords = listOf("devops", "deploy", "ci/cd", "ci ", "pipeline", "docker"),
        shouldDo = listOf(
            "Keep build scripts reproducible and documented",
            "Gate deployments on green builds and tests",
            "Prefer small, reversible releases"
        ),
        neverDo = listOf(
            "Never deploy with failing tests",
            "Never hardcode secrets in build scripts",
            "Never push directly to production without a pipeline"
        )
    ),
    DraftTemplate(
        name = "Docs Writer",
        title = "Documentation Writer",
        baseRole = TeamRole.ARCHITECT,
        keywords = listOf("doc", "document", "readme", "guide", "tutorial"),
        shouldDo = listOf(
            "Write docs a new joiner can follow without asking questions",
            "Keep examples copy-paste runnable",
            "Update docs in the same change as the code"
        ),
        neverDo = listOf(
            "Never document behavior you did not verify",
            "Never leave stale docs behind after a change",
            "Never write docs only experts can understand"
        )
    )
)

fun draftCustomAgent(description: String): CustomTeamAgent {
    val lower = description.lowercase()
    val template = draftTemplates.firstOrNull { t -> t.keywords.any { lower.contains(it) } }
    val name = template?.name ?: "Custom Specialist"
    val title = template?.title ?: "Custom Specialist"
    val baseRole = template?.baseRole ?: TeamRole.DEVELOPER
    val shouldDo = template?.shouldDo ?: listOf(
        "Break the goal into small, verifiable steps",
        "Ask for clarification when requirements are ambiguous",
        "Report progress and blockers honestly"
    )
    val neverDo = template?.neverDo ?: listOf(
        "Never invent results you did not actually produce",
        "Never take destructive actions without confirmation",
        "Never exceed your granted permissions"
    )
    val prompt = buildString {
        appendLine("You are $name, the $title on this team.")
        if (description.isNotBlank()) {
            appendLine()
            appendLine("Your focus: ${description.trim()}")
        }
        appendLine()
        appendLine("What you SHOULD do:")
        shouldDo.forEach { appendLine("- $it") }
        appendLine()
        appendLine("What you should NEVER do:")
        neverDo.forEach { appendLine("- $it") }
    }.trimEnd()
    return CustomTeamAgent(
        name = name,
        title = title,
        description = description.trim().ifBlank { "Custom team agent" },
        baseRole = baseRole,
        systemPrompt = prompt,
        permissions = TeamRolePermissions.forRole(baseRole)
    )
}

fun customAgentMatchesGoal(agent: CustomTeamAgent, goal: String): Boolean {
    if (!agent.isEnabled) return false
    val haystack = "$goal ${agent.name} ${agent.title} ${agent.description}".lowercase()
    val tokens = goal.lowercase().split(Regex("[^a-z0-9]+")).filter { it.length > 3 }.toSet()
    val agentTokens = "${agent.name} ${agent.title} ${agent.description}".lowercase()
        .split(Regex("[^a-z0-9]+")).filter { it.length > 3 }.toSet()
    if (tokens.intersect(agentTokens).isNotEmpty()) return true
    return agent.description.lowercase().split(Regex("[^a-z0-9]+"))
        .filter { it.length > 4 }
        .any { haystack.contains(it) && goal.lowercase().contains(it) }
}