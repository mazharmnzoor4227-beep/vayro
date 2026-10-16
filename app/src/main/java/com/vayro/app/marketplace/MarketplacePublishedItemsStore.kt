package com.vayro.app.marketplace

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Durable store for items the user publishes from the Marketplace screen.
 *
 * Published items used to live only in the catalog's in-memory list and
 * vanished on restart. They are now persisted as JSON in SharedPreferences
 * and reloaded into the catalog when the marketplace coordinator starts.
 *
 * This store is local-only: no registry is configured in the app, so a
 * published item is saved on this device, not shared with other devices
 * (the publish dialog says so). If a registry is configured in future,
 * publishing to it is a separate step on top of this store — not a
 * replacement for it.
 */
class MarketplacePublishedItemsStore(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun load(): List<MarketplaceItem> {
        val raw = prefs.getString(KEY, null) ?: return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { i ->
                runCatching { fromJson(arr.getJSONObject(i)) }.getOrNull()
            }
        }.getOrDefault(emptyList())
    }

    fun save(items: List<MarketplaceItem>) {
        val arr = JSONArray()
        items.forEach { arr.put(toJson(it)) }
        prefs.edit().putString(KEY, arr.toString()).apply()
    }

    fun add(item: MarketplaceItem) {
        save(load().filter { it.id != item.id } + item)
    }

    fun remove(itemId: String) {
        save(load().filter { it.id != itemId })
    }

    private fun toJson(item: MarketplaceItem): JSONObject = JSONObject()
        .put("id", item.id)
        .put("name", item.name)
        .put("category", item.category.name)
        .put("author", item.author)
        .put("description", item.description)
        .put("rating", item.rating)
        .put("downloads", item.downloads)
        .put("installUrl", item.installUrl)
        .put("isCustom", item.isCustom)
        .put("tags", JSONArray(item.tags))
        .put("sha256", item.sha256)

    private fun fromJson(json: JSONObject): MarketplaceItem = MarketplaceItem(
        id = json.getString("id"),
        name = json.optString("name"),
        category = runCatching {
            MarketplaceCategory.valueOf(json.optString("category", "SKILLS"))
        }.getOrDefault(MarketplaceCategory.SKILLS),
        author = json.optString("author"),
        description = json.optString("description"),
        rating = json.optDouble("rating", 0.0),
        downloads = json.optInt("downloads", 0),
        installUrl = json.optString("installUrl"),
        isCustom = json.optBoolean("isCustom", true),
        sha256 = json.optString("sha256"),
        tags = mutableListOf<String>().also { tags ->
            val arr = json.optJSONArray("tags")
            if (arr != null) {
                for (i in 0 until arr.length()) tags.add(arr.optString(i))
            }
        }
    )

    companion object {
        private const val PREFS_NAME = "marketplace_published_items"
        private const val KEY = "published_items_json"
    }
}
