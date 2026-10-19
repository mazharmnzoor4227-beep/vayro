package com.vayro.app.data

import android.content.Context
import org.json.JSONObject

/**
 * v3.2.37 (Part C): persistent per-key health for the auto-switch system.
 *
 * The session-scoped [ApiKeyFailoverCoordinator] decides rotation while a
 * task runs; this store remembers WHICH key was exhausted and WHEN across
 * restarts, so the Providers screen can show "Key 2 · limit reached 14:02,
 * resting" and the home-chat engine can deprioritise a key that is still
 * inside its cooldown ([ApiKeyFailoverCoordinator.QUOTA_COOLDOWN_MS]) —
 * resting keys are tried last, never hard-skipped, so chat still answers
 * when every key is resting.
 *
 * Only timestamps are stored — never key material (that stays in the
 * encrypted [ApiKeyVault]).
 */
class KeyHealthStore(context: Context) {
    private val preferences = context.getSharedPreferences("kodex_key_health", Context.MODE_PRIVATE)

    private fun read(providerId: String): JSONObject =
        runCatching { JSONObject(preferences.getString(providerId, null) ?: "{}") }.getOrDefault(JSONObject())

    /** Records that [keyId] hit a quota/rate limit just now. (Auth-rejected keys are not marked — v3.2.38.) */
    fun markExhausted(providerId: String, keyId: String) {
        val map = read(providerId)
        map.put(keyId, System.currentTimeMillis())
        preferences.edit().putString(providerId, map.toString()).apply()
    }

    /** Clears the exhausted mark (key worked again, or was removed/replaced). */
    fun clear(providerId: String, keyId: String) {
        val map = read(providerId)
        if (map.has(keyId)) {
            map.remove(keyId)
            preferences.edit().putString(providerId, map.toString()).apply()
        }
    }

    /** When [keyId] was last marked exhausted, or null if it is healthy. */
    fun exhaustedAt(providerId: String, keyId: String): Long? {
        val map = read(providerId)
        return if (map.has(keyId)) map.optLong(keyId).takeIf { it > 0L } else null
    }

    /** All exhausted marks for a provider: keyId -> exhaustedAt millis. */
    fun snapshot(providerId: String): Map<String, Long> {
        val map = read(providerId)
        val result = mutableMapOf<String, Long>()
        val keys = map.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            val at = map.optLong(key)
            if (at > 0L) result[key] = at
        }
        return result
    }

    /** True while [keyId] is still inside the shared quota cooldown window. */
    fun inCooldown(providerId: String, keyId: String, nowMs: Long = System.currentTimeMillis()): Boolean {
        val at = exhaustedAt(providerId, keyId) ?: return false
        return nowMs - at < ApiKeyFailoverCoordinator.QUOTA_COOLDOWN_MS
    }
}
