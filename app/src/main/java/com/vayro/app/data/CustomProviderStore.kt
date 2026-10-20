package com.vayro.app.data

import android.content.Context
import com.vayro.app.model.CustomProviderConfig
import com.vayro.app.model.customProviderFromJson
import com.vayro.app.model.toJson
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray

/**
 * Persists the user's custom API provider configurations.
 *
 * Each config gets its own API-key pool in [ApiKeyVault] under the vault id
 * "CUSTOM_<configId>", so [ApiKeyFailoverCoordinator] multi-key rotation works
 * per custom provider exactly like it does for DeepSeek and NVIDIA.
 */
class CustomProviderStore(context: Context) {
    private val prefs = context.getSharedPreferences("custom_providers", Context.MODE_PRIVATE)
    private val mutableConfigs = MutableStateFlow(loadAll())
    val configs: StateFlow<List<CustomProviderConfig>> = mutableConfigs.asStateFlow()

    /** Vault id for a custom provider config's key pool. */
    fun vaultIdFor(configId: String): String = "CUSTOM_$configId"

    fun getAll(): List<CustomProviderConfig> = mutableConfigs.value


    fun save(config: CustomProviderConfig) {
        val current = mutableConfigs.value.toMutableList()
        val index = current.indexOfFirst { it.id == config.id }
        if (index >= 0) current[index] = config else current.add(config)
        persist(current)
    }


    fun remove(vault: ApiKeyVault, id: String) {
        vault.remove(vaultIdFor(id))
        val remaining = mutableConfigs.value.filterNot { it.id == id }
        // Keep exactly one active when the active one is removed.
        val fixed = if (remaining.none { it.isActive } && remaining.isNotEmpty()) {
            remaining.mapIndexed { index, cfg -> cfg.copy(isActive = index == 0) }
        } else remaining
        persist(fixed)
    }


    private fun loadAll(): List<CustomProviderConfig> {
        val raw = prefs.getString("configs_json", "[]") ?: "[]"
        return runCatching {
            val array = JSONArray(raw)
            (0 until array.length()).mapNotNull { i ->
                runCatching { customProviderFromJson(array.getJSONObject(i)) }.getOrNull()
            }
        }.getOrDefault(emptyList())
    }

    private fun persist(configs: List<CustomProviderConfig>) {
        val array = JSONArray()
        configs.forEach { array.put(it.toJson()) }
        prefs.edit().putString("configs_json", array.toString()).commit()
        mutableConfigs.value = configs
    }

}
