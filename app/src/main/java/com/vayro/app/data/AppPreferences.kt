package com.vayro.app.data

import android.content.Context
import com.vayro.app.model.AgentKind
import com.vayro.app.model.ChatMessage
import com.vayro.app.model.ChatAttachment
import com.vayro.app.model.Project
import com.vayro.app.model.ProjectKind
import com.vayro.app.model.ProjectChat
import com.vayro.app.model.ProviderKind
import com.vayro.app.model.ProviderProfile
import com.vayro.app.model.ExecutionMode
import com.vayro.app.model.ActiveRoadmap
import com.vayro.app.model.RoadmapStep
import com.vayro.app.model.RoadmapComment
import com.vayro.app.model.StepStatus
import com.vayro.app.model.defaultDshApiForProvider
import com.vayro.app.model.projectSlug
import com.vayro.app.model.providersForAgent
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.Instant

class AppPreferences(private val context: Context) {
    private val preferences = context.getSharedPreferences("pocket_preferences", Context.MODE_PRIVATE)

    var onboardingComplete: Boolean
        get() = preferences.getBoolean("onboarding_complete", false)
        set(value) { preferences.edit().putBoolean("onboarding_complete", value).commit() }

    var appTourCompleted: Boolean
        get() = preferences.getBoolean("app_tour_completed_v3", false)
        set(value) { preferences.edit().putBoolean("app_tour_completed_v3", value).commit() }

    /** One-time studio onboarding: shown on first open of each studio, never again after Skip/Exit. */
    var appFactoryOnboardingSeen: Boolean
        get() = preferences.getBoolean("app_factory_onboarding_seen", false)
        set(value) { preferences.edit().putBoolean("app_factory_onboarding_seen", value).commit() }

    var teamStudioOnboardingSeen: Boolean
        get() = preferences.getBoolean("team_studio_onboarding_seen", false)
        set(value) { preferences.edit().putBoolean("team_studio_onboarding_seen", value).commit() }

    var cloudHybridOnboardingSeen: Boolean
        get() = preferences.getBoolean("cloud_hybrid_onboarding_seen", false)
        set(value) { preferences.edit().putBoolean("cloud_hybrid_onboarding_seen", value).commit() }

    var runtimeSetupComplete: Boolean
        get() = preferences.getBoolean("runtime_setup_complete", false)
        set(value) { preferences.edit().putBoolean("runtime_setup_complete", value).commit() }

    var backgroundSetupComplete: Boolean
        get() = preferences.getBoolean("background_setup_complete", false)
        set(value) { preferences.edit().putBoolean("background_setup_complete", value).commit() }

    /** Coding agent engine the user picked during setup. Absent = pre-agent-choice install → Claude. */
    var agentKind: String
        get() = preferences.getString("agent_kind", AgentKind.CLAUDE_CODE.stableId) ?: AgentKind.CLAUDE_CODE.stableId
        set(value) { preferences.edit().putString("agent_kind", value).commit() }

    /** Agent chosen for the initial runtime installation; used as the leading UI tab. */
    var primaryAgentKind: String
        get() = preferences.getString("primary_agent_kind", "") ?: ""
        set(value) { preferences.edit().putString("primary_agent_kind", value).commit() }

    var antigravityModel: String
        get() = preferences.getString("agent_antigravity_model", "") ?: ""
        set(value) { preferences.edit().putString("agent_antigravity_model", value).commit() }

    var antigravityEffort: String
        get() = preferences.getString("agent_antigravity_effort", "high") ?: "high"
        set(value) { preferences.edit().putString("agent_antigravity_effort", value).commit() }

    var antigravitySignedIn: Boolean
        get() = preferences.getBoolean("agent_antigravity_signed_in", false)
        set(value) { preferences.edit().putBoolean("agent_antigravity_signed_in", value).commit() }

    var antigravityAccountEmail: String
        get() = preferences.getString("agent_antigravity_account_email", "") ?: ""
        set(value) { preferences.edit().putString("agent_antigravity_account_email", value).commit() }

    var antigravityAutoRoundRobin: Boolean
        get() = preferences.getBoolean("agent_antigravity_auto_round_robin", true)
        set(value) { preferences.edit().putBoolean("agent_antigravity_auto_round_robin", value).commit() }

    var antigravityAccountsJson: String
        get() = preferences.getString("agent_antigravity_accounts_json", "[]") ?: "[]"
        set(value) { preferences.edit().putString("agent_antigravity_accounts_json", value).commit() }

    var githubLogin: String
        get() = preferences.getString("github_login", "") ?: ""
        set(value) { preferences.edit().putString("github_login", value).commit() }

    /** Free Mode: when on, agent and API-provider pickers show only free options. */
    var freeModeEnabled: Boolean
        get() = preferences.getBoolean("free_mode_enabled", false)
        set(value) { preferences.edit().putBoolean("free_mode_enabled", value).commit() }

    fun saveAgentConversation(agent: AgentKind, projectId: String, chatId: String, conversationId: String?) {
        val key = agentConversationKey(agent, projectId, chatId)
        preferences.edit().apply {
            if (conversationId.isNullOrBlank()) remove(key) else putString(key, conversationId)
        }.commit()
    }

    fun loadAgentConversation(agent: AgentKind, projectId: String, chatId: String): String? =
        preferences.getString(agentConversationKey(agent, projectId, chatId), null)

    fun clearAgentConversations(agent: AgentKind) {
        val stable = agent.stableId
        val keys = preferences.all.keys.filter { key ->
            key.startsWith("agent_conversation_${stable}_") ||
                key.startsWith("agent_conversation_v2_${stable}_")
        }
        if (keys.isEmpty()) return
        preferences.edit().apply { keys.forEach(::remove) }.commit()
    }

    private fun agentConversationKey(agent: AgentKind, projectId: String, chatId: String): String {
        // Antigravity v2 sessions are created with an explicit CLI project so
        // old default-project conversations cannot redirect writes to scratch.
        val version = if (agent == AgentKind.ANTIGRAVITY) "v2_" else ""
        return "agent_conversation_${version}${agent.stableId}_${projectId}_$chatId"
    }

    /** Pinned dsh version recorded when DeepSeek Harness was installed. */
    var dshVersion: String
        get() = preferences.getString("dsh_version", "") ?: ""
        set(value) { preferences.edit().putString("dsh_version", value).commit() }

    var themeMode: String
        get() = preferences.getString("theme_mode", "dark") ?: "dark"
        set(value) { preferences.edit().putString("theme_mode", value).commit() }

    /** Font style (AppFontStyle name); "default" = bundled Claude pairing, "system" = device font. */
    var fontStyle: String
        get() = preferences.getString("font_style", "default") ?: "default"
        set(value) { preferences.edit().putString("font_style", value).commit() }

    /** Settings -> haptic feedback switch for the shell. */
    var hapticsEnabled: Boolean
        get() = preferences.getBoolean("haptics_enabled", true)
        set(value) { preferences.edit().putBoolean("haptics_enabled", value).commit() }

    /** v3.2.37 (F2): ProviderKind name powering home chat replies; blank = first connected catalog provider. */
    var chatProviderKind: String
        get() = preferences.getString("chat_provider_kind", "") ?: ""
        set(value) { preferences.edit().putString("chat_provider_kind", value).commit() }

    /** v3.2.38 (L): the model Test & Connect self-corrected to for a provider kind; wins over catalog defaults for home chat. */
    fun chatModelOverride(kindName: String): String? =
        preferences.getString("chat_model_$kindName", null)?.ifBlank { null }

    fun setChatModelOverride(kindName: String, model: String) {
        preferences.edit().putString("chat_model_$kindName", model).commit()
    }

    /** v3.2.38 (J): the Base URL Test & Connect actually validated for a provider kind; wins over catalog defaults for home chat. */
    fun chatBaseUrlOverride(kindName: String): String? =
        preferences.getString("chat_base_url_$kindName", null)?.ifBlank { null }

    fun setChatBaseUrlOverride(kindName: String, baseUrl: String) {
        preferences.edit().putString("chat_base_url_$kindName", baseUrl).commit()
    }

    /** v3.2.40: the prebuilt Gemini Live voice the Live call speaks with (a voice NAME from LiveCallProtocol.VOICES). */
    var liveCallVoice: String
        get() = preferences.getString("live_call_voice", "")?.ifBlank { null } ?: "Kore"
        set(value) { preferences.edit().putString("live_call_voice", value).commit() }

    /** v3.2.37 (F): the home thread currently open on the chat home. */
    var activeHomeThreadId: String
        get() = preferences.getString("active_home_thread_id", "") ?: ""
        set(value) { preferences.edit().putString("active_home_thread_id", value).commit() }

    /** v3.2.37 (C): per-provider "Auto-switch keys" toggle; default ON. */
    fun autoSwitchKeys(providerId: String): Boolean =
        preferences.getBoolean("autoswitch_" + providerId, true)

    fun setAutoSwitchKeys(providerId: String, enabled: Boolean) {
        preferences.edit().putBoolean("autoswitch_" + providerId, enabled).commit()
    }

    /** v3.2.37 (G): "Keep working in background" — gates the run foreground service. Default ON. */
    var keepWorkingInBackground: Boolean
        get() = preferences.getBoolean("keep_working_background", true)
        set(value) { preferences.edit().putBoolean("keep_working_background", value).commit() }

    /** v3.2.37 (G): the battery-optimization hint is shown once, never nagged. */
    var batteryHintSeen: Boolean
        get() = preferences.getBoolean("battery_hint_seen", false)
        set(value) { preferences.edit().putBoolean("battery_hint_seen", value).commit() }

    /** Projects/chats the user pinned in the sidebar or Chats screen. */
    var pinnedProjectIds: Set<String>
        get() = preferences.getStringSet("pinned_project_ids", emptySet()) ?: emptySet()
        set(value) { preferences.edit().putStringSet("pinned_project_ids", value).commit() }

    var legacySeededCredentialRemoved: Boolean
        get() = preferences.getBoolean("legacy_seeded_credential_removed", false)
        set(value) { preferences.edit().putBoolean("legacy_seeded_credential_removed", value).commit() }

    var testProviderDefaultsVersion: Int
        get() = preferences.getInt("test_provider_defaults_version", 0)
        set(value) { preferences.edit().putInt("test_provider_defaults_version", value).commit() }

    var lastAppUpdateCheckMillis: Long
        get() = preferences.getLong("last_app_update_check_millis", 0L)
        set(value) { preferences.edit().putLong("last_app_update_check_millis", value).commit() }

    /**
     * Debug-only manifest URL override. Empty in release builds; populated via
     * Settings → Update channel in debug builds so a local server (exposed via
     * Cloudflare Tunnel or ngrok) can be tested without publishing a release.
     */
    var debugUpdateManifestUrl: String
        get() = preferences.getString("debug_update_manifest_url", "") ?: ""
        set(value) { preferences.edit().putString("debug_update_manifest_url", value).commit() }

    /** Custom manifest URL or proxy endpoint override for in-app updates. */
    var updateManifestUrlOverride: String
        get() = preferences.getString("update_manifest_url_override", "") ?: ""
        set(value) { preferences.edit().putString("update_manifest_url_override", value).commit() }

    /** Optional Personal Access Token / Bearer token for accessing private GitHub repository releases. */
    var updateAuthToken: String
        get() = preferences.getString("update_auth_token", "") ?: ""
        set(value) { preferences.edit().putString("update_auth_token", value).commit() }

    /** Development stacks the user picked during onboarding (names of DevStack). */
    var selectedDevStacks: Set<String>
        get() {
            val raw = preferences.getString("selected_dev_stacks", null) ?: return emptySet()
            return runCatching {
                val arr = JSONArray(raw)
                (0 until arr.length()).mapNotNull { arr.optString(it).takeIf(String::isNotBlank) }.toSet()
            }.getOrDefault(emptySet())
        }
        set(value) {
            val arr = JSONArray()
            value.sorted().forEach(arr::put)
            preferences.edit().putString("selected_dev_stacks", arr.toString()).commit()
        }

    var autoUpdateToolsAndSkills: Boolean
        get() = preferences.getBoolean("auto_update_tools_and_skills", false)
        set(value) { preferences.edit().putBoolean("auto_update_tools_and_skills", value).commit() }

    var lastToolsAutoUpdateCheckMillis: Long
        get() = preferences.getLong("last_tools_auto_update_check_millis", 0L)
        set(value) { preferences.edit().putLong("last_tools_auto_update_check_millis", value).commit() }

    var executionMode: String
        get() = preferences.getString("execution_mode", ExecutionMode.UNIFIED.name) ?: ExecutionMode.UNIFIED.name
        set(value) { preferences.edit().putString("execution_mode", value).commit() }

    var agentBrowserDriveEnabled: Boolean
        get() = preferences.getBoolean("agent_browser_drive_enabled", true)
        set(value) { preferences.edit().putBoolean("agent_browser_drive_enabled", value).commit() }


    fun saveProvider(profile: ProviderProfile, agent: AgentKind? = null) {
        val editor = preferences.edit()
            .putString("provider_kind", profile.kind.name)
            .putString("provider_base_url", profile.baseUrl)
            .putString("provider_model", profile.model)
            .putString("provider_dsh_api", profile.dshApi)
            .putString("provider_openrouter_order", profile.openRouterProviderOrder)
            .putBoolean("provider_openrouter_allow_fallbacks", profile.openRouterAllowFallbacks)
        if (agent != null) {
            val prefix = providerPrefix(agent)
            editor
                .putString("${prefix}kind", profile.kind.name)
                .putString("${prefix}base_url", profile.baseUrl)
                .putString("${prefix}model", profile.model)
                .putString("${prefix}dsh_api", profile.dshApi)
                .putString("${prefix}openrouter_order", profile.openRouterProviderOrder)
                .putBoolean("${prefix}openrouter_allow_fallbacks", profile.openRouterAllowFallbacks)
        }
        editor.commit()
    }

    fun loadProvider(vault: ApiKeyVault, agent: AgentKind? = null): ProviderProfile {
        val prefix = agent?.let(::providerPrefix)
        val hasAgentProfile = prefix != null && preferences.contains("${prefix}kind")
        val sourcePrefix = if (hasAgentProfile) prefix.orEmpty() else "provider_"
        val storedKind = runCatching {
            ProviderKind.valueOf(preferences.getString("${sourcePrefix}kind", null).orEmpty())
        }.getOrNull()
        val storedBaseUrl = storedKind?.let {
            preferences.getString("${sourcePrefix}base_url", it.defaultBaseUrl) ?: it.defaultBaseUrl
        }.orEmpty()
        val storedModel = storedKind?.let {
            preferences.getString("${sourcePrefix}model", it.defaultModel) ?: it.defaultModel
        }.orEmpty()
        // Older builds copied the global Claude/Anthropic default into a new
        // DeepSeek Harness profile. Treat that untouched, keyless placeholder
        // as unconfigured so DeepSeek opens on its own official provider.
        val legacyClaudeDefaultInDeepSeek = agent == AgentKind.DEEPSEEK_HARNESS &&
            storedKind == ProviderKind.ANTHROPIC &&
            !vault.contains(ProviderKind.ANTHROPIC.name) &&
            storedBaseUrl == ProviderKind.ANTHROPIC.defaultBaseUrl &&
            storedModel == ProviderKind.ANTHROPIC.defaultModel
        val kind = when {
            agent == null -> storedKind ?: ProviderKind.ANTHROPIC
            agent == AgentKind.DEEPSEEK_HARNESS && (!hasAgentProfile || legacyClaudeDefaultInDeepSeek) -> ProviderKind.DEEPSEEK
            storedKind != null && storedKind in providersForAgent(agent) -> storedKind
            agent == AgentKind.DEEPSEEK_HARNESS -> ProviderKind.DEEPSEEK
            agent == AgentKind.CODEX -> ProviderKind.OPENAI
            // Fresh Custom agent opens on the Custom API provider (bring-your-own endpoint).
            agent == AgentKind.CUSTOM -> ProviderKind.CUSTOM
            else -> ProviderKind.ANTHROPIC
        }
        val useStoredValues = storedKind == kind
        val savedModel = if (useStoredValues) {
            preferences.getString("${sourcePrefix}model", kind.defaultModel) ?: kind.defaultModel
        } else {
            kind.defaultModel
        }
        // DeepSeek retired its legacy alias. Migrate only DeepSeek profiles so
        // custom and gateway providers keep their independently selected model.
        val model = if (
            kind == ProviderKind.DEEPSEEK &&
            savedModel in setOf("deepseek-chat", "deepseek-reasoner")
        ) {
            kind.defaultModel.also {
                preferences.edit().putString("${sourcePrefix}model", it).commit()
            }
        } else {
            savedModel
        }
        return ProviderProfile(
            kind = kind,
            baseUrl = if (useStoredValues) {
                preferences.getString("${sourcePrefix}base_url", kind.defaultBaseUrl) ?: kind.defaultBaseUrl
            } else {
                kind.defaultBaseUrl
            },
            model = model,
            hasSecret = vault.contains(kind.name),
            dshApi = if (useStoredValues) {
                preferences.getString("${sourcePrefix}dsh_api", defaultDshApiForProvider(kind))
                    ?: defaultDshApiForProvider(kind)
            } else {
                defaultDshApiForProvider(kind)
            },
            openRouterProviderOrder = if (useStoredValues) {
                preferences.getString("${sourcePrefix}openrouter_order", "").orEmpty()
            } else {
                ""
            },
            openRouterAllowFallbacks = if (useStoredValues) {
                preferences.getBoolean("${sourcePrefix}openrouter_allow_fallbacks", true)
            } else {
                true
            },
        )
    }

    private fun providerPrefix(agent: AgentKind): String = "provider_${agent.stableId.replace('-', '_')}_"

    fun saveProjects(projects: List<Project>) {
        val arr = JSONArray()
        projects.forEach { p ->
            arr.put(JSONObject().apply {
                put("id", p.id)
                put("name", p.name)
                put("description", p.description)
                put("language", p.language)
                put("slug", p.slug)
                put("rootPath", p.rootPath)
                put("updatedAtMillis", p.updatedAtMillis)
                put("kind", p.kind.name)
            })
        }
        preferences.edit().putString("projects_json", arr.toString()).commit()
    }

    fun loadProjects(): List<Project> {
        val raw = preferences.getString("projects_json", null) ?: return emptyList()
        var needsSave = false
        val list = runCatching {
            val arr = JSONArray(raw)
            val usedSlugs = mutableSetOf<String>()
            (0 until arr.length()).map { i ->
                val obj = arr.getJSONObject(i)
                val id = obj.getString("id")
                val name = obj.getString("name")
                val storedKind = obj.optString("kind", ProjectKind.PROJECT.name)
                if (!obj.has("kind") || storedKind == "QUICK_CHAT") needsSave = true
                val requestedSlug = obj.optString("slug").ifBlank { projectSlug(name) }
                var slug = requestedSlug
                if (!usedSlugs.add(slug)) {
                    slug = "$requestedSlug-${id.take(6)}"
                    var suffix = 2
                    while (!usedSlugs.add(slug)) slug = "$requestedSlug-${suffix++}"
                }
                if (slug != obj.optString("slug")) needsSave = true
                var millis = obj.optLong("updatedAtMillis", 0L)
                if (millis <= 0L) {
                    val workspaceDir = File(context.filesDir, "workspaces/$id")
                    millis = if (workspaceDir.exists() && workspaceDir.lastModified() > 0L) {
                        workspaceDir.lastModified()
                    } else {
                        System.currentTimeMillis() - 3600_000L
                    }
                    needsSave = true
                }
                Project(
                    id = id,
                    name = name,
                    description = obj.optString("description", ""),
                    language = obj.optString("language", ""),
                    slug = slug,
                    rootPath = obj.optString("rootPath", "").takeIf { root ->
                        root.isBlank() || (!root.startsWith('/') && !root.contains(".."))
                    } ?: "",
                    updatedAtMillis = millis,
                    kind = when (storedKind) {
                        "QUICK_CHAT" -> ProjectKind.QUICK_PROJECT
                        else -> runCatching { ProjectKind.valueOf(storedKind) }
                            .getOrDefault(ProjectKind.PROJECT)
                    },
                )
            }
        }.getOrDefault(emptyList())

        if (needsSave && list.isNotEmpty()) {
            saveProjects(list)
        }
        return list
    }

    private val chatsDir = File(context.filesDir, "chats").also { it.mkdirs() }

    fun saveProjectChats(projectId: String, chats: List<ProjectChat>) {
        val projectDir = File(chatsDir, projectId).also { it.mkdirs() }
        val arr = JSONArray()
        chats.forEach { chat ->
            arr.put(JSONObject().apply {
                put("id", chat.id)
                put("title", chat.title)
                put("createdAtMillis", chat.createdAtMillis)
                put("updatedAtMillis", chat.updatedAtMillis)
            })
        }
        File(projectDir, "index.json").writeText(arr.toString())
    }

    fun loadProjectChats(projectId: String): List<ProjectChat> {
        val projectDir = File(chatsDir, projectId).also { it.mkdirs() }
        val index = File(projectDir, "index.json")
        if (index.exists()) {
            return runCatching {
                val arr = JSONArray(index.readText())
                (0 until arr.length()).map { i ->
                    val obj = arr.getJSONObject(i)
                    ProjectChat(
                        id = obj.getString("id"),
                        title = obj.optString("title", "Chat"),
                        createdAtMillis = obj.optLong("createdAtMillis", System.currentTimeMillis()),
                        updatedAtMillis = obj.optLong("updatedAtMillis", System.currentTimeMillis()),
                    )
                }.sortedByDescending { it.updatedAtMillis }
            }.getOrDefault(emptyList())
        }

        // Migrate the original one-file-per-project conversation without losing it.
        val legacy = File(chatsDir, "$projectId.json")
        val legacyMessages = loadLegacyMessages(legacy)
        val now = System.currentTimeMillis()
        val chat = ProjectChat(
            id = "main",
            title = legacyMessages.firstOrNull { it.fromUser }?.text?.toChatTitle() ?: "Main chat",
            createdAtMillis = now,
            updatedAtMillis = now,
        )
        saveProjectChats(projectId, listOf(chat))
        if (legacyMessages.isNotEmpty()) saveMessages(projectId, chat.id, legacyMessages)
        return listOf(chat)
    }

    @Synchronized
    fun saveMessages(projectId: String, chatId: String, messages: List<ChatMessage>) {
        // QA H28: persist only the newest MAX_TRANSCRIPT_MESSAGES. The old
        // code rewrote the ENTIRE unbounded transcript on every message
        // (O(n^2) total I/O, growing lag + battery/disk burn). The cap
        // bounds every write to O(1000). The in-memory chat is untouched —
        // the visible session keeps its full history; only the file on
        // disk is a newest-1000 retention window, so a relaunch shows the
        // last 1000 messages. This is a documented retention policy, not
        // silent trimming: see MAX_TRANSCRIPT_MESSAGES.
        val trimmed = trimTranscriptForPersist(messages)
        val arr = JSONArray()
        trimmed.forEach { m ->
            arr.put(JSONObject().apply {
                put("id", m.id)
                put("fromUser", m.fromUser)
                put("text", m.text)
                put("createdAt", m.createdAt.toString())
                put("attachments", JSONArray().apply {
                    m.attachments.forEach { attachment ->
                        put(JSONObject().apply {
                            put("id", attachment.id)
                            put("displayName", attachment.displayName)
                            put("relativePath", attachment.relativePath)
                            put("mimeType", attachment.mimeType)
                            put("sizeBytes", attachment.sizeBytes)
                        })
                    }
                })
                put("workedMillis", m.workedMillis)
                put("workItems", JSONArray().apply {
                    m.workItems.forEach { item ->
                        put(JSONObject().apply {
                            put("title", item.title)
                            put("detail", item.detail)
                            put("isComplete", item.isComplete)
                            put("isCommand", item.isCommand)
                        })
                    }
                })
                m.roadmap?.let { r ->
                    put("roadmap", JSONObject().apply {
                        put("id", r.id)
                        put("title", r.title)
                        put("summary", r.summary)
                        put("rawMarkdown", r.rawMarkdown)
                        put("isApproved", r.isApproved)
                        put("mode", r.mode.name)
                        put("createdAtMillis", r.createdAtMillis)
                        put("steps", JSONArray().apply {
                            r.steps.forEach { s ->
                                put(JSONObject().apply {
                                    put("id", s.id)
                                    put("title", s.title)
                                    put("description", s.description)
                                    put("status", s.status.name)
                                    put("filesAffected", JSONArray(s.filesAffected))
                                })
                            }
                        })
                        put("comments", JSONArray().apply {
                            r.comments.forEach { c ->
                                put(JSONObject().apply {
                                    put("id", c.id)
                                    put("author", c.author)
                                    put("text", c.text)
                                    put("timestampMillis", c.timestampMillis)
                                    c.stepId?.let { put("stepId", it) }
                                })
                            }
                        })
                    })
                }
            })
        }
        val projectDir = File(chatsDir, projectId).also { it.mkdirs() }
        val destination = File(projectDir, "$chatId.json")
        val temporary = File(projectDir, ".$chatId.json.tmp")
        temporary.writeText(arr.toString())
        if (!temporary.renameTo(destination)) {
            temporary.copyTo(destination, overwrite = true)
            temporary.delete()
        }
    }

    fun loadMessages(projectId: String, chatId: String): List<ChatMessage> {
        val file = File(File(chatsDir, projectId), "$chatId.json")
        return loadLegacyMessages(file)
    }

    fun deleteProjectChats(projectId: String) {
        File(chatsDir, projectId).deleteRecursively()
        File(chatsDir, "$projectId.json").delete()
    }

    var lastActiveProjectId: String?
        get() = preferences.getString("last_active_project_id", null)?.takeIf { it.isNotBlank() }
        set(value) { preferences.edit().putString("last_active_project_id", value).commit() }

    fun getLastActiveChatId(projectId: String): String? {
        return preferences.getString("last_active_chat_id_$projectId", null)?.takeIf { it.isNotBlank() }
    }

    fun setLastActiveChatId(projectId: String, chatId: String?) {
        preferences.edit().putString("last_active_chat_id_$projectId", chatId).commit()
    }

    fun renameProjectChat(projectId: String, chatId: String, newTitle: String) {
        val currentChats = loadProjectChats(projectId).map {
            if (it.id == chatId) it.copy(title = newTitle, updatedAtMillis = System.currentTimeMillis())
            else it
        }
        saveProjectChats(projectId, currentChats)
    }

    fun deleteProjectChat(projectId: String, chatId: String) {
        val projectDir = File(chatsDir, projectId)
        File(projectDir, "$chatId.json").delete()
        File(projectDir, ".$chatId.json.tmp").delete()
        val currentChats = loadProjectChats(projectId).filterNot { it.id == chatId }
        saveProjectChats(projectId, currentChats)
        if (getLastActiveChatId(projectId) == chatId) {
            setLastActiveChatId(projectId, currentChats.firstOrNull()?.id)
        }
    }

    private fun loadLegacyMessages(file: File): List<ChatMessage> {
        if (!file.exists()) return emptyList()
        return runCatching {
            val arr = JSONArray(file.readText())
            (0 until arr.length()).map { i ->
                val obj = arr.getJSONObject(i)
                ChatMessage(
                    id = obj.getString("id"),
                    fromUser = obj.getBoolean("fromUser"),
                    text = obj.getString("text"),
                    createdAt = runCatching { Instant.parse(obj.getString("createdAt")) }
                        .getOrDefault(Instant.now()),
                    attachments = obj.optJSONArray("attachments")?.let { attachments ->
                        (0 until attachments.length()).mapNotNull { index ->
                            runCatching {
                                attachments.getJSONObject(index).let { attachment ->
                                    ChatAttachment(
                                        id = attachment.optString("id").ifBlank { java.util.UUID.randomUUID().toString() },
                                        displayName = attachment.getString("displayName"),
                                        relativePath = attachment.getString("relativePath"),
                                        mimeType = attachment.optString("mimeType", "application/octet-stream"),
                                        sizeBytes = attachment.optLong("sizeBytes", 0L),
                                    )
                                }
                            }.getOrNull()
                        }
                    }.orEmpty(),
                    workedMillis = obj.optLong("workedMillis", 0L),
                    workItems = obj.optJSONArray("workItems")?.let { workItems ->
                        (0 until workItems.length()).mapNotNull { index ->
                            runCatching {
                                workItems.getJSONObject(index).let { item ->
                                    com.vayro.app.model.ActivityItem(
                                        title = item.optString("title"),
                                        detail = item.optString("detail"),
                                        isComplete = item.optBoolean("isComplete", true),
                                        isCommand = item.optBoolean("isCommand", false),
                                    )
                                }
                            }.getOrNull()
                        }
                    }.orEmpty(),
                    roadmap = obj.optJSONObject("roadmap")?.let { r ->
                        ActiveRoadmap(
                            id = r.optString("id", java.util.UUID.randomUUID().toString()),
                            title = r.optString("title", "Implementation Roadmap"),
                            summary = r.optString("summary", ""),
                            rawMarkdown = r.optString("rawMarkdown", ""),
                            isApproved = r.optBoolean("isApproved", false),
                            mode = runCatching { ExecutionMode.valueOf(r.optString("mode", ExecutionMode.UNIFIED.name)) }.getOrDefault(ExecutionMode.UNIFIED),
                            createdAtMillis = r.optLong("createdAtMillis", System.currentTimeMillis()),
                            steps = r.optJSONArray("steps")?.let { stepsArr ->
                                (0 until stepsArr.length()).mapNotNull { sIdx ->
                                    runCatching {
                                        val sObj = stepsArr.getJSONObject(sIdx)
                                        RoadmapStep(
                                            id = sObj.optString("id", java.util.UUID.randomUUID().toString()),
                                            title = sObj.getString("title"),
                                            description = sObj.optString("description", ""),
                                            status = runCatching { StepStatus.valueOf(sObj.optString("status", StepStatus.PENDING.name)) }.getOrDefault(StepStatus.PENDING),
                                            filesAffected = sObj.optJSONArray("filesAffected")?.let { fArr ->
                                                (0 until fArr.length()).map { fArr.getString(it) }
                                            } ?: emptyList(),
                                        )
                                    }.getOrNull()
                                }
                            } ?: emptyList(),
                            comments = r.optJSONArray("comments")?.let { commentsArr ->
                                (0 until commentsArr.length()).mapNotNull { cIdx ->
                                    runCatching {
                                        val cObj = commentsArr.getJSONObject(cIdx)
                                        RoadmapComment(
                                            id = cObj.optString("id", java.util.UUID.randomUUID().toString()),
                                            author = cObj.optString("author", "User"),
                                            text = cObj.getString("text"),
                                            timestampMillis = cObj.optLong("timestampMillis", System.currentTimeMillis()),
                                            stepId = cObj.optString("stepId").takeIf(String::isNotBlank),
                                        )
                                    }.getOrNull()
                                }
                            } ?: emptyList(),
                        )
                    } ?: if (!obj.optBoolean("fromUser", false) && obj.optString("text").isNotBlank()) {
                        com.vayro.app.ui.RoadmapParser.parseFromText(
                            obj.optString("text"),
                            runCatching { ExecutionMode.valueOf(preferences.getString("execution_mode", ExecutionMode.UNIFIED.name) ?: ExecutionMode.UNIFIED.name) }.getOrDefault(ExecutionMode.UNIFIED),
                        )
                    } else null,
                )
            }
        // Crash fix (2026-10-13): a persisted transcript could contain two messages
        // with the same id (two "interrupted-<sessionId>" rows). The chat
        // LazyColumn keys by message id, and a duplicate key throws
        // IllegalArgumentException and kills the app on every launch. Drop
        // duplicates here (keep first) so a poisoned transcript heals on load.
        }.getOrDefault(emptyList()).distinctBy { it.id }
    }

    fun getOpenEditorTabs(projectId: String): List<String> {
        val raw = preferences.getString("open_editor_tabs_$projectId", null) ?: return emptyList()
        return runCatching {
            val arr = org.json.JSONArray(raw)
            (0 until arr.length()).map { arr.getString(it) }
        }.getOrDefault(emptyList())
    }

    fun setOpenEditorTabs(projectId: String, tabs: List<String>) {
        val arr = org.json.JSONArray()
        tabs.forEach { arr.put(it) }
        preferences.edit().putString("open_editor_tabs_$projectId", arr.toString()).commit()
    }

    private fun String.toChatTitle(): String {
        val clean = replace(Regex("\\s+"), " ").trim()
        return if (clean.length <= 42) clean else clean.take(39).trimEnd() + "…"
    }
}

/**
 * QA H28: maximum chat messages persisted per transcript file. Older
 * messages beyond this window are not written to disk (the in-memory chat
 * keeps its full history for the running session). Bounds every
 * transcript write to O([MAX_TRANSCRIPT_MESSAGES]) instead of O(n^2)
 * total I/O over an unbounded transcript.
 */
const val MAX_TRANSCRIPT_MESSAGES = 1000

/**
 * QA H28: the retention window applied by [AppPreferences.saveMessages].
 * Pure function (no Android Context) so it is unit-tested directly.
 */
internal fun trimTranscriptForPersist(messages: List<ChatMessage>): List<ChatMessage> =
    if (messages.size <= MAX_TRANSCRIPT_MESSAGES) messages else messages.takeLast(MAX_TRANSCRIPT_MESSAGES)
