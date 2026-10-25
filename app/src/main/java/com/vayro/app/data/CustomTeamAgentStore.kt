package com.vayro.app.data

import android.content.Context
import com.vayro.app.agent.team.model.BuiltinAgentOverride
import com.vayro.app.agent.team.model.CustomTeamAgent
import com.vayro.app.agent.team.model.TeamRole
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray

class CustomTeamAgentStore(context: Context) {
    private val prefs = context.getSharedPreferences("custom_team_agents", Context.MODE_PRIVATE)

    private val mutableAgents = MutableStateFlow(loadAgents())
    val agents: StateFlow<List<CustomTeamAgent>> = mutableAgents.asStateFlow()

    private val mutableOverrides = MutableStateFlow(loadOverrides())
    val overrides: StateFlow<Map<TeamRole, BuiltinAgentOverride>> = mutableOverrides.asStateFlow()

    fun getAll(): List<CustomTeamAgent> = mutableAgents.value

    fun getById(id: String): CustomTeamAgent? = mutableAgents.value.firstOrNull { it.id == id }

    fun getEnabledForProject(projectId: String?): List<CustomTeamAgent> =
        mutableAgents.value.filter { agent ->
            agent.isEnabled && (projectId == null ||
                agent.boundProjectIds.isEmpty() ||
                agent.boundProjectIds.contains(projectId))
        }

    fun save(agent: CustomTeamAgent) {
        val current = mutableAgents.value.toMutableList()
        val index = current.indexOfFirst { it.id == agent.id }
        if (index >= 0) current[index] = agent else current.add(agent)
        persistAgents(current)
    }

    fun remove(id: String) {
        persistAgents(mutableAgents.value.filterNot { it.id == id })
    }

    fun setEnabled(id: String, enabled: Boolean) {
        getById(id)?.let { save(it.copy(isEnabled = enabled)) }
    }

    fun getOverride(role: TeamRole): BuiltinAgentOverride? = mutableOverrides.value[role]

    fun saveOverride(override: BuiltinAgentOverride) {
        val current = mutableOverrides.value.toMutableMap()
        current[override.role] = override
        persistOverrides(current)
    }

    fun clearOverride(role: TeamRole) {
        val current = mutableOverrides.value.toMutableMap()
        current.remove(role)
        persistOverrides(current)
    }

    fun hasOverride(role: TeamRole): Boolean = mutableOverrides.value.containsKey(role)

    private fun loadAgents(): List<CustomTeamAgent> {
        val raw = prefs.getString(KEY_AGENTS, null) ?: return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            List(arr.length()) { i -> CustomTeamAgent.fromJson(arr.getJSONObject(i)) }
        }.getOrDefault(emptyList())
    }

    private fun persistAgents(agents: List<CustomTeamAgent>) {
        val arr = JSONArray()
        agents.forEach { arr.put(it.toJson()) }
        prefs.edit().putString(KEY_AGENTS, arr.toString()).apply()
        mutableAgents.value = agents
    }

    private fun loadOverrides(): Map<TeamRole, BuiltinAgentOverride> {
        val raw = prefs.getString(KEY_OVERRIDES, null) ?: return emptyMap()
        return runCatching {
            val arr = JSONArray(raw)
            List(arr.length()) { i -> BuiltinAgentOverride.fromJson(arr.getJSONObject(i)) }
                .associateBy { it.role }
        }.getOrDefault(emptyMap())
    }

    private fun persistOverrides(overrides: Map<TeamRole, BuiltinAgentOverride>) {
        val arr = JSONArray()
        overrides.values.forEach { arr.put(it.toJson()) }
        prefs.edit().putString(KEY_OVERRIDES, arr.toString()).apply()
        mutableOverrides.value = overrides
    }

    companion object {
        private const val KEY_AGENTS = "agents_json"
        private const val KEY_OVERRIDES = "overrides_json"
    }
}